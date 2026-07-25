package com.dhaval.echo.ui.auth

import android.content.Context
import android.util.Log
import com.dhaval.echo.R
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleAuthHelper(private val context: Context) {
    private val credentialManager = CredentialManager.create(context)

    // The web client ID (OAuth client_type 3) for whatever Firebase project this
    // build targets. Read from the google-services-generated resource so it always
    // matches google-services.json — hardcoding it broke Google Sign-In the moment
    // the app switched Firebase projects.
    private val webClientId: String
        get() = context.getString(R.string.default_web_client_id)

    suspend fun signIn(activityContext: Context): Result<String> = withContext(Dispatchers.IO) {
        Log.d("GoogleAuthHelper", "signIn started with context: ${activityContext::class.java.simpleName}")
        try {
            // GetSignInWithGoogleOption is the explicit "Sign in with Google" button
            // flow: it always presents the account picker with every Google account
            // on the device. GetGoogleIdOption (the one-tap/bottom-sheet flow) throws
            // NoCredentialException here when nothing is pre-authorized — which is why
            // first-time sign-in showed "No Google accounts found".
            val signInWithGoogleOption: GetSignInWithGoogleOption =
                GetSignInWithGoogleOption.Builder(webClientId).build()

            val request: GetCredentialRequest = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            Log.d("GoogleAuthHelper", "Calling getCredential...")
            val result = credentialManager.getCredential(
                request = request,
                context = activityContext
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
            Result.failure(Exception("No Google accounts found on this device. Please add a Google account in settings."))
        } catch (e: GetCredentialException) {
            Log.e("GoogleAuthHelper", "Credential Manager error: [${e.type}] ${e.message}", e)
            val friendlyMessage = when {
                e.type.contains("androidx.credentials.TYPE_GET_CREDENTIAL_CANCELED_EXCEPTION") -> "Sign-in cancelled"
                e.type.contains("androidx.credentials.TYPE_GET_CREDENTIAL_INTERRUPTED_EXCEPTION") -> "Sign-in interrupted"
                else -> "Google Sign-In error: ${e.message ?: "Unknown error"}"
            }
            Result.failure(Exception(friendlyMessage))
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
