package com.dhaval.echo.data.ai

import com.dhaval.echo.data.db.ConversationDao
import com.dhaval.echo.data.db.ConversationEntity
import com.dhaval.echo.data.db.MessageEntity
import com.dhaval.echo.domain.ai.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository
) : ConversationRepository {

    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getConversations(): Flow<List<Conversation>> {
        return authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())
            conversationDao.getAllConversations(userId).map { entities ->
                entities.map { it.toDomain() }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getConversationById(id: String): Flow<Conversation?> {
        return authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(null)
            conversationDao.getConversationById(id, userId).map { it?.toDomain() }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())
            conversationDao.getMessagesForConversation(conversationId, userId).map { entities ->
                entities.map { it.toDomain() }
            }
        }
    }

    override suspend fun createConversation(title: String?): String {
        val userId = authRepository.getCurrentUser()?.id ?: return ""
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        conversationDao.insertConversation(
            ConversationEntity(
                id = id,
                userId = userId,
                title = title,
                createdAt = now,
                lastUpdatedAt = now
            )
        )
        return id
    }

    override suspend fun addMessage(message: Message) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        conversationDao.insertMessage(message.toEntity(userId))
        // Update lastUpdatedAt
        val entity = conversationDao.getConversationByIdOnce(message.conversationId, userId)
        if (entity != null) {
            conversationDao.updateConversation(entity.copy(lastUpdatedAt = LocalDateTime.now()))
        }
    }

    override suspend fun deleteConversation(id: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        conversationDao.deleteConversation(id, userId)
    }

    private fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        createdAt = createdAt.format(formatter),
        lastUpdatedAt = lastUpdatedAt.format(formatter)
    )

    private fun MessageEntity.toDomain() = Message(
        id = id,
        conversationId = conversationId,
        content = content,
        role = MessageRole.valueOf(role),
        createdAt = createdAt.format(formatter),
        citations = Json.decodeFromString(citationsJson),
        reasoning = reasoning
    )

    private fun Message.toEntity(userId: String) = MessageEntity(
        id = id,
        userId = userId,
        conversationId = conversationId,
        content = content,
        role = role.name,
        createdAt = LocalDateTime.parse(createdAt, formatter),
        citationsJson = Json.encodeToString(citations),
        reasoning = reasoning
    )
}
