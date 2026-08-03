package com.dhaval.echo.data.backup

import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archive file names, and the facts that can be read back out of them.
 *
 * The name carries more than decoration. Retention has to decide what to delete
 * *without* being able to open anything — an encrypted archive's manifest needs
 * a password, and asking the user for one in order to prune old backups
 * overnight is not a design. So the two facts pruning needs live in the name:
 * which pool an archive belongs to, and whether the user named it.
 *
 * It also means the files make sense in a file manager, which is where someone
 * will eventually go looking for them:
 *
 * ```
 * Echo_2026-08-03_2115_FULL.echo
 * Echo_2026-08-03_2115_QUICK.echo
 * Echo_2026-08-03_2115_FULL_Before-Europe-Trip.echo
 * Echo_2026-08-03_2115_CHECKPOINT.echo
 * ```
 */
object ArchiveNaming {

    /**
     * Which retention pool an archive competes in.
     *
     * Separate pools exist because a shared one is self-defeating: event-driven
     * Quick backups can fire many times a day, and under one "keep last 5" they
     * would evict every Full backup within hours, leaving the user with five
     * database snapshots and no recordings.
     */
    enum class Pool(val tag: String) {
        FULL("FULL"),
        QUICK("QUICK"),

        /**
         * Pre-restore checkpoints. Kept apart so that restoring several times
         * in a row can't flush the ordinary backups, and so a handful of
         * them can be retained cheaply for Undo.
         */
        CHECKPOINT("CHECKPOINT");

        companion object {
            fun of(profile: BackupProfile, reason: BackupReason): Pool = when {
                reason == BackupReason.PRE_RESTORE -> CHECKPOINT
                profile == BackupProfile.FULL -> FULL
                else -> QUICK
            }

            fun fromTag(tag: String): Pool? = entries.firstOrNull { it.tag == tag }
        }
    }

    /** How many checkpoints to keep, regardless of the user's retention setting. */
    const val CHECKPOINT_RETENTION = 3

    fun fileNameFor(
        createdAtMillis: Long,
        profile: BackupProfile,
        reason: BackupReason,
        name: String? = null
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(createdAtMillis))
        val pool = Pool.of(profile, reason).tag
        val suffix = name?.let { sanitize(it) }?.takeIf { it.isNotBlank() }?.let { "_$it" }.orEmpty()
        return "Echo_${stamp}_$pool$suffix.${BackupManifest.FILE_EXTENSION}"
    }

    /** What can be told about an archive from its name alone. */
    data class Parsed(val pool: Pool, val isNamed: Boolean)

    fun parse(fileName: String): Parsed? {
        val base = fileName.removeSuffix(".${BackupManifest.FILE_EXTENSION}")
        val parts = base.split("_")
        // Echo _ date _ time _ POOL [ _ name ]
        if (parts.size < 4 || parts[0] != "Echo") return null
        val pool = Pool.fromTag(parts[3]) ?: return null
        return Parsed(pool = pool, isNamed = parts.size > 4)
    }

    /**
     * Names come from a text field and end up as a filename, so anything a
     * filesystem or a path parser could choke on is replaced rather than
     * escaped. Underscores in particular would break [parse]'s segmenting.
     */
    private fun sanitize(name: String): String =
        name.trim()
            .replace(Regex("[^A-Za-z0-9 -]"), "")
            .replace(Regex("\\s+"), "-")
            .take(40)
            .trim('-')
}
