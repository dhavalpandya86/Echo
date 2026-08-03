package com.dhaval.echo.data.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.dhaval.echo.domain.video.VideoStorageEngine
import com.dhaval.echo.domain.video.VideoStorageResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Android implementation of [VideoStorageEngine].
 *
 * Follows the same layout AndroidAudioStorageEngine established: a cache
 * capture area for in-progress writes, and a chronological yyyy/MM/dd archive
 * under external app-specific storage. Videos live beside audio rather than in
 * `filesDir` (where MEDIA-01 put photos) because they are large and internal
 * storage is scarce; app-specific external storage is removed on uninstall and
 * needs no permission.
 */
class AndroidVideoStorageEngine(
    private val context: Context
) : VideoStorageEngine {

    private val rootFolder: File by lazy {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(baseDir, "Echo/video").apply { mkdirs() }
    }

    private val thumbnailFolder: File by lazy {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(baseDir, "Echo/thumbnails").apply { mkdirs() }
    }

    private val captureFolder: File by lazy {
        File(context.cacheDir, "video_captures").apply { mkdirs() }
    }

    override fun newVideoId(): String = UUID.randomUUID().toString()

    override fun getCapturePath(videoId: String): File =
        File(captureFolder, "$videoId.$EXTENSION")

    override fun getThumbnailPath(videoId: String): File =
        File(thumbnailFolder, "$videoId.jpg")

    override fun finalizeRecording(
        videoId: String,
        createdAtMillis: Long
    ): Result<VideoStorageResult> = runCatching {
        val source = getCapturePath(videoId)
        if (!source.exists()) error("Capture file not found for $videoId")

        val target = archiveFileFor(videoId, createdAtMillis)
        if (target.exists()) error("File collision for video $videoId")

        // copy+delete rather than renameTo: cacheDir and external storage can be
        // different filesystems, where renameTo silently fails.
        source.copyTo(target, overwrite = false)
        source.delete()

        resultFor(videoId, target)
    }.onFailure { Log.e(TAG, "finalizeRecording failed for $videoId", it) }

    override fun saveImportedVideo(
        sourceUri: String,
        createdAtMillis: Long
    ): Result<VideoStorageResult> = runCatching {
        val videoId = newVideoId()
        val target = archiveFileFor(videoId, createdAtMillis)

        context.contentResolver.openInputStream(Uri.parse(sourceUri)).use { input ->
            checkNotNull(input) { "Could not open $sourceUri" }
            target.outputStream().use { output -> input.copyTo(output) }
        }

        resultFor(videoId, target)
    }.onFailure { Log.e(TAG, "saveImportedVideo failed for $sourceUri", it) }

    override fun deleteVideo(path: String): Boolean = deleteIfPresent(path)

    override fun deleteThumbnail(path: String): Boolean = deleteIfPresent(path)

    // ── internals ────────────────────────────────────────────────────────

    /** Archive path for a video: Echo/video/yyyy/MM/dd/{id}.mp4 */
    private fun archiveFileFor(videoId: String, createdAtMillis: Long): File {
        val date = Date(createdAtMillis)
        val dir = File(
            rootFolder,
            SimpleDateFormat("yyyy/MM/dd", Locale.US).format(date)
        ).apply { mkdirs() }
        return File(dir, "$videoId.$EXTENSION")
    }

    private fun resultFor(videoId: String, file: File) = VideoStorageResult(
        videoId = videoId,
        file = file,
        uri = file.toURI().toString(),
        relativePath = file.absolutePath.substringAfter(context.packageName),
        sizeBytes = file.length(),
        durationMillis = readDurationMillis(file)
    )

    /**
     * Duration is part of a video's identity in the UI, and reading it once at
     * save time is far cheaper than re-deriving it per list render. A failure
     * here is not fatal — the attachment is still valid with duration 0.
     */
    private fun readDurationMillis(file: File): Long = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        }
    }.getOrElse {
        Log.w(TAG, "Could not read duration for ${file.name}", it)
        0L
    }

    /** Absent is the desired end state, so a missing file counts as deleted. */
    private fun deleteIfPresent(path: String): Boolean = runCatching {
        val file = File(path)
        !file.exists() || file.delete()
    }.getOrElse {
        Log.w(TAG, "Failed to delete $path", it)
        false
    }

    private companion object {
        const val TAG = "VideoStorage"
        const val EXTENSION = "mp4"
    }
}
