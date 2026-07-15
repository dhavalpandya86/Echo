package com.dhaval.echo.domain.auth

sealed interface AuthState {
    object Unauthenticated : AuthState
    object Authenticating : AuthState
    data class Authenticated(val user: User) : AuthState
    data class Error(val message: String) : AuthState
    object Loading : AuthState
}
