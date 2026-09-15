package com.acme.b2b.web.security

import com.acme.b2b.web.SpaRoutes
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Who may reach which route. This lives in the web module because the rules describe
 * this module's own endpoints — a controller added here and a rule protecting it should
 * not be two modules apart.
 *
 * It states no opinion on how a token is read: the JwtDecoder is injected, and its
 * algorithm and key live with the issuer in the infrastructure module. Verification
 * itself runs in Spring Security's filter chain, ahead of every controller, so no
 * controller and nothing below it ever handles a token.
 */
@Configuration
@EnableWebSecurity
class WebSecurityConfig(
    /**
     * Origins allowed to call /api — the Vite dev server by default, deployments add their
     * own. A browser sends Origin on POST even same-host, so an origin missing here fails
     * every form submission with 403 while GETs, which carry none, keep working and hide it.
     */
    @Value("\${app.cors.allowed-origins:http://localhost:5173}")
    private val allowedOrigins: List<String>,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain =
        http
            .csrf { it.disable() }  // no cookies: the token is sent explicitly
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/auth/login", "/api/admin/auth/login").permitAll()
                    // Forgotten-password endpoints cannot require a token: whoever needs
                    // them is precisely the person unable to obtain one. Neither reveals
                    // whether an account exists, and the reset link itself is the
                    // credential — see PasswordResetService.
                    .requestMatchers(
                        "/api/auth/forgot-password", "/api/auth/reset-password",
                        "/api/admin/auth/forgot-password", "/api/admin/auth/reset-password",
                    ).permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    // The single-page app: its files and its routes, because a browser
                    // opening /admin/login has no token yet and the page it needs in order
                    // to get one must not 401. Nothing is given away — these serve the same
                    // index.html to everyone, and the data behind them still comes from
                    // /api, which is not on this list. /assets/** is what the bundler emits,
                    // /brand/** what it copies through untouched.
                    .requestMatchers(HttpMethod.GET, *SpaRoutes.PUBLIC_GETS).permitAll()
                    // Anyone still on a password somebody else generated holds a token whose
                    // only reachable endpoint is their own change-password one. That is what
                    // makes the forced change enforced rather than asked of the client.
                    //
                    // Two scopes, not one: a dealer's restricted token must not reach the
                    // admin endpoint, nor an admin's the dealer one. This rule comes before
                    // /api/admin/**, which would otherwise demand a full admin scope.
                    .requestMatchers("/api/auth/change-password")
                        .hasAnyAuthority("SCOPE_DEALER", "SCOPE_PASSWORD_CHANGE")
                    .requestMatchers("/api/admin/auth/change-password")
                        .hasAnyAuthority("SCOPE_ADMIN", "SCOPE_ADMIN_PASSWORD_CHANGE")
                    // The rule that matters most: a dealer token must not reach the
                    // back office. Enforced by scope, not by role or by path alone.
                    .requestMatchers("/api/admin/**").hasAuthority("SCOPE_ADMIN")
                    .anyRequest().hasAnyAuthority("SCOPE_ADMIN", "SCOPE_DEALER")
            }
            .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(authoritiesConverter()) } }
            .build()

    /** Maps our single-valued `scope` claim onto SCOPE_ADMIN / SCOPE_DEALER. */
    private fun authoritiesConverter(): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(
                JwtGrantedAuthoritiesConverter().apply {
                    setAuthorityPrefix("SCOPE_")
                    setAuthoritiesClaimName("scope")
                }
            )
        }

    private fun corsConfigurationSource(): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration(
                "/api/**",
                CorsConfiguration().apply {
                    allowedOrigins = this@WebSecurityConfig.allowedOrigins
                    allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    allowedHeaders = listOf("*")
                },
            )
        }
}
