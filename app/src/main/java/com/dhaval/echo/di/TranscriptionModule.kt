package com.dhaval.echo.di

import com.dhaval.echo.data.transcription.whisper.WhisperTranscriptionEngine
import com.dhaval.echo.domain.transcription.SpeechToTextEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TranscriptionModule {

    /**
     * MU-2: on-device Whisper-base replaces the mic-only SpeechRecognizer engine,
     * which could not transcribe recorded audio files at all.
     */
    @Binds
    @Singleton
    abstract fun bindSpeechToTextEngine(impl: WhisperTranscriptionEngine): SpeechToTextEngine
}
