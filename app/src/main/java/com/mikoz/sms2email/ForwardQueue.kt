package io.github.sms2email.sms2email

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The outbox. Each received SMS becomes one WorkManager job for each recipient of each enabled
 * endpoint. WorkManager saves the jobs in its own database, so they survive app restarts and
 * reboots, and it retries them when they fail for a temporary reason.
 */
object ForwardQueue {
  const val TAG = "sms2email-forward"
  const val SMS_TAG_PREFIX = "sms2email-sms-"

  const val KEY_SMS_ID = "sms_id"
  const val KEY_MESSAGE_ID = "message_id"
  const val KEY_ENDPOINT_ID = "endpoint_id"
  const val KEY_RECIPIENT = "recipient"
  const val KEY_SENDER = "sender"
  const val KEY_BODY = "body"
  const val KEY_BODY_FILE = "body_file"
  const val KEY_RECEIVED_AT = "received_at"
  const val KEY_IGNORE_ENABLED = "ignore_enabled"

  // WorkManager limits the input data of one job to 10,240 bytes. A longer SMS body is saved in a
  // file, and the job stores only the file name.
  private const val MAX_INLINE_BODY_BYTES = 6_000
  private const val OUTBOX_DIR = "outbox"
  private const val OUTBOX_FILE_MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000

  private const val INITIAL_BACKOFF_SECONDS = 30L

  /** The result of [enqueue]: how many emails were queued, and through how many endpoints. */
  data class Enqueued(val emails: Int, val endpoints: Int)

  /**
   * Queues one email for each recipient of each enabled endpoint. With [onlyEndpointId], it queues
   * only that endpoint, whether it is enabled or not. The "Test" button uses that.
   *
   * This blocks while it reads the settings and writes the jobs, so call it off the main thread.
   */
  @JvmStatic
  @JvmOverloads
  fun enqueue(
      context: Context,
      sender: String,
      body: String,
      onlyEndpointId: String? = null,
  ): Enqueued {
    val appContext = context.applicationContext
    val endpoints =
        PreferencesManager.getEndpointsBlocking(appContext).filter {
          if (onlyEndpointId != null) it.id == onlyEndpointId else it.enabled
        }
    val smsId = UUID.randomUUID().toString()
    val receivedAt = System.currentTimeMillis()
    val bodyData = storeBody(appContext, smsId, body)

    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val requests =
        endpoints.flatMap { endpoint ->
          endpoint.toEmails.map { recipient ->
            val input =
                Data.Builder()
                    .putAll(bodyData)
                    .putString(KEY_SMS_ID, smsId)
                    .putString(KEY_MESSAGE_ID, "<${UUID.randomUUID()}@sms2email.local>")
                    .putString(KEY_ENDPOINT_ID, endpoint.id)
                    .putString(KEY_RECIPIENT, recipient)
                    .putString(KEY_SENDER, sender)
                    .putLong(KEY_RECEIVED_AT, receivedAt)
                    .putBoolean(KEY_IGNORE_ENABLED, onlyEndpointId != null)
                    .build()
            OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(input)
                .setConstraints(constraints)
                // Expedited jobs start at once when the network is up. If the app has used its
                // quota of expedited jobs, the job runs as normal background work instead.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag(SMS_TAG_PREFIX + smsId)
                .build()
          }
        }

    if (requests.isEmpty()) {
      ForwardNotifications.showNothingToSend(appContext, onlyEndpointId != null)
      deleteBodyFile(appContext, bodyData.getString(KEY_BODY_FILE))
      return Enqueued(0, 0)
    }
    // Waiting for the result makes sure that the jobs are saved before the SMS receiver ends.
    WorkManager.getInstance(appContext).enqueue(requests).result.get()
    deleteOldBodyFiles(appContext)
    return Enqueued(requests.size, endpoints.count { it.toEmails.isNotEmpty() })
  }

  /** The number of emails that wait to be sent or are being sent. */
  fun pendingCountFlow(context: Context): Flow<Int> =
      WorkManager.getInstance(context.applicationContext).getWorkInfosByTagFlow(TAG).map { infos ->
        infos.count {
          it.state == WorkInfo.State.ENQUEUED ||
              it.state == WorkInfo.State.RUNNING ||
              it.state == WorkInfo.State.BLOCKED
        }
      }

  /** Removes every email that waits to be sent. Emails that are being sent now may still go out. */
  fun cancelAll(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG)
  }

  private fun storeBody(context: Context, smsId: String, body: String): Data {
    if (body.toByteArray(Charsets.UTF_8).size <= MAX_INLINE_BODY_BYTES) {
      return Data.Builder().putString(KEY_BODY, body).build()
    }
    val dir = File(context.filesDir, OUTBOX_DIR).apply { mkdirs() }
    val file = File(dir, "$smsId.txt")
    file.writeText(body, Charsets.UTF_8)
    return Data.Builder().putString(KEY_BODY_FILE, file.name).build()
  }

  /** Reads the SMS body of a job, from its input data or from its file. */
  fun readBody(context: Context, input: Data): String? {
    input.getString(KEY_BODY)?.let {
      return it
    }
    val name = input.getString(KEY_BODY_FILE) ?: return null
    val file = File(File(context.filesDir, OUTBOX_DIR), name)
    return if (file.exists()) file.readText(Charsets.UTF_8) else null
  }

  private fun deleteBodyFile(context: Context, name: String?) {
    if (name != null) File(File(context.filesDir, OUTBOX_DIR), name).delete()
  }

  /**
   * Deletes body files that are older than any job can live. Jobs give up long before this age,
   * so no job still needs these files.
   */
  private fun deleteOldBodyFiles(context: Context) {
    val cutoff = System.currentTimeMillis() - OUTBOX_FILE_MAX_AGE_MILLIS
    File(context.filesDir, OUTBOX_DIR).listFiles()?.forEach {
      if (it.lastModified() < cutoff) it.delete()
    }
  }
}
