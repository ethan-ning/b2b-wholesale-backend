package com.acme.b2b.web.dealer

import com.acme.b2b.application.dealer.ChangePasswordCommand
import com.acme.b2b.application.dealer.DealerAuthService
import com.acme.b2b.application.dealer.DealerLoginCommand
import com.acme.b2b.application.dealer.dto.DealerLoginResponse
import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.application.support.DealerContext
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class DealerAuthController(
    private val auth: DealerAuthService,
    private val dealerContext: DealerContext,
) {

    @PostMapping("/login")
    fun login(@RequestBody request: DealerLoginRequest): DealerLoginResponse =
        auth.login(DealerLoginCommand(request.email, request.password))

    /**
     * The customer id comes from the token, never from the request body — otherwise
     * any authenticated dealer could set another dealer's password.
     */
    @PostMapping("/change-password")
    fun changePassword(@RequestBody request: ChangePasswordRequest): DealerLoginResponse {
        val customerId = dealerContext.currentCustomerId()
            ?: throw AuthenticationFailed("Not signed in as a dealer")
        return auth.changePassword(customerId, ChangePasswordCommand(request.currentPassword, request.newPassword))
    }
}

data class DealerLoginRequest(val email: String, val password: String)

data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
