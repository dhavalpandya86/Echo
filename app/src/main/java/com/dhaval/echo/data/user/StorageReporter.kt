package com.dhaval.echo.data.user

import android.content.Context
import com.dhaval.echo.data.db.EchoDatabase
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
    @ApplicationContext private val context: Context
) {

    suspend fun measure(): StorageBreakdown = withContext(Dispatchers.IO) {
        StorageBreakdown(
            audioBytes = sizeOf(context.filesDir.resolve("recordings")) +
                sizeOf(context.getExternalFilesDir(null)?.resolve("recordings")),
            mediaBytes = sizeOf(context.filesDir.resolve("images")) +
                sizeOf(context.filesDir.resolve("videos")) +
                sizeOf(context.getExternalFilesDir(null)?.resolve("images")) +
                sizeOf(context.getExternalFilesDir(null)?.resolve("videos")),
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
    private fun databaseSize(): Long {
        val db = context.getDatabasePath(EchoDatabase.DATABASE_NAME)
        return listOf(db, File("${db.path}-wal"), File("${db.path}-shm")).sumOf { sizeOf(it) }
    }

    /**
     * Models copied out of the asset packs into app storage so they can be
     * memory-mapped at runtime. The packs themselves are counted by the system
     * against the install, not here, so this is only the working copies.
     */
    private fun modelSize(): Long =
        sizeOf(context.filesDir.resolve("embeddings")) +
            sizeOf(context.filesDir.resolve("whisper")) +
            sizeOf(context.filesDir.resolve("models"))

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
