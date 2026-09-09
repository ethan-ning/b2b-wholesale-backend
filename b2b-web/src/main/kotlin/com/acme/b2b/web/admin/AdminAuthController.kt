package com.acme.b2b.web.admin

import com.acme.b2b.application.admin.AdminAuthService
import com.acme.b2b.application.admin.AdminLoginCommand
import com.acme.b2b.application.admin.dto.AdminLoginResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/auth")
class AdminAuthController(
    private val auth: AdminAuthService,
) {
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): AdminLoginResponse =
        auth.login(AdminLoginCommand(request.email, request.password))
}

data class LoginRequest(val email: String, val password: String)
