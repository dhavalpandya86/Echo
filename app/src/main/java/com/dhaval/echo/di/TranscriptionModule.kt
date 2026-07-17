package com.dhaval.echo.di

import com.dhaval.echo.data.transcription.AndroidSpeechToTextEngine
import com.dhaval.echo.domain.transcription.SpeechToTextEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TranscriptionModule {

    @Binds
    @Singleton
    abstract fun bindSpeechToTextEngine(impl: AndroidSpeechToTextEngine): SpeechToTextEngine
}
