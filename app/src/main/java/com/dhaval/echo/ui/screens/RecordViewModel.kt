package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.audio.AudioRepository
import com.dhaval.echo.domain.audio.RecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
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
    val error: String? = null,
    val requiresPermission: Boolean = false,
    val permissionDenied: Boolean = false
)

/**
 * ViewModel for the Record Screen.
 * Exposes the recording state as a StateFlow for the UI.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    private val audioRepository: AudioRepository
) : ViewModel() {

    private val _permissionState = MutableStateFlow(PermissionStatus.Unknown)

    val uiState: StateFlow<RecordUiState> = combine(
        audioRepository.currentRecordingState,
        _permissionState
    ) { state, permission ->
        when (state) {
            is RecordingState.Idle -> RecordUiState(
                requiresPermission = permission == PermissionStatus.Unknown,
                permissionDenied = permission == PermissionStatus.Denied
            )
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

    fun onPermissionResult(granted: Boolean) {
        _permissionState.value = if (granted) PermissionStatus.Granted else PermissionStatus.Denied
        if (granted) {
            startRecording()
        }
    }

    fun startRecording() {
        if (_permissionState.value == PermissionStatus.Granted) {
            audioRepository.startCapture()
        } else {
            _permissionState.value = PermissionStatus.Unknown
        }
    }

    fun pauseRecording() = audioRepository.pauseCapture()
    fun resumeRecording() = audioRepository.resumeCapture()
    fun stopRecording() = audioRepository.stopCapture()
    fun cancelRecording() = audioRepository.discardCapture()

    enum class PermissionStatus {
        Unknown, Granted, Denied
    }
}
