package com.acme.b2b.web.admin

import com.acme.b2b.application.admin.AdminAccountService
import com.acme.b2b.application.admin.ChangeAdminPasswordCommand
import com.acme.b2b.application.admin.CreateAdminCommand
import com.acme.b2b.application.admin.dto.AdminCreatedDTO
import com.acme.b2b.application.admin.dto.AdminUserDTO
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Admin accounts. Who may do what is decided by the use case, not here — the rule
 * depends on the caller's stored role, which this layer has no business loading.
 */
@RestController
@RequestMapping("/api/admin")
class AdminAccountController(
    private val accounts: AdminAccountService,
) {

    @PostMapping("/auth/change-password")
    fun changeOwnPassword(@RequestBody request: ChangeAdminPasswordCommand): AdminUserDTO =
        accounts.changeOwnPassword(request)

    @GetMapping("/admins")
    fun list(): List<AdminUserDTO> = accounts.list()

    /** 201 with the generated password — the only time it is ever returned. */
    @PostMapping("/admins")
    fun create(@RequestBody request: CreateAdminCommand): ResponseEntity<AdminCreatedDTO> =
        ResponseEntity.status(201).body(accounts.create(request))

    @DeleteMapping("/admins/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        accounts.delete(id)
        return ResponseEntity.noContent().build()
    }
}
