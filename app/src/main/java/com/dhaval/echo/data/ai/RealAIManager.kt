package com.dhaval.echo.data.ai

import android.content.Context
import com.dhaval.echo.data.preferences.AiPreferences
import com.dhaval.echo.data.understanding.ClaudeMemoryAnalyzer
import com.dhaval.echo.data.understanding.CloudMemoryAnalyzer
import com.dhaval.echo.data.understanding.EvidenceExtraction
import com.dhaval.echo.domain.ai.*
import com.dhaval.echo.domain.auth.AuthRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealAIManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryEntryDao: com.dhaval.echo.data.db.DiaryEntryDao,
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao,
    private val conversationRepository: ConversationRepository,
    private val memoryContextBuilder: Lazy<MemoryContextBuilder>,
    private val authRepository: AuthRepository,
    private val aiPreferences: AiPreferences,
    private val sttEngine: com.dhaval.echo.domain.transcription.SpeechToTextEngine,
    private val embeddingEngine: com.dhaval.echo.domain.embeddings.EmbeddingEngine,
    private val localMemoryAnalyzers: Set<@JvmSuppressWildcards com.dhaval.echo.domain.understanding.MemoryAnalyzer>,
    private val localPhotoVisualDescriber: com.dhaval.echo.data.understanding.MlKitPhotoVisualDescriber
) : AIManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val providers = listOf(
        LocalProvider(),
        ClaudeProvider(),
        OpenAIProvider(),
        GeminiProvider(),
        OllamaProvider(),
        LMStudioProvider(),
        DisabledProvider()
    )

    private val sttProviders = listOf(
        SimpleSTTProvider("android_offline", "Android Offline", true, true, "Ready"),
        SimpleSTTProvider("whisper_cpp", "Whisper.cpp", false, true, "Coming Soon"),
        SimpleSTTProvider("mlkit", "Google ML Kit", false, true, "Coming Soon"),
        SimpleSTTProvider("gemini", "Gemini", false, false, "Coming Soon"),
        SimpleSTTProvider("openai", "OpenAI", false, false, "Coming Soon")
    )

    private val _availableProviders = MutableStateFlow(providers)
    override val availableProviders: StateFlow<List<AIProvider>> = _availableProviders.asStateFlow()

    private val _currentProvider = MutableStateFlow<AIProvider>(providers.first())
    override val currentProvider: StateFlow<AIProvider> = _currentProvider.asStateFlow()

    private val _availableSttProviders = MutableStateFlow(sttProviders)
    override val availableSttProviders: StateFlow<List<STTProvider>> = _availableSttProviders.asStateFlow()

    private val _currentSttProvider = MutableStateFlow<STTProvider>(sttProviders.first())
    override val currentSttProvider: StateFlow<STTProvider> = _currentSttProvider.asStateFlow()

    private var claudeApiKey: String = ""
    private var openAiApiKey: String = ""
    private var geminiApiKey: String = ""

    init {
        scope.launch {
            aiPreferences.selectedProviderId.collect { id ->
                providers.find { it.id == id }?.let { _currentProvider.value = it }
            }
        }
        scope.launch {
            aiPreferences.selectedSttProviderId.collect { id ->
                sttProviders.find { it.id == id }?.let { _currentSttProvider.value = it }
            }
        }
        scope.launch {
            aiPreferences.claudeApiKey.collect { key ->
                claudeApiKey = key
                val claude = providers.find { it.id == "claude" } as? ClaudeProvider
                claude?.updateStatus(
                    if (key.isNotBlank()) AIProviderStatus.Available else AIProviderStatus.NeedsApiKey
                )
            }
        }
        scope.launch { aiPreferences.openAiApiKey.collect { openAiApiKey = it } }
        scope.launch { aiPreferences.geminiApiKey.collect { geminiApiKey = it } }
    }

    override fun switchProvider(providerId: String) {
        providers.find { it.id == providerId }?.let {
            _currentProvider.value = it
            scope.launch { aiPreferences.setSelectedProvider(providerId) }
        }
    }

    override fun switchSttProvider(providerId: String) {
        sttProviders.find { it.id == providerId }?.let {
            if (it.isEnabled) {
                _currentSttProvider.value = it
                scope.launch { aiPreferences.setSelectedSttProvider(providerId) }
            }
        }
    }

    override fun isCapabilitySupported(capability: AICapability): Boolean {
        val p = _currentProvider.value
        return when (capability) {
            AICapability.Offline -> p.supportsOffline
            AICapability.Streaming -> p.supportsStreaming
            AICapability.Embeddings -> p.supportsEmbeddings
            AICapability.Vision -> p.supportsVision
            AICapability.Audio -> p.supportsAudio
            AICapability.Relationships -> p.supportsRelationships
            AICapability.SemanticSearch -> p.supportsSemanticSearch
            AICapability.Conversation -> p.supportsConversation
        }
    }

    override fun getLanguageDetectionService(): LanguageDetectionService = FakeLanguageDetectionService()

    override fun getTranscriptionService(): TranscriptionService {
        val onDevice = object : TranscriptionService {
            override fun transcribe(audioPath: String): Flow<TranscriptionResult> = flow {
                val result = sttEngine.transcribe(audioPath)
                emit(TranscriptionResult(text = result.transcript, isFinal = true))
            }
        }
        // With an OpenAI key, transcribe in the cloud — its model detects the
        // language (Gujarati, Hindi, English…) and returns the right script,
        // degrading to on-device Whisper on any failure. Other providers don't
        // expose an audio-transcription endpoint here, so they use on-device.
        return if (_currentProvider.value.id == "openai" && openAiApiKey.isNotBlank()) {
            CloudTranscriptionService(onDevice) { file -> callOpenAITranscription(openAiApiKey, file) }
        } else {
            onDevice
        }
    }

    override fun getSpeechToTextEngine(): com.dhaval.echo.domain.transcription.SpeechToTextEngine = sttEngine

    /**
     * A cloud text completion bound to the current provider + key, or null on the
     * free tier. Each provider folds Echo's system prompt in the way its API
     * wants (Claude system field, OpenAI developer turn, Gemini prepended text).
     * This is the single seam every cloud text feature routes through.
     */
    private fun textCompleter(): CloudTextCompleter? = when (_currentProvider.value.id) {
        "claude" -> claudeApiKey.takeIf { it.isNotBlank() }?.let { key ->
            CloudTextCompleter { prompt, max -> callClaude(key, prompt, ECHO_SYSTEM_PROMPT, max) }
        }
        "openai" -> openAiApiKey.takeIf { it.isNotBlank() }?.let { key ->
            CloudTextCompleter { prompt, max -> callOpenAI(key, prompt, ECHO_SYSTEM_PROMPT, max) }
        }
        "gemini" -> geminiApiKey.takeIf { it.isNotBlank() }?.let { key ->
            CloudTextCompleter { prompt, _ -> callGemini(key, "$ECHO_SYSTEM_PROMPT\n\n$prompt") }
        }
        else -> null
    }

    override fun hasCloudKey(): Boolean = textCompleter() != null

    override fun getNarrativeService(): com.dhaval.echo.domain.ai.NarrativeService? =
        textCompleter()?.let { CloudNarrativeService(it) }

    override fun getSummaryService(): SummaryService {
        // Cloud when a key is set (real LLM summary, degrading to the on-device
        // extractive summary on failure); on-device otherwise. No more Fake.
        val completer = textCompleter()
        return if (completer != null) CloudSummaryService(LocalSummarizerService(), completer)
        else LocalSummarizerService()
    }

    override fun getTitleGenerationService(): TitleGenerationService =
        when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isNotBlank()) ClaudeTitleGenerationService(claudeApiKey) else LocalTitleGenerationService()
            else -> LocalTitleGenerationService()
        }

    override fun getTagSuggestionService(): TagSuggestionService =
        when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isNotBlank()) ClaudeTagSuggestionService(claudeApiKey) else LocalTagSuggestionService()
            else -> LocalTagSuggestionService()
        }

    override fun getEmbeddingService(): EmbeddingService = object : EmbeddingService {
        override fun generateEmbedding(text: String): Flow<List<Float>> = flow {
            val result = embeddingEngine.generateEmbedding(text)
            emit(result.vector.toList())
        }
    }

    override fun getMemoryRelationshipService(): MemoryRelationshipService = FakeMemoryRelationshipService()

    override fun getMemoryClassificationService(): MemoryClassificationService =
        when (_currentProvider.value.id) {
            "local" -> LocalClassificationService()
            else -> FakeMemoryClassificationService()
        }

    override fun getSemanticSearchService(): SemanticSearchService =
        when (_currentProvider.value.id) {
            "local" -> LocalSemanticSearchService(diaryEntryDao, intelligenceDao, authRepository)
            else -> FakeSemanticSearchService()
        }

    override fun getTimelineIntelligenceService(): TimelineIntelligenceService =
        when (_currentProvider.value.id) {
            "local" -> LocalTimelineIntelligenceService(diaryEntryDao, intelligenceDao, authRepository)
            else -> FakeTimelineIntelligenceService()
        }

    override fun getConversationService(): ConversationService =
        when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isNotBlank()) {
                ClaudeConversationService(claudeApiKey, memoryContextBuilder.get(), conversationRepository)
            } else {
                RealConversationService(memoryContextBuilder.get(), conversationRepository, this)
            }
            // RealConversationService now talks back through the cloud narrative
            // engine when a key exists (OpenAI/Gemini), and degrades to its
            // on-device template otherwise — so it's the right service for local
            // and every keyed cloud provider. No more FakeConversationService.
            else -> RealConversationService(memoryContextBuilder.get(), conversationRepository, this)
        }

    override fun getMemoryAnalyzers(): List<com.dhaval.echo.domain.understanding.MemoryAnalyzer> {
        val local = localMemoryAnalyzers.toList()
        // A keyed cloud provider does one structured-output extraction call per
        // memory (reliable people/places/projects → populated Worlds), degrading
        // to the local heuristics on failure. No key → on-device.
        return when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isNotBlank()) {
                listOf(ClaudeMemoryAnalyzer(claudeApiKey, local))
            } else local
            "openai" -> if (openAiApiKey.isNotBlank()) {
                listOf(CloudMemoryAnalyzer(local) { msg ->
                    callOpenAI(openAiApiKey, msg, EvidenceExtraction.SYSTEM_PROMPT, maxOutputTokens = 1024)
                })
            } else local
            "gemini" -> if (geminiApiKey.isNotBlank()) {
                listOf(CloudMemoryAnalyzer(local) { msg ->
                    callGemini(geminiApiKey, "${EvidenceExtraction.SYSTEM_PROMPT}\n\n$msg")
                })
            } else local
            else -> local
        }
    }

    /**
     * Cloud vision for whichever of the three the user trusts enough to give a
     * key to; on-device otherwise. Each branch passes the on-device describer in
     * as the fallback, so a failed, refused, or unaffordable call still leaves
     * the memory with labels and a place rather than nothing.
     *
     * Only the transport differs between providers — prompt, schema, encoding
     * and parsing all live in CloudPhotoVisualDescriber.
     */
    override fun getPhotoVisualDescriber(): com.dhaval.echo.domain.understanding.PhotoVisualDescriber {
        val describer = com.dhaval.echo.data.understanding.CloudPhotoVisualDescriber
        return when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isBlank()) localPhotoVisualDescriber else {
                com.dhaval.echo.data.understanding.CloudPhotoVisualDescriber(
                    fallback = localPhotoVisualDescriber,
                    providerTag = "Claude"
                ) { images, schema ->
                    callClaudeWithImages(
                        apiKey = claudeApiKey,
                        base64Images = images,
                        userMessage = describer.USER_PROMPT,
                        systemPrompt = describer.SYSTEM_PROMPT,
                        maxTokens = 512,
                        jsonSchema = schema
                    )
                }
            }

            "openai" -> if (openAiApiKey.isBlank()) localPhotoVisualDescriber else {
                com.dhaval.echo.data.understanding.CloudPhotoVisualDescriber(
                    fallback = localPhotoVisualDescriber,
                    providerTag = "OpenAI"
                ) { images, schema ->
                    callOpenAIWithImages(
                        apiKey = openAiApiKey,
                        base64Images = images,
                        // No separate system field on this path.
                        userMessage = "${describer.SYSTEM_PROMPT}\n\n${describer.USER_PROMPT}",
                        maxOutputTokens = 512,
                        jsonSchema = schema,
                        schemaName = "photo_understanding"
                    )
                }
            }

            "gemini" -> if (geminiApiKey.isBlank()) localPhotoVisualDescriber else {
                com.dhaval.echo.data.understanding.CloudPhotoVisualDescriber(
                    fallback = localPhotoVisualDescriber,
                    providerTag = "Gemini"
                ) { images, schema ->
                    callGeminiWithImages(
                        apiKey = geminiApiKey,
                        base64Images = images,
                        userMessage = "${describer.SYSTEM_PROMPT}\n\n${describer.USER_PROMPT}",
                        jsonSchema = schema
                    )
                }
            }

            else -> localPhotoVisualDescriber
        }
    }
}

private data class SimpleSTTProvider(
    override val id: String,
    override val displayName: String,
    override val isEnabled: Boolean,
    override val isOffline: Boolean,
    override val statusLabel: String
) : STTProvider
