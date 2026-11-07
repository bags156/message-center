package io.github.bags156.messagecenter;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Reply and Mark as read buttons on message notifications (and in Android Auto). */
public final class NotificationActions extends BroadcastReceiver {
    static final String REPLY = "io.github.bags156.messagecenter.REPLY";
    static final String READ = "io.github.bags156.messagecenter.MARK_READ";

    @Override public void onReceive(Context context, Intent intent) {
        String chat = intent.getStringExtra("chat");
        if (chat == null || chat.isEmpty()) return;
        if (REPLY.equals(intent.getAction())) {
            Bundle input = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = input == null ? null : input.getCharSequence(PairedService.KEY_REPLY);
            if (text == null || text.toString().trim().isEmpty()) return;
            PendingResult pending = goAsync();
            PairedService.replyFromNotification(context.getApplicationContext(), chat, text.toString().trim(), pending::finish);
        } else if (READ.equals(intent.getAction())) {
            PendingResult pending = goAsync();
            PairedService.markReadFromNotification(context.getApplicationContext(), chat, pending::finish);
        }
    }
}
