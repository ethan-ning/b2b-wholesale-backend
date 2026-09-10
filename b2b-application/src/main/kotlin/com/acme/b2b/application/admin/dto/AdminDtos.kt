package com.acme.b2b.application.admin.dto

/** Published contract for the admin portal. Matches the frontend's api/adminApi.ts. */

data class AdminUserDTO(
    val id: Long?,
    val email: String,
    val name: String,
    val role: String,
)

data class AdminLoginResponse(
    val token: String,
    val admin: AdminUserDTO,
)

data class CustomerDTO(
    val id: Long?,
    val email: String,
    val name: String,
    val companyName: String,
    val tierId: Long,
    val tierName: String,
    val phone: String?,
    val status: String,
    val mustChangePassword: Boolean,
    val createdAt: String?,
)

/**
 * Returned once, on creation. The generated password is shown to the admin here and
 * never again — it is stored only as a hash, so this response is the sole opportunity
 * to pass it to the dealer.
 */
data class CustomerCreatedDTO(
    val customer: CustomerDTO,
    val temporaryPassword: String,
)

data class CustomerTierDTO(
    val id: Long,
    val name: String,
    val sortOrder: Int,
)

/**
 * Returned once, on creation. Same reasoning as [CustomerCreatedDTO]: the password is
 * stored only as a hash, so this response is the sole opportunity to pass it on.
 */
data class AdminCreatedDTO(
    val admin: AdminUserDTO,
    val temporaryPassword: String,
)
