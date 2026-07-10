package com.dhaval.echo.domain.audio

/**
 * Configuration for the Capture Engine.
 * Allows for production-quality adjustments and future-proof encryption support.
 */
data class AudioConfig(
    val sampleRate: Int = 44100,
    val bitRate: Int = 128000,
    val channelCount: Int = 1, // Mono preferred for memory-efficiency and AI compatibility
    val format: AudioFormat = AudioFormat.AAC_LC,
    val isEncryptionEnabled: Boolean = false,
    val bufferSizeMultiplier: Int = 2 // For crash recovery and stability during long sessions
)

enum class AudioFormat {
    AAC_LC,
    FLAC,  // Lossless for premium archival
    OPUS   // Future-proof for efficient sync
}
