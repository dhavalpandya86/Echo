package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.TagSuggestionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * On-device tag suggestion — the fallback when no AI provider key is set. It
 * reads real tags out of the memory's own words (topical categories it matches +
 * its most salient keywords) instead of the old fixed "Personal / Reflection /
 * Voice" placeholder. Silence over fabrication: a memory with nothing to say
 * (a test recording, "hello hello") gets no tags rather than fake ones.
 */
class LocalTagSuggestionService : TagSuggestionService {

    // Topic → cue words. A memory earns a category tag if it uses any cue.
    private val categoryCues = mapOf(
        "Business" to setOf("business", "startup", "revenue", "market", "client", "customer", "strategy", "funding"),
        "Health" to setOf("health", "workout", "exercise", "gym", "diet", "sleep", "doctor", "meditation", "running"),
        "Family" to setOf("family", "mom", "dad", "sister", "brother", "wife", "husband", "son", "daughter", "kids", "kid"),
        "Travel" to setOf("travel", "trip", "flight", "hotel", "vacation", "journey", "destination", "airport", "goa"),
        "Learning" to setOf("learning", "study", "book", "course", "skill", "lesson", "research"),
        "Finance" to setOf("finance", "money", "budget", "investment", "stock", "bank", "expense", "savings"),
        "Ideas" to setOf("idea", "invention", "concept", "creative", "inspiration", "brainstorm"),
        "Work" to setOf("work", "office", "meeting", "task", "deadline", "project", "boss", "colleague", "email"),
        "Shopping" to setOf("shopping", "buy", "store", "price", "order", "groceries", "purchase"),
        "Food" to setOf("food", "lunch", "dinner", "breakfast", "cook", "recipe", "restaurant", "coffee")
    )

    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "was", "were", "is", "are", "to", "of",
        "in", "it", "with", "that", "this", "for", "on", "at", "by", "from", "up",
        "about", "into", "over", "after", "have", "has", "had", "will", "just",
        "what", "when", "where", "your", "you", "they", "them", "their", "then",
        "want", "going", "gonna", "really", "there", "here", "some", "like", "hello",
        "recording", "today", "tomorrow", "yesterday", "thing", "things", "much"
    )

    override fun suggestTags(text: String): Flow<List<String>> = flow {
        val lower = text.lowercase(Locale.getDefault())
        val words = lower.split(Regex("\\W+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        // 1. Topical categories the text actually touches.
        val categories = categoryCues
            .filter { (_, cues) -> cues.any { it in words } }
            .keys.toList()

        // 2. The memory's most salient keywords (frequent, meaty, non-stop words).
        val frequencies = LinkedHashMap<String, Int>()
        for (w in words) {
            if (w.length > 3 && w !in stopWords && w.any { it.isLetter() }) {
                frequencies[w] = (frequencies[w] ?: 0) + 1
            }
        }
        val keywords = frequencies.entries
            .sortedByDescending { it.value }
            .map { it.key.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
            .filter { it !in categories }

        // Categories first (they're the strongest signal), then top keywords,
        // capped so the memory isn't drowned in tags.
        val tags = (categories + keywords).distinct().take(5)
        emit(tags)
    }
}
