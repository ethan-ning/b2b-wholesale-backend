package com.acme.b2b.application.admin

import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.domain.admin.AdminUserRepository
import com.acme.b2b.domain.auth.AccessTokenIssuer
import com.acme.b2b.domain.auth.PasswordHasher
import com.acme.b2b.domain.auth.TemporaryPasswordGenerator
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.common.PageOf
import com.acme.b2b.domain.customer.*
import com.acme.b2b.types.*

/**
 * In-memory stand-ins for the ports. Hand-written rather than mocked: they behave like
 * the real thing (a save assigns an id, a duplicate email is visible), so the tests
 * exercise the use case rather than asserting on call sequences.
 *
 * That these exist at all is the payoff of the port interfaces — no database, no Spring
 * context, no mocking framework.
 */

class InMemoryCustomerRepository(seed: List<Customer> = emptyList()) : CustomerRepository {
    private val rows = mutableMapOf<Long, Customer>()
    private var nextId = 1L

    init { seed.forEach { save(it) } }

    override fun findById(id: Long) = rows[id]
    override fun findByEmail(email: Email) = rows.values.firstOrNull { it.email == email }
    override fun existsByEmail(email: Email) = findByEmail(email) != null
    override fun anyOnTier(tierId: TierId) = rows.values.any { it.tierId == tierId }
    override fun countAll() = rows.size.toLong()
    override fun countByStatus(status: CustomerStatus) = rows.values.count { it.status == status }.toLong()

    override fun search(criteria: CustomerSearchCriteria, page: Page): PageOf<Customer> {
        val text = criteria.text?.lowercase()
        val matched = rows.values
            .filter { criteria.status == null || it.status == criteria.status }
            .filter {
                text == null ||
                    it.name.lowercase().contains(text) ||
                    it.email.value.contains(text) ||
                    it.companyName.lowercase().contains(text)
            }
            .sortedBy { it.id }
        return PageOf.of(matched, page)
    }

    override fun save(customer: Customer): Customer {
        val id = customer.id ?: nextId++
        val stored = Customer(
            id, customer.email, customer.passwordHash, customer.name, customer.companyName,
            customer.tierId, customer.phone, customer.status, customer.mustChangePassword,
            customer.createdAt,
        )
        rows[id] = stored
        return stored
    }
}

class InMemoryTierRepository(
    private val tiers: List<CustomerTier> = listOf(
        CustomerTier(TierId(1), "Gold", 1),
        CustomerTier(TierId(2), "Silver", 2),
    ),
) : CustomerTierRepository {
    override fun findById(id: TierId) = tiers.firstOrNull { it.id == id }
    override fun findAll() = tiers
}

class InMemoryAdminRepository(private val admins: List<AdminUser>) : AdminUserRepository {
    override fun findByEmail(email: Email) = admins.firstOrNull { it.email == email }
    override fun findById(id: Long) = admins.firstOrNull { it.id == id }
}

/** Reversible stand-in for BCrypt — fast, and lets a test assert on what was hashed. */
class FakePasswordHasher : PasswordHasher {
    override fun hash(raw: RawPassword) = PasswordHash("hashed:${raw.value}")
    override fun matches(raw: RawPassword, hash: PasswordHash) = hash.value == "hashed:${raw.value}"
}

class FixedTemporaryPasswordGenerator(private val value: String = "TempPass1234") : TemporaryPasswordGenerator {
    override fun generate() = RawPassword(value)
}

class FakeTokenIssuer : AccessTokenIssuer {
    override fun issueForAdmin(adminId: Long, email: String, role: String) = "admin-token:$adminId:$role"
    override fun issueForDealer(customerId: Long, email: String, tierId: Long) = "dealer-token:$customerId:$tierId"
    override fun issuePasswordChangeToken(customerId: Long, email: String) = "pwchange-token:$customerId"
}
