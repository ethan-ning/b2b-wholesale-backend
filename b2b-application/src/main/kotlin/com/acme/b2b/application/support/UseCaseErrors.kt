package com.acme.b2b.application.support

/** Credentials did not match, or the account cannot sign in. */
class AuthenticationFailed(message: String) : RuntimeException(message)

/** A use-case precondition failed — duplicate email, unknown tier. */
class UseCaseViolation(message: String) : RuntimeException(message)
