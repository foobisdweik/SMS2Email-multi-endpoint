package io.github.sms2email.sms2email

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
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
        val body = ForwardQueue.readBody(applicationContext, input)

        val endpoint = PreferencesManager.getEndpoints(applicationContext).find { it.id == endpointId }
        // The user removed or disabled the endpoint, or removed the recipient, after the SMS
        // arrived. The user no longer wants this email, so it is dropped without an error.
        if (endpoint == null ||
            (!endpoint.enabled && !ignoreEnabled) ||
            recipient !in endpoint.toEmails) {
          return@withContext drop()
        }

        if (body == null) {
          // The saved SMS text is missing, so the email cannot be built. Report it, because the
          // user would otherwise never learn that this SMS was not forwarded.
          ForwardNotifications.showFailed(
              applicationContext,
              id,
              endpoint,
              recipient,
              sender,
              IllegalStateException("The saved SMS text is missing."),
              FailureKind.PERMANENT)
          return@withContext Result.failure()
        }

        try {
          SmtpClient.send(endpoint, recipient, sender, body, receivedAt, messageId)
        } catch (e: Exception) {
          val kind = SmtpClient.classify(e)
          val attempt = runAttemptCount + 1
          return@withContext if (kind == FailureKind.TEMPORARY && attempt < MAX_ATTEMPTS) {
            // Report only repeated temporary failures. A single network hiccup is normal.
            if (attempt >= 3) {
              ForwardNotifications.showRetrying(applicationContext, id, endpoint, recipient, e, attempt)
            }
            Result.retry()
          } else {
            ForwardNotifications.showFailed(applicationContext, id, endpoint, recipient, sender, e, kind)
            Result.failure()
          }
        }

        // Removes this job's own "still trying" notice. Other jobs keep their notices.
        ForwardNotifications.clearJobProblem(applicationContext, id)
        showProgress(smsId, sender, body)
        Result.success()
      }

  private fun drop(): Result = Result.failure()

  /** Updates the notification for this SMS with the number of accepted emails so far. */
  private suspend fun showProgress(smsId: String, sender: String, body: String) {
    val total =
        WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagFlow(ForwardQueue.SMS_TAG_PREFIX + smsId)
            .first()
            .size
    // The count is kept in one shared counter instead of being read from the job states. Two jobs
    // that finish at the same moment could both read the states before either is marked done.
    val accepted = AcceptedCounter.increment(applicationContext, smsId, total)
    ForwardNotifications.showAccepted(applicationContext, smsId, sender, body, accepted, total)
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

/**
 * Counts the accepted emails of each SMS. The count is saved, so it stays right when Android
 * restarts the app between jobs. The lock makes each increment complete before the next one reads.
 */
internal object AcceptedCounter {
  private const val PREFS = "accepted_counts"
  private val lock = Any()

  fun increment(context: Context, smsId: String, total: Int): Int =
      synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(smsId, 0) + 1
        val editor = prefs.edit()
        // When all emails of the SMS are counted, the entry is not needed any more.
        if (count >= total) editor.remove(smsId) else editor.putInt(smsId, count)
        editor.commit()
        count
      }
}
