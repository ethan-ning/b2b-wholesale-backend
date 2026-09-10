package com.acme.b2b.application.support

/** Credentials did not match, or the account cannot sign in. */
class AuthenticationFailed(message: String) : RuntimeException(message)

/** A use-case precondition failed — duplicate email, unknown tier. */
class UseCaseViolation(message: String) : RuntimeException(message)

/**
 * Signed in, but not allowed to do this. Distinct from [AuthenticationFailed] because
 * the caller should not be sent back to a login page they have already passed.
 */
class NotPermitted(message: String) : RuntimeException(message)
