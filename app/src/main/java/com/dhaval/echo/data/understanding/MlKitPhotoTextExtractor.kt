package com.dhaval.echo.data.understanding

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dhaval.echo.domain.understanding.PhotoTextExtractor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device OCR via ML Kit Text Recognition v2 (bundled models, no network).
 *
 * ML Kit needs a script chosen per call and does not auto-detect, so each image
 * is run through both the Latin and Devanagari (Hindi) recognizers and the
 * longer result is kept — a pragmatic "which script is this?" for the common
 * case of one dominant script per photo.
 *
 * Honesty (no-silent-failure): a failed image is logged and skipped; the method
 * returns whatever text the other images yielded, never a fabricated blank pass.
 *
 * Limitation: ML Kit v2 covers Latin, Devanagari, Chinese, Japanese, Korean —
 * not Gujarati. Gujarati handwriting/print is not recognized yet; that needs a
 * different engine and is out of scope for MU-3.
 */
@Singleton
class MlKitPhotoTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) : PhotoTextExtractor {

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    override suspend fun extractText(imagePaths: List<String>): String {
        if (imagePaths.isEmpty()) return ""
        val pieces = mutableListOf<String>()
        for (path in imagePaths) {
            val file = File(path)
            if (!file.exists()) {
                Log.w(TAG, "Image missing, skipping OCR: $path")
                continue
            }
            val text = runCatching { recognize(file) }
                .onFailure { Log.w(TAG, "OCR failed for $path", it) }
                .getOrDefault("")
            if (text.isNotBlank()) pieces += text.trim()
        }
        return pieces.joinToString("\n\n")
    }

    private suspend fun recognize(file: File): String {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        val latinText = latin.process(image).await().text
        val devText = devanagari.process(image).await().text
        // Keep whichever script recognized more — the dominant one for this image.
        return if (devText.length > latinText.length) devText else latinText
    }

    private companion object {
        const val TAG = "MlKitPhotoOCR"
    }
}
