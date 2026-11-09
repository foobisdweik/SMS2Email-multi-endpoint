package io.github.sms2email.sms2email;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.Objects;

public class SmsReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (!Objects.equals(intent.getAction(), Telephony.Sms.Intents.SMS_RECEIVED_ACTION)) return;

    SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
    String sender = messages[0].getOriginatingAddress();
    StringBuilder bodyText = new StringBuilder();
    for (SmsMessage message : messages) {
      bodyText.append(message.getMessageBody());
    }
    String body = bodyText.toString();

    // Saving the jobs reads the settings and writes a database, so it runs off the main thread.
    // goAsync() keeps the process alive until the jobs are saved. That takes well under a second,
    // far below Android's 10 s limit. The emails themselves are sent later by ForwardWorker.
    final PendingResult pendingResult = goAsync();
    final Context appContext = context.getApplicationContext();
    new Thread(
            () -> {
              try {
                ForwardQueue.enqueue(appContext, sender, body);
              } finally {
                pendingResult.finish();
              }
            })
        .start();
  }
}
