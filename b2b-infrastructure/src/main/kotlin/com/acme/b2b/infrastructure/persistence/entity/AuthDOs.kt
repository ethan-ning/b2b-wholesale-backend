package com.acme.b2b.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant

/**
 * A password-reset link. Holds the digest of the secret, never the secret — see
 * V8__password_reset.sql for why the digest is a plain SHA-256 rather than BCrypt.
 */
@Entity
@Table(name = "password_reset_token")
class PasswordResetTokenDO(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** DEALER or ADMIN. Only meaningful alongside [subjectId], which is why there is no FK. */
    @Column(nullable = false)
    var audience: String = "",

    @Column(name = "subject_id", nullable = false)
    var subjectId: Long = 0,

    @Column(name = "token_digest", nullable = false, unique = true, length = 64)
    var tokenDigest: String = "",

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null,

    @Column(name = "used_at")
    var usedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,
)
