package com.dhaval.echo.data.intelligence

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.dhaval.echo.data.db.*
import com.dhaval.echo.domain.intelligence.*
import com.dhaval.echo.domain.tags.TagRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

@HiltWorker
class MemoryIntelligenceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val transcriptionService: TranscriptionService,
    private val intelligenceService: IntelligenceService,
    private val tagRepository: TagRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString("entryId") ?: return Result.failure()
        val entry = diaryEntryDao.getEntryById(entryId) ?: return Result.failure()

        try {
            // 1. Transcription Phase
            intelligenceDao.updateTranscriptionStatus(entryId, IntelligenceStatus.PROCESSING)
            val audioFile = File(entry.audioPath)
            if (!audioFile.exists()) return Result.failure()

            val transResult = transcriptionService.transcribe(audioFile)
            
            // Save transcript and segments
            intelligenceDao.updateTranscript(entryId, transResult.text, transResult.segments.firstOrNull()?.languageCode)
            val segments = transResult.segments.map { 
                TranscriptionSegmentEntity(
                    entryId = entryId,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    text = it.text,
                    languageCode = it.languageCode
                )
            }
            intelligenceDao.insertSegments(segments)
            intelligenceDao.updateTranscriptionStatus(entryId, IntelligenceStatus.COMPLETED)

            // 2. Intelligence/Analysis Phase
            intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.PROCESSING)
            val analysis = intelligenceService.analyzeContent(transResult.text)
            
            intelligenceDao.updateAnalysisResults(entryId, analysis.title, analysis.summary)
            
            // Add tags
            analysis.tags.forEach { tagName ->
                tagRepository.addTagToEntry(entryId, tagName)
            }
            
            intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.COMPLETED)

            // 3. Connections (Simplified for now, will find similar ones later)
            // TODO: Implement embedding-based similarity check

            return Result.success()
        } catch (e: Exception) {
            intelligenceDao.updateTranscriptionStatus(entryId, IntelligenceStatus.FAILED)
            intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.FAILED)
            return Result.retry()
        }
    }
}
