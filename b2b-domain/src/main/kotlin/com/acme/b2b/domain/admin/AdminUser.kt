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
) {
    init { require(name.isNotBlank()) { "Admin name must not be blank" } }

    /** Only a super admin may create or remove other admins. */
    val canManageAdmins: Boolean get() = role == AdminRole.SUPER_ADMIN

    fun withPassword(hash: PasswordHash) = AdminUser(id, email, hash, name, role)

    companion object {
        fun create(email: Email, passwordHash: PasswordHash, name: String, role: AdminRole) =
            AdminUser(id = null, email = email, passwordHash = passwordHash, name = name, role = role)
    }

    override fun toString() = "AdminUser($email)"
}

enum class AdminRole { SUPER_ADMIN, ADMIN }

interface AdminUserRepository {
    fun findByEmail(email: Email): AdminUser?
    fun findById(id: Long): AdminUser?
    fun findAll(): List<AdminUser>
    fun existsByEmail(email: Email): Boolean
    fun save(admin: AdminUser): AdminUser
    fun deleteById(id: Long)
    fun countByRole(role: AdminRole): Long
}
