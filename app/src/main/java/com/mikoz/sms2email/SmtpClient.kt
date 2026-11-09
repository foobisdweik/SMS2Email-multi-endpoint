package io.github.sms2email.sms2email

import com.sun.mail.smtp.SMTPAddressFailedException
import com.sun.mail.smtp.SMTPSendFailedException
import java.io.IOException
import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.Session
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Whether a failed send is worth trying again later. */
enum class FailureKind {
  /** For example: no network, a timeout, or an SMTP 4xx reply. A later attempt can succeed. */
  TEMPORARY,
  /** For example: a wrong password, a rejected address, or an SMTP 5xx reply. Retrying cannot help. */
  PERMANENT,
}

/** Sends one email through one endpoint. It keeps no state between calls. */
object SmtpClient {
  private const val SOCKET_TIMEOUT_MILLIS = "20000"

  /**
   * A message with a fixed Message-ID. JavaMail normally creates a new ID for every send. A fixed
   * ID lets mail clients recognize a retried send as the same email.
   */
  private class FixedIdMessage(session: Session, private val messageId: String) :
      MimeMessage(session) {
    override fun updateMessageID() {
      setHeader("Message-ID", messageId)
    }
  }

  fun buildProperties(config: SmtpConfig): Properties {
    val prop = Properties()
    prop["mail.smtp.host"] = config.smtpHost
    prop["mail.smtp.port"] = config.smtpPort.toString()
    prop["mail.smtp.auth"] = "true"
    // Without timeouts, a server that does not answer would block the worker for a long time.
    prop["mail.smtp.connectiontimeout"] = SOCKET_TIMEOUT_MILLIS
    prop["mail.smtp.timeout"] = SOCKET_TIMEOUT_MILLIS
    prop["mail.smtp.writetimeout"] = SOCKET_TIMEOUT_MILLIS
    when (config.encryptionMode) {
      SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_NONE -> {
        prop["mail.smtp.starttls.enable"] = "false"
        prop["mail.smtp.ssl.enable"] = "false"
      }
      SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS -> {
        prop["mail.smtp.starttls.enable"] = "false"
        prop["mail.smtp.socketFactory.class"] = "javax.net.ssl.SSLSocketFactory"
        prop["mail.smtp.socketFactory.fallback"] = "false"
        prop["mail.smtp.ssl.checkserveridentity"] = "true"
      }
      else -> {
        prop["mail.smtp.starttls.enable"] = "true"
        prop["mail.smtp.starttls.required"] = "true"
        prop["mail.smtp.ssl.checkserveridentity"] = "true"
      }
    }
    return prop
  }

  /** Sends one email. It throws the JavaMail exception when the server or network fails. */
  fun send(
      config: SmtpConfig,
      recipient: String,
      sender: String,
      body: String,
      receivedAtMillis: Long,
      messageId: String,
  ) {
    val session = Session.getInstance(buildProperties(config))
    val transport = session.getTransport("smtp")
    try {
      transport.connect(config.smtpHost, config.smtpPort, config.smtpUser, config.smtpPassword)
      val message = FixedIdMessage(session, messageId)
      message.setFrom(InternetAddress(config.fromEmail, sender, "UTF-8"))
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
      message.setSubject("SMS from $sender", "UTF-8")
      message.setText(body, "UTF-8")
      // The Date header is the time the phone received the SMS, not the time of this attempt.
      message.sentDate = Date(receivedAtMillis)
      message.saveChanges()
      transport.sendMessage(message, message.allRecipients)
    } finally {
      try {
        transport.close()
      } catch (ignored: MessagingException) {
        // The send already succeeded or failed. A close error changes nothing.
      }
    }
  }

  /** Decides whether [error] from [send] is worth a retry. */
  fun classify(error: Throwable): FailureKind {
    // An SMTP reply code is the most exact signal: 4xx means "try later", 5xx means "never".
    smtpReturnCode(error)?.let { code ->
      return if (code in 400..499) FailureKind.TEMPORARY else FailureKind.PERMANENT
    }
    return when {
      error is AuthenticationFailedException -> FailureKind.PERMANENT
      error is AddressException -> FailureKind.PERMANENT
      error is SendFailedException && !error.invalidAddresses.isNullOrEmpty() ->
          FailureKind.PERMANENT
      causes(error).any { it is IOException } -> FailureKind.TEMPORARY
      // Other JavaMail errors, such as "Could not connect", are usually network problems.
      error is MessagingException -> FailureKind.TEMPORARY
      else -> FailureKind.PERMANENT
    }
  }

  private fun smtpReturnCode(error: Throwable): Int? {
    for (cause in causes(error)) {
      when (cause) {
        is SMTPAddressFailedException -> return cause.returnCode
        is SMTPSendFailedException -> if (cause.returnCode > 0) return cause.returnCode
      }
    }
    return null
  }

  /** The error itself, its causes, and JavaMail's chained "next" exceptions. */
  private fun causes(error: Throwable): List<Throwable> {
    val result = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (current != null && current !in result && result.size < 20) {
      result += current
      current =
          (current as? MessagingException)?.nextException?.takeIf { it !in result }
              ?: current.cause
    }
    return result
  }
}
