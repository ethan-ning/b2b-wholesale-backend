package com.acme.b2b.application.support

/**
 * Which admin is asking. A port for the same reason [DealerContext] is one: the caller's
 * identity decides what they may do, and how it was established is not this layer's
 * business.
 *
 * Only the id travels; role is read from the database at the point of the check.
 */
interface AdminContext {
    fun currentAdminId(): Long?
}
