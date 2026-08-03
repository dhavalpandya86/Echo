package com.dhaval.echo.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.dhaval.echo.ui.navigation.TextEntryRoute
import java.time.LocalDate
import java.time.LocalDateTime
import com.dhaval.echo.domain.audio.AudioConfig
import com.dhaval.echo.domain.audio.AudioStorageEngine
import com.dhaval.echo.domain.audio.Recorder
import com.dhaval.echo.domain.diary.DiaryRepository
import com.dhaval.echo.domain.transcription.DictationEvent
import com.dhaval.echo.domain.transcription.LiveDictation
import com.dhaval.echo.domain.transcription.SpeechToTextEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

/** Which field dictation is filling. */
enum class DictationTarget { TITLE, BODY }

/** Per-character delay for the live typing animation (ms). Small = snappy. */
private const val TYPE_DELAY_MS = 8L

data class TextEntryUiState(
    val title: String = "",
    val textContent: String = "",
    val imagePaths: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val savedEntryId: String? = null,
    val error: String? = null,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val dictationTarget: DictationTarget? = null,
    /** Non-null when this entry is being backdated to a chosen calendar day. */
    val entryDate: LocalDate? = null
)

@HiltViewModel
class TextEntryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryRepository: DiaryRepository,
    private val recorder: Recorder,
    private val storageEngine: AudioStorageEngine,
    private val sttEngine: SpeechToTextEngine,
    private val liveDictation: LiveDictation,
    private val backupTriggers: com.dhaval.echo.data.backup.BackupTriggers,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** The day this memory should be dated to (from the calendar), or null = now. */
    private val entryDate: LocalDateTime? =
        runCatching { savedStateHandle.toRoute<TextEntryRoute>().dateEpochDay }
            .getOrNull()
            ?.let { LocalDate.ofEpochDay(it).atTime(java.time.LocalTime.now()) }

    private val _uiState = MutableStateFlow(TextEntryUiState(entryDate = entryDate?.toLocalDate()))
    val uiState: StateFlow<TextEntryUiState> = _uiState.asStateFlow()

    // ── Mic dictation ────────────────────────────────────────────────
    // Live path (platform SpeechRecognizer) when available; else a Whisper
    // record-then-transcribe fallback.

    private var usingWhisper = false

    // Whisper fallback session
    private var dictationSessionId: String? = null

    // Live session
    private var liveJob: Job? = null
    private var dictationBase = ""   // field text before dictation began
    private var committed = ""       // finalized utterances this session
    private var partial = ""         // current interim utterance

    // Typewriter: the field types toward the latest recognized text one char at a
    // time (natural typing), catching up fast on big jumps so it never lags behind.
    private var targetText = ""
    private var typeJob: Job? = null

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }

    fun onTextChange(text: String) = _uiState.update { it.copy(textContent = text) }

    /** Begin dictating into [target]. Words stream in live when the platform allows. */
    fun startDictation(target: DictationTarget) {
        if (_uiState.value.isRecording || _uiState.value.isTranscribing) return
        if (liveDictation.isAvailable) startLiveDictation(target) else startWhisperDictation(target)
    }

    /** Stop the current dictation and commit the (cleaned) text. */
    fun stopDictation() {
        if (!_uiState.value.isRecording) return
        if (usingWhisper) stopWhisperDictation() else stopLiveDictation()
    }

    // ── Live dictation ───────────────────────────────────────────────

    private fun startLiveDictation(target: DictationTarget) {
        usingWhisper = false
        dictationBase = currentField(target)
        committed = ""
        partial = ""
        _uiState.update { it.copy(isRecording = true, dictationTarget = target, error = null) }

        liveJob = viewModelScope.launch {
            liveDictation.events.collect { event ->
                when (event) {
                    is DictationEvent.Partial -> { partial = event.text; renderLive(target) }
                    is DictationEvent.Final -> {
                        committed = joinDictation(committed, event.text); partial = ""; renderLive(target)
                    }
                    is DictationEvent.Failed -> failLive(target, event.message)
                    DictationEvent.Ended -> finalizeLive(target)
                    DictationEvent.Ready -> Unit
                }
            }
        }
        liveDictation.start(languageTag = null) // device default; language picker is a later step
    }

    private fun stopLiveDictation() {
        // Wait for the last utterance to finalize, then clean up in finalizeLive().
        _uiState.update { it.copy(isRecording = false, isTranscribing = true) }
        liveDictation.stop()
    }

    /** Show the live draft: base + finalized + the in-progress partial. */
    private fun renderLive(target: DictationTarget) {
        typeToward(target, joinDictation(dictationBase, joinDictation(committed, partial)))
    }

    private fun finalizeLive(target: DictationTarget) {
        liveJob?.cancel(); liveJob = null
        typeJob?.cancel(); typeJob = null
        val raw = committed.trim()
        viewModelScope.launch {
            val clean = if (raw.isBlank()) "" else cleanupTranscript(raw)
            setField(target, joinDictation(dictationBase, clean)) // snap to the final text
            resetDictationFlags()
        }
    }

    private fun failLive(target: DictationTarget, message: String) {
        liveJob?.cancel(); liveJob = null
        typeJob?.cancel(); typeJob = null
        // Keep whatever was already dictated; just report the problem.
        setField(target, joinDictation(dictationBase, committed.trim()))
        _uiState.update { it.copy(error = message) }
        resetDictationFlags()
    }

    /** Aim the typewriter at [text]; a single loop converges the field toward it. */
    private fun typeToward(target: DictationTarget, text: String) {
        targetText = text
        if (typeJob?.isActive == true) return
        typeJob = viewModelScope.launch {
            while (true) {
                val cur = currentField(target)
                val tgt = targetText
                if (cur == tgt) break
                val lcp = commonPrefixLength(cur, tgt)
                val next = if (cur.length > lcp) {
                    cur.dropLast(1) // recognizer revised the text — backspace to the divergence
                } else {
                    val remaining = tgt.length - cur.length
                    val step = if (remaining > 16) remaining / 8 else 1 // catch up fast on big jumps
                    tgt.substring(0, (cur.length + step.coerceAtLeast(1)).coerceAtMost(tgt.length))
                }
                setField(target, next)
                delay(TYPE_DELAY_MS)
            }
        }
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    // ── Whisper fallback (record → transcribe file) ──────────────────

    private fun startWhisperDictation(target: DictationTarget) {
        usingWhisper = true
        val id = "dictation_${UUID.randomUUID()}"
        dictationSessionId = id
        _uiState.update { it.copy(isRecording = true, dictationTarget = target, error = null) }
        recorder.start(id, AudioConfig())
    }

    private fun stopWhisperDictation() {
        val sessionId = dictationSessionId ?: return
        val target = _uiState.value.dictationTarget ?: DictationTarget.BODY
        _uiState.update { it.copy(isRecording = false, isTranscribing = true) }

        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    recorder.stop()
                    storageEngine.getCapturePath(sessionId)
                }
                if (!file.exists() || file.length() == 0L) {
                    _uiState.update { it.copy(error = "Didn't catch that — try again.") }
                    return@launch
                }
                val raw = sttEngine.transcribe(file.absolutePath).transcript.trim()
                if (raw.isNotEmpty()) {
                    val clean = cleanupTranscript(raw)
                    setField(target, joinDictation(currentField(target), clean))
                } else {
                    _uiState.update { it.copy(error = "Didn't catch that — try again.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Dictation failed: ${e.message}") }
            } finally {
                sessionId.let { runCatching { storageEngine.deleteRecording(it) } }
                dictationSessionId = null
                resetDictationFlags()
            }
        }
    }

    /**
     * Post-process a raw transcript — filler removal, punctuation, name correction.
     * Identity for now; wired to the cleanup service in V2.
     */
    private suspend fun cleanupTranscript(raw: String): String = raw

    private fun currentField(target: DictationTarget): String = when (target) {
        DictationTarget.TITLE -> _uiState.value.title
        DictationTarget.BODY -> _uiState.value.textContent
    }

    private fun setField(target: DictationTarget, value: String) {
        _uiState.update {
            when (target) {
                DictationTarget.TITLE -> it.copy(title = value)
                DictationTarget.BODY -> it.copy(textContent = value)
            }
        }
    }

    private fun resetDictationFlags() {
        _uiState.update { it.copy(isRecording = false, isTranscribing = false, dictationTarget = null) }
    }

    private fun joinDictation(existing: String, added: String): String = when {
        added.isBlank() -> existing
        existing.isBlank() -> added
        else -> "${existing.trimEnd()} $added"
    }

    override fun onCleared() {
        liveJob?.cancel()
        typeJob?.cancel()
        runCatching { liveDictation.release() }
        if (usingWhisper && dictationSessionId != null) {
            runCatching { recorder.stop() }
            dictationSessionId?.let { runCatching { storageEngine.deleteRecording(it) } }
        }
        super.onCleared()
    }

    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            val paths = uris.mapNotNull { uri -> copyToInternalStorage(uri) }
            _uiState.update { it.copy(imagePaths = it.imagePaths + paths) }
            if (paths.isNotEmpty()) backupTriggers.onMediaImported(paths.size)
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
                    imagePaths = state.imagePaths,
                    date = entryDate
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
