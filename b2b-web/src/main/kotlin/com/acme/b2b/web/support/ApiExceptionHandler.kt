package com.acme.b2b.web.support

import com.acme.b2b.application.support.AuthenticationFailed
import com.acme.b2b.application.support.UseCaseViolation
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Translates the failure types the layers below raise into status codes, so no
 * controller has to and no stack trace reaches a client.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    /** Domain invariants throw from constructors — a bad code is a client error, not a 500. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun onInvalidInput(e: IllegalArgumentException) =
        ResponseEntity.badRequest().body(ApiError(e.message ?: "Invalid request"))

    @ExceptionHandler(UseCaseViolation::class)
    fun onViolation(e: UseCaseViolation): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError(e.message ?: "Request could not be completed"))

    @ExceptionHandler(AuthenticationFailed::class)
    fun onAuthFailure(e: AuthenticationFailed): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError(e.message ?: "Unauthorized"))

    @ExceptionHandler(NoSuchElementException::class)
    fun onMissing(e: NoSuchElementException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(e.message ?: "Not found"))
}

data class ApiError(val message: String)
