package io.github.bags156.messagecenter;

import org.json.JSONObject;

/**
 * Calls with the person in a chat, from the phone's call log, shown as quiet lines in the conversation
 * ("Outgoing call · 4 min 12 sec"). They're only ever on this phone: nothing is sent. Pure Java for tests.
 */
final class CallLines {
    private CallLines() {}

    // android.provider.CallLog.Calls types
    static final int INCOMING = 1, OUTGOING = 2, MISSED = 3, VOICEMAIL = 4, REJECTED = 5, BLOCKED = 6, ANSWERED_ELSEWHERE = 7;

    /** Two phone numbers are the same line when their last 10 digits match (at least 7 digits to compare). */
    static boolean sameNumber(String a, String b) {
        String x = ChatText.digits(a), y = ChatText.digits(b);
        if (x.length() < 7 || y.length() < 7) return false;
        String tx = x.length() > 10 ? x.substring(x.length() - 10) : x, ty = y.length() > 10 ? y.substring(y.length() - 10) : y;
        return tx.equals(ty) || (tx.length() != ty.length() && (tx.endsWith(ty) || ty.endsWith(tx)));
    }

    /** "45 sec", "4 min 12 sec", "1 hr 3 min". */
    static String duration(long seconds) {
        if (seconds < 60) return seconds + " sec";
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return h + " hr" + (m > 0 ? " " + m + " min" : "");
        return m + " min" + (s > 0 ? " " + s + " sec" : "");
    }

    /** What happened, in a few words: "Outgoing call · 4 min 12 sec", "Missed video call", "Declined call". */
    static String label(int type, long seconds, boolean video) {
        String call = video ? "video call" : "call";
        switch (type) {
            case INCOMING: return "Incoming " + call + (seconds > 0 ? " · " + duration(seconds) : "");
            case OUTGOING: return "Outgoing " + call + (seconds > 0 ? " · " + duration(seconds) : " · no answer");
            case MISSED: return "Missed " + call;
            case VOICEMAIL: return "Voicemail";
            case REJECTED: return "Declined " + call;
            case BLOCKED: return "Blocked " + call;
            case ANSWERED_ELSEWHERE: return "Answered on another device";
            default: return "Call" + (seconds > 0 ? " · " + duration(seconds) : "");
        }
    }

    /** Missed, declined and blocked calls are the ones worth noticing. */
    static boolean missed(int type) { return type == MISSED || type == REJECTED || type == BLOCKED; }

    /** A call as an item for the conversation's timeline (marked "call" so nothing treats it as a message). */
    static JSONObject item(long id, long atMs, int type, long seconds, boolean video, String number) {
        try {
            return new JSONObject().put("messageID", "call:" + id).put("call", true).put("timestamp", atMs * 1000)
                .put("callType", type).put("seconds", seconds).put("video", video).put("number", number == null ? "" : number);
        } catch (Exception e) { return new JSONObject(); }
    }

    static boolean isCall(JSONObject m) { return m != null && m.optBoolean("call"); }

    static String label(JSONObject call) { return label(call.optInt("callType"), call.optLong("seconds"), call.optBoolean("video")); }
}
