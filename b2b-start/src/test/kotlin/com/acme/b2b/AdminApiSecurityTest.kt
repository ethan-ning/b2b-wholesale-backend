package com.acme.b2b

import com.acme.b2b.application.admin.AdminAuthService
import com.acme.b2b.application.admin.CustomerAdminService
import com.acme.b2b.application.admin.dto.AdminLoginResponse
import com.acme.b2b.application.admin.dto.AdminUserDTO
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.config.SecurityConfig
import com.acme.b2b.infrastructure.security.JwtAccessTokenIssuer
import com.acme.b2b.web.admin.AdminAuthController
import com.acme.b2b.web.admin.AdminCustomerController
import com.acme.b2b.web.support.ApiExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The security rules, exercised through the real filter chain with real signed tokens.
 * No database: the application services are mocked, because what is under test is who
 * may reach an endpoint, not what the endpoint computes.
 */
@WebMvcTest(controllers = [AdminAuthController::class, AdminCustomerController::class])
@Import(SecurityConfig::class, ApiExceptionHandler::class)
@TestPropertySource(properties = ["security.jwt.secret=test-secret-that-is-at-least-32-bytes-long"])
class AdminApiSecurityTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @MockitoBean private lateinit var adminAuth: AdminAuthService
    @MockitoBean private lateinit var customers: CustomerAdminService

    private val tokens = JwtAccessTokenIssuer("test-secret-that-is-at-least-32-bytes-long", 60)
    private val adminToken = tokens.issueForAdmin(1, "admin@example.com", "SUPER_ADMIN")
    private val dealerToken = tokens.issueForDealer(7, "dealer@example.com", 1)

    @Test
    fun `login is reachable without a token`() {
        whenever(adminAuth.login(org.mockito.kotlin.any())).thenReturn(
            AdminLoginResponse("a-token", AdminUserDTO(1, "admin@example.com", "System Admin", "SUPER_ADMIN"))
        )

        mockMvc.perform(
            post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"admin@example.com","password":"admin123"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value("a-token"))
            .andExpect(jsonPath("$.admin.role").value("SUPER_ADMIN"))
    }

    @Test
    fun `admin routes reject an anonymous request`() {
        mockMvc.perform(get("/api/admin/customers")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `admin routes reject a dealer's token`() {
        // The claim that matters most here: a dealer session cannot reach the back office.
        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $dealerToken"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin routes accept an admin's token`() {
        whenever(customers.list(org.mockito.kotlin.any()))
            .thenReturn(PagedDTO(emptyList(), 0, 0, 0, 10))

        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk)
    }

    @Test
    fun `a token signed with the wrong key is rejected`() {
        val forged = JwtAccessTokenIssuer("a-completely-different-secret-32-bytes!!", 60)
            .issueForAdmin(1, "admin@example.com", "SUPER_ADMIN")

        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $forged"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `an expired token is rejected`() {
        val expired = JwtAccessTokenIssuer("test-secret-that-is-at-least-32-bytes-long", -1)
            .issueForAdmin(1, "admin@example.com", "SUPER_ADMIN")

        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $expired"))
            .andExpect(status().isUnauthorized)
    }
}
