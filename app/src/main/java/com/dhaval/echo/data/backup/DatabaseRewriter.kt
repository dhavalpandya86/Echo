package com.dhaval.echo.data.backup

import android.database.Cursor
import android.util.Log
import com.dhaval.echo.domain.video.VideoAttachment
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes a restored database describe *this* device and *this* account.
 *
 * Two problems, both invisible until someone actually restores:
 *
 * **1. Media paths are absolute.** `audioPath`, `imagePaths`, `videos[].path`,
 * `videos[].thumbnailPath` and the *keys* of `photoCaptions` are all full paths
 * baked in when the file was written. Restore onto a new phone — or onto the
 * same phone whose external storage now resolves differently — and every one of
 * them points at nothing. The memories come back; the recordings don't play and
 * the photos are blank.
 *
 * **2. Every row is scoped by Firebase `userId`.** Restore an archive made under
 * one account while signed in as another, and every query filters the restored
 * rows straight back out. The restore succeeds perfectly and the app looks empty.
 *
 * During a restore both are fixed against the *staged* copy, before anything is
 * committed. The re-key also runs against the live database when someone
 * restored before signing in and only now has an account to attach it to.
 */
@Singleton
class DatabaseRewriter @Inject constructor() {

    /**
     * Rewrites the storage roots inside every column that can hold a path.
     *
     * Plain substring replacement is sound here, and not by luck:
     * kotlinx-serialization does not escape `/`, so a path inside the JSON of
     * `imagePaths`, `videos` or `photoCaptions` appears byte-for-byte as it does
     * in a bare column. That is what allows one `REPLACE` to fix a bare string,
     * a JSON array, a JSON object's values *and* its keys — the last of which a
     * per-field deserialise-and-rewrite pass would most easily miss.
     *
     * @return true when anything actually needed changing.
     */
    fun rewriteMediaRoots(db: SqlHandle, from: ManifestRoots, to: ManifestRoots): Boolean {
        val replacements = listOf(
            from.externalFiles to to.externalFiles,
            from.internalFiles to to.internalFiles
        ).filter { (old, new) -> old.isNotBlank() && old != new }

        // Same device, same install paths — the overwhelmingly common reinstall
        // case. Skipping spares a full rewrite of every row to change nothing.
        if (replacements.isEmpty()) return false

        PATH_COLUMNS.forEach { column ->
            replacements.forEach { (old, new) ->
                db.exec(
                    "UPDATE diary_entries SET `$column` = REPLACE(`$column`, ?, ?) " +
                        "WHERE `$column` IS NOT NULL AND instr(`$column`, ?) > 0",
                    arrayOf(old, new, old)
                )
            }
        }
        return true
    }

    /**
     * Points every `userId`-scoped row at the given account.
     *
     * Tables are discovered rather than listed: the schema has grown from 8
     * tables to 19 and will keep growing, and a hardcoded list would silently
     * miss whichever table is added next — leaving part of someone's diary
     * orphaned under an account that no longer exists.
     *
     * Runs with foreign keys off. `tags` is keyed on `(name, userId)` and the
     * cross-reference tables cascade from it, so a naive update would either
     * collide or delete the very rows it is trying to re-point. `UPDATE OR
     * IGNORE` moves everything that can move; the `DELETE` that follows removes
     * only what could not, which by definition duplicates a row already present
     * under the new id.
     *
     * @return how many rows changed hands.
     */
    fun rekeyUserId(db: SqlHandle, newUserId: String): Int {
        var changed = 0
        db.exec("PRAGMA foreign_keys = OFF")
        try {
            userScopedTables(db).forEach { table ->
                val before = countWhereOtherUser(db, table, newUserId)
                if (before == 0) return@forEach
                db.exec(
                    "UPDATE OR IGNORE `$table` SET userId = ? WHERE userId <> ?",
                    arrayOf(newUserId, newUserId)
                )
                db.exec("DELETE FROM `$table` WHERE userId <> ?", arrayOf(newUserId))
                changed += before
            }
        } finally {
            db.exec("PRAGMA foreign_keys = ON")
        }
        return changed
    }

    /** Every media file the restored database expects to find. */
    fun referencedMedia(db: SqlHandle): ReferencedMedia {
        val audio = mutableListOf<String>()
        val photos = mutableListOf<String>()
        val videos = mutableListOf<String>()

        db.query("SELECT audioPath, imagePaths, videos FROM diary_entries WHERE deleted = 0")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.stringOrNull(0)?.takeIf { it.isNotBlank() }?.let(audio::add)
                    cursor.stringOrNull(1)?.let { raw ->
                        runCatching { lenient.decodeFromString<List<String>>(raw) }
                            .getOrDefault(emptyList())
                            .forEach(photos::add)
                    }
                    cursor.stringOrNull(2)?.let { raw ->
                        runCatching { lenient.decodeFromString<List<VideoAttachment>>(raw) }
                            .getOrDefault(emptyList())
                            .forEach { videos.add(it.path) }
                    }
                }
            }
        return ReferencedMedia(audio = audio, photos = photos, videos = videos)
    }

    /**
     * A last sanity check that the graph survived re-keying: dangling foreign
     * keys here would mean memories detached from their tags or entities.
     */
    fun foreignKeyViolations(db: SqlHandle): Int = runCatching {
        db.query("PRAGMA foreign_key_check").use { it.count }
    }.getOrElse {
        Log.w(TAG, "Could not run foreign_key_check", it)
        0
    }

    private fun userScopedTables(db: SqlHandle): List<String> {
        val tables = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                "AND name NOT LIKE 'room_%'"
        ).use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        return tables.filter { table ->
            // user_profiles is keyed *on* the account rather than scoped by it;
            // re-keying it would forge a profile row for the new user. The
            // session layer writes the real one at sign-in.
            table != "user_profiles" && hasUserIdColumn(db, table)
        }
    }

    private fun hasUserIdColumn(db: SqlHandle, table: String): Boolean =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "userId") return true
            }
            false
        }

    private fun countWhereOtherUser(db: SqlHandle, table: String, userId: String): Int =
        db.query("SELECT COUNT(*) FROM `$table` WHERE userId <> ?", arrayOf(userId)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    private companion object {
        const val TAG = "DatabaseRewriter"

        /**
         * Every column on `diary_entries` that can contain an absolute path.
         * `photoCaptions` is here because its *keys* are image paths — a caption
         * map left un-rewritten detaches every caption from its photo.
         */
        val PATH_COLUMNS = listOf("audioPath", "imagePaths", "videos", "photoCaptions")

        val lenient = Json { ignoreUnknownKeys = true }
    }
}

data class ReferencedMedia(
    val audio: List<String>,
    val photos: List<String>,
    val videos: List<String>
)

private fun Cursor.stringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
