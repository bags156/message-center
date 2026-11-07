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
    static final String COPY = "io.github.bags156.messagecenter.COPY_CODE";

    @Override public void onReceive(Context context, Intent intent) {
        String chat = intent.getStringExtra("chat");
        if (chat == null || chat.isEmpty()) return;
        if (REPLY.equals(intent.getAction())) {
            Bundle input = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = input == null ? null : input.getCharSequence(PairedService.KEY_REPLY);
            if (text == null || text.toString().trim().isEmpty()) return;
            PendingResult pending = goAsync();
            PairedService.replyFromNotification(context.getApplicationContext(), chat, text.toString().trim(), pending::finish);
        } else if (COPY.equals(intent.getAction())) {
            String code = intent.getStringExtra("code");
            if (code == null || code.isEmpty()) return;
            android.content.ClipData clip = android.content.ClipData.newPlainText("Code", code);
            // Marked sensitive so Android doesn't show the code in its clipboard preview.
            android.os.PersistableBundle extras = new android.os.PersistableBundle(); extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
            context.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
        } else if (READ.equals(intent.getAction())) {
            PendingResult pending = goAsync();
            PairedService.markReadFromNotification(context.getApplicationContext(), chat, pending::finish);
        }
    }
}
