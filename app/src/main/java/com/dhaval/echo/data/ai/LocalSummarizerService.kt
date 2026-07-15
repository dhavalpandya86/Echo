package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.SummaryService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * A lightweight, deterministic heuristic summarizer for on-device use.
 * Does not use an LLM. It extracts key sentences based on word frequency.
 */
class LocalSummarizerService : SummaryService {

    override fun summarize(text: String): Flow<String> = flow {
        delay(1500) // Simulate processing time

        if (text.isBlank()) {
            emit("")
            return@flow
        }

        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }

        if (sentences.size <= 2) {
            emit(text)
            return@flow
        }

        // Heuristic: Extract first sentence + most "important" words/concepts
        // For a true heuristic, we'd rank sentences by keyword density.
        // For this sprint, we'll implement a "Concise Extractive" approach.
        
        val stopWords = setOf("the", "a", "an", "and", "or", "but", "i", "was", "were", "is", "are", "to", "of", "in", "it", "with")
        val wordFrequencies = mutableMapOf<String, Int>()
        
        text.lowercase(Locale.getDefault())
            .split(Regex("\\W+"))
            .filter { it.length > 3 && it !in stopWords }
            .forEach { word ->
                wordFrequencies[word] = (wordFrequencies[word] ?: 0) + 1
            }

        // Score sentences based on word frequency sum
        val scoredSentences = sentences.map { sentence ->
            val score = sentence.lowercase(Locale.getDefault())
                .split(Regex("\\W+"))
                .sumOf { wordFrequencies[it] ?: 0 }
            sentence to score
        }

        val bestSentences = scoredSentences
            .sortedByDescending { it.second }
            .take(2) // Get top 2 sentences
            .sortedBy { sentences.indexOf(it.first) } // Keep original order
            .map { it.first }

        val summary = bestSentences.joinToString(" ")
        
        // Ensure it's not too long (approx 10-30 words as per PRD)
        val words = summary.split(Regex("\\s+")).filter { it.isNotBlank() }
        val finalSummary = if (words.size > 30) {
            words.take(30).joinToString(" ") + "..."
        } else {
            summary
        }

        emit(finalSummary)
    }
}
