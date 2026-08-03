package com.dhaval.echo.data.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val openAiClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

// Transcription can take a while for a minutes-long recording (upload + model),
// so it gets a longer read timeout than the text/vision calls.
private val openAiAudioClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .writeTimeout(180, TimeUnit.SECONDS)
    .build()

private val openAiJson = Json { ignoreUnknownKeys = true }

/** The Whisper-successor transcription model: multilingual, auto-detects language. */
private const val OPENAI_TRANSCRIBE_MODEL = "gpt-4o-transcribe"
private const val OPENAI_TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions"

/**
 * Transcribes an audio file with OpenAI. The model detects the spoken language
 * itself (so Gujarati, Hindi, English… come back in the right script) and returns
 * the transcript — this is the whole point of routing to the cloud when a key is
 * present: on-device Whisper struggles with languages like Gujarati.
 */
internal suspend fun callOpenAITranscription(apiKey: String, audioFile: File): String {
    require(audioFile.exists()) { "audio file not found: ${audioFile.absolutePath}" }

    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("model", OPENAI_TRANSCRIBE_MODEL)
        .addFormDataPart("response_format", "json")
        // The recorder writes MPEG-4/AAC .m4a, which the API accepts.
        .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mp4".toMediaType()))
        .build()

    val request = Request.Builder()
        .url(OPENAI_TRANSCRIBE_URL)
        .post(body)
        .header("Authorization", "Bearer $apiKey")
        .build()

    return suspendCancellableCoroutine { cont ->
        val call = openAiAudioClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                try {
                    val raw = response.body?.string() ?: throw IOException("Empty response")
                    if (!response.isSuccessful) {
                        throw IOException("OpenAI transcription error ${response.code}: $raw")
                    }
                    val text = openAiJson.parseToJsonElement(raw).jsonObject["text"]
                        ?.jsonPrimitive?.contentOrNull
                        ?: throw IOException("No text in transcription response: $raw")
                    cont.resume(text)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        })
    }
}

/** The Responses API, OpenAI's current surface (Chat Completions is legacy). */
private const val OPENAI_API_URL = "https://api.openai.com/v1/responses"

/**
 * Balanced tier: vision-capable, and far cheaper than the flagship for what is
 * ultimately a perception task. Describing a photo is not the "complex
 * professional work" the top model is priced for.
 */
private const val OPENAI_MODEL = "gpt-5.6-terra"

/**
 * Text-only completion. The sibling of [callClaude] for OpenAI, so the same
 * provider-agnostic text services can call any provider. [jsonSchema], when
 * given, is enforced through `text.format` strict mode.
 */
internal suspend fun callOpenAI(
    apiKey: String,
    userMessage: String,
    systemPrompt: String = "",
    maxOutputTokens: Int = 512,
    jsonSchema: JsonObject? = null,
    schemaName: String = "response"
): String {
    val body = buildJsonObject {
        put("model", OPENAI_MODEL)
        put("max_output_tokens", maxOutputTokens)
        formatBlock(jsonSchema, schemaName)
        putJsonArray("input") {
            // The Responses API has no separate system field; a developer-role
            // turn is how a system instruction is expressed here.
            if (systemPrompt.isNotBlank()) {
                addJsonObject {
                    put("role", "developer")
                    putJsonArray("content") {
                        addJsonObject { put("type", "input_text"); put("text", systemPrompt) }
                    }
                }
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject { put("type", "input_text"); put("text", userMessage) }
                }
            }
        }
    }
    return postToOpenAI(apiKey, body)
}

/**
 * Sends images to OpenAI and returns the reply text.
 *
 * Mirrors [callClaudeWithImages] so either provider can back the same describer.
 * [jsonSchema], when given, is enforced through `text.format` in strict mode, so
 * the caller parses a guaranteed shape rather than digging JSON out of prose.
 */
internal suspend fun callOpenAIWithImages(
    apiKey: String,
    base64Images: List<String>,
    userMessage: String,
    maxOutputTokens: Int = 512,
    jsonSchema: JsonObject? = null,
    schemaName: String = "response"
): String {
    require(base64Images.isNotEmpty()) { "callOpenAIWithImages needs at least one image" }

    val body = buildJsonObject {
        put("model", OPENAI_MODEL)
        put("max_output_tokens", maxOutputTokens)
        formatBlock(jsonSchema, schemaName)
        putJsonArray("input") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    base64Images.forEach { encoded ->
                        addJsonObject {
                            put("type", "input_image")
                            // Re-encoded to JPEG before upload, so this always holds.
                            put("image_url", "data:image/jpeg;base64,$encoded")
                        }
                    }
                    addJsonObject {
                        put("type", "input_text")
                        put("text", userMessage)
                    }
                }
            }
        }
    }
    return postToOpenAI(apiKey, body)
}

/** Adds the strict `text.format` structured-output block when a schema is given. */
private fun kotlinx.serialization.json.JsonObjectBuilder.formatBlock(
    jsonSchema: JsonObject?,
    schemaName: String
) {
    if (jsonSchema == null) return
    putJsonObject("text") {
        putJsonObject("format") {
            put("type", "json_schema")
            put("name", schemaName)
            put("schema", jsonSchema)
            // Without strict mode the schema is a hint, not a guarantee.
            put("strict", true)
        }
    }
}

/** Shared transport: auth header, error surfacing, response-text extraction. */
private suspend fun postToOpenAI(apiKey: String, body: JsonObject): String {
    val request = Request.Builder()
        .url(OPENAI_API_URL)
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .header("Authorization", "Bearer $apiKey")
        .build()

    return suspendCancellableCoroutine { cont ->
        val call = openAiClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                try {
                    val raw = response.body?.string() ?: throw IOException("Empty response")
                    if (!response.isSuccessful) {
                        throw IOException("OpenAI API error ${response.code}: $raw")
                    }
                    cont.resume(extractOutputText(openAiJson.parseToJsonElement(raw).jsonObject, raw))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        })
    }
}

/**
 * Pulls the reply out of a Responses API body.
 *
 * The SDKs expose a flattened `output_text`, but that is a client-side
 * convenience — over raw HTTP the text lives in `output[].content[]`, so walk
 * that and only fall back to the flattened field if a future response provides
 * it. A refusal is surfaced rather than returned as empty text.
 */
private fun extractOutputText(json: JsonObject, raw: String): String {
    val contents = json["output"]?.jsonArray
        ?.mapNotNull { it.jsonObject["content"]?.jsonArray }
        ?.flatten()
        .orEmpty()

    contents.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "refusal" }
        ?.let { refusal ->
            val reason = refusal.jsonObject["refusal"]?.jsonPrimitive?.content ?: "no reason given"
            throw IOException("OpenAI declined this request: $reason")
        }

    contents.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
        ?.jsonObject?.get("text")?.jsonPrimitive?.content
        ?.let { return it }

    json["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }

    throw IOException("No output text in OpenAI response: $raw")
}
