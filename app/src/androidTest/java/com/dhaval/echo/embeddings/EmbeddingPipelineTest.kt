package com.dhaval.echo.embeddings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.embeddings.OnDeviceEmbeddingEngine
import com.dhaval.echo.data.embeddings.OnnxTextTokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * On-device validation of the local embedding pipeline (AI-LOCAL-02A/03).
 *
 * Runs against the real assets on real hardware — the only place the ONNX
 * Runtime Android build, the arm64 quantized model, and the SentencePiece
 * custom op are all exercised together. The Python-side checks proved the
 * artifacts are correct; this proves the Android wiring is.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingPipelineTest {

    companion object {
        private const val DIMS = 384

        private lateinit var tokenizer: OnnxTextTokenizer
        private lateinit var engine: OnDeviceEmbeddingEngine

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            tokenizer = OnnxTextTokenizer(context)
            engine = OnDeviceEmbeddingEngine(context, tokenizer)
        }

        // Reference ids captured from HF's tokenizer (build_tokenizer.py).
        private const val EN = "I spent the morning debugging the memory pipeline."
        private const val GU = "મેં આજે સવારે મારી યાદો વિશે લખ્યું."
        private const val HI = "मैंने आज सुबह अपनी यादों के बारे में लिखा।"
        private const val ES = "Pasé la mañana escribiendo sobre mis recuerdos."
        private const val MIXED = "Today મેં લખ્યું about my memories और यादें too."
    }

    private fun embed(text: String, isQuery: Boolean = false) = runBlocking {
        engine.generateEmbedding(text, isQuery)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot   // vectors are already L2-normalised by the engine
    }

    // ── tokenizer ────────────────────────────────────────────────────

    @Test
    fun tokenizer_loads_and_wraps_with_special_tokens() {
        val e = tokenizer.encode("passage: $EN", maxTokens = 512)
        assertTrue("expected tokens", e.ids.isNotEmpty())
        // XLM-R: <s> … </s>
        assertEquals("first token must be <s>", 0L, e.ids.first())
        assertEquals("last token must be </s>", 2L, e.ids.last())
        assertEquals("mask must align with ids", e.ids.size, e.attentionMask.size)
        assertTrue("no id may be negative", e.ids.all { it >= 0 })
        assertTrue("ids must be inside the 250037 vocab", e.ids.all { it < 250_037 })
    }

    @Test
    fun tokenizer_truncates_to_model_position_limit() {
        val long = List(2000) { "memory" }.joinToString(" ")
        val e = tokenizer.encode(long, maxTokens = 512)
        assertTrue("must not exceed max_position_embeddings", e.ids.size <= 512)
        assertEquals("still terminated by </s>", 2L, e.ids.last())
    }

    // ── embeddings, per language ─────────────────────────────────────

    @Test
    fun embeddings_work_for_every_required_language() {
        mapOf(
            "English" to EN, "Gujarati" to GU, "Hindi" to HI,
            "Spanish" to ES, "Mixed" to MIXED
        ).forEach { (name, text) ->
            val r = embed(text)
            assertTrue("$name failed: ${r.errorMessage}", r.success)
            assertEquals("$name dimensions", DIMS, r.dimensions)
            assertEquals("$name vector size", DIMS, r.vector.size)
            assertTrue("$name has non-finite values", r.vector.all { it.isFinite() })
            // A normalised vector has unit length; anything else means pooling broke.
            val norm = sqrt(r.vector.fold(0f) { acc, v -> acc + v * v })
            assertTrue("$name not L2-normalised (norm=$norm)", kotlin.math.abs(norm - 1f) < 1e-3)
        }
    }

    @Test
    fun blank_text_is_rejected_rather_than_embedded() {
        val r = embed("   ")
        assertTrue("blank text must not produce a vector", !r.success)
    }

    // ── semantics: the point of the whole exercise ───────────────────

    @Test
    fun related_text_scores_higher_than_unrelated() {
        val query = embed("customer discussion", isQuery = true)
        val related = embed("Meeting with Raj regarding Oceanis export")
        val unrelated = embed("The price of aluminium futures fell sharply in Q3.")

        assertTrue(query.success && related.success && unrelated.success)
        val near = cosine(query.vector, related.vector)
        val far = cosine(query.vector, unrelated.vector)
        assertTrue(
            "semantic ordering broken: related=$near unrelated=$far",
            near > far
        )
    }

    @Test
    fun same_meaning_across_languages_clusters() {
        val en = embed(EN)
        listOf(GU, HI, ES).forEach { other ->
            val v = embed(other)
            val sim = cosine(en.vector, v.vector)
            // Cross-lingual alignment is the reason this model was chosen;
            // unrelated text sits far below this.
            assertTrue("cross-lingual similarity too low: $sim", sim > 0.5f)
        }
    }

    // ── model reuse + performance ────────────────────────────────────

    @Test
    fun consecutive_requests_reuse_the_loaded_model() {
        // Warm up so the first (cold) load isn't measured.
        embed("warmup")

        val timings = (1..5).map { i ->
            var r: com.dhaval.echo.domain.embeddings.EmbeddingResult? = null
            val wall = measureTimeMillis { r = embed("consecutive request $i") }
            assertTrue("request $i failed", r!!.success)
            wall
        }

        // A reloaded 118 MB model would cost hundreds of ms; a reused session
        // is far cheaper. Slowest warm call must stay near the median.
        val median = timings.sorted()[timings.size / 2]
        val slowest = timings.max()
        assertTrue(
            "a warm request re-loaded the model (timings=$timings)",
            slowest < median * 5 + 200
        )
    }

    @Test
    fun identical_input_is_deterministic() {
        val a = embed("determinism check")
        val b = embed("determinism check")
        assertTrue(a.success && b.success)
        assertTrue("same input must give the same vector", cosine(a.vector, b.vector) > 0.9999f)
    }
}
