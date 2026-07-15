package com.dhaval.echo.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class FacebookAuthHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val callbackManager = CallbackManager.Factory.create()

    suspend fun signIn(activity: Activity): String? = suspendCancellableCoroutine { continuation ->
        LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                continuation.resume(result.accessToken.token)
            }

            override fun onCancel() {
                continuation.resume(null)
            }

            override fun onError(error: FacebookException) {
                continuation.resume(null)
            }
        })

        LoginManager.getInstance().logInWithReadPermissions(
            activity,
            listOf("email", "public_profile")
        )
    }
    
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        callbackManager.onActivityResult(requestCode, resultCode, data)
    }
}
