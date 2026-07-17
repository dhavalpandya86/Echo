package com.dhaval.echo.di

import android.content.Context
import com.dhaval.echo.data.video.AndroidVideoStorageEngine
import com.dhaval.echo.domain.video.VideoStorageEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VideoModule {

    @Provides
    @Singleton
    fun provideVideoStorageEngine(@ApplicationContext context: Context): VideoStorageEngine {
        return AndroidVideoStorageEngine(context)
    }
}
