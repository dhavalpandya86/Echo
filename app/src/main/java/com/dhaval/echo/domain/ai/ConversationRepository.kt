package com.dhaval.echo.domain.ai

import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    fun getConversationById(id: String): Flow<Conversation?>
    fun getMessages(conversationId: String): Flow<List<Message>>
    suspend fun createConversation(title: String?): String
    suspend fun addMessage(message: Message)
    suspend fun deleteConversation(id: String)
}
