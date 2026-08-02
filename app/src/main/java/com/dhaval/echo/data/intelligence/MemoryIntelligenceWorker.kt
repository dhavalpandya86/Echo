package com.dhaval.echo.data.intelligence

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.dhaval.echo.data.db.*
import com.dhaval.echo.data.understanding.StagedUnderstandingRunner
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.ai.IntelligenceStatus
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import com.dhaval.echo.domain.understanding.Stage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.last
import java.io.File

/**
 * Gets a memory readable, fast.
 *
 * This is the half of the pipeline the user is waiting on: transcribe the audio,
 * read any photos, and correct the sentence so what appears on screen is text
 * they would actually write. Then it hands off to [UnderstandingWorker] and
 * finishes — the ~22 extraction questions are minutes of work on-device, and
 * making someone stare at a spinner through them would be the wrong trade.
 *
 * A memory may be voice, text, photos, or any mix, so source text is resolved
 * from every modality present. A memory with no source text at all (photos only)
 * is a valid, fully-processed memory — not a failure.
 */
@HiltWorker
class MemoryIntelligenceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val aiManager: AIManager,
    private val runner: StagedUnderstandingRunner,
    private val photoTextExtractor: com.dhaval.echo.domain.understanding.PhotoTextExtractor,
    private val photoVisualDescriber: com.dhaval.echo.domain.understanding.PhotoVisualDescriber
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID) ?: run {
            Log.e(TAG, "No entryId in input data")
            return Result.failure()
        }
        val entry = diaryEntryDao.getEntryById(entryId) ?: run {
            Log.e(TAG, "Entry $entryId not found — it may have been deleted")
            return Result.failure()
        }

        return try {
            val sourceText = resolveSourceText(entry)

            if (sourceText.isBlank()) {
                // Nothing to reason about (photo-only memory, or empty recording).
                // That's a complete memory, not a failed one.
                Log.d(TAG, "Entry $entryId has no source text — skipping analysis")
                intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.COMPLETED)
                return Result.success()
            }

            // PREPARE: correct the sentence so the text on screen reads as the
            // user would have written it. Cheap enough to keep on this path,
            // and everything downstream reasons about the corrected version.
            runCatching {
                runner.run(
                    content = normalizedContentFor(entry, sourceText),
                    stages = setOf(Stage.PREPARE)
                )
            }.onFailure { Log.w(TAG, "Text preparation failed for $entryId", it) }

            intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.COMPLETED)
            Log.d(TAG, "Memory $entryId is readable — handing off to understanding")

            // The slow, thorough half. Runs on its own, resumably, while the
            // user gets on with their day.
            UnderstandingWorker.enqueue(applicationContext, entryId)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Intelligence pipeline failed for $entryId", e)
            intelligenceDao.updateTranscriptionStatus(entryId, IntelligenceStatus.FAILED)
            intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.FAILED)
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    /**
     * The text this memory should be reasoned about: transcript (voice) +
     * written content (text) + OCR (photos), so a mixed memory contributes every
     * modality and a photo-only memory is no longer text-less.
     */
    private suspend fun resolveSourceText(entry: DiaryEntry): String {
        val audioFile = entry.audioPath.takeIf { it.isNotBlank() }?.let(::File)
        val hasAudio = audioFile?.exists() == true

        val transcript = if (hasAudio) {
            intelligenceDao.updateTranscriptionStatus(entry.id, IntelligenceStatus.TRANSCRIBING)
            val result = aiManager.getTranscriptionService().transcribe(audioFile!!.absolutePath).last()
            intelligenceDao.updateTranscript(
                entry.id, result.text, result.segments.firstOrNull()?.languageCode
            )
            if (result.segments.isNotEmpty()) {
                intelligenceDao.insertSegments(
                    result.segments.map {
                        TranscriptionSegmentEntity(
                            entryId = entry.id,
                            userId = entry.userId,
                            startTime = it.startTime,
                            endTime = it.endTime,
                            text = it.text,
                            languageCode = it.languageCode
                        )
                    }
                )
            }
            intelligenceDao.updateTranscriptionStatus(entry.id, IntelligenceStatus.COMPLETED)
            result.text
        } else {
            if (entry.audioPath.isNotBlank()) {
                // Path recorded but file is gone — worth knowing about; not fatal.
                Log.w(TAG, "Entry ${entry.id} references missing audio: ${entry.audioPath}")
            }
            intelligenceDao.updateTranscriptionStatus(entry.id, IntelligenceStatus.COMPLETED)
            ""
        }

        // Photo OCR (MU-3) — degrade to empty on any failure, never block the memory.
        val ocr = runCatching { photoTextExtractor.extractText(entry.imagePaths.orEmpty()) }
            .onFailure { Log.w(TAG, "Photo OCR failed for ${entry.id}", it) }
            .getOrDefault("")

        // Photo visual understanding (on-device labels + EXIF-GPS place). Its snippet
        // joins the source text so places become entities and labels become tags;
        // the human summary is stored for the "Echo sees…" line.
        val visual = runCatching { photoVisualDescriber.describe(entry.imagePaths.orEmpty()) }
            .onFailure { Log.w(TAG, "Photo visual understanding failed for ${entry.id}", it) }
            .getOrNull()
        intelligenceDao.updateVisualSummary(entry.id, visual?.asSummary())

        return listOfNotNull(
            transcript.takeIf { it.isNotBlank() },
            entry.textContent?.takeIf { it.isNotBlank() },
            ocr.takeIf { it.isNotBlank() },
            visual?.asSourceText()?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n").trim()
    }

    /** Stage-2 canonical form: after this, source kind no longer matters. */
    private fun normalizedContentFor(entry: DiaryEntry, sourceText: String): NormalizedContent {
        val kinds = buildSet {
            if (entry.audioPath.isNotBlank()) add(SourceKind.VOICE)
            if (!entry.textContent.isNullOrBlank()) add(SourceKind.TEXT)
            if (!entry.imagePaths.isNullOrEmpty()) add(SourceKind.PHOTO)
            if (!entry.videos.isNullOrEmpty()) add(SourceKind.VIDEO)
        }
        return NormalizedContent(
            memoryId = entry.id,
            userId = entry.userId,
            text = sourceText,
            sourceKinds = kinds.ifEmpty { setOf(SourceKind.TEXT) },
            capturedAt = entry.createdAt,
            imagePaths = entry.imagePaths.orEmpty()
        )
    }

    companion object {
        const val KEY_ENTRY_ID = "entryId"
        private const val TAG = "MemoryIntelWorker"
        private const val MAX_ATTEMPTS = 3
    }
}
