package com.acme.b2b.application.dealer

import com.acme.b2b.application.admin.FakePasswordHasher
import com.acme.b2b.application.admin.FakeTokenIssuer
import com.acme.b2b.application.admin.InMemoryCustomerRepository
import com.acme.b2b.application.admin.InMemoryTierRepository
import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.customer.Customer
import com.acme.b2b.domain.customer.CustomerStatus
import com.acme.b2b.domain.customer.CustomerTier
import com.acme.b2b.types.Email
import com.acme.b2b.types.PasswordHash
import com.acme.b2b.types.TierId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dealer sign-in and the forced first password change.
 *
 * The rule under most of this is that every failure looks the same from outside. Wrong
 * password, unknown email, suspended account — telling them apart would tell an outsider
 * which dealers exist and which have been cut off, and the dealer learns nothing useful
 * from the difference.
 */
class DealerAuthServiceTest {

    private val gold = TierId(1)

    private fun dealer(
        email: String = "dealer1@example.com",
        password: String = "correct-horse",
        status: CustomerStatus = CustomerStatus.ACTIVE,
        mustChangePassword: Boolean = false,
    ) = Customer(
        id = 1,
        email = Email.of(email),
        passwordHash = PasswordHash("hashed:$password"),
        name = "Gold Dealer",
        companyName = "Northgate Truck Supply",
        tierId = gold,
        phone = null,
        status = status,
        mustChangePassword = mustChangePassword,
        createdAt = Instant.EPOCH,
    )

    private fun service(vararg seed: Customer) = DealerAuthService(
        InMemoryCustomerRepository(seed.toList()),
        InMemoryTierRepository(listOf(CustomerTier(gold, "Gold", 1))),
        FakePasswordHasher(),
        FakeTokenIssuer(),
    )

    private fun login(email: String, password: String) = DealerLoginCommand(email, password)

    // ─── Sign in ─────────────────────────────────────────────────────────

    @Test
    fun `a dealer signs in and gets their tier`() {
        val response = service(dealer()).login(login("dealer1@example.com", "correct-horse"))

        assertEquals("dealer-token:1:1", response.token)
        assertEquals("Gold", response.user.tierName)
        assertEquals("Northgate Truck Supply", response.user.companyName)
        assertFalse(response.user.mustChangePassword)
    }

    @Test
    fun `every rejection reads the same`() {
        val service = service(dealer(status = CustomerStatus.ACTIVE))
        val disabled = service(dealer(email = "off@example.com", status = CustomerStatus.DISABLED))

        val messages = listOf(
            assertFailsWith<AuthenticationFailed> { service.login(login("dealer1@example.com", "wrong")) },
            assertFailsWith<AuthenticationFailed> { service.login(login("nobody@example.com", "correct-horse")) },
            assertFailsWith<AuthenticationFailed> { disabled.login(login("off@example.com", "correct-horse")) },
            assertFailsWith<AuthenticationFailed> { service.login(login("not-an-email", "correct-horse")) },
            assertFailsWith<AuthenticationFailed> { service.login(login("dealer1@example.com", "")) },
        ).map { it.message }

        assertEquals(1, messages.distinct().size, "the failures differ: $messages")
        assertEquals("Invalid email or password", messages.first())
    }

    @Test
    fun `a dealer on an admin-issued password gets a token that only reaches the change endpoint`() {
        val response = service(dealer(mustChangePassword = true))
            .login(login("dealer1@example.com", "correct-horse"))

        // Not a catalog token: the forced change must not depend on the client honouring
        // a flag it can see and ignore.
        assertEquals("pwchange-token:1", response.token)
        assertTrue(response.user.mustChangePassword)
    }

    // ─── Change password ─────────────────────────────────────────────────

    @Test
    fun `changing the password ends the forced change and returns a full token`() {
        val customers = InMemoryCustomerRepository(listOf(dealer(mustChangePassword = true)))
        val service = DealerAuthService(
            customers,
            InMemoryTierRepository(listOf(CustomerTier(gold, "Gold", 1))),
            FakePasswordHasher(),
            FakeTokenIssuer(),
        )

        val response = service.changePassword(1, ChangePasswordCommand("correct-horse", "a-better-one"))

        assertEquals("dealer-token:1:1", response.token)
        assertFalse(response.user.mustChangePassword)
        assertFalse(customers.findById(1)!!.mustChangePassword)
        assertEquals(PasswordHash("hashed:a-better-one"), customers.findById(1)!!.passwordHash)
    }

    @Test
    fun `the current password is required even though the caller is authenticated`() {
        // The token may have been issued from a credential the dealer never chose.
        val failure = assertFailsWith<AuthenticationFailed> {
            service(dealer()).changePassword(1, ChangePasswordCommand("not-it", "a-better-one"))
        }

        assertEquals("Current password is incorrect", failure.message)
    }

    @Test
    fun `the new password must actually be new`() {
        val failure = assertFailsWith<UseCaseViolation> {
            service(dealer()).changePassword(1, ChangePasswordCommand("correct-horse", "correct-horse"))
        }

        assertTrue(failure.message!!.contains("differ"), failure.message)
    }

    @Test
    fun `a new password that the rules refuse is reported as a rule, not as a bad login`() {
        // The dealer knows their own current password here, so telling them what is wrong
        // with the replacement gives nothing away.
        assertFailsWith<UseCaseViolation> {
            service(dealer()).changePassword(1, ChangePasswordCommand("correct-horse", "short"))
        }
    }

    @Test
    fun `changing the password of someone who is not there fails as a bad login`() {
        assertFailsWith<AuthenticationFailed> {
            service(dealer()).changePassword(404, ChangePasswordCommand("correct-horse", "a-better-one"))
        }
    }

    @Test
    fun `a tier that has vanished leaves the name blank rather than failing the login`() {
        val service = DealerAuthService(
            InMemoryCustomerRepository(listOf(dealer())),
            InMemoryTierRepository(emptyList()),
            FakePasswordHasher(),
            FakeTokenIssuer(),
        )

        assertEquals("", service.login(login("dealer1@example.com", "correct-horse")).user.tierName)
    }
}
