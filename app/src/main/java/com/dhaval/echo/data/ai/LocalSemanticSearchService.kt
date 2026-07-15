package com.dhaval.echo.data.ai

import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.domain.ai.ScoredResult
import com.dhaval.echo.domain.ai.SemanticSearchService
import com.dhaval.echo.domain.auth.AuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.util.Locale

/**
 * A lightweight, deterministic heuristic search engine for on-device use.
 * Does not use embeddings. It ranks memories based on keyword matches across
 * various fields with specific weighting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalSemanticSearchService(
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val authRepository: AuthRepository
) : SemanticSearchService {

    private val stopWords = setOf("the", "a", "an", "and", "or", "but", "i", "was", "were", "is", "are", "to", "of", "in", "it", "with", "that", "this", "for", "on", "at", "by", "from", "up", "about", "into", "over", "after", "show", "me", "find", "everything", "about", "what", "did", "say")

    override fun search(query: String): Flow<List<ScoredResult>> {
        if (query.isBlank()) return flowOf(emptyList())

        val normalizedTerms = query.lowercase(Locale.getDefault())
            .split(Regex("\\W+"))
            .filter { it.isNotBlank() && it !in stopWords }

        if (normalizedTerms.isEmpty()) return flowOf(emptyList())

        return authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest flowOf(emptyList())

            combine(
                diaryEntryDao.getAllEntries(userId),
                flow { emit(intelligenceDao.getAllClassifications(userId)) },
                flow { emit(intelligenceDao.getAllConnections(userId)) }
            ) { entries, classifications, connections ->
                val classificationMap = classifications.associateBy { it.entryId }
                val connectionsMap = (connections.groupBy { it.fromEntryId } + connections.groupBy { it.toEntryId })
                    .mapValues { it.value.size }
                
                entries.map { entry ->
                    val classification = classificationMap[entry.id]
                    var score = 0f
                    val matchedTerms = mutableListOf<String>()

                    normalizedTerms.forEach { term ->
                        var termMatched = false
                        
                        // Title Match (+30)
                        if (entry.title.lowercase(Locale.getDefault()).contains(term)) {
                            score += 30f
                            termMatched = true
                        }

                        // Entity Match (+25)
                        classification?.entities?.values?.flatten()?.forEach { entity ->
                            if (entity.lowercase(Locale.getDefault()).contains(term)) {
                                score += 25f
                                termMatched = true
                            }
                        }

                        // Category Match (+20)
                        classification?.categories?.forEach { category ->
                            if (category.lowercase(Locale.getDefault()).contains(term)) {
                                score += 20f
                                termMatched = true
                            }
                        }

                        // Keyword Match (+15)
                        classification?.keywords?.forEach { keyword ->
                            if (keyword.lowercase(Locale.getDefault()).contains(term)) {
                                score += 15f
                                termMatched = true
                            }
                        }

                        // Summary Match (+15)
                        if (entry.summary?.lowercase(Locale.getDefault())?.contains(term) == true) {
                            score += 15f
                            termMatched = true
                        }

                        // Transcript Match (+10)
                        if (entry.transcript?.lowercase(Locale.getDefault())?.contains(term) == true) {
                            score += 10f
                            termMatched = true
                        }

                        if (termMatched) {
                            matchedTerms.add(term)
                        }
                    }

                    // Related Memory Boost (+5 per connection if there was any match)
                    if (score > 0) {
                        val connectionCount = connectionsMap[entry.id] ?: 0
                        score += connectionCount * 5f
                    }

                    ScoredResult(
                        entryId = entry.id,
                        score = score,
                        matchedTerms = matchedTerms.distinct()
                    )
                }.filter { it.score > 0 }
                .sortedByDescending { it.score }
            }
        }
    }
}
