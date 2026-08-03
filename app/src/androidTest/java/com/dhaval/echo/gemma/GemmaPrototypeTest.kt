package com.dhaval.echo.gemma

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Evaluation harness for Gemma 4 E2B on this device — not a pass/fail test.
 *
 * Three questions decide whether the opt-in "better understanding" upgrade is
 * worth ~2.6 GB of someone's storage:
 *   1. Does it load at all on an 8 GB phone, and how long does that take?
 *   2. How fast is a realistic prompt?
 *   3. Does image input work *from Kotlin*? The LiteRT-LM Kotlin guide only
 *      names Gemma 3n as multimodal, and issue #1874 reported that the Kotlin
 *      API's missing PromptTemplate support blocks Gemma 4 vision. That claim is
 *      the single biggest risk to this feature, so it is tested explicitly and
 *      the failure is reported rather than swallowed.
 *
 * The model is side-loaded rather than bundled, so this stays out of the app:
 *   adb push gemma-4-E2B-it.litertlm \
 *     /sdcard/Android/data/com.dhaval.echo/files/gemma/gemma-4-E2B-it.litertlm
 *
 * Skips (rather than fails) when the model isn't present.
 */
@RunWith(AndroidJUnit4::class)
class GemmaPrototypeTest {

    private val tag = "GemmaProto"

    private fun modelFile(): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "gemma-4-E2B-it.litertlm"
        val candidates = listOfNotNull(
            ctx.getExternalFilesDir(null)?.let { File(it, "gemma/$name") },
            File("/storage/emulated/0/Android/data/com.dhaval.echo/files/gemma/$name"),
            File("/sdcard/Android/data/com.dhaval.echo/files/gemma/$name"),
            File(ctx.filesDir, "gemma/$name"),
            File("/data/local/tmp/gemma/$name")
        )
        // Say which paths were tried and why each was rejected — a silent "not
        // found" over five candidates is untraceable.
        candidates.forEach {
            Log.i(tag, "candidate: ${it.absolutePath} exists=${it.exists()} " +
                "canRead=${runCatching { it.canRead() }.getOrDefault(false)} len=${runCatching { it.length() }.getOrDefault(-1L)}")
        }
        return candidates.firstOrNull {
            runCatching { it.exists() && it.canRead() && it.length() > 0 }.getOrDefault(false)
        }
    }

    /** Loads the engine once per test and always releases it. */
    private fun <T> withEngine(visionBackend: Backend?, block: (Engine) -> T): T {
        val model = modelFile()!!
        val config =
            if (visionBackend == null) EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU())
            else EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.CPU(),
                visionBackend = visionBackend
            )

        val engine = Engine(config)
        val loadMs = measureTimeMillis { engine.initialize() }
        Log.i(tag, "engine.initialize() took ${loadMs}ms (vision=${visionBackend != null})")
        return try {
            block(engine)
        } finally {
            runCatching { engine.close() }
        }
    }

    @Test
    fun reports_model_presence_and_size() {
        val model = modelFile()
        if (model == null) {
            Log.w(tag, "MODEL NOT FOUND — push it with:")
            Log.w(tag, "  adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.dhaval.echo/files/gemma/")
        } else {
            Log.i(tag, "model=${model.absolutePath} size=${"%.2f".format(model.length() / 1073741824.0)} GB")
        }
        val rt = Runtime.getRuntime()
        Log.i(tag, "jvm maxMemory=${rt.maxMemory() / 1048576} MB")
        assumeTrue("model not pushed to device", model != null)
    }

    @Test
    fun text_generation_speed_and_quality(): Unit = runBlocking {
        assumeTrue("model not pushed to device", modelFile() != null)

        withEngine(visionBackend = null) { engine ->
            engine.createConversation().use { conversation ->
                // The job Echo actually needs: turn a raw note into a summary and
                // pull out the commitment — today done by regex.
                val prompt = """
                    Summarise this diary note in one sentence, then list any tasks with their due dates.
                    Note: "Met Prabir at the Titan project review today. I must send him the budget file tomorrow."
                """.trimIndent()

                val started = System.currentTimeMillis()
                val reply = conversation.sendMessage(prompt)
                Log.i(tag, "TEXT ${System.currentTimeMillis() - started}ms")
                Log.i(tag, "TEXT REPLY: $reply")
            }
        }
    }

    @Test
    fun vision_is_the_decisive_question(): Unit = runBlocking {
        assumeTrue("model not pushed to device", modelFile() != null)

        // The same dock photo ML Kit describes as "Forklift, Carton" — a direct
        // quality comparison against what ships today.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val photo = File(ctx.cacheDir, "dock_workers.jpg")
        assets.open("dock_workers.jpg").use { input ->
            photo.outputStream().use { input.copyTo(it) }
        }

        // The vision encoder has to be compiled by whichever backend we ask for,
        // and that can fail per-device — so try each and report, rather than
        // concluding "Gemma 4 vision doesn't work" from one backend's failure.
        // GPU first, deliberately: the CPU vision path exhausts memory on an 8 GB
        // device and gets the whole process killed, which would take the GPU
        // result down with it before it was ever logged.
        val backends = listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU())

        for ((name, backend) in backends) {
            val outcome = runCatching {
                withEngine(visionBackend = backend) { engine ->
                    engine.createConversation().use { conversation ->
                        val started = System.currentTimeMillis()
                        val reply = conversation.sendMessage(
                            Contents.of(
                                Content.ImageFile(photo.absolutePath),
                                Content.Text("Describe this photo in one or two sentences.")
                            )
                        )
                        (System.currentTimeMillis() - started) to reply
                    }
                }
            }

            outcome
                .onSuccess { (ms, reply) ->
                    Log.i(tag, "VISION[$name] OK ${ms}ms")
                    Log.i(tag, "VISION[$name] REPLY: $reply")
                    Log.i(tag, "ML Kit baseline for this photo: \"Forklift, Carton\"")
                }
                .onFailure {
                    // Not a test failure — a blocked backend is the finding we came for.
                    Log.e(tag, "VISION[$name] BLOCKED: ${it::class.simpleName}: ${it.message}")
                }
        }
    }
}
