package com.acme.b2b.infrastructure.mail

import com.acme.b2b.domain.auth.ResetAudience
import com.acme.b2b.domain.auth.ResetLinkMessage

/**
 * The reset email itself — subject, HTML and a plain-text alternative.
 *
 * Kept apart from the transport so the wording can be read and changed without touching
 * SMTP, and so the local logging mailer renders exactly what production sends.
 *
 * Written as one inlined-CSS table because that is what mail clients support. Outlook
 * ignores most of a stylesheet, Gmail strips `<style>` in forwarded copies, and neither
 * honours flexbox. The layout stays deliberately plain for the same reason.
 */
object ResetLinkEmail {

    /** Content-ID of the inline logo, and the classpath resource the mailer attaches under it. */
    const val LOGO_CID = "woltaphor-logo"
    const val LOGO_RESOURCE = "mail/woltaphor-logo.jpg"

    fun subject(message: ResetLinkMessage): String = when (message.audience) {
        ResetAudience.ADMIN -> "Reset your Woltaphor admin password"
        ResetAudience.DEALER -> "Reset your Woltaphor dealer password"
    }

    /** The fallback part. Some clients show it, and a link that only exists in HTML is a link some people cannot use. */
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

    fun html(message: ResetLinkMessage): String {
        val name = escape(message.recipientName)
        val link = escape(message.link)
        return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escape(subject(message))}</title>
</head>
<body style="margin:0; padding:0; background:#f4f4f5;">
  <!-- Preheader: what shows in the inbox list beside the subject. Hidden in the body. -->
  <div style="display:none; max-height:0; overflow:hidden; opacity:0;">
    Choose a new password. This link expires in ${expiry(message.expiresInMinutes)}.
  </div>

  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f4f4f5;">
    <tr>
      <td align="center" style="padding:32px 16px;">

        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
               style="max-width:560px; background:#ffffff; border-radius:10px; overflow:hidden;
                      font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;">

          <!--
            The logo is attached to the message and referenced by Content-ID, not fetched
            from a URL. Outlook blocks remote images by default and a header that arrives
            as an empty box is worse than none; an inline part renders without asking.
            The alt text carries the brand for anyone who blocks images anyway.
          -->
          <tr>
            <td style="background:#111111; font-size:0; line-height:0;">
              <img src="cid:$LOGO_CID" width="560" alt="Woltaphor"
                   style="display:block; width:100%; max-width:560px; height:auto; border:0;">
            </td>
          </tr>
          <tr>
            <td style="background:#111111; padding:0 32px 16px 32px;">
              <span style="color:#9b9b9b; font-size:11px; letter-spacing:1.2px; text-transform:uppercase;">
                ${escape(audienceLabel(message.audience))}
              </span>
            </td>
          </tr>

          <tr>
            <td style="padding:32px 32px 8px 32px;">
              <h1 style="margin:0 0 14px 0; font-size:21px; line-height:1.3; color:#111111;">
                Reset your password
              </h1>
              <p style="margin:0 0 14px 0; font-size:15px; line-height:1.6; color:#333333;">
                Hi ${name},
              </p>
              <p style="margin:0 0 24px 0; font-size:15px; line-height:1.6; color:#333333;">
                We received a request to reset the password for your Woltaphor
                ${audienceWord(message.audience)} account. Choose a new one using the button below.
              </p>
            </td>
          </tr>

          <tr>
            <td align="center" style="padding:0 32px 26px 32px;">
              <!-- Bulletproof-ish button: a padded anchor, since many clients drop <button>. -->
              <a href="$link"
                 style="display:inline-block; background:#111111; color:#ffffff; text-decoration:none;
                        font-size:15px; font-weight:600; padding:14px 30px; border-radius:6px;">
                Choose a new password
              </a>
            </td>
          </tr>

          <tr>
            <td style="padding:0 32px 26px 32px;">
              <p style="margin:0 0 8px 0; font-size:13px; line-height:1.6; color:#666666;">
                The link expires in <strong>${expiry(message.expiresInMinutes)}</strong> and can only be used once.
              </p>
              <p style="margin:0; font-size:13px; line-height:1.6; color:#666666;">
                If the button does not work, copy this address into your browser:
              </p>
              <p style="margin:6px 0 0 0; font-size:12px; line-height:1.5; word-break:break-all;">
                <a href="$link" style="color:#1a5fb4;">$link</a>
              </p>
            </td>
          </tr>

          <tr>
            <td style="padding:0 32px 30px 32px;">
              <div style="border-top:1px solid #e6e6e6; padding-top:18px;">
                <p style="margin:0; font-size:13px; line-height:1.6; color:#666666;">
                  Did not ask for this? You can ignore this email — your password has not
                  changed, and the link above will expire on its own.
                </p>
              </div>
            </td>
          </tr>

          <tr>
            <td style="background:#fafafa; padding:18px 32px; text-align:center;">
              <p style="margin:0; font-size:12px; line-height:1.6; color:#8a8a8a;">
                Woltaphor &middot; Aftermarket parts for Class 8 trucks<br>
                This is an automated message — please do not reply.
              </p>
            </td>
          </tr>

        </table>
      </td>
    </tr>
  </table>
</body>
</html>
        """.trimIndent()
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

    /**
     * A dealer's name comes from a form, and the link carries a token. Neither is trusted
     * into markup unescaped.
     */
    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
