package io.github.bags156.messagecenter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Text messages set to send later. Google's protocol has no scheduled send, so this app keeps
 * them and, at the chosen time, hands each one to the outbox, which sends it once connected.
 * Alarms are set again after a restart or an app update.
 */
public final class Scheduled extends BroadcastReceiver {
    static final String FIRE = "io.github.bags156.messagecenter.SEND_SCHEDULED";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("scheduled", Context.MODE_PRIVATE); }

    static synchronized List<JSONObject> all(Context context) {
        List<JSONObject> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs(context).getString("items", "[]")); for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i)); }
        catch (Exception ignored) {}
        return out;
    }

    static List<JSONObject> forChat(Context context, String chat) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : all(context)) if (chat.equals(o.optString("chat"))) out.add(o);
        out.sort((a, b) -> Long.compare(a.optLong("at"), b.optLong("at")));
        return out;
    }

    static synchronized void add(Context context, String chat, String text, String reply, long at) {
        JSONObject o = new JSONObject();
        try { o.put("id", UUID.randomUUID().toString()).put("chat", chat).put("text", text).put("reply", reply).put("at", at); } catch (Exception ignored) {}
        List<JSONObject> list = all(context); list.add(o); save(context, list);
        arm(context, o);
    }

    static synchronized void remove(Context context, String id) {
        List<JSONObject> list = all(context);
        for (JSONObject o : list) if (id.equals(o.optString("id"))) alarms(context).cancel(pending(context, id));
        list.removeIf(o -> id.equals(o.optString("id"))); save(context, list);
    }

    /** Moves a scheduled message to the outbox now (when it is due, or when the person taps Send now). */
    static void sendNow(Context context, String id) {
        JSONObject due = null;
        for (JSONObject o : all(context)) if (id.equals(o.optString("id"))) due = o;
        if (due == null) return;
        remove(context, id);
        new Outbox(context).add(due.optString("chat"), due.optString("text"), due.optString("reply"));
        PairedService.flushOutboxNow(context);
    }

    /** Sets every alarm again (after a restart, alarms are gone); anything already due goes out now. */
    static void rearmAll(Context context) {
        long now = System.currentTimeMillis();
        for (JSONObject o : all(context)) { if (o.optLong("at") <= now) sendNow(context, o.optString("id")); else arm(context, o); }
    }

    private static void arm(Context context, JSONObject o) {
        AlarmManager am = alarms(context); PendingIntent p = pending(context, o.optString("id")); long at = o.optLong("at");
        // Exact when Android allows it; otherwise Android may send it a few minutes late while the phone sleeps.
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
    }

    private static AlarmManager alarms(Context c) { return c.getSystemService(AlarmManager.class); }

    private static PendingIntent pending(Context context, String id) {
        Intent i = new Intent(context, Scheduled.class).setAction(FIRE).setData(Uri.parse("messagecenter://scheduled/" + id)).putExtra("id", id);
        return PendingIntent.getBroadcast(context, 5, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void save(Context context, List<JSONObject> list) {
        JSONArray a = new JSONArray(); for (JSONObject o : list) a.put(o);
        prefs(context).edit().putString("items", a.toString()).apply();
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (FIRE.equals(intent.getAction())) { String id = intent.getStringExtra("id"); if (id != null) sendNow(context.getApplicationContext(), id); }
    }
}
