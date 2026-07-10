package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.audio.AudioRepository
import com.dhaval.echo.domain.audio.RecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI State for the Record Screen.
 */
data class RecordUiState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val durationMillis: Long = 0,
    val amplitude: Float = 0f,
    val isSaving: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for the Record Screen.
 * Exposes the recording state as a StateFlow for the UI.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val audioRepository: AudioRepository
) : ViewModel() {

    val uiState: StateFlow<RecordUiState> = audioRepository.currentRecordingState
        .map { state ->
            when (state) {
                is RecordingState.Idle -> RecordUiState()
                is RecordingState.Recording -> RecordUiState(
                    isRecording = true,
                    isPaused = state.isPaused,
                    durationMillis = state.durationMillis,
                    amplitude = state.amplitude
                )
                is RecordingState.Saving -> RecordUiState(isSaving = true)
                is RecordingState.Error -> RecordUiState(error = state.message)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = RecordUiState()
        )

    fun startRecording() = audioRepository.startCapture()
    fun pauseRecording() = audioRepository.pauseCapture()
    fun resumeRecording() = audioRepository.resumeCapture()
    fun stopRecording() = audioRepository.stopCapture()
    fun cancelRecording() = audioRepository.discardCapture()
}
