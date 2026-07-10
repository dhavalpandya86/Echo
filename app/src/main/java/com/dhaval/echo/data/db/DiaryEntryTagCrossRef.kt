package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "diary_entry_tag_cross_ref",
    primaryKeys = ["entryId", "tagName"],
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["name"],
            childColumns = ["tagName"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("entryId"),
        Index("tagName")
    ]
)
data class DiaryEntryTagCrossRef(
    val entryId: String,
    val tagName: String
)
