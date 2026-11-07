package io.github.bags156.messagecenter;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONObject;
import java.io.File;
import java.text.DateFormat;
import java.util.List;

/** Home screen widget with your newest chats (pinned first). Tap one to open it, + to start a chat. */
public final class MessagesWidget extends AppWidgetProvider {
    static final String NEW_CHAT = "io.github.bags156.messagecenter.NEW_CHAT";
    private static final int ROWS = 4;
    private static final int[] ROW = {R.id.row0, R.id.row1, R.id.row2, R.id.row3}, AVATAR = {R.id.avatar0, R.id.avatar1, R.id.avatar2, R.id.avatar3},
        NAME = {R.id.name0, R.id.name1, R.id.name2, R.id.name3}, PREVIEW = {R.id.preview0, R.id.preview1, R.id.preview2, R.id.preview3},
        TIME = {R.id.time0, R.id.time1, R.id.time2, R.id.time3};

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { update(context); }

    /** Redraws every Message Center widget on the home screen (does nothing if there are none). */
    static void update(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MessagesWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_messages);
        views.setOnClickPendingIntent(R.id.widget_title, open(context, "", 10));
        views.setOnClickPendingIntent(R.id.widget_new, PendingIntent.getActivity(context, 11,
            new Intent(context, PairedActivity.class).setAction(NEW_CHAT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE));
        List<JSONObject> chats;
        ChatSettings settings = new ChatSettings(context);
        try (PairedStore store = new PairedStore(context)) { chats = InboxOrder.sort(store.chats("", 1), settings::pinned); }
        catch (Exception e) { chats = java.util.Collections.emptyList(); }
        views.setViewVisibility(R.id.widget_empty, chats.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < ROWS; i++) {
            if (i >= chats.size()) { views.setViewVisibility(ROW[i], View.GONE); continue; }
            JSONObject c = chats.get(i); String id = c.optString("conversationID"), title = PairedProtocol.title(c);
            boolean unread = settings.unread(c);
            CharSequence name = title;
            if (unread) { SpannableString s = new SpannableString(title); s.setSpan(new StyleSpan(Typeface.BOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); name = s; }
            String preview = PairedProtocol.string(c.optJSONObject("latestMessage"), "displayContent");
            if (PairedProtocol.latestFromMe(c) && !preview.isEmpty()) preview = "You: " + preview;
            views.setViewVisibility(ROW[i], View.VISIBLE);
            views.setTextViewText(NAME[i], name);
            views.setTextViewText(PREVIEW[i], preview);
            long time = PairedProtocol.time(c);
            views.setTextViewText(TIME[i], time <= 0 ? "" : DateUtils.formatSameDayTime(time, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT));
            views.setImageViewBitmap(AVATAR[i], avatar(context, c, title));
            views.setOnClickPendingIntent(ROW[i], open(context, id, 20 + i));
        }
        manager.updateAppWidget(ids, views);
    }

    private static Bitmap avatar(Context context, JSONObject chat, String title) {
        JSONObject other = PairedProtocol.other(chat);
        String pid = PairedProtocol.participantID(other);
        if (!pid.isEmpty()) {
            File f = Avatars.file(context, pid);
            if (f.isFile()) { Bitmap b = BitmapFactory.decodeFile(f.getPath()); if (b != null) return Avatars.circle(b, 120); }
        }
        String hex = other != null ? other.optString("avatarHexColor", "") : chat.optString("avatarHexColor", "");
        return Avatars.initial(PairedProtocol.initial(title), PairedProtocol.color(hex, Avatars.fallbackColor(title)), 120);
    }

    private static PendingIntent open(Context context, String chatId, int request) {
        Intent intent = new Intent(context, PairedActivity.class).setAction(PairedActivity.OPEN_CHAT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!chatId.isEmpty()) intent.setData(Uri.parse("messagecenter://widget/" + Uri.encode(chatId))).putExtra("chat", chatId);
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
