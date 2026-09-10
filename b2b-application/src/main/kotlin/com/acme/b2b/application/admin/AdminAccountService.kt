package com.acme.b2b.application.admin

import com.acme.b2b.application.admin.dto.AdminCreatedDTO
import com.acme.b2b.application.admin.dto.AdminUserDTO
import com.acme.b2b.application.support.AdminContext
import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.application.support.NotPermitted
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.admin.AdminRole
import com.acme.b2b.domain.admin.AdminUser
import com.acme.b2b.domain.admin.AdminUserRepository
import com.acme.b2b.domain.auth.PasswordHasher
import com.acme.b2b.domain.auth.TemporaryPasswordGenerator
import com.acme.b2b.types.Email
import com.acme.b2b.types.RawPassword
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Admin accounts: their own password, and — for a super admin — the roster.
 *
 * Every privilege check loads the acting admin from the database rather than trusting
 * the role claim in their token. A token outlives a demotion, and the window between
 * the two is exactly when the check matters most.
 */
@Service
@Transactional(readOnly = true)
class AdminAccountService(
    private val admins: AdminUserRepository,
    private val passwordHasher: PasswordHasher,
    private val temporaryPasswords: TemporaryPasswordGenerator,
    private val context: AdminContext,
) {

    /** Visible to any admin: knowing who else holds the keys is not a privilege. */
    fun list(): List<AdminUserDTO> =
        admins.findAll()
            .sortedWith(compareBy({ it.role != AdminRole.SUPER_ADMIN }, { it.email.value }))
            .map(AdminAssembler::toDTO)

    /**
     * Requires the current password even though the caller is already authenticated —
     * an unattended session should not be enough to take an account over.
     */
    @Transactional
    fun changeOwnPassword(command: ChangeAdminPasswordCommand): AdminUserDTO {
        val admin = caller()

        val current = runCatching { RawPassword(command.currentPassword) }.getOrNull()
            ?: throw AuthenticationFailed(CURRENT_INCORRECT)
        if (!passwordHasher.matches(current, admin.passwordHash)) {
            throw AuthenticationFailed(CURRENT_INCORRECT)
        }

        val replacement = runCatching { RawPassword(command.newPassword) }.getOrElse {
            throw UseCaseViolation(it.message ?: "New password is not acceptable")
        }
        if (passwordHasher.matches(replacement, admin.passwordHash)) {
            throw UseCaseViolation("The new password must differ from the current one")
        }

        return AdminAssembler.toDTO(admins.save(admin.withPassword(passwordHasher.hash(replacement))))
    }

    @Transactional
    fun create(command: CreateAdminCommand): AdminCreatedDTO {
        requireManager()

        val email = Email.of(command.email)
        if (admins.existsByEmail(email)) {
            throw UseCaseViolation("An admin with email ${email.value} already exists")
        }
        val role = runCatching { AdminRole.valueOf(command.role) }.getOrElse {
            throw UseCaseViolation("Unknown role: ${command.role}")
        }

        val temporary = temporaryPasswords.generate()
        val created = admins.save(
            AdminUser.create(
                email = email,
                passwordHash = passwordHasher.hash(temporary),
                name = command.name.trim(),
                role = role,
            )
        )
        return AdminCreatedDTO(AdminAssembler.toDTO(created), temporary.value)
    }

    /**
     * Two refusals that are not about permission. Removing yourself is almost always a
     * misclick, and removing the last super admin leaves an installation nobody can ever
     * add an admin to again — recoverable only by editing the database by hand.
     */
    @Transactional
    fun delete(id: Long) {
        val manager = requireManager()
        val target = admins.findById(id) ?: throw NoSuchElementException("No such admin")

        if (target.id == manager.id) {
            throw UseCaseViolation("You cannot remove your own account")
        }
        if (target.role == AdminRole.SUPER_ADMIN && admins.countByRole(AdminRole.SUPER_ADMIN) <= 1) {
            throw UseCaseViolation("The last super admin cannot be removed")
        }

        admins.deleteById(id)
    }

    private fun caller(): AdminUser {
        val id = context.currentAdminId() ?: throw AuthenticationFailed("Not signed in")
        return admins.findById(id) ?: throw AuthenticationFailed("Not signed in")
    }

    private fun requireManager(): AdminUser = caller().also {
        if (!it.canManageAdmins) throw NotPermitted("Only a super admin may manage admins")
    }

    private companion object {
        const val CURRENT_INCORRECT = "Current password is incorrect"
    }
}
