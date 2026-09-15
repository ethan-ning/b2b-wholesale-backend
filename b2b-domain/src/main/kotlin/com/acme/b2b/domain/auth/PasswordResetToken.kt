package com.acme.b2b.domain.auth

import java.time.Instant

/**
 * Whose password a reset link opens.
 *
 * Dealers and admins live in separate tables and authenticate at separate endpoints, and
 * conflating them is how a dealer ends up able to reset an admin. A link states which it
 * is, and the reset can only ever reach that side.
 */
enum class ResetAudience { DEALER, ADMIN }

/**
 * A live password-reset link, identified by the digest of the secret it carries.
 *
 * The rules live here rather than in the service that issues them: single use, a hard
 * expiry, and nothing about who the holder *claims* to be — only the account the link was
 * minted for.
 */
class PasswordResetToken(
    val id: Long?,
    val audience: ResetAudience,
    val subjectId: Long,
    val tokenDigest: String,
    val expiresAt: Instant,
    /** Set the moment the link is spent, so a second click cannot spend it again. */
    val usedAt: Instant? = null,
    val createdAt: Instant,
) {
    init {
        require(tokenDigest.length == DIGEST_LENGTH) { "Token digest must be a SHA-256 hex string" }
        require(subjectId > 0) { "A reset token must belong to a persisted account" }
    }

    /** Unspent and not yet expired. The only state in which a reset may proceed. */
    fun isUsable(now: Instant): Boolean = usedAt == null && now.isBefore(expiresAt)

    /** Why it cannot be used, for a caller that wants to say something more useful than "invalid". */
    fun refusalReason(now: Instant): ResetRefusal? = when {
        usedAt != null -> ResetRefusal.ALREADY_USED
        !now.isBefore(expiresAt) -> ResetRefusal.EXPIRED
        else -> null
    }

    fun spent(now: Instant) = PasswordResetToken(
        id = id,
        audience = audience,
        subjectId = subjectId,
        tokenDigest = tokenDigest,
        expiresAt = expiresAt,
        usedAt = now,
        createdAt = createdAt,
    )

    override fun toString() = "PasswordResetToken($audience/$subjectId)"

    companion object {
        private const val DIGEST_LENGTH = 64

        /**
         * How long a link stays good. Long enough to survive a mail queue and someone
         * reading mail on a break; short enough that a forwarded or archived message
         * stops being a key to the account.
         */
        const val DEFAULT_TTL_MINUTES = 60L

        fun issue(
            audience: ResetAudience,
            subjectId: Long,
            tokenDigest: String,
            now: Instant,
            ttlMinutes: Long = DEFAULT_TTL_MINUTES,
        ) = PasswordResetToken(
            id = null,
            audience = audience,
            subjectId = subjectId,
            tokenDigest = tokenDigest,
            expiresAt = now.plusSeconds(ttlMinutes * 60),
            usedAt = null,
            createdAt = now,
        )
    }
}

/** Why a link was refused. Distinguished because the two want different words on screen. */
enum class ResetRefusal { EXPIRED, ALREADY_USED }
