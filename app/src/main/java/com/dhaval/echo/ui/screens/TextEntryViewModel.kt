package com.dhaval.echo.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.audio.AudioConfig
import com.dhaval.echo.domain.audio.AudioStorageEngine
import com.dhaval.echo.domain.audio.Recorder
import com.dhaval.echo.domain.audio.RecordingEvent
import com.dhaval.echo.domain.audio.RecordingResult
import com.dhaval.echo.domain.diary.DiaryRepository
import com.dhaval.echo.domain.transcription.SpeechToTextEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

/** Which field dictation is filling. */
enum class DictationTarget { TITLE, BODY }

data class TextEntryUiState(
    val title: String = "",
    val textContent: String = "",
    val imagePaths: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val savedEntryId: String? = null,
    val error: String? = null,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val dictationTarget: DictationTarget? = null
)

@HiltViewModel
class TextEntryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryRepository: DiaryRepository,
    private val recorder: Recorder,
    private val storageEngine: AudioStorageEngine,
    private val sttEngine: SpeechToTextEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(TextEntryUiState())
    val uiState: StateFlow<TextEntryUiState> = _uiState.asStateFlow()

    private var dictationSessionId: String? = null
    private var recorderJob: Job? = null

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }

    fun onTextChange(text: String) = _uiState.update { it.copy(textContent = text) }

    // ── Mic dictation (Whisper, offline) ─────────────────────────────

    /** Start capturing a short clip to dictate into [target]. */
    fun startDictation(target: DictationTarget) {
        if (_uiState.value.isRecording || _uiState.value.isTranscribing) return
        val id = "dictation_${UUID.randomUUID()}"
        dictationSessionId = id
        _uiState.update { it.copy(isRecording = true, dictationTarget = target, error = null) }

        recorderJob = viewModelScope.launch {
            recorder.state.collect { event ->
                if (event is RecordingEvent.Stopped) {
                    recorderJob?.cancel()
                    onDictationStopped(event.result, target)
                }
            }
        }
        recorder.start(id, AudioConfig())
    }

    /** Stop the clip and transcribe it into the target field. */
    fun stopDictation() {
        if (!_uiState.value.isRecording) return
        _uiState.update { it.copy(isRecording = false, isTranscribing = true) }
        recorder.stop()
    }

    private fun onDictationStopped(result: RecordingResult, target: DictationTarget) {
        val sessionId = dictationSessionId
        viewModelScope.launch {
            try {
                when (result) {
                    is RecordingResult.Success -> {
                        val text = sttEngine.transcribe(result.file.absolutePath).transcript.trim()
                        if (text.isNotEmpty()) appendDictation(target, text)
                    }
                    is RecordingResult.Failure ->
                        _uiState.update { it.copy(error = "Couldn't hear that. Try again.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Dictation failed: ${e.message}") }
            } finally {
                // The clip was only a means to text — don't keep it around.
                sessionId?.let { runCatching { storageEngine.deleteRecording(it) } }
                dictationSessionId = null
                _uiState.update { it.copy(isTranscribing = false, dictationTarget = null) }
            }
        }
    }

    private fun appendDictation(target: DictationTarget, text: String) {
        _uiState.update { state ->
            when (target) {
                DictationTarget.TITLE ->
                    state.copy(title = joinDictation(state.title, text))
                DictationTarget.BODY ->
                    state.copy(textContent = joinDictation(state.textContent, text))
            }
        }
    }

    private fun joinDictation(existing: String, added: String): String =
        if (existing.isBlank()) added else "${existing.trimEnd()} $added"

    override fun onCleared() {
        recorderJob?.cancel()
        if (_uiState.value.isRecording) recorder.stop()
        super.onCleared()
    }

    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            val paths = uris.mapNotNull { uri -> copyToInternalStorage(uri) }
            _uiState.update { it.copy(imagePaths = it.imagePaths + paths) }
        }
    }

    fun onCameraCapture(filePath: String) {
        _uiState.update { it.copy(imagePaths = it.imagePaths + filePath) }
    }

    fun removeImage(path: String) {
        _uiState.update { it.copy(imagePaths = it.imagePaths - path) }
    }

    fun save() {
        val state = _uiState.value
        if (state.textContent.isBlank() && state.imagePaths.isEmpty()) {
            _uiState.update { it.copy(error = "Please write something or add a photo.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val id = diaryRepository.createTextEntry(
                    title = state.title,
                    textContent = state.textContent,
                    imagePaths = state.imagePaths
                )
                _uiState.update { it.copy(isSaving = false, savedEntryId = id) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save.") }
            }
        }
    }

    fun createCameraFile(): File {
        val imagesDir = File(context.filesDir, "images").also { it.mkdirs() }
        return File(imagesDir, "capture_${UUID.randomUUID()}.jpg")
    }

    private fun copyToInternalStorage(uri: Uri): String? {
        return try {
            val imagesDir = File(context.filesDir, "images").also { it.mkdirs() }
            val destFile = File(imagesDir, "img_${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
