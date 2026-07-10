package com.dhaval.echo.domain.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for audio playback.
 * Agnostic of the underlying media engine.
 */
interface AudioPlayer {
    val playbackState: StateFlow<PlaybackState>
    
    fun play(uri: String)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMillis: Long)
    fun setPlaybackSpeed(speed: Float)
    fun release()
}

/**
 * State representing the current playback status.
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val error: String? = null,
    val isReady: Boolean = false
)
