package com.dhaval.echo.data.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dhaval.echo.domain.audio.AudioPlayer
import com.dhaval.echo.domain.audio.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Media3 implementation of the AudioPlayer.
 */
class Media3AudioPlayer(
    private val context: Context
) : AudioPlayer {

    private var exoPlayer: ExoPlayer? = null
    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private fun initPlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playbackState.update { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) startProgressUpdate() else stopProgressUpdate()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        _playbackState.update { 
                            it.copy(
                                isReady = state == Player.STATE_READY || state == Player.STATE_BUFFERING,
                                duration = duration.coerceAtLeast(0)
                            )
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        _playbackState.update { it.copy(error = error.message) }
                    }
                })
            }
        }
    }

    override fun play(uri: String) {
        initPlayer()
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            play()
        }
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun resume() {
        exoPlayer?.play()
    }

    override fun stop() {
        exoPlayer?.stop()
        stopProgressUpdate()
    }

    override fun seekTo(positionMillis: Long) {
        exoPlayer?.seekTo(positionMillis)
        _playbackState.update { it.copy(currentPosition = positionMillis) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
        _playbackState.update { it.copy(playbackSpeed = speed) }
    }

    override fun release() {
        stopProgressUpdate()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                _playbackState.update { 
                    it.copy(currentPosition = exoPlayer?.currentPosition ?: 0) 
                }
                delay(100)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }
}
