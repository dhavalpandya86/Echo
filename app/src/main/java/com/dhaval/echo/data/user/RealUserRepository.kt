package com.dhaval.echo.data.user

import com.dhaval.echo.data.db.UserDao
import com.dhaval.echo.data.db.UserProfile
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.user.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class RealUserRepository @Inject constructor(
    private val userDao: UserDao,
    private val authRepository: AuthRepository
) : UserRepository {
    override fun getUserProfile(): Flow<UserProfile?> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) flowOf(null) else userDao.getUserProfileFlow(userId)
    }

    override suspend fun updateDisplayName(name: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        val currentProfile = userDao.getUserProfile(userId)
        if (currentProfile != null) {
            userDao.updateProfile(currentProfile.copy(displayName = name))
        } else {
            userDao.insertProfile(UserProfile(id = userId, displayName = name))
        }
    }
}
