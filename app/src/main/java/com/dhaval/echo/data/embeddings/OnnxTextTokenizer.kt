package com.dhaval.echo.data.embeddings

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import android.content.Context
import android.util.Log
import com.dhaval.echo.domain.embeddings.TextTokenizer
import com.dhaval.echo.domain.embeddings.TokenizerUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * XLM-RoBERTa tokenizer for multilingual-e5-small, run as an ONNX graph.
 *
 * `tokenizer.onnx` wraps ONNX Runtime Extensions' SentencepieceTokenizer op
 * with the model's sentencepiece vocabulary embedded, which avoids
 * reimplementing SentencePiece (and its `precompiled_charsmap` normalizer) by
 * hand in Kotlin.
 *
 * The op emits *raw* sentencepiece ids. XLM-R does not use those directly — it
 * remaps them fairseq-style and wraps the sequence in `<s> … </s>`. That remap
 * is [toXlmRobertaIds] below, and it is the whole correctness story: a wrong
 * offset still yields plausible ids and a well-formed 384-d vector, so the
 * failure would be silent. The mapping here is verified byte-for-byte against
 * HF's reference tokenizer (see AI-LOCAL-03 build_tokenizer.py).
 */
@Singleton
class OnnxTextTokenizer @Inject constructor(
    @ApplicationContext private val context: Context
) : TextTokenizer {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession by lazy {
        try {
            val file = unpackAsset()
            val opts = OrtSession.SessionOptions().apply {
                // Without this the graph fails to load: SentencepieceTokenizer
                // lives in the extensions library, not core ORT.
                registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            }
            ortEnv.createSession(file.absolutePath, opts).also {
                Log.i(TAG, "Tokenizer session initialised")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise tokenizer", e)
            throw TokenizerUnavailableException("Could not load tokenizer.onnx: ${e.message}")
        }
    }

    private fun unpackAsset(): File {
        val target = File(context.filesDir, "embeddings/tokenizer.onnx")
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        context.assets.open(ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        check(tmp.renameTo(target)) { "Could not finalise unpacked tokenizer" }
        return target
    }

    override fun encode(text: String, maxTokens: Int): TextTokenizer.Encoding {
        val spmIds = runSentencePiece(text)
        val ids = toXlmRobertaIds(spmIds, maxTokens)
        return TextTokenizer.Encoding(
            ids = ids,
            // Nothing is padded: batch size is 1 and the graph takes a dynamic
            // sequence length, so every token is real.
            attentionMask = LongArray(ids.size) { 1L }
        )
    }

    private fun runSentencePiece(text: String): IntArray {
        val inputs = OnnxTensor.createTensor(ortEnv, arrayOf(text))
        // The op's signature requires all six inputs even when unused.
        val nbest = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(longArrayOf(0)), longArrayOf(1))
        val alpha = OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1))
        // BOS/EOS are added during the remap, not here, so the ids line up
        // with XLM-R's own special-token values rather than sentencepiece's.
        // Primitive boolean[]: ORT rejects boxed Boolean[].
        val addBos = OnnxTensor.createTensor(ortEnv, booleanArrayOf(false))
        val addEos = OnnxTensor.createTensor(ortEnv, booleanArrayOf(false))
        val reverse = OnnxTensor.createTensor(ortEnv, booleanArrayOf(false))

        return inputs.use { i ->
            nbest.use { n ->
                alpha.use { a ->
                    addBos.use { b ->
                        addEos.use { e ->
                            reverse.use { r ->
                                session.run(
                                    mapOf(
                                        "inputs" to i,
                                        "nbest_size" to n,
                                        "alpha" to a,
                                        "add_bos" to b,
                                        "add_eos" to e,
                                        "reverse" to r
                                    )
                                ).use { result ->
                                    (result.get(0).value as IntArray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Raw sentencepiece ids -> XLM-RoBERTa ids.
     *
     * HF's XLMRobertaTokenizer defines: `<s>`=0, `<pad>`=1, `</s>`=2,
     * `<unk>`=3, and every other piece is `spm_id + 1`. Truncation leaves room
     * for both special tokens so the sequence never exceeds the model's
     * position limit.
     */
    private fun toXlmRobertaIds(spmIds: IntArray, maxTokens: Int): LongArray {
        val room = (maxTokens - 2).coerceAtLeast(0)
        val body = spmIds.take(room).map { spmId ->
            if (spmId > 0) (spmId + FAIRSEQ_OFFSET).toLong() else UNK_ID
        }
        return LongArray(body.size + 2).apply {
            this[0] = BOS_ID
            body.forEachIndexed { index, id -> this[index + 1] = id }
            this[body.size + 1] = EOS_ID
        }
    }

    private companion object {
        const val TAG = "OnnxTextTokenizer"
        const val ASSET = "embeddings/tokenizer.onnx"

        // XLM-RoBERTa fairseq vocabulary layout.
        const val BOS_ID = 0L      // <s>
        const val EOS_ID = 2L      // </s>
        const val UNK_ID = 3L      // <unk>
        const val FAIRSEQ_OFFSET = 1
    }
}
