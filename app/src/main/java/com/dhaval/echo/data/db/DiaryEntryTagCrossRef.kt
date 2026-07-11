package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "diary_entry_tag_cross_ref",
    primaryKeys = ["entryId", "tagName", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["name", "userId"],
            childColumns = ["tagName", "userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("entryId"),
        Index("tagName", "userId")
    ]
)
data class DiaryEntryTagCrossRef(
    val entryId: String,
    val tagName: String,
    val userId: String = "legacy_user"
)
