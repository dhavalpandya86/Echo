package com.dhaval.echo.domain.auth

data class User(
    val id: String,
    val displayName: String?,
    val email: String?,
    val phoneNumber: String?,
    val photoUrl: String?,
    val authProviders: List<AuthProvider>,
    val createdAt: Long
)
