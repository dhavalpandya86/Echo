package com.dhaval.echo.data.timeline

import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.domain.timeline.TimelineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Mock implementation of TimelineRepository for UI development.
 * Populates the timeline with varied dates to test grouping logic.
 */
class MockTimelineRepository @Inject constructor() : TimelineRepository {
    override fun getTimelineEntries(query: String): Flow<List<TimelineEntry>> {
        val now = LocalDateTime.now()
        val entries = listOf(
            // Today
            TimelineEntry("1", "Morning Reflection", "", 120000, now.minusHours(2), isFavorite = true),
            TimelineEntry("2", "Project Kickoff", "", 350000, now.minusHours(5)),
            
            // Yesterday
            TimelineEntry("3", "Grocery List", "", 45000, now.minusDays(1).withHour(10)),
            TimelineEntry("4", "Late Night Ideas", "", 600000, now.minusDays(1).withHour(23), isFavorite = true),
            
            // This Week
            TimelineEntry("5", "Client Call", "", 1800000, now.minusDays(3)),
            
            // Last Week
            TimelineEntry("6", "Gym Session Notes", "", 30000, now.minusDays(10)),
            
            // Earlier
            TimelineEntry("7", "New Year Resolutions", "", 90000, now.minusMonths(1)),
            TimelineEntry("8", "Old Memory", "", 150000, now.minusYears(1))
        )
        
        return flowOf(entries).map { list ->
            if (query.isBlank()) list 
            else list.filter { it.title.contains(query, ignoreCase = true) }
        }
    }

    override suspend fun toggleFavorite(entryId: String) {
        // Mock implementation, nothing to do here
    }
}
