package com.acme.b2b.web.security

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
class WebSecurityConfig {

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
                    .requestMatchers("/actuator/health").permitAll()
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

    /** The Vite dev server, so the portal can run against a local backend. */
    private fun corsConfigurationSource(): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration(
                "/api/**",
                CorsConfiguration().apply {
                    allowedOrigins = listOf("http://localhost:5173")
                    allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    allowedHeaders = listOf("*")
                },
            )
        }
}
