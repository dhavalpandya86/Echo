package com.dhaval.echo.data.backup

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Archives in a folder the user chose, reached through the Storage Access
 * Framework.
 *
 * SAF rather than a hardcoded `Documents/Echo/Backups` because scoped storage
 * forbids writing there without the user granting it, and because a
 * user-selected tree can be internal storage, an SD card, or a USB drive
 * without this class knowing the difference.
 *
 * The important consequence, and the reason Echo cannot offer WhatsApp's
 * "we found a backup" prompt after a reinstall: **the grant does not survive
 * uninstall.** The archives do — they are in shared storage and Android does not
 * touch them — but a freshly installed Echo holds no permission to look
 * anywhere, so it cannot discover them unprompted. Restore therefore begins
 * with the user pointing at the file.
 */
@Singleton
class SafStorageProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localState: BackupLocalState
) : StorageProvider {

    override suspend fun describe(): String? = withContext(Dispatchers.IO) {
        localState.destinationLabel ?: treeDocument()?.name
    }

    override suspend fun isWritable(): Boolean = withContext(Dispatchers.IO) {
        val tree = treeDocument()
        tree != null && tree.exists() && tree.canWrite()
    }

    override suspend fun list(): List<StoredArchive> = withContext(Dispatchers.IO) {
        val tree = treeDocument() ?: return@withContext emptyList()
        runCatching {
            tree.listFiles()
                .filter { it.isFile && it.name?.endsWith(".${BackupManifest.FILE_EXTENSION}") == true }
                .map {
                    StoredArchive(
                        uri = it.uri.toString(),
                        name = it.name.orEmpty(),
                        sizeBytes = it.length(),
                        lastModifiedMillis = it.lastModified()
                    )
                }
                .sortedByDescending { it.lastModifiedMillis }
        }.getOrElse {
            Log.w(TAG, "Could not list backup destination", it)
            emptyList()
        }
    }

    override suspend fun openWrite(fileName: String): ArchiveSink = withContext(Dispatchers.IO) {
        val tree = treeDocument()
            ?: throw IOException("No backup folder selected")
        if (!tree.exists() || !tree.canWrite()) {
            throw IOException("Backup folder is unavailable")
        }

        // A MIME type MimeTypeMap has no extension for, so the provider keeps the
        // name verbatim. With "application/octet-stream" it helpfully appends
        // ".bin", turning Echo_2026-08-03.echo into Echo_2026-08-03.echo.bin.
        val document = tree.createFile(MIME_TYPE, fileName)
            ?: throw IOException("Could not create $fileName in the backup folder")

        val descriptor = context.contentResolver.openFileDescriptor(document.uri, "w")
            ?: run {
                document.delete()
                throw IOException("Could not open $fileName for writing")
            }

        SafArchiveSink(document.uri.toString(), descriptor)
    }

    override suspend fun openRead(uri: String): InputStream = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(uri))
            ?: throw FileNotFoundException("Could not open backup: $uri")
    }

    override suspend fun delete(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() == true }
            .getOrElse {
                Log.w(TAG, "Could not delete $uri", it)
                false
            }
    }

    override suspend fun sizeOf(uri: String): Long? = withContext(Dispatchers.IO) {
        runCatching { DocumentFile.fromSingleUri(context, Uri.parse(uri))?.length() }.getOrNull()
    }

    private fun treeDocument(): DocumentFile? {
        val uri = localState.destinationUri ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrElse {
            Log.w(TAG, "Backup destination could not be resolved", it)
            null
        }
    }

    /**
     * Writes through the descriptor rather than a plain stream so free space can
     * be measured on the very volume the file lives on — which for SAF is the
     * only way to know, since the tree URI carries no path we could `StatFs`.
     */
    private class SafArchiveSink(
        override val uri: String,
        private val descriptor: ParcelFileDescriptor
    ) : ArchiveSink {

        override val stream: OutputStream =
            BufferedOutputStream(
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor),
                BUFFER_BYTES
            )

        override fun freeBytes(): Long? = runCatching {
            val stats = Os.fstatvfs(descriptor.fileDescriptor)
            stats.f_bavail * stats.f_frsize
        }.getOrNull()

        override fun close() {
            runCatching { stream.close() }
        }
    }

    private companion object {
        const val TAG = "SafStorage"
        const val MIME_TYPE = "application/x-echo-backup"
        const val BUFFER_BYTES = 128 * 1024
    }
}
