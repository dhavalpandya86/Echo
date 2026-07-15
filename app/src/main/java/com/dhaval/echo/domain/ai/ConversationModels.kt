package com.dhaval.echo.domain.ai

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String,
    val title: String?,
    val createdAt: String, // Simplified for serialization
    val lastUpdatedAt: String
)

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val content: String,
    val role: MessageRole,
    val createdAt: String,
    val citations: List<Citation> = emptyList(),
    val reasoning: String? = null
)

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

@Serializable
data class Citation(
    val memoryId: String,
    val title: String,
    val date: String,
    val snippet: String? = null
)

data class ConversationContext(
    val relevantMemories: List<String>, // Entry IDs
    val relevantInsights: List<String>, // Insight IDs
    val userProfile: String? = null
)
