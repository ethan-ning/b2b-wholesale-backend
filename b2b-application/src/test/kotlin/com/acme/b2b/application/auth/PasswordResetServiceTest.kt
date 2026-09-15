package com.acme.b2b.application.auth

import com.acme.b2b.application.admin.FakePasswordHasher
import com.acme.b2b.application.admin.InMemoryAdminRepository
import com.acme.b2b.application.admin.InMemoryCustomerRepository
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.admin.AdminRole
import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.domain.auth.PasswordResetToken
import com.acme.b2b.domain.auth.PasswordResetTokenRepository
import com.acme.b2b.domain.auth.ResetAudience
import com.acme.b2b.domain.auth.ResetLinkMailer
import com.acme.b2b.domain.auth.ResetLinkMessage
import com.acme.b2b.domain.customer.Customer
import com.acme.b2b.domain.customer.CustomerStatus
import com.acme.b2b.types.Email
import com.acme.b2b.types.PasswordHash
import com.acme.b2b.types.TierId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordResetServiceTest {

    private val now = Instant.parse("2026-09-15T10:00:00Z")

    private val dealer = Customer(
        id = 1,
        email = Email.of("dealer@example.com"),
        passwordHash = PasswordHash("hashed:old-dealer-pw"),
        name = "Pat Dealer",
        companyName = "Pat Haulage",
        tierId = TierId(1),
        phone = null,
        status = CustomerStatus.ACTIVE,
        mustChangePassword = true,
        createdAt = now,
    )

    private val admin = AdminUser(
        id = 1,
        email = Email.of("admin@example.com"),
        passwordHash = PasswordHash("hashed:old-admin-pw"),
        name = "System Admin",
        role = AdminRole.SUPER_ADMIN,
    )

    private lateinit var customers: InMemoryCustomerRepository
    private lateinit var admins: InMemoryAdminRepository
    private lateinit var tokens: InMemoryResetTokenRepository
    private lateinit var mailer: RecordingMailer

    private fun service(clock: Clock = Clock.fixed(now, ZoneOffset.UTC)): PasswordResetService {
        customers = InMemoryCustomerRepository(listOf(dealer))
        admins = InMemoryAdminRepository(listOf(admin))
        tokens = InMemoryResetTokenRepository()
        mailer = RecordingMailer()
        return PasswordResetService(
            customers = customers,
            admins = admins,
            tokens = tokens,
            passwordHasher = FakePasswordHasher(),
            mailer = mailer,
            clock = clock,
            portalBaseUrl = "https://b2b.example.com",
            ttlMinutes = 60,
        )
    }

    /** The token in the link must never be what the database holds. */
    @Test
    fun `stores only the digest of the token it emails`() {
        val service = service()
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")

        val emailedToken = mailer.sent.single().link.substringAfter("token=")
        val stored = tokens.rows.single()
        assertNotEquals(emailedToken, stored.tokenDigest)
        assertEquals(64, stored.tokenDigest.length)
        // And the raw token appears nowhere in the row.
        assertTrue(emailedToken !in stored.tokenDigest)
    }

    @Test
    fun `the link points at the portal route for that audience`() {
        val service = service()
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        assertTrue(mailer.sent.single().link.startsWith("https://b2b.example.com/reset-password?token="))

        val adminService = service()
        adminService.requestReset(ResetAudience.ADMIN, "admin@example.com")
        assertTrue(mailer.sent.single().link.startsWith("https://b2b.example.com/admin/reset-password?token="))
    }

    /**
     * The whole point of the endpoint's design: an address nobody holds must be
     * indistinguishable from one somebody does.
     */
    @Test
    fun `an unknown address is silent, not an error`() {
        val service = service()
        service.requestReset(ResetAudience.DEALER, "nobody@example.com")

        assertTrue(mailer.sent.isEmpty())
        assertTrue(tokens.rows.isEmpty())
    }

    @Test
    fun `a disabled dealer gets no link`() {
        val service = service()
        customers.save(
            Customer(
                id = 1, email = Email.of("dealer@example.com"),
                passwordHash = PasswordHash("hashed:old-dealer-pw"), name = "Pat Dealer",
                companyName = "Pat Haulage", tierId = TierId(1), phone = null,
                status = CustomerStatus.DISABLED, mustChangePassword = false, createdAt = now,
            )
        )
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        assertTrue(mailer.sent.isEmpty())
    }

    @Test
    fun `resetting sets the new password and ends any forced change`() {
        val service = service()
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        val token = mailer.sent.single().link.substringAfter("token=")

        service.completeReset(ResetAudience.DEALER, token, "a-new-password")

        val updated = customers.findById(1)!!
        assertEquals(PasswordHash("hashed:a-new-password"), updated.passwordHash)
        // They chose this one, so they must not be sent straight back to a change prompt.
        assertTrue(!updated.mustChangePassword)
    }

    @Test
    fun `a link works once`() {
        val service = service()
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        val token = mailer.sent.single().link.substringAfter("token=")

        service.completeReset(ResetAudience.DEALER, token, "a-new-password")
        val second = assertFailsWith<UseCaseViolation> {
            service.completeReset(ResetAudience.DEALER, token, "another-password")
        }
        assertTrue(second.message!!.contains("already been used"))
    }

    @Test
    fun `an expired link is refused and says so`() {
        var clock = Clock.fixed(now, ZoneOffset.UTC)
        val service = PasswordResetService(
            customers = InMemoryCustomerRepository(listOf(dealer)).also { customers = it },
            admins = InMemoryAdminRepository(listOf(admin)).also { admins = it },
            tokens = InMemoryResetTokenRepository().also { tokens = it },
            passwordHasher = FakePasswordHasher(),
            mailer = RecordingMailer().also { mailer = it },
            clock = object : Clock() {
                override fun getZone() = ZoneOffset.UTC
                override fun withZone(zone: java.time.ZoneId?) = this
                override fun instant() = clock.instant()
            },
            portalBaseUrl = "https://b2b.example.com",
            ttlMinutes = 60,
        )
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        val token = mailer.sent.single().link.substringAfter("token=")

        clock = Clock.fixed(now.plus(Duration.ofMinutes(61)), ZoneOffset.UTC)
        val failure = assertFailsWith<UseCaseViolation> {
            service.completeReset(ResetAudience.DEALER, token, "a-new-password")
        }
        assertTrue(failure.message!!.contains("expired"))
    }

    /** The rule that keeps the two portals apart. */
    @Test
    fun `a dealer link cannot be spent at the admin endpoint`() {
        val service = service()
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        val token = mailer.sent.single().link.substringAfter("token=")

        assertFailsWith<UseCaseViolation> {
            service.completeReset(ResetAudience.ADMIN, token, "a-new-password")
        }
        // And the admin's password is untouched.
        assertEquals(PasswordHash("hashed:old-admin-pw"), admins.findById(1)!!.passwordHash)
    }

    @Test
    fun `requesting again retires the previous link`() {
        val service = service()
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        val first = mailer.sent.single().link.substringAfter("token=")

        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        val second = mailer.sent.last().link.substringAfter("token=")
        assertNotEquals(first, second)

        assertFailsWith<UseCaseViolation> {
            service.completeReset(ResetAudience.DEALER, first, "a-new-password")
        }
        service.completeReset(ResetAudience.DEALER, second, "a-new-password")
    }

    @Test
    fun `a too-short password is refused before the link is spent`() {
        val service = service()
        service.requestReset(ResetAudience.DEALER, "dealer@example.com")
        val token = mailer.sent.single().link.substringAfter("token=")

        assertFailsWith<UseCaseViolation> { service.completeReset(ResetAudience.DEALER, token, "short") }
        // Still usable — a mistyped password must not burn the link.
        service.completeReset(ResetAudience.DEALER, token, "a-new-password")
    }

    @Test
    fun `a token we never issued is refused`() {
        val service = service()
        assertFailsWith<UseCaseViolation> {
            service.completeReset(ResetAudience.DEALER, "not-a-real-token-but-long-enough-to-parse-ok", "a-new-password")
        }
    }
}

/** Records what would have been sent, so a test can read the link out of it. */
private class RecordingMailer : ResetLinkMailer {
    val sent = mutableListOf<ResetLinkMessage>()
    override fun sendResetLink(message: ResetLinkMessage) { sent += message }
}

private class InMemoryResetTokenRepository : PasswordResetTokenRepository {
    val rows = mutableListOf<PasswordResetToken>()
    private var nextId = 1L

    override fun save(token: PasswordResetToken): PasswordResetToken {
        val id = token.id ?: nextId++
        val stored = PasswordResetToken(
            id = id,
            audience = token.audience,
            subjectId = token.subjectId,
            tokenDigest = token.tokenDigest,
            expiresAt = token.expiresAt,
            usedAt = token.usedAt,
            createdAt = token.createdAt,
        )
        rows.removeAll { it.id == id }
        rows += stored
        return stored
    }

    override fun findByDigest(digest: String) = rows.firstOrNull { it.tokenDigest == digest }

    override fun invalidateAllFor(audience: ResetAudience, subjectId: Long, now: Instant) {
        rows.filter { it.audience == audience && it.subjectId == subjectId && it.usedAt == null }
            .forEach { save(it.spent(now)) }
    }

    override fun deleteExpiredBefore(cutoff: Instant): Int {
        val before = rows.size
        rows.removeAll { it.expiresAt.isBefore(cutoff) }
        return before - rows.size
    }
}
