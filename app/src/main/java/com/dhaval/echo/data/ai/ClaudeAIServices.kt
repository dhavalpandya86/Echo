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
private const val CLAUDE_MODEL = "claude-sonnet-4-6"

private suspend fun callClaude(
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
                    val text = json["content"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content
                        ?: "I couldn't generate a response."
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
            text.take(150) + if (text.length > 150) "..." else ""
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
            listOf("Personal", "Reflection")
        }
        emit(result)
    }
}
