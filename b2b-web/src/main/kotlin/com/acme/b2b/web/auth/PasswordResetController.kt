package com.acme.b2b.web.auth

import com.acme.b2b.application.auth.PasswordResetService
import com.acme.b2b.domain.auth.ResetAudience
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Forgotten-password links for both portals.
 *
 * One controller rather than one per audience: these four endpoints are the same two use
 * cases with an audience constant, and splitting them across the dealer and admin
 * controllers would leave the rule that a dealer link cannot reset an admin stated in two
 * places.
 *
 * All four are unauthenticated by necessity — whoever needs them cannot sign in.
 */
@RestController
class PasswordResetController(
    private val resets: PasswordResetService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Always 200, always the same body, whether or not the address belongs to anyone.
     * Anything else turns this into a way to enumerate dealers.
     */
    @PostMapping("/api/auth/forgot-password")
    fun dealerForgot(@RequestBody request: ForgotPasswordRequest): ForgotPasswordResponse {
        log.info("Dealer password reset requested")
        resets.requestReset(ResetAudience.DEALER, request.email)
        return ForgotPasswordResponse()
    }

    @PostMapping("/api/auth/reset-password")
    fun dealerReset(@RequestBody request: ResetPasswordRequest): ResetPasswordResponse {
        resets.completeReset(ResetAudience.DEALER, request.token, request.newPassword)
        log.info("Dealer password reset completed")
        return ResetPasswordResponse()
    }

    @PostMapping("/api/admin/auth/forgot-password")
    fun adminForgot(@RequestBody request: ForgotPasswordRequest): ForgotPasswordResponse {
        log.info("Admin password reset requested")
        resets.requestReset(ResetAudience.ADMIN, request.email)
        return ForgotPasswordResponse()
    }

    @PostMapping("/api/admin/auth/reset-password")
    fun adminReset(@RequestBody request: ResetPasswordRequest): ResetPasswordResponse {
        resets.completeReset(ResetAudience.ADMIN, request.token, request.newPassword)
        log.info("Admin password reset completed")
        return ResetPasswordResponse()
    }
}

data class ForgotPasswordRequest(val email: String)

/**
 * Deliberately says "if the address is registered". The wording is the feature: it is the
 * same sentence for an address that exists and one that does not.
 */
data class ForgotPasswordResponse(
    val message: String =
        "If that address has an account, a reset link is on its way. Check your inbox and spam folder.",
)

data class ResetPasswordRequest(val token: String, val newPassword: String)

data class ResetPasswordResponse(
    val message: String = "Your password has been changed. You can now sign in.",
)
