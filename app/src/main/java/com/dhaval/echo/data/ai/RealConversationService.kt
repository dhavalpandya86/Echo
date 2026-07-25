package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.*
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

class RealConversationService @Inject constructor(
    private val memoryContextBuilder: MemoryContextBuilder,
    private val conversationRepository: ConversationRepository,
    private val aiManager: AIManager
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
        
        // 3. Generate the answer. With a cloud key, Echo talks back — a warm,
        //    grounded paragraph synthesised from the retrieved memories. Without
        //    one, we degrade to the on-device template so the free tier still
        //    answers (just less fluently), honouring the key-gated-with-fallback
        //    contract.
        val narrator = aiManager.getNarrativeService()
        val responseContent = if (narrator != null && context.isNotBlank()) {
            narrator.narrate(
                instruction = "The user asked: \"$question\". Answer them directly, in a warm " +
                    "and personal short paragraph, as if you remember their life. Ground every " +
                    "claim in the memories below and don't invent anything. If the memories don't " +
                    "really cover the question, say so gently.",
                memoriesBlock = context
            ) ?: localAnswer(question, context)
        } else {
            localAnswer(question, context)
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

    /** On-device fallback answer when no cloud key is set. */
    private fun localAnswer(question: String, context: String): String =
        if (context.contains("Memory:")) {
            "Based on your memories about \"$question\", I found some relevant information. $context"
        } else {
            "I couldn't find any specific memories related to \"$question\"."
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
