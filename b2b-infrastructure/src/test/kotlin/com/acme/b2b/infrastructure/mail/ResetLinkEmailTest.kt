package com.acme.b2b.infrastructure.mail

import com.acme.b2b.domain.auth.ResetAudience
import com.acme.b2b.domain.auth.ResetLinkMessage
import com.acme.b2b.types.Email
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResetLinkEmailTest {

    private fun message(
        audience: ResetAudience = ResetAudience.DEALER,
        name: String = "Pat Dealer",
        link: String = "https://b2b.woltaphor.com/reset-password?token=abc123",
        minutes: Long = 60,
    ) = ResetLinkMessage(
        to = Email.of("dealer@example.com"),
        recipientName = name,
        link = link,
        expiresInMinutes = minutes,
        audience = audience,
    )

    @Test
    fun `fills every placeholder`() {
        val html = ResetLinkEmail.html(message())
        // The render refuses on a leftover, so reaching here proves it; assert anyway so a
        // change to that guard cannot quietly ship "{{link}}" to a dealer.
        assertFalse(html.contains("{{"), "template still has an unfilled placeholder")
        assertContains(html, "https://b2b.woltaphor.com/reset-password?token=abc123")
        assertContains(html, "Pat Dealer")
        assertContains(html, "cid:${ResetLinkEmail.LOGO_CID}")
    }

    /**
     * A name comes from a signup form. Without escaping it is markup, and the account
     * holder chooses it.
     */
    @Test
    fun `escapes values rather than trusting them into markup`() {
        val html = ResetLinkEmail.html(message(name = """<script>alert("x")</script>"""))
        assertFalse(html.contains("<script>"), "a name was interpolated as live markup")
        assertContains(html, "&lt;script&gt;")
    }

    @Test
    fun `says dealer or admin in the words each audience expects`() {
        assertContains(ResetLinkEmail.html(message(ResetAudience.DEALER)), "Dealer portal")
        assertContains(ResetLinkEmail.html(message(ResetAudience.ADMIN)), "Admin portal")
        assertEquals("Reset your Woltaphor admin password", ResetLinkEmail.subject(message(ResetAudience.ADMIN)))
        assertEquals("Reset your Woltaphor dealer password", ResetLinkEmail.subject(message(ResetAudience.DEALER)))
    }

    @Test
    fun `writes the expiry the way a person would say it`() {
        assertContains(ResetLinkEmail.html(message(minutes = 60)), "1 hour")
        assertContains(ResetLinkEmail.html(message(minutes = 120)), "2 hours")
        assertContains(ResetLinkEmail.html(message(minutes = 45)), "45 minutes")
        assertContains(ResetLinkEmail.html(message(minutes = 1)), "1 minute")
    }

    /** A client that will not render HTML still has to be able to follow the link. */
    @Test
    fun `the plain-text part carries the link`() {
        val text = ResetLinkEmail.text(message())
        assertContains(text, "https://b2b.woltaphor.com/reset-password?token=abc123")
        assertContains(text, "Pat Dealer")
        assertFalse(text.contains("<"), "the text alternative should carry no markup")
    }

    @Test
    fun `the logo it names is actually on the classpath`() {
        val logo = org.springframework.core.io.ClassPathResource(ResetLinkEmail.LOGO_RESOURCE)
        assertTrue(logo.exists(), "${ResetLinkEmail.LOGO_RESOURCE} is missing — the header would be an empty box")
    }
}
