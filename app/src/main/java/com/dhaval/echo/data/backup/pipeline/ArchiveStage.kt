package com.dhaval.echo.data.backup.pipeline

import com.dhaval.echo.data.backup.ArchiveWriter
import com.dhaval.echo.data.backup.BackupCrypto
import com.dhaval.echo.data.backup.BackupManifest
import com.dhaval.echo.data.backup.OperationJournal
import com.dhaval.echo.data.backup.SettingsSnapshotter
import com.dhaval.echo.data.backup.StorageProvider
import com.dhaval.echo.domain.backup.BackupFailure
import com.dhaval.echo.domain.backup.BackupProgress
import com.dhaval.echo.domain.backup.BackupStage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import java.util.zip.Deflater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the archive: manifest, database, settings, media, checksums.
 *
 * Encryption happens here rather than in a stage of its own, because it isn't
 * one — [ArchiveWriter] is handed a stream that seals whatever passes through
 * it, so the bytes are encrypted as they are produced rather than afterwards.
 * A 2.8 GB archive has nowhere to sit in between.
 */
@Singleton
class ArchiveStage @Inject constructor(
    private val storage: StorageProvider,
    private val settingsSnapshotter: SettingsSnapshotter,
    private val journal: OperationJournal
) : BackupStageStep {

    override val stage = BackupStage.DATABASE

    override suspend fun run(context: BackupContext): StageResult {
        val manifest = context.manifest ?: return StageResult.Failed(
            BackupFailure.Unexpected(IllegalStateException("No manifest to write"))
        )
        val databaseFile = context.databaseFile ?: return StageResult.Failed(
            BackupFailure.Unexpected(IllegalStateException("No database snapshot"))
        )

        val sink = try {
            storage.openWrite(context.fileName)
        } catch (e: Exception) {
            return StageResult.Failed(BackupFailure.DestinationUnavailable(e.message))
        }
        context.sink = sink

        // Media is stored uncompressed because it is already compressed, so the
        // archive lands within a few percent of its sources. Asking the volume
        // now — through the descriptor of the file we just created, which is the
        // only way to measure a SAF destination — beats finding out at 94%.
        val required = ((databaseFile.length() + context.mediaBytes) * 1.05).toLong() + HEADROOM_BYTES
        sink.freeBytes()?.let { free ->
            if (free < required) {
                return StageResult.Failed(BackupFailure.OutOfSpace(required, free))
            }
        }

        journal.beginBackup(sink.uri)

        val kdf = context.password?.let { BackupCrypto.newKdfParams() }
        val key = context.password?.let { password ->
            kdf?.let { BackupCrypto.deriveKey(password, it) }
        }

        val settingsJson = BackupManifest.json.encodeToString(
            settingsSnapshotter.capture(includeApiKeys = context.password != null)
        )

        ArchiveWriter.open(sink.stream, context.startedAt, key, kdf).use { writer ->
            writer.putManifest(manifest)

            context.onProgress(BackupProgress(BackupStage.DATABASE))
            writer.putFile(BackupManifest.ENTRY_DATABASE, databaseFile, Deflater.BEST_SPEED)
            writer.putBytes(BackupManifest.ENTRY_SETTINGS, settingsJson.toByteArray())

            if (context.media.isNotEmpty()) {
                writeMedia(context, writer)
            }
            writer.finish()
        }
        sink.close()

        context.archiveSizeBytes = storage.sizeOf(sink.uri) ?: 0L
        return StageResult.Continue
    }

    private suspend fun writeMedia(context: BackupContext, writer: ArchiveWriter) {
        var done = 0
        var bytes = 0L
        context.onProgress(
            BackupProgress(
                stage = BackupStage.MEDIA,
                filesTotal = context.media.size,
                bytesTotal = context.mediaBytes
            )
        )
        for (item in context.media) {
            currentCoroutineContext().ensureActive()
            // A file that vanished between enumeration and now is a user
            // deleting a memory mid-backup, not an error.
            if (!item.file.exists()) continue

            writer.putFile(item.entryPath, item.file, Deflater.NO_COMPRESSION) { delta ->
                bytes += delta
            }
            done++
            context.onProgress(
                BackupProgress(
                    stage = BackupStage.MEDIA,
                    filesDone = done,
                    filesTotal = context.media.size,
                    bytesDone = bytes,
                    bytesTotal = context.mediaBytes
                )
            )
        }
    }

    private companion object {
        /** Slack for the ZIP central directory and manifest, above source bytes. */
        const val HEADROOM_BYTES = 8L * 1024 * 1024
    }
}
