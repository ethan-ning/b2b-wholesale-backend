package com.acme.b2b.web.support

import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.application.support.NotPermitted
import com.acme.b2b.application.support.UseCaseViolation
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Translates the failure types the layers below raise into status codes, so no
 * controller has to and no stack trace reaches a client.
 *
 * Every refusal is also logged. Without this the only record of a rejected request was
 * the status code in the access log, which says a call failed but never why — and the
 * message the client got, the one that would explain it, was thrown away.
 *
 * Client errors log at WARN without a stack trace: they are ordinary and the trace says
 * nothing a message and a path do not. Only [onMissing] is INFO, being routine.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Domain invariants throw from constructors — a bad code is a client error, not a 500. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun onInvalidInput(e: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val message = e.message ?: "Invalid request"
        log.warn("400 {} {} — {}", request.method, request.requestURI, message)
        return ResponseEntity.badRequest().body(ApiError(message))
    }

    @ExceptionHandler(UseCaseViolation::class)
    fun onViolation(e: UseCaseViolation, request: HttpServletRequest): ResponseEntity<ApiError> {
        val message = e.message ?: "Request could not be completed"
        log.warn("409 {} {} — {}", request.method, request.requestURI, message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError(message))
    }

    /**
     * The message is deliberately the same for every cause, so it is logged for the path
     * rather than for what actually went wrong — the log cannot say more than the response
     * without recording which addresses exist.
     */
    @ExceptionHandler(AuthenticationFailed::class)
    fun onAuthFailure(e: AuthenticationFailed, request: HttpServletRequest): ResponseEntity<ApiError> {
        log.warn("401 {} {}", request.method, request.requestURI)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError(e.message ?: "Unauthorized"))
    }

    /** 403, not 401: the caller is signed in, and bouncing them to a login page would not help. */
    @ExceptionHandler(NotPermitted::class)
    fun onNotPermitted(e: NotPermitted, request: HttpServletRequest): ResponseEntity<ApiError> {
        val message = e.message ?: "Not permitted"
        log.warn("403 {} {} — {}", request.method, request.requestURI, message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError(message))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun onMissing(e: NoSuchElementException, request: HttpServletRequest): ResponseEntity<ApiError> {
        val message = e.message ?: "Not found"
        log.info("404 {} {} — {}", request.method, request.requestURI, message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(message))
    }
}

data class ApiError(val message: String)
