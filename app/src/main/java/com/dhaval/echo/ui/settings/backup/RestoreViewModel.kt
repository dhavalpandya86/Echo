package com.dhaval.echo.ui.settings.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.backup.ArchiveReader
import com.dhaval.echo.data.backup.BackupPasswordStore
import com.dhaval.echo.data.backup.RestoreEngine
import com.dhaval.echo.data.backup.describe
import com.dhaval.echo.domain.backup.RestoreOutcome
import com.dhaval.echo.domain.backup.RestorePlan
import com.dhaval.echo.domain.backup.RestoreProgress
import com.dhaval.echo.domain.backup.RestoreStage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RestoreUiState(
    val archiveUri: String? = null,
    val archiveName: String? = null,
    val createdAtMillis: Long = 0,
    val sizeBytes: Long = 0,
    val isEncrypted: Boolean = false,

    /** True once the header says encrypted and no password has been accepted. */
    val needsPassword: Boolean = false,
    val wrongPassword: Boolean = false,

    val progress: RestoreProgress? = null,
    val plan: RestorePlan? = null,
    val error: String? = null,

    /** Set when the swap is done and only the restart remains. */
    val readyToRestart: Boolean = false
) {
    val isWorking: Boolean get() = progress != null && plan == null && !readyToRestart
}

/**
 * Drives the restore flow: choose a file, unlock it, see what's in it, confirm.
 *
 * The confirmation step is meaningful here rather than ceremonial, because by
 * the time it appears the entire restore has already been carried out in
 * staging. What the user is approving is a swap of directories that has been
 * proven to work — not the start of an operation that might fail halfway.
 */
@HiltViewModel
class RestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RestoreEngine,
    private val passwords: BackupPasswordStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(RestoreUiState())
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    /**
     * Reads the plaintext envelope so the archive can be described before any
     * password is asked for — otherwise the only way to find out what a file is
     * would be to successfully decrypt it.
     */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } // A one-shot grant is enough for this flow; failure here is fine.

            val envelope = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.let(ArchiveReader::readHeaderOnly)
                }.getOrNull()
            }

            if (envelope == null) {
                _uiState.value = RestoreUiState(error = "That file isn't an Echo backup")
                return@launch
            }

            _uiState.value = RestoreUiState(
                archiveUri = uri.toString(),
                archiveName = uri.lastPathSegment?.substringAfterLast('/'),
                createdAtMillis = envelope.createdAtMillis,
                sizeBytes = 0,
                isEncrypted = envelope.encrypted,
                needsPassword = envelope.encrypted
            )

            // An unencrypted archive has nothing to unlock, so go straight on.
            if (!envelope.encrypted) stage(password = null)
        }
    }

    fun submitPassword(password: CharArray) {
        _uiState.update { it.copy(needsPassword = false, wrongPassword = false) }
        stage(password)
    }

    /** Tries the password this device already holds, before asking the user. */
    fun tryStoredPassword() {
        val stored = passwords.read() ?: return
        submitPassword(stored)
    }

    private fun stage(password: CharArray?) {
        val uri = _uiState.value.archiveUri ?: return
        viewModelScope.launch {
            val outcome = engine.stage(uri, password) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            password?.fill(' ')

            when (outcome) {
                is RestoreOutcome.Staged -> _uiState.update {
                    it.copy(plan = outcome.plan, progress = RestoreProgress(RestoreStage.READY))
                }

                is RestoreOutcome.Failed -> {
                    val wrongPassword =
                        outcome.failure is com.dhaval.echo.domain.backup.BackupFailure.WrongPassword
                    _uiState.update {
                        it.copy(
                            progress = null,
                            needsPassword = wrongPassword,
                            wrongPassword = wrongPassword,
                            error = if (wrongPassword) null else outcome.failure.describe()
                        )
                    }
                }

                else -> _uiState.update { it.copy(progress = null) }
            }
        }
    }

    fun confirm() {
        viewModelScope.launch {
            when (val outcome = engine.commit { progress ->
                _uiState.update { it.copy(progress = progress) }
            }) {
                is RestoreOutcome.Committed ->
                    _uiState.update { it.copy(readyToRestart = true, plan = null) }

                is RestoreOutcome.Failed -> _uiState.update {
                    it.copy(progress = null, error = outcome.failure.describe())
                }

                else -> Unit
            }
        }
    }

    fun cancel() {
        viewModelScope.launch {
            runCatching { engine.discard() }
                .onFailure { Log.w(TAG, "Could not discard staged restore", it) }
            _uiState.value = RestoreUiState()
        }
    }

    /** Room and DataStore are holding files that no longer exist. */
    fun restart() = engine.restart()

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private companion object {
        const val TAG = "RestoreViewModel"
    }
}
