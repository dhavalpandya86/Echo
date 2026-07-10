package com.dhaval.echo.domain.audio

import kotlinx.coroutines.flow.Flow

/**
 * Low-level interface for the audio recording engine.
 * Decouples the domain from specific Android APIs (MediaRecorder vs AudioRecord).
 */
interface Recorder {
    val state: Flow<RecordingEvent>
    
    fun start(sessionId: String, config: AudioConfig)
    fun pause()
    fun resume()
    fun stop()
    fun release()
}
