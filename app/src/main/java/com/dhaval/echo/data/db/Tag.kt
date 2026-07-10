package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tags")
data class Tag(
    @PrimaryKey val name: String // Using name as PK for simplicity and uniqueness
)
