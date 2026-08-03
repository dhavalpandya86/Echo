package com.dhaval.echo.data.db

import androidx.room.*
import com.dhaval.echo.domain.ai.IntelligenceStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface IntelligenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptionSegmentEntity>)

    @Query("SELECT * FROM transcription_segments WHERE entryId = :entryId AND userId = :userId ORDER BY startTime ASC")
    fun getSegmentsForEntry(entryId: String, userId: String): Flow<List<TranscriptionSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnections(connections: List<MemoryConnection>)

    @Query("""
        SELECT de.* FROM diary_entries de
        INNER JOIN memory_connections conn ON de.id = conn.toEntryId
        WHERE conn.fromEntryId = :entryId AND de.userId = :userId
        ORDER BY conn.similarity DESC
    """)
    fun getRelatedEntries(entryId: String, userId: String): Flow<List<DiaryEntry>>

    @Query("UPDATE diary_entries SET transcriptionStatus = :status WHERE id = :entryId")
    suspend fun updateTranscriptionStatus(entryId: String, status: IntelligenceStatus)

    @Query("UPDATE diary_entries SET analysisStatus = :status WHERE id = :entryId")
    suspend fun updateAnalysisStatus(entryId: String, status: IntelligenceStatus)

    @Query("UPDATE diary_entries SET transcript = :transcript, language = :language WHERE id = :entryId")
    suspend fun updateTranscript(entryId: String, transcript: String, language: String?)

    @Query("UPDATE diary_entries SET title = :title, summary = :summary WHERE id = :entryId")
    suspend fun updateAnalysisResults(entryId: String, title: String, summary: String?)

    @Query("UPDATE diary_entries SET visualSummary = :visualSummary WHERE id = :entryId")
    suspend fun updateVisualSummary(entryId: String, visualSummary: String?)

    /**
     * The sentence-corrected text, written by the cleanup extractor. Never
     * touches `transcript` — that stays verbatim so playback and segment timings
     * keep lining up with what the user actually said.
     */
    @Query("UPDATE diary_entries SET cleanedText = :cleanedText WHERE id = :entryId")
    suspend fun updateCleanedText(entryId: String, cleanedText: String?)

    // Granular writes for the staged pipeline: each extractor settles one field
    // as it answers, rather than several fields being written together at the end.

    @Query("UPDATE diary_entries SET summary = :summary WHERE id = :entryId")
    suspend fun updateSummary(entryId: String, summary: String?)

    @Query("UPDATE diary_entries SET title = :title WHERE id = :entryId")
    suspend fun updateTitle(entryId: String, title: String)

    @Query("UPDATE diary_entries SET language = :language WHERE id = :entryId")
    suspend fun updateLanguage(entryId: String, language: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClassification(classification: MemoryClassificationEntity)

    @Query("SELECT * FROM memory_classifications WHERE entryId = :entryId AND userId = :userId")
    fun getClassificationForEntry(entryId: String, userId: String): Flow<MemoryClassificationEntity?>

    @Query("SELECT * FROM memory_connections WHERE (fromEntryId = :entryId OR toEntryId = :entryId) AND userId = :userId")
    fun getConnectionsForEntry(entryId: String, userId: String): Flow<List<MemoryConnection>>

    @Query("SELECT * FROM memory_classifications WHERE userId = :userId")
    suspend fun getAllClassifications(userId: String): List<MemoryClassificationEntity>

    @Query("SELECT * FROM memory_connections WHERE userId = :userId")
    suspend fun getAllConnections(userId: String): List<MemoryConnection>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInsights(insights: List<TimelineInsightEntity>)

    @Query("SELECT * FROM timeline_insights WHERE userId = :userId ORDER BY priority DESC, createdAt DESC")
    fun getAllInsights(userId: String): Flow<List<TimelineInsightEntity>>

    @Query("DELETE FROM timeline_insights WHERE userId = :userId")
    suspend fun deleteAllInsights(userId: String)

    @Transaction
    suspend fun updateInsights(userId: String, insights: List<TimelineInsightEntity>) {
        deleteAllInsights(userId)
        insertInsights(insights)
    }

    @Query("UPDATE transcription_segments SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacySegments(userId: String)

    @Query("UPDATE memory_classifications SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyClassifications(userId: String)

    @Query("UPDATE memory_connections SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyConnections(userId: String)

    @Query("UPDATE timeline_insights SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyInsights(userId: String)

    @Transaction
    suspend fun updateMemoryLinks(entryId: String, connections: List<MemoryConnection>) {
        // Simple implementation for now
        insertConnections(connections)
    }

    @Query("DELETE FROM memory_connections WHERE fromEntryId = :entryId OR toEntryId = :entryId")
    suspend fun deleteConnectionsForEntry(entryId: String)

    /**
     * Replaces every link touching [entryId] with a freshly-computed set. Used by
     * the embedding-based linker: recomputing an entry's whole neighbourhood each
     * time keeps links current and clears any stale ones (e.g. those the old
     * classification-keyword linker wrote).
     */
    @Transaction
    suspend fun replaceMemoryLinks(entryId: String, connections: List<MemoryConnection>) {
        deleteConnectionsForEntry(entryId)
        insertConnections(connections)
    }
}
