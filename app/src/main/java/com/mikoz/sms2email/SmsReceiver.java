package io.github.sms2email.sms2email;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class SmsReceiver extends BroadcastReceiver {
  // The telephony stack can send SMS_RECEIVED as a foreground broadcast. Android treats a
  // receiver that holds such a broadcast for about 10 s as not responding, and goAsync() does not
  // extend that limit. So the receiver releases the broadcast after this time at the latest.
  // Sending threads that still run after this point keep running, as in older app versions.
  private static final long MAX_ASYNC_MILLIS = 7_000L;

  protected MailSender mailSender = new MailSender();

  @Override
  public void onReceive(Context context, Intent intent) {
    if (Objects.equals(intent.getAction(), Telephony.Sms.Intents.SMS_RECEIVED_ACTION)) {
      SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
      String sender = messages[0].getOriginatingAddress();
      StringBuilder bodyText = new StringBuilder();
      for (SmsMessage message : messages) {
        bodyText.append(message.getMessageBody());
      }
      String message = bodyText.toString();

      // goAsync() tells Android that this receiver is still working after onReceive() returns.
      // Without it, Android may stop the app process before all endpoints have finished sending.
      final PendingResult pendingResult = goAsync();
      final AtomicBoolean finished = new AtomicBoolean(false);
      final Runnable finishOnce =
          () -> {
            if (finished.compareAndSet(false, true)) pendingResult.finish();
          };
      new Handler(Looper.getMainLooper()).postDelayed(finishOnce, MAX_ASYNC_MILLIS);
      mailSender.send(context.getApplicationContext(), sender, message, finishOnce);
    }
  }
}
