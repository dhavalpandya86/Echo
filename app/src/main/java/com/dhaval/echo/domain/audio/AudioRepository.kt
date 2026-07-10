package com.dhaval.echo.domain.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * The high-level orchestrator for audio operations.
 * Coordinates between the Recorder, File Manager, and (future) Database.
 */
interface AudioRepository {
    val currentRecordingState: StateFlow<RecordingState>
    
    fun startCapture()
    fun pauseCapture()
    fun resumeCapture()
    fun stopCapture()
    fun discardCapture()
    
    // Future sync and cleanup
    suspend fun getSession(sessionId: String): AudioSession?
    suspend fun getAllSessions(): List<AudioSession>
}
