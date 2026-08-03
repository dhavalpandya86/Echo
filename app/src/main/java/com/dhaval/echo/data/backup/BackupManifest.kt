package com.dhaval.echo.data.backup

import com.dhaval.echo.domain.backup.BackupCounts
import com.dhaval.echo.domain.backup.BackupHeaderInfo
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupReason
import com.dhaval.echo.domain.backup.BackupSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an archive says about itself.
 *
 * Written as the **first** entry in the ZIP so an archive can be described
 * without being unpacked — the difference between a history screen that lists
 * 500 backups instantly and one that reads 2.8 GB to draw a row.
 *
 * ### Two version numbers, on purpose
 *
 * [manifestVersion] describes *this JSON*; [BackupCrypto.BACKUP_FORMAT]
 * describes the container. Only the container gates restore. Adding a field
 * here later — battery level at backup time, which AI model was configured,
 * anything — bumps [manifestVersion] and is read by older builds through
 * `ignoreUnknownKeys` without complaint. If the two were one number, every
 * cosmetic addition would lock users out of their own backups.
 */
@Serializable
data class BackupManifest(
    val manifestVersion: Int = MANIFEST_VERSION,

    // ── Identity. Unused today; see the note below.
    val backupId: String,
    val parentBackupId: String? = null,
    val deviceId: String,

    // ── What and why
    val createdAtMillis: Long,
    val profile: String,
    val reason: String,
    val reasonDetail: String? = null,
    val name: String? = null,
    val note: String? = null,

    // ── Compatibility
    val appVersionName: String,
    val appVersionCode: Long,
    val databaseVersion: Int,
    val androidRelease: String,
    val deviceModel: String,

    /**
     * The account the rows in this database belong to. Compared against the
     * signed-in user at restore; if they differ, every `userId` is re-keyed, or
     * the restored diary would be invisible to the person restoring it.
     */
    val primaryUserId: String? = null,

    /**
     * Which password era sealed this archive. Changing the backup password
     * doesn't re-encrypt old files, so history uses this to warn "made with your
     * previous password" instead of letting someone conclude the file is broken.
     */
    val passwordEpoch: Int = 0,

    /** See [BackupFingerprint]. Lets an unchanged scheduled run skip writing. */
    val contentFingerprint: String,

    val includesMedia: Boolean,

    /**
     * False for unencrypted archives, where the AI provider keys are stripped.
     * A plaintext file in a shared folder is not a place for the user's
     * Claude/OpenAI/Gemini credentials, and restore needs to know they're absent
     * rather than restoring empty strings over working keys.
     */
    val apiKeysIncluded: Boolean,

    /**
     * The absolute directories this archive was made from. Media paths are
     * stored absolutely in the database, so restore rewrites these roots to
     * wherever the app now lives.
     */
    val roots: ManifestRoots,

    val counts: ManifestCounts,

    /** Every file in the archive with its original size, for progress and checks. */
    val entries: List<ManifestEntry> = emptyList()
) {
    val backupProfile: BackupProfile
        get() = runCatching { BackupProfile.valueOf(profile) }.getOrDefault(BackupProfile.FULL)

    val backupReason: BackupReason
        get() = runCatching { BackupReason.valueOf(reason) }.getOrDefault(BackupReason.MANUAL)

    fun toSummary(header: BackupHeaderInfo) = BackupSummary(
        header = header,
        backupId = backupId,
        profile = backupProfile,
        reason = backupReason,
        reasonDetail = reasonDetail,
        name = name,
        note = note,
        appVersionName = appVersionName,
        databaseVersion = databaseVersion,
        deviceModel = deviceModel,
        passwordEpoch = passwordEpoch,
        counts = counts.toDomain()
    )

    companion object {
        /**
         * 1 — initial. Bump for any field added below; never for a format change,
         * which belongs to [BackupCrypto.BACKUP_FORMAT].
         */
        const val MANIFEST_VERSION = 1

        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_CHECKSUMS = "checksums.json"
        const val ENTRY_DATABASE = "database/echo_db"
        const val ENTRY_SETTINGS = "settings/preferences.json"
        const val PREFIX_MEDIA = "media/"

        /** The archive extension, and what the file picker filters on. */
        const val FILE_EXTENSION = "echo"

        /**
         * Lenient on read so a manifest written by a newer Echo still parses;
         * defaults encoded on write so an older reader never sees a missing key.
         */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}

@Serializable
data class ManifestRoots(
    val internalFiles: String,
    val externalFiles: String
)

@Serializable
data class ManifestEntry(
    val path: String,
    val sizeBytes: Long
)

/**
 * Counts in the user's units. Computed once at backup time rather than derived
 * at restore, so the preview can be shown before anything is unpacked.
 */
@Serializable
data class ManifestCounts(
    val memories: Int = 0,
    val recordings: Int = 0,
    val photos: Int = 0,
    val videos: Int = 0,
    val collections: Int = 0,
    val people: Int = 0,
    val tasks: Int = 0,
    val calendarEvents: Int = 0,
    val hasMemoryGraph: Boolean = false,
    val mediaBytes: Long = 0,
    val databaseBytes: Long = 0
) {
    fun toDomain() = BackupCounts(
        memories = memories,
        recordings = recordings,
        photos = photos,
        videos = videos,
        collections = collections,
        people = people,
        tasks = tasks,
        calendarEvents = calendarEvents,
        hasMemoryGraph = hasMemoryGraph,
        mediaBytes = mediaBytes,
        databaseBytes = databaseBytes
    )
}

/** `checksums.json` — archive entry path to SHA-256 hex, written last. */
@Serializable
data class BackupChecksums(
    val algorithm: String = "SHA-256",
    val entries: Map<String, String> = emptyMap()
)
