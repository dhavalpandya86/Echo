package com.dhaval.echo.data.intelligence

import com.dhaval.echo.domain.intelligence.TranscriptionResult
import com.dhaval.echo.domain.intelligence.TranscriptionSegment
import com.dhaval.echo.domain.intelligence.TranscriptionService
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject

class MockTranscriptionService @Inject constructor() : TranscriptionService {
    override suspend fun transcribe(audioFile: File): TranscriptionResult {
        delay(3000) // Simulate processing
        return TranscriptionResult(
            text = "Today I was thinking about the new project. We should focus on the premium design and emotion. Also, we need to buy some groceries like milk and coffee.",
            segments = listOf(
                TranscriptionSegment(0, 5000, "Today I was thinking about the new project.", "en"),
                TranscriptionSegment(5000, 10000, "We should focus on the premium design and emotion.", "en"),
                TranscriptionSegment(10000, 15000, "Also, we need to buy some groceries like milk and coffee.", "en")
            )
        )
    }
}
