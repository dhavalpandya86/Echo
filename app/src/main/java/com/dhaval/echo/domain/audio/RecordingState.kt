package com.dhaval.echo.domain.audio

/**
 * Represents the current state of the Capture Engine.
 * Essential for UDF in the UI and crash recovery logic.
 */
sealed interface RecordingState {
    object Idle : RecordingState
    
    data class Recording(
        val durationMillis: Long,
        val amplitude: Float,
        val sessionId: String,
        val isPaused: Boolean = false
    ) : RecordingState
    
    data class Saving(val sessionId: String) : RecordingState
    
    data class Error(val message: String, val throwable: Throwable? = null) : RecordingState
}
