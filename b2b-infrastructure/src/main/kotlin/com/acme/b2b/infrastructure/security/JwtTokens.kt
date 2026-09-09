package com.acme.b2b.infrastructure.security

import com.acme.b2b.domain.auth.AccessTokenIssuer
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import javax.crypto.spec.SecretKeySpec

/**
 * Both halves of the token mechanism live here, deliberately: the algorithm and the key
 * are stated once, so signing and verification cannot drift apart. They were previously
 * split across two modules, each choosing HS256 independently — changing one would have
 * left the other happily validating the old scheme, with nothing failing to compile.
 *
 * Symmetric signing is adequate while one service both issues and verifies. Moving to
 * asymmetric keys is a change to this file alone.
 */
private const val ALGORITHM = "HmacSHA256"

@Component
class JwtAccessTokenIssuer(
    @Value("\${security.jwt.secret}") private val secret: String,
    @Value("\${security.jwt.ttl-minutes:480}") private val ttlMinutes: Long,
) : AccessTokenIssuer {

    private val signer = MACSigner(secret.toByteArray())

    override fun issueForAdmin(adminId: Long, email: String, role: String): String =
        sign(
            JWTClaimsSet.Builder()
                .subject(adminId.toString())
                .claim("email", email)
                .claim("scope", "ADMIN")
                .claim("role", role)
        )

    /**
     * The dealer's tier travels in the token so pricing a catalog request needs no
     * customer lookup. The cost is that a tier change only takes effect on next login.
     */
    override fun issueForDealer(customerId: Long, email: String, tierId: Long): String =
        sign(
            JWTClaimsSet.Builder()
                .subject(customerId.toString())
                .claim("email", email)
                .claim("scope", "DEALER")
                .claim("tierId", tierId)
        )

    private fun sign(claims: JWTClaimsSet.Builder): String {
        val now = Instant.now()
        val jwt = SignedJWT(
            JWSHeader(JWSAlgorithm.HS256),
            claims
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlMinutes * 60)))
                .build(),
        )
        jwt.sign(signer)
        return jwt.serialize()
    }
}

/**
 * The verification half. Spring Security's filter chain uses this decoder; the web
 * layer states which routes need which authority without knowing how a token is read.
 */
@Configuration
class JwtDecoderConfig {

    @Bean
    fun jwtDecoder(@Value("\${security.jwt.secret}") secret: String): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(SecretKeySpec(secret.toByteArray(), ALGORITHM)).build()
}
