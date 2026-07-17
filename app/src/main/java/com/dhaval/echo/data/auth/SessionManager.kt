package com.dhaval.echo.data.auth

import com.dhaval.echo.data.db.*
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages user sessions and handles data migration/isolation.
 */
@Singleton
class SessionManager @Inject constructor(
    private val authRepository: AuthRepository,
    private val diaryEntryDao: DiaryEntryDao,
    private val tagDao: TagDao,
    private val collectionDao: CollectionDao,
    private val intelligenceDao: IntelligenceDao,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val userRepository: com.dhaval.echo.domain.user.UserRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        scope.launch {
            authRepository.authState.collectLatest { state ->
                if (state is AuthState.Authenticated) {
                    handleUserSignIn(state.user.id)
                }
            }
        }
    }

    private suspend fun handleUserSignIn(userId: String) {
        // 1. Check for legacy data and migrate if needed
        migrateLegacyDataIfNeeded(userId)
        
        // 2. Sync/Initialize user profile in local DB
        val currentUser = authRepository.getCurrentUser()
        if (currentUser != null) {
            val profile = UserProfile(
                id = currentUser.id,
                displayName = currentUser.displayName,
                email = currentUser.email,
                phoneNumber = currentUser.phoneNumber,
                profilePictureUri = currentUser.photoUrl,
                authProvidersJson = "[]", // Simplified
                createdAt = currentUser.createdAt
            )
            userDao.insertProfile(profile)
            userRepository.syncProfile(userId)
        }
    }

    private suspend fun migrateLegacyDataIfNeeded(userId: String) {
        // We only migrate if this is the FIRST time a user is signing in 
        // AND there is legacy data. 
        // For simplicity, we'll check if any 'legacy_user' entries exist.
        
        diaryEntryDao.migrateLegacyEntries(userId)
        tagDao.migrateLegacyTags(userId)
        tagDao.migrateLegacyCrossRefs(userId)
        collectionDao.migrateLegacyCollections(userId)
        collectionDao.migrateLegacyCrossRefs(userId)
        intelligenceDao.migrateLegacySegments(userId)
        intelligenceDao.migrateLegacyClassifications(userId)
        intelligenceDao.migrateLegacyConnections(userId)
        intelligenceDao.migrateLegacyInsights(userId)
        conversationDao.migrateLegacyConversations(userId)
        conversationDao.migrateLegacyMessages(userId)
    }
}
