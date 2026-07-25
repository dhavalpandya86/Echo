package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.AIProvider
import com.dhaval.echo.domain.ai.AIProviderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base class for AI providers.
 */
abstract class BaseAIProvider(
    override val id: String,
    override val displayName: String,
    override val supportsOffline: Boolean = false,
    override val supportsStreaming: Boolean = false,
    override val supportedLanguages: List<String> = listOf("en"),
    override val supportsEmbeddings: Boolean = false,
    override val supportsVision: Boolean = false,
    override val supportsAudio: Boolean = false,
    override val supportsRelationships: Boolean = false,
    override val supportsSemanticSearch: Boolean = false,
    override val supportsConversation: Boolean = false,
    initialStatus: AIProviderStatus = AIProviderStatus.Available
) : AIProvider {
    protected val _status = MutableStateFlow(initialStatus)
    override val status: StateFlow<AIProviderStatus> = _status.asStateFlow()

    fun updateStatus(newStatus: AIProviderStatus) {
        _status.value = newStatus
    }
}

class GeminiProvider : BaseAIProvider(
    id = "gemini",
    displayName = "Google Gemini",
    supportsStreaming = true,
    supportsVision = true,
    supportsAudio = true,
    supportsSemanticSearch = true,
    supportsConversation = true
)

class OpenAIProvider : BaseAIProvider(
    id = "openai",
    displayName = "OpenAI",
    supportsStreaming = true,
    supportsVision = true,
    supportsAudio = true,
    supportsEmbeddings = true,
    supportsConversation = true
)

class ClaudeProvider : BaseAIProvider(
    id = "claude",
    displayName = "Anthropic Claude",
    supportsStreaming = true,
    supportsVision = true,
    supportsConversation = true,
    supportsRelationships = true,
    supportsSemanticSearch = true,
    supportedLanguages = listOf("en", "es", "fr", "de", "pt", "it", "ja", "ko", "zh"),
    initialStatus = AIProviderStatus.NeedsApiKey
)

class OllamaProvider : BaseAIProvider(
    id = "ollama",
    displayName = "Ollama (Local)",
    supportsOffline = true,
    supportsStreaming = true,
    supportsEmbeddings = true
)

class LMStudioProvider : BaseAIProvider(
    id = "lmstudio",
    displayName = "LM Studio",
    supportsOffline = true,
    supportsStreaming = true
)

class LocalProvider : BaseAIProvider(
    id = "local",
    displayName = "On-Device (ML Kit / MediaPipe)",
    supportsOffline = true,
    supportsAudio = true,
    supportsConversation = true
)

class DisabledProvider : BaseAIProvider(
    id = "disabled",
    displayName = "AI Disabled",
    initialStatus = AIProviderStatus.Unavailable
)
