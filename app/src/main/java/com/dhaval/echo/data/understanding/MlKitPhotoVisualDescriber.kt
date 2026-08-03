package com.dhaval.echo.data.understanding

import android.content.Context
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.dhaval.echo.domain.understanding.PhotoVisualDescriber
import com.dhaval.echo.domain.understanding.VisualUnderstanding
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free, on-device photo understanding: ML Kit Image Labeling (scene/object labels,
 * ranked by confidence, generic tags filtered out), Face Detection (people +
 * smiling), and the photo's EXIF GPS reverse-geocoded to a place. No network, no
 * cost — the private default. Claude vision can supersede this later as an opt-in.
 *
 * Honesty (no silent failure): a failed image is logged and skipped; whatever the
 * others yield is returned, never a fabricated result.
 */
@Singleton
class MlKitPhotoVisualDescriber @Inject constructor(
    @ApplicationContext private val context: Context
) : PhotoVisualDescriber {

    // A custom 1,000-class ImageNet classifier (EfficientNet-Lite0) — far more
    // specific than ML Kit's ~400-label default (which hallucinated "Dog/Boat" on
    // a dock photo). Bundled TFLite with embedded labels.
    private val labeler by lazy {
        val model = LocalModel.Builder().setAssetFilePath(MODEL_ASSET).build()
        ImageLabeling.getClient(
            CustomImageLabelerOptions.Builder(model)
                .setConfidenceThreshold(CONFIDENCE)
                .setMaxResultCount(10)
                .build()
        )
    }

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                // Accurate + a small minimum so distant/small faces still count.
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.04f)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // smiling
                .build()
        )
    }

    override suspend fun describe(imagePaths: List<String>): VisualUnderstanding {
        if (imagePaths.isEmpty()) return VisualUnderstanding()

        val labelConfidence = HashMap<String, Float>() // best confidence seen per label
        val places = LinkedHashSet<String>()
        var faceCount = 0
        var smilingFaces = 0

        for (path in imagePaths) {
            val file = File(path)
            if (!file.exists()) {
                Log.w(TAG, "Image missing, skipping: $path")
                continue
            }
            // InputImage.fromFilePath reads EXIF orientation, so sideways photos label correctly.
            val image = runCatching { InputImage.fromFilePath(context, Uri.fromFile(file)) }
                .onFailure { Log.w(TAG, "Couldn't open $path", it) }
                .getOrNull() ?: continue

            runCatching { labeler.process(image).await() }
                .onFailure { Log.w(TAG, "Labeling failed for $path", it) }
                .getOrNull()
                ?.forEach { label ->
                    val name = cleanLabel(label.text)
                    if (name.isNotBlank() && name.lowercase(Locale.ROOT) !in LABEL_STOPLIST) {
                        labelConfidence[name] = maxOf(labelConfidence[name] ?: 0f, label.confidence)
                    }
                }

            runCatching { faceDetector.process(image).await() }
                .onFailure { Log.w(TAG, "Face detection failed for $path", it) }
                .getOrNull()
                ?.let { faces ->
                    faceCount += faces.size
                    smilingFaces += faces.count { (it.smilingProbability ?: 0f) > SMILE }
                }

            runCatching { placeOf(file) }
                .onFailure { Log.w(TAG, "Place lookup failed for $path", it) }
                .getOrNull()
                ?.let { places += it }
        }

        // Adaptive floor: keep only labels near the top prediction. On a clear
        // photo (top ~0.9) this keeps just the confident one; on a cluttered scene
        // (top ~0.09) it keeps the handful of real top guesses without the noise.
        val topConf = labelConfidence.values.maxOrNull() ?: 0f
        val floor = maxOf(ABS_FLOOR, REL_FLOOR * topConf)
        val labels = labelConfidence.entries
            .filter { it.value >= floor }
            .sortedByDescending { it.value }
            .take(MAX_LABELS)
            .map { it.key }

        return VisualUnderstanding(
            labels = labels,
            places = places.take(MAX_PLACES),
            faceCount = faceCount,
            smiling = faceCount > 0 && smilingFaces * 2 >= faceCount // majority smiling
        )
    }

    /** Reverse-geocode the photo's EXIF GPS to a human place (locality/region). */
    private fun placeOf(file: File): String? {
        val exif = ExifInterface(file.absolutePath)
        val latLong = FloatArray(2)
        @Suppress("DEPRECATION")
        if (!exif.getLatLong(latLong)) return null
        val (lat, lon) = latLong[0].toDouble() to latLong[1].toDouble()

        @Suppress("DEPRECATION")
        val address = runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
        }.getOrNull()?.firstOrNull() ?: return null

        return address.locality
            ?: address.subAdminArea
            ?: address.adminArea
            ?: address.countryName
    }

    /** ImageNet labels are lowercase with comma-synonyms ("container ship, containership").
     *  Keep the first synonym and Title-Case it for display. */
    private fun cleanLabel(raw: String): String =
        raw.substringBefore(",").trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private companion object {
        const val TAG = "MlKitPhotoVisual"
        const val MODEL_ASSET = "mlkit/efficientnet_lite0.tflite"
        const val CONFIDENCE = 0.02f   // labeler floor; the adaptive floor below does the real filtering
        const val ABS_FLOOR = 0.05f    // never keep a label below this
        const val REL_FLOOR = 0.5f     // keep labels within 50% of the top prediction
        const val SMILE = 0.5f
        const val MAX_LABELS = 6
        const val MAX_PLACES = 2

        // Uninformative ImageNet classes for personal photos.
        val LABEL_STOPLIST = setOf(
            "web site", "menu", "envelope", "rubber eraser", "band aid",
            "matchstick", "nematode", "screen", "monitor"
        )
    }
}
