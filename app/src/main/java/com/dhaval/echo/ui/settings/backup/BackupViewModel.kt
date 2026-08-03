package com.dhaval.echo.ui.settings.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.backup.BackupEngine
import com.dhaval.echo.data.backup.BackupHealthMonitor
import com.dhaval.echo.data.backup.BackupIndex
import com.dhaval.echo.data.backup.BackupIndexEntry
import com.dhaval.echo.data.backup.BackupLocalState
import com.dhaval.echo.data.backup.BackupPasswordStore
import com.dhaval.echo.data.backup.BackupPreferences
import com.dhaval.echo.data.backup.BackupRequest
import com.dhaval.echo.data.backup.BackupScheduler
import com.dhaval.echo.data.backup.BackupVerifier
import com.dhaval.echo.data.backup.describe
import com.dhaval.echo.domain.backup.BackupConditions
import com.dhaval.echo.domain.backup.BackupFrequency
import com.dhaval.echo.domain.backup.BackupHealth
import com.dhaval.echo.domain.backup.BackupOutcome
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupProgress
import com.dhaval.echo.domain.backup.BackupReason
import com.dhaval.echo.domain.backup.BackupSettings
import com.dhaval.echo.domain.backup.BackupTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val loading: Boolean = true,
    val health: BackupHealth? = null,
    val settings: BackupSettings = BackupSettings(),
    val destinationLabel: String? = null,
    val history: List<BackupIndexEntry> = emptyList(),
    val hasStoredPassword: Boolean = false,

    /** Non-null while a backup is running; drives the progress card. */
    val progress: BackupProgress? = null,

    /** Set when an action needs a password that hasn't been created yet. */
    val needsPasswordSetup: Boolean = false,

    /** The archive currently being checked, if any. */
    val verifyingUri: String? = null,

    val message: String? = null
) {
    val isRunning: Boolean get() = progress != null
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: BackupPreferences,
    private val localState: BackupLocalState,
    private val health: BackupHealthMonitor,
    private val index: BackupIndex,
    private val engine: BackupEngine,
    private val scheduler: BackupScheduler,
    private val passwords: BackupPasswordStore,
    private val verifier: BackupVerifier
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private var runningBackup: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val settings = preferences.current()
            // Rebuilt from the folder rather than trusted: archives can be
            // copied in, deleted, or written by another device entirely.
            val entries = runCatching { index.refresh() }.getOrElse { index.entries() }
            _uiState.update {
                it.copy(
                    loading = false,
                    settings = settings,
                    health = health.current(),
                    destinationLabel = localState.destinationLabel,
                    history = entries,
                    hasStoredPassword = passwords.hasPassword
                )
            }
        }
    }

    /**
     * Persists the folder grant.
     *
     * Without [Intent.FLAG_GRANT_WRITE_URI_PERMISSION] taken persistably, the
     * permission dies with the process and every scheduled backup afterwards
     * fails with no obvious cause.
     */
    fun onDestinationPicked(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                localState.destinationUri = uri.toString()
                localState.destinationLabel = DocumentFile.fromTreeUri(context, uri)?.name
                scheduler.reschedule()
            }.onFailure {
                Log.e(TAG, "Could not keep access to the chosen folder", it)
                _uiState.update { state ->
                    state.copy(message = "Echo couldn't keep access to that folder")
                }
            }
            refresh()
        }
    }

    /**
     * @param name optional; a named backup is also exempt from retention, so
     *   this is how someone keeps one indefinitely.
     */
    fun backupNow(profile: BackupProfile, name: String? = null) {
        if (_uiState.value.isRunning) return

        val settings = _uiState.value.settings
        if (!settings.hasDestination) {
            _uiState.update { it.copy(message = "Choose where to save backups first") }
            return
        }
        // Encryption is on by default, so the very first manual backup is where
        // the password gets created. Asking here, rather than failing in the
        // engine, is what makes that a setup step instead of an error.
        if (settings.encryptionEnabled && !passwords.hasPassword) {
            _uiState.update { it.copy(needsPasswordSetup = true) }
            return
        }

        runningBackup = viewModelScope.launch {
            val outcome = engine.run(
                BackupRequest(
                    profile = profile,
                    reason = BackupReason.MANUAL,
                    name = name,
                    // A person who taps "Back up now" and is told "nothing
                    // changed" has been handed a puzzle, not a backup.
                    force = true
                )
            ) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }

            _uiState.update { it.copy(progress = null, message = outcome.describeForUser()) }
            refresh()
        }
    }

    fun cancelBackup() {
        runningBackup?.cancel()
        runningBackup = null
        _uiState.update { it.copy(progress = null, message = "Backup cancelled") }
    }

    fun verify(uri: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(verifyingUri = uri) }
            val password = passwords.read()
            val failure = try {
                verifier.verify(uri, password)
            } finally {
                password?.fill(' ')
            }
            _uiState.update {
                it.copy(
                    verifyingUri = null,
                    message = failure?.describe() ?: "This backup is intact"
                )
            }
        }
    }

    fun delete(uri: String) {
        viewModelScope.launch {
            index.remove(uri)
            refresh()
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun setFrequency(frequency: BackupFrequency) = update {
        preferences.setFrequency(frequency)
        scheduler.reschedule()
    }

    fun setConditions(conditions: BackupConditions) = update {
        preferences.setConditions(conditions)
        scheduler.reschedule()
    }

    fun setIncludeMedia(include: Boolean) = update {
        preferences.setIncludeMedia(include)
        scheduler.reschedule()
    }

    fun setRetentionCount(count: Int) = update { preferences.setRetentionCount(count) }

    fun toggleTrigger(trigger: BackupTrigger, enabled: Boolean) = update {
        val current = preferences.current().triggers
        preferences.setTriggers(if (enabled) current + trigger else current - trigger)
    }

    /**
     * Turning encryption off does not decrypt existing archives — they keep the
     * password they were sealed with. Only future backups change.
     */
    fun setEncryptionEnabled(enabled: Boolean) = update {
        preferences.setEncryptionEnabled(enabled)
        if (!enabled) passwords.clear()
        _uiState.update { it.copy(needsPasswordSetup = enabled && !passwords.hasPassword) }
    }

    /**
     * Sets or changes the backup password.
     *
     * Changing it deliberately leaves old archives alone: re-encrypting a
     * multi-gigabyte file in place is a long operation that can fail halfway
     * and gains nothing. The epoch counter is bumped so history can flag which
     * archives want the previous password.
     */
    fun setPassword(password: CharArray) {
        viewModelScope.launch {
            val changing = passwords.hasPassword
            passwords.save(password)
            password.fill(' ')
            if (changing) localState.passwordEpoch = localState.passwordEpoch + 1
            _uiState.update {
                it.copy(
                    needsPasswordSetup = false,
                    hasStoredPassword = true,
                    message = if (changing) {
                        "New password saved. Older backups still need the previous one."
                    } else {
                        "Backup password saved"
                    }
                )
            }
            refresh()
        }
    }

    fun dismissPasswordSetup() = _uiState.update { it.copy(needsPasswordSetup = false) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            refresh()
        }
    }

    private fun BackupOutcome.describeForUser(): String = when (this) {
        is BackupOutcome.Success -> "Backed up ${summary.counts.memories} memories"
        is BackupOutcome.Skipped -> reason
        is BackupOutcome.Failed -> failure.describe()
        BackupOutcome.Cancelled -> "Backup cancelled"
    }

    private companion object {
        const val TAG = "BackupViewModel"
    }
}
