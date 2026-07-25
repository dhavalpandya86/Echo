package com.dhaval.echo.data.ai

import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.domain.ai.*
import com.dhaval.echo.domain.auth.AuthRepository
import kotlinx.coroutines.flow.*
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class RealMemoryContextBuilder @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val semanticSearchService: SemanticSearchService,
    private val timelineIntelligenceService: TimelineIntelligenceService,
    private val authRepository: AuthRepository
) : MemoryContextBuilder {

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * The memories a question should be answered from. Semantic search first;
     * but broad, time-range questions ("how was my week") rarely match any single
     * memory semantically, so when search comes back thin we fall back to the
     * user's most recent memories. That's what makes "summarise my week" work
     * instead of returning "0 related memories".
     */
    private suspend fun relevantEntries(query: String, limit: Int): List<DiaryEntry> {
        val hits = semanticSearchService.search(query).first().take(limit)
            .mapNotNull { diaryEntryDao.getEntryById(it.entryId) }
        if (hits.isNotEmpty()) return hits

        val userId = authRepository.getCurrentUser()?.id ?: return emptyList()
        return diaryEntryDao.getAllEntries(userId).first().take(limit)
    }

    override fun buildContext(query: String, maxMemories: Int): Flow<String> = flow {
        val entries = relevantEntries(query, maxMemories)
        if (entries.isEmpty()) { emit(""); return@flow } // truly nothing → caller degrades

        val contextBuilder = StringBuilder()
        contextBuilder.append("Here is the relevant context from the user's memories:\n\n")
        for (entry in entries) {
            contextBuilder.append("Memory: ${entry.title}\n")
            contextBuilder.append("Date: ${entry.createdAt}\n")
            contextBuilder.append("Transcript: ${entry.transcript ?: "No transcript available"}\n")
            if (entry.summary != null) {
                contextBuilder.append("Summary: ${entry.summary}\n")
            }
            contextBuilder.append("---\n")
        }
        emit(contextBuilder.toString())
    }

    override fun buildCitations(query: String, maxCitations: Int): Flow<List<Citation>> = flow {
        val citations = relevantEntries(query, maxCitations).map { entry ->
            Citation(
                memoryId = entry.id,
                title = entry.title,
                date = entry.createdAt.format(formatter),
                snippet = (entry.summary ?: entry.transcript ?: "").take(100) + "..."
            )
        }
        emit(citations)
    }
}
