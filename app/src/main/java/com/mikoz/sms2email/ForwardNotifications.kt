package io.github.sms2email.sms2email

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat

/** All notifications about forwarding. Each method is safe to call from a background thread. */
object ForwardNotifications {
  const val SENDING_NOTIFICATION_ID = 3
  private const val NOTHING_TO_SEND_ID = 1
  private const val ACCEPTED_ID_BASE = 2_000_000
  private const val ENDPOINT_PROBLEM_ID_BASE = 1000

  private fun manager(context: Context): NotificationManager {
    NotificationHelper.createNotificationChannel(context)
    return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  }

  private fun builder(context: Context) =
      NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
          .setSmallIcon(android.R.drawable.ic_dialog_email)

  private fun endpointProblemId(endpoint: SmtpConfig) =
      ENDPOINT_PROBLEM_ID_BASE + Math.floorMod(endpoint.id.hashCode(), 100_000)

  fun buildSendingNotification(context: Context): Notification {
    NotificationHelper.createNotificationChannel(context)
    return builder(context).setContentTitle("Forwarding SMS by email ...").setOngoing(true).build()
  }

  fun showNothingToSend(context: Context, isTest: Boolean) {
    val text =
        if (isTest) "This endpoint has no recipient. Add one first."
        else "No enabled email endpoint with a recipient is configured. Open SMS2Email to add one."
    showToast(context, "Nothing to send\n$text")
    manager(context)
        .notify(
            NOTHING_TO_SEND_ID,
            builder(context).setContentTitle("SMS not forwarded").setContentText(text).build())
  }

  /**
   * Shows how many emails for one SMS the SMTP servers accepted so far. A server can still bounce
   * an accepted email later, so the text does not claim delivery to the inbox.
   */
  fun showAccepted(
      context: Context,
      smsId: String,
      sender: String,
      body: String,
      accepted: Int,
      total: Int,
  ) {
    // On a locked screen, show this version without the SMS text. SMS can hold codes.
    val publicVersion = builder(context).setContentTitle("SMS forwarded by email").build()
    manager(context)
        .notify(
            ACCEPTED_ID_BASE + Math.floorMod(smsId.hashCode(), 1_000_000),
            builder(context)
                .setContentTitle(
                    "SMS from $sender: accepted by SMTP server for $accepted/$total recipients")
                .setContentText(body)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build())
  }

  fun showRetrying(
      context: Context,
      endpoint: SmtpConfig,
      recipient: String,
      error: Throwable,
      attempt: Int,
  ) {
    manager(context)
        .notify(
            endpointProblemId(endpoint),
            builder(context)
                .setContentTitle("Still trying to send via ${endpoint.label}")
                .setContentText("$recipient: ${error.message}")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            "Attempt $attempt failed for $recipient: ${error.message}\n\n" +
                                "The email stays in the outbox. SMS2Email tries again later."))
                .build())
  }

  fun showFailed(
      context: Context,
      endpoint: SmtpConfig,
      recipient: String,
      sender: String,
      error: Throwable,
      kind: FailureKind,
  ) {
    val reason =
        if (kind == FailureKind.PERMANENT)
            "The server refused the email. Check the endpoint settings and the address."
        else "SMS2Email gave up after ${ForwardWorker.MAX_ATTEMPTS} attempts."
    showToast(context, "Failed to send email via ${endpoint.label}\n${error.message}")
    manager(context)
        .notify(
            endpointProblemId(endpoint),
            builder(context)
                .setContentTitle("SMS from $sender not sent via ${endpoint.label}")
                .setContentText("$recipient: ${error.message}")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            "$recipient: ${error.message}\n\n$reason\n\n" +
                                error.stackTraceToString()))
                .build())
  }

  /** Removes an old problem notice for this endpoint, because a send through it worked. */
  fun clearEndpointProblem(context: Context, endpoint: SmtpConfig) {
    manager(context).cancel(endpointProblemId(endpoint))
  }

  private fun showToast(context: Context, text: String) {
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
  }
}
