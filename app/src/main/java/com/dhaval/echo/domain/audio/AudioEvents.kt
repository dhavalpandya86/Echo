package com.dhaval.echo.domain.audio

import java.io.File

/**
 * Events emitted by the Recorder to be handled by the Repository or UI.
 */
sealed interface RecordingEvent {
    data class Progress(val durationMillis: Long, val amplitude: Float) : RecordingEvent
    object Paused : RecordingEvent
    object Resumed : RecordingEvent
    data class Stopped(val result: RecordingResult) : RecordingEvent
}

/**
 * Result of a completed recording session.
 */
sealed interface RecordingResult {
    data class Success(val session: AudioSession, val file: File) : RecordingResult
    data class Failure(val throwable: Throwable) : RecordingResult
}
