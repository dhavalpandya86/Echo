package com.dhaval.echo.understanding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.embeddings.OnDeviceEmbeddingEngine
import com.dhaval.echo.data.embeddings.OnnxTextTokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MU-4 calibration (exploratory, not an assertion suite): what cosine does e5
 * give for entity-name pairs we WANT to merge vs pairs we must NOT? Sets the
 * fuzzy-identity threshold from real on-device numbers before any merge logic
 * ships — a wrong merge corrupts the user's graph, so this is measured first.
 */
@RunWith(AndroidJUnit4::class)
class EntityIdentityCalibrationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cos(a: FloatArray, b: FloatArray): Float {
        var d = 0f; for (i in a.indices) d += a[i] * b[i]; return d  // engine L2-normalises
    }

    @Test
    fun report_name_pair_similarities() = runBlocking<Unit> {
        val engine = OnDeviceEmbeddingEngine(ctx, OnnxTextTokenizer(ctx))
        suspend fun emb(s: String) = engine.generateEmbedding(s).vector

        val pairs = listOf(
            // SHOULD merge (same entity, different surface form)
            Triple("MERGE", "Raj", "Rajesh"),
            Triple("MERGE", "Oceanis", "the Oceanis project"),
            Triple("MERGE", "Oceanis", "Oceanis logo"),
            Triple("MERGE", "mom", "my mother"),
            // MUST NOT merge (distinct entities)
            Triple("KEEP", "Raj", "Priya"),
            Triple("KEEP", "Raj", "Ravi"),          // similar Indian names — danger
            Triple("KEEP", "Oceanis", "Atlas"),
            Triple("KEEP", "Raj", "Oceanis")        // cross-type sanity
        )
        val cache = HashMap<String, FloatArray>()
        suspend fun v(s: String) = cache.getOrPut(s) { emb(s) }

        val sb = StringBuilder("\n=== e5 name-pair cosines ===\n")
        for ((tag, a, b) in pairs) {
            val c = cos(v(a), v(b))
            sb.append(String.format("%-6s  %-22s ~ %-22s  cos=%.3f\n", tag, "'$a'", "'$b'", c))
        }
        android.util.Log.i("MU4Calib", sb.toString())
    }
}
