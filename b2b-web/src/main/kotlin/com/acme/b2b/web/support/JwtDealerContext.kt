package com.acme.b2b.web.support

import com.acme.b2b.application.support.DealerContext
import com.acme.b2b.types.TierId
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * Adapter for the DealerContext port, reading the verified token.
 *
 * The tier travels in the token, so pricing a catalog request costs no lookup. The
 * trade is that a tier change only reaches a dealer at their next login — acceptable
 * while tiers change rarely and by an admin's deliberate act.
 *
 * An admin browsing the dealer catalog has no tier of their own, so they see the least
 * generous one. Better a wrong-but-conservative price than a crash, and better than
 * silently showing them the best tier.
 */
@Component
class JwtDealerContext : DealerContext {

    override fun currentTierId(): TierId =
        jwt()?.getClaim<Any>("tierId")?.let { TierId((it as Number).toLong()) } ?: FALLBACK_TIER

    override fun currentCustomerId(): Long? = jwt()?.subject?.toLongOrNull()

    private fun jwt(): Jwt? =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token

    private companion object {
        /** Silver. Used only for a token with no tier claim, i.e. an admin. */
        val FALLBACK_TIER = TierId(2)
    }
}
