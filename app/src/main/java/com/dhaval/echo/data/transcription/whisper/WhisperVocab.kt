package com.dhaval.echo.data.transcription.whisper

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * Whisper's GPT-2-style byte-level BPE, decode side only (id → text). Encoding
 * is never needed on device — the model produces token ids and we render them.
 *
 * Each vocab piece is a string of "byte-level unicode" characters; decoding
 * reverses that mapping to raw bytes, then UTF-8 decodes. Special tokens
 * (ids ≥ the first special id) carry no text and are skipped.
 */
class WhisperVocab private constructor(
    private val idToPiece: Array<String?>,
    private val firstSpecialId: Int
) {
    private val unicodeToByte: HashMap<Char, Int> = buildByteDecoder()

    /** Decodes text token ids to a string, skipping special/timestamp tokens. */
    fun decode(ids: List<Int>): String {
        val bytes = ArrayList<Byte>(ids.size * 2)
        for (id in ids) {
            if (id < 0 || id >= firstSpecialId) continue
            val piece = idToPiece.getOrNull(id) ?: continue
            for (ch in piece) {
                val b = unicodeToByte[ch] ?: continue
                bytes.add(b.toByte())
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun buildByteDecoder(): HashMap<Char, Int> {
        // GPT-2 bytes_to_unicode(), inverted.
        val bs = ArrayList<Int>()
        for (b in '!'.code..'~'.code) bs.add(b)
        for (b in '¡'.code..'¬'.code) bs.add(b)
        for (b in '®'.code..'ÿ'.code) bs.add(b)
        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val map = HashMap<Char, Int>(bs.size * 2)
        for (i in bs.indices) map[cs[i].toChar()] = bs[i]
        return map
    }

    companion object {
        fun load(context: Context, cfg: WhisperConfig, assetDir: String = "whisper"): WhisperVocab {
            val text = context.assets.open("$assetDir/vocab.json").bufferedReader().use { it.readText() }
            // vocab.json is { piece: id }; invert into an id-indexed array.
            val obj = Json.parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonObject }
            val idToPiece = arrayOfNulls<String>(cfg.vocab)
            for ((piece, idEl) in obj) {
                val id = idEl.jsonPrimitive.content.toIntOrNull() ?: continue
                if (id in idToPiece.indices) idToPiece[id] = piece
            }
            // Special tokens (<|...|>) begin at eot (<|endoftext|>) in Whisper's vocab.
            return WhisperVocab(idToPiece, firstSpecialId = cfg.eot)
        }
    }
}
