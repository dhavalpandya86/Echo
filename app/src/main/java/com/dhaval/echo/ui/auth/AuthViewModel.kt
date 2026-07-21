package com.dhaval.echo.ui.auth

import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loginWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            authRepository.loginWithEmail(email, password).onFailure {
                _error.value = it.message ?: "Login failed"
            }
            _isLoading.value = false
        }
    }

    fun createAccount(name: String, email: String, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            authRepository.createEmailAccount(name, email, password).onFailure {
                _error.value = it.message ?: "Account creation failed"
            }
            _isLoading.value = false
        }
    }

    fun loginWithGoogle(idToken: String) {
        Log.d("AuthViewModel", "loginWithGoogle called, token present: ${idToken.isNotEmpty()}")
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            authRepository.loginWithGoogle(idToken).onFailure {
                Log.e("AuthViewModel", "Google login failure", it)
                _error.value = it.message ?: "Google login failed"
            }
            _isLoading.value = false
        }
    }

    fun sendPasswordReset(email: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            authRepository.sendPasswordReset(email).onFailure {
                _error.value = it.message ?: "Failed to send reset email"
            }
            _isLoading.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun setError(message: String) {
        _error.value = message
    }
}
