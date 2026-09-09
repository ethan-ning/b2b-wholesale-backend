package com.acme.b2b.application.dealer.dto

/** Matches the portal's LoginResponse in api/types.ts. */
data class DealerLoginResponse(
    val token: String,
    val user: DealerUserDTO,
)

data class DealerUserDTO(
    val id: Long,
    val email: String,
    val name: String,
    val companyName: String,
    val tierId: Long,
    val tierName: String,
    /**
     * True when the dealer is still on the password an admin generated. The token
     * issued alongside it reaches only the change-password endpoint, so this is a hint
     * for the UI rather than the thing enforcing it.
     */
    val mustChangePassword: Boolean,
)
