package com.dhaval.echo.backup

import com.dhaval.echo.data.backup.ArchiveNaming
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filenames carry two facts retention depends on: which pool an archive belongs
 * to, and whether the user named it. Retention runs unattended and cannot open
 * an encrypted archive to find out, so if parsing breaks it does not fail
 * loudly — it silently deletes the wrong backups.
 */
class ArchiveNamingTest {

    @Test
    fun `profile and reason decide the pool`() {
        assertEquals(
            ArchiveNaming.Pool.FULL,
            ArchiveNaming.Pool.of(BackupProfile.FULL, BackupReason.MANUAL)
        )
        assertEquals(
            ArchiveNaming.Pool.QUICK,
            ArchiveNaming.Pool.of(BackupProfile.QUICK, BackupReason.EVENT_RECORDING)
        )
    }

    /**
     * A pre-restore checkpoint is a Quick backup, but it must not compete with
     * ordinary Quick backups — a few restores in a row would otherwise flush
     * every event backup the user has.
     */
    @Test
    fun `pre-restore checkpoints get their own pool regardless of profile`() {
        assertEquals(
            ArchiveNaming.Pool.CHECKPOINT,
            ArchiveNaming.Pool.of(BackupProfile.QUICK, BackupReason.PRE_RESTORE)
        )
        assertEquals(
            ArchiveNaming.Pool.CHECKPOINT,
            ArchiveNaming.Pool.of(BackupProfile.FULL, BackupReason.PRE_RESTORE)
        )
    }

    @Test
    fun `an unnamed backup round-trips to its pool`() {
        val name = ArchiveNaming.fileNameFor(AUGUST_3, BackupProfile.FULL, BackupReason.SCHEDULED)
        val parsed = ArchiveNaming.parse(name)

        assertTrue(name.endsWith(".echo"))
        assertEquals(ArchiveNaming.Pool.FULL, parsed?.pool)
        assertFalse("an unnamed backup must stay prunable", parsed!!.isNamed)
    }

    @Test
    fun `a named backup is recognisable as named`() {
        val name = ArchiveNaming.fileNameFor(
            AUGUST_3, BackupProfile.FULL, BackupReason.MANUAL, name = "Before Europe Trip"
        )
        val parsed = ArchiveNaming.parse(name)

        assertTrue(name.contains("Before-Europe-Trip"))
        assertEquals(ArchiveNaming.Pool.FULL, parsed?.pool)
        assertTrue("a named backup must be exempt from retention", parsed!!.isNamed)
    }

    /**
     * Names come from a free-text field and become part of a filename. An
     * underscore would split the segments `parse` relies on, and a slash would
     * be a path — either one turns a named backup into an unparseable one,
     * which retention then treats as fair game.
     */
    @Test
    fun `hostile names cannot break the segment structure`() {
        val hostile = "../../etc_passwd: my/trip*?\"backup\""
        val name = ArchiveNaming.fileNameFor(
            AUGUST_3, BackupProfile.QUICK, BackupReason.MANUAL, name = hostile
        )
        val parsed = ArchiveNaming.parse(name)

        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
        assertEquals(ArchiveNaming.Pool.QUICK, parsed?.pool)
        assertTrue(parsed!!.isNamed)
    }

    /** A file someone dropped in the folder is not ours to reason about. */
    @Test
    fun `foreign filenames parse to null rather than a guess`() {
        assertNull(ArchiveNaming.parse("holiday-photos.zip"))
        assertNull(ArchiveNaming.parse("Echo_2026-08-03.echo"))
        assertNull(ArchiveNaming.parse("Echo_2026-08-03_2115_MYSTERY.echo"))
    }

    private companion object {
        const val AUGUST_3 = 1_754_236_800_000L
    }
}
