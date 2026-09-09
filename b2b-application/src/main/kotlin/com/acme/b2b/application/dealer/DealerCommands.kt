package com.acme.b2b.application.dealer

data class DealerLoginCommand(
    val email: String,
    val password: String,
)

data class ChangePasswordCommand(
    val currentPassword: String,
    val newPassword: String,
)
