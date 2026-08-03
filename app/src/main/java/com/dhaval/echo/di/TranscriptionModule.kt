package com.dhaval.echo.di

import com.dhaval.echo.data.transcription.AndroidLiveDictation
import com.dhaval.echo.data.transcription.whisper.WhisperTranscriptionEngine
import com.dhaval.echo.domain.transcription.LiveDictation
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
     * File-based transcription (voice memos): on-device Whisper-base. It replaced
     * the mic-only SpeechRecognizer engine because that one could not read files.
     */
    @Binds
    @Singleton
    abstract fun bindSpeechToTextEngine(impl: WhisperTranscriptionEngine): SpeechToTextEngine

    /**
     * Live, mic-only dictation into text fields (words stream as you speak). This
     * IS the SpeechRecognizer path — fine here because dictation never needs files.
     */
    @Binds
    @Singleton
    abstract fun bindLiveDictation(impl: AndroidLiveDictation): LiveDictation
}
