package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class FakeConversationService : ConversationService {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun ask(
        question: String,
        conversationId: String?,
        contextOverride: ConversationContext?
    ): Flow<Message> = flow {
        delay(1500) // Simulate AI thinking
        
        val id = UUID.randomUUID().toString()
        val response = "Based on your memories, you've been focused on Oceanis branding recently. You mentioned it 3 times last week."
        
        emit(Message(
            id = id,
            conversationId = conversationId ?: "new_conv",
            content = response,
            role = MessageRole.ASSISTANT,
            createdAt = LocalDateTime.now().format(formatter),
            citations = listOf(
                Citation("mem_1", "Oceanis Branding", LocalDateTime.now().minusDays(2).format(formatter), "Working on the new logo..."),
                Citation("mem_2", "Website Strategy", LocalDateTime.now().minusDays(1).format(formatter), "Planning the layout...")
            ),
            reasoning = "I found 2 related memories using semantic search."
        ))
    }

    override fun getSuggestedQuestions(): Flow<List<String>> = flow {
        emit(listOf(
            "What have I been thinking about lately?",
            "What projects am I working on?",
            "Summarize my recent travel thoughts.",
            "What ideas have I repeated?"
        ))
    }
}
