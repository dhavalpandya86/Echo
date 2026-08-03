package com.dhaval.echo.data.understanding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.dhaval.echo.domain.understanding.PhotoVisualDescriber
import com.dhaval.echo.domain.understanding.VisualUnderstanding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Cloud photo understanding, used only when the user has supplied their own key
 * for a provider they trust. Where [MlKitPhotoVisualDescriber] can offer only
 * disconnected nouns ("Forklift, Carton"), this writes what the photo shows.
 *
 * Provider-agnostic on purpose: encoding, prompt, schema, parsing and fallback
 * are identical across Claude, OpenAI and Gemini, so only the transport differs
 * and it arrives as [send]. Adding a fourth provider is one lambda, not another
 * copy of this file.
 *
 * Echo's default stays fully on-device — this is the opt-in half of that
 * bargain, so it is built to fail *backwards*: any error, refusal, or malformed
 * reply falls through to the on-device describer rather than leaving the memory
 * with nothing. Photos leave the device only on this path, and only because the
 * user chose it.
 */
class CloudPhotoVisualDescriber(
    private val fallback: PhotoVisualDescriber,
    private val providerTag: String,
    private val send: suspend (base64Images: List<String>, schema: JsonObject) -> String
) : PhotoVisualDescriber {

    override suspend fun describe(imagePaths: List<String>): VisualUnderstanding {
        if (imagePaths.isEmpty()) return fallback.describe(imagePaths)

        // The on-device pass is cheap and supplies EXIF places, which no cloud
        // model can know — so it runs regardless and the reply enriches it.
        val local = runCatching { fallback.describe(imagePaths) }
            .getOrElse { VisualUnderstanding() }

        val encoded = withContext(Dispatchers.IO) {
            imagePaths.take(MAX_IMAGES).mapNotNull { encodeForUpload(it) }
        }
        if (encoded.isEmpty()) return local

        val reply = runCatching { send(encoded, RESPONSE_SCHEMA) }
            .getOrElse {
                Log.w(TAG, "$providerTag description failed; keeping the on-device result", it)
                return local
            }

        return parse(reply)?.let { cloud ->
            // Places come from EXIF, which only the local pass can read.
            cloud.copy(places = local.places)
        } ?: local
    }

    /** Null when the reply can't be read — the caller then keeps the local result. */
    private fun parse(reply: String): VisualUnderstanding? = runCatching {
        val obj = json.parseToJsonElement(reply).jsonObject
        val description = obj["description"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (description.isBlank()) return null

        VisualUnderstanding(
            labels = obj["labels"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf(String::isNotBlank) }
                ?.take(MAX_LABELS)
                .orEmpty(),
            faceCount = obj["people_count"]?.jsonPrimitive?.int?.coerceAtLeast(0) ?: 0,
            description = description
        )
    }.getOrElse {
        Log.w(TAG, "Could not read the $providerTag reply as JSON", it)
        null
    }

    /**
     * Decodes at a bounded size and re-encodes as JPEG. Phone photos are far
     * larger than any of these models needs; sending them whole would cost the
     * user extra tokens and upload time for detail that changes nothing in the
     * answer — and Gemini rejects inline requests over 20 MB outright.
     */
    private fun encodeForUpload(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            // Power-of-two subsampling happens during decode, so the full-size
            // bitmap is never allocated.
            inSampleSize = generateSequence(1) { it * 2 }
                .first { longEdge / it <= MAX_EDGE_PX }
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null

        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        bitmap.recycle()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrElse {
        Log.w(TAG, "Could not read image for upload: $path", it)
        null
    }

    companion object {
        private const val TAG = "CloudPhotoVisual"

        /** Enough for a memory's photos without turning one save into a large bill. */
        private const val MAX_IMAGES = 4
        private const val MAX_LABELS = 6
        private const val MAX_EDGE_PX = 1024
        private const val JPEG_QUALITY = 85

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Kept separate because Claude takes a first-class system prompt, while
         * the OpenAI and Gemini calls fold it into the user turn.
         */
        internal const val SYSTEM_PROMPT =
            "You describe photos from someone's personal diary. Report only what is " +
                "visibly there — never guess at names, relationships, places, or events " +
                "the image does not show. If the photo is unclear, say so plainly rather " +
                "than inventing detail."

        internal const val USER_PROMPT =
            "Describe this memory's photos in one or two plain sentences, as a friend " +
                "would when reminding someone what the moment was. Then list the main " +
                "objects or scene tags, and count the people visible."

        /**
         * Constrains the reply so the parser never has to dig JSON out of prose.
         * Deliberately shallow and free of numeric bounds: all three providers
         * accept only a subset of JSON Schema, and Gemini rejects deeply nested
         * schemas. `additionalProperties: false` plus a complete `required` list
         * is what OpenAI's strict mode demands.
         */
        internal val RESPONSE_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("description") {
                    put("type", "string")
                    put("description", "One or two sentences describing what the photos show.")
                }
                putJsonObject("labels") {
                    put("type", "array")
                    put("description", "Main objects or scene tags, most prominent first.")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("people_count") {
                    put("type", "integer")
                    put("description", "How many people are visible; 0 if none.")
                }
            }
            put("required", buildJsonArray {
                add("description")
                add("labels")
                add("people_count")
            })
            put("additionalProperties", false)
        }
    }
}
