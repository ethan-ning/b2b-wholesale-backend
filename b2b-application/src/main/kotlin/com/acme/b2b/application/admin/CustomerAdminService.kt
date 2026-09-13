package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.CustomerCreatedDTO
import com.acme.b2b.application.admin.dto.CustomerDTO
import com.acme.b2b.application.admin.dto.CustomerTierDTO
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.auth.PasswordHasher
import com.acme.b2b.domain.auth.TemporaryPasswordGenerator
import com.acme.b2b.domain.common.Page
import com.acme.b2b.domain.customer.*
import com.acme.b2b.types.Email
import com.acme.b2b.types.TierId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.acme.b2b.types.DiscountPercent
import java.math.BigDecimal

/**
 * Dealer account management. Orchestration only: Customer owns its own lifecycle rules
 * and the hasher owns the algorithm, so what is left here is loading, checking
 * preconditions the entity cannot see on its own, and saving.
 */
@Service
@Transactional(readOnly = true)
class CustomerAdminService(
    private val customers: CustomerRepository,
    private val tiers: CustomerTierRepository,
    private val passwordHasher: PasswordHasher,
    private val temporaryPasswords: TemporaryPasswordGenerator,
) {

    fun list(query: CustomerQuery): PagedDTO<CustomerDTO> {
        val criteria = CustomerSearchCriteria(
            text = query.search?.takeIf { it.isNotBlank() },
            status = query.status?.takeIf { it.isNotBlank() }?.let { parseStatus(it) },
        )
        val page = customers.search(criteria, Page(query.page, query.size))
        val tierNames = tierNames()

        return PagedDTO(
            content = page.content.map { AdminAssembler.toDTO(it, tierNames) },
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            page = page.page.number,
            size = page.page.size,
        )
    }

    fun findById(id: Long): CustomerDTO? =
        customers.findById(id)?.let { AdminAssembler.toDTO(it, tierNames()) }

    fun tiers(): List<CustomerTierDTO> = tiers.findAll().map { AdminAssembler.toDTO(it) }

    /**
     * Retunes what a tier pays.
     *
     * Takes effect on every SKU nobody has quoted separately, which is most of the
     * catalogue — so it is the one field here that changes prices in bulk, and the
     * refusal below is deliberately about the number rather than about permission.
     */
    @Transactional
    fun setTierDiscount(tierId: Long, percent: BigDecimal): CustomerTierDTO {
        val discount = runCatching { DiscountPercent.of(percent) }
            .getOrElse { throw UseCaseViolation(it.message ?: "That is not a usable discount") }
        val tier = tiers.findById(TierId(tierId))
            ?: throw NoSuchElementException("No such tier")
        return AdminAssembler.toDTO(tiers.updateDiscount(tier.id, discount))
    }

    /**
     * Creates the dealer with a generated temporary password, returned once in the
     * response. Only the hash is stored, so there is no way to retrieve it later.
     */
    @Transactional
    fun create(command: CreateCustomerCommand): CustomerCreatedDTO {
        val email = Email.of(command.email)
        if (customers.existsByEmail(email)) {
            throw UseCaseViolation("A dealer with email ${email.value} already exists")
        }
        val tierId = requireTier(command.tierId)

        val temporary = temporaryPasswords.generate()
        val customer = Customer.create(
            email = email,
            passwordHash = passwordHasher.hash(temporary),
            name = command.name,
            companyName = command.companyName,
            tierId = tierId,
            phone = command.phone,
        )

        val saved = customers.save(customer)
        return CustomerCreatedDTO(
            customer = AdminAssembler.toDTO(saved, tierNames()),
            temporaryPassword = temporary.value,
        )
    }

    @Transactional
    fun update(id: Long, command: UpdateCustomerCommand): CustomerDTO {
        val existing = customers.findById(id)
            ?: throw NoSuchElementException("No dealer with id $id")

        var updated = existing.withProfile(
            name = command.name,
            companyName = command.companyName,
            tierId = requireTier(command.tierId),
            phone = command.phone,
        )
        command.status?.let { updated = updated.withStatus(parseStatus(it)) }

        return AdminAssembler.toDTO(customers.save(updated), tierNames())
    }

    /**
     * Issues a fresh temporary password and forces a change at next login. Returned
     * once, for the same reason as on creation.
     */
    @Transactional
    fun resetPassword(id: Long): CustomerCreatedDTO {
        val existing = customers.findById(id)
            ?: throw NoSuchElementException("No dealer with id $id")

        val temporary = temporaryPasswords.generate()
        val saved = customers.save(existing.withResetPassword(passwordHasher.hash(temporary)))

        return CustomerCreatedDTO(
            customer = AdminAssembler.toDTO(saved, tierNames()),
            temporaryPassword = temporary.value,
        )
    }

    private fun requireTier(tierId: Long): TierId {
        val id = TierId(tierId)
        tiers.findById(id) ?: throw UseCaseViolation("No such pricing tier: $tierId")
        return id
    }

    private fun parseStatus(raw: String): CustomerStatus =
        runCatching { CustomerStatus.valueOf(raw.uppercase()) }
            .getOrElse { throw UseCaseViolation("Unknown customer status: $raw") }

    private fun tierNames(): Map<Long, String> =
        tiers.findAll().associate { it.id.value to it.name }
}
