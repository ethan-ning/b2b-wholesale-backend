package com.acme.b2b.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless JWT via Spring Security's resource server — no custom filter.
 *
 * The `scope` claim separates the two audiences: a dealer token cannot reach
 * the admin routes, which is the one thing that must not be got wrong here.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain =
        http
            .csrf { it.disable() }  // no cookies: the token is sent explicitly
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/auth/login", "/api/admin/auth/login").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/api/admin/**").hasAuthority("SCOPE_ADMIN")
                    .anyRequest().hasAnyAuthority("SCOPE_ADMIN", "SCOPE_DEALER")
            }
            .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(authoritiesConverter()) } }
            .build()

    @Bean
    fun jwtDecoder(@Value("\${security.jwt.secret}") secret: String): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(SecretKeySpec(secret.toByteArray(), "HmacSHA256")).build()

    /** Maps our single-valued `scope` claim to SCOPE_ADMIN / SCOPE_DEALER. */
    private fun authoritiesConverter(): JwtAuthenticationConverter =
        JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(
                JwtGrantedAuthoritiesConverter().apply {
                    setAuthorityPrefix("SCOPE_")
                    setAuthoritiesClaimName("scope")
                }
            )
        }
}
