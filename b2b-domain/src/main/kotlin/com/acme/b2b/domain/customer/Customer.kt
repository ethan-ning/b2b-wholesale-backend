package com.acme.b2b.domain.customer

import com.acme.b2b.types.TierId

/**
 * A dealer account. Admin-created — self-serve registration is deferred, so there is
 * no registration behaviour here.
 */
class Customer(
    val id: Long?,
    val email: String,
    val name: String,
    val companyName: String,
    val tierId: TierId,
    val phone: String?,
    val status: CustomerStatus,
    val mustChangePassword: Boolean,
) {
    init {
        require(email.contains("@")) { "Customer email looks invalid: $email" }
        require(companyName.isNotBlank()) { "Company name must not be blank" }
    }

    /** A disabled dealer keeps their account but cannot see pricing. */
    val canAccessCatalog: Boolean get() = status == CustomerStatus.ACTIVE

    override fun toString() = "Customer($email)"
}

enum class CustomerStatus { ACTIVE, DISABLED }
