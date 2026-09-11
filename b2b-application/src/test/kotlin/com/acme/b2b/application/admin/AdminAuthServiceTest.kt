package com.acme.b2b.application.admin

import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.domain.admin.AdminRole
import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.types.Email
import com.acme.b2b.types.PasswordHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminAuthServiceTest {

    private val admin = AdminUser(
        id = 1,
        email = Email.of("admin@example.com"),
        passwordHash = PasswordHash("hashed:admin123"),
        name = "System Admin",
        role = AdminRole.SUPER_ADMIN,
    )

    private val service = AdminAuthService(
        admins = InMemoryAdminRepository(listOf(admin)),
        passwordHasher = FakePasswordHasher(),
        tokens = FakeTokenIssuer(),
    )

    @Test
    fun `issues a token carrying the admin's role`() {
        val response = service.login(AdminLoginCommand("admin@example.com", "admin123"))

        assertEquals("admin-token:1:SUPER_ADMIN", response.token)
        assertEquals("admin@example.com", response.admin.email)
        assertEquals("SUPER_ADMIN", response.admin.role)
    }

    @Test
    fun `accepts the email in any case`() {
        service.login(AdminLoginCommand("ADMIN@Example.com", "admin123"))
    }

    @Test
    fun `a wrong password and an unknown account fail identically`() {
        val wrongPassword = assertFailsWith<AuthenticationFailed> {
            service.login(AdminLoginCommand("admin@example.com", "wrong-password"))
        }
        val unknownAccount = assertFailsWith<AuthenticationFailed> {
            service.login(AdminLoginCommand("nobody@example.com", "admin123"))
        }
        // Identical, so the response cannot be used to enumerate accounts.
        assertEquals(wrongPassword.message, unknownAccount.message)
    }

    @Test
    fun `malformed input fails as an auth failure, not a validation error`() {
        // A too-short password would throw IllegalArgumentException from RawPassword;
        // surfacing that would tell an attacker the minimum length.
        assertFailsWith<AuthenticationFailed> { service.login(AdminLoginCommand("admin@example.com", "x")) }
        assertFailsWith<AuthenticationFailed> { service.login(AdminLoginCommand("not-an-email", "admin123")) }
    }

    /**
     * The forced change is enforced by what the token can reach, not by the client agreeing
     * to navigate somewhere — so the token itself has to be the restricted one.
     */
    @Test
    fun `an admin on an issued password gets a token that can only change it`() {
        val forced = AdminUser(
            9, Email.of("forced@example.com"), PasswordHash("hashed:Issued12345"),
            "Forced", AdminRole.ADMIN, mustChangePassword = true,
        )
        val service = AdminAuthService(
            InMemoryAdminRepository(listOf(forced)), FakePasswordHasher(), FakeTokenIssuer(),
        )

        val result = service.login(AdminLoginCommand("forced@example.com", "Issued12345"))

        assertEquals("admin-pwchange-token:9", result.token)
        assertTrue(result.admin.mustChangePassword)
    }

    @Test
    fun `an admin on their own password gets a full session token`() {
        val settled = AdminUser(
            8, Email.of("settled@example.com"), PasswordHash("hashed:Chosen12345"),
            "Settled", AdminRole.ADMIN, mustChangePassword = false,
        )
        val service = AdminAuthService(
            InMemoryAdminRepository(listOf(settled)), FakePasswordHasher(), FakeTokenIssuer(),
        )

        val result = service.login(AdminLoginCommand("settled@example.com", "Chosen12345"))

        assertEquals("admin-token:8:ADMIN", result.token)
        assertFalse(result.admin.mustChangePassword)
    }
}
