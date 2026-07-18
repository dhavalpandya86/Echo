package com.dhaval.echo.data.transcription.whisper

import android.content.Context
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Whisper log-mel front end, bit-close to openai-whisper / transformers
 * WhisperFeatureExtractor (validated in WhisperMelTest against a reference
 * dumped from the Python pipeline).
 *
 *   pad/trim to 30 s (480000 samples) → reflect-pad n_fft/2 each side
 *   → framed STFT (n_fft=400, hop=160, periodic Hann) → power spectrum
 *   → mel filterbank [80×201] → log10 → clamp/normalise to [-1, +1]-ish.
 *
 * Output is the [80, 3000] `input_features` tensor the ONNX encoder expects,
 * flattened row-major (mel-major).
 */
class WhisperMel(
    private val nFft: Int,       // 400
    private val hop: Int,        // 160
    private val nMels: Int,      // 80
    private val nFrames: Int,    // 3000
    private val melFilters: FloatArray  // [nMels * nFreq], row-major (mel-major)
) {
    private val nFreq = nFft / 2 + 1          // 201
    private val pad = nFft / 2                // 200
    // Periodic Hann window: w[n] = 0.5 - 0.5*cos(2πn/N).
    private val window = FloatArray(nFft) { n ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * n / nFft)).toFloat()
    }
    // Precomputed DFT bases: cosT[k*nFft+n], sinT[k*nFft+n] for k in 0..nFreq-1.
    private val cosT = FloatArray(nFreq * nFft)
    private val sinT = FloatArray(nFreq * nFft)

    init {
        for (k in 0 until nFreq) {
            for (n in 0 until nFft) {
                val a = 2.0 * Math.PI * k * n / nFft
                cosT[k * nFft + n] = cos(a).toFloat()
                sinT[k * nFft + n] = sin(a).toFloat()
            }
        }
    }

    /**
     * @param pcm mono 16 kHz samples for one 30 s window. Shorter input is
     *   zero-padded; longer is truncated (the caller windows long audio).
     * @return FloatArray of size nMels*nFrames, row-major [mel][frame].
     */
    fun logMel(pcm: FloatArray): FloatArray {
        val nSamples = nFrames * hop                       // 480000
        val padded = FloatArray(nSamples + 2 * pad)
        // centre: samples, zero-padded to 30 s
        val copy = minOf(pcm.size, nSamples)
        System.arraycopy(pcm, 0, padded, pad, copy)
        // reflect padding (exclude the edge sample), matching torch.stft center=True
        for (k in 1..pad) {
            padded[pad - k] = padded[pad + k]                          // left
            padded[pad + nSamples - 1 + k] = padded[pad + nSamples - 1 - k]  // right
        }

        // power spectrogram [nFreq][nFrames], then mel-projected + log-compressed
        val mel = FloatArray(nMels * nFrames)
        val frameWin = FloatArray(nFft)
        val power = FloatArray(nFreq)
        var globalMax = Float.NEGATIVE_INFINITY

        // log10(x) = ln(x) / ln(10)
        val invLn10 = (1.0 / ln(10.0)).toFloat()

        for (t in 0 until nFrames) {
            val base = t * hop
            for (n in 0 until nFft) frameWin[n] = padded[base + n] * window[n]
            for (k in 0 until nFreq) {
                var re = 0f; var im = 0f
                val off = k * nFft
                for (n in 0 until nFft) {
                    val s = frameWin[n]
                    re += s * cosT[off + n]
                    im -= s * sinT[off + n]
                }
                power[k] = re * re + im * im
            }
            // mel projection + log10, remember running max for normalisation
            for (m in 0 until nMels) {
                var acc = 0f
                val mo = m * nFreq
                for (k in 0 until nFreq) acc += melFilters[mo + k] * power[k]
                val logv = ln(max(acc, 1e-10f)) * invLn10
                mel[m * nFrames + t] = logv
                if (logv > globalMax) globalMax = logv
            }
        }

        // log_spec = max(log_spec, max-8); (log_spec + 4) / 4
        val floor = globalMax - 8f
        for (i in mel.indices) {
            var v = mel[i]
            if (v < floor) v = floor
            mel[i] = (v + 4f) / 4f
        }
        return mel
    }

    companion object {
        /** Loads the [80×201] mel filterbank shipped alongside the models. */
        fun load(context: Context, cfg: WhisperConfig, assetDir: String = "whisper"): WhisperMel {
            val bytes = context.assets.open("$assetDir/mel_filters.f32").use { it.readBytes() }
            val fb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val filters = FloatArray(fb.remaining())
            fb.get(filters)
            return WhisperMel(cfg.n_fft, cfg.hop, cfg.n_mels, cfg.n_frames, filters)
        }
    }
}
