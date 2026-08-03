package com.dhaval.echo.data.backup

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.Room
import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.backup.BackupFailure
import com.dhaval.echo.domain.backup.BackupHeaderInfo
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupReason
import com.dhaval.echo.domain.backup.MediaConsistency
import com.dhaval.echo.domain.backup.RestoreOutcome
import com.dhaval.echo.domain.backup.RestorePlan
import com.dhaval.echo.domain.backup.RestoreProgress
import com.dhaval.echo.domain.backup.RestoreStage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Restores an archive, in two halves separated by the user's consent.
 *
 * [stage] does **all** the work — unpack, verify, migrate, rewrite paths,
 * re-key the account, check the media is really there — in scratch directories
 * that sit beside the real ones. [commit] then swaps them, which is a rename.
 *
 * This ordering is the whole design. The alternative, unpacking over live data
 * and fixing it up as you go, means a migration that fails, a checksum that
 * doesn't match, or a phone that dies at the wrong moment leaves someone with
 * neither their old diary nor their new one. Here, everything that can fail has
 * already succeeded before the user is asked to confirm, and the confirmation
 * commits work that is already done.
 */
@Singleton
class RestoreEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roots: EchoStorageRoots,
    private val storage: StorageProvider,
    private val journal: OperationJournal,
    private val maintenanceMode: MaintenanceMode,
    private val rewriter: DatabaseRewriter,
    private val settingsSnapshotter: SettingsSnapshotter,
    private val localState: BackupLocalState,
    private val authRepository: AuthRepository,
    private val backupEngine: BackupEngine
) {
    /** Held between [stage] and [commit]. */
    private var staged: StagedRestore? = null

    /**
     * Describes an archive without unpacking it.
     *
     * Reads the plaintext envelope and, if the password opens it, the manifest —
     * which is the archive's first entry precisely so this costs one entry
     * rather than a full read.
     */
    suspend fun inspect(uri: String, password: CharArray?): Result<com.dhaval.echo.domain.backup.BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                ArchiveReader.open(storage.openRead(uri), password).use { reader ->
                    val first = reader.nextEntry()
                    check(first == BackupManifest.ENTRY_MANIFEST) {
                        "This file is not a readable Echo backup"
                    }
                    val manifest = reader.readManifest()
                    manifest.toSummary(
                        BackupHeaderInfo(
                            fileName = uri.substringAfterLast('/'),
                            sizeBytes = storage.sizeOf(uri) ?: 0L,
                            createdAtMillis = reader.envelope.createdAtMillis,
                            encrypted = reader.envelope.encrypted,
                            backupFormat = reader.envelope.backupFormat
                        )
                    )
                }
            }
        }

    /**
     * Unpacks and prepares the restore without touching anything live.
     *
     * @return [RestoreOutcome.Staged] carrying the plan to confirm.
     */
    suspend fun stage(
        uri: String,
        password: CharArray?,
        onProgress: (RestoreProgress) -> Unit = {}
    ): RestoreOutcome = withContext(Dispatchers.IO) {
        maintenanceMode.enter()
        journal.beginRestore(uri)
        clearStaging()

        try {
            onProgress(RestoreProgress(RestoreStage.READING))
            val started = System.currentTimeMillis()

            ArchiveReader.open(storage.openRead(uri), password).use { reader ->
                val firstEntry = reader.nextEntry()
                if (firstEntry != BackupManifest.ENTRY_MANIFEST) {
                    return@withContext fail(BackupFailure.Corrupted("Backup has no manifest"))
                }
                val manifest = reader.readManifest()

                versionGate(manifest, reader.envelope)?.let { return@withContext fail(it) }

                val extraction = extract(reader, manifest, onProgress)
                    ?: return@withContext fail(BackupFailure.Corrupted("Backup is incomplete"))

                extraction.checksumFailure?.let { return@withContext fail(it) }

                onProgress(RestoreProgress(RestoreStage.MIGRATING))
                migrateStagedDatabase()

                onProgress(RestoreProgress(RestoreStage.RELINKING))
                val currentUserId = authRepository.getCurrentUser()?.id
                val rekeyed = relink(manifest, currentUserId)

                onProgress(RestoreProgress(RestoreStage.CHECKING))
                val consistency = scanConsistency(manifest)

                val elapsed = System.currentTimeMillis() - started
                val plan = RestorePlan(
                    summary = manifest.toSummary(
                        BackupHeaderInfo(
                            fileName = uri.substringAfterLast('/'),
                            sizeBytes = storage.sizeOf(uri) ?: 0L,
                            createdAtMillis = reader.envelope.createdAtMillis,
                            encrypted = reader.envelope.encrypted,
                            backupFormat = reader.envelope.backupFormat
                        )
                    ),
                    consistency = consistency,
                    willRekeyUserId = rekeyed,
                    migratedFromVersion = manifest.databaseVersion
                        .takeIf { it < EchoDatabase.DATABASE_VERSION },
                    // Committing is renames, so it takes a moment regardless of
                    // size. Staging is what took the time, and it is done.
                    estimatedCommitMillis = COMMIT_ESTIMATE_MILLIS,
                    canUndo = true,
                    undoIncludesMedia = manifest.includesMedia
                )

                staged = StagedRestore(
                    uri = uri,
                    manifest = manifest,
                    settingsJson = extraction.settingsJson,
                    plan = plan,
                    pendingRekeyFrom = manifest.primaryUserId.takeIf { currentUserId == null },
                    stagingTookMillis = elapsed
                )
                onProgress(RestoreProgress(RestoreStage.READY))
                RestoreOutcome.Staged(plan)
            }
        } catch (e: CancellationException) {
            abandon()
            throw e
        } catch (e: BackupDecryptionException) {
            fail(
                if (e.isProbablyWrongPassword) BackupFailure.WrongPassword
                else BackupFailure.Corrupted("The backup is damaged (chunk ${e.chunkIndex})")
            )
        } catch (e: BackupTruncatedException) {
            fail(BackupFailure.Corrupted("The backup file is incomplete"))
        } catch (e: Exception) {
            Log.e(TAG, "Staging failed", e)
            fail(BackupFailure.Unexpected(e))
        }
    }

    /**
     * Swaps the staged copy into place.
     *
     * Before anything moves, the current state is checkpointed so the whole
     * thing can be undone: a Quick backup of the live database, and the live
     * media directories *renamed* aside rather than deleted. Renaming is what
     * makes Undo affordable — parking 2.8 GB costs nothing and no time, where
     * copying it would double the disk requirement of every restore.
     */
    suspend fun commit(onProgress: (RestoreProgress) -> Unit = {}): RestoreOutcome =
        withContext(Dispatchers.IO) {
            val pending = staged ?: return@withContext RestoreOutcome.Failed(
                BackupFailure.Unexpected(IllegalStateException("Nothing staged to restore"))
            )
            try {
                onProgress(RestoreProgress(RestoreStage.COMMITTING))

                checkpointCurrentState(pending)
                journal.markCommitting()

                if (pending.manifest.includesMedia) {
                    roots.mediaRoots.values.forEach { root ->
                        val stagedDir = roots.stagingFor(root)
                        if (!stagedDir.exists()) return@forEach
                        val holding = roots.holdingFor(root)
                        if (root.exists() && !holding.exists()) root.renameTo(holding)
                        stagedDir.renameTo(root)
                    }
                }

                swapDatabase()
                journal.markCommitted()

                // Written through DataStore rather than by replacing its file:
                // the API is the only thing that keeps the in-memory cache and
                // the on-disk protobuf in agreement.
                runCatching {
                    settingsSnapshotter.apply(
                        BackupManifest.json.decodeFromString(pending.settingsJson)
                    )
                }.onFailure { Log.w(TAG, "Could not apply restored settings", it) }

                // Restoring before signing in is allowed, so the account swap
                // may have to happen later. Recording it here is what stops a
                // restored diary being invisible after the user signs in.
                pending.pendingRekeyFrom?.let { localState.pendingRekeyFromUserId = it }

                val undoUntil = System.currentTimeMillis() + UNDO_WINDOW_MILLIS
                localState.undoAvailableUntilMillis = undoUntil
                localState.pendingRestoreReport = BackupManifest.json.encodeToString(
                    pending.toReportRecord(undoUntil)
                )

                journal.clear()
                staged = null
                onProgress(RestoreProgress(RestoreStage.COMPLETE))

                RestoreOutcome.Committed(pending.toReport(undoUntil))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Commit failed", e)
                RestoreOutcome.Failed(BackupFailure.Unexpected(e))
            }
        }

    /** Throws away a staged restore the user decided against. */
    suspend fun discard() = withContext(Dispatchers.IO) {
        abandon()
    }

    /**
     * Puts everything back as it was before the last restore.
     *
     * Both halves move together — the database and the media directories — so
     * the result is the state that existed before, not a database pointing at
     * recordings that were replaced.
     */
    suspend fun undo(): Boolean = withContext(Dispatchers.IO) {
        if (localState.undoAvailableUntilMillis <= System.currentTimeMillis()) return@withContext false
        maintenanceMode.enter()

        runCatching {
            roots.mediaRoots.values.forEach { root ->
                val holding = roots.holdingFor(root)
                if (!holding.exists()) return@forEach
                if (root.exists()) root.deleteRecursively()
                holding.renameTo(root)
            }
            if (roots.heldDatabase.exists()) {
                roots.database.delete()
                deleteSiblings(roots.database)
                roots.heldDatabase.renameTo(roots.database)
            }
            localState.undoAvailableUntilMillis = 0
            localState.pendingRestoreReport = null
            localState.pendingRekeyFromUserId = null
            true
        }.getOrElse {
            Log.e(TAG, "Undo failed", it)
            false
        }
    }

    /** Frees the parked copies once the user has accepted the restore. */
    fun releaseUndo() {
        roots.mediaRoots.values.forEach { runCatching { roots.holdingFor(it).deleteRecursively() } }
        runCatching { roots.heldDatabase.delete() }
        localState.undoAvailableUntilMillis = 0
    }

    /**
     * Restarts the app.
     *
     * Unavoidable: Room and DataStore hold open handles to files that have just
     * been replaced underneath them, and no amount of care makes a live
     * `SQLiteDatabase` notice that its file is a different file. It is also the
     * cleanest way out of maintenance mode.
     */
    fun restart() {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent?.let(context::startActivity)
        exitProcess(0)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * An archive from a newer Echo is refused rather than attempted. Feeding a
     * future schema to a migration chain that has never heard of it produces a
     * database that is neither the old one nor a working one.
     */
    private fun versionGate(
        manifest: BackupManifest,
        envelope: BackupCrypto.Envelope
    ): BackupFailure? = when {
        envelope.backupFormat > BackupCrypto.BACKUP_FORMAT ->
            BackupFailure.TooNew(
                "format ${envelope.backupFormat}",
                "format ${BackupCrypto.BACKUP_FORMAT}"
            )

        manifest.databaseVersion > EchoDatabase.DATABASE_VERSION ->
            BackupFailure.TooNew(manifest.appVersionName, currentVersionName())

        // A newer manifestVersion alone is fine: unknown fields are ignored, and
        // the container is what determines whether this build can read the file.
        else -> null
    }

    private class Extraction(
        val settingsJson: String,
        val checksumFailure: BackupFailure?
    )

    private suspend fun extract(
        reader: ArchiveReader,
        manifest: BackupManifest,
        onProgress: (RestoreProgress) -> Unit
    ): Extraction? {
        val expectedBytes = manifest.entries.sumOf { it.sizeBytes }
        val actual = mutableMapOf<String, String>()
        var declared: BackupChecksums? = null
        var settingsJson = "{}"
        var files = 0
        var bytes = 0L

        onProgress(
            RestoreProgress(
                stage = RestoreStage.EXTRACTING,
                filesTotal = manifest.entries.size,
                bytesTotal = expectedBytes
            )
        )

        while (true) {
            currentCoroutineContext().ensureActive()
            val name = reader.nextEntry() ?: break

            when {
                name == BackupManifest.ENTRY_CHECKSUMS -> declared = reader.readChecksums()

                name == BackupManifest.ENTRY_SETTINGS -> {
                    val raw = reader.readEntryBytes()
                    settingsJson = String(raw)
                    actual[name] = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(raw).toHexString()
                }

                name == BackupManifest.ENTRY_DATABASE ->
                    actual[name] = writeTo(reader, roots.stagedDatabase) { delta ->
                        bytes += delta
                        onProgress(
                            RestoreProgress(
                                RestoreStage.EXTRACTING, files, manifest.entries.size, bytes, expectedBytes
                            )
                        )
                    }.also { files++ }

                name.startsWith(BackupManifest.PREFIX_MEDIA) -> {
                    val target = mediaTargetFor(name) ?: continue
                    actual[name] = writeTo(reader, target) { delta ->
                        bytes += delta
                        onProgress(
                            RestoreProgress(
                                RestoreStage.EXTRACTING, files, manifest.entries.size, bytes, expectedBytes
                            )
                        )
                    }
                    files++
                }
            }
        }

        val expected = declared?.entries ?: return null
        // The manifest is hashed too, but it was consumed before extraction
        // began, so it is compared by presence rather than by value here.
        val mismatched = expected.count { (path, hash) ->
            path != BackupManifest.ENTRY_MANIFEST && actual[path] != hash
        }
        return Extraction(
            settingsJson = settingsJson,
            checksumFailure = if (mismatched == 0) null else BackupFailure.Corrupted(
                "$mismatched items in the backup don't match their checksums"
            )
        )
    }

    private fun writeTo(reader: ArchiveReader, target: File, onBytes: (Long) -> Unit): String {
        target.parentFile?.mkdirs()
        return target.outputStream().use { out -> reader.copyEntryTo(out, onBytes).second }
    }

    /** `media/audio/2026/08/03/x.m4a` → the staged audio directory. */
    private fun mediaTargetFor(entryName: String): File? {
        val relative = entryName.removePrefix(BackupManifest.PREFIX_MEDIA)
        val prefix = relative.substringBefore('/')
        val path = relative.substringAfter('/', missingDelimiterValue = "")
        if (path.isEmpty()) return null
        val root = roots.mediaRoots[prefix] ?: return null
        return File(roots.stagingFor(root), path)
    }

    /**
     * Brings an older archive up to the current schema by the identical route an
     * in-place upgrade takes — `Room` pointed at the staged file, running
     * [EchoDatabase.MIGRATIONS]. `Context.getDatabasePath` resolves the staged
     * name in the same directory, so no path juggling is needed.
     */
    private fun migrateStagedDatabase() {
        val database = Room.databaseBuilder(
            context,
            EchoDatabase::class.java,
            roots.stagedDatabase.name
        ).addMigrations(*EchoDatabase.MIGRATIONS).build()

        try {
            // Touching the helper is what actually opens the file and runs the chain.
            database.openHelper.writableDatabase.version
        } finally {
            database.close()
        }
        // Room opens in WAL mode, so it leaves a log beside the staged file.
        // Committing renames only the main file, and a stale log left behind
        // would be replayed into the wrong database.
        deleteSiblings(roots.stagedDatabase)
    }

    /** @return true when the account was re-keyed. */
    private fun relink(manifest: BackupManifest, currentUserId: String?): Boolean {
        val database = SqlHandle.open(roots.stagedDatabase)
        return try {
            rewriter.rewriteMediaRoots(
                db = database,
                from = manifest.roots,
                to = ManifestRoots(
                    internalFiles = roots.internalFiles.absolutePath,
                    externalFiles = roots.externalFiles.absolutePath
                )
            )
            val rekey = currentUserId != null && currentUserId != manifest.primaryUserId
            if (rekey) rewriter.rekeyUserId(database, currentUserId!!)
            database.exec("PRAGMA wal_checkpoint(TRUNCATE)")
            rekey
        } finally {
            database.close()
            deleteSiblings(roots.stagedDatabase)
        }
    }

    /**
     * Compares what the restored database expects against what is actually
     * there. Checksums proved the archive was intact; this proves the *app* is.
     */
    private fun scanConsistency(manifest: BackupManifest): List<MediaConsistency> {
        val database = SqlHandle.open(roots.stagedDatabase)
        val referenced = try {
            rewriter.referencedMedia(database)
        } finally {
            database.close()
            deleteSiblings(roots.stagedDatabase)
        }

        fun present(paths: List<String>) = paths.count { resolveForScan(it, manifest.includesMedia).exists() }

        return listOf(
            MediaConsistency("Recordings", referenced.audio.size, present(referenced.audio)),
            MediaConsistency("Photos", referenced.photos.size, present(referenced.photos)),
            MediaConsistency("Videos", referenced.videos.size, present(referenced.videos))
        ).filter { it.referenced > 0 }
    }

    /**
     * Paths in the database already point at their final homes, but during
     * staging the files are still in the scratch directories — so the scan looks
     * there. When the archive carries no media, nothing is being replaced and
     * the live directories are the right place to look.
     */
    private fun resolveForScan(path: String, includesMedia: Boolean): File {
        if (!includesMedia) return File(path)
        roots.mediaRoots.values.forEach { root ->
            val prefix = "${root.absolutePath}${File.separator}"
            if (path.startsWith(prefix)) {
                return File(roots.stagingFor(root), path.removePrefix(prefix))
            }
        }
        return File(path)
    }

    /** The Quick backup and the parked directories that make Undo possible. */
    private suspend fun checkpointCurrentState(pending: StagedRestore) {
        runCatching {
            backupEngine.run(
                BackupRequest(
                    profile = BackupProfile.QUICK,
                    reason = BackupReason.PRE_RESTORE,
                    reasonDetail = "Before restoring ${pending.plan.summary.header.fileName}",
                    force = true
                )
            )
        }.onFailure {
            // A destination that isn't reachable shouldn't block a restore the
            // user asked for; the directory-level undo below still applies.
            Log.w(TAG, "Could not take a pre-restore checkpoint", it)
        }
    }

    private fun swapDatabase() {
        if (!roots.stagedDatabase.exists()) return
        if (roots.database.exists() && !roots.heldDatabase.exists()) {
            roots.database.renameTo(roots.heldDatabase)
        }
        roots.stagedDatabase.renameTo(roots.database)
        // The replaced database's log describes a file that no longer exists.
        deleteSiblings(roots.database)
    }

    private fun deleteSiblings(database: File) {
        runCatching { File("${database.path}-wal").delete() }
        runCatching { File("${database.path}-shm").delete() }
    }

    private fun clearStaging() {
        roots.mediaRoots.values.forEach { runCatching { roots.stagingFor(it).deleteRecursively() } }
        runCatching { roots.stagedDatabase.delete() }
        deleteSiblings(roots.stagedDatabase)
    }

    private fun abandon() {
        clearStaging()
        staged = null
        journal.clear()
        maintenanceMode.exit()
    }

    private fun fail(failure: BackupFailure): RestoreOutcome {
        abandon()
        return RestoreOutcome.Failed(failure)
    }

    private fun currentVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "this version"

    private class StagedRestore(
        val uri: String,
        val manifest: BackupManifest,
        val settingsJson: String,
        val plan: RestorePlan,
        val pendingRekeyFrom: String?,
        val stagingTookMillis: Long
    ) {
        fun toReport(undoUntil: Long) = com.dhaval.echo.domain.backup.RestoreReport(
            completedAtMillis = System.currentTimeMillis(),
            summary = plan.summary,
            consistency = plan.consistency,
            rekeyedUserId = plan.willRekeyUserId,
            migratedFromVersion = plan.migratedFromVersion,
            undoAvailableUntilMillis = undoUntil
        )

        fun toReportRecord(undoUntil: Long) = RestoreReportRecord(
            completedAtMillis = System.currentTimeMillis(),
            archiveName = plan.summary.header.fileName,
            createdAtMillis = plan.summary.header.createdAtMillis,
            includesMedia = plan.summary.includesMedia,
            memories = plan.summary.counts.memories,
            collections = plan.summary.counts.collections,
            people = plan.summary.counts.people,
            hasMemoryGraph = plan.summary.counts.hasMemoryGraph,
            consistency = plan.consistency.map {
                ConsistencyRecord(it.label, it.referenced, it.present)
            },
            rekeyedUserId = plan.willRekeyUserId,
            migratedFromVersion = plan.migratedFromVersion,
            undoAvailableUntilMillis = undoUntil
        )
    }

    private companion object {
        const val TAG = "RestoreEngine"

        /** Renames, not copies — the size of the diary doesn't change this. */
        const val COMMIT_ESTIMATE_MILLIS = 3_000L

        /** How long Undo stays available after a restore. */
        const val UNDO_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
