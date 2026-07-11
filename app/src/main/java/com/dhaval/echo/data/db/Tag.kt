package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tags", primaryKeys = ["name", "userId"])
data class Tag(
    val name: String,
    val userId: String = "legacy_user"
)
