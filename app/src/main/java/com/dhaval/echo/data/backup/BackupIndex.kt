package com.dhaval.echo.data.backup

import android.util.Log
import com.dhaval.echo.domain.backup.BackupSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A cached description of every archive in the destination folder.
 *
 * ### Why a cache exists
 *
 * Drawing the history screen from the folder itself means a `DocumentFile`
 * query per file plus a header read per archive. At five backups that is
 * invisible; at five hundred it is seconds of blank screen every time someone
 * opens the page.
 *
 * ### Why it is not in `echo_db`
 *
 * This is the part that would be a bug rather than a slowdown. A restore
 * *replaces* `echo_db`. History kept there would be swapped for whatever
 * history the archive happened to contain — so after restoring, the app would
 * show the backup list of some other moment in time, and the record of the
 * restore that had just happened would be gone. It is also excluded from
 * backups for the same reason: it describes one folder on one device.
 *
 * ### Why a file rather than a second Room database
 *
 * Deviation from the plan, deliberately: five hundred rows of metadata is about
 * a hundred kilobytes, and it is a *cache* — the folder is the source of truth
 * and [refresh] rebuilds it from scratch. An entity, a DAO, a `@Database`, a
 * migration policy and a DI module to hold a disposable file would be
 * machinery with nothing to show for it. Deleting this file costs one rescan.
 */
@Singleton
class BackupIndex @Inject constructor(
    private val roots: EchoStorageRoots,
    private val storage: StorageProvider
) {
    private val file: File get() = File(roots.internalFiles, FILE_NAME)
    private val mutex = Mutex()

    suspend fun entries(): List<BackupIndexEntry> = withContext(Dispatchers.IO) {
        read().sortedByDescending { it.createdAtMillis }
    }

    /** Called after a successful backup, when everything about it is known. */
    suspend fun record(uri: String, summary: BackupSummary) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = BackupIndexEntry(
                uri = uri,
                fileName = summary.header.fileName,
                createdAtMillis = summary.header.createdAtMillis,
                sizeBytes = summary.header.sizeBytes,
                encrypted = summary.header.encrypted,
                pool = ArchiveNaming.Pool.of(summary.profile, summary.reason).tag,
                reason = summary.reason.name,
                reasonDetail = summary.reasonDetail,
                name = summary.name,
                note = summary.note,
                passwordEpoch = summary.passwordEpoch,
                includesMedia = summary.includesMedia,
                memories = summary.counts.memories,
                recordings = summary.counts.recordings,
                photos = summary.counts.photos,
                detailsKnown = true
            )
            write(read().filterNot { it.uri == uri } + entry)
        }
    }

    suspend fun remove(uri: String) = withContext(Dispatchers.IO) {
        mutex.withLock { write(read().filterNot { it.uri == uri }) }
    }

    /**
     * Reconciles the cache with the folder.
     *
     * Archives the index has never seen — copied in by hand, written by another
     * device, or made before the index existed — are described from their
     * plaintext envelope header alone. That yields date, size and whether they
     * are encrypted, which is enough to list them; the counts stay unknown until
     * someone opens the archive, because for an encrypted one they are behind
     * the password, and prompting for it merely to draw a list would be absurd.
     */
    suspend fun refresh(): List<BackupIndexEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val archives = runCatching { storage.list() }.getOrElse {
                Log.w(TAG, "Could not list destination; keeping cached index", it)
                return@withLock read()
            }
            val known = read().associateBy { it.uri }
            val rebuilt = archives.map { archive ->
                known[archive.uri]?.copy(sizeBytes = archive.sizeBytes)
                    ?: describeFromHeader(archive)
            }
            write(rebuilt)
            rebuilt
        }
    }

    private suspend fun describeFromHeader(archive: StoredArchive): BackupIndexEntry {
        val parsed = ArchiveNaming.parse(archive.name)
        val envelope = runCatching {
            ArchiveReader.readHeaderOnly(storage.openRead(archive.uri))
        }.getOrNull()

        return BackupIndexEntry(
            uri = archive.uri,
            fileName = archive.name,
            // The header's timestamp is when the backup was taken; the file's is
            // when it last landed on this volume, which for a copied archive is
            // a different and less useful thing.
            createdAtMillis = envelope?.createdAtMillis ?: archive.lastModifiedMillis,
            sizeBytes = archive.sizeBytes,
            encrypted = envelope?.encrypted ?: false,
            pool = parsed?.pool?.tag ?: ArchiveNaming.Pool.FULL.tag,
            reason = "",
            name = if (parsed?.isNamed == true) archive.name else null,
            detailsKnown = false
        )
    }

    private fun read(): List<BackupIndexEntry> = runCatching {
        if (!file.exists()) emptyList()
        else BackupManifest.json.decodeFromString<List<BackupIndexEntry>>(file.readText())
    }.getOrElse {
        // A cache that cannot be read is a cache to rebuild, never a failure.
        Log.w(TAG, "Discarding unreadable backup index", it)
        emptyList()
    }

    private fun write(entries: List<BackupIndexEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(BackupManifest.json.encodeToString(entries))
        }.onFailure { Log.w(TAG, "Could not write backup index", it) }
    }

    private companion object {
        const val TAG = "BackupIndex"
        const val FILE_NAME = "backup_index.json"
    }
}

/**
 * @property detailsKnown false for an archive described from its header alone.
 *   The UI shows what it has and doesn't invent the rest.
 */
@Serializable
data class BackupIndexEntry(
    val uri: String,
    val fileName: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val pool: String,
    val reason: String,
    val reasonDetail: String? = null,
    val name: String? = null,
    val note: String? = null,
    val passwordEpoch: Int = 0,
    val includesMedia: Boolean = true,
    val memories: Int = 0,
    val recordings: Int = 0,
    val photos: Int = 0,
    val detailsKnown: Boolean = false
)
