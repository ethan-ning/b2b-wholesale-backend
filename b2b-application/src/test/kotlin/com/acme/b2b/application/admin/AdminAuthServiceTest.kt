package com.acme.b2b.application.admin

import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.domain.admin.AdminRole
import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.types.Email
import com.acme.b2b.types.PasswordHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
