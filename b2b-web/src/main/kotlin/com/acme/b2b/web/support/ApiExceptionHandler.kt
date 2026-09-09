package com.acme.b2b.web.support

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Domain invariants throw IllegalArgumentException from constructors — a bad SPU code or
 * a negative price is a client error, not a 500. Translating them here keeps that
 * knowledge out of every controller.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun onInvalidInput(e: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError(e.message ?: "Invalid request"))

    @ExceptionHandler(NoSuchElementException::class)
    fun onMissing(e: NoSuchElementException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(e.message ?: "Not found"))
}

data class ApiError(val message: String)
