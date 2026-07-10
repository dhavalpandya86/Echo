package com.dhaval.echo.di

import com.dhaval.echo.data.timeline.DatabaseTimelineRepository
import com.dhaval.echo.domain.timeline.TimelineRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TimelineModule {

    @Binds
    @Singleton
    abstract fun bindTimelineRepository(
        databaseTimelineRepository: DatabaseTimelineRepository
    ): TimelineRepository
}
