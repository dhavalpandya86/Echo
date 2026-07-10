package com.dhaval.echo.domain.audio

/**
 * Domain model representing a single recording session.
 * Tracks metadata before the session is persisted to the database.
 */
data class AudioSession(
    val id: String,
    val startTimeMillis: Long,
    val config: AudioConfig,
    val filePath: String,
    val metadata: Map<String, String> = emptyMap()
)
