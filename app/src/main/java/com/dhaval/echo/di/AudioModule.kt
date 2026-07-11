package com.dhaval.echo.di

import android.content.Context
import com.dhaval.echo.data.audio.AndroidAudioStorageEngine
import com.dhaval.echo.data.audio.AndroidMediaRecorder
import com.dhaval.echo.data.audio.RealAudioRepository
import com.dhaval.echo.domain.audio.AudioRepository
import com.dhaval.echo.domain.audio.AudioStorageEngine
import com.dhaval.echo.domain.audio.Recorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideAudioStorageEngine(@ApplicationContext context: Context): AudioStorageEngine {
        return AndroidAudioStorageEngine(context)
    }

    @Provides
    @Singleton
    fun provideRecorder(
        @ApplicationContext context: Context,
        storageEngine: AudioStorageEngine
    ): Recorder {
        return AndroidMediaRecorder(context, storageEngine)
    }

    @Provides
    @Singleton
    fun provideAudioPlayer(@ApplicationContext context: Context): com.dhaval.echo.domain.audio.AudioPlayer {
        return com.dhaval.echo.data.audio.Media3AudioPlayer(context)
    }

    @Provides
    @Singleton
    fun provideAudioRepository(
        recorder: Recorder,
        storageEngine: AudioStorageEngine,
        diaryEntryDao: com.dhaval.echo.data.db.DiaryEntryDao,
        intelligenceRepository: com.dhaval.echo.domain.intelligence.IntelligenceRepository,
        authRepository: com.dhaval.echo.domain.auth.AuthRepository
    ): AudioRepository {
        return RealAudioRepository(recorder, storageEngine, diaryEntryDao, intelligenceRepository, authRepository)
    }
}
