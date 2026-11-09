package io.github.sms2email.sms2email

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.sms2email.sms2email.ui.theme.SMS2EmailTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private val smsPermissionGrantedFlow = MutableStateFlow(false)
  private val notificationPermissionGrantedFlow = MutableStateFlow(false)
  private val requestPermissionLauncher =
      registerForActivityResult(
          ActivityResultContracts.RequestPermission(),
      ) { isGranted: Boolean ->
        if (!isGranted) {
          Toast.makeText(this, "SMS permission denied", Toast.LENGTH_SHORT).show()
        }
        smsPermissionGrantedFlow.value = isGranted
      }

  private val requestNotificationPermissionLauncher =
      registerForActivityResult(
          ActivityResultContracts.RequestPermission(),
      ) { isGranted: Boolean ->
        if (!isGranted) {
          Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
        notificationPermissionGrantedFlow.value = isGranted
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // window.setBackgroundDrawableResource(R.drawable.background)
    enableEdgeToEdge()

    smsPermissionGrantedFlow.value = isSmsPermissionGranted()
    val initialSmsPermissionGranted = smsPermissionGrantedFlow.value

    notificationPermissionGrantedFlow.value = isNotificationPermissionGranted()
    val initialNotificationPermissionGranted = notificationPermissionGrantedFlow.value

    setContent {
      SMS2EmailTheme {
        val isDark = isSystemInDarkTheme()
        val isSmsPermissionGranted by
            smsPermissionGrantedFlow.collectAsState(initial = initialSmsPermissionGranted)
        val isNotificationPermissionGranted by
            notificationPermissionGrantedFlow.collectAsState(
                initial = initialNotificationPermissionGranted,
            )
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            if (isDark) {
              Image(
                  painter = painterResource(id = R.drawable.background_dark),
                  contentDescription = "Background",
                  modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                  contentScale = ContentScale.FillWidth,
                  alpha = 0.3f,
              )
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
            ) { innerPadding ->
              MailPreferencesScreen(
                  context = this@MainActivity,
                  isSmsPermissionGranted = isSmsPermissionGranted,
                  onRequestPermission = {
                    requestPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                  },
                  isNotificationPermissionGranted = isNotificationPermissionGranted,
                  onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                      requestNotificationPermissionLauncher.launch(
                          Manifest.permission.POST_NOTIFICATIONS,
                      )
                    }
                  },
                  modifier = Modifier.padding(innerPadding),
              )
            }
          }
        }
      }
    }
  }

  private fun isSmsPermissionGranted(): Boolean =
      ContextCompat.checkSelfPermission(
          this,
          Manifest.permission.RECEIVE_SMS,
      ) == PackageManager.PERMISSION_GRANTED

  private fun isNotificationPermissionGranted(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MailPreferencesScreen(
    context: Context,
    isSmsPermissionGranted: Boolean,
    onRequestPermission: () -> Unit = {},
    isNotificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit = {},
    modifier: Modifier = Modifier,
) {

  val endpoints by
      PreferencesManager.endpointsFlow(context).collectAsState(initial = emptyList())
  val coroutineScope = rememberCoroutineScope()

  Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .background(Color.Transparent),
    ) {
      Card(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          colors =
              CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceVariant,
              ),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
          shape = MaterialTheme.shapes.medium,
      ) {
        Text(
            text =
                "SMS2Email automatically forwards received SMS to email via SMTP, even if the app is closed.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Card(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          colors =
              CardDefaults.cardColors(
                  containerColor =
                      if (isSmsPermissionGranted) {
                        MaterialTheme.colorScheme.primaryContainer
                      } else {
                        MaterialTheme.colorScheme.errorContainer
                      },
              ),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
          shape = MaterialTheme.shapes.medium,
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
              text =
                  if (isSmsPermissionGranted) "✓ SMS Permission: Granted"
                  else "✗ SMS Permission: Not Granted",
              style = MaterialTheme.typography.bodyLarge,
              color =
                  if (isSmsPermissionGranted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                  } else {
                    MaterialTheme.colorScheme.onErrorContainer
                  },
          )

          if (!isSmsPermissionGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onRequestPermission() },
                modifier = Modifier.fillMaxWidth(),
            ) {
              Text("Request SMS Permission")
            }
          }
        }
      }

      Card(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          colors =
              CardDefaults.cardColors(
                  containerColor =
                      if (isNotificationPermissionGranted) {
                        MaterialTheme.colorScheme.primaryContainer
                      } else {
                        MaterialTheme.colorScheme.errorContainer
                      },
              ),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
          shape = MaterialTheme.shapes.medium,
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
              text =
                  if (isNotificationPermissionGranted) "✓ Notification Permission: Granted"
                  else "✗ Notification Permission: Not Granted",
              style = MaterialTheme.typography.bodyLarge,
              color =
                  if (isNotificationPermissionGranted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                  } else {
                    MaterialTheme.colorScheme.onErrorContainer
                  },
          )

          if (!isNotificationPermissionGranted) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onRequestNotificationPermission() },
                modifier = Modifier.fillMaxWidth(),
            ) {
              Text("Request Notification Permission")
            }
          }
        }
      }

      Text(
          text = "Email Endpoints",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
          text =
              "Each endpoint is one email account that sends the SMS. Every enabled endpoint " +
                  "sends a copy to each of its recipients. One Gmail account can also send to " +
                  "iCloud or other addresses.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.padding(bottom = 12.dp),
      )

      if (endpoints.isEmpty()) {
        Text(
            text = "No endpoint yet. Add one below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 12.dp),
        )
      }

      for (endpoint in endpoints) {
        key(endpoint.id) {
          EndpointCard(
              context = context,
              endpoint = endpoint,
              onRemove = {
                coroutineScope.launch { PreferencesManager.removeEndpoint(context, endpoint.id) }
              },
          )
        }
      }

      Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        for (preset in EndpointPreset.entries) {
          OutlinedButton(
              onClick = {
                coroutineScope.launch { PreferencesManager.addEndpoint(context, preset) }
              },
              modifier = Modifier.weight(1f),
              contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
          ) {
            Text(
                text = "+ " + if (preset == EndpointPreset.CUSTOM) "Custom" else preset.displayName,
                maxLines = 1,
            )
          }
        }
      }

      Button(
          onClick = { queueTestEmail(context, coroutineScope, endpointId = null) },
          enabled = endpoints.any { it.enabled },
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Send Test Email to All Enabled Endpoints")
      }

      // remember() keeps one flow, so that each redraw does not start a new database query.
      val pendingFlow = remember { ForwardQueue.pendingCountFlow(context) }
      val pendingCount by pendingFlow.collectAsState(initial = 0)
      if (pendingCount > 0) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              text =
                  if (pendingCount == 1) "Outbox: 1 email waits to be sent."
                  else "Outbox: $pendingCount emails wait to be sent.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onBackground,
              modifier = Modifier.weight(1f),
          )
          TextButton(onClick = { ForwardQueue.cancelAll(context) }) { Text("Clear Outbox") }
        }
      }

      Row(
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
          horizontalArrangement = Arrangement.End,
      ) {
        Text(
            text = "Licenses...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier.clickable {
                  context.startActivity(Intent(context, AboutActivity::class.java))
                },
        )
      }
    }
  }
}

/** The settings card for one endpoint (one SMTP account and its recipients). */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EndpointCard(
    context: Context,
    endpoint: SmtpConfig,
    onRemove: () -> Unit,
) {
  val id = endpoint.id
  val coroutineScope = rememberCoroutineScope()
  fun update(change: SmtpEndpoint.Builder.() -> Unit) {
    coroutineScope.launch { PreferencesManager.updateEndpoint(context, id, change) }
  }

  // A new, empty endpoint starts open so that the user can fill it in at once.
  val expandedState = rememberSaveable {
    mutableStateOf(endpoint.smtpUser.isBlank() || endpoint.toEmails.isEmpty())
  }
  val confirmRemoveState = rememberSaveable { mutableStateOf(false) }

  val nameState =
      rememberPreferenceTextState(endpoint.name) { value ->
        PreferencesManager.updateEndpoint(context, id) { setName(value) }
      }
  val smtpHostState =
      rememberPreferenceTextState(endpoint.smtpHost) { value ->
        PreferencesManager.updateEndpoint(context, id) { setSmtpHost(value) }
      }
  val smtpPortState =
      rememberPreferenceTextState(endpoint.smtpPort.toString()) { value ->
        value.toIntOrNull()?.let { port ->
          PreferencesManager.updateEndpoint(context, id) { setSmtpPort(port) }
        }
      }
  val smtpUserState =
      rememberPreferenceTextState(endpoint.smtpUser) { value ->
        PreferencesManager.updateEndpoint(context, id) { setSmtpUser(value.trim()) }
      }
  val smtpPasswordState =
      rememberPreferenceTextState(endpoint.smtpPassword) { value ->
        PreferencesManager.updateEndpoint(context, id) { setSmtpPassword(value) }
      }
  val fromEmailState =
      rememberPreferenceTextState(endpoint.fromEmail) { value ->
        PreferencesManager.updateEndpoint(context, id) { setFromEmail(value.trim()) }
      }
  val newRecipientState = rememberSaveable { mutableStateOf("") }
  val newRecipientError = rememberSaveable { mutableStateOf<String?>(null) }

  val portNumber = smtpPortState.value.toIntOrNull()
  val encryptionExpandedState = rememberSaveable { mutableStateOf(false) }
  val encryptionLabel =
      when (endpoint.encryptionMode) {
        SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_NONE -> "None"
        SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS -> "SMTPS (SSL/TLS)"
        SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_STARTTLS -> "STARTTLS"
        else -> "STARTTLS"
      }

  fun addRecipient() {
    val parts = PreferencesManager.splitRecipients(newRecipientState.value)
    val invalid = parts.filterNot { PreferencesManager.isValidEmail(it) }
    when {
      parts.isEmpty() -> newRecipientError.value = "Enter an email address."
      invalid.isNotEmpty() ->
          newRecipientError.value = "Not a valid email address: " + invalid.joinToString(", ")
      else -> {
        newRecipientError.value = null
        newRecipientState.value = ""
        coroutineScope.launch {
          for (part in parts) PreferencesManager.addRecipient(context, id, part)
        }
      }
    }
  }

  if (confirmRemoveState.value) {
    AlertDialog(
        onDismissRequest = { confirmRemoveState.value = false },
        title = { Text("Remove endpoint?") },
        text = { Text("\"${endpoint.label}\" and its recipients will be deleted.") },
        confirmButton = {
          TextButton(
              onClick = {
                confirmRemoveState.value = false
                onRemove()
              },
          ) {
            Text("Remove")
          }
        },
        dismissButton = {
          TextButton(onClick = { confirmRemoveState.value = false }) { Text("Cancel") }
        },
    )
  }

  Card(
      modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
          ),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      shape = MaterialTheme.shapes.medium,
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier =
                Modifier.weight(1f).clickable { expandedState.value = !expandedState.value },
        ) {
          Text(
              text = endpoint.label,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          val count = endpoint.toEmails.size
          Text(
              text =
                  (if (endpoint.enabled) "Enabled" else "Disabled") +
                      " · " +
                      (if (count == 1) "1 recipient" else "$count recipients"),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
            checked = endpoint.enabled,
            onCheckedChange = { checked -> update { setEnabled(checked) } },
        )
        IconButton(onClick = { expandedState.value = !expandedState.value }) {
          Icon(
              imageVector =
                  if (expandedState.value) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
              contentDescription = if (expandedState.value) "Collapse" else "Expand",
          )
        }
      }

      if (!expandedState.value) return@Column

      Spacer(modifier = Modifier.height(8.dp))

      OutlinedTextField(
          value = nameState.value,
          onValueChange = { nameState.value = it },
          label = { Text("Endpoint Name") },
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
          singleLine = true,
      )

      OutlinedTextField(
          value = smtpHostState.value,
          onValueChange = { smtpHostState.value = it },
          label = { Text("SMTP Host") },
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
          singleLine = true,
      )

      OutlinedTextField(
          value = smtpPortState.value,
          onValueChange = { smtpPortState.value = it },
          label = { Text("SMTP Port") },
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )

      ExposedDropdownMenuBox(
          expanded = encryptionExpandedState.value,
          onExpandedChange = { encryptionExpandedState.value = !encryptionExpandedState.value },
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
      ) {
        OutlinedTextField(
            value = encryptionLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Encryption") },
            trailingIcon = {
              ExposedDropdownMenuDefaults.TrailingIcon(expanded = encryptionExpandedState.value)
            },
            modifier =
                Modifier.menuAnchor(
                        type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                        enabled = true,
                    )
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = encryptionExpandedState.value,
            onDismissRequest = { encryptionExpandedState.value = false },
        ) {
          val modes =
              listOf(
                  "STARTTLS" to SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_STARTTLS,
                  "SMTPS (SSL/TLS)" to SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS,
                  "None" to SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_NONE,
              )
          for ((label, mode) in modes) {
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                  encryptionExpandedState.value = false
                  update { setEncryptionMode(mode) }
                },
            )
          }
        }
      }

      val warningText =
          when {
            endpoint.encryptionMode == SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_NONE ->
                "Warning: No encryption selected. This may expose credentials and email content."
            portNumber == 465 &&
                endpoint.encryptionMode != SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS ->
                "Warning: Port 465 is typically used with SMTPS (SSL/TLS)."
            portNumber != null &&
                portNumber != 465 &&
                endpoint.encryptionMode == SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS ->
                "Warning: SMTPS (SSL/TLS) typically uses port 465."
            else -> null
          }
      if (warningText != null) WarningText(warningText, bottomPadding = 4)
      // A port and encryption mode that do not match make every connection hang until it times
      // out. So the app offers a one-tap fix.
      val fix =
          when {
            portNumber == 465 &&
                endpoint.encryptionMode == SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_STARTTLS ->
                "Use SMTPS (SSL/TLS)" to SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS
            portNumber == 587 &&
                endpoint.encryptionMode == SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS ->
                "Use STARTTLS" to SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_STARTTLS
            else -> null
          }
      if (fix != null) {
        TextButton(
            onClick = { update { setEncryptionMode(fix.second) } },
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
          Text("Fix: ${fix.first}")
        }
      } else if (warningText != null) {
        Spacer(modifier = Modifier.height(8.dp))
      }

      OutlinedTextField(
          value = smtpUserState.value,
          onValueChange = { smtpUserState.value = it },
          label = { Text("SMTP Username") },
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      )

      if (endpoint.isGmail &&
          smtpUserState.value.isNotBlank() &&
          smtpUserState.value.trim() != fromEmailState.value.trim()) {
        WarningText(
            "Warning: For Gmail, the SMTP username should be your full email address " +
                "(same as From Email Address).")
      }
      if (endpoint.isICloud &&
          smtpUserState.value.isNotBlank() &&
          !smtpUserState.value.contains("@")) {
        WarningText(
            "Warning: For iCloud, the SMTP username should be your full iCloud Mail address, " +
                "for example name@icloud.com.")
      }

      OutlinedTextField(
          value = smtpPasswordState.value,
          onValueChange = { smtpPasswordState.value = it },
          label = { Text("SMTP Password") },
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
      )

      if (endpoint.isGmail && endpoint.smtpPassword.replace(" ", "").length != 16) {
        WarningText(
            "Your 16-digit Google \"App password\" needs to be entered, not your Google " +
                "Account password.",
            bottomPadding = 8)
        LinkText("Generate App Password", "https://myaccount.google.com/apppasswords", context)
      }
      if (endpoint.isICloud &&
          endpoint.smtpPassword.replace("-", "").replace(" ", "").length != 16) {
        WarningText(
            "An Apple \"app-specific password\" (format xxxx-xxxx-xxxx-xxxx) needs to be " +
                "entered, not your Apple Account password. Create it under Sign-In and " +
                "Security > App-Specific Passwords.",
            bottomPadding = 8)
        LinkText("Open Apple Account", "https://account.apple.com/account/manage", context)
      }

      OutlinedTextField(
          value = fromEmailState.value,
          onValueChange = { fromEmailState.value = it },
          label = { Text("From Email Address") },
          supportingText = {
            Text(
                when {
                  endpoint.isGmail ->
                      "The sender address of each forwarded email. Use your Gmail address, " +
                          "the same as the SMTP Username. Gmail replaces other addresses " +
                          "unless you set them up as aliases in Gmail."
                  endpoint.isICloud ->
                      "The sender address of each forwarded email. Use your iCloud Mail " +
                          "address or one of its aliases. iCloud rejects other addresses."
                  else ->
                      "The sender address of each forwarded email. Most servers accept only " +
                          "the address of the account you log in with."
                } +
                    " This is not where the SMS goes: add those addresses under Recipients. " +
                    "The SMS phone number appears as the sender name.")
          },
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      )

      Text(
          text = "Recipients",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 4.dp),
      )
      if (endpoint.toEmails.isEmpty()) {
        WarningText("Add at least one recipient. This endpoint sends nothing until then.")
      }
      for (address in endpoint.toEmails) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              text = address,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.weight(1f),
          )
          IconButton(
              onClick = {
                coroutineScope.launch {
                  PreferencesManager.removeRecipient(context, id, address)
                }
              },
          ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove $address")
          }
        }
      }

      Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
            value = newRecipientState.value,
            onValueChange = {
              newRecipientState.value = it
              newRecipientError.value = null
            },
            label = { Text("Add Recipient Address") },
            supportingText = {
              if (newRecipientState.value.isNotBlank() && newRecipientError.value == null) {
                Text("Not added yet. Tap + or Done to add it.")
              }
            },
            // Leaving the field also adds the typed address, so that an address is never lost
            // because the user did not tap +.
            modifier =
                Modifier.weight(1f).onFocusChanged { focus ->
                  if (!focus.isFocused && newRecipientState.value.isNotBlank()) addRecipient()
                },
            singleLine = true,
            isError = newRecipientError.value != null,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addRecipient() }),
        )
        IconButton(onClick = { addRecipient() }) {
          Icon(Icons.Filled.Add, contentDescription = "Add recipient")
        }
      }
      newRecipientError.value?.let { WarningText(it) }

      Spacer(modifier = Modifier.height(12.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
            onClick = { queueTestEmail(context, coroutineScope, endpointId = id) },
            modifier = Modifier.weight(1f),
        ) {
          Text("Test This Endpoint")
        }
        TextButton(onClick = { confirmRemoveState.value = true }) {
          Icon(Icons.Filled.Delete, contentDescription = null)
          Text("Remove", modifier = Modifier.padding(start = 4.dp))
        }
      }
    }
  }
}

/**
 * Puts a test email in the outbox. With [endpointId], only that endpoint sends it. Otherwise every
 * enabled endpoint sends it.
 */
private fun queueTestEmail(context: Context, scope: CoroutineScope, endpointId: String?) {
  scope.launch {
    val result =
        withContext(Dispatchers.IO) {
          ForwardQueue.enqueue(context, "[TEST]", "This is a test email.", endpointId)
        }
    if (result.emails > 0) {
      Toast.makeText(
              context,
              "Test queued: ${result.emails} email(s). It sends as soon as the network allows.",
              Toast.LENGTH_SHORT)
          .show()
    }
  }
}

@Composable
private fun WarningText(text: String, bottomPadding: Int = 12) {
  Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.padding(bottom = bottomPadding.dp),
  )
}

@Composable
private fun LinkText(text: String, url: String, context: Context) {
  Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.primary,
      textDecoration = TextDecoration.Underline,
      modifier =
          Modifier.padding(bottom = 12.dp).clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
          },
  )
}

@Composable
private fun rememberPreferenceTextState(
    storedValue: String,
    debounceMillis: Long = 500L,
    onPersist: suspend (String) -> Unit,
): MutableState<String> {
  val inputState = rememberSaveable { mutableStateOf(storedValue) }
  // The value this field saved most recently. When the store reports this value back, it is an
  // echo of the field's own save. The user may have typed more since then, so the echo must not
  // replace the newer text.
  val lastPersisted = rememberSaveable { mutableStateOf(storedValue) }

  LaunchedEffect(storedValue) {
    if (storedValue != inputState.value && storedValue != lastPersisted.value) {
      inputState.value = storedValue
    }
  }

  LaunchedEffect(inputState.value, storedValue) {
    val currentValue = inputState.value
    if (currentValue == storedValue) return@LaunchedEffect
    delay(debounceMillis)
    if (currentValue == inputState.value && currentValue != storedValue) {
      lastPersisted.value = currentValue
      onPersist(currentValue)
    }
  }

  return inputState
}

@Preview(showBackground = true)
@Composable
fun MailPreferencesScreenPreview() {
  SMS2EmailTheme {
    // Note: Preview doesn't have access to actual SharedPreferences
    // In a real preview, you'd need to mock the context
  }
}
