package com.dhaval.echo.di

import com.dhaval.echo.data.search.DatabaseSearchRepository
import com.dhaval.echo.domain.search.SearchRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchModule {

    @Binds
    @Singleton
    abstract fun bindSearchRepository(
        databaseSearchRepository: DatabaseSearchRepository
    ): SearchRepository
}
