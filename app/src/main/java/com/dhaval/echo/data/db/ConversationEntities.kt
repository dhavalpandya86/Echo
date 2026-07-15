package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.LocalDateTime

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val userId: String = "legacy_user",
    val title: String?,
    val createdAt: LocalDateTime,
    val lastUpdatedAt: LocalDateTime
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val userId: String = "legacy_user",
    val conversationId: String,
    val content: String,
    val role: String, // USER, ASSISTANT, SYSTEM
    val createdAt: LocalDateTime,
    val citationsJson: String, // JSON list of Citations
    val reasoning: String?
)
