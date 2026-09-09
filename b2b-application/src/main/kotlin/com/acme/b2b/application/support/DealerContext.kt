package com.acme.b2b.application.support

import com.acme.b2b.types.TierId

/**
 * Who is asking. A port, not a Spring Security dependency: the application layer needs
 * the dealer's tier to price a lookup, but must not know how identity was established.
 *
 * The web layer supplies the implementation. Swapping the current header-based adapter
 * for JWT touches only that adapter.
 */
interface DealerContext {
    fun currentTierId(): TierId
    fun currentCustomerId(): Long?
}
