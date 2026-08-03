package com.dhaval.echo.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.dhaval.echo.domain.backup.BackupConditions
import com.dhaval.echo.domain.backup.BackupFrequency
import com.dhaval.echo.domain.backup.BackupSettings
import com.dhaval.echo.domain.backup.BackupTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The user's backup choices.
 *
 * Lives in the shared `ai_preferences` DataStore — the same store
 * [com.dhaval.echo.data.preferences.AiPreferences] and `AppearancePreferences`
 * use, per the one-store-per-app rule in `PreferencesModule`. That store *is*
 * included in backups and *is* replaced by a restore, which is exactly right
 * for everything here: how often someone wants backups, under what conditions,
 * and whether to encrypt are decisions that should follow them to a new phone.
 *
 * Anything that must not survive that replacement lives in [BackupLocalState].
 */
class BackupPreferences(
    private val dataStore: DataStore<Preferences>,
    private val localState: BackupLocalState
) {
    val frequency: Flow<BackupFrequency> =
        dataStore.data.map { BackupFrequency.from(it[FREQUENCY]) }

    val conditions: Flow<BackupConditions> = dataStore.data.map { prefs ->
        BackupConditions(
            requireCharging = prefs[REQUIRE_CHARGING] ?: true,
            requireUnmetered = prefs[REQUIRE_UNMETERED] ?: false,
            requireBatteryNotLow = prefs[REQUIRE_BATTERY_OK] ?: true,
            requireDeviceIdle = prefs[REQUIRE_IDLE] ?: false
        )
    }

    val includeMedia: Flow<Boolean> = dataStore.data.map { it[INCLUDE_MEDIA] ?: true }

    val encryptionEnabled: Flow<Boolean> = dataStore.data.map { it[ENCRYPTION_ENABLED] ?: true }

    val retentionCount: Flow<Int> =
        dataStore.data.map { it[RETENTION] ?: BackupSettings.DEFAULT_RETENTION }

    val triggers: Flow<Set<BackupTrigger>> = dataStore.data.map { prefs ->
        prefs[TRIGGERS]
            ?.mapNotNull { raw -> runCatching { BackupTrigger.valueOf(raw) }.getOrNull() }
            ?.toSet()
            ?: BackupTrigger.DEFAULTS
    }

    val recordingBatchSize: Flow<Int> =
        dataStore.data.map { it[RECORDING_BATCH] ?: BackupTrigger.DEFAULT_RECORDING_BATCH }

    /**
     * Everything in one object, with the device-local half spliced in from
     * [BackupLocalState]. Callers get a single coherent picture without having
     * to know which half of it a restore is allowed to touch.
     */
    val settings: Flow<BackupSettings> = combine(
        frequency, conditions, includeMedia, encryptionEnabled, retentionCount
    ) { frequency, conditions, includeMedia, encryption, retention ->
        BackupSettings(
            frequency = frequency,
            conditions = conditions,
            includeMedia = includeMedia,
            encryptionEnabled = encryption,
            retentionCount = retention
        )
    }.combine(triggers) { settings, triggers ->
        settings.copy(triggers = triggers)
    }.combine(recordingBatchSize) { settings, batch ->
        settings.copy(
            recordingBatchSize = batch,
            destinationUri = localState.destinationUri,
            passwordEpoch = localState.passwordEpoch
        )
    }

    /** A one-shot read, for workers that have no business collecting a Flow. */
    suspend fun current(): BackupSettings = settings.first()

    suspend fun setFrequency(value: BackupFrequency) =
        dataStore.edit { it[FREQUENCY] = value.name }

    suspend fun setConditions(value: BackupConditions) = dataStore.edit {
        it[REQUIRE_CHARGING] = value.requireCharging
        it[REQUIRE_UNMETERED] = value.requireUnmetered
        it[REQUIRE_BATTERY_OK] = value.requireBatteryNotLow
        it[REQUIRE_IDLE] = value.requireDeviceIdle
    }

    suspend fun setIncludeMedia(value: Boolean) = dataStore.edit { it[INCLUDE_MEDIA] = value }

    suspend fun setEncryptionEnabled(value: Boolean) =
        dataStore.edit { it[ENCRYPTION_ENABLED] = value }

    suspend fun setRetentionCount(value: Int) = dataStore.edit { it[RETENTION] = value }

    suspend fun setTriggers(value: Set<BackupTrigger>) =
        dataStore.edit { prefs -> prefs[TRIGGERS] = value.mapTo(mutableSetOf()) { it.name } }

    suspend fun setRecordingBatchSize(value: Int) =
        dataStore.edit { it[RECORDING_BATCH] = value.coerceAtLeast(1) }

    private companion object {
        val FREQUENCY = stringPreferencesKey("backup_frequency")
        val REQUIRE_CHARGING = booleanPreferencesKey("backup_require_charging")
        val REQUIRE_UNMETERED = booleanPreferencesKey("backup_require_unmetered")
        val REQUIRE_BATTERY_OK = booleanPreferencesKey("backup_require_battery_ok")
        val REQUIRE_IDLE = booleanPreferencesKey("backup_require_idle")
        val INCLUDE_MEDIA = booleanPreferencesKey("backup_include_media")
        val ENCRYPTION_ENABLED = booleanPreferencesKey("backup_encryption_enabled")
        val RETENTION = intPreferencesKey("backup_retention_count")
        val TRIGGERS = stringSetPreferencesKey("backup_triggers")
        val RECORDING_BATCH = intPreferencesKey("backup_recording_batch")
    }
}
