package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.MemoryClassification
import com.dhaval.echo.domain.ai.MemoryClassificationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * A lightweight, deterministic heuristic classifier for on-device use.
 * Does not use an LLM. It extracts categories, keywords and entities based on patterns.
 */
class LocalClassificationService : MemoryClassificationService {

    private val categoryKeywords = mapOf(
        "Business" to setOf("business", "startup", "revenue", "market", "client", "customer", "strategy", "growth", "funding"),
        "Personal" to setOf("personal", "life", "thought", "feeling", "mood", "dream", "habit", "routine"),
        "Health" to setOf("health", "workout", "exercise", "gym", "diet", "sleep", "doctor", "meditation", "running"),
        "Family" to setOf("family", "mom", "dad", "sister", "brother", "wife", "husband", "son", "daughter", "kids"),
        "Travel" to setOf("travel", "trip", "flight", "hotel", "vacation", "journey", "destination", "airport"),
        "Learning" to setOf("learning", "study", "book", "course", "skill", "lesson", "knowledge", "research"),
        "Finance" to setOf("finance", "money", "budget", "investment", "stock", "bank", "expense", "savings"),
        "Ideas" to setOf("idea", "invention", "concept", "creative", "inspiration", "brainstorm", "project"),
        "Work" to setOf("work", "office", "meeting", "task", "deadline", "project", "boss", "colleague", "email"),
        "Shopping" to setOf("shopping", "buy", "store", "price", "order", "groceries", "online", "purchase"),
        "Meetings" to setOf("meeting", "call", "discussion", "agenda", "notes", "feedback", "presentation")
    )

    private val stopWords = setOf("the", "a", "an", "and", "or", "but", "i", "was", "were", "is", "are", "to", "of", "in", "it", "with", "that", "this", "for", "on", "at", "by", "from", "up", "about", "into", "over", "after")

    override fun classify(text: String, summary: String): Flow<MemoryClassification> = flow {
        delay(1000) // Simulate processing time

        val combinedText = (text + " " + summary).lowercase(Locale.getDefault())
        val words = combinedText.split(Regex("\\W+")).filter { it.isNotBlank() }

        // 1. Categories
        val detectedCategories = categoryKeywords.filter { (_, keywords) ->
            keywords.any { it in words }
        }.keys.toList()

        // 2. Keywords (High frequency, non-stop words)
        val wordFrequencies = mutableMapOf<String, Int>()
        words.filter { it.length > 3 && it !in stopWords }.forEach { word ->
            wordFrequencies[word] = (wordFrequencies[word] ?: 0) + 1
        }
        val topKeywords = wordFrequencies.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }

        // 3. Entity Extraction (Heuristic: Capitalized words in original text)
        val entities = mutableMapOf<String, MutableList<String>>()
        val originalWords = text.split(Regex("\\s+"))
        
        // Simple heuristic for People/Places: Capitalized words not at start of sentence
        // This is very crude but fits "deterministic heuristic" for this sprint
        val potentialEntities = mutableListOf<String>()
        for (i in 1 until originalWords.size) {
            val word = originalWords[i].filter { it.isLetter() }
            if (word.isNotEmpty() && word[0].isUpperCase() && word.lowercase() !in stopWords) {
                potentialEntities.add(word)
            }
        }
        
        if (potentialEntities.isNotEmpty()) {
            entities["General"] = potentialEntities.distinct().toMutableList()
        }

        emit(MemoryClassification(
            entryId = "", // To be filled by caller or worker
            categories = detectedCategories,
            keywords = topKeywords,
            entities = entities,
            confidence = 0.8f
        ))
    }
}
