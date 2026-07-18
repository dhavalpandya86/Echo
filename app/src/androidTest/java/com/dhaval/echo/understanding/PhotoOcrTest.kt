package com.dhaval.echo.understanding

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.understanding.MlKitPhotoTextExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MU-3: on-device photo OCR. A "whiteboard" image (rendered known text) must
 * yield text the analyzers can then turn into entities. Also checks the honest
 * empty-input contract.
 */
@RunWith(AndroidJUnit4::class)
class PhotoOcrTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun renderTextPng(lines: List<String>): String {
        val w = 1200; val h = 100 + lines.size * 90
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 56f; isSubpixelText = true
        }
        var y = 90f
        for (line in lines) { canvas.drawText(line, 40f, y, paint); y += 90f }
        val file = File(ctx.cacheDir, "ocr_test_${System.nanoTime()}.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    @Test
    fun recognizes_text_from_a_whiteboard_photo() = runBlocking {
        val path = renderTextPng(
            listOf("Meeting with Raj about the", "Oceanis logo tomorrow")
        )
        val extractor = MlKitPhotoTextExtractor(ctx)
        val text = extractor.extractText(listOf(path))
        File(path).delete()

        android.util.Log.i("PhotoOcrTest", "OCR text = '$text'")
        val lower = text.lowercase()
        assertTrue("expected 'raj' in OCR: $text", lower.contains("raj"))
        assertTrue("expected 'oceanis' in OCR: $text", lower.contains("oceanis"))
    }

    @Test
    fun empty_input_returns_empty_not_an_error() = runBlocking {
        val extractor = MlKitPhotoTextExtractor(ctx)
        assertEquals("", extractor.extractText(emptyList()))
        assertEquals("", extractor.extractText(listOf("/does/not/exist.jpg")))
    }
}
