package com.dhaval.echo.data.transcription.whisper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.dhaval.echo.domain.transcription.SpeechToTextEngine
import com.dhaval.echo.domain.transcription.TranscriptionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device Whisper-base transcription (MU-2). Replaces the mic-only
 * SpeechRecognizer engine, which could not read audio files at all.
 *
 * Pipeline (proven end-to-end in the Python spike, then ported op-for-op):
 *   file → PCM 16 kHz mono → 30 s windows → log-mel [80×3000]
 *        → encoder (once/window) → greedy KV-cache decode → byte-level BPE.
 *
 * Two decoder graphs, matching the ONNX export:
 *   - whisper_decoder_int8      : first step, computes cross-attention KV.
 *   - whisper_decoder_past_int8 : subsequent steps, reuses cached KV.
 * The cross (encoder) KV is computed once per window and held constant; the
 * decoder self-KV grows one token per step.
 */
@Singleton
class WhisperTranscriptionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeechToTextEngine {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val initMutex = Mutex()

    @Volatile private var encoder: OrtSession? = null
    @Volatile private var decoder: OrtSession? = null
    @Volatile private var decoderPast: OrtSession? = null
    private lateinit var cfg: WhisperConfig
    private lateinit var mel: WhisperMel
    private lateinit var vocab: WhisperVocab

    override suspend fun transcribe(audioPath: String, languageHint: String?): TranscriptionResult =
        withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            try {
                ensureLoaded()
                val pcm = withContext(Dispatchers.IO) { WhisperAudio.decodeToMono16k(audioPath) }
                if (pcm.isEmpty()) return@withContext fail("Could not decode audio", started)
                transcribeSamples(pcm, languageHint, started)
            } catch (e: Exception) {
                Log.e(TAG, "Whisper transcription failed", e)
                fail(e.message ?: e::class.simpleName ?: "Unknown error", started)
            }
        }

    /**
     * Transcribes mono 16 kHz float PCM directly. The file path in [transcribe]
     * resolves to this; exposed so tests can exercise the full ONNX chain from
     * a known waveform without depending on device audio-codec support.
     */
    suspend fun transcribeSamples(
        pcm: FloatArray,
        languageHint: String? = null,
        started: Long = System.currentTimeMillis()
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        ensureLoaded()
        if (pcm.isEmpty()) return@withContext fail("Empty audio", started)

        val windowSamples = cfg.n_samples                 // 30 s
        val pieces = StringBuilder()
        var detectedLang: Int? = languageHint?.let { langTokenId(it) }

        var offset = 0
        while (offset < pcm.size) {
            val window = pcm.copyOfRange(offset, minOf(offset + windowSamples, pcm.size))
            val features = mel.logMel(window)
            val encHidden = runEncoder(features)
            try {
                if (detectedLang == null) detectedLang = detectLanguage(encHidden)
                val text = decodeWindow(encHidden, detectedLang!!)
                if (text.isNotBlank()) {
                    if (pieces.isNotEmpty()) pieces.append(' ')
                    pieces.append(text.trim())
                }
            } finally {
                encHidden.close()
            }
            offset += windowSamples
        }

        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "Transcribed ${pcm.size / 16000}s audio in ${elapsed}ms")
        TranscriptionResult(
            transcript = pieces.toString().trim(),
            detectedLanguage = languageLabel(detectedLang),
            duration = (pcm.size / 16000L) * 1000,
            processingTime = elapsed,
            providerName = "Whisper base (on-device)",
            success = true
        )
    }

    // ---- inference steps -------------------------------------------------

    /** Encoder: input_features [1,80,3000] → last_hidden_state [1,1500,512]. */
    private fun runEncoder(features: FloatArray): OnnxTensor {
        val input = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features), longArrayOf(1, cfg.n_mels.toLong(), cfg.n_frames.toLong())
        )
        input.use {
            encoder!!.run(mapOf("input_features" to it)).use { res ->
                val hidden = res.get("last_hidden_state").get() as OnnxTensor
                // copy out so we can close the Result while keeping the value
                val arr = hidden.floatArray()
                val shape = hidden.info.shape
                return OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), shape)
            }
        }
    }

    /** Probe [SOT], restrict logits to the language block, argmax. */
    private fun detectLanguage(encHidden: OnnxTensor): Int {
        val (logits, present) = runDecoderFirst(intArrayOf(cfg.sot), encHidden)
        present.values.forEach { it.close() }
        var best = cfg.lang_lo; var bestVal = Float.NEGATIVE_INFINITY
        for (id in cfg.lang_lo..cfg.lang_hi) {
            if (logits[id] > bestVal) { bestVal = logits[id]; best = id }
        }
        return best
    }

    /** Full greedy decode for one window, given a resolved language. */
    private fun decodeWindow(encHidden: OnnxTensor, langId: Int): String {
        val prompt = intArrayOf(cfg.sot, langId, cfg.transcribe, cfg.notimestamps)
        val (logits, present) = runDecoderFirst(prompt, encHidden)

        // split present KV into constant encoder KV and growing decoder KV
        val encKv = HashMap<String, OnnxTensor>()
        val decKv = HashMap<String, OnnxTensor>()
        for ((name, t) in present) {
            if (name.contains(".encoder.")) encKv[name.replace("present", "past_key_values")] = t
            else decKv[name.replace("present", "past_key_values")] = t
        }

        val out = ArrayList<Int>()
        try {
            suppress(logits, cfg.suppress); suppress(logits, cfg.begin_suppress)
            var token = argmax(logits)
            var steps = 0
            while (token != cfg.eot && steps < MAX_TOKENS) {
                out.add(token)
                val step = runDecoderPast(token, encKv, decKv)
                // advance decoder self-KV; encoder KV stays constant
                for ((name, t) in step.present) {
                    val pastName = name.replace("present", "past_key_values")
                    decKv.remove(pastName)?.close()
                    decKv[pastName] = t
                }
                suppress(step.logits, cfg.suppress)
                token = argmax(step.logits)
                steps++
            }
        } finally {
            encKv.values.forEach { it.close() }
            decKv.values.forEach { it.close() }
        }
        return vocab.decode(out)
    }

    private class DecStep(val logits: FloatArray, val present: Map<String, OnnxTensor>)

    /** decoder_model: input_ids + encoder_hidden_states → logits + all present KV. */
    private fun runDecoderFirst(ids: IntArray, encHidden: OnnxTensor): Pair<FloatArray, Map<String, OnnxTensor>> {
        val idTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong())
        )
        idTensor.use {
            decoder!!.run(mapOf("input_ids" to it, "encoder_hidden_states" to encHidden)).use { res ->
                val logits = lastRowLogits(res.get("logits").get() as OnnxTensor)
                val present = detachPresent(res)
                return logits to present
            }
        }
    }

    /** decoder_with_past: input_ids[1] + past KV → logits + present decoder KV. */
    private fun runDecoderPast(token: Int, encKv: Map<String, OnnxTensor>, decKv: Map<String, OnnxTensor>): DecStep {
        val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(token.toLong())), longArrayOf(1, 1))
        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_ids"] = idTensor
        inputs.putAll(encKv)
        inputs.putAll(decKv)
        idTensor.use {
            decoderPast!!.run(inputs).use { res ->
                val logits = lastRowLogits(res.get("logits").get() as OnnxTensor)
                val present = detachPresent(res)   // only present.*.decoder.*
                return DecStep(logits, present)
            }
        }
    }

    /** Copies every present.* KV tensor out of the Result so it survives close. */
    private fun detachPresent(res: OrtSession.Result): Map<String, OnnxTensor> {
        val map = HashMap<String, OnnxTensor>()
        for (item in res) {
            val name = item.key
            if (!name.startsWith("present.")) continue
            val t = item.value as OnnxTensor
            map[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(t.floatArray()), t.info.shape)
        }
        return map
    }

    private fun lastRowLogits(logits: OnnxTensor): FloatArray {
        val shape = logits.info.shape          // [1, L, vocab]
        val seq = shape[1].toInt(); val vocabN = shape[2].toInt()
        val all = logits.floatArray()
        val start = (seq - 1) * vocabN
        return all.copyOfRange(start, start + vocabN)
    }

    private fun suppress(logits: FloatArray, ids: List<Int>) {
        for (id in ids) if (id in logits.indices) logits[id] = Float.NEGATIVE_INFINITY
    }

    private fun argmax(logits: FloatArray): Int {
        var bi = 0; var bv = logits[0]
        for (i in 1 until logits.size) if (logits[i] > bv) { bv = logits[i]; bi = i }
        return bi
    }

    private fun OnnxTensor.floatArray(): FloatArray {
        val fb = this.floatBuffer
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        return arr
    }

    private fun langTokenId(hint: String): Int? {
        // hint like "en"/"hi"/"gu"; language tokens are contiguous from lang_lo
        // in the tokenizer's fixed order — resolve via the vocab is overkill here,
        // so accept only when it maps into range, else fall back to detection.
        return null  // v1: always auto-detect unless a numeric id is supplied elsewhere
    }

    private fun languageLabel(langId: Int?): String? = langId?.let { "id:$it" }

    // ---- session lifecycle ----------------------------------------------

    private suspend fun ensureLoaded() {
        if (encoder != null) return
        initMutex.withLock {
            if (encoder != null) return
            withContext(Dispatchers.IO) {
                cfg = WhisperConfig.load(context)
                mel = WhisperMel.load(context, cfg)
                vocab = WhisperVocab.load(context, cfg)
                encoder = createSession("whisper_encoder_int8.onnx")
                decoder = createSession("whisper_decoder_int8.onnx")
                decoderPast = createSession("whisper_decoder_past_int8.onnx")
                Log.i(TAG, "Whisper sessions initialised")
            }
        }
    }

    private fun createSession(asset: String): OrtSession {
        val file = unpack(asset)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(NUM_THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(file.absolutePath, opts)
    }

    /** Assets are compressed in the APK and can't be mmapped; unpack once. */
    private fun unpack(asset: String): File {
        val target = File(context.filesDir, "whisper/$asset")
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$asset.tmp")
        context.assets.open("whisper/$asset").use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        check(tmp.renameTo(target)) { "Could not finalise unpacked $asset" }
        Log.i(TAG, "Unpacked $asset (${target.length() / 1_000_000} MB)")
        return target
    }

    private fun fail(message: String, started: Long) = TranscriptionResult(
        transcript = "",
        processingTime = System.currentTimeMillis() - started,
        providerName = "Whisper base (on-device)",
        success = false,
        errorMessage = message
    )

    private companion object {
        const val TAG = "WhisperEngine"
        const val NUM_THREADS = 4
        const val MAX_TOKENS = 224   // per 30 s window
    }
}
