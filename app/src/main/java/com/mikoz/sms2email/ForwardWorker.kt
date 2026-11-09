package io.github.sms2email.sms2email

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Sends one queued email: one SMS to one recipient through one endpoint. It reads the endpoint
 * settings when it runs, so a corrected password also applies to emails that already wait.
 */
class ForwardWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

  companion object {
    /** After this many failed attempts, the worker gives up. With backoff, this spans about a day. */
    const val MAX_ATTEMPTS = 12
  }

  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        val input = inputData
        val endpointId = input.getString(ForwardQueue.KEY_ENDPOINT_ID) ?: return@withContext drop()
        val recipient = input.getString(ForwardQueue.KEY_RECIPIENT) ?: return@withContext drop()
        val sender = input.getString(ForwardQueue.KEY_SENDER).orEmpty()
        val smsId = input.getString(ForwardQueue.KEY_SMS_ID).orEmpty()
        val messageId = input.getString(ForwardQueue.KEY_MESSAGE_ID) ?: "<$id@sms2email.local>"
        val receivedAt = input.getLong(ForwardQueue.KEY_RECEIVED_AT, System.currentTimeMillis())
        val ignoreEnabled = input.getBoolean(ForwardQueue.KEY_IGNORE_ENABLED, false)
        val body = ForwardQueue.readBody(applicationContext, input) ?: return@withContext drop()

        val endpoint = PreferencesManager.getEndpoints(applicationContext).find { it.id == endpointId }
        // The user removed or disabled the endpoint, or removed the recipient, after the SMS
        // arrived. The user no longer wants this email, so it is dropped without an error.
        if (endpoint == null ||
            (!endpoint.enabled && !ignoreEnabled) ||
            recipient !in endpoint.toEmails) {
          return@withContext drop()
        }

        try {
          SmtpClient.send(endpoint, recipient, sender, body, receivedAt, messageId)
        } catch (e: Exception) {
          val kind = SmtpClient.classify(e)
          val attempt = runAttemptCount + 1
          return@withContext if (kind == FailureKind.TEMPORARY && attempt < MAX_ATTEMPTS) {
            // Report only repeated temporary failures. A single network hiccup is normal.
            if (attempt >= 3) {
              ForwardNotifications.showRetrying(applicationContext, endpoint, recipient, e, attempt)
            }
            Result.retry()
          } else {
            ForwardNotifications.showFailed(applicationContext, endpoint, recipient, sender, e, kind)
            Result.failure()
          }
        }

        ForwardNotifications.clearEndpointProblem(applicationContext, endpoint)
        showProgress(smsId, sender, body)
        Result.success()
      }

  private fun drop(): Result = Result.failure()

  /** Updates the notification for this SMS with the number of accepted emails so far. */
  private suspend fun showProgress(smsId: String, sender: String, body: String) {
    val infos =
        WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagFlow(ForwardQueue.SMS_TAG_PREFIX + smsId)
            .first()
    // This job still counts as running here, so it is added to the succeeded count by hand.
    val accepted = infos.count { it.state == WorkInfo.State.SUCCEEDED && it.id != id } + 1
    ForwardNotifications.showAccepted(applicationContext, smsId, sender, body, accepted, infos.size)
  }

  /**
   * Android 11 and older run expedited jobs as a foreground service, and that needs a
   * notification. Android 12 and newer do not call this.
   */
  override suspend fun getForegroundInfo(): ForegroundInfo {
    val notification = ForwardNotifications.buildSendingNotification(applicationContext)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(
          ForwardNotifications.SENDING_NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(ForwardNotifications.SENDING_NOTIFICATION_ID, notification)
    }
  }
}
