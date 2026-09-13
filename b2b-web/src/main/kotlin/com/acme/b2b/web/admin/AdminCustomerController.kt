package com.acme.b2b.web.admin

import com.acme.b2b.application.admin.*
import com.acme.b2b.application.admin.dto.CustomerCreatedDTO
import com.acme.b2b.application.admin.dto.CustomerDTO
import com.acme.b2b.application.admin.dto.CustomerTierDTO
import com.acme.b2b.application.catalog.dto.PagedDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Dealer account management. Paths and payloads match the portal's api/adminApi.ts.
 *
 * Adaptation only: the controller turns request parameters into a command and returns
 * what the use case produced. Nothing here decides anything.
 */
@RestController
@RequestMapping("/api/admin")
class AdminCustomerController(
    private val customers: CustomerAdminService,
) {

    @GetMapping("/customers")
    fun list(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ): PagedDTO<CustomerDTO> = customers.list(CustomerQuery(search, status, page, size))

    @GetMapping("/customers/{id}")
    fun byId(@PathVariable id: Long): ResponseEntity<CustomerDTO> =
        customers.findById(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    /** 201 with the generated password — the only time it is ever returned. */
    @PostMapping("/customers")
    fun create(@RequestBody request: CreateCustomerCommand): ResponseEntity<CustomerCreatedDTO> =
        ResponseEntity.status(201).body(customers.create(request))

    @PutMapping("/customers/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: UpdateCustomerCommand): CustomerDTO =
        customers.update(id, request)

    @PostMapping("/customers/{id}/reset-password")
    fun resetPassword(@PathVariable id: Long): CustomerCreatedDTO = customers.resetPassword(id)

    @GetMapping("/tiers")
    fun tiers(): List<CustomerTierDTO> = customers.tiers()

    /** Retunes a tier. Every SKU nobody has quoted separately moves with it. */
    @PutMapping("/tiers/{id}/discount")
    fun setTierDiscount(
        @PathVariable id: Long,
        @RequestBody body: TierDiscountRequest,
    ): CustomerTierDTO = customers.setTierDiscount(id, body.discountPercent)
}

data class TierDiscountRequest(val discountPercent: java.math.BigDecimal)
