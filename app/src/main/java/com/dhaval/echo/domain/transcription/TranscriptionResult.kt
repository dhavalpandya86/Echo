package com.dhaval.echo.domain.transcription

/**
 * Result of a transcription request.
 */
data class TranscriptionResult(
    val transcript: String,
    val detectedLanguage: String? = null,
    val confidence: Float = 0f,
    val duration: Long = 0L,
    val processingTime: Long = 0L,
    val providerName: String = "",
    val success: Boolean = true,
    val errorMessage: String? = null
)
