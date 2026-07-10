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
        .fallbackToDestructiveMigration() // Strategy for initial dev phase
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
}
