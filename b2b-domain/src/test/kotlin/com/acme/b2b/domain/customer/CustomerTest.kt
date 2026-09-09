package com.acme.b2b.domain.customer

import com.acme.b2b.types.Email
import com.acme.b2b.types.PasswordHash
import com.acme.b2b.types.TierId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomerTest {

    private fun newCustomer() = Customer.create(
        email = Email.of("Dealer1@Example.com "),
        passwordHash = PasswordHash("hashed"),
        name = "Alice Johnson",
        companyName = "Johnson Auto Supply",
        tierId = TierId(1),
    )

    @Test
    fun `a new dealer is active and must replace the issued password`() {
        val customer = newCustomer()
        assertEquals(CustomerStatus.ACTIVE, customer.status)
        assertTrue(customer.mustChangePassword)
        assertTrue(customer.canAccessCatalog)
    }

    @Test
    fun `email is normalised on the way in`() {
        assertEquals("dealer1@example.com", newCustomer().email.value)
    }

    @Test
    fun `rejects a blank company name`() {
        assertFailsWith<IllegalArgumentException> {
            Customer.create(Email.of("a@b.com"), PasswordHash("h"), "Alice", "  ", TierId(1))
        }
    }

    @Test
    fun `disabling keeps the account but closes the catalog`() {
        val disabled = newCustomer().withStatus(CustomerStatus.DISABLED)
        assertFalse(disabled.canAccessCatalog)
        assertEquals("dealer1@example.com", disabled.email.value)
    }

    @Test
    fun `choosing a password satisfies the forced change, resetting one re-arms it`() {
        val chosen = newCustomer().withChosenPassword(PasswordHash("chosen"))
        assertFalse(chosen.mustChangePassword)

        val reset = chosen.withResetPassword(PasswordHash("temp"))
        assertTrue(reset.mustChangePassword)
        assertEquals("temp", reset.passwordHash.value)
    }

    @Test
    fun `a profile edit cannot change identity`() {
        val original = newCustomer()
        val updated = original.withProfile("Alice J", "Johnson Auto", TierId(2), "555-1001")

        assertEquals(original.email, updated.email)
        assertEquals(original.passwordHash, updated.passwordHash)
        assertEquals(TierId(2), updated.tierId)
        assertEquals("Alice J", updated.name)
    }

    @Test
    fun `password hashes do not leak through toString`() {
        assertFalse(PasswordHash("super-secret-hash").toString().contains("super-secret"))
    }
}
