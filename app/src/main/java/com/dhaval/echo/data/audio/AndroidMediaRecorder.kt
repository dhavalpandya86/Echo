package com.dhaval.echo.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.dhaval.echo.domain.audio.AudioConfig
import com.dhaval.echo.domain.audio.AudioStorageEngine
import com.dhaval.echo.domain.audio.Recorder
import com.dhaval.echo.domain.audio.RecordingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android implementation of the Recorder using MediaRecorder.
 * Optimized for AAC_LC to balance quality and file size.
 */
class AndroidMediaRecorder(
    private val context: Context,
    private val storageEngine: AudioStorageEngine
) : Recorder {

    private var recorder: MediaRecorder? = null
    private var tickerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _state = MutableSharedFlow<RecordingEvent>()
    override val state: SharedFlow<RecordingEvent> = _state

    private var startTimeMillis: Long = 0
    private var pausedDurationMillis: Long = 0
    private var lastPauseTimeMillis: Long = 0

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }

    override fun start(sessionId: String, config: AudioConfig) {
        if (recorder != null) return

        val file = storageEngine.getCapturePath(sessionId)
        
        recorder = createRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(config.sampleRate)
            setAudioEncodingBitRate(config.bitRate)
            setOutputFile(file.absolutePath)

            try {
                prepare()
                start()
                startTimeMillis = System.currentTimeMillis()
                pausedDurationMillis = 0
                startTicker()
            } catch (e: Exception) {
                scope.launch { _state.emit(RecordingEvent.Stopped(com.dhaval.echo.domain.audio.RecordingResult.Failure(e))) }
                release()
            }
        }
    }

    override fun pause() {
        try {
            recorder?.pause()
            lastPauseTimeMillis = System.currentTimeMillis()
            stopTicker()
            scope.launch { _state.emit(RecordingEvent.Paused) }
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun resume() {
        try {
            recorder?.resume()
            pausedDurationMillis += (System.currentTimeMillis() - lastPauseTimeMillis)
            startTicker()
            scope.launch { _state.emit(RecordingEvent.Resumed) }
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun stop() {
        try {
            stopTicker()
            recorder?.stop()
        } catch (e: Exception) {
            // Handle edge case where stop is called immediately after start
        } finally {
            release()
        }
    }

    override fun release() {
        stopTicker()
        recorder?.release()
        recorder = null
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val duration = (System.currentTimeMillis() - startTimeMillis) - pausedDurationMillis
                val amplitude = try { recorder?.maxAmplitude?.toFloat() ?: 0f } catch (e: Exception) { 0f }
                _state.emit(RecordingEvent.Progress(duration, amplitude))
                delay(100)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
