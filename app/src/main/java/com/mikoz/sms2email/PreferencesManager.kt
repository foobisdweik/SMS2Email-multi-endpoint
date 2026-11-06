package io.github.sms2email.sms2email

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

val Context.smtpDataStore: DataStore<SmtpPreferences> by
    dataStore(
        fileName = "smtp_prefs.pb",
        serializer = SmtpPreferencesSerializer,
        produceMigrations = { listOf(LegacySingleEndpointMigration) },
    )

/** A provider template that fills in the known server settings for a new endpoint. */
enum class EndpointPreset(
    val displayName: String,
    val smtpHost: String,
    val smtpPort: Int,
    val encryptionMode: SmtpEncryptionMode,
) {
  GMAIL("Gmail", "smtp.gmail.com", 465, SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS),
  ICLOUD("iCloud", "smtp.mail.me.com", 587, SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_STARTTLS),
  CUSTOM("Custom SMTP", "", 587, SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_STARTTLS),
}

/** The settings of one SMTP account (endpoint) and the recipients it forwards to. */
data class SmtpConfig(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 465,
    val smtpUser: String = "",
    val smtpPassword: String = "",
    val fromEmail: String = "",
    val toEmails: List<String> = emptyList(),
    val encryptionMode: SmtpEncryptionMode = SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS,
) {
  val isGmail: Boolean
    get() = smtpHost.contains("gmail", ignoreCase = true)

  val isICloud: Boolean
    get() =
        smtpHost.contains("mail.me.com", ignoreCase = true) ||
            smtpHost.contains("icloud", ignoreCase = true)

  /** A short label for notifications and headings. */
  val label: String
    get() = name.ifBlank { fromEmail.ifBlank { smtpUser.ifBlank { smtpHost } } }
}

object SmtpPreferencesSerializer : Serializer<SmtpPreferences> {
  override val defaultValue: SmtpPreferences = SmtpPreferences.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): SmtpPreferences =
      try {
        SmtpPreferences.parseFrom(input)
      } catch (exception: InvalidProtocolBufferException) {
        throw CorruptionException("Cannot read proto.", exception)
      }

  override suspend fun writeTo(
      t: SmtpPreferences,
      output: OutputStream,
  ) = t.writeTo(output)
}

/**
 * Copies the single-account settings of older app versions into the first endpoint. It runs once,
 * then it clears the old fields so that the password is stored in one place only.
 */
object LegacySingleEndpointMigration : DataMigration<SmtpPreferences> {
  override suspend fun shouldMigrate(currentData: SmtpPreferences): Boolean =
      !currentData.legacyMigrated

  override suspend fun migrate(currentData: SmtpPreferences): SmtpPreferences {
    val builder = currentData.toBuilder()
    val hasLegacyData =
        listOf(
                currentData.smtpHost,
                currentData.smtpUser,
                currentData.smtpPassword,
                currentData.fromEmail,
                currentData.toEmail,
            )
            .any { it.isNotBlank() }
    if (hasLegacyData && currentData.endpointsCount == 0) {
      val legacyMode =
          currentData.encryptionMode.takeIf { it != SmtpEncryptionMode.UNRECOGNIZED }
              ?: SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS
      builder.addEndpoints(
          SmtpEndpoint.newBuilder()
              .setId(UUID.randomUUID().toString())
              .setEnabled(true)
              .setSmtpHost(currentData.smtpHost.ifBlank { EndpointPreset.GMAIL.smtpHost })
              .setSmtpPort(
                  if (currentData.smtpPort > 0) currentData.smtpPort
                  else EndpointPreset.GMAIL.smtpPort)
              .setSmtpUser(currentData.smtpUser)
              .setSmtpPassword(currentData.smtpPassword)
              .setFromEmail(currentData.fromEmail)
              .addAllToEmails(PreferencesManager.splitRecipients(currentData.toEmail))
              .setEncryptionMode(legacyMode)
              .build())
    }
    return builder
        .clearSmtpHost()
        .clearSmtpPort()
        .clearSmtpUser()
        .clearSmtpPassword()
        .clearFromEmail()
        .clearToEmail()
        .clearEncryptionMode()
        .setLegacyMigrated(true)
        .build()
  }

  override suspend fun cleanUp() = Unit
}

object PreferencesManager {
  private val emailPattern = Regex("^[^\\s@<>,;]+@[^\\s@<>,;]+\\.[^\\s@<>,;]+$")

  /** Splits text such as "a@x.com, b@y.com; c@z.com" into separate, trimmed addresses. */
  @JvmStatic
  fun splitRecipients(text: String): List<String> =
      text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

  fun isValidEmail(address: String): Boolean = emailPattern.matches(address.trim())

  private fun SmtpEndpoint.toConfig(): SmtpConfig =
      SmtpConfig(
          id = id,
          name = name,
          enabled = enabled,
          smtpHost = smtpHost,
          smtpPort = smtpPort,
          smtpUser = smtpUser,
          smtpPassword = smtpPassword,
          fromEmail = fromEmail,
          toEmails = toEmailsList.toList(),
          encryptionMode =
              encryptionMode.takeIf { it != SmtpEncryptionMode.UNRECOGNIZED }
                  ?: SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_STARTTLS,
      )

  fun endpointsFlow(context: Context): Flow<List<SmtpConfig>> =
      context.smtpDataStore.data.map { prefs -> prefs.endpointsList.map { it.toConfig() } }

  suspend fun getEndpoints(context: Context): List<SmtpConfig> = endpointsFlow(context).first()

  @JvmStatic
  fun getEndpointsBlocking(context: Context): List<SmtpConfig> = runBlocking {
    getEndpoints(context)
  }

  /** Adds a new endpoint with the server settings of [preset] and returns its id. */
  suspend fun addEndpoint(
      context: Context,
      preset: EndpointPreset,
  ): String {
    val id = UUID.randomUUID().toString()
    context.smtpDataStore.updateData { prefs ->
      val sameKind = prefs.endpointsList.count { it.smtpHost == preset.smtpHost }
      val name = if (sameKind == 0) preset.displayName else "${preset.displayName} ${sameKind + 1}"
      prefs
          .toBuilder()
          .addEndpoints(
              SmtpEndpoint.newBuilder()
                  .setId(id)
                  .setName(name)
                  .setEnabled(true)
                  .setSmtpHost(preset.smtpHost)
                  .setSmtpPort(preset.smtpPort)
                  .setEncryptionMode(preset.encryptionMode)
                  .build())
          .build()
    }
    return id
  }

  suspend fun removeEndpoint(
      context: Context,
      id: String,
  ) {
    context.smtpDataStore.updateData { prefs ->
      val index = prefs.endpointsList.indexOfFirst { it.id == id }
      if (index < 0) prefs else prefs.toBuilder().removeEndpoints(index).build()
    }
  }

  /** Changes the endpoint with [id]. Nothing happens if the endpoint was removed in the meantime. */
  suspend fun updateEndpoint(
      context: Context,
      id: String,
      change: SmtpEndpoint.Builder.() -> Unit,
  ) {
    context.smtpDataStore.updateData { prefs ->
      val index = prefs.endpointsList.indexOfFirst { it.id == id }
      if (index < 0) {
        prefs
      } else {
        val updated = prefs.getEndpoints(index).toBuilder().apply(change).build()
        prefs.toBuilder().setEndpoints(index, updated).build()
      }
    }
  }

  suspend fun addRecipient(
      context: Context,
      id: String,
      address: String,
  ) {
    val clean = address.trim()
    updateEndpoint(context, id) {
      if (toEmailsList.none { it.equals(clean, ignoreCase = true) }) addToEmails(clean)
    }
  }

  suspend fun removeRecipient(
      context: Context,
      id: String,
      address: String,
  ) {
    updateEndpoint(context, id) {
      val remaining = toEmailsList.filter { it != address }
      clearToEmails()
      addAllToEmails(remaining)
    }
  }
}
