package com.acme.b2b.application.auth

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.admin.AdminUserRepository
import com.acme.b2b.domain.auth.PasswordHasher
import com.acme.b2b.domain.auth.PasswordResetToken
import com.acme.b2b.domain.auth.PasswordResetTokenRepository
import com.acme.b2b.domain.auth.ResetAudience
import com.acme.b2b.domain.auth.ResetLinkMailer
import com.acme.b2b.domain.auth.ResetLinkMessage
import com.acme.b2b.domain.auth.ResetRefusal
import com.acme.b2b.domain.customer.CustomerRepository
import com.acme.b2b.types.Email
import com.acme.b2b.types.RawPassword
import com.acme.b2b.types.ResetToken
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Forgotten-password links, for dealers and admins alike.
 *
 * One service rather than one per audience: the rules — single use, expiry, invalidate the
 * previous link, never say whether an address exists — are identical, and stating them
 * twice is how one of them quietly loses a rule.
 */
@Service
class PasswordResetService(
    private val customers: CustomerRepository,
    private val admins: AdminUserRepository,
    private val tokens: PasswordResetTokenRepository,
    private val passwordHasher: PasswordHasher,
    private val mailer: ResetLinkMailer,
    private val clock: Clock,
    @Value("\${app.portal.base-url}") private val portalBaseUrl: String,
    @Value("\${app.password-reset.ttl-minutes:60}") private val ttlMinutes: Long,
) {

    /**
     * Start a reset. Returns nothing and tells the caller nothing.
     *
     * An unknown address, a disabled dealer and a successful send are indistinguishable
     * from outside — anything else turns this endpoint into a way to ask which dealers
     * exist and which have been suspended. The same reasoning as login returning one
     * message for every failure.
     */
    @Transactional
    fun requestReset(audience: ResetAudience, rawEmail: String) {
        val email = runCatching { Email.of(rawEmail) }.getOrNull() ?: return
        val now = clock.instant()

        val target = when (audience) {
            ResetAudience.DEALER -> customers.findByEmail(email)
                ?.takeIf { it.canAccessCatalog }
                ?.let { ResetTarget(checkNotNull(it.id), it.name, it.email) }

            ResetAudience.ADMIN -> admins.findByEmail(email)
                ?.let { ResetTarget(checkNotNull(it.id), it.name, it.email) }
        }
        // No account, or a disabled one. Deliberately not an error and deliberately
        // silent: returning early here is what makes an unknown address indistinguishable
        // from a known one. Nothing is logged because this module has no logger — see the
        // dependency comment in b2b-application/build.gradle.kts.
            ?: return

        // A new link retires any earlier one. Two live links to one account means a stale
        // email in an inbox stays a key for as long as the newest does.
        tokens.invalidateAllFor(audience, target.id, now)

        val secret = ResetToken.random()
        tokens.save(
            PasswordResetToken.issue(
                audience = audience,
                subjectId = target.id,
                tokenDigest = secret.digest,
                now = now,
                ttlMinutes = ttlMinutes,
            )
        )

        mailer.sendResetLink(
            ResetLinkMessage(
                to = target.email,
                recipientName = target.name,
                link = resetLink(audience, secret),
                expiresInMinutes = ttlMinutes,
                audience = audience,
            )
        )
    }

    /**
     * Finish a reset. The link identifies the account, so no email is asked for and none
     * is trusted if offered — otherwise a valid link for one account could be pointed at
     * another.
     */
    @Transactional
    fun completeReset(audience: ResetAudience, rawToken: String, rawNewPassword: String) {
        val token = runCatching { ResetToken(rawToken) }.getOrNull()
            ?: throw UseCaseViolation(INVALID_LINK)
        val newPassword = runCatching { RawPassword(rawNewPassword) }.getOrNull()
            ?: throw UseCaseViolation(
                "Password must be between ${RawPassword.MIN_LENGTH} and ${RawPassword.MAX_LENGTH} characters"
            )

        val now = clock.instant()
        val stored = tokens.findByDigest(token.digest) ?: throw UseCaseViolation(INVALID_LINK)

        // A link minted for a dealer must not be spendable at the admin endpoint.
        if (stored.audience != audience) throw UseCaseViolation(INVALID_LINK)

        stored.refusalReason(now)?.let {
            throw UseCaseViolation(
                when (it) {
                    ResetRefusal.EXPIRED -> "This reset link has expired. Please request a new one."
                    ResetRefusal.ALREADY_USED -> "This reset link has already been used. Please request a new one."
                }
            )
        }

        val hash = passwordHasher.hash(newPassword)
        when (stored.audience) {
            ResetAudience.DEALER -> {
                val customer = customers.findById(stored.subjectId) ?: throw UseCaseViolation(INVALID_LINK)
                // withChosenPassword, not withIssuedPassword: they picked this one, so the
                // forced-change flag must not be set and send them straight back to a prompt.
                customers.save(customer.withChosenPassword(hash))
            }

            ResetAudience.ADMIN -> {
                val admin = admins.findById(stored.subjectId) ?: throw UseCaseViolation(INVALID_LINK)
                admins.save(admin.withChosenPassword(hash))
            }
        }

        // Spend this link and retire any other live one for the account.
        tokens.save(stored.spent(now))
        tokens.invalidateAllFor(stored.audience, stored.subjectId, now)
    }

    private fun resetLink(audience: ResetAudience, token: ResetToken): String {
        val path = if (audience == ResetAudience.ADMIN) "/admin/reset-password" else "/reset-password"
        return "${portalBaseUrl.trimEnd('/')}$path?token=${token.value}"
    }

    private data class ResetTarget(val id: Long, val name: String, val email: Email)

    private companion object {
        /**
         * One message for a malformed token, an unknown one, and a mismatched audience.
         * Telling them apart would say whether a given token ever existed.
         */
        const val INVALID_LINK = "This reset link is not valid. Please request a new one."
    }
}
