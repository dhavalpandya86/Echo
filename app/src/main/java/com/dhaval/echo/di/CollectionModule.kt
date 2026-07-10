package com.dhaval.echo.di

import com.dhaval.echo.data.collections.RealCollectionRepository
import com.dhaval.echo.domain.collections.CollectionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CollectionModule {

    @Binds
    @Singleton
    abstract fun bindCollectionRepository(
        realCollectionRepository: RealCollectionRepository
    ): CollectionRepository
}
