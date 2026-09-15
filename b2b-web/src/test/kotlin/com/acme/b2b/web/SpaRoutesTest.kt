package com.acme.b2b.web

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The invariant that broke in production: a path the client-side router owns must be both
 * forwarded to index.html and permitted without a token. It was permitted but not
 * forwarded, so /reset-password answered 401 to every dealer following their reset email.
 *
 * Deriving both lists from [SpaRoutes] makes that specific drift impossible; these assert
 * the derivation stays that way, and that the pages someone signed out has to reach are
 * actually on the list.
 */
class SpaRoutesTest {

    @Test
    fun `every client route is also a public GET`() {
        val public = SpaRoutes.PUBLIC_GETS.toSet()
        val missing = SpaRoutes.CLIENT_ROUTES.filterNot { it in public }
        assertTrue(missing.isEmpty(), "Client routes not permitted without a token: $missing")
    }

    /**
     * Each of these is reachable only by someone who cannot sign in. One missing is a 401
     * on a page that must be public, which is exactly how the reset link failed.
     */
    @Test
    fun `the routes a signed-out visitor must reach are all present`() {
        val required = listOf("/login", "/forgot-password", "/reset-password", "/change-password")
        val missing = required.filterNot { it in SpaRoutes.CLIENT_ROUTES }
        assertTrue(missing.isEmpty(), "Signed-out routes missing from SpaRoutes: $missing")
    }

    /** The admin equivalents are covered by the wildcard rather than listed one by one. */
    @Test
    fun `admin paths are covered by a single wildcard`() {
        assertTrue("/admin/**" in SpaRoutes.CLIENT_ROUTES)
    }

    @Test
    fun `static asset paths are public but never forwarded to index`() {
        val forwarded = SpaRoutes.CLIENT_ROUTES.toSet()
        val alsoForwarded = SpaRoutes.STATIC_PATHS.filter { it in forwarded }
        // Forwarding /assets/** to index.html would serve HTML in place of every script.
        assertTrue(alsoForwarded.isEmpty(), "Static paths must not be forwarded: $alsoForwarded")
    }
}
