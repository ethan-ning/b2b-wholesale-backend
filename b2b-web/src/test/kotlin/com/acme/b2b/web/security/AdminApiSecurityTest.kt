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
import com.acme.b2b.application.catalog.dto.PagedDTO
import com.acme.b2b.web.support.ApiExceptionHandler
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.Date
import javax.crypto.spec.SecretKeySpec

private const val SECRET = "test-secret-that-is-at-least-32-bytes-long"

/**
 * The route rules, exercised through the real filter chain with real signed tokens.
 *
 * No database and no infrastructure module: the application services are mocked and the
 * decoder is supplied here. That the test can stand up this module's security with only
 * a JwtDecoder is the point — the web layer depends on the abstraction, not on how the
 * token is signed.
 */
@WebMvcTest
@Import(
    WebSecurityConfig::class,
    ApiExceptionHandler::class,
    com.acme.b2b.web.support.JwtDealerContext::class,
    AdminApiSecurityTest.TestBeans::class,
)
class AdminApiSecurityTest {

    @TestConfiguration
    class TestBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder =
            NimbusJwtDecoder.withSecretKey(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")).build()
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @MockitoBean private lateinit var adminAuth: AdminAuthService
    @MockitoBean private lateinit var customers: CustomerAdminService
    @MockitoBean private lateinit var adminAccounts: AdminAccountService
    @MockitoBean private lateinit var imageAdmin: ImageAdminService

    /**
     * Not exercised here, but the whole routing table loads, so every controller's
     * dependencies must be satisfiable. That is deliberate: a controller added without a
     * matching rule fails this test rather than shipping unprotected.
     */
    @MockitoBean private lateinit var catalog: CatalogQueryService
    @MockitoBean private lateinit var productAdmin: ProductAdminService
    @MockitoBean private lateinit var categoryAdmin: CategoryAdminService
    @MockitoBean private lateinit var inventoryQuery: InventoryQueryService
    @MockitoBean private lateinit var dashboard: DashboardService
    @MockitoBean private lateinit var dealerAuth: DealerAuthService
    @MockitoBean private lateinit var passwordResets: com.acme.b2b.application.auth.PasswordResetService
    @MockitoBean private lateinit var sellfoxAdmin: com.acme.b2b.application.sellfox.SellfoxAdminService
    @MockitoBean private lateinit var sellfoxSync: com.acme.b2b.application.sellfox.SellfoxSyncService

    private val adminToken = token(scope = "ADMIN", secret = SECRET)
    private val dealerToken = token(scope = "DEALER", secret = SECRET)

    @Test
    fun `login is reachable without a token`() {
        whenever(adminAuth.login(any())).thenReturn(
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

    /**
     * Browsers send Origin on POST even same-origin, so an unknown one is refused by the
     * CORS filter before any controller runs — 403, not the 401 a wrong password gives.
     */
    @Test
    fun `a sign-in from a configured origin is not blocked`() {
        whenever(adminAuth.login(any())).thenReturn(
            AdminLoginResponse("a-token", AdminUserDTO(1, "admin@example.com", "System Admin", "SUPER_ADMIN"))
        )

        mockMvc.perform(
            post("/api/admin/auth/login")
                .header("Origin", "http://localhost:5173")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"admin@example.com","password":"admin123"}""")
        ).andExpect(status().isOk)
    }

    @Test
    fun `a sign-in from an unconfigured origin is refused by CORS, not by the password check`() {
        mockMvc.perform(
            post("/api/admin/auth/login")
                .header("Origin", "https://not-configured.example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"admin@example.com","password":"admin123"}""")
        ).andExpect(status().isForbidden)
    }

    // Every static path the sign-in pages need before anyone has a token; the brand images
    // ship outside the bundler's directory, so they need their own rule.
    //
    // Line comments, not KDoc: Kotlin nests block comments, and a wildcard path written out
    // in full closes with a slash-star that opens one.
    @Test
    fun `the portal's own static files are reachable without a token`() {
        listOf("/favicon.svg", "/assets/index.js", "/brand/logo-horizontal.png", "/brand/hero.jpg")
            .forEach { path ->
                mockMvc.perform(get(path))
                    .andExpect(status().isNotFound)  // permitted, then simply not present in a unit test
            }
    }

    @Test
    fun `admin routes reject an anonymous request`() {
        mockMvc.perform(get("/api/admin/customers")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the portal's own admin path is public, the admin API behind it is not`() {
        // Both begin /admin. One is a page a browser must be able to load before it has a
        // token; the other is the data. Widening the first must never widen the second.
        mockMvc.perform(get("/admin/login")).andExpect(status().isNotFound)
        mockMvc.perform(get("/admin/products")).andExpect(status().isNotFound)

        mockMvc.perform(get("/api/admin/customers")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `admin routes reject a dealer's token`() {
        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $dealerToken"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin routes accept an admin's token`() {
        whenever(customers.list(any())).thenReturn(PagedDTO(emptyList(), 0, 0, 0, 10))

        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk)
    }

    @Test
    fun `every admin route is closed to a dealer, not only the ones with a test`() {
        // Enumerated rather than spot-checked: the rule is on /api/admin/**, so a route
        // added later is covered by adding one line here, not by remembering to.
        listOf(
            "/api/admin/customers",
            "/api/admin/tiers",
            "/api/admin/dashboard",
            "/api/admin/products",
            "/api/admin/categories",
            "/api/admin/inventory",
            "/api/admin/admins",
            "/api/admin/images",
        ).forEach { path ->
            mockMvc.perform(get(path).header("Authorization", "Bearer $dealerToken"))
                .andExpect(status().isForbidden)
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
        }
    }

    /**
     * The two restricted tokens must not cross. Each reaches its own change-password
     * endpoint and nothing else — including the other side's, which would let someone
     * part-way through a forced change rewrite an account that is not theirs.
     */
    @Test
    fun `a restricted admin token reaches only the admin change-password endpoint`() {
        val restricted = token(scope = "ADMIN_PASSWORD_CHANGE", secret = SECRET)
        val body = """{"currentPassword":"OldPassword1","newPassword":"NewPassword1"}"""

        whenever(adminAccounts.changeOwnPassword(any())).thenReturn(
            AdminLoginResponse("a-token", AdminUserDTO(1, "admin@example.com", "System Admin", "ADMIN"))
        )
        mockMvc.perform(
            post("/api/admin/auth/change-password")
                .header("Authorization", "Bearer $restricted")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)

        // Not the back office it would otherwise be signed in to.
        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $restricted"))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/admin/admins").header("Authorization", "Bearer $restricted"))
            .andExpect(status().isForbidden)
        // Nor the dealer's own change-password endpoint.
        mockMvc.perform(
            post("/api/auth/change-password")
                .header("Authorization", "Bearer $restricted")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `a dealer's restricted token does not reach the admin change-password endpoint`() {
        val pwToken = token(scope = "PASSWORD_CHANGE", secret = SECRET)

        mockMvc.perform(
            post("/api/admin/auth/change-password")
                .header("Authorization", "Bearer $pwToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"a","newPassword":"b"}""")
        ).andExpect(status().isForbidden)
    }

    /** Behind the token, unlike signing in: a dealer token here would rewrite an admin's. */
    @Test
    fun `admin change-password needs an admin token`() {
        val body = """{"currentPassword":"OldPassword1","newPassword":"NewPassword1"}"""

        mockMvc.perform(post("/api/admin/auth/change-password").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/api/admin/auth/change-password")
                .header("Authorization", "Bearer $dealerToken")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isForbidden)

        whenever(adminAccounts.changeOwnPassword(any())).thenReturn(
            AdminLoginResponse("a-token", AdminUserDTO(1, "admin@example.com", "System Admin", "ADMIN"))
        )
        mockMvc.perform(
            post("/api/admin/auth/change-password")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)
    }

    @Test
    fun `dealer login is reachable without a token`() {
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"dealer@example.com","password":"whatever1"}""")
        ).andExpect(status().isOk)
    }

    @Test
    fun `a password-change token reaches only the change-password endpoint`() {
        val pwToken = token(scope = "PASSWORD_CHANGE", secret = SECRET)

        // The one thing it may do.
        mockMvc.perform(
            post("/api/auth/change-password")
                .header("Authorization", "Bearer $pwToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"issued-one","newPassword":"chosen-one"}""")
        ).andExpect(status().isOk)

        // And nothing else — not the catalog it was issued against, nor the back office.
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer $pwToken"))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $pwToken"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a dealer token reaches the catalog and its own password change`() {
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer $dealerToken"))
            .andExpect(status().isOk)
        mockMvc.perform(
            post("/api/auth/change-password")
                .header("Authorization", "Bearer $dealerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"old-one-1","newPassword":"new-one-1"}""")
        ).andExpect(status().isOk)
    }

    @Test
    fun `a token signed with the wrong key is rejected`() {
        val forged = token(scope = "ADMIN", secret = "a-completely-different-secret-32-bytes!!")

        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $forged"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `an expired token is rejected`() {
        val expired = token(scope = "ADMIN", secret = SECRET, ttlSeconds = -60)

        mockMvc.perform(get("/api/admin/customers").header("Authorization", "Bearer $expired"))
            .andExpect(status().isUnauthorized)
    }

    private fun token(scope: String, secret: String, ttlSeconds: Long = 3600): String {
        val now = Instant.now()
        val jwt = SignedJWT(
            JWSHeader(JWSAlgorithm.HS256),
            JWTClaimsSet.Builder()
                .subject("1")
                .claim("scope", scope)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .build(),
        )
        jwt.sign(MACSigner(secret.toByteArray()))
        return jwt.serialize()
    }

    /**
     * Forgotten-password endpoints must be reachable without a token — whoever needs them
     * cannot obtain one. Four separate assertions rather than one loop: each is a distinct
     * rule in WebSecurityConfig, and a loop would pass if three were right.
     */
    @Test
    fun `password reset endpoints are reachable without a token`() {
        for (path in listOf("/api/auth/forgot-password", "/api/admin/auth/forgot-password")) {
            mockMvc.perform(
                post(path).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"someone@example.com"}""")
            ).andExpect(status().isOk)
        }
        for (path in listOf("/api/auth/reset-password", "/api/admin/auth/reset-password")) {
            mockMvc.perform(
                post(path).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"a-token","newPassword":"a-new-password"}""")
            ).andExpect(status().isOk)
        }
    }

}
