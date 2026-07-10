package com.dhaval.echo.di

import com.dhaval.echo.data.diary.RealDiaryRepository
import com.dhaval.echo.domain.diary.DiaryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiaryModule {

    @Binds
    @Singleton
    abstract fun bindDiaryRepository(
        realDiaryRepository: RealDiaryRepository
    ): DiaryRepository
}
