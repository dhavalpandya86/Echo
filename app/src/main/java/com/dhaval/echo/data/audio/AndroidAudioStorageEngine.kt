package com.dhaval.echo.data.audio

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.dhaval.echo.domain.audio.AudioStorageEngine
import com.dhaval.echo.domain.audio.StorageResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android implementation of the Audio Storage Engine.
 * Manages the chronological file hierarchy and metadata sidecars.
 */
class AndroidAudioStorageEngine(
    private val context: Context
) : AudioStorageEngine {

    private val rootFolder: File by lazy {
        // Fallback to internal files if external is not available
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(baseDir, "Echo/audio").apply { mkdirs() }
    }

    private val captureFolder: File by lazy {
        File(context.cacheDir, "captures").apply { mkdirs() }
    }

    override fun getCapturePath(sessionId: String): File {
        return File(captureFolder, "$sessionId.m4a")
    }

    override fun finalizeRecording(
        sessionId: String,
        startTimeMillis: Long,
        metadata: Map<String, String>
    ): Result<StorageResult> {
        return try {
            val sourceFile = getCapturePath(sessionId)
            if (!sourceFile.exists()) {
                return Result.failure(Exception("Capture file not found"))
            }

            // Generate chronological path: yyyy/MM/dd
            val date = Date(startTimeMillis)
            val year = SimpleDateFormat("yyyy", Locale.US).format(date)
            val month = SimpleDateFormat("MM", Locale.US).format(date)
            val day = SimpleDateFormat("dd", Locale.US).format(date)
            
            val targetDir = File(rootFolder, "$year/$month/$day").apply { mkdirs() }
            val targetFile = File(targetDir, "$sessionId.m4a")

            // Ensure we never overwrite
            if (targetFile.exists()) {
                return Result.failure(Exception("File collision for session $sessionId"))
            }

            // Copy file to final destination (renameTo can fail across filesystems)
            try {
                sourceFile.copyTo(targetFile, overwrite = true)
                sourceFile.delete()
            } catch (e: Exception) {
                return Result.failure(Exception("Failed to move recording to archive: ${e.message}"))
            }

            // Store metadata sidecar (Future-proofing for crash recovery/sync)
            saveMetadata(targetFile, metadata)

            val relativePath = targetFile.absolutePath.substringAfter(context.packageName)
            
            // Generate File URI (using FileProvider for secure sharing if needed later)
            val uri = targetFile.toURI().toString()

            Result.success(
                StorageResult(
                    file = targetFile,
                    uri = uri,
                    relativePath = relativePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun deleteRecording(sessionId: String): Boolean {
        val temp = getCapturePath(sessionId)
        if (temp.exists()) temp.delete()
        
        // Note: Searching the chronological tree for a sessionId is expensive.
        // In a real app, the database would provide the full path.
        // For the foundation, we prioritize deleting the active capture.
        return true
    }

    private fun saveMetadata(audioFile: File, metadata: Map<String, String>) {
        if (metadata.isEmpty()) return
        val metadataFile = File(audioFile.parent, "${audioFile.nameWithoutExtension}.json")
        try {
            val content = metadata.entries.joinToString(
                prefix = "{",
                postfix = "}",
                transform = { "\"${it.key}\": \"${it.value}\"" }
            )
            metadataFile.writeText(content)
        } catch (e: Exception) {
            // Log but don't fail finalization for metadata issues
        }
    }
}
