package com.dhaval.echo.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.backup.DatabaseRewriter
import com.dhaval.echo.data.backup.ManifestRoots
import com.dhaval.echo.data.backup.SqlHandle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The two ways a restore silently produces a broken app.
 *
 * Neither failure announces itself: the restore reports success, the memory
 * list looks right, and only when the user taps play does it turn out the audio
 * path points at a directory that exists on a phone they no longer own. These
 * tests are the reason to trust that it doesn't.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseRewriterTest {

    private lateinit var file: File
    private lateinit var db: SqlHandle
    private val rewriter = DatabaseRewriter()

    private val oldRoots = ManifestRoots(
        internalFiles = "/data/user/0/com.dhaval.echo/files",
        externalFiles = "/storage/emulated/0/Android/data/com.dhaval.echo/files"
    )
    private val newRoots = ManifestRoots(
        internalFiles = "/data/user/10/com.dhaval.echo/files",
        externalFiles = "/storage/1A2B-3C4D/Android/data/com.dhaval.echo/files"
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File.createTempFile("rewriter-test", ".db", context.cacheDir)
        file.delete()
        db = SqlHandle.open(file)
        createSchema()
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    // ── Media paths ──────────────────────────────────────────────────────────

    /**
     * `photoCaptions` is the one that catches people out: it is a JSON map whose
     * *keys* are image paths, so a rewrite that only touches values leaves every
     * caption attached to a photo that no longer exists.
     */
    @Test
    fun rewritesEveryColumnThatCanHoldAPath() {
        val audio = "${oldRoots.externalFiles}/Echo/audio/2026/08/03/session.m4a"
        val photo = "${oldRoots.internalFiles}/images/img_1.jpg"
        val video = "${oldRoots.externalFiles}/Echo/video/2026/08/03/clip.mp4"
        val thumb = "${oldRoots.externalFiles}/Echo/thumbnails/clip.jpg"

        insertEntry(
            id = "m1",
            audioPath = audio,
            imagePaths = """["$photo"]""",
            videos = """[{"id":"v1","path":"$video","thumbnailPath":"$thumb"}]""",
            photoCaptions = """{"$photo":"Prabir at the pool"}"""
        )

        val changed = rewriter.rewriteMediaRoots(db, oldRoots, newRoots)
        assertTrue(changed)

        val row = readEntry("m1")
        assertEquals(audio.replace(oldRoots.externalFiles, newRoots.externalFiles), row.audioPath)
        assertTrue(row.imagePaths!!.contains(newRoots.internalFiles))
        assertTrue(row.videos!!.contains("${newRoots.externalFiles}/Echo/video"))
        assertTrue("thumbnail path must be rewritten too", row.videos.contains("${newRoots.externalFiles}/Echo/thumbnails"))
        assertTrue("caption keys must be rewritten", row.photoCaptions!!.contains(newRoots.internalFiles))
        assertTrue("the caption itself must survive", row.photoCaptions.contains("Prabir at the pool"))
        assertFalse(row.videos.contains(oldRoots.externalFiles))
    }

    /** Reinstalling on the same phone is the common case and must cost nothing. */
    @Test
    fun skipsEntirelyWhenTheRootsAreUnchanged() {
        val audio = "${oldRoots.externalFiles}/Echo/audio/x.m4a"
        insertEntry(id = "m1", audioPath = audio)

        val changed = rewriter.rewriteMediaRoots(db, oldRoots, oldRoots)

        assertFalse("an identical-roots rewrite must be a no-op", changed)
        assertEquals(audio, readEntry("m1").audioPath)
    }

    @Test
    fun leavesRowsWithNoPathsAlone() {
        insertEntry(id = "text-only", audioPath = "")

        rewriter.rewriteMediaRoots(db, oldRoots, newRoots)

        assertEquals("", readEntry("text-only").audioPath)
    }

    // ── Account re-keying ────────────────────────────────────────────────────

    @Test
    fun movesEveryUserScopedTableToTheNewAccount() {
        insertEntry(id = "m1", userId = OLD_USER)
        db.exec("INSERT INTO entities(id, userId, name) VALUES('e1', ?, 'Raj')", arrayOf(OLD_USER))

        val changed = rewriter.rekeyUserId(db, NEW_USER)

        assertTrue(changed >= 2)
        assertEquals(NEW_USER, readEntry("m1").userId)
        assertEquals(1, count("SELECT COUNT(*) FROM entities WHERE userId = '$NEW_USER'"))
        assertEquals(0, count("SELECT COUNT(*) FROM entities WHERE userId = '$OLD_USER'"))
    }

    /**
     * `tags` is keyed on `(name, userId)`. When both accounts already have a
     * tag called "work", a plain `UPDATE` hits a uniqueness conflict — and the
     * obvious fix, `UPDATE OR REPLACE`, cascades and deletes the cross-reference
     * rows that connect memories to that tag.
     */
    @Test
    fun survivesTheTagPrimaryKeyCollision() {
        db.exec("INSERT INTO tags(name, userId) VALUES('work', ?)", arrayOf(OLD_USER))
        db.exec("INSERT INTO tags(name, userId) VALUES('work', ?)", arrayOf(NEW_USER))
        db.exec("INSERT INTO tags(name, userId) VALUES('family', ?)", arrayOf(OLD_USER))
        insertEntry(id = "m1", userId = OLD_USER)
        db.exec(
            "INSERT INTO diary_entry_tag_cross_ref(entryId, tagName, userId) VALUES('m1','work',?)",
            arrayOf(OLD_USER)
        )

        rewriter.rekeyUserId(db, NEW_USER)

        assertEquals(
            "the duplicate tag must collapse to one row, not two or zero",
            1,
            count("SELECT COUNT(*) FROM tags WHERE name = 'work'")
        )
        assertEquals(
            "a tag only the old account had must come across",
            1,
            count("SELECT COUNT(*) FROM tags WHERE name = 'family' AND userId = '$NEW_USER'")
        )
        assertEquals(
            "no rows may be left stranded under the old account",
            0,
            count("SELECT COUNT(*) FROM tags WHERE userId = '$OLD_USER'")
        )
        assertEquals(
            "the memory must still be connected to its tag",
            1,
            count(
                "SELECT COUNT(*) FROM diary_entry_tag_cross_ref " +
                    "WHERE entryId = 'm1' AND tagName = 'work' AND userId = '$NEW_USER'"
            )
        )
    }

    /** Discovery, not a hardcoded list — a table added tomorrow must be covered. */
    @Test
    fun rekeysTablesItWasNeverToldAbout() {
        db.exec("CREATE TABLE future_feature(id TEXT PRIMARY KEY, userId TEXT NOT NULL)")
        db.exec("INSERT INTO future_feature(id, userId) VALUES('f1', ?)", arrayOf(OLD_USER))

        rewriter.rekeyUserId(db, NEW_USER)

        assertEquals(1, count("SELECT COUNT(*) FROM future_feature WHERE userId = '$NEW_USER'"))
    }

    /**
     * `user_profiles` is keyed *on* the account rather than scoped by it, so
     * re-keying would forge a profile row for someone who never had one.
     */
    @Test
    fun doesNotForgeAProfileForTheNewAccount() {
        db.exec("INSERT INTO user_profiles(id, displayName) VALUES(?, 'Old owner')", arrayOf(OLD_USER))

        rewriter.rekeyUserId(db, NEW_USER)

        assertEquals(1, count("SELECT COUNT(*) FROM user_profiles WHERE id = '$OLD_USER'"))
        assertEquals(0, count("SELECT COUNT(*) FROM user_profiles WHERE id = '$NEW_USER'"))
    }

    // ── Referenced media ─────────────────────────────────────────────────────

    @Test
    fun readsBackEveryMediaReferenceTheDatabaseHolds() {
        insertEntry(
            id = "m1",
            audioPath = "/a/one.m4a",
            imagePaths = """["/a/p1.jpg","/a/p2.jpg"]""",
            videos = """[{"id":"v1","path":"/a/v1.mp4","thumbnailPath":"/a/v1.jpg"}]"""
        )
        insertEntry(id = "m2", audioPath = "/a/two.m4a")
        insertEntry(id = "gone", audioPath = "/a/deleted.m4a", deleted = 1)

        val referenced = rewriter.referencedMedia(db)

        assertEquals("soft-deleted memories are not counted", 2, referenced.audio.size)
        assertEquals(2, referenced.photos.size)
        assertEquals(1, referenced.videos.size)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun createSchema() {
        db.exec(
            """CREATE TABLE diary_entries(
                id TEXT PRIMARY KEY, userId TEXT NOT NULL DEFAULT 'legacy_user',
                audioPath TEXT NOT NULL DEFAULT '', imagePaths TEXT, videos TEXT,
                photoCaptions TEXT, deleted INTEGER NOT NULL DEFAULT 0)"""
        )
        db.exec("CREATE TABLE tags(name TEXT NOT NULL, userId TEXT NOT NULL, PRIMARY KEY(name, userId))")
        db.exec(
            """CREATE TABLE diary_entry_tag_cross_ref(
                entryId TEXT NOT NULL, tagName TEXT NOT NULL, userId TEXT NOT NULL,
                PRIMARY KEY(entryId, tagName, userId),
                FOREIGN KEY(tagName, userId) REFERENCES tags(name, userId) ON DELETE CASCADE)"""
        )
        db.exec("CREATE TABLE entities(id TEXT PRIMARY KEY, userId TEXT NOT NULL, name TEXT)")
        db.exec("CREATE TABLE user_profiles(id TEXT PRIMARY KEY, displayName TEXT)")
    }

    private fun insertEntry(
        id: String,
        userId: String = OLD_USER,
        audioPath: String = "",
        imagePaths: String? = null,
        videos: String? = null,
        photoCaptions: String? = null,
        deleted: Int = 0
    ) {
        db.exec(
            "INSERT INTO diary_entries(id, userId, audioPath, imagePaths, videos, photoCaptions, deleted) " +
                "VALUES(?,?,?,?,?,?,?)",
            arrayOf(id, userId, audioPath, imagePaths, videos, photoCaptions, deleted)
        )
    }

    private data class Row(
        val userId: String,
        val audioPath: String,
        val imagePaths: String?,
        val videos: String?,
        val photoCaptions: String?
    )

    private fun readEntry(id: String): Row =
        db.query(
            "SELECT userId, audioPath, imagePaths, videos, photoCaptions FROM diary_entries WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            assertTrue("expected a row for $id", cursor.moveToFirst())
            Row(
                userId = cursor.getString(0),
                audioPath = cursor.getString(1),
                imagePaths = if (cursor.isNull(2)) null else cursor.getString(2),
                videos = if (cursor.isNull(3)) null else cursor.getString(3),
                photoCaptions = if (cursor.isNull(4)) null else cursor.getString(4)
            )
        }

    private fun count(sql: String): Int =
        db.query(sql).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private companion object {
        const val OLD_USER = "firebase-uid-old"
        const val NEW_USER = "firebase-uid-new"
    }
}
