package com.acme.b2b.infrastructure.mail

import com.acme.b2b.domain.auth.ResetAudience
import com.acme.b2b.domain.auth.ResetLinkMessage
import org.springframework.core.io.ClassPathResource
import java.nio.charset.StandardCharsets

/**
 * Subject, HTML and plain-text alternative for the reset email.
 *
 * The markup lives in mail/reset-link.html rather than in a Kotlin string, so it can be
 * opened in a browser, highlighted and linted like the HTML it is. What stays here is
 * everything that has to be code: which values fill which placeholder, escaping them, and
 * refusing to send a template that still has a hole in it.
 *
 * The text part stays inline — eight lines with no markup to highlight.
 */
object ResetLinkEmail {

    /** Content-ID of the inline logo, and the classpath resources this renders from. */
    const val LOGO_CID = "woltaphor-logo"
    const val LOGO_RESOURCE = "mail/woltaphor-logo.jpg"
    private const val HTML_RESOURCE = "mail/reset-link.html"

    private val template: String by lazy {
        ClassPathResource(HTML_RESOURCE).inputStream.use {
            it.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    fun subject(message: ResetLinkMessage): String = when (message.audience) {
        ResetAudience.ADMIN -> "Reset your Woltaphor admin password"
        ResetAudience.DEALER -> "Reset your Woltaphor dealer password"
    }

    /** Some clients show this, and a link that exists only in HTML is one some people cannot use. */
    fun text(message: ResetLinkMessage): String = """
        Hi ${message.recipientName},

        We received a request to reset the password for your Woltaphor
        ${audienceWord(message.audience)} account.

        Open this link to choose a new password:
        ${message.link}

        The link expires in ${expiry(message.expiresInMinutes)} and can be used once.

        If you did not ask for this, you can ignore this email — your password
        has not changed.

        Woltaphor
    """.trimIndent()

    fun html(message: ResetLinkMessage): String = render(
        mapOf(
            "subject" to subject(message),
            "recipientName" to message.recipientName,
            "link" to message.link,
            "expiry" to expiry(message.expiresInMinutes),
            "audienceLabel" to audienceLabel(message.audience),
            "audienceWord" to audienceWord(message.audience),
            "logoCid" to LOGO_CID,
        )
    )

    /**
     * Fills the template, escaping every value.
     *
     * Escaping happens here rather than at each call site because a name comes from a
     * signup form and the link carries a token — one forgotten escape is an injection hole
     * opened by a dealer's own name.
     *
     * A placeholder left unfilled throws rather than shipping "{{link}}" to a dealer: a
     * renamed key would otherwise produce an email whose button goes nowhere.
     */
    private fun render(values: Map<String, String>): String {
        var out = template
        values.forEach { (key, value) -> out = out.replace("{{$key}}", escape(value)) }
        val leftover = PLACEHOLDER.findAll(out).map { it.groupValues[1] }.distinct().toList()
        require(leftover.isEmpty()) { "Reset email template has unfilled placeholders: $leftover" }
        return out
    }

    private fun audienceWord(audience: ResetAudience) =
        if (audience == ResetAudience.ADMIN) "admin" else "dealer"

    private fun audienceLabel(audience: ResetAudience) =
        if (audience == ResetAudience.ADMIN) "Admin portal" else "Dealer portal"

    private fun expiry(minutes: Long) = when {
        minutes % 60L == 0L && minutes >= 60L -> {
            val hours = minutes / 60L
            if (hours == 1L) "1 hour" else "$hours hours"
        }
        minutes == 1L -> "1 minute"
        else -> "$minutes minutes"
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val PLACEHOLDER = Regex("""\{\{(\w+)}}""")
}
