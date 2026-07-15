package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dhaval.echo.domain.ai.InsightType
import java.time.LocalDateTime

@Entity(tableName = "timeline_insights")
data class TimelineInsightEntity(
    @PrimaryKey val id: String,
    val userId: String = "legacy_user",
    val title: String,
    val description: String,
    val type: InsightType,
    val confidence: Float,
    val relatedMemoryIds: List<String>,
    val createdAt: LocalDateTime,
    val priority: Int
)
