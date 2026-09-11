package com.acme.b2b.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "admin_user")
class AdminUserDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var email: String = "",

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",

    @Column(nullable = false)
    var name: String = "",

    @Column(nullable = false)
    var role: String = "ADMIN",

    @Column(name = "must_change_password", nullable = false)
    var mustChangePassword: Boolean = false,

    @Column(name = "created_at")
    var createdAt: Instant? = null,
)

@Entity
@Table(name = "customer")
class CustomerDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var email: String = "",

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",

    @Column(nullable = false)
    var name: String = "",

    @Column(name = "company_name", nullable = false)
    var companyName: String = "",

    @Column(name = "tier_id", nullable = false)
    var tierId: Long = 0,

    var phone: String? = null,

    @Column(name = "must_change_password", nullable = false)
    var mustChangePassword: Boolean = true,

    @Column(nullable = false)
    var status: String = "ACTIVE",

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)
