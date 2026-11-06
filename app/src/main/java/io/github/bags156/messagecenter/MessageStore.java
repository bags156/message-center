package io.github.bags156.messagecenter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MessageStore extends SQLiteOpenHelper {
    public static final String CHANGED = "io.github.bags156.messagecenter.CHANGED";
    public static final class ThreadInfo {
        public String id, title, snippet;
        public long time;
    }
    public static final class Message {
        public String id, sender, text, status;
        public long time;
        public boolean outgoing, hidden;
        public final Map<String, String> reactions = new LinkedHashMap<>();
    }
    public MessageStore(Context context) { super(context, "messages.db", null, 1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE threads (id TEXT PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, thread_id TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE, sender TEXT NOT NULL, body TEXT NOT NULL, time INTEGER NOT NULL, outgoing INTEGER NOT NULL, status TEXT NOT NULL)");
        db.execSQL("CREATE INDEX messages_thread_time ON messages(thread_id, time)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("A migration is required before changing the database version");
    }
    public synchronized void add(String thread, String title, String sender, String body,
                                  long time, boolean outgoing, String status) {
        if (body == null || body.trim().isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues t = new ContentValues();
            t.put("id", thread); t.put("title", title); t.put("updated", time);
            db.insertWithOnConflict("threads", null, t, SQLiteDatabase.CONFLICT_IGNORE);
            // Replay of older notification context must not move a conversation to the top.
            db.execSQL("UPDATE threads SET title=?, updated=MAX(updated,?) WHERE id=?",
                       new Object[]{title, time, thread});
            if (outgoing && status.equals("observed")) {
                ContentValues observed = new ContentValues(); observed.put("status", "observed");
                int matched = db.update("messages", observed,
                    "thread_id=? AND outgoing=1 AND body=? AND status='submitted' AND ABS(time-?)<120000",
                    new String[]{thread, body, String.valueOf(time)});
                if (matched > 0) { db.setTransactionSuccessful(); return; }
            }
            ContentValues m = new ContentValues();
            m.put("id", digest(thread + "\u0000" + time + "\u0000" + sender + "\u0000" + body + "\u0000" + outgoing));
            m.put("thread_id", thread); m.put("sender", sender); m.put("body", body);
            m.put("time", time); m.put("outgoing", outgoing ? 1 : 0); m.put("status", status);
            db.insertWithOnConflict("messages", null, m, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("DELETE FROM messages WHERE time < ?", new Object[]{System.currentTimeMillis() - 90L*24*60*60*1000});
            db.execSQL("DELETE FROM messages WHERE id IN (SELECT id FROM messages ORDER BY time DESC LIMIT -1 OFFSET 5000)");
            db.execSQL("DELETE FROM threads WHERE id NOT IN (SELECT DISTINCT thread_id FROM messages)");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public List<ThreadInfo> threads(String query) {
        List<ThreadInfo> result = new ArrayList<>();
        String sql = "SELECT t.id,t.title,t.updated,(SELECT body FROM messages WHERE thread_id=t.id ORDER BY time DESC,rowid DESC LIMIT 1) FROM threads t ORDER BY t.updated DESC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while(c.moveToNext()) {
                ThreadInfo t = new ThreadInfo(); t.id=c.getString(0); t.title=c.getString(1);
                t.time=c.getLong(2); t.snippet=c.getString(3);
                String q=query.toLowerCase(java.util.Locale.ROOT);
                if(q.isEmpty() || (t.title + " " + t.snippet).toLowerCase(java.util.Locale.ROOT).contains(q)) result.add(t);
            }
        }
        return result;
    }
    public List<Message> messages(String thread) {
        List<Message> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT id,sender,body,time,outgoing,status FROM (SELECT rowid AS seq,* FROM messages WHERE thread_id=? ORDER BY time DESC,rowid DESC LIMIT 300) ORDER BY time,seq", new String[]{thread})) {
            while(c.moveToNext()) {
                Message m = new Message(); m.id=c.getString(0); m.sender=c.getString(1);
                m.text=c.getString(2); m.time=c.getLong(3); m.outgoing=c.getInt(4)!=0;
                m.status=c.getString(5); result.add(m);
            }
        }
        applyReactions(result);
        return result;
    }
    static void applyReactions(List<Message> messages) {
        List<String> previous = new ArrayList<>();
        for (Message m : messages) {
            ReactionParser.Reaction reaction = ReactionParser.parse(m.text);
            int target = reaction == null ? -1 : ReactionParser.uniqueMatch(reaction.quote, previous);
            if(target >= 0) {
                Message original = messages.get(target);
                if(reaction.removed) original.reactions.remove(m.sender);
                else original.reactions.put(m.sender, reaction.emoji);
                m.hidden=true;
            }
            // Keep this list aligned with the original message list, including reactions.
            previous.add(m.text);
        }
    }
    public synchronized void clear() {
        SQLiteDatabase db=getWritableDatabase(); db.delete("threads", null, null);
    }
    private static String digest(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder(); for(byte x:bytes) b.append(String.format("%02x", x & 255));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
