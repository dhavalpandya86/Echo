package com.dhaval.echo.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * The user's preferences, as data rather than as a file.
 *
 * The obvious implementation is to copy `ai_preferences.preferences_pb` into
 * the archive. It is rejected for two reasons: the file is protobuf, so
 * removing a single value from it would mean hand-parsing DataStore's wire
 * format; and restoring a whole file cannot preserve the device-local values
 * that must not be replaced. Reading the preferences into a typed map makes
 * both trivial.
 */
@Serializable
data class SettingsSnapshot(
    val values: Map<String, SettingValue> = emptyMap()
)

/**
 * DataStore keys are typed, and the type is not recoverable from the value, so
 * it travels alongside it. An unrecognised [type] on read is skipped rather
 * than guessed — a preference restored as the wrong type is worse than one that
 * quietly falls back to its default.
 */
@Serializable
data class SettingValue(
    val type: String,
    val value: String? = null,
    val values: List<String>? = null
)

class SettingsSnapshotter(
    private val dataStore: DataStore<Preferences>
) {
    /**
     * @param includeApiKeys false for unencrypted archives. A backup written in
     *   the clear to a folder the user might sync, share or hand to someone is
     *   not a place for their Claude/OpenAI/Gemini credentials — those are
     *   billable secrets, not preferences. Everything else is still captured, so
     *   an unencrypted restore brings back the whole app minus three keys the
     *   user can paste again.
     */
    suspend fun capture(includeApiKeys: Boolean): SettingsSnapshot {
        val preferences = dataStore.data.first()
        val values = buildMap {
            preferences.asMap().forEach { (key, value) ->
                if (!includeApiKeys && key.name in API_KEY_NAMES) return@forEach
                encode(value)?.let { put(key.name, it) }
            }
        }
        return SettingsSnapshot(values)
    }

    /**
     * Writes the snapshot back.
     *
     * Note what this deliberately does *not* clear: existing preferences absent
     * from the snapshot survive. That is what keeps an unencrypted restore from
     * wiping working API keys that the archive simply never carried.
     */
    suspend fun apply(snapshot: SettingsSnapshot) {
        dataStore.edit { preferences ->
            snapshot.values.forEach { (name, encoded) ->
                when (encoded.type) {
                    TYPE_BOOLEAN -> encoded.value?.toBooleanStrictOrNull()
                        ?.let { preferences[booleanPreferencesKey(name)] = it }

                    TYPE_INT -> encoded.value?.toIntOrNull()
                        ?.let { preferences[intPreferencesKey(name)] = it }

                    TYPE_LONG -> encoded.value?.toLongOrNull()
                        ?.let { preferences[longPreferencesKey(name)] = it }

                    TYPE_FLOAT -> encoded.value?.toFloatOrNull()
                        ?.let { preferences[floatPreferencesKey(name)] = it }

                    TYPE_DOUBLE -> encoded.value?.toDoubleOrNull()
                        ?.let { preferences[doublePreferencesKey(name)] = it }

                    TYPE_STRING -> encoded.value
                        ?.let { preferences[stringPreferencesKey(name)] = it }

                    TYPE_STRING_SET -> encoded.values
                        ?.let { preferences[stringSetPreferencesKey(name)] = it.toSet() }
                }
            }
        }
    }

    private fun encode(value: Any?): SettingValue? = when (value) {
        is Boolean -> SettingValue(TYPE_BOOLEAN, value.toString())
        is Int -> SettingValue(TYPE_INT, value.toString())
        is Long -> SettingValue(TYPE_LONG, value.toString())
        is Float -> SettingValue(TYPE_FLOAT, value.toString())
        is Double -> SettingValue(TYPE_DOUBLE, value.toString())
        is String -> SettingValue(TYPE_STRING, value)
        is Set<*> -> SettingValue(TYPE_STRING_SET, values = value.map(Any?::toString))
        else -> null
    }

    private companion object {
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_INT = "int"
        const val TYPE_LONG = "long"
        const val TYPE_FLOAT = "float"
        const val TYPE_DOUBLE = "double"
        const val TYPE_STRING = "string"
        const val TYPE_STRING_SET = "stringSet"

        /** Mirrors the keys in [com.dhaval.echo.data.preferences.AiPreferences]. */
        val API_KEY_NAMES = setOf("claude_api_key", "openai_api_key", "gemini_api_key")
    }
}
