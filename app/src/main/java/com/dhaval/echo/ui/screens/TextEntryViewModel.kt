package com.dhaval.echo.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.diary.DiaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

data class TextEntryUiState(
    val title: String = "",
    val textContent: String = "",
    val imagePaths: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val savedEntryId: String? = null,
    val error: String? = null
)

@HiltViewModel
class TextEntryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryRepository: DiaryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TextEntryUiState())
    val uiState: StateFlow<TextEntryUiState> = _uiState.asStateFlow()

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }

    fun onTextChange(text: String) = _uiState.update { it.copy(textContent = text) }

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
