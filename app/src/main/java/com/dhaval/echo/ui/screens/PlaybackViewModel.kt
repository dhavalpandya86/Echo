package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.audio.AudioPlayer
import com.dhaval.echo.domain.audio.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel responsible for managing audio playback.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val audioPlayer: AudioPlayer
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = audioPlayer.playbackState

    fun play(uri: String) = audioPlayer.play(uri)
    
    fun togglePlayPause() {
        if (playbackState.value.isPlaying) {
            audioPlayer.pause()
        } else {
            audioPlayer.resume()
        }
    }

    fun seekTo(positionMillis: Long) = audioPlayer.seekTo(positionMillis)
    
    fun setSpeed(speed: Float) = audioPlayer.setPlaybackSpeed(speed)

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
