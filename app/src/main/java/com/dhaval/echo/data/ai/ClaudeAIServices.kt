package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val claudeClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private val claudeJson = Json { ignoreUnknownKeys = true }
private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
private const val CLAUDE_MODEL = "claude-opus-4-8"
private const val CLAUDE_TAG = "ClaudeAI"

/**
 * Shared Claude Messages API call. `internal` so other data-layer callers in
 * this module (e.g. the Memory Understanding analyzer) can reuse the same
 * refusal-aware, text-block-filtering client without duplicating HTTP logic.
 */
internal suspend fun callClaude(
    apiKey: String,
    userMessage: String,
    systemPrompt: String = "",
    maxTokens: Int = 1024
): String {
    val body = buildJsonObject {
        put("model", CLAUDE_MODEL)
        put("max_tokens", maxTokens)
        if (systemPrompt.isNotBlank()) put("system", systemPrompt)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", userMessage)
            }
        }
    }
    return postToClaude(apiKey, body)
}

/**
 * The same call with images attached, for the Photo modality.
 *
 * Images go *before* the question in the content array — Claude attends to a
 * question asked after the evidence it refers to. [jsonSchema], when given,
 * constrains the reply through `output_config.format` so the caller can parse it
 * without defending against prose wrapped around the JSON.
 */
internal suspend fun callClaudeWithImages(
    apiKey: String,
    base64Images: List<String>,
    userMessage: String,
    systemPrompt: String = "",
    maxTokens: Int = 1024,
    jsonSchema: JsonObject? = null
): String {
    require(base64Images.isNotEmpty()) { "callClaudeWithImages needs at least one image" }

    val body = buildJsonObject {
        put("model", CLAUDE_MODEL)
        put("max_tokens", maxTokens)
        if (systemPrompt.isNotBlank()) put("system", systemPrompt)
        if (jsonSchema != null) {
            putJsonObject("output_config") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", jsonSchema)
                }
            }
        }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    base64Images.forEach { encoded ->
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                // Re-encoded to JPEG before upload, so this always holds.
                                put("media_type", "image/jpeg")
                                put("data", encoded)
                            }
                        }
                    }
                    addJsonObject {
                        put("type", "text")
                        put("text", userMessage)
                    }
                }
            }
        }
    }
    return postToClaude(apiKey, body)
}

/** Shared transport: headers, refusal check, and first-text-block extraction. */
private suspend fun postToClaude(apiKey: String, body: JsonObject): String {
    val request = Request.Builder()
        .url(CLAUDE_API_URL)
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .build()

    return suspendCancellableCoroutine { cont ->
        val call = claudeClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string() ?: throw IOException("Empty response")
                    if (!response.isSuccessful) {
                        throw IOException("Claude API error ${response.code}: $responseBody")
                    }
                    val json = claudeJson.parseToJsonElement(responseBody).jsonObject

                    // Claude may decline a request: HTTP 200 with stop_reason "refusal"
                    // and no usable text. Surface that rather than returning a blank.
                    val stopReason = json["stop_reason"]?.jsonPrimitive?.content
                    if (stopReason == "refusal") {
                        throw IOException("Claude declined this request (stop_reason: refusal)")
                    }

                    // `content` is a list of polymorphic blocks (text / thinking / tool_use).
                    // Take the first *text* block rather than assuming index 0 is text.
                    val text = json["content"]?.jsonArray
                        ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                        ?.jsonObject?.get("text")?.jsonPrimitive?.content
                        ?: throw IOException("No text block in Claude response: $responseBody")
                    cont.resume(text)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        })
    }
}

class ClaudeConversationService(
    private val apiKey: String,
    private val memoryContextBuilder: MemoryContextBuilder,
    private val conversationRepository: ConversationRepository
) : ConversationService {

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun ask(
        question: String,
        conversationId: String?,
        contextOverride: ConversationContext?
    ): Flow<Message> = flow {
        val targetConversationId = conversationId
            ?: conversationRepository.createConversation(question.take(40) + "...")

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = targetConversationId,
            content = question,
            role = MessageRole.USER,
            createdAt = LocalDateTime.now().format(formatter)
        )
        conversationRepository.addMessage(userMessage)

        val context = memoryContextBuilder.buildContext(question).let {
            var result = ""
            it.collect { c -> result = c }
            result
        }
        val citations = memoryContextBuilder.buildCitations(question).let {
            var result = emptyList<Citation>()
            it.collect { c -> result = c }
            result
        }

        val systemPrompt = """
            You are Echo, a personal AI memory assistant. You help users understand, reflect on, and connect their recorded memories and diary entries.

            ${if (context.isNotBlank()) "Here is the user's relevant memory context:\n\n$context\n\n" else ""}
            Guidelines:
            - Be warm, empathetic, and conversational
            - Reference specific memories by their title when relevant
            - Help the user find patterns, themes, and insights in their life
            - Keep responses concise but meaningful (2-4 paragraphs max)
            - If no relevant memories are found, gently acknowledge that and suggest what they might record
        """.trimIndent()

        val responseText = try {
            callClaude(apiKey, question, systemPrompt)
        } catch (e: Exception) {
            android.util.Log.e(CLAUDE_TAG, "conversation ask failed", e)
            "I'm having trouble connecting right now. Please check your API key in AI Settings and try again."
        }

        val assistantMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = targetConversationId,
            content = responseText,
            role = MessageRole.ASSISTANT,
            createdAt = LocalDateTime.now().format(formatter),
            citations = citations,
            reasoning = if (citations.isNotEmpty()) "Referenced ${citations.size} memories." else null
        )
        conversationRepository.addMessage(assistantMessage)
        emit(assistantMessage)
    }

    override fun getSuggestedQuestions(): Flow<List<String>> = flow {
        emit(listOf(
            "What have I been thinking about lately?",
            "What patterns do you see in my memories?",
            "What projects am I working on?",
            "What ideas have I repeated across entries?"
        ))
    }
}

class ClaudeSummaryService(private val apiKey: String) : SummaryService {
    override fun summarize(text: String): Flow<String> = flow {
        if (text.isBlank()) { emit(""); return@flow }
        val prompt = "Summarize this diary/voice memo entry in 2-3 concise sentences. Capture the key thoughts, emotions, and ideas. Be direct and personal:\n\n$text"
        val result = try {
            callClaude(apiKey, prompt, maxTokens = 200)
        } catch (e: Exception) {
            // A truncated copy of the entry is not a summary. Log loudly and mark
            // the fallback so a silent API failure can't pass for a real result.
            android.util.Log.e(CLAUDE_TAG, "summarize failed — falling back to excerpt", e)
            "[AI unavailable] " + text.take(150) + if (text.length > 150) "..." else ""
        }
        emit(result)
    }
}

class ClaudeTitleGenerationService(private val apiKey: String) : TitleGenerationService {
    override fun generateTitle(text: String): Flow<String> = flow {
        if (text.isBlank()) { emit("Untitled Memory"); return@flow }
        val prompt = "Generate a short, evocative title (4-6 words) for this diary entry. Return ONLY the title, no quotes, no punctuation at the end:\n\n${text.take(600)}"
        val result = try {
            callClaude(apiKey, prompt, maxTokens = 30).trim().removeSurrounding("\"")
        } catch (e: Exception) {
            android.util.Log.e(CLAUDE_TAG, "generateTitle failed — falling back to date title", e)
            "Memory ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d"))}"
        }
        emit(result)
    }
}

class ClaudeTagSuggestionService(private val apiKey: String) : TagSuggestionService {
    override fun suggestTags(text: String): Flow<List<String>> = flow {
        if (text.isBlank()) { emit(emptyList()); return@flow }
        val prompt = "Suggest 3-5 short, relevant tags for this diary entry. Return ONLY the tags, one per line, no numbers, no hashes, no punctuation:\n\n${text.take(600)}"
        val result = try {
            val response = callClaude(apiKey, prompt, maxTokens = 80)
            response.split("\n").map { it.trim() }.filter { it.isNotBlank() && it.length < 30 }.take(5)
        } catch (e: Exception) {
            android.util.Log.e(CLAUDE_TAG, "suggestTags failed — emitting no tags", e)
            emptyList()
        }
        emit(result)
    }
}
