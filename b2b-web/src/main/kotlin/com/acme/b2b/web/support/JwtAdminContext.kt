package com.acme.b2b.web.support

import com.acme.b2b.application.support.AdminContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

/**
 * Adapter for the AdminContext port, reading the verified token's subject.
 *
 * Only the id is taken. The token also carries a role claim, but reading privilege from
 * it would mean a demoted admin keeps their powers until the token expires — so the
 * service loads the role from the database instead.
 */
@Component
class JwtAdminContext : AdminContext {

    override fun currentAdminId(): Long? =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)
            ?.token
            ?.takeIf { it.isAdmin() }
            ?.subject
            ?.toLongOrNull()

    private fun Jwt.isAdmin(): Boolean = getClaim<Any>("scope")?.toString() == "ADMIN"
}
