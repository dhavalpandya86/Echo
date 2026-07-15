package com.dhaval.echo.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE userId = :userId ORDER BY lastUpdatedAt DESC")
    fun getAllConversations(userId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id AND userId = :userId")
    fun getConversationById(id: String, userId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id AND userId = :userId")
    suspend fun getConversationByIdOnce(id: String, userId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id AND userId = :userId")
    suspend fun deleteConversation(id: String, userId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND userId = :userId ORDER BY createdAt ASC")
    fun getMessagesForConversation(conversationId: String, userId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE conversations SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyConversations(userId: String)

    @Query("UPDATE messages SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyMessages(userId: String)
}
