package com.dhaval.echo.data.ai

import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.domain.ai.*
import kotlinx.coroutines.flow.*
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class RealMemoryContextBuilder @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val semanticSearchService: SemanticSearchService,
    private val timelineIntelligenceService: TimelineIntelligenceService
) : MemoryContextBuilder {

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun buildContext(query: String, maxMemories: Int): Flow<String> = flow {
        val searchResults = semanticSearchService.search(query).first()
        val topResults = searchResults.take(maxMemories)
        
        val contextBuilder = StringBuilder()
        contextBuilder.append("Here is the relevant context from the user's memories:\n\n")
        
        for (result in topResults) {
            val entry = diaryEntryDao.getEntryById(result.entryId)
            if (entry != null) {
                contextBuilder.append("Memory: ${entry.title}\n")
                contextBuilder.append("Date: ${entry.createdAt}\n")
                contextBuilder.append("Transcript: ${entry.transcript ?: "No transcript available"}\n")
                if (entry.summary != null) {
                    contextBuilder.append("Summary: ${entry.summary}\n")
                }
                contextBuilder.append("---\n")
            }
        }
        
        emit(contextBuilder.toString())
    }

    override fun buildCitations(query: String, maxCitations: Int): Flow<List<Citation>> = flow {
        val searchResults = semanticSearchService.search(query).first()
        val topResults = searchResults.take(maxCitations)
        
        val citations = topResults.mapNotNull { result ->
            val entry = diaryEntryDao.getEntryById(result.entryId)
            if (entry != null) {
                Citation(
                    memoryId = entry.id,
                    title = entry.title,
                    date = entry.createdAt.format(formatter),
                    snippet = (entry.transcript ?: "").take(100) + "..."
                )
            } else null
        }
        
        emit(citations)
    }
}
