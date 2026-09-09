package com.acme.b2b.application.admin

/**
 * Use-case inputs. Named for the intent rather than the entity, so that "create a
 * dealer" and "change a dealer's profile" cannot be confused for a generic save —
 * they differ in what they are allowed to touch.
 */

data class AdminLoginCommand(
    val email: String,
    val password: String,
)

data class CreateCustomerCommand(
    val email: String,
    val name: String,
    val companyName: String,
    val tierId: Long,
    val phone: String? = null,
)

/**
 * Note what is absent: email and password. An email change is an identity change and
 * needs its own use case; a password is never set by an admin, only reset.
 */
data class UpdateCustomerCommand(
    val name: String,
    val companyName: String,
    val tierId: Long,
    val phone: String? = null,
    val status: String? = null,
)

data class CustomerQuery(
    val search: String? = null,
    val status: String? = null,
    val page: Int = 0,
    val size: Int = 10,
)
