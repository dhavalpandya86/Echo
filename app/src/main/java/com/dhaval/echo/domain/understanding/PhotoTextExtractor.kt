package com.dhaval.echo.domain.understanding

/**
 * Stage-1 source extractor for the Photo modality (MU-3): reads any text
 * present in a memory's images (a whiteboard, a note, a sign) so it can join
 * the memory's normalized text and flow through the same analyzers.
 */
interface PhotoTextExtractor {
    /**
     * OCR text merged across the given image files, newline-separated. Returns
     * an empty string when there are no images or nothing is recognized;
     * failures are logged and degrade to empty, never fabricated.
     */
    suspend fun extractText(imagePaths: List<String>): String
}
