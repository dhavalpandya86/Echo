package com.dhaval.echo.domain.audio

import java.io.File

/**
 * Interface for the Audio Storage Engine.
 * Responsible for hierarchical file management, metadata persistence, and URI resolution.
 */
interface AudioStorageEngine {
    /**
     * Provides a temporary path for an active capture session.
     */
    fun getCapturePath(sessionId: String): File

    /**
     * Finalizes a recording by moving it from the capture area to the chronological archive.
     * @param sessionId The unique session identifier.
     * @param startTimeMillis The timestamp used to determine the yyyy/MM/dd structure.
     * @param metadata Key-value pairs to persist alongside the audio file.
     * @return Result containing the final storage details.
     */
    fun finalizeRecording(
        sessionId: String,
        startTimeMillis: Long,
        metadata: Map<String, String> = emptyMap()
    ): Result<StorageResult>

    /**
     * Discards and deletes all files associated with a session.
     */
    fun deleteRecording(sessionId: String): Boolean
}

/**
 * Result details for a successfully stored audio file.
 */
data class StorageResult(
    val file: File,
    val uri: String,
    val relativePath: String
)
