package com.acme.b2b.web.support

import com.acme.b2b.application.support.DealerContext
import com.acme.b2b.types.TierId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * Adapter for the DealerContext port.
 *
 * INTERIM: reads the tier from a request header so the catalog slice can be exercised
 * before auth exists. Replace with a JwtDealerContext that reads the `tierId` claim —
 * that is a change to this class only, since nothing above the port knows how identity
 * is established.
 */
@Component
class HeaderDealerContext(
    private val request: HttpServletRequest,
) : DealerContext {

    override fun currentTierId(): TierId =
        request.getHeader(TIER_HEADER)?.toLongOrNull()?.let { TierId(it) } ?: DEFAULT_TIER

    override fun currentCustomerId(): Long? =
        request.getHeader(CUSTOMER_HEADER)?.toLongOrNull()

    companion object {
        const val TIER_HEADER = "X-Dealer-Tier"
        const val CUSTOMER_HEADER = "X-Dealer-Id"
        private val DEFAULT_TIER = TierId(2) // Silver — the least generous tier
    }
}
