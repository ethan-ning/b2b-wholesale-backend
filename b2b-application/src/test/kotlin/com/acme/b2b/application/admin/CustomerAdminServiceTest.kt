package com.acme.b2b.application.admin

import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.customer.CustomerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CustomerAdminServiceTest {

    private val customers = InMemoryCustomerRepository()
    private val service = CustomerAdminService(
        customers = customers,
        tiers = InMemoryTierRepository(),
        passwordHasher = FakePasswordHasher(),
        temporaryPasswords = FixedTemporaryPasswordGenerator(),
    )

    private fun createAlice() = service.create(
        CreateCustomerCommand(
            email = "Alice@Example.com",
            name = "Alice Johnson",
            companyName = "Johnson Auto Supply",
            tierId = 1,
            phone = "555-1001",
        )
    )

    @Test
    fun `creating a dealer returns the temporary password exactly once`() {
        val created = createAlice()

        assertEquals("TempPass1234", created.temporaryPassword)
        assertTrue(created.customer.mustChangePassword)
        assertEquals("Gold", created.customer.tierName)
        assertEquals("alice@example.com", created.customer.email)

        // Reading it back never exposes the password again.
        val fetched = service.findById(created.customer.id!!)!!
        assertEquals(created.customer.email, fetched.email)
    }

    @Test
    fun `only the hash is stored`() {
        val created = createAlice()
        val stored = customers.findById(created.customer.id!!)!!

        assertNotEquals("TempPass1234", stored.passwordHash.value)
        assertEquals("hashed:TempPass1234", stored.passwordHash.value)
    }

    @Test
    fun `a duplicate email is refused, whatever its case`() {
        createAlice()
        val error = assertFailsWith<UseCaseViolation> {
            service.create(CreateCustomerCommand("ALICE@example.com", "Someone Else", "Other Co", 1))
        }
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun `an unknown tier is refused rather than stored`() {
        assertFailsWith<UseCaseViolation> {
            service.create(CreateCustomerCommand("bob@example.com", "Bob", "Bob Co", 99))
        }
    }

    @Test
    fun `updating changes the profile and leaves credentials alone`() {
        val created = createAlice()
        val before = customers.findById(created.customer.id!!)!!

        val updated = service.update(
            created.customer.id!!,
            UpdateCustomerCommand("Alice J", "Johnson Auto", tierId = 2, phone = null, status = "DISABLED"),
        )

        assertEquals("Alice J", updated.name)
        assertEquals("Silver", updated.tierName)
        assertEquals("DISABLED", updated.status)

        val after = customers.findById(created.customer.id!!)!!
        assertEquals(before.email, after.email)
        assertEquals(before.passwordHash, after.passwordHash)
    }

    @Test
    fun `resetting a password issues a new one and re-arms the forced change`() {
        val created = createAlice()
        service.update(created.customer.id!!, UpdateCustomerCommand("Alice", "Johnson Auto", 1))
        val chosen = customers.save(customers.findById(created.customer.id!!)!!.withChosenPassword(
            com.acme.b2b.types.PasswordHash("hashed:their-own")
        ))
        assertTrue(!chosen.mustChangePassword)

        val reset = service.resetPassword(created.customer.id!!)
        assertEquals("TempPass1234", reset.temporaryPassword)
        assertTrue(reset.customer.mustChangePassword)
    }

    @Test
    fun `listing filters by text and status`() {
        createAlice()
        service.create(CreateCustomerCommand("bob@example.com", "Bob Chen", "Chen Parts Co", 2))
        service.update(2, UpdateCustomerCommand("Bob Chen", "Chen Parts Co", 2, status = "DISABLED"))

        assertEquals(2, service.list(CustomerQuery()).totalElements)
        assertEquals(1, service.list(CustomerQuery(search = "johnson")).totalElements)
        assertEquals(1, service.list(CustomerQuery(search = "chen parts")).totalElements)
        assertEquals(1, service.list(CustomerQuery(status = "ACTIVE")).totalElements)
        assertEquals(CustomerStatus.DISABLED.name, service.list(CustomerQuery(status = "DISABLED")).content.single().status)
    }

    @Test
    fun `an unknown dealer is a not-found, not a crash`() {
        assertFailsWith<NoSuchElementException> {
            service.update(404, UpdateCustomerCommand("X", "Y", 1))
        }
    }
}
