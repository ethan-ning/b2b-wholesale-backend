package com.acme.b2b.application.dealer

import com.acme.b2b.application.dealer.dto.DealerLoginResponse
import com.acme.b2b.application.dealer.dto.DealerUserDTO
import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.application.support.UseCaseViolation
import com.acme.b2b.domain.auth.AccessTokenIssuer
import com.acme.b2b.domain.auth.PasswordHasher
import com.acme.b2b.domain.customer.Customer
import com.acme.b2b.domain.customer.CustomerRepository
import com.acme.b2b.domain.customer.CustomerTierRepository
import com.acme.b2b.types.Email
import com.acme.b2b.types.RawPassword
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class DealerAuthService(
    private val customers: CustomerRepository,
    private val tiers: CustomerTierRepository,
    private val passwordHasher: PasswordHasher,
    private val tokens: AccessTokenIssuer,
) {

    /**
     * Every failure path returns the same message — wrong password, unknown email,
     * disabled account. Distinguishing them tells an outsider which dealers exist and
     * which have been suspended, and the dealer gains nothing from the difference.
     *
     * A dealer still on an admin-issued password gets a token that reaches only the
     * change-password endpoint, so the forced change does not depend on the client
     * choosing to honour a flag.
     */
    fun login(command: DealerLoginCommand): DealerLoginResponse {
        val email = runCatching { Email.of(command.email) }.getOrNull()
            ?: throw AuthenticationFailed(INVALID_CREDENTIALS)
        val password = runCatching { RawPassword(command.password) }.getOrNull()
            ?: throw AuthenticationFailed(INVALID_CREDENTIALS)

        val customer = customers.findByEmail(email) ?: throw AuthenticationFailed(INVALID_CREDENTIALS)
        if (!passwordHasher.matches(password, customer.passwordHash)) {
            throw AuthenticationFailed(INVALID_CREDENTIALS)
        }
        if (!customer.canAccessCatalog) throw AuthenticationFailed(INVALID_CREDENTIALS)

        return response(customer)
    }

    /**
     * Replaces the dealer's password and returns a full catalog token, which is how a
     * forced change ends. Requires the current password even though the caller is
     * already authenticated: the password-change token may have been issued from a
     * credential the dealer never chose.
     */
    @Transactional
    fun changePassword(customerId: Long, command: ChangePasswordCommand): DealerLoginResponse {
        val customer = customers.findById(customerId)
            ?: throw AuthenticationFailed(INVALID_CREDENTIALS)

        val current = runCatching { RawPassword(command.currentPassword) }.getOrNull()
            ?: throw AuthenticationFailed("Current password is incorrect")
        if (!passwordHasher.matches(current, customer.passwordHash)) {
            throw AuthenticationFailed("Current password is incorrect")
        }

        val replacement = runCatching { RawPassword(command.newPassword) }.getOrElse {
            throw UseCaseViolation(it.message ?: "New password is not acceptable")
        }
        if (passwordHasher.matches(replacement, customer.passwordHash)) {
            throw UseCaseViolation("The new password must differ from the current one")
        }

        val updated = customers.save(customer.withChosenPassword(passwordHasher.hash(replacement)))
        return response(updated)
    }

    private fun response(customer: Customer): DealerLoginResponse {
        val id = checkNotNull(customer.id) { "A persisted customer must have an id" }
        val tierName = tiers.findById(customer.tierId)?.name.orEmpty()

        val token =
            if (customer.mustChangePassword) tokens.issuePasswordChangeToken(id, customer.email.value)
            else tokens.issueForDealer(id, customer.email.value, customer.tierId.value)

        return DealerLoginResponse(
            token = token,
            user = DealerUserDTO(
                id = id,
                email = customer.email.value,
                name = customer.name,
                companyName = customer.companyName,
                tierId = customer.tierId.value,
                tierName = tierName,
                mustChangePassword = customer.mustChangePassword,
            ),
        )
    }

    private companion object {
        const val INVALID_CREDENTIALS = "Invalid email or password"
    }
}
