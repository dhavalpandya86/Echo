package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "diary_entry_collection_cross_ref",
    primaryKeys = ["entryId", "collectionId"],
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EchoCollection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("entryId"),
        Index("collectionId")
    ]
)
data class DiaryEntryCollectionCrossRef(
    val entryId: String,
    val collectionId: String
)
