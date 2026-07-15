package com.dhaval.echo.data.ai

import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.domain.ai.InsightType
import com.dhaval.echo.domain.ai.TimelineInsight
import com.dhaval.echo.domain.ai.TimelineIntelligenceService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import java.util.UUID

/**
 * A lightweight, deterministic timeline intelligence engine.
 * Analyzes patterns across all memories to surface insights.
 */
class LocalTimelineIntelligenceService(
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository
) : TimelineIntelligenceService {

    override fun analyzeTimeline(): Flow<List<TimelineInsight>> = flow {
        val userId = authRepository.getCurrentUser()?.id ?: return@flow
        val entries = diaryEntryDao.getAllEntries(userId).first()
        val classifications = intelligenceDao.getAllClassifications(userId)
        val insights = mutableListOf<TimelineInsight>()

        if (entries.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        // 1. Detect Active Projects (recurring General entities/keywords)
        val projectCounts = classifications.flatMap { it.entities["General"] ?: emptyList() }
            .groupBy { it }
            .mapValues { it.value.size }
            .filter { it.value >= 3 }

        projectCounts.forEach { (project, count) ->
            val relatedIds = classifications.filter { project in (it.entities["General"] ?: emptyList()) }
                .map { it.entryId }
            
            insights.add(TimelineInsight(
                id = UUID.randomUUID().toString(),
                title = "Active Project: $project",
                description = "You've been focused on $project for the last few recordings. You have $count connected memories.",
                type = InsightType.ACTIVE_PROJECT,
                confidence = 0.9f,
                relatedMemoryIds = relatedIds,
                createdAt = LocalDateTime.now(),
                priority = 10
            ))
        }

        // 2. Detect Frequent Topics (recurring Categories)
        val categoryCounts = classifications.flatMap { it.categories }
            .groupBy { it }
            .mapValues { it.value.size }
            .filter { it.value >= 5 }

        categoryCounts.forEach { (category, count) ->
            insights.add(TimelineInsight(
                id = UUID.randomUUID().toString(),
                title = "Top Topic: $category",
                description = "Most of your recent memories are related to $category ($count recordings).",
                type = InsightType.FREQUENT_TOPIC,
                confidence = 0.8f,
                relatedMemoryIds = classifications.filter { category in it.categories }.map { it.entryId },
                createdAt = LocalDateTime.now(),
                priority = 8
            ))
        }

        // 3. Detect Dormant Projects
        val threeWeeksAgo = LocalDateTime.now().minusWeeks(3)
        classifications.flatMap { it.entities["General"] ?: emptyList() }
            .distinct()
            .forEach { project ->
                val projectEntries = entries.filter { entry ->
                    classifications.find { it.entryId == entry.id }?.entities?.get("General")?.contains(project) == true
                }.sortedByDescending { it.createdAt }

                if (projectEntries.isNotEmpty() && projectEntries.first().createdAt.isBefore(threeWeeksAgo)) {
                    insights.add(TimelineInsight(
                        id = UUID.randomUUID().toString(),
                        title = "Dormant Project: $project",
                        description = "You haven't talked about $project for over 3 weeks. Would you like to review your progress?",
                        type = InsightType.DORMANT_PROJECT,
                        confidence = 0.7f,
                        relatedMemoryIds = projectEntries.map { it.id },
                        createdAt = LocalDateTime.now(),
                        priority = 5
                    ))
                }
            }

        emit(insights.sortedByDescending { it.priority })
    }

    override fun getProjectEvolution(projectName: String): Flow<List<String>> = flow {
        val userId = authRepository.getCurrentUser()?.id ?: return@flow
        val classifications = intelligenceDao.getAllClassifications(userId)
        val entryIds = classifications.filter { 
            it.entities["General"]?.contains(projectName) == true 
        }.map { it.entryId }
        emit(entryIds)
    }

    override fun getTopicTrends(): Flow<Map<String, List<Int>>> = flow {
        // Implementation for trends visualization (placeholder)
        emit(emptyMap())
    }
}
