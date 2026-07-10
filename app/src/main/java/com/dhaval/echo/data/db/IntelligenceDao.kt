package com.dhaval.echo.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IntelligenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptionSegmentEntity>)

    @Query("SELECT * FROM transcription_segments WHERE entryId = :entryId ORDER BY startTime ASC")
    fun getSegmentsForEntry(entryId: String): Flow<List<TranscriptionSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnections(connections: List<MemoryConnection>)

    @Query("""
        SELECT de.* FROM diary_entries de
        INNER JOIN memory_connections conn ON de.id = conn.toEntryId
        WHERE conn.fromEntryId = :entryId
        ORDER BY conn.similarity DESC
    """)
    fun getRelatedEntries(entryId: String): Flow<List<DiaryEntry>>

    @Query("UPDATE diary_entries SET transcriptionStatus = :status WHERE id = :entryId")
    suspend fun updateTranscriptionStatus(entryId: String, status: IntelligenceStatus)

    @Query("UPDATE diary_entries SET analysisStatus = :status WHERE id = :entryId")
    suspend fun updateAnalysisStatus(entryId: String, status: IntelligenceStatus)

    @Query("UPDATE diary_entries SET transcript = :transcript, language = :language WHERE id = :entryId")
    suspend fun updateTranscript(entryId: String, transcript: String, language: String?)

    @Query("UPDATE diary_entries SET title = :title, summary = :summary WHERE id = :entryId")
    suspend fun updateAnalysisResults(entryId: String, title: String, summary: String?)
}
