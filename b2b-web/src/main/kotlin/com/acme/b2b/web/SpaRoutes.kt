package com.acme.b2b.web

// The paths the client-side router owns.
//
// Two places need this list and they have to agree: SinglePageAppRouting forwards these to
// index.html, and WebSecurityConfig permits them without a token. Stated once because
// stating it twice has already gone wrong — /forgot-password and /reset-password were added
// to the security rules but not to the forwarding, so the page a dealer reached from their
// reset email answered 401, while the admin equivalent worked only because /admin/** was
// already covered.
//
// A route missing here is not a 404. It falls through to anyRequest(), which demands a
// token the visitor does not have, so the failure surfaces as an unexplained 401 on a page
// that plainly should be public.
//
// Deliberately not a catch-all: a genuine typo under /api should still be a 404 rather than
// a page, and so should a missing asset.
//
// Line comments, not KDoc: Kotlin nests block comments, and a wildcard path contains the
// characters that open one — which silently swallows the rest of the file.
object SpaRoutes {

    val CLIENT_ROUTES: List<String> = listOf(
        "/login",
        "/change-password",
        "/forgot-password",
        "/reset-password",
        "/search",
        "/products/**",
        "/admin/**",
    )

    // Files the bundler emits or copies through, served as themselves rather than forwarded.
    val STATIC_PATHS: List<String> = listOf(
        "/", "/index.html", "/favicon.svg", "/assets/**", "/brand/**", "/local-images/**",
    )

    // Everything a browser may GET without a token.
    val PUBLIC_GETS: Array<String> = (STATIC_PATHS + CLIENT_ROUTES).toTypedArray()
}
