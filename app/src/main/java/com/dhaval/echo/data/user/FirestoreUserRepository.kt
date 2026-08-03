package com.dhaval.echo.data.user

import com.dhaval.echo.data.db.UserDao
import com.dhaval.echo.data.db.UserProfile
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.user.UserRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreUserRepository @Inject constructor(
    private val userDao: UserDao,
    private val authRepository: AuthRepository,
    private val firestore: FirebaseFirestore
) : UserRepository {

    override fun getUserProfile(): Flow<UserProfile?> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) flowOf(null) else userDao.getUserProfileFlow(userId)
    }

    override suspend fun updateDisplayName(name: String) {
        val user = authRepository.getCurrentUser() ?: return
        val userId = user.id
        
        // 1. Update local DB
        val currentProfile = userDao.getUserProfile(userId)
        val updatedProfile = if (currentProfile != null) {
            currentProfile.copy(displayName = name)
        } else {
            UserProfile(id = userId, displayName = name, email = user.email)
        }
        userDao.insertProfile(updatedProfile)

        // 2. Sync to Firestore
        syncProfileToFirestore(updatedProfile)
    }

    override suspend fun syncProfile(userId: String) {
        val profile = userDao.getUserProfile(userId) ?: return
        syncProfileToFirestore(profile)
    }

    private suspend fun syncProfileToFirestore(profile: UserProfile) {
        try {
            val userMap = mapOf(
                "displayName" to profile.displayName,
                "email" to profile.email,
                "photoUrl" to profile.profilePictureUri,
                "updatedAt" to System.currentTimeMillis()
            )
            firestore.collection("users").document(profile.id)
                .set(userMap, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            // Log error but don't fail the local update
            android.util.Log.e("FirestoreUserRepository", "Failed to sync profile", e)
        }
    }
}
