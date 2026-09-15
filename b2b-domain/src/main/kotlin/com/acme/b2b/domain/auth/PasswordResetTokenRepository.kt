package com.acme.b2b.domain.auth

import java.time.Instant

/** Where live reset links are kept. Adapted by infrastructure; the domain states only what it needs. */
interface PasswordResetTokenRepository {

    fun save(token: PasswordResetToken): PasswordResetToken

    /**
     * The lookup the reset endpoint performs. Returns a token in any state — expired and
     * spent ones included — so the caller can say *why* a link will not work rather than
     * treating every failure as "not found".
     */
    fun findByDigest(digest: String): PasswordResetToken?

    /**
     * Spends every live link for one account. Called when a new one is issued and again
     * when a reset completes, so a second request invalidates the first and a completed
     * reset leaves nothing usable behind.
     */
    fun invalidateAllFor(audience: ResetAudience, subjectId: Long, now: Instant)

    /** Housekeeping: drop rows that expired long enough ago to be of no interest. */
    fun deleteExpiredBefore(cutoff: Instant): Int
}
