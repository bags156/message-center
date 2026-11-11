package io.github.bags156.messagecenter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Text them when I get there": Android's own proximity alerts (no Google Play services needed)
 * watch a saved place, and on arrival the text goes through the outbox like any message, so it's
 * sent even if the connection is down at that moment. Rules in ArrivalPlan. Alerts don't survive a
 * restart or an update, so PairedBootReceiver and PairedService set them again (registerAll).
 */
public final class Arrivals extends BroadcastReceiver {
    static final String FIRE = "io.github.bags156.messagecenter.ARRIVAL", LATE = "io.github.bags156.messagecenter.ARRIVAL_LATE", CHANNEL = "arrivals";
    private static final Object LOCK = new Object();

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("arrivals", Context.MODE_PRIVATE); }
    private static List<JSONObject> list(Context c, String key) {
        List<JSONObject> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(prefs(c).getString(key, "[]")); for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i)); } catch (Exception ignored) {}
        return out;
    }
    private static void store(Context c, String key, List<JSONObject> items) {
        JSONArray a = new JSONArray(); for (JSONObject o : items) a.put(o);
        prefs(c).edit().putString(key, a.toString()).commit();
    }

    static List<JSONObject> all(Context c) { synchronized (LOCK) { return list(c, "watching"); } }
    static List<JSONObject> forChat(Context c, String chat) { List<JSONObject> out = all(c); out.removeIf(o -> !chat.equals(o.optString("chat"))); return out; }

    /** Places you've saved (name, latitude, longitude), newest first. */
    static List<JSONObject> places(Context c) { synchronized (LOCK) { return list(c, "places"); } }
    static JSONObject savePlace(Context c, String name, double lat, double lng) {
        synchronized (LOCK) {
            List<JSONObject> places = list(c, "places");
            places.removeIf(p -> p.optString("name").equalsIgnoreCase(name.trim()));
            JSONObject p = new JSONObject();
            try { p.put("name", name.trim()).put("lat", lat).put("lng", lng); } catch (Exception ignored) {}
            places.add(0, p); store(c, "places", places); return p;
        }
    }
    static void removePlace(Context c, String name) { synchronized (LOCK) { List<JSONObject> places = list(c, "places"); places.removeIf(p -> p.optString("name").equals(name)); store(c, "places", places); } }

    /** Location access that works with the screen off: precise location, allowed all the time. */
    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts watching a place for a chat. state: from ArrivalPlan.startState. */
    static JSONObject add(Context context, String chat, JSONObject place, String text, boolean everyTime, String state) {
        return add(context, chat, place, text, everyTime, state, false, 0, "");
    }
    /** leave: text when you leave instead. deadline/lateText: a check-in (arrivals only), or 0 and "". */
    static JSONObject add(Context context, String chat, JSONObject place, String text, boolean everyTime, String state, boolean leave, long deadline, String lateText) {
        Context c = context.getApplicationContext(); long now = System.currentTimeMillis();
        boolean checkIn = !leave && !everyTime && deadline > now && lateText != null && !lateText.trim().isEmpty();
        JSONObject a = new JSONObject();
        try {
            a.put("id", UUID.randomUUID().toString()).put("chat", chat).put("place", place.optString("name")).put("lat", place.optDouble("lat")).put("lng", place.optDouble("lng"))
                    .put("radius", ArrivalPlan.RADIUS_M).put("text", text).put("everyTime", everyTime).put("state", state).put("created", now)
                    .put("on", leave ? ArrivalPlan.LEAVE : ArrivalPlan.ARRIVE).put("deadline", checkIn ? deadline : 0).put("lateText", checkIn ? lateText.trim() : "")
                    .put("expires", ArrivalPlan.expires(now, everyTime, checkIn ? deadline : 0));
        } catch (Exception ignored) {}
        synchronized (LOCK) { List<JSONObject> items = list(c, "watching"); items.add(a); store(c, "watching", items); }
        watch(c, a); remind(c, a);
        return a;
    }
    static boolean leaving(JSONObject a) { return ArrivalPlan.LEAVE.equals(a.optString("on")); }

    static void cancel(Context context, String id) {
        Context c = context.getApplicationContext();
        synchronized (LOCK) { List<JSONObject> items = list(c, "watching"); items.removeIf(o -> id.equals(o.optString("id"))); store(c, "watching", items); }
        unwatch(c, id); unremind(c, id);
    }

    /** Sets every alert again and drops ones that ran out; safe to call any time. */
    static void registerAll(Context context) {
        Context c = context.getApplicationContext(); long now = System.currentTimeMillis();
        for (JSONObject a : all(c)) {
            if (ArrivalPlan.expired(a.optLong("expires"), now)) { cancel(c, a.optString("id")); continue; }
            watch(c, a);
            // A check-in whose time passed while the phone was off is checked now.
            if (a.optLong("deadline") > 0 && a.optLong("deadline") <= now) late(c, a.optString("id")); else remind(c, a);
        }
    }

    /** The check-in alarm (exact, so it isn't late itself). */
    private static PendingIntent pendingLate(Context c, String id) {
        Intent i = new Intent(c, Arrivals.class).setAction(LATE).setData(Uri.parse("messagecenter://arrival-late/" + id)).putExtra("id", id);
        return PendingIntent.getBroadcast(c, 8, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void remind(Context c, JSONObject a) {
        long at = a.optLong("deadline");
        if (at <= System.currentTimeMillis() || a.optLong("arrivedAt") > 0 || a.optBoolean("lateSent")) return;
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class); PendingIntent p = pendingLate(c, a.optString("id"));
        try { if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p); else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p); }
        catch (SecurityException e) { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p); }
    }
    private static void unremind(Context c, String id) { PendingIntent p = pendingLate(c, id); c.getSystemService(android.app.AlarmManager.class).cancel(p); p.cancel(); }

    /** Check-in time: unless you've arrived or are there now, the late text goes (the arrival text still waits for you). */
    static boolean late(Context context, String id) {
        Context c = context.getApplicationContext(); JSONObject a = null;
        for (JSONObject o : all(c)) if (id.equals(o.optString("id"))) a = o;
        if (a == null) return false;
        boolean thereNow = ArrivalPlan.there(metersFromLastKnown(c, a), (float) a.optDouble("radius", ArrivalPlan.RADIUS_M));
        boolean send;
        synchronized (LOCK) {
            List<JSONObject> items = list(c, "watching"); JSONObject current = null;
            for (JSONObject o : items) if (id.equals(o.optString("id"))) current = o;
            if (current == null) return false;
            send = ArrivalPlan.late(current.optLong("deadline"), System.currentTimeMillis(), current.optLong("arrivedAt"), current.optBoolean("lateSent"), thereNow);
            if (send) MediaOutbox.put(current, "lateSent", true); else if (thereNow && current.optLong("arrivedAt") <= 0) MediaOutbox.put(current, "arrivedAt", System.currentTimeMillis());
            store(c, "watching", items); a = current;
        }
        if (!send) return false;
        new Outbox(c).add(a.optString("chat"), a.optString("lateText"), "");
        Diagnostics.note("Check-in text queued");
        if (new SessionVault(c).exists()) PairedService.flushOutboxNow(c);
        confirm(c, a, a.optString("lateText"), "You're not at " + a.optString("place") + " yet");
        return true;
    }

    /** How far the last location Android knows is from the place, or -1 if none (or no permission). */
    private static double metersFromLastKnown(Context c, JSONObject a) {
        LocationManager lm = c.getSystemService(LocationManager.class); android.location.Location best = null;
        try { for (String p : lm.getProviders(true)) { android.location.Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } }
        catch (SecurityException ignored) {}
        // A fix more than 15 minutes old says nothing about now.
        if (best == null || System.currentTimeMillis() - best.getTime() > 15 * 60_000L) return -1;
        return ArrivalPlan.meters(best.getLatitude(), best.getLongitude(), a.optDouble("lat"), a.optDouble("lng"));
    }

    private static PendingIntent pending(Context c, String id) {
        Intent i = new Intent(c, Arrivals.class).setAction(FIRE).setData(Uri.parse("messagecenter://arrival/" + id)).putExtra("id", id);
        // Mutable: Android adds whether you're arriving or leaving to the intent.
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }
    private static void watch(Context c, JSONObject a) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        long left = a.optLong("expires") > 0 ? Math.max(1, a.optLong("expires") - System.currentTimeMillis()) : -1;
        try { lm.addProximityAlert(a.optDouble("lat"), a.optDouble("lng"), (float) a.optDouble("radius", ArrivalPlan.RADIUS_M), left, pending(c, a.optString("id"))); }
        catch (SecurityException | IllegalArgumentException e) { Diagnostics.note("Arrival text couldn't watch its place: " + e.getClass().getSimpleName()); }
    }
    private static void unwatch(Context c, String id) {
        try { c.getSystemService(LocationManager.class).removeProximityAlert(pending(c, id)); } catch (SecurityException | IllegalArgumentException ignored) {}
        pending(c, id).cancel();
    }

    @Override public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra("id");
        if (id == null) return;
        if (FIRE.equals(intent.getAction())) arrived(context.getApplicationContext(), id, intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false));
        else if (LATE.equals(intent.getAction())) late(context.getApplicationContext(), id);
    }

    /** An arrival (entering) or departure at the place. Returns whether a text went into the outbox. */
    static boolean arrived(Context c, String id, boolean entering) {
        JSONObject sent = null;
        synchronized (LOCK) {
            List<JSONObject> items = list(c, "watching"); JSONObject a = null;
            for (JSONObject o : items) if (id.equals(o.optString("id"))) a = o;
            if (a == null) { unwatch(c, id); return false; }
            if (ArrivalPlan.expired(a.optLong("expires"), System.currentTimeMillis())) { items.remove(a); store(c, "watching", items); unwatch(c, id); return false; }
            String state = a.optString("state", ArrivalPlan.ARMED);boolean leave = leaving(a);
            // Being there counts for a check-in even when the arrival text itself waits.
            if (entering && !leave && a.optLong("arrivedAt") <= 0) { MediaOutbox.put(a, "arrivedAt", System.currentTimeMillis()); unremind(c, id); }
            if (ArrivalPlan.sends(state, entering, leave)) {
                sent = a;
                if (a.optBoolean("everyTime")) MediaOutbox.put(a, "state", ArrivalPlan.AWAY_FIRST); else items.remove(a);
            } else MediaOutbox.put(a, "state", ArrivalPlan.after(state, entering, leave));
            store(c, "watching", items);
        }
        if (sent == null) return false;
        if (!sent.optBoolean("everyTime")) { unwatch(c, id); unremind(c, id); }
        new Outbox(c).add(sent.optString("chat"), sent.optString("text"), "");
        Diagnostics.note("Arrival text queued (" + (sent.optBoolean("everyTime") ? "every time" : "once") + ")");
        if (new SessionVault(c).exists()) PairedService.flushOutboxNow(c);
        confirm(c, sent, sent.optString("text"), leaving(sent) ? "You left " + sent.optString("place") : "You arrived at " + sent.optString("place"));
        return true;
    }

    /** "Texted Hannah: Made it home!", as your notification privacy for that chat allows (what happened, if text is hidden). */
    private static void confirm(Context c, JSONObject a, String said, String happened) {
        NotificationManager nm = c.getSystemService(NotificationManager.class); String chat = a.optString("chat"), title = "";
        try (PairedStore store = new PairedStore(c)) { JSONObject conversation = store.chat(chat); if (conversation != null) title = PairedProtocol.title(conversation); } catch (Exception ignored) {}
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Arrival texts", NotificationManager.IMPORTANCE_DEFAULT));
        boolean name = NotificationPrivacy.showName(c, chat) && !title.isEmpty(), text = NotificationPrivacy.showText(c, chat);
        Intent open = new Intent(c, PairedActivity.class).setAction(PairedActivity.OPEN_CHAT).setData(Uri.parse("messagecenter://chat/" + chat)).putExtra("chat", chat).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name ? "Texted " + title : "Location text sent").setContentText(text ? said : happened)
                .setContentIntent(PendingIntent.getActivity(c, chat.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build();
        try { nm.notify("arrival:" + a.optString("id") + ":" + said.hashCode(), 1, n); } catch (SecurityException ignored) {}
    }
}
