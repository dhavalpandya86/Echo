package com.dhaval.echo.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which theme the user wants.
 *
 * [SYSTEM] is the default and is what most people should keep. The explicit
 * choices exist because "follow the system" is not always what someone wants
 * from a diary in particular — reading and writing at night is common enough
 * that people reach for dark deliberately, regardless of what the phone is doing.
 */
enum class AppearanceMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** The label shown on the Appearance row. */
    val label: String
        get() = when (this) {
            SYSTEM -> "Match device"
            LIGHT -> "Light"
            DARK -> "Dark"
        }

    companion object {
        fun from(raw: String?): AppearanceMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * Stores the appearance choice.
 *
 * Shares the existing preferences DataStore rather than opening a second one:
 * two DataStores over one app means two files, two write queues, and no benefit
 * at this scale.
 */
@Singleton
class AppearancePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private companion object {
        val APPEARANCE_MODE = stringPreferencesKey("appearance_mode")
    }

    val mode: Flow<AppearanceMode> =
        dataStore.data.map { AppearanceMode.from(it[APPEARANCE_MODE]) }

    suspend fun setMode(mode: AppearanceMode) {
        dataStore.edit { it[APPEARANCE_MODE] = mode.name }
    }
}
