package com.acme.b2b.web.security

import com.acme.b2b.application.admin.AdminAccountService
import com.acme.b2b.application.admin.ImageAdminService
import com.acme.b2b.application.admin.AdminAuthService
import com.acme.b2b.application.admin.CategoryAdminService
import com.acme.b2b.application.admin.CustomerAdminService
import com.acme.b2b.application.admin.DashboardService
import com.acme.b2b.application.admin.InventoryQueryService
import com.acme.b2b.application.admin.ProductAdminService
import com.acme.b2b.application.admin.dto.AdminLoginResponse
import com.acme.b2b.application.admin.dto.AdminUserDTO
import com.acme.b2b.application.catalog.CatalogQueryService
import com.acme.b2b.application.dealer.DealerAuthService
import com.acme.b2b.web.support.ApiExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * A deployment serves the portal from its own domain, so that domain has to be an allowed
 * origin or every sign-in fails with 403 while every page still loads.
 *
 * Separate class because the origin list is read once at startup, and this needs a
 * different one from the dev default the sibling test relies on.
 */
@WebMvcTest
@Import(
    WebSecurityConfig::class,
    ApiExceptionHandler::class,
    com.acme.b2b.web.support.JwtDealerContext::class,
)
@TestPropertySource(properties = ["app.cors.allowed-origins=https://portal.example.com"])
class DeployedOriginCorsTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var adminAuth: AdminAuthService

    // The whole routing table loads, so every controller's dependencies must resolve.
    @MockitoBean private lateinit var customers: CustomerAdminService
    @MockitoBean private lateinit var adminAccounts: AdminAccountService
    @MockitoBean private lateinit var imageAdmin: ImageAdminService
    @MockitoBean private lateinit var catalog: CatalogQueryService
    @MockitoBean private lateinit var productAdmin: ProductAdminService
    @MockitoBean private lateinit var categoryAdmin: CategoryAdminService
    @MockitoBean private lateinit var inventoryQuery: InventoryQueryService
    @MockitoBean private lateinit var dashboard: DashboardService
    @MockitoBean private lateinit var dealerAuth: DealerAuthService
    @MockitoBean private lateinit var sellfoxAdmin: com.acme.b2b.application.sellfox.SellfoxAdminService
    @MockitoBean private lateinit var sellfoxSync: com.acme.b2b.application.sellfox.SellfoxSyncService

    private fun signInFrom(origin: String) =
        mockMvc.perform(
            post("/api/admin/auth/login")
                .header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"admin@example.com","password":"whatever"}""")
        )

    @Test
    fun `the configured deployment origin can sign in`() {
        whenever(adminAuth.login(any())).thenReturn(
            AdminLoginResponse("a-token", AdminUserDTO(1, "admin@example.com", "Admin", "SUPER_ADMIN"))
        )

        signInFrom("https://portal.example.com").andExpect(status().isOk)
    }

    @Test
    fun `configuring one origin does not open the door to others`() {
        signInFrom("https://somewhere-else.example.com").andExpect(status().isForbidden)
    }
}
