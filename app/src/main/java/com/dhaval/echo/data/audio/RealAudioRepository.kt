package com.dhaval.echo.data.audio

import com.dhaval.echo.domain.audio.AudioConfig
import com.dhaval.echo.domain.audio.AudioRepository
import com.dhaval.echo.domain.audio.AudioSession
import com.dhaval.echo.domain.audio.AudioStorageEngine
import com.dhaval.echo.domain.audio.Recorder
import com.dhaval.echo.domain.audio.RecordingEvent
import com.dhaval.echo.domain.audio.RecordingResult
import com.dhaval.echo.domain.audio.RecordingState
import com.dhaval.echo.domain.intelligence.IntelligenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import java.time.LocalDateTime

/**
 * Real implementation of the AudioRepository.
 * Orchestrates between the Recording Engine and the Storage Engine.
 */
class RealAudioRepository(
    private val recorder: Recorder,
    private val storageEngine: AudioStorageEngine,
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceRepository: IntelligenceRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : AudioRepository {

    private val _currentRecordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val currentRecordingState: StateFlow<RecordingState> = _currentRecordingState.asStateFlow()

    private var currentSessionId: String? = null
    private var observerJob: Job? = null
    private var startTimeMillis: Long = 0

    override fun startCapture() {
        if (_currentRecordingState.value !is RecordingState.Idle) return

        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        startTimeMillis = System.currentTimeMillis()
        val config = AudioConfig()

        observeRecorder()
        recorder.start(sessionId, config)
    }

    override fun pauseCapture() {
        recorder.pause()
    }

    override fun resumeCapture() {
        recorder.resume()
    }

    override fun stopCapture() {
        val sessionId = currentSessionId ?: return
        _currentRecordingState.value = RecordingState.Saving(sessionId)
        
        recorder.stop()
        
        // Finalize the recording via the Storage Engine
        val result = storageEngine.finalizeRecording(
            sessionId = sessionId,
            startTimeMillis = startTimeMillis,
            metadata = mapOf(
                "app" to "Echo",
                "version" to "1.0"
            )
        )

        result.fold(
            onSuccess = { storageResult ->
                scope.launch {
                    val now = LocalDateTime.now()
                    val entry = DiaryEntry(
                        id = sessionId,
                        title = "Recording ${now.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))}",
                        audioPath = storageResult.file.absolutePath,
                        createdAt = now,
                        updatedAt = now,
                        duration = System.currentTimeMillis() - startTimeMillis
                    )
                    diaryEntryDao.insertEntry(entry)
                    
                    // Trigger intelligence pipeline
                    intelligenceRepository.processEntry(sessionId)

                    _currentRecordingState.value = RecordingState.Idle
                }
            },
            onFailure = { throwable ->
                _currentRecordingState.value = RecordingState.Error(
                    throwable.message ?: "Failed to save recording"
                )
            }
        )
        
        cleanup()
    }

    override fun discardCapture() {
        val sessionId = currentSessionId ?: return
        recorder.stop()
        storageEngine.deleteRecording(sessionId)
        _currentRecordingState.value = RecordingState.Idle
        cleanup()
    }

    override suspend fun getSession(sessionId: String): AudioSession? {
        return null
    }

    override suspend fun getAllSessions(): List<AudioSession> {
        return emptyList()
    }

    private fun observeRecorder() {
        observerJob?.cancel()
        observerJob = scope.launch {
            recorder.state.collect { event ->
                when (event) {
                    is RecordingEvent.Progress -> {
                        _currentRecordingState.value = RecordingState.Recording(
                            durationMillis = event.durationMillis,
                            amplitude = event.amplitude,
                            sessionId = currentSessionId ?: "",
                            isPaused = false
                        )
                    }
                    RecordingEvent.Paused -> {
                        val current = _currentRecordingState.value
                        if (current is RecordingState.Recording) {
                            _currentRecordingState.value = current.copy(isPaused = true)
                        }
                    }
                    RecordingEvent.Resumed -> {
                        val current = _currentRecordingState.value
                        if (current is RecordingState.Recording) {
                            _currentRecordingState.value = current.copy(isPaused = false)
                        }
                    }
                    is RecordingEvent.Stopped -> {
                        if (event.result is RecordingResult.Failure) {
                            _currentRecordingState.value = RecordingState.Error(
                                event.result.throwable.message ?: "Hardware error"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun cleanup() {
        observerJob?.cancel()
        observerJob = null
        currentSessionId = null
    }
}
