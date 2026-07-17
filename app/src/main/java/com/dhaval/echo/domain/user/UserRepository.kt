package com.dhaval.echo.domain.user

import com.dhaval.echo.data.db.UserProfile
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getUserProfile(): Flow<UserProfile?>
    suspend fun updateDisplayName(name: String)
    suspend fun syncProfile(userId: String)
}
