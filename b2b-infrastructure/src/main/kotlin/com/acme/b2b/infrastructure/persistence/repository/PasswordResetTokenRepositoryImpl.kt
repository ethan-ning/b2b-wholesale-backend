package com.acme.b2b.infrastructure.persistence.repository

import com.acme.b2b.domain.auth.PasswordResetToken
import com.acme.b2b.domain.auth.PasswordResetTokenRepository
import com.acme.b2b.domain.auth.ResetAudience
import com.acme.b2b.infrastructure.persistence.entity.PasswordResetTokenDO
import com.acme.b2b.infrastructure.persistence.jpa.PasswordResetTokenJpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class PasswordResetTokenRepositoryImpl(
    private val jpa: PasswordResetTokenJpaRepository,
) : PasswordResetTokenRepository {

    override fun save(token: PasswordResetToken): PasswordResetToken {
        val row = token.id?.let { jpa.findById(it).orElse(null) } ?: PasswordResetTokenDO()
        row.audience = token.audience.name
        row.subjectId = token.subjectId
        row.tokenDigest = token.tokenDigest
        row.expiresAt = token.expiresAt
        row.usedAt = token.usedAt
        row.createdAt = token.createdAt
        return toDomain(jpa.save(row))
    }

    override fun findByDigest(digest: String): PasswordResetToken? =
        jpa.findByTokenDigest(digest)?.let { toDomain(it) }

    @Transactional
    override fun invalidateAllFor(audience: ResetAudience, subjectId: Long, now: Instant) {
        jpa.markUsed(audience.name, subjectId, now)
    }

    @Transactional
    override fun deleteExpiredBefore(cutoff: Instant): Int = jpa.deleteExpiredBefore(cutoff)

    private fun toDomain(row: PasswordResetTokenDO) = PasswordResetToken(
        id = row.id,
        audience = ResetAudience.valueOf(row.audience),
        subjectId = row.subjectId,
        tokenDigest = row.tokenDigest,
        expiresAt = checkNotNull(row.expiresAt) { "A persisted reset token must have an expiry" },
        usedAt = row.usedAt,
        createdAt = checkNotNull(row.createdAt) { "A persisted reset token must have a created_at" },
    )
}
