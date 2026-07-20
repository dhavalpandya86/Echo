package com.dhaval.echo.data.understanding

import android.content.Context
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.dhaval.echo.domain.understanding.PhotoVisualDescriber
import com.dhaval.echo.domain.understanding.VisualUnderstanding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free, on-device photo understanding: ML Kit Image Labeling for scene/object
 * labels, plus the photo's own EXIF GPS reverse-geocoded to a place name. No
 * network, no cost, no cloud — the private default. Claude vision can supersede
 * this later as an opt-in.
 *
 * Honesty (no silent failure): a failed image is logged and skipped; whatever the
 * others yield is returned, never a fabricated result.
 */
@Singleton
class MlKitPhotoVisualDescriber @Inject constructor(
    @ApplicationContext private val context: Context
) : PhotoVisualDescriber {

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(CONFIDENCE).build()
        )
    }

    override suspend fun describe(imagePaths: List<String>): VisualUnderstanding {
        if (imagePaths.isEmpty()) return VisualUnderstanding()

        val labels = LinkedHashSet<String>()
        val places = LinkedHashSet<String>()

        for (path in imagePaths) {
            val file = File(path)
            if (!file.exists()) {
                Log.w(TAG, "Image missing, skipping: $path")
                continue
            }
            runCatching { labelsOf(file) }
                .onFailure { Log.w(TAG, "Labeling failed for $path", it) }
                .getOrDefault(emptyList())
                .forEach { labels += it }

            runCatching { placeOf(file) }
                .onFailure { Log.w(TAG, "Place lookup failed for $path", it) }
                .getOrNull()
                ?.let { places += it }
        }

        return VisualUnderstanding(
            labels = labels.take(MAX_LABELS),
            places = places.take(MAX_PLACES)
        )
    }

    private suspend fun labelsOf(file: File): List<String> {
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        return labeler.process(image).await().map { it.text }
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

        // Prefer the most human name: city → district → state → country.
        return address.locality
            ?: address.subAdminArea
            ?: address.adminArea
            ?: address.countryName
    }

    private companion object {
        const val TAG = "MlKitPhotoVisual"
        const val CONFIDENCE = 0.7f
        const val MAX_LABELS = 6
        const val MAX_PLACES = 2
    }
}
