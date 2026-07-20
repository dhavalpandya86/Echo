package com.dhaval.echo.understanding

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.understanding.MlKitPhotoVisualDescriber
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the on-device photo describer over a real dock-workers photo and prints
 * what it found — the concrete way to judge label/face precision on device.
 */
@RunWith(AndroidJUnit4::class)
class PhotoVisualDescriberTest {

    @Test
    fun describes_a_real_photo() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets

        // Copy the bundled test image out to a real file the describer can open.
        val file = File(ctx.cacheDir, "dock_workers.jpg")
        assets.open("dock_workers.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }

        val result = MlKitPhotoVisualDescriber(ctx).describe(listOf(file.absolutePath))

        Log.i("PhotoVisualTest", "labels=${result.labels}")
        Log.i("PhotoVisualTest", "faceCount=${result.faceCount} smiling=${result.smiling}")
        Log.i("PhotoVisualTest", "places=${result.places}")
        Log.i("PhotoVisualTest", "summary=\"${result.asSummary()}\"")

        // It's a photo of people at a dock — it should see *something*.
        assertTrue("expected labels or faces", result.labels.isNotEmpty() || result.faceCount > 0)
    }
}
