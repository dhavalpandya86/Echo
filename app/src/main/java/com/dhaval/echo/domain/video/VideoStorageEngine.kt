package com.dhaval.echo.domain.video

import java.io.File

/**
 * Video counterpart to [com.dhaval.echo.domain.audio.AudioStorageEngine].
 *
 * Owns where video files live and how they are named, so callers never build
 * paths themselves. Videos arrive two ways — recorded by the camera (which
 * writes into a capture path we hand it, then we archive it) or imported from
 * the gallery (which we copy out of a content Uri) — so there are two intake
 * routes and one archive layout.
 *
 * Kept free of Android types: [saveImportedVideo] takes the source as a URI
 * string, which the platform implementation resolves.
 */
interface VideoStorageEngine {

    /** A unique id for a new video. Also the basename of its file and thumbnail. */
    fun newVideoId(): String

    /** Temporary path the camera writes a recording to, before it is archived. */
    fun getCapturePath(videoId: String): File

    /**
     * Moves a completed camera recording out of the capture area into the archive.
     */
    fun finalizeRecording(
        videoId: String,
        createdAtMillis: Long = System.currentTimeMillis()
    ): Result<VideoStorageResult>

    /**
     * Copies a gallery video into app-managed storage. Content URIs are not
     * durable, so the bytes must be owned by the app before being referenced.
     *
     * @param sourceUri A `content://` or `file://` URI string.
     */
    fun saveImportedVideo(
        sourceUri: String,
        createdAtMillis: Long = System.currentTimeMillis()
    ): Result<VideoStorageResult>

    /** Where this video's thumbnail belongs. Generation happens elsewhere (Part 2). */
    fun getThumbnailPath(videoId: String): File

    /** Deletes a video file. Returns true when it is gone (including if absent). */
    fun deleteVideo(path: String): Boolean

    /** Deletes a thumbnail file. Returns true when it is gone (including if absent). */
    fun deleteThumbnail(path: String): Boolean
}

/**
 * Details of a video successfully written to app-managed storage.
 */
data class VideoStorageResult(
    val videoId: String,
    val file: File,
    val uri: String,
    val relativePath: String,
    val sizeBytes: Long,
    val durationMillis: Long
)
