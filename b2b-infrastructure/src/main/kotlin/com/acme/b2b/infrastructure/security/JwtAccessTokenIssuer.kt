package com.acme.b2b.infrastructure.security

import com.acme.b2b.domain.auth.AccessTokenIssuer
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date

/**
 * Issues the HS256 tokens the resource server validates. Symmetric signing is adequate
 * while one service both issues and verifies; move to asymmetric keys when a second
 * service needs to verify without being able to mint.
 *
 * The dealer's tier travels in the token so pricing a catalog request does not need a
 * customer lookup — which also means a tier change only takes effect on next login.
 */
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
