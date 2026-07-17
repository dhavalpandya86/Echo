package com.dhaval.echo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        MemoryConnection::class,
        UserProfile::class,
        MemoryClassificationEntity::class,
        TimelineInsightEntity::class,
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun diaryEntryDao(): DiaryEntryDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun intelligenceDao(): IntelligenceDao
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        const val DATABASE_NAME = "echo_db"

        /**
         * Adds the on-device embedding columns for semantic search.
         *
         * These columns were added to [DiaryEntry] without a version bump,
         * which left the entity and the declared schema out of sync: Room
         * rewrote 11.json with a new identity hash while devices still held a
         * database built from the old one, and refused to open it. Additive and
         * nullable, so existing memories keep their data and simply have no
         * embedding until the worker generates one.
         *
         * `embedding` is TEXT because FloatArray is stored as JSON by
         * [Converters]; it is not a native SQLite type.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN embedding TEXT")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN embeddingDimensions INTEGER")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN embeddingModelVersion TEXT")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN embeddingCreatedAt INTEGER")
            }
        }

        /**
         * Adds video attachments. Additive and nullable: existing rows read back
         * as `videos = null` (no videos), so every memory written before v11
         * survives untouched. No table rewrite, no backfill.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN videos TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN textContent TEXT")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN imagePaths TEXT")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN entryType TEXT NOT NULL DEFAULT 'VOICE'")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add userId to simple tables
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN userId TEXT NOT NULL DEFAULT 'legacy_user'")
                db.execSQL("ALTER TABLE timeline_insights ADD COLUMN userId TEXT NOT NULL DEFAULT 'legacy_user'")
                db.execSQL("ALTER TABLE memory_classifications ADD COLUMN userId TEXT NOT NULL DEFAULT 'legacy_user'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN userId TEXT NOT NULL DEFAULT 'legacy_user'")
                db.execSQL("ALTER TABLE messages ADD COLUMN userId TEXT NOT NULL DEFAULT 'legacy_user'")
                db.execSQL("ALTER TABLE transcription_segments ADD COLUMN userId TEXT NOT NULL DEFAULT 'legacy_user'")
                db.execSQL("ALTER TABLE memory_connections ADD COLUMN userId TEXT NOT NULL DEFAULT 'legacy_user'")
                db.execSQL("ALTER TABLE collections ADD COLUMN userId TEXT NOT NULL DEFAULT 'legacy_user'")

                // Update user_profiles
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN phoneNumber TEXT")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN authProvidersJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_profiles ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

                // Migrate tags table (recreate due to PK change)
                db.execSQL("CREATE TABLE tags_new (name TEXT NOT NULL, userId TEXT NOT NULL DEFAULT 'legacy_user', PRIMARY KEY(name, userId))")
                db.execSQL("INSERT INTO tags_new (name) SELECT name FROM tags")
                db.execSQL("DROP TABLE tags")
                db.execSQL("ALTER TABLE tags_new RENAME TO tags")

                // Migrate diary_entry_tag_cross_ref (recreate due to PK and FK change)
                db.execSQL("CREATE TABLE det_new (entryId TEXT NOT NULL, tagName TEXT NOT NULL, userId TEXT NOT NULL DEFAULT 'legacy_user', " +
                        "PRIMARY KEY(entryId, tagName, userId), " +
                        "FOREIGN KEY(entryId) REFERENCES diary_entries(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(tagName, userId) REFERENCES tags(name, userId) ON DELETE CASCADE)")
                db.execSQL("INSERT INTO det_new (entryId, tagName) SELECT entryId, tagName FROM diary_entry_tag_cross_ref")
                db.execSQL("DROP TABLE diary_entry_tag_cross_ref")
                db.execSQL("ALTER TABLE det_new RENAME TO diary_entry_tag_cross_ref")
                db.execSQL("CREATE INDEX index_det_entryId ON diary_entry_tag_cross_ref(entryId)")
                db.execSQL("CREATE INDEX index_det_tagName_userId ON diary_entry_tag_cross_ref(tagName, userId)")

                // Migrate diary_entry_collection_cross_ref (recreate due to PK change)
                db.execSQL("CREATE TABLE dec_new (entryId TEXT NOT NULL, collectionId TEXT NOT NULL, userId TEXT NOT NULL DEFAULT 'legacy_user', " +
                        "PRIMARY KEY(entryId, collectionId, userId), " +
                        "FOREIGN KEY(entryId) REFERENCES diary_entries(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(collectionId) REFERENCES collections(id) ON DELETE CASCADE)")
                db.execSQL("INSERT INTO dec_new (entryId, collectionId) SELECT entryId, collectionId FROM diary_entry_collection_cross_ref")
                db.execSQL("DROP TABLE diary_entry_collection_cross_ref")
                db.execSQL("ALTER TABLE dec_new RENAME TO diary_entry_collection_cross_ref")
                db.execSQL("CREATE INDEX index_dec_entryId ON diary_entry_collection_cross_ref(entryId)")
                db.execSQL("CREATE INDEX index_dec_collectionId ON diary_entry_collection_cross_ref(collectionId)")
                db.execSQL("CREATE INDEX index_dec_userId ON diary_entry_collection_cross_ref(userId)")
            }
        }
    }
}
