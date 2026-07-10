package com.dhaval.echo.domain.intelligence

import java.io.File

/**
 * High-level status of a transcription task.
 */
sealed interface TaskStatus<out T> {
    object Pending : TaskStatus<Nothing>
    object Processing : TaskStatus<Nothing>
    data class Completed<T>(val data: T) : TaskStatus<T>
    data class Failed(val error: Throwable) : TaskStatus<Nothing>
}

data class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptionSegment>
)

data class TranscriptionSegment(
    val startTime: Long,
    val endTime: Long,
    val text: String,
    val languageCode: String
)

/**
 * Interface for converting audio to text.
 * Implementations can be Local (Whisper) or Cloud (OpenAI, Gemini).
 */
interface TranscriptionService {
    suspend fun transcribe(audioFile: File): TranscriptionResult
}
