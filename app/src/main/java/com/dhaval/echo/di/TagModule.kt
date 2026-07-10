package com.dhaval.echo.di

import com.dhaval.echo.data.tags.RealTagRepository
import com.dhaval.echo.domain.tags.TagRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TagModule {

    @Binds
    @Singleton
    abstract fun bindTagRepository(
        realTagRepository: RealTagRepository
    ): TagRepository
}
