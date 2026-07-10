package com.dhaval.echo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Main Room Database for the Echo application.
 * Versioning is handled here with a clear migration strategy.
 */
@Database(
    entities = [
        DiaryEntry::class,
        Tag::class,
        DiaryEntryTagCrossRef::class,
        EchoCollection::class,
        DiaryEntryCollectionCrossRef::class,
        TranscriptionSegmentEntity::class,
        MemoryConnection::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun diaryEntryDao(): DiaryEntryDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun intelligenceDao(): IntelligenceDao

    companion object {
        const val DATABASE_NAME = "echo_db"
    }
}
