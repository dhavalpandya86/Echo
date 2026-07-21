package com.dhaval.echo.data.auth

import com.dhaval.echo.domain.auth.*
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) : AuthRepository {

    // Real Firebase auth state — reflects who is actually signed in. Unauthenticated
    // drives the app to the Welcome/Login flow (EchoApp gates on this).
    private val _authState = MutableStateFlow(currentState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override val currentUserId: Flow<String?> =
        _authState.map { (it as? AuthState.Authenticated)?.user?.id }

    private val authListener = FirebaseAuth.AuthStateListener { _authState.value = currentState() }

    init {
        firebaseAuth.addAuthStateListener(authListener)
    }

    private fun currentState(): AuthState =
        firebaseAuth.currentUser?.let { AuthState.Authenticated(it.toDomainUser()) }
            ?: AuthState.Unauthenticated

    override suspend fun createEmailAccount(name: String, email: String, password: String): Result<User> {
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            val firebaseUser = result.user ?: throw Exception("Failed to create account")
            
            // Update display name
            val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                .setDisplayName(name)
                .build()
            firebaseUser.updateProfile(profileUpdates).await()
            
            Result.success(firebaseUser.toDomainUser())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loginWithEmail(email: String, password: String): Result<User> {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = result.user ?: throw Exception("Login failed")
            Result.success(firebaseUser.toDomainUser())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            firebaseAuth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun startPhoneVerification(phoneNumber: String): Result<String> {
        // This is complex to do in a suspend function because of callbacks.
        // Usually, we'd return a Flow or use a deferred.
        return Result.failure(Exception("Not implemented yet - requires Activity context for PhoneAuth"))
    }

    override suspend fun verifyOtp(verificationId: String, otp: String): Result<User> {
        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, otp)
            val result = firebaseAuth.signInWithCredential(credential).await()
            val firebaseUser = result.user ?: throw Exception("OTP verification failed")
            Result.success(firebaseUser.toDomainUser())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loginWithGoogle(idToken: String): Result<User> {
        Log.d("FirebaseAuthRepository", "loginWithGoogle started with token: ${idToken.take(10)}...")
        return try {
            val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
            Log.d("FirebaseAuthRepository", "Credential created, signing in with Firebase...")
            val result = firebaseAuth.signInWithCredential(credential).await()
            val firebaseUser = result.user ?: throw Exception("Google login failed: user is null")
            Log.d("FirebaseAuthRepository", "Sign-in successful for user: ${firebaseUser.uid}")
            
            Result.success(firebaseUser.toDomainUser())
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
            Log.e("FirebaseAuthRepository", "Google login error: User disabled", e)
            Result.failure(Exception("This account has been disabled."))
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            Log.e("FirebaseAuthRepository", "Google login error: Invalid credentials (likely Client ID mismatch or SHA-1 issue)", e)
            Result.failure(Exception("Authentication failed. This is usually due to a configuration mismatch between the app and Firebase console."))
        } catch (e: Exception) {
            Log.e("FirebaseAuthRepository", "Google login error", e)
            Result.failure(e)
        }
    }

    override suspend fun logout(): Result<Unit> {
        return try {
            firebaseAuth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCurrentUser(): User? = firebaseAuth.currentUser?.toDomainUser()

    private fun FirebaseUser.toDomainUser(): User {
        return User(
            id = uid,
            displayName = displayName,
            email = email,
            phoneNumber = phoneNumber,
            photoUrl = photoUrl?.toString(),
            authProviders = providerData.mapNotNull { 
                when (it.providerId) {
                    "password" -> AuthProvider.EMAIL
                    "phone" -> AuthProvider.PHONE
                    "google.com" -> AuthProvider.GOOGLE
                    else -> null
                }
            }.distinct(),
            createdAt = metadata?.creationTimestamp ?: System.currentTimeMillis()
        )
    }
}
