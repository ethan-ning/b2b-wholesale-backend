package com.acme.b2b.application.admin

import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.application.support.NotPermitted
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.admin.AdminRole
import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.types.Email
import com.acme.b2b.types.PasswordHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminAccountServiceTest {

    private fun admin(id: Long, email: String, role: AdminRole, password: String = "Secret12345") =
        AdminUser(id, Email.of(email), PasswordHash("hashed:$password"), "Admin $id", role)

    private val owner = admin(1, "owner@example.com", AdminRole.SUPER_ADMIN)
    private val staff = admin(2, "staff@example.com", AdminRole.ADMIN)

    private fun serviceAs(callerId: Long?, seed: List<AdminUser> = listOf(owner, staff)): Pair<AdminAccountService, InMemoryAdminRepository> {
        val repo = InMemoryAdminRepository(seed)
        return AdminAccountService(
            admins = repo,
            passwordHasher = FakePasswordHasher(),
            temporaryPasswords = FixedTemporaryPasswordGenerator(),
            context = FixedAdminContext(callerId),
        ) to repo
    }

    // ---- own password ----------------------------------------------------------------

    @Test
    fun `an admin can change their own password`() {
        val (service, repo) = serviceAs(callerId = 2)

        service.changeOwnPassword(ChangeAdminPasswordCommand("Secret12345", "BrandNew98765"))

        val stored = repo.findById(2)!!
        assertEquals("hashed:BrandNew98765", stored.passwordHash.value)
    }

    @Test
    fun `the current password must be right, even though the caller is signed in`() {
        val (service, repo) = serviceAs(callerId = 2)

        assertFailsWith<AuthenticationFailed> {
            service.changeOwnPassword(ChangeAdminPasswordCommand("NotTheOne99", "BrandNew98765"))
        }
        assertEquals("hashed:Secret12345", repo.findById(2)!!.passwordHash.value)
    }

    @Test
    fun `the replacement must differ from the current password`() {
        val (service, _) = serviceAs(callerId = 2)

        assertFailsWith<UseCaseViolation> {
            service.changeOwnPassword(ChangeAdminPasswordCommand("Secret12345", "Secret12345"))
        }
    }

    @Test
    fun `changing a password touches only the caller's own account`() {
        val (service, repo) = serviceAs(callerId = 2)

        service.changeOwnPassword(ChangeAdminPasswordCommand("Secret12345", "BrandNew98765"))

        assertEquals("hashed:Secret12345", repo.findById(1)!!.passwordHash.value)
    }

    // ---- who may manage admins -------------------------------------------------------

    @Test
    fun `a plain admin cannot create another admin`() {
        val (service, repo) = serviceAs(callerId = 2)

        assertFailsWith<NotPermitted> {
            service.create(CreateAdminCommand("new@example.com", "New Person", "ADMIN"))
        }
        assertEquals(2, repo.findAll().size)
    }

    @Test
    fun `a plain admin cannot delete another admin`() {
        val (service, repo) = serviceAs(callerId = 2)

        assertFailsWith<NotPermitted> { service.delete(1) }
        assertEquals(2, repo.findAll().size)
    }

    @Test
    fun `a super admin creates an admin and gets the temporary password once`() {
        val (service, repo) = serviceAs(callerId = 1)

        val created = service.create(CreateAdminCommand("New@Example.com", "  New Person  ", "ADMIN"))

        assertEquals("TempPass1234", created.temporaryPassword)
        assertEquals("new@example.com", created.admin.email)
        assertEquals("New Person", created.admin.name)
        assertEquals("ADMIN", created.admin.role)
        // Listing never exposes it again — the DTO has no password field at all.
        assertTrue(service.list().any { it.email == "new@example.com" })
        assertEquals("hashed:TempPass1234", repo.findByEmail(Email.of("new@example.com"))!!.passwordHash.value)
    }

    @Test
    fun `a super admin may create another super admin`() {
        val (service, _) = serviceAs(callerId = 1)

        val created = service.create(CreateAdminCommand("second@example.com", "Second Owner", "SUPER_ADMIN"))

        assertEquals("SUPER_ADMIN", created.admin.role)
    }

    @Test
    fun `a duplicate email is refused`() {
        val (service, _) = serviceAs(callerId = 1)

        assertFailsWith<UseCaseViolation> {
            service.create(CreateAdminCommand("staff@example.com", "Impostor", "ADMIN"))
        }
    }

    @Test
    fun `an unknown role is refused rather than defaulted`() {
        val (service, _) = serviceAs(callerId = 1)

        assertFailsWith<UseCaseViolation> {
            service.create(CreateAdminCommand("new@example.com", "New Person", "OWNER"))
        }
    }

    // ---- the two guards that are not about permission ---------------------------------

    @Test
    fun `a super admin cannot delete themselves`() {
        val (service, repo) = serviceAs(callerId = 1)

        assertFailsWith<UseCaseViolation> { service.delete(1) }
        assertEquals(2, repo.findAll().size)
    }

    @Test
    fun `the last super admin cannot be deleted`() {
        val second = admin(3, "second@example.com", AdminRole.SUPER_ADMIN)
        val (service, repo) = serviceAs(callerId = 3, seed = listOf(owner, staff, second))

        // Two super admins: removing one is fine.
        service.delete(1)
        assertNull(repo.findById(1))

        // Now only this caller is left, and they cannot remove themselves either.
        assertFailsWith<UseCaseViolation> { service.delete(3) }
    }

    @Test
    fun `a super admin can delete a plain admin`() {
        val (service, repo) = serviceAs(callerId = 1)

        service.delete(2)

        assertNull(repo.findById(2))
    }

    @Test
    fun `deleting someone who does not exist is a not-found, not a silent success`() {
        val (service, _) = serviceAs(callerId = 1)

        assertFailsWith<NoSuchElementException> { service.delete(999) }
    }

    // ---- privilege comes from the database, not the token -----------------------------

    @Test
    fun `an admin whose account is gone cannot act on a still-valid token`() {
        val (service, _) = serviceAs(callerId = 99)

        assertFailsWith<AuthenticationFailed> {
            service.changeOwnPassword(ChangeAdminPasswordCommand("Secret12345", "BrandNew98765"))
        }
        assertFailsWith<AuthenticationFailed> {
            service.create(CreateAdminCommand("new@example.com", "New Person", "ADMIN"))
        }
    }

    @Test
    fun `the roster lists super admins first`() {
        val (service, _) = serviceAs(callerId = 2)

        assertEquals(listOf("owner@example.com", "staff@example.com"), service.list().map { it.email })
    }
}
