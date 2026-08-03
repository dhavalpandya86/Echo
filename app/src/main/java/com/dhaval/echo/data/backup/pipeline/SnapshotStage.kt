package com.dhaval.echo.data.backup.pipeline

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.dhaval.echo.data.backup.ArchiveNaming
import com.dhaval.echo.data.backup.BackupFingerprint
import com.dhaval.echo.data.backup.BackupLocalState
import com.dhaval.echo.data.backup.BackupManifest
import com.dhaval.echo.data.backup.BackupPasswordStore
import com.dhaval.echo.data.backup.BackupPreferences
import com.dhaval.echo.data.backup.DatabaseSnapshot
import com.dhaval.echo.data.backup.EchoStorageRoots
import com.dhaval.echo.data.backup.ManifestEntry
import com.dhaval.echo.data.backup.ManifestRoots
import com.dhaval.echo.data.backup.MediaInventory
import com.dhaval.echo.data.backup.StorageProvider
import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.domain.backup.BackupFailure
import com.dhaval.echo.domain.backup.BackupProgress
import com.dhaval.echo.domain.backup.BackupStage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether this backup should happen, and works out exactly what it is.
 *
 * Every reason to *not* proceed is checked here, before anything is created:
 * an unreachable destination, a missing password, and a device whose content
 * hasn't changed since the last archive. Getting all of them out of the way
 * first is what allows the later stages to assume they are writing a real
 * backup, and what makes a failed run leave nothing behind.
 */
@Singleton
class SnapshotStage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roots: EchoStorageRoots,
    private val storage: StorageProvider,
    private val preferences: BackupPreferences,
    private val passwords: BackupPasswordStore,
    private val localState: BackupLocalState,
    private val databaseSnapshot: DatabaseSnapshot,
    private val fingerprints: BackupFingerprint,
    private val mediaInventory: MediaInventory
) : BackupStageStep {

    override val stage = BackupStage.PREPARING

    override suspend fun run(context: BackupContext): StageResult {
        context.onProgress(BackupProgress(stage))

        if (!storage.isWritable()) {
            return StageResult.Failed(BackupFailure.DestinationUnavailable(storage.describe()))
        }

        val settings = preferences.current()
        context.settings = settings

        if (settings.encryptionEnabled) {
            context.ownsPassword = context.request.password == null
            context.password = context.request.password ?: passwords.read()
            if (context.password == null) {
                return StageResult.Failed(BackupFailure.PasswordUnavailable)
            }
        }

        // Opening the stable window before fingerprinting is not incidental:
        // until the checkpoint runs, recent writes sit in the write-ahead log
        // and the database file hashes as unchanged even though the user has
        // just recorded something. Fingerprinting first would skip exactly the
        // backup that was most needed.
        context.databaseFile = databaseSnapshot.begin()
        context.snapshotHeld = true

        val includesMedia = context.request.profile.includesMedia
        context.fingerprint = fingerprints.compute(includesMedia)

        if (!context.request.force && context.fingerprint == localState.lastFingerprint) {
            return StageResult.Stop("Nothing has changed since the last backup")
        }

        context.media = if (includesMedia) mediaInventory.enumerate() else emptyList()
        context.mediaBytes = context.media.sumOf { it.file.length() }
        context.manifest = buildManifest(context)
        context.fileName = ArchiveNaming.fileNameFor(
            createdAtMillis = context.startedAt,
            profile = context.request.profile,
            reason = context.request.reason,
            name = context.request.name
        )
        return StageResult.Continue
    }

    private fun buildManifest(context: BackupContext): BackupManifest {
        val packageInfo = runCatching {
            this.context.packageManager.getPackageInfo(this.context.packageName, 0)
        }.getOrNull()

        val counts = databaseSnapshot.counts(
            recordings = context.media.count { it.isRecording },
            photos = context.media.count { it.isPhoto },
            videos = context.media.count { it.isVideo },
            mediaBytes = context.mediaBytes
        )

        return BackupManifest(
            backupId = UUID.randomUUID().toString(),
            deviceId = localState.deviceId,
            createdAtMillis = context.startedAt,
            profile = context.request.profile.name,
            reason = context.request.reason.name,
            reasonDetail = context.request.reasonDetail,
            name = context.request.name,
            note = context.request.note,
            appVersionName = packageInfo?.versionName ?: "unknown",
            appVersionCode = packageInfo?.longVersionCodeCompat() ?: 0L,
            databaseVersion = EchoDatabase.DATABASE_VERSION,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            primaryUserId = databaseSnapshot.primaryUserId(),
            passwordEpoch = localState.passwordEpoch,
            contentFingerprint = context.fingerprint,
            includesMedia = context.request.profile.includesMedia,
            // Unencrypted archives have their AI keys stripped, and restore
            // needs to know that so it doesn't write empty strings over
            // working ones.
            apiKeysIncluded = context.password != null,
            roots = ManifestRoots(
                internalFiles = roots.internalFiles.absolutePath,
                externalFiles = roots.externalFiles.absolutePath
            ),
            counts = counts,
            entries = buildList {
                add(ManifestEntry(BackupManifest.ENTRY_DATABASE, roots.database.length()))
                context.media.forEach { add(ManifestEntry(it.entryPath, it.file.length())) }
            }
        )
    }
}

internal fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
    else @Suppress("DEPRECATION") versionCode.toLong()
