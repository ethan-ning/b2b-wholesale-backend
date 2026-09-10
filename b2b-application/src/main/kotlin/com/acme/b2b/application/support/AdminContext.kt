package com.acme.b2b.application.support

/**
 * Which admin is asking. A port for the same reason [DealerContext] is one: the
 * application layer needs the caller's identity to decide what they may do, and must
 * not know how that identity was established.
 *
 * Only the id travels. Role is deliberately not taken from here — it is read from the
 * database at the point of the check, so an admin demoted after their token was issued
 * loses the privilege immediately rather than at expiry.
 */
interface AdminContext {
    fun currentAdminId(): Long?
}
