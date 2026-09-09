package com.acme.b2b.domain.customer

import com.acme.b2b.types.Email
import com.acme.b2b.types.PasswordHash
import com.acme.b2b.types.TierId
import java.time.Instant

/**
 * A dealer account. Admin-created — self-serve registration is deferred, so the only
 * way one comes into existence is [create].
 *
 * State changes return a new instance rather than mutating: a use case then has both
 * the before and after, and nothing can be half-applied if a later step throws.
 */
class Customer(
    val id: Long?,
    val email: Email,
    val passwordHash: PasswordHash,
    val name: String,
    val companyName: String,
    val tierId: TierId,
    val phone: String?,
    val status: CustomerStatus,
    /** Set on creation and after a reset; cleared once the dealer picks their own. */
    val mustChangePassword: Boolean,
    val createdAt: Instant?,
) {
    init {
        require(name.isNotBlank()) { "Customer name must not be blank" }
        require(companyName.isNotBlank()) { "Company name must not be blank" }
    }

    /**
     * A disabled dealer keeps their account and their pricing, but sees nothing until
     * an admin re-enables them. Checked at the edge of every dealer use case.
     */
    val canAccessCatalog: Boolean get() = status == CustomerStatus.ACTIVE

    fun withProfile(name: String, companyName: String, tierId: TierId, phone: String?) =
        copy(name = name, companyName = companyName, tierId = tierId, phone = phone)

    fun withStatus(status: CustomerStatus) = copy(status = status)

    /** The dealer chose this one, so the forced-change flag is satisfied. */
    fun withChosenPassword(hash: PasswordHash) =
        copy(passwordHash = hash, mustChangePassword = false)

    /** An admin reset it, so the dealer must replace it at next login. */
    fun withResetPassword(hash: PasswordHash) =
        copy(passwordHash = hash, mustChangePassword = true)

    private fun copy(
        name: String = this.name,
        companyName: String = this.companyName,
        tierId: TierId = this.tierId,
        phone: String? = this.phone,
        status: CustomerStatus = this.status,
        passwordHash: PasswordHash = this.passwordHash,
        mustChangePassword: Boolean = this.mustChangePassword,
    ) = Customer(id, email, passwordHash, name, companyName, tierId, phone, status, mustChangePassword, createdAt)

    override fun toString() = "Customer($email)"

    companion object {
        /** A new dealer is active immediately and must replace the issued password. */
        fun create(
            email: Email,
            passwordHash: PasswordHash,
            name: String,
            companyName: String,
            tierId: TierId,
            phone: String? = null,
        ) = Customer(
            id = null,
            email = email,
            passwordHash = passwordHash,
            name = name,
            companyName = companyName,
            tierId = tierId,
            phone = phone,
            status = CustomerStatus.ACTIVE,
            mustChangePassword = true,
            createdAt = null,
        )
    }
}

enum class CustomerStatus { ACTIVE, DISABLED }
