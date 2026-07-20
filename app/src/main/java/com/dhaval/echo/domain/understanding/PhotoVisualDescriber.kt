package com.dhaval.echo.domain.understanding

/**
 * What Echo could see in a memory's photos, entirely on-device: scene/object
 * labels and any place derived from the photo's GPS. Lower-fidelity than a cloud
 * vision model, but free, private, and enough to feed the graph.
 */
data class VisualUnderstanding(
    val labels: List<String> = emptyList(),   // e.g. "Beach", "Sunset", "Food"
    val places: List<String> = emptyList()     // e.g. "Goa" (from EXIF GPS)
) {
    val isEmpty: Boolean get() = labels.isEmpty() && places.isEmpty()

    /** Companion-voice one-liner for the "Echo sees…" line, or null if nothing. */
    fun asSummary(): String? {
        if (isEmpty) return null
        val parts = mutableListOf<String>()
        if (labels.isNotEmpty()) parts += labels.joinToString(", ")
        if (places.isNotEmpty()) parts += "in ${places.joinToString(", ")}"
        return parts.joinToString(" · ")
    }

    /**
     * Natural-language snippet folded into the memory's source text so places
     * become graph entities and labels become tags/keywords through the existing
     * analyzers (no special-casing needed downstream).
     */
    fun asSourceText(): String {
        val parts = mutableListOf<String>()
        if (places.isNotEmpty()) parts += "Taken in ${places.joinToString(", ")}."
        if (labels.isNotEmpty()) parts += "Photo shows ${labels.joinToString(", ")}."
        return parts.joinToString(" ")
    }
}

/**
 * Stage-1 visual extractor for the Photo modality: describes what's in a memory's
 * images. Mirrors [PhotoTextExtractor] (OCR). Failures degrade to an empty result,
 * never a fabricated one.
 */
interface PhotoVisualDescriber {
    suspend fun describe(imagePaths: List<String>): VisualUnderstanding
}
