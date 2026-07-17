package com.dhaval.echo.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_preferences")

@Singleton
class AiPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
        val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    val selectedProviderId: Flow<String> = dataStore.data.map { it[SELECTED_PROVIDER_ID] ?: "local" }
    val claudeApiKey: Flow<String> = dataStore.data.map { it[CLAUDE_API_KEY] ?: "" }
    val openAiApiKey: Flow<String> = dataStore.data.map { it[OPENAI_API_KEY] ?: "" }
    val geminiApiKey: Flow<String> = dataStore.data.map { it[GEMINI_API_KEY] ?: "" }

    suspend fun setSelectedProvider(providerId: String) {
        dataStore.edit { it[SELECTED_PROVIDER_ID] = providerId }
    }

    suspend fun setClaudeApiKey(key: String) {
        dataStore.edit { it[CLAUDE_API_KEY] = key }
    }

    suspend fun setOpenAiApiKey(key: String) {
        dataStore.edit { it[OPENAI_API_KEY] = key }
    }

    suspend fun setGeminiApiKey(key: String) {
        dataStore.edit { it[GEMINI_API_KEY] = key }
    }

    fun getApiKeyForProvider(providerId: String): Flow<String> = when (providerId) {
        "claude" -> claudeApiKey
        "openai" -> openAiApiKey
        "gemini" -> geminiApiKey
        else -> dataStore.data.map { "" }
    }
}
