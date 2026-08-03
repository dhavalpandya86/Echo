package com.dhaval.echo.data.backup

import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.db.ItemKind
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces a consistent copy of the database while the app keeps running.
 *
 * ### Why this is not just "copy echo_db"
 *
 * Room runs SQLite in WAL mode, so at any moment an unknown amount of committed
 * data lives in `echo_db-wal` rather than in `echo_db`. Copying the main file
 * alone would silently omit everything the user recorded since the last
 * checkpoint — the newest memories, which are the ones they would most notice
 * missing.
 *
 * `wal_checkpoint(TRUNCATE)` folds the log back into the main file and empties
 * it. After that, the main file holds everything committed so far and new
 * writes go into a fresh, empty log — so copying just `echo_db` is a coherent
 * snapshot as of the checkpoint, even if the user keeps recording during the
 * copy.
 *
 * The one thing that would break that is SQLite deciding to checkpoint again
 * mid-copy and writing into the file being read, so autocheckpoint is disabled
 * for the duration and restored afterwards.
 */
@Singleton
class DatabaseSnapshot @Inject constructor(
    private val database: EchoDatabase,
    private val roots: EchoStorageRoots
) {
    /**
     * Runs [block] with the database file in a stable, fully-checkpointed state.
     *
     * @param block receives the main database file. It must not be modified.
     */
    suspend fun <T> withStableSnapshot(block: suspend (File) -> T): T {
        val file = begin()
        return try {
            block(file)
        } finally {
            end()
        }
    }

    /**
     * Opens a stable window and returns the file to copy.
     *
     * Split from [end] because the pipeline's stages need the window to span
     * more than one of them — the fingerprint is computed in one stage and the
     * file is read in the next, and both must see the same bytes.
     *
     * **Every caller must pair this with [end]**, which the pipeline does in a
     * `finally`. Leaving autocheckpoint disabled would let the write-ahead log
     * grow without bound.
     */
    fun begin(): File {
        pragma("PRAGMA wal_autocheckpoint = 0")
        pragma("PRAGMA wal_checkpoint(TRUNCATE)")
        return roots.database
    }

    /** 1000 pages is SQLite's default, and what Room leaves in place. */
    fun end() {
        runCatching { pragma("PRAGMA wal_autocheckpoint = 1000") }
    }

    /** Folds the write-ahead log into the main file without holding it open. */
    fun checkpoint() {
        pragma("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    /**
     * The account most of this database belongs to.
     *
     * Taken as the commonest `userId` rather than the signed-in one, because at
     * backup time we care about what the *rows* say — that is what has to be
     * matched against whoever is signed in when the archive is restored.
     */
    fun primaryUserId(): String? = queryString(
        "SELECT userId FROM diary_entries GROUP BY userId ORDER BY COUNT(*) DESC LIMIT 1"
    )

    /**
     * Counts for the manifest, in the user's units.
     *
     * Media counts are supplied by the caller from what it actually archived —
     * a Quick backup must report zero recordings because it contains zero
     * recordings, not because the device has none.
     */
    fun counts(
        recordings: Int,
        photos: Int,
        videos: Int,
        mediaBytes: Long
    ) = ManifestCounts(
        memories = count("SELECT COUNT(*) FROM diary_entries WHERE deleted = 0"),
        recordings = recordings,
        photos = photos,
        videos = videos,
        collections = count("SELECT COUNT(*) FROM collections"),
        people = count("SELECT COUNT(*) FROM entities WHERE type = '${EntityType.PERSON}' AND archived = 0"),
        tasks = count("SELECT COUNT(*) FROM extracted_items WHERE kind = '${ItemKind.TASK}'"),
        // Calendar events are read live from the phone's own calendar and are
        // never stored by Echo, so there is nothing here to back up and nothing
        // that could be lost. Always zero, and the preview hides zero rows.
        calendarEvents = 0,
        hasMemoryGraph = count("SELECT COUNT(*) FROM entity_relationships") > 0,
        mediaBytes = mediaBytes,
        databaseBytes = roots.database.length()
    )

    private fun pragma(statement: String) {
        // Queried rather than exec'd: several PRAGMAs return a row, and
        // execSQL on those throws on some Android versions.
        database.openHelper.writableDatabase.query(statement).use { it.moveToFirst() }
    }

    private fun count(sql: String): Int = runCatching {
        database.openHelper.writableDatabase.query(sql).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }.getOrDefault(0)

    private fun queryString(sql: String): String? = runCatching {
        database.openHelper.writableDatabase.query(sql).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    }.getOrNull()
}
