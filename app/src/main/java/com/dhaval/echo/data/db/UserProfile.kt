package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing the user's profile information.
 */
@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey val id: String,
    val displayName: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    val profilePictureUri: String? = null,
    val authProvidersJson: String = "[]", // JSON list of AuthProvider
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
