package com.dhaval.echo.ui.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleAuthHelper(private val context: Context) {
    private val credentialManager = CredentialManager.create(context)

    // Actual Web Client ID from Firebase/Google Cloud Console
    // Note: Ensure this matches the Web Client ID in your Google Cloud Console for the same project.
    private val WEB_CLIENT_ID = "994516006717-m1482uhdkfluvqfmrkn8r4etem0u2jvf.apps.googleusercontent.com"

    suspend fun signIn(): Result<String> = withContext(Dispatchers.IO) {
        Log.d("GoogleAuthHelper", "signIn started")
        try {
            val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()
            Log.d("GoogleAuthHelper", "GetGoogleIdOption created")

            val request: GetCredentialRequest = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            Log.d("GoogleAuthHelper", "GetCredentialRequest created, calling getCredential...")

            val result = credentialManager.getCredential(
                request = request,
                context = context
            )
            Log.d("GoogleAuthHelper", "getCredential returned successfully")
            
            val token = handleSignIn(result)
            if (token != null) {
                Result.success(token)
            } else {
                Result.failure(Exception("Failed to extract ID token from credential"))
            }
        } catch (e: NoCredentialException) {
            Log.e("GoogleAuthHelper", "No accounts found", e)
            Result.failure(Exception("No Google accounts found on this device"))
        } catch (e: GetCredentialException) {
            Log.e("GoogleAuthHelper", "Credential Manager error: ${e.type}", e)
            Result.failure(Exception("Google Sign-In error: ${e.message}"))
        } catch (e: Exception) {
            Log.e("GoogleAuthHelper", "Unexpected error during signIn", e)
            Result.failure(e)
        }
    }

    private fun handleSignIn(result: GetCredentialResponse): String? {
        val credential = result.credential
        Log.d("GoogleAuthHelper", "handleSignIn: credential type = ${credential::class.java.simpleName}")
        
        // Handle GoogleIdTokenCredential
        if (credential is GoogleIdTokenCredential) {
            Log.d("GoogleAuthHelper", "GoogleIdTokenCredential found, token length: ${credential.idToken.length}")
            return credential.idToken
        }
        
        // Fallback for CustomCredential if it's a Google ID Token
        if (credential is androidx.credentials.CustomCredential && 
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                return googleIdTokenCredential.idToken
            } catch (e: Exception) {
                Log.e("GoogleAuthHelper", "Error parsing CustomCredential as GoogleIdTokenCredential", e)
            }
        }

        Log.w("GoogleAuthHelper", "Unexpected credential type: ${credential.type}")
        return null
    }
}
