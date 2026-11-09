package io.github.sms2email.sms2email

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.UUID

/** All notifications about forwarding. Each method is safe to call from a background thread. */
object ForwardNotifications {
  const val SENDING_NOTIFICATION_ID = 3
  private const val NOTHING_TO_SEND_ID = 1
  private const val ACCEPTED_ID_BASE = 2_000_000
  private const val JOB_PROBLEM_ID_BASE = 1000

  private fun manager(context: Context): NotificationManager {
    NotificationHelper.createNotificationChannel(context)
    return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  }

  private fun builder(context: Context) =
      NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
          .setSmallIcon(android.R.drawable.ic_dialog_email)

  /**
   * Each job (one SMS, one endpoint, one recipient) has its own problem notification. A success of
   * one job therefore never hides the failure of another job.
   */
  private fun jobProblemId(jobId: UUID) =
      JOB_PROBLEM_ID_BASE + Math.floorMod(jobId.hashCode(), 1_000_000)

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
      jobId: UUID,
      endpoint: SmtpConfig,
      recipient: String,
      error: Throwable,
      attempt: Int,
  ) {
    manager(context)
        .notify(
            jobProblemId(jobId),
            builder(context)
                .setContentTitle("Still trying to send via ${endpoint.label}")
                .setContentText("$recipient: ${error.message}")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            "Attempt $attempt failed for $recipient: ${error.message}\n\n" +
                                mismatchHint(endpoint) +
                                "The email stays in the outbox. SMS2Email tries again later."))
                .build())
  }

  fun showFailed(
      context: Context,
      jobId: UUID,
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
            jobProblemId(jobId),
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

  /**
   * A port and encryption mode that do not match make each connection hang until it times out.
   * That looks like a network problem, so the notice names the likely cause.
   */
  private fun mismatchHint(endpoint: SmtpConfig): String =
      when {
        endpoint.smtpPort == 465 &&
            endpoint.encryptionMode != SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS ->
            "Likely cause: port 465 needs the encryption \"SMTPS (SSL/TLS)\".\n\n"
        endpoint.smtpPort == 587 &&
            endpoint.encryptionMode == SmtpEncryptionMode.SMTP_ENCRYPTION_MODE_SMTPS ->
            "Likely cause: port 587 needs the encryption \"STARTTLS\".\n\n"
        else -> ""
      }

  /** Removes the "still trying" notice of one job, because that job succeeded. */
  fun clearJobProblem(context: Context, jobId: UUID) {
    manager(context).cancel(jobProblemId(jobId))
  }

  private fun showToast(context: Context, text: String) {
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
  }
}
