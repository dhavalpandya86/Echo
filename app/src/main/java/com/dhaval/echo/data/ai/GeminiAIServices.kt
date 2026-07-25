package com.dhaval.echo.data.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val geminiClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

private val geminiJson = Json { ignoreUnknownKeys = true }

private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"

/**
 * Flash tier: vision-capable and the cheapest of the three providers for what is
 * ultimately a perception task.
 */
private const val GEMINI_MODEL = "gemini-3.5-flash"

/**
 * Text-only completion. The sibling of [callClaude] for Gemini. Gemini takes no
 * separate system field here, so callers fold instructions into [userMessage].
 */
internal suspend fun callGemini(
    apiKey: String,
    userMessage: String,
    jsonSchema: JsonObject? = null
): String {
    val body = buildJsonObject {
        put("model", GEMINI_MODEL)
        responseFormat(jsonSchema)
        putJsonArray("input") {
            addJsonObject {
                put("type", "text")
                put("text", userMessage)
            }
        }
    }
    return postToGemini(apiKey, body)
}

/**
 * Sends images to Gemini and returns the reply text.
 *
 * Mirrors [callClaudeWithImages] and [callOpenAIWithImages] so any of the three
 * can back the same describer. Gemini takes no separate system prompt here, so
 * callers fold their instructions into [userMessage].
 */
internal suspend fun callGeminiWithImages(
    apiKey: String,
    base64Images: List<String>,
    userMessage: String,
    jsonSchema: JsonObject? = null
): String {
    require(base64Images.isNotEmpty()) { "callGeminiWithImages needs at least one image" }

    val body = buildJsonObject {
        put("model", GEMINI_MODEL)
        responseFormat(jsonSchema)
        putJsonArray("input") {
            base64Images.forEach { encoded ->
                addJsonObject {
                    put("type", "image")
                    // Re-encoded to JPEG before upload, so this always holds.
                    put("mime_type", "image/jpeg")
                    put("data", encoded)
                }
            }
            addJsonObject {
                put("type", "text")
                put("text", userMessage)
            }
        }
    }
    return postToGemini(apiKey, body)
}

/** Adds the JSON `response_format` block when a schema is given. */
private fun kotlinx.serialization.json.JsonObjectBuilder.responseFormat(jsonSchema: JsonObject?) {
    if (jsonSchema == null) return
    putJsonObject("response_format") {
        put("type", "text")
        put("mime_type", "application/json")
        put("schema", jsonSchema)
    }
}

/** Shared transport: key header, error surfacing, response-text extraction. */
private suspend fun postToGemini(apiKey: String, body: JsonObject): String {
    val request = Request.Builder()
        .url(GEMINI_API_URL)
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        // Header rather than ?key= so the secret never lands in a URL, where it
        // would be far easier to leak into a log or crash report.
        .header("x-goog-api-key", apiKey)
        .build()

    return suspendCancellableCoroutine { cont ->
        val call = geminiClient.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                try {
                    val raw = response.body?.string() ?: throw IOException("Empty response")
                    if (!response.isSuccessful) {
                        throw IOException("Gemini API error ${response.code}: $raw")
                    }
                    cont.resume(extractGeminiText(geminiJson.parseToJsonElement(raw).jsonObject, raw))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        })
    }
}

/**
 * Pulls the reply out of a Gemini body.
 *
 * The SDKs expose a flattened `output_text`, but that is a client-side
 * convenience, and the Interactions API is new enough that the raw envelope is
 * worth treating as unsettled — so try the flattened field, the interactions
 * `output[]` shape, and the older `candidates[]` shape before giving up. The
 * describer degrades to on-device on a throw, so a shape change costs the user
 * a worse description, never a crash.
 */
private fun extractGeminiText(json: JsonObject, raw: String): String {
    json["output_text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }

    json["output"]?.jsonArray
        ?.flatMap { entry ->
            val obj = entry.jsonObject
            obj["content"]?.jsonArray ?: listOfNotNull(entry.takeIf { obj.containsKey("text") })
        }
        ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    json["candidates"]?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
        ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    throw IOException("No text in Gemini response: $raw")
}
