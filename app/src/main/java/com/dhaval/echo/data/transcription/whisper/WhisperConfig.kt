package com.dhaval.echo.data.transcription.whisper

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Model + decoding constants, loaded once from assets/whisper/whisper_config.json
 * (dumped from the exact openai/whisper-base export used to build the ONNX models,
 * so the ids and dimensions can never drift from the shipped weights).
 */
@Serializable
data class WhisperConfig(
    val sot: Int,
    val eot: Int,
    val transcribe: Int,
    val translate: Int,
    val notimestamps: Int,
    val lang_lo: Int,
    val lang_hi: Int,
    val n_layers: Int,
    val n_heads: Int,
    val head_dim: Int,
    val hidden: Int,
    val vocab: Int,
    val n_fft: Int,
    val hop: Int,
    val n_mels: Int,
    val sr: Int,
    val n_frames: Int,
    val n_samples: Int,
    val suppress: List<Int>,
    val begin_suppress: List<Int>
) {
    companion object {
        fun load(context: Context, assetDir: String = "whisper"): WhisperConfig {
            val text = context.assets.open("$assetDir/whisper_config.json")
                .bufferedReader().use { it.readText() }
            return Json { ignoreUnknownKeys = true }.decodeFromString(text)
        }
    }
}
