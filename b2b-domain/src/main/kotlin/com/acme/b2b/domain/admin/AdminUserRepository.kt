package com.acme.b2b.domain.admin

import com.acme.b2b.types.Email

interface AdminUserRepository {
    fun findByEmail(email: Email): AdminUser?
    fun findById(id: Long): AdminUser?
    fun findAll(): List<AdminUser>
    fun existsByEmail(email: Email): Boolean
    fun save(admin: AdminUser): AdminUser
    fun deleteById(id: Long)
    fun countByRole(role: AdminRole): Long
}
