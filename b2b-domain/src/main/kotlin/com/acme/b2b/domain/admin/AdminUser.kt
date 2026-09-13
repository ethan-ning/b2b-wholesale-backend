package com.acme.b2b.domain.admin

import com.acme.b2b.types.Email
import com.acme.b2b.types.PasswordHash

/**
 * A back-office operator. Separate from Customer on purpose: they authenticate against
 * a different endpoint, hold a different kind of authority, and conflating them is how
 * a dealer ends up able to reach admin routes.
 */
class AdminUser(
    val id: Long?,
    val email: Email,
    val passwordHash: PasswordHash,
    val name: String,
    val role: AdminRole,
    /** True while the password is one somebody else generated. */
    val mustChangePassword: Boolean = false,
) {
    init { require(name.isNotBlank()) { "Admin name must not be blank" } }

    /** Only a super admin may create, reset or remove other admins. */
    val canManageAdmins: Boolean get() = role == AdminRole.SUPER_ADMIN

    /** A password the holder chose themselves, which ends the forced change. */
    fun withChosenPassword(hash: PasswordHash) = copy(passwordHash = hash, mustChangePassword = false)

    /** A password somebody else generated, which begins one. */
    fun withIssuedPassword(hash: PasswordHash) = copy(passwordHash = hash, mustChangePassword = true)

    private fun copy(
        passwordHash: PasswordHash = this.passwordHash,
        mustChangePassword: Boolean = this.mustChangePassword,
    ) = AdminUser(id, email, passwordHash, name, role, mustChangePassword)

    companion object {
        /**
         * A new admin always starts on a password they did not choose. Super admins are
         * exempt from the forced change: the first one is created by hand at deploy time,
         * before there is anyone to enforce it.
         */
        fun create(email: Email, passwordHash: PasswordHash, name: String, role: AdminRole) =
            AdminUser(
                id = null,
                email = email,
                passwordHash = passwordHash,
                name = name,
                role = role,
                mustChangePassword = role == AdminRole.ADMIN,
            )
    }

    override fun toString() = "AdminUser($email)"
}

enum class AdminRole { SUPER_ADMIN, ADMIN }
