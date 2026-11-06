package io.github.sms2email.sms2email;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * Sends one SMS to every enabled endpoint. Each endpoint uses its own SMTP account and sends in its
 * own thread, so a slow or broken account does not delay or block the other accounts.
 */
public class MailSender {
  private static final int SUCCESS_NOTIFICATION_ID = 2;
  private static final int NO_ENDPOINT_NOTIFICATION_ID = 1;
  private static final int FAILURE_NOTIFICATION_ID_BASE = 1000;
  private static final String SOCKET_TIMEOUT_MILLIS = "20000";

  /** The outcome of one endpoint for one SMS. */
  private static final class EndpointResult {
    final SmtpConfig endpoint;
    int delivered = 0;
    final List<String> errors = new ArrayList<>();
    final StringBuilder details = new StringBuilder();

    EndpointResult(SmtpConfig endpoint) {
      this.endpoint = endpoint;
    }
  }

  /** Sends to all enabled endpoints in the background. */
  public void send(Context context, String subject, String content) {
    send(context, subject, content, null);
  }

  /**
   * Sends to all enabled endpoints in the background. Then it runs {@code onComplete}, also when
   * sending failed.
   */
  public void send(
      Context context, String subject, String content, @Nullable Runnable onComplete) {
    new Thread(
            () -> {
              try {
                List<SmtpConfig> targets = new ArrayList<>();
                for (SmtpConfig endpoint : PreferencesManager.getEndpointsBlocking(context)) {
                  if (endpoint.getEnabled()) targets.add(endpoint);
                }
                sendToAll(context, subject, content, targets);
              } finally {
                if (onComplete != null) onComplete.run();
              }
            })
        .start();
  }

  /** Sends to one endpoint only, whether it is enabled or not. The UI uses this for a test. */
  public void sendToEndpoint(Context context, String endpointId, String subject, String content) {
    new Thread(
            () -> {
              List<SmtpConfig> targets = new ArrayList<>();
              for (SmtpConfig endpoint : PreferencesManager.getEndpointsBlocking(context)) {
                if (endpoint.getId().equals(endpointId)) targets.add(endpoint);
              }
              sendToAll(context, subject, content, targets);
            })
        .start();
  }

  private void sendToAll(
      Context context, String subject, String content, List<SmtpConfig> targets) {
    NotificationHelper.createNotificationChannel(context);

    if (targets.isEmpty()) {
      String text = "No enabled email endpoint is configured. Open SMS2Email to add one.";
      showToast(context, "Failed to send email\n" + text);
      notify(
          context,
          NO_ENDPOINT_NOTIFICATION_ID,
          new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
              .setContentTitle("Failed to send email")
              .setContentText(text)
              .setSmallIcon(android.R.drawable.ic_dialog_email));
      return;
    }

    List<EndpointResult> results = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    for (SmtpConfig endpoint : targets) {
      EndpointResult result = new EndpointResult(endpoint);
      results.add(result);
      Thread thread = new Thread(() -> sendWithEndpoint(result, subject, content));
      threads.add(thread);
      thread.start();
    }
    for (Thread thread : threads) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    int totalDelivered = 0;
    int totalRecipients = 0;
    StringBuilder failureToast = new StringBuilder();
    for (EndpointResult result : results) {
      totalDelivered += result.delivered;
      totalRecipients += Math.max(result.endpoint.getToEmails().size(), 1);
      int notificationId = failureNotificationId(result.endpoint);
      if (result.errors.isEmpty()) {
        // Remove an old failure notice for this endpoint, because it works now.
        cancel(context, notificationId);
        continue;
      }
      String label = result.endpoint.getLabel();
      String summary = String.join("\n", result.errors);
      failureToast.append("\n").append(label).append(": ").append(result.errors.get(0));
      notify(
          context,
          notificationId,
          new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
              .setContentTitle("Failed to send email via " + label)
              .setContentText(result.errors.get(0))
              .setStyle(
                  new NotificationCompat.BigTextStyle()
                      .bigText(summary + "\n\n" + result.details))
              .setSmallIcon(android.R.drawable.ic_dialog_email));
    }

    if (failureToast.length() > 0) {
      showToast(context, "Failed to send email" + failureToast);
    }
    if (totalDelivered > 0) {
      notify(
          context,
          SUCCESS_NOTIFICATION_ID,
          new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
              .setContentTitle(
                  "Transferred SMS from "
                      + subject
                      + " ("
                      + totalDelivered
                      + "/"
                      + totalRecipients
                      + " recipients)")
              .setContentText(content)
              .setSmallIcon(android.R.drawable.ic_dialog_email));
    }
  }

  private static Properties buildProperties(SmtpConfig config) {
    final Properties prop = new Properties();
    prop.put("mail.smtp.host", config.getSmtpHost());
    prop.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
    prop.put("mail.smtp.auth", "true");
    // Without timeouts, a server that does not answer would block this thread for a long time.
    prop.put("mail.smtp.connectiontimeout", SOCKET_TIMEOUT_MILLIS);
    prop.put("mail.smtp.timeout", SOCKET_TIMEOUT_MILLIS);
    prop.put("mail.smtp.writetimeout", SOCKET_TIMEOUT_MILLIS);

    // Configure transport encryption.
    switch (config.getEncryptionMode()) {
      case SMTP_ENCRYPTION_MODE_NONE:
        prop.setProperty("mail.smtp.starttls.enable", "false");
        prop.setProperty("mail.smtp.ssl.enable", "false"); // enabled by default
        break;
      case SMTP_ENCRYPTION_MODE_SMTPS:
        prop.setProperty("mail.smtp.starttls.enable", "false");
        prop.setProperty("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        prop.setProperty("mail.smtp.socketFactory.fallback", "false");
        prop.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        break;
      case SMTP_ENCRYPTION_MODE_STARTTLS:
      default:
        prop.setProperty("mail.smtp.starttls.enable", "true");
        prop.setProperty("mail.smtp.starttls.required", "true");
        prop.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        break;
    }
    return prop;
  }

  /**
   * Opens one SMTP connection for the endpoint and sends a separate message to each recipient. A
   * rejected address therefore does not stop delivery to the other addresses.
   */
  private static void sendWithEndpoint(EndpointResult result, String subject, String content) {
    SmtpConfig config = result.endpoint;
    if (config.getToEmails().isEmpty()) {
      result.errors.add("No recipient address is configured.");
      return;
    }

    Session session = Session.getInstance(buildProperties(config));
    Transport transport = null;
    try {
      transport = session.getTransport("smtp");
      transport.connect(
          config.getSmtpHost(),
          config.getSmtpPort(),
          config.getSmtpUser(),
          config.getSmtpPassword());

      InternetAddress from = new InternetAddress(config.getFromEmail(), subject, "UTF-8");
      for (String recipient : config.getToEmails()) {
        try {
          Message message = new MimeMessage(session);
          message.setFrom(from);
          message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
          message.setSubject("SMS from " + subject);
          message.setText(content);
          message.saveChanges();
          transport.sendMessage(message, message.getAllRecipients());
          result.delivered++;
        } catch (Exception e) {
          recordError(result, recipient + ": " + e.getMessage(), e);
        }
      }
    } catch (Exception e) {
      recordError(result, String.valueOf(e.getMessage()), e);
    } finally {
      if (transport != null) {
        try {
          transport.close();
        } catch (Exception ignored) {
          // The messages were already sent or already failed. A close error changes nothing.
        }
      }
    }
  }

  private static void recordError(EndpointResult result, String message, Exception e) {
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    result.errors.add(message);
    result.details.append(sw).append("\n");
  }

  private static int failureNotificationId(SmtpConfig endpoint) {
    return FAILURE_NOTIFICATION_ID_BASE + Math.floorMod(endpoint.getId().hashCode(), 100000);
  }

  private static void notify(Context context, int id, NotificationCompat.Builder builder) {
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    notificationManager.notify(id, builder.build());
  }

  private static void cancel(Context context, int id) {
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    notificationManager.cancel(id);
  }

  private static void showToast(Context context, String text) {
    new Handler(Looper.getMainLooper())
        .post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
  }
}
