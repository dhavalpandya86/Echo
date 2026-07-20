package com.dhaval.echo.data.ai

import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.db.ItemKind
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.auth.AuthRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import javax.inject.Inject

/** A period a reflection can cover. */
enum class ReviewPeriod(val label: String, val days: Long) {
    WEEK("This week", 7),
    MONTH("This month", 30)
}

data class ReviewSection(val title: String, val items: List<String>)

/** A generated reflection: a warm lead line + observation sections. */
data class Reflection(
    val heading: String,
    val lead: String,
    val sections: List<ReviewSection>
)

/**
 * Builds a reflection over the user's own memories and graph — on-device, no LLM.
 * It observes rather than judges (Constitution): how much you captured, who and
 * what recurred, how you felt, what you still owe yourself. Companion voice; never
 * "Analysis complete."
 */
class ReflectionReviewService @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val understandingDao: UnderstandingDao,
    private val authRepository: AuthRepository
) {
    suspend fun generate(period: ReviewPeriod): Reflection {
        val userId = authRepository.getCurrentUser()?.id
            ?: return Reflection(period.label, "Nothing to reflect on yet.", emptyList())
        val since = LocalDateTime.now().minusDays(period.days)

        val recentMemories = diaryEntryDao.getAllEntries(userId).first()
            .filter { it.createdAt.isAfter(since) }
        val entities = understandingDao.getActiveEntities(userId)
            .filter { it.lastSeenAt.isAfter(since) }
        val people = entities.filter { it.type == EntityType.PERSON }
            .sortedByDescending { it.memoryCount }.take(5).map { it.name }
        val topics = entities.filter {
            it.type == EntityType.PROJECT || it.type == EntityType.TOPIC
        }.sortedByDescending { it.memoryCount }.take(5).map { it.name }
        val places = entities.filter { it.type == EntityType.PLACE }
            .sortedByDescending { it.memoryCount }.take(5).map { it.name }
        val feelings = entities.filter { it.type == EntityType.FEELING }
            .sortedByDescending { it.memoryCount }.take(5).map { it.name }
        val openCommitments = understandingDao.getOpenActionables(userId).first()
            .filter { it.kind == ItemKind.TASK || it.kind == ItemKind.REMINDER }

        if (recentMemories.isEmpty()) {
            return Reflection(
                heading = period.label,
                lead = "You didn't capture anything ${period.label.lowercase()}. That's okay — the space is yours.",
                sections = emptyList()
            )
        }

        val lead = buildString {
            append("You captured ")
            append(if (recentMemories.size == 1) "one memory" else "${recentMemories.size} memories")
            append(" ${period.label.lowercase()}")
            when {
                topics.isNotEmpty() -> append(". You've been thinking about ${topics.first()}.")
                people.isNotEmpty() -> append(". ${people.first()} has been on your mind.")
                else -> append(".")
            }
        }

        val sections = buildList {
            if (feelings.isNotEmpty()) add(ReviewSection("How you felt", feelings))
            if (people.isNotEmpty()) add(ReviewSection("People", people))
            if (topics.isNotEmpty()) add(ReviewSection("What you focused on", topics))
            if (places.isNotEmpty()) add(ReviewSection("Places", places))
            if (openCommitments.isNotEmpty()) {
                add(ReviewSection(
                    "Still waiting for you",
                    openCommitments.take(5).map { it.value }
                ))
            }
        }
        return Reflection(period.label, lead, sections)
    }
}
