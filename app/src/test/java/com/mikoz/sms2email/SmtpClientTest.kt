package io.github.sms2email.sms2email

import com.sun.mail.smtp.SMTPAddressFailedException
import com.sun.mail.smtp.SMTPSendFailedException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class SmtpClientTest {
  private val address = InternetAddress("a@example.com")

  @Test
  fun networkProblems_areTemporary() {
    assertEquals(
        FailureKind.TEMPORARY,
        SmtpClient.classify(MessagingException("Could not connect", SocketTimeoutException())))
    assertEquals(
        FailureKind.TEMPORARY,
        SmtpClient.classify(MessagingException("Unknown host", UnknownHostException("smtp"))))
    assertEquals(FailureKind.TEMPORARY, SmtpClient.classify(MessagingException("Connection lost")))
  }

  @Test
  fun wrongPasswordAndBadAddress_arePermanent() {
    assertEquals(
        FailureKind.PERMANENT, SmtpClient.classify(AuthenticationFailedException("535 bad login")))
    assertEquals(FailureKind.PERMANENT, SmtpClient.classify(AddressException("bad", "x@")))
    assertEquals(
        FailureKind.PERMANENT,
        SmtpClient.classify(SendFailedException("Invalid", null, null, null, arrayOf(address))))
  }

  @Test
  fun smtpReplyCode_decides_4xxTemporary_5xxPermanent() {
    val busy = SMTPSendFailedException("DATA", 451, "451 try later", null, null, null, null)
    val rejected = SMTPSendFailedException("DATA", 554, "554 rejected", null, null, null, null)
    assertEquals(FailureKind.TEMPORARY, SmtpClient.classify(busy))
    assertEquals(FailureKind.PERMANENT, SmtpClient.classify(rejected))
  }

  @Test
  fun replyCode_isFoundInChainedNextException() {
    val outer = SendFailedException("Invalid Addresses")
    outer.setNextException(SMTPAddressFailedException(address, "RCPT", 550, "550 no such user"))
    assertEquals(FailureKind.PERMANENT, SmtpClient.classify(outer))

    val greylisted = SendFailedException("Invalid Addresses")
    greylisted.setNextException(SMTPAddressFailedException(address, "RCPT", 450, "450 greylisted"))
    assertEquals(FailureKind.TEMPORARY, SmtpClient.classify(greylisted))
  }

  @Test
  fun otherErrors_arePermanent() {
    assertEquals(FailureKind.PERMANENT, SmtpClient.classify(IllegalStateException("bug")))
  }
}
