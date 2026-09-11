package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.AdminLoginResponse
import com.acme.b2b.domain.auth.AccessTokenIssuer
import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.domain.admin.AdminUserRepository
import com.acme.b2b.domain.auth.PasswordHasher
import com.acme.b2b.types.Email
import com.acme.b2b.types.RawPassword
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminAuthService(
    private val admins: AdminUserRepository,
    private val passwordHasher: PasswordHasher,
    private val tokens: AccessTokenIssuer,
) {

    /**
     * Every failure path returns the same message. Distinguishing "no such account"
     * from "wrong password" tells an attacker which emails are registered, and the
     * admin gains nothing from the difference.
     */
    fun login(command: AdminLoginCommand): AdminLoginResponse {
        val email = runCatching { Email.of(command.email) }.getOrNull()
            ?: throw AuthenticationFailed(INVALID_CREDENTIALS)
        val password = runCatching { RawPassword(command.password) }.getOrNull()
            ?: throw AuthenticationFailed(INVALID_CREDENTIALS)

        val admin = admins.findByEmail(email) ?: throw AuthenticationFailed(INVALID_CREDENTIALS)
        if (!passwordHasher.matches(password, admin.passwordHash)) {
            throw AuthenticationFailed(INVALID_CREDENTIALS)
        }

        val id = checkNotNull(admin.id) { "A persisted admin must have an id" }
        // An admin still on a password somebody else generated gets a token that reaches
        // only the change-password endpoint, so the forced change does not depend on the
        // client choosing to honour a flag.
        val token =
            if (admin.mustChangePassword) tokens.issueAdminPasswordChangeToken(id, admin.email.value)
            else tokens.issueForAdmin(id, admin.email.value, admin.role.name)

        return AdminLoginResponse(token = token, admin = AdminAssembler.toDTO(admin))
    }

    private companion object {
        const val INVALID_CREDENTIALS = "Invalid email or password"
    }
}
