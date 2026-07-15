package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.*
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

class RealConversationService @Inject constructor(
    private val memoryContextBuilder: MemoryContextBuilder,
    private val conversationRepository: ConversationRepository
) : ConversationService {

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun ask(
        question: String,
        conversationId: String?,
        contextOverride: ConversationContext?
    ): Flow<Message> = flow {
        val targetConversationId = conversationId ?: conversationRepository.createConversation(question.take(30) + "...")
        
        // 1. Add user message
        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = targetConversationId,
            content = question,
            role = MessageRole.USER,
            createdAt = LocalDateTime.now().format(formatter)
        )
        conversationRepository.addMessage(userMessage)

        // 2. Build context
        val context = memoryContextBuilder.buildContext(question).first()
        val citations = memoryContextBuilder.buildCitations(question).first()
        
        // 3. TODO: Generate response using AI provider
        // For now, we use a template-based response as per requirements
        val responseContent = if (context.contains("Memory:")) {
            "Based on your memories about \"${question}\", I found some relevant information. $context"
        } else {
            "I couldn't find any specific memories related to \"${question}\"."
        }
        
        val assistantMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = targetConversationId,
            content = responseContent,
            role = MessageRole.ASSISTANT,
            createdAt = LocalDateTime.now().format(formatter),
            citations = citations,
            reasoning = "Retrieved ${citations.size} related memories."
        )
        
        // 4. Add assistant message
        conversationRepository.addMessage(assistantMessage)
        
        emit(assistantMessage)
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
