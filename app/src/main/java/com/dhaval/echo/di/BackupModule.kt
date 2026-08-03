package com.dhaval.echo.di

import android.content.Context
import com.dhaval.echo.data.backup.BackupLocalState
import com.dhaval.echo.data.backup.BackupPreferences
import com.dhaval.echo.data.backup.SafStorageProvider
import com.dhaval.echo.data.backup.SettingsSnapshotter
import com.dhaval.echo.data.backup.StorageProvider
import com.dhaval.echo.data.preferences.aiDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {

    /**
     * The only destination today. Everything upstream of this binding — the
     * archive format, the pipeline, restore — is written against
     * [StorageProvider] and has no idea where bytes end up, so adding Drive or a
     * NAS later is a second implementation and a changed binding, not a change
     * to the code that handles people's diaries.
     */
    @Binds
    @Singleton
    abstract fun bindStorageProvider(impl: SafStorageProvider): StorageProvider

    companion object {

        /**
         * Constructed here rather than via `@Inject` for the same reason
         * [PreferencesModule] does it: `DataStore<Preferences>` is deliberately
         * not a bound type, so each preferences class is handed the one shared
         * store explicitly instead of the graph guessing which store it wants.
         */
        @Provides
        @Singleton
        fun provideBackupPreferences(
            @ApplicationContext context: Context,
            localState: BackupLocalState
        ): BackupPreferences = BackupPreferences(context.aiDataStore, localState)

        @Provides
        @Singleton
        fun provideSettingsSnapshotter(
            @ApplicationContext context: Context
        ): SettingsSnapshotter = SettingsSnapshotter(context.aiDataStore)
    }
}
