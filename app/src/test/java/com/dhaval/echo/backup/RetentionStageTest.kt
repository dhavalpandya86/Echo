package com.dhaval.echo.backup

import com.dhaval.echo.data.backup.ArchiveNaming
import com.dhaval.echo.data.backup.ArchiveSink
import com.dhaval.echo.data.backup.BackupRequest
import com.dhaval.echo.data.backup.StorageProvider
import com.dhaval.echo.data.backup.StoredArchive
import com.dhaval.echo.data.backup.pipeline.BackupContext
import com.dhaval.echo.data.backup.pipeline.RetentionStage
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupReason
import com.dhaval.echo.domain.backup.BackupSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/**
 * Retention is the only part of the backup system whose job is to delete
 * things, so it is the only part whose bugs destroy data rather than merely
 * failing to protect it. These tests pin the three rules that stop that.
 */
class RetentionStageTest {

    /**
     * The failure this guards against: event-driven Quick backups can fire many
     * times a day. Under one shared "keep last 3" they would evict every Full
     * backup within hours, leaving someone with three database snapshots and no
     * recordings at all.
     */
    @Test
    fun `quick backups cannot evict full backups`() = runBlocking {
        val storage = FakeStorage(
            fulls(count = 3, from = DAY_1) + quicks(count = 6, from = DAY_2)
        )
        val newest = storage.archives.first().uri

        RetentionStage(storage).run(contextKeeping(newest, retention = 3))

        val survivingFulls = storage.archives.count { it.name.contains("_FULL") }
        val survivingQuicks = storage.archives.count { it.name.contains("_QUICK") }
        assertEquals("full backups are kept in their own pool", 3, survivingFulls)
        assertEquals(3, survivingQuicks)
    }

    /** Someone who typed a name has said what they want kept. */
    @Test
    fun `named backups are never pruned`() {
        val named = archive(
            ArchiveNaming.fileNameFor(DAY_1, BackupProfile.FULL, BackupReason.MANUAL, "Before Europe Trip"),
            DAY_1
        )
        val storage = FakeStorage(listOf(named) + fulls(count = 5, from = DAY_2))

        runBlocking {
            RetentionStage(storage).run(contextKeeping(storage.archives.last().uri, retention = 1))
        }

        assertTrue(
            "the named backup must survive even at the tightest retention",
            storage.archives.any { it.uri == named.uri }
        )
    }

    /**
     * Checkpoints are what "Undo Restore" restores from. If ordinary retention
     * could reach them, a restore followed by a few event backups would quietly
     * remove the user's only way back.
     */
    @Test
    fun `checkpoints are retained separately from ordinary backups`() = runBlocking {
        val checkpoints = (0 until 5).map { i ->
            archive(
                ArchiveNaming.fileNameFor(DAY_1 + i * HOUR, BackupProfile.QUICK, BackupReason.PRE_RESTORE),
                DAY_1 + i * HOUR
            )
        }
        val storage = FakeStorage(checkpoints + quicks(count = 2, from = DAY_2))

        RetentionStage(storage).run(contextKeeping(keep = "none", retention = 1))

        val surviving = storage.archives.count { it.name.contains("_CHECKPOINT") }
        assertEquals(
            "checkpoints follow their own limit, not the user's retention setting",
            ArchiveNaming.CHECKPOINT_RETENTION,
            surviving
        )
    }

    /** The archive just written is never a candidate for its own cleanup. */
    @Test
    fun `the backup that just succeeded is always kept`() = runBlocking {
        val storage = FakeStorage(fulls(count = 4, from = DAY_1))
        val justWritten = storage.archives.last().uri // deliberately the oldest

        RetentionStage(storage).run(contextKeeping(justWritten, retention = 1))

        assertTrue(storage.archives.any { it.uri == justWritten })
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun contextKeeping(keep: String, retention: Int): BackupContext =
        BackupContext(
            request = BackupRequest(BackupProfile.FULL, BackupReason.SCHEDULED),
            startedAt = DAY_2,
            onProgress = {}
        ).apply {
            settings = BackupSettings(retentionCount = retention)
            sink = FakeSink(keep)
        }

    private fun fulls(count: Int, from: Long) = (0 until count).map { i ->
        archive(
            ArchiveNaming.fileNameFor(from + i * HOUR, BackupProfile.FULL, BackupReason.SCHEDULED),
            from + i * HOUR
        )
    }.sortedByDescending { it.lastModifiedMillis }

    private fun quicks(count: Int, from: Long) = (0 until count).map { i ->
        archive(
            ArchiveNaming.fileNameFor(from + i * HOUR, BackupProfile.QUICK, BackupReason.EVENT_RECORDING),
            from + i * HOUR
        )
    }.sortedByDescending { it.lastModifiedMillis }

    private fun archive(name: String, at: Long) =
        StoredArchive(uri = "content://test/$name", name = name, sizeBytes = 1024, lastModifiedMillis = at)

    private class FakeSink(override val uri: String) : ArchiveSink {
        override val stream: OutputStream get() = throw NotImplementedError()
        override fun freeBytes(): Long? = null
        override fun close() = Unit
    }

    private class FakeStorage(initial: List<StoredArchive>) : StorageProvider {
        val archives = initial.sortedByDescending { it.lastModifiedMillis }.toMutableList()
        val deleted = mutableListOf<String>()

        override suspend fun describe() = "Test folder"
        override suspend fun isWritable() = true
        override suspend fun list(): List<StoredArchive> = archives.toList()
        override suspend fun openWrite(fileName: String): ArchiveSink = throw NotImplementedError()
        override suspend fun openRead(uri: String): InputStream = throw NotImplementedError()
        override suspend fun sizeOf(uri: String): Long = 1024

        override suspend fun delete(uri: String): Boolean {
            deleted += uri
            return archives.removeAll { it.uri == uri }
        }
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY_1 = 1_754_236_800_000L
        const val DAY_2 = DAY_1 + 24 * HOUR
    }
}
