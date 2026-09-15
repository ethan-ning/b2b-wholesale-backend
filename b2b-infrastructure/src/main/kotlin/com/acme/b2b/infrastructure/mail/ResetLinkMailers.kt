package com.acme.b2b.infrastructure.mail

import com.acme.b2b.domain.auth.ResetLinkMailer
import com.acme.b2b.domain.auth.ResetLinkMessage
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.nio.charset.StandardCharsets

/**
 * Sends the reset link over SMTP.
 *
 * Multipart with both a plain-text and an HTML part: clients that will not render HTML
 * still get a usable link, and a text alternative is one of the things spam filters look
 * for.
 */
class SmtpResetLinkMailer(
    private val sender: JavaMailSender,
    private val fromAddress: String,
    private val fromName: String,
) : ResetLinkMailer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendResetLink(message: ResetLinkMessage) {
        // Never propagates. The caller has already committed a token and must answer the
        // same way whether or not delivery worked — otherwise the response time alone
        // says whether an address exists.
        runCatching {
            val mime: MimeMessage = sender.createMimeMessage()
            val helper = MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name())
            helper.setFrom(fromAddress, fromName)
            helper.setTo(message.to.value)
            helper.setSubject(ResetLinkEmail.subject(message))
            // text first, then html — the order decides which a client prefers.
            helper.setText(ResetLinkEmail.text(message), ResetLinkEmail.html(message))
            // Must come after setText: the helper needs the multipart to exist before it
            // can hang an inline part off it.
            addLogo(helper)
            sender.send(mime)
            log.info("Password reset link sent for a {} account", message.audience)
        }.onFailure {
            log.error("Could not send a {} password reset email", message.audience, it)
        }
    }

    /**
     * Attaches the header logo as an inline part. A missing resource degrades to a message
     * with alt text where the banner would be — worth far less than losing the reset.
     */
    private fun addLogo(helper: MimeMessageHelper) {
        val resource = ClassPathResource(ResetLinkEmail.LOGO_RESOURCE)
        if (!resource.exists()) {
            log.warn("{} is not on the classpath — sending without the header logo", ResetLinkEmail.LOGO_RESOURCE)
            return
        }
        helper.addInline(ResetLinkEmail.LOGO_CID, resource, "image/jpeg")
    }
}

/**
 * Writes the link to the log instead of sending it.
 *
 * What local development uses, and what production falls back to if no SMTP host is
 * configured — a reset that lands in the log is recoverable; one that throws on a missing
 * mail server loses the request entirely.
 */
class LoggingResetLinkMailer : ResetLinkMailer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendResetLink(message: ResetLinkMessage) {
        log.warn(
            "No SMTP configured — {} password reset link for {} not sent. Link: {}",
            message.audience,
            message.to.value,
            message.link,
        )
    }
}
