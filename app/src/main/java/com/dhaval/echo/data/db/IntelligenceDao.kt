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
}
