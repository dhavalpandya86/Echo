package com.dhaval.echo.di

import android.content.Context
import com.dhaval.echo.data.preferences.AiPreferences
import com.dhaval.echo.data.preferences.aiDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    @Singleton
    fun provideAiPreferences(@ApplicationContext context: Context): AiPreferences {
        return AiPreferences(context.aiDataStore)
    }

    /**
     * Shares the same DataStore file as [AiPreferences] — one preferences store
     * per app, keyed by name. `DataStore<Preferences>` itself is deliberately
     * not a bound type, so each preferences class is constructed with it here
     * rather than injecting it.
     */
    @Provides
    @Singleton
    fun provideAppearancePreferences(
        @ApplicationContext context: Context
    ): com.dhaval.echo.data.preferences.AppearancePreferences {
        return com.dhaval.echo.data.preferences.AppearancePreferences(context.aiDataStore)
    }
}
