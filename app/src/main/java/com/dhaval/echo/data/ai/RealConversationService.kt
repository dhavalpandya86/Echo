package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.*
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.reflection.ReflectionEngine
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

/**
 * Reflect, backed by the Reflection Engine.
 *
 * This used to build a RAG context block and, with no cloud key, hand that block
 * straight to the UI as the answer — so asking "what have I been thinking about"
 * returned "Here is the relevant context from the user's memories: Memory: …
 * Transcript: …". That is the prompt, not the reply.
 *
 * The engine now owns every stage, and this class is only the bridge between a
 * question and a stored message. Nothing here composes user-visible prose, which
 * is what makes it impossible for prompt scaffolding to leak into the transcript
 * again.
 */
class RealConversationService @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val reflectionEngine: ReflectionEngine,
    private val authRepository: AuthRepository
) : ConversationService {

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun ask(
        question: String,
        conversationId: String?,
        contextOverride: ConversationContext?
    ): Flow<Message> = flow {
        val targetConversationId = conversationId
            ?: conversationRepository.createConversation(question.take(30) + "...")

        conversationRepository.addMessage(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = targetConversationId,
                content = question,
                role = MessageRole.USER,
                createdAt = LocalDateTime.now().format(formatter)
            )
        )

        val userId = authRepository.getCurrentUser()?.id
        val reflection = if (userId == null) null else reflectionEngine.reflect(question, userId)

        // The planner note rides with the reflection rather than replacing it,
        // and only appears when the engine found something genuinely worth
        // raising — see RealReflectionEngine.attentionFrom.
        val body = when {
            reflection == null -> "I can't reach your memories right now."
            reflection.attention != null -> "${reflection.text}\n\n${reflection.attention}"
            else -> reflection.text
        }

        val assistantMessage = Message(
            id = UUID.randomUUID().toString(),
            conversationId = targetConversationId,
            content = body,
            role = MessageRole.ASSISTANT,
            createdAt = LocalDateTime.now().format(formatter),
            citations = reflection?.sources.orEmpty(),
            reasoning = reflection?.basis
        )

        conversationRepository.addMessage(assistantMessage)
        emit(assistantMessage)
    }

    override fun getSuggestedQuestions(): Flow<List<String>> = flow {
        // Phrased to match the intents the engine actually recognises, so a
        // tapped prompt lands on a real pipeline rather than the open-question
        // catch-all.
        emit(
            listOf(
                "What did I focus on this week?",
                "What have I been thinking about?",
                "What am I forgetting?",
                "How have I been feeling lately?"
            )
        )
    }
}
