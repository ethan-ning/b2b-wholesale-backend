package com.acme.b2b.infrastructure.mail

import com.acme.b2b.domain.auth.ResetLinkMailer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.util.Properties

/**
 * Which mailer the reset link goes through: SMTP when a host is named, the log when not.
 *
 * Decided here in one @Bean method, for the same reason [com.acme.b2b.infrastructure.storage.ImageStoreConfig]
 * decides the image store here — a condition on a scanned @Component has already once left
 * this application with no bean at all.
 *
 * The SMTP settings are deliberately generic. Gmail, Workspace, SendGrid, Resend, Postmark
 * and Mailgun all speak SMTP on 587, so choosing a provider is a change to environment
 * variables rather than to code.
 */
@Configuration
class MailConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun resetLinkMailer(
        @Value("\${app.mail.host:}") host: String,
        @Value("\${app.mail.port:587}") port: Int,
        @Value("\${app.mail.username:}") username: String,
        @Value("\${app.mail.password:}") password: String,
        @Value("\${app.mail.from:no-reply@woltaphor.com}") from: String,
        @Value("\${app.mail.from-name:Woltaphor}") fromName: String,
        @Value("\${app.mail.starttls:true}") startTls: Boolean,
    ): ResetLinkMailer {
        if (host.isBlank()) {
            log.warn("app.mail.host is unset — password reset links will be logged, not emailed")
            return LoggingResetLinkMailer()
        }

        // 465 is implicit SSL — encrypted from the first byte, with no STARTTLS handshake.
        // 587 begins in the clear and upgrades. Configuring one as though it were the other
        // fails to connect at all, so the port decides unless told otherwise.
        val implicitSsl = port == IMPLICIT_SSL_PORT

        val sender = JavaMailSenderImpl().apply {
            this.host = host
            this.port = port
            this.defaultEncoding = "UTF-8"
            if (username.isNotBlank()) this.username = username
            if (password.isNotBlank()) this.password = password
            javaMailProperties = Properties().apply {
                put("mail.transport.protocol", "smtp")
                put("mail.smtp.auth", username.isNotBlank().toString())
                if (implicitSsl) {
                    put("mail.smtp.ssl.enable", "true")
                } else {
                    put("mail.smtp.starttls.enable", startTls.toString())
                    // Without this a downgrade to plaintext is silent; fail instead.
                    put("mail.smtp.starttls.required", startTls.toString())
                }
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
                put("mail.smtp.writetimeout", "10000")
            }
        }
        log.info(
            "Password reset links go out over SMTP via {}:{} ({}) as {}",
            host, port, if (implicitSsl) "implicit SSL" else "STARTTLS", from,
        )
        return SmtpResetLinkMailer(sender, from, fromName)
    }

    private companion object {
        const val IMPLICIT_SSL_PORT = 465
    }
}
