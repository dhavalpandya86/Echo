package com.dhaval.echo.data.intelligence

import com.dhaval.echo.domain.intelligence.ContentAnalysis
import com.dhaval.echo.domain.intelligence.IntelligenceService
import kotlinx.coroutines.delay
import javax.inject.Inject

class MockIntelligenceService @Inject constructor() : IntelligenceService {
    override suspend fun analyzeContent(transcript: String): ContentAnalysis {
        delay(2000) // Simulate analysis
        return ContentAnalysis(
            title = "Project Echo Reflection",
            tags = listOf("Idea", "Personal", "Design"),
            summary = "Reflecting on the importance of emotion in design for Project Echo."
        )
    }
}
