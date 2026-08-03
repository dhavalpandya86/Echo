package com.dhaval.echo.data.user

import android.content.Context
import com.dhaval.echo.data.backup.EchoStorageRoots
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Echo is actually using on disk, broken down the way a user would ask.
 *
 * Every figure is measured, never estimated. The Storage row previously read
 * "1.2 MB used" as a hardcoded string, which is the exact failure the settings
 * page is meant to avoid: a number that looks like state and isn't.
 */
data class StorageBreakdown(
    /** Recordings — almost always the largest share. */
    val audioBytes: Long = 0,
    /** Photos and videos attached to memories. */
    val mediaBytes: Long = 0,
    /** The database: memories, entities, the graph. Small but the important part. */
    val databaseBytes: Long = 0,
    /** Speech and embedding models shipped as asset packs. */
    val modelBytes: Long = 0
) {
    val totalBytes: Long get() = audioBytes + mediaBytes + databaseBytes + modelBytes
}

@Singleton
class StorageReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roots: EchoStorageRoots
) {

    /**
     * Reads its directories from [EchoStorageRoots] rather than naming them
     * here.
     *
     * This previously measured `recordings/` and `videos/` under both roots —
     * directories nothing has ever written to. Audio is at `Echo/audio` and
     * video at `Echo/video`, so the Storage row reported every user's
     * recordings, the single largest thing Echo stores, as 0 bytes. One shared
     * definition means the storage report and the backup can no longer disagree
     * about where the data is, and the next directory that moves breaks both or
     * neither.
     */
    suspend fun measure(): StorageBreakdown = withContext(Dispatchers.IO) {
        StorageBreakdown(
            audioBytes = sizeOf(roots.audio),
            mediaBytes = sizeOf(roots.images) + sizeOf(roots.video) + sizeOf(roots.thumbnails),
            databaseBytes = databaseSize(),
            modelBytes = modelSize()
        )
    }

    /**
     * The database plus its write-ahead log and shared-memory files.
     *
     * The -wal file alone can hold megabytes of recent writes that have not yet
     * been checkpointed into the main file, so reporting only the .db would
     * understate it and would visibly lag behind what the user just recorded.
     */
    private fun databaseSize(): Long = roots.databaseFamily.sumOf { sizeOf(it) }

    /**
     * Models copied out of the asset packs into app storage so they can be
     * memory-mapped at runtime. The packs themselves are counted by the system
     * against the install, not here, so this is only the working copies.
     */
    private fun modelSize(): Long = roots.modelDirectories.sumOf { sizeOf(it) }

    private fun sizeOf(file: File?): Long {
        if (file == null || !file.exists()) return 0
        return if (file.isDirectory) {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            file.length()
        }
    }

    companion object {
        /** "1.2 GB", "412 MB", "88 KB" — one decimal only above a megabyte. */
        fun format(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
