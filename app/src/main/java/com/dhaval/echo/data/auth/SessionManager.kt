package com.dhaval.echo.data.auth

import com.dhaval.echo.data.backup.SqlHandle
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
    private val userRepository: com.dhaval.echo.domain.user.UserRepository,
    private val database: EchoDatabase,
    private val databaseRewriter: com.dhaval.echo.data.backup.DatabaseRewriter,
    private val backupLocalState: com.dhaval.echo.data.backup.BackupLocalState
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
        // 0. A backup restored before anyone signed in is still keyed to the
        //    account that made it. Claim it now, or the user lands in an empty
        //    app holding a perfectly restored diary belonging to nobody.
        applyPendingRestoreRekey(userId)

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

    /**
     * Completes a restore that happened on the Welcome screen.
     *
     * Restoring before sign-in is the normal path after a reinstall — the user
     * has the file and no session yet — so the account swap has to be deferred
     * to whenever they do sign in. [BackupLocalState.pendingRekeyFromUserId] is
     * the note the restore left behind; consuming it is what makes the restored
     * rows visible.
     */
    private suspend fun applyPendingRestoreRekey(userId: String) {
        val pendingFrom = backupLocalState.pendingRekeyFromUserId ?: return
        if (pendingFrom != userId) {
            runCatching {
                databaseRewriter.rekeyUserId(
                    SqlHandle.of(database.openHelper.writableDatabase),
                    userId
                )
            }.onFailure { android.util.Log.e("SessionManager", "Restore re-key failed", it) }
        }
        backupLocalState.pendingRekeyFromUserId = null
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
