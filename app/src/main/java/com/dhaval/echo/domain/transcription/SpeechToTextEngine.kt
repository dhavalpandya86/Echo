package com.dhaval.echo.domain.transcription

/**
 * Abstraction for Speech-to-Text engines.
 */
interface SpeechToTextEngine {

    /**
     * Transcribes audio from the given path.
     *
     * @param audioPath Absolute path to the audio file.
     * @param languageHint Optional BCP-47 language code hint.
     * @return [TranscriptionResult] containing the transcript or error details.
     */
    suspend fun transcribe(
        audioPath: String,
        languageHint: String? = null
    ): TranscriptionResult
}
