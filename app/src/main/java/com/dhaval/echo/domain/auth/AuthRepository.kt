package com.dhaval.echo.domain.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val authState: StateFlow<AuthState>
    val currentUserId: Flow<String?>

    suspend fun createEmailAccount(name: String, email: String, password: String): Result<User>
    suspend fun loginWithEmail(email: String, password: String): Result<User>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    
    suspend fun startPhoneVerification(phoneNumber: String): Result<String>
    suspend fun verifyOtp(verificationId: String, otp: String): Result<User>
    
    suspend fun loginWithGoogle(idToken: String): Result<User>
    suspend fun loginWithFacebook(accessToken: String): Result<User>
    
    suspend fun logout(): Result<Unit>
    suspend fun getCurrentUser(): User?
}
