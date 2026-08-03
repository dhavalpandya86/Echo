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
        EntityRelationship::class,
        ExtractionRun::class
    ],
    version = 19,
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
         * Mirrors the `version` in the `@Database` annotation above, which Room
         * does not expose until a database is open.
         *
         * Backup writes this into every archive's manifest, and restore compares
         * it before unpacking anything: an archive from a newer Echo is refused
         * with an explanation rather than being fed to a migration chain that
         * has no idea what to do with it. Bump both together.
         */
        const val DATABASE_VERSION = 19

        /**
         * The whole migration chain, in one place.
         *
         * Restore needs the identical list: an archive from an older version is
         * brought up to date by exactly the path an in-place upgrade would have
         * taken, so there is no second, subtly different route into the current
         * schema. Anywhere that opens this database must use this array.
         */
        val MIGRATIONS: Array<Migration> by lazy {
            arrayOf(
                MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19
            )
        }

        /**
         * Memory Intelligence Pipeline: understanding becomes ~22 single-question
         * extractors run one at a time in the background, so the pipeline needs to
         * remember its own progress.
         *
         *  - `memory_extraction_runs` — one row per extractor per memory. Makes a
         *    half-understood memory a durable, resumable state rather than a lost
         *    one, and doubles as the progress channel the detail screen reads.
         *  - `diary_entries.cleanedText` — the sentence-corrected text. Separate
         *    from `transcript`, which must stay verbatim for playback alignment.
         *  - `entity_relationships.asserted` — distinguishes an edge a model read
         *    out of a sentence from one counted from co-occurrence.
         *
         * Entirely additive. Existing memories keep every conclusion they already
         * have; they simply have no run rows until the backfill re-processes them.
         * The new evidence kinds (OBJECT, ACTIVITY, INTENT, MEMORY_TYPE, CATEGORY,
         * PRIORITY) need no schema change at all — `entities.type` and
         * `extracted_items.kind` are TEXT precisely so the vocabulary can grow.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `diary_entries` ADD COLUMN `cleanedText` TEXT")
                db.execSQL("ALTER TABLE `entity_relationships` ADD COLUMN `asserted` INTEGER NOT NULL DEFAULT 0")

                // Provenance: which question produced this row. Required for the
                // runner to replace one extractor's output without disturbing the
                // other 21 — the whole basis of incremental, resumable extraction.
                // Nullable: rows written before the pipeline existed have no
                // extractor, and a full re-process replaces them anyway.
                db.execSQL("ALTER TABLE `memory_entity_links` ADD COLUMN `extractorId` TEXT")
                db.execSQL("ALTER TABLE `extracted_items` ADD COLUMN `extractorId` TEXT")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `memory_extraction_runs` (
                        `memoryId` TEXT NOT NULL, `extractorId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL, `status` TEXT NOT NULL,
                        `engine` TEXT NOT NULL, `evidenceCount` INTEGER NOT NULL,
                        `startedAt` TEXT NOT NULL, `completedAt` TEXT,
                        `latencyMs` INTEGER, `error` TEXT,
                        PRIMARY KEY(`memoryId`, `extractorId`),
                        FOREIGN KEY(`memoryId`) REFERENCES `diary_entries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_extraction_runs_memoryId` ON `memory_extraction_runs` (`memoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_extraction_runs_userId` ON `memory_extraction_runs` (`userId`)")
            }
        }

        /**
         * Auto-suggested collections: link an AI-generated collection back to the
         * topic/project entity it was built around, so the curator updates the same
         * collection on each run rather than making duplicates. Additive nullable
         * column; manual collections leave it null.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `collections` ADD COLUMN `sourceEntityId` TEXT")
            }
        }

        /**
         * Per-photo captions: the user's own words about a photo, stored as a JSON
         * map keyed by image path. Additive nullable column; existing memories have
         * no captions until the user writes one.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `diary_entries` ADD COLUMN `photoCaptions` TEXT")
            }
        }

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
