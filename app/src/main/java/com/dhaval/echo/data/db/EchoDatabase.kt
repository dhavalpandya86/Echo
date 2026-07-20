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
        MessageEntity::class,
        EntityNode::class,
        MemoryEntityLink::class,
        ExtractedItem::class,
        EntityRelationship::class
    ],
    version = 16,
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
    abstract fun understandingDao(): UnderstandingDao

    companion object {
        const val DATABASE_NAME = "echo_db"

        /**
         * Photo understanding: the on-device "Echo sees…" summary (image labels +
         * EXIF-GPS place). Additive nullable column.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `diary_entries` ADD COLUMN `visualSummary` TEXT")
            }
        }

        /**
         * Phase B (corrections loop): users can archive an entity Echo got wrong
         * or that is just noise. Additive nullable-safe column, default 0 (false).
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `entities` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Phase A (spec 07 — Knowledge Graph): the weighted entity↔entity edge
         * table. Additive; existing data is untouched and edges are (re)built by
         * the resolver as memories are processed.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `entity_relationships` (
                        `id` TEXT NOT NULL, `userId` TEXT NOT NULL,
                        `sourceEntityId` TEXT NOT NULL, `targetEntityId` TEXT NOT NULL,
                        `relation` TEXT NOT NULL, `weight` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL, `evidence` TEXT,
                        `firstSeenAt` TEXT NOT NULL, `lastSeenAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sourceEntityId`) REFERENCES `entities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`targetEntityId`) REFERENCES `entities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_entity_relationships_sourceEntityId_targetEntityId` ON `entity_relationships` (`sourceEntityId`, `targetEntityId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_relationships_targetEntityId` ON `entity_relationships` (`targetEntityId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entity_relationships_userId` ON `entity_relationships` (`userId`)")
            }
        }

        /**
         * Memory Understanding Engine foundation (MU-0): the Entity Graph
         * (`entities` — long-lived people/projects/places/topics), the Memory
         * Graph (`memory_entity_links` — typed event→entity edges with
         * confidence + evidence), and the evidence board (`extracted_items` —
         * tasks/reminders/mood/decisions). Purely additive; existing memories
         * simply have no graph presence until (re)processed.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `entities` (
                        `id` TEXT NOT NULL, `userId` TEXT NOT NULL, `type` TEXT NOT NULL,
                        `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL,
                        `aliases` TEXT NOT NULL, `firstSeenAt` TEXT NOT NULL,
                        `lastSeenAt` TEXT NOT NULL, `memoryCount` INTEGER NOT NULL,
                        `embedding` TEXT, PRIMARY KEY(`id`))"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entities_userId_type_normalizedName` ON `entities` (`userId`, `type`, `normalizedName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entities_userId` ON `entities` (`userId`)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `memory_entity_links` (
                        `id` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `entityId` TEXT NOT NULL,
                        `relation` TEXT NOT NULL, `confidence` REAL NOT NULL,
                        `evidence` TEXT, `inferred` INTEGER NOT NULL, `createdAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`memoryId`) REFERENCES `diary_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`entityId`) REFERENCES `entities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_entity_links_memoryId` ON `memory_entity_links` (`memoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_entity_links_entityId` ON `memory_entity_links` (`entityId`)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `extracted_items` (
                        `id` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `userId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL, `value` TEXT NOT NULL, `dueAtMillis` INTEGER,
                        `confidence` REAL NOT NULL, `evidence` TEXT, `status` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL, PRIMARY KEY(`id`),
                        FOREIGN KEY(`memoryId`) REFERENCES `diary_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_extracted_items_memoryId` ON `extracted_items` (`memoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_extracted_items_userId` ON `extracted_items` (`userId`)")
            }
        }

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
