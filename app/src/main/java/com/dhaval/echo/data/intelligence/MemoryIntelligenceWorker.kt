package com.dhaval.echo.data.intelligence

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.dhaval.echo.data.db.*
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.ai.IntelligenceStatus
import com.dhaval.echo.domain.intelligence.*
import com.dhaval.echo.domain.tags.TagRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.last
import java.io.File

@HiltWorker
class MemoryIntelligenceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val aiManager: AIManager,
    private val intelligenceService: IntelligenceService,
    private val tagRepository: TagRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString("entryId") ?: return Result.failure()
        val entry = diaryEntryDao.getEntryById(entryId) ?: return Result.failure()
        val userId = entry.userId

        try {
            // 1. Transcription Phase
            intelligenceDao.updateTranscriptionStatus(entryId, IntelligenceStatus.TRANSCRIBING)
            val audioFile = File(entry.audioPath)
            if (!audioFile.exists()) return Result.failure()

            val transcriptionService = aiManager.getTranscriptionService()
            val transResult = transcriptionService.transcribe(audioFile.absolutePath).last()
            
            // Save transcript and segments
            intelligenceDao.updateTranscript(entryId, transResult.text, transResult.segments.firstOrNull()?.languageCode)
            val segments = transResult.segments.map { 
                TranscriptionSegmentEntity(
                    entryId = entryId,
                    userId = userId,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    text = it.text,
                    languageCode = it.languageCode
                )
            }
            intelligenceDao.insertSegments(segments)
            intelligenceDao.updateTranscriptionStatus(entryId, IntelligenceStatus.COMPLETED)

            // 2. Summarization Phase
            try {
                intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.SUMMARIZING)
                val summaryService = aiManager.getSummaryService()
                val summary = summaryService.summarize(transResult.text).last()
                
                intelligenceDao.updateAnalysisResults(entryId, entry.title, summary)
                
                // 3. Classification Phase
                intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.CLASSIFYING)
                val classificationService = aiManager.getMemoryClassificationService()
                val classification = classificationService.classify(transResult.text, summary).last()
                
                intelligenceDao.insertClassification(
                    MemoryClassificationEntity(
                        entryId = entryId,
                        userId = userId,
                        categories = classification.categories,
                        keywords = classification.keywords,
                        entities = classification.entities,
                        confidence = classification.confidence
                    )
                )

                // 4. Memory Linking Phase
                intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.LINKING)
                val otherClassifications = intelligenceDao.getAllClassifications(userId)
                    .filter { it.entryId != entryId }
                
                val connections = mutableListOf<MemoryConnection>()
                otherClassifications.forEach { other ->
                    val similarity = calculateSimilarity(classification, other)
                    if (similarity > 0.3f) {
                        connections.add(
                            MemoryConnection(
                                fromEntryId = entryId,
                                toEntryId = other.entryId,
                                userId = userId,
                                similarity = similarity,
                                connectionReason = "Shared topics or keywords"
                            )
                        )
                    }
                }
                
                if (connections.isNotEmpty()) {
                    intelligenceDao.updateMemoryLinks(entryId, connections)
                }

                // 5. Timeline Intelligence Phase
                intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.ANALYZING_TIMELINE)
                val timelineService = aiManager.getTimelineIntelligenceService()
                val insights = timelineService.analyzeTimeline().last()
                
                if (insights.isNotEmpty()) {
                    intelligenceDao.updateInsights(userId, insights.map { 
                        TimelineInsightEntity(
                            id = it.id,
                            userId = userId,
                            title = it.title,
                            description = it.description,
                            type = it.type,
                            confidence = it.confidence,
                            relatedMemoryIds = it.relatedMemoryIds,
                            createdAt = it.createdAt,
                            priority = it.priority
                        )
                    })
                }

                intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.COMPLETED)
            } catch (e: Exception) {
                intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.FAILED)
            }

            return Result.success()
        } catch (e: Exception) {
            intelligenceDao.updateTranscriptionStatus(entryId, IntelligenceStatus.FAILED)
            return Result.retry()
        }
    }

    private fun calculateSimilarity(current: com.dhaval.echo.domain.ai.MemoryClassification, other: MemoryClassificationEntity): Float {
        // Simple heuristic similarity based on shared keywords and categories
        val sharedKeywords = current.keywords.intersect(other.keywords.toSet()).size
        val sharedCategories = current.categories.intersect(other.categories.toSet()).size
        
        val keywordScore = if (current.keywords.isEmpty()) 0f else sharedKeywords.toFloat() / current.keywords.size
        val categoryScore = if (current.categories.isEmpty()) 0f else sharedCategories.toFloat() / current.categories.size
        
        return (keywordScore * 0.6f) + (categoryScore * 0.4f)
    }
}
