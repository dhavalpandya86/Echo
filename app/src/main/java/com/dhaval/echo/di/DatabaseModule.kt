package com.dhaval.echo.di

import android.content.Context
import androidx.room.Room
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.EchoDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EchoDatabase {
        return Room.databaseBuilder(
            context,
            EchoDatabase::class.java,
            EchoDatabase.DATABASE_NAME
        )
        .addMigrations(
            EchoDatabase.MIGRATION_8_9,
            EchoDatabase.MIGRATION_9_10,
            EchoDatabase.MIGRATION_10_11,
            EchoDatabase.MIGRATION_11_12,
            EchoDatabase.MIGRATION_12_13
        )
        // No fallbackToDestructiveMigration: this database holds the user's
        // diary. A missing migration must fail loudly at launch, not silently
        // erase every memory they have.
        .build()
    }

    @Provides
    fun provideDiaryEntryDao(database: EchoDatabase): DiaryEntryDao {
        return database.diaryEntryDao()
    }

    @Provides
    fun provideTagDao(database: EchoDatabase): com.dhaval.echo.data.db.TagDao {
        return database.tagDao()
    }

    @Provides
    fun provideCollectionDao(database: EchoDatabase): com.dhaval.echo.data.db.CollectionDao {
        return database.collectionDao()
    }

    @Provides
    fun provideIntelligenceDao(database: EchoDatabase): com.dhaval.echo.data.db.IntelligenceDao {
        return database.intelligenceDao()
    }

    @Provides
    fun provideUnderstandingDao(database: EchoDatabase): com.dhaval.echo.data.db.UnderstandingDao {
        return database.understandingDao()
    }
}
