package com.dhaval.echo.data.embeddings

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.dhaval.echo.domain.embeddings.EmbeddingEngine
import com.dhaval.echo.domain.embeddings.EmbeddingResult
import com.dhaval.echo.domain.embeddings.TextTokenizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OnDeviceEmbeddingEngine"
private const val MODEL_ASSET = "embeddings/multilingual-e5-small.onnx"
private const val MODEL_VERSION = "multilingual-e5-small-v1-int8"

/** intfloat/multilingual-e5-small: hidden_size 384. */
private const val EXPECTED_DIMENSIONS = 384

/**
 * Generates embeddings locally with multilingual-e5-small (int8, ONNX Runtime).
 *
 * The model is quantized to ~113 MB, which is too large to read into a
 * ByteArray — the asset is unpacked to app storage once and the session is
 * created from that path so ONNX Runtime can memory-map it instead of holding
 * the whole model on the heap.
 */
@Singleton
class OnDeviceEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenizer: TextTokenizer
) : EmbeddingEngine {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Volatile
    private var ortSession: OrtSession? = null

    /** Guards first-use init; suspend-friendly, unlike synchronized. */
    private val sessionMutex = Mutex()

    private suspend fun getSession(): OrtSession {
        ortSession?.let { return it }
        return sessionMutex.withLock {
            // Re-check: another caller may have initialised while we waited.
            ortSession ?: withContext(Dispatchers.IO) {
                val modelFile = unpackModel()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(NUM_THREADS)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                // From a path, not bytes: lets ORT mmap the 113 MB file.
                ortEnv.createSession(modelFile.absolutePath, opts).also {
                    ortSession = it
                    Log.i(TAG, "ONNX session initialised from ${modelFile.name}")
                }
            }
        }
    }

    /**
     * Copies the model out of assets on first run. Assets live compressed
     * inside the APK and cannot be mapped directly.
     */
    private fun unpackModel(): File {
        val target = File(context.filesDir, "embeddings/multilingual-e5-small.onnx")
        if (target.exists() && target.length() > 0) return target

        Log.i(TAG, "Unpacking model from assets (first run)")
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        // Rename only once fully written: a half-copied model must never be
        // mistaken for a usable one on the next launch.
        check(tmp.renameTo(target)) { "Could not finalise unpacked model" }
        Log.i(TAG, "Model unpacked (${target.length() / 1_000_000} MB)")
        return target
    }

    override suspend fun generateEmbedding(
        text: String,
        isQuery: Boolean
    ): EmbeddingResult = withContext(Dispatchers.Default) {
        if (text.isBlank()) {
            return@withContext failure("Input text is blank", 0)
        }

        val startTime = System.currentTimeMillis()
        try {
            val session = getSession()

            // e5 is trained with these prefixes; omitting them measurably
            // degrades retrieval quality.
            val input = (if (isQuery) "query: " else "passage: ") + text
            val encoded = tokenizer.encode(input, maxTokens = MAX_SEQUENCE_LENGTH)

            if (encoded.ids.isEmpty()) {
                return@withContext failure("Tokenizer produced no tokens", startTime)
            }

            val seqLen = encoded.ids.size
            val shape = longArrayOf(1, seqLen.toLong())

            // The graph declares three inputs; ORT rejects the run if any is
            // missing. token_type_ids is all-zero for XLM-R.
            val idsTensor = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(encoded.ids), shape
            )
            val maskTensor = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(encoded.attentionMask), shape
            )
            val typeTensor = OnnxTensor.createTensor(
                ortEnv, LongBuffer.wrap(LongArray(seqLen)), shape
            )

            idsTensor.use { ids ->
                maskTensor.use { mask ->
                    typeTensor.use { types ->
                        session.run(
                            mapOf(
                                "input_ids" to ids,
                                "attention_mask" to mask,
                                "token_type_ids" to types
                            )
                        ).use { result ->
                            val output = result.get(0) as OnnxTensor
                            val vector = meanPool(output, encoded.attentionMask)

                            if (vector.size != EXPECTED_DIMENSIONS) {
                                return@withContext failure(
                                    "Expected $EXPECTED_DIMENSIONS dims, got ${vector.size}",
                                    startTime
                                )
                            }

                            val duration = System.currentTimeMillis() - startTime
                            Log.d(TAG, "Embedded ${vector.size}d in ${duration}ms ($seqLen tokens)")
                            EmbeddingResult(
                                vector = vector,
                                dimensions = vector.size,
                                modelVersion = MODEL_VERSION,
                                inferenceTime = duration
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding generation failed", e)
            failure(e.message ?: e::class.simpleName ?: "Unknown error", startTime)
        }
    }

    /**
     * Masked mean pooling over the token axis, then L2 normalisation — the
     * pooling e5 was trained with. Padding tokens must be excluded or they
     * drag the vector toward the pad embedding.
     */
    private fun meanPool(output: OnnxTensor, attentionMask: LongArray): FloatArray {
        val shape = output.info.shape          // [1, seq, 384]
        val seqLen = shape[1].toInt()
        val hidden = shape[2].toInt()

        val buffer = output.floatBuffer
        val pooled = FloatArray(hidden)
        var counted = 0f

        for (s in 0 until seqLen) {
            val keep = attentionMask.getOrElse(s) { 0L } == 1L
            for (d in 0 until hidden) {
                val v = buffer.get()
                if (keep) pooled[d] += v
            }
            if (keep) counted += 1f
        }

        if (counted > 0f) {
            for (d in 0 until hidden) pooled[d] /= counted
        }
        return normalize(pooled)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var norm = 0f
        for (v in vector) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-9f) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }

    private fun failure(message: String, startTime: Long) = EmbeddingResult(
        vector = FloatArray(0),
        dimensions = 0,
        modelVersion = MODEL_VERSION,
        inferenceTime = if (startTime == 0L) 0 else System.currentTimeMillis() - startTime,
        success = false,
        errorMessage = message
    )

    private companion object {
        const val MAX_SEQUENCE_LENGTH = 512   // config.json: max_position_embeddings
        const val NUM_THREADS = 2
    }
}
