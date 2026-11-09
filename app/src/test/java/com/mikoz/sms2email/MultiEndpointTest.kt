package io.github.sms2email.sms2email

import javax.mail.internet.InternetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiEndpointTest {
  @Test
  fun splitRecipients_acceptsCommaSemicolonAndNewline() {
    assertEquals(
        listOf("a@gmail.com", "b@icloud.com", "c@me.com"),
        PreferencesManager.splitRecipients(" a@gmail.com, b@icloud.com;\nc@me.com ,"),
    )
  }

  @Test
  fun isValidEmail_rejectsIncompleteAddresses() {
    assertTrue(PreferencesManager.isValidEmail("name@icloud.com"))
    assertFalse(PreferencesManager.isValidEmail("name"))
    assertFalse(PreferencesManager.isValidEmail("name@icloud"))
    assertFalse(PreferencesManager.isValidEmail("a b@gmail.com"))
  }

  @Test
  fun migration_movesLegacySettingsIntoFirstEndpoint() = runBlocking {
    val legacy =
        SmtpPreferences.newBuilder()
            .setSmtpHost("smtp.gmail.com")
            .setSmtpPort(465)
            .setSmtpUser("me@gmail.com")
            .setSmtpPassword("abcdabcdabcdabcd")
            .setFromEmail("me@gmail.com")
            .setToEmail("me@gmail.com, me@icloud.com")
            .setEncryptionMode(SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS)
            .build()

    assertTrue(LegacySingleEndpointMigration.shouldMigrate(legacy))
    val migrated = LegacySingleEndpointMigration.migrate(legacy)

    assertTrue(migrated.legacyMigrated)
    assertFalse(LegacySingleEndpointMigration.shouldMigrate(migrated))
    assertEquals(1, migrated.endpointsCount)
    val endpoint = migrated.getEndpoints(0)
    assertTrue(endpoint.enabled)
    assertTrue(endpoint.id.isNotBlank())
    assertEquals("smtp.gmail.com", endpoint.smtpHost)
    assertEquals(465, endpoint.smtpPort)
    assertEquals("me@gmail.com", endpoint.smtpUser)
    assertEquals("abcdabcdabcdabcd", endpoint.smtpPassword)
    assertEquals("me@gmail.com", endpoint.fromEmail)
    assertEquals(listOf("me@gmail.com", "me@icloud.com"), endpoint.toEmailsList)
    assertEquals(SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS, endpoint.encryptionMode)
    // The old fields are cleared, so the password is stored only once.
    assertEquals("", migrated.smtpPassword)
    assertEquals("", migrated.toEmail)
  }

  @Test
  fun migration_keepsQuotedNamesWithCommasTogether() = runBlocking {
    val legacy =
        SmtpPreferences.newBuilder()
            .setSmtpUser("me@gmail.com")
            .setToEmail("\"Doe, Jane\" <jane@example.com>, bob@icloud.com")
            .build()
    val recipients = LegacySingleEndpointMigration.migrate(legacy).getEndpoints(0).toEmailsList
    assertEquals(2, recipients.size)
    assertEquals("jane@example.com", InternetAddress.parse(recipients[0]).single().address)
    assertEquals("Doe, Jane", InternetAddress.parse(recipients[0]).single().personal)
    assertEquals("bob@icloud.com", recipients[1])
  }

  @Test
  fun migration_ofFreshInstallCreatesNoEndpoint() = runBlocking {
    val migrated = LegacySingleEndpointMigration.migrate(SmtpPreferences.getDefaultInstance())
    assertTrue(migrated.legacyMigrated)
    assertEquals(0, migrated.endpointsCount)
  }
}
