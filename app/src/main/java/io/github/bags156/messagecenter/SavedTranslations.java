package io.github.bags156.messagecenter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Messages you've had translated, so the translation is still there when you come back (and whether
 * you'd switched that message to its original). Kept only on this phone (the app makes no backups),
 * one per message, removed with its message or chat, and limited to the most recent few thousand.
 * A message that's since been edited isn't shown its old translation.
 */
final class SavedTranslations extends SQLiteOpenHelper {
    private static final int KEEP = 5000;
    private static SavedTranslations shared;
    private static int saves;

    /** One saved translation: [translated text, "from" language name, target code, the words it was made from]. */
    static final class Saved {
        final String text, fromLabel, target, words; final boolean original, automatic; final String engine;
        Saved(String text, String fromLabel, String target, String words, boolean original, boolean automatic, String engine) {
            this.text = text; this.fromLabel = fromLabel; this.target = target; this.words = words; this.original = original; this.automatic = automatic; this.engine = engine;
        }
    }

    private SavedTranslations(Context c) { super(c.getApplicationContext(), "translations.db", null, 1); setWriteAheadLoggingEnabled(true); }
    private static synchronized SavedTranslations db(Context c) { if (shared == null) shared = new SavedTranslations(c); return shared; }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS translations(message TEXT PRIMARY KEY,chat TEXT NOT NULL,target TEXT NOT NULL,engine TEXT NOT NULL,automatic INTEGER NOT NULL,words TEXT NOT NULL,text TEXT NOT NULL,source TEXT NOT NULL,original INTEGER NOT NULL,saved INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS translations_chat ON translations(chat)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int old, int next) { throw new IllegalStateException("Unsupported translations version"); }

    /** Saves a message's translation (replacing any earlier one), showing the translation. */
    static void save(Context c, String chat, String message, String target, String engine, boolean automatic, String words, String text, String fromLabel) {
        if (message == null || message.isEmpty()) return;
        try {
            ContentValues v = new ContentValues();
            v.put("message", message); v.put("chat", chat); v.put("target", target); v.put("engine", engine); v.put("automatic", automatic ? 1 : 0);
            v.put("words", words); v.put("text", text); v.put("source", fromLabel); v.put("original", 0); v.put("saved", System.currentTimeMillis());
            SQLiteDatabase w = db(c).getWritableDatabase();
            w.insertWithOnConflict("translations", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            // Every so often, only the newest few thousand are kept.
            if (++saves % 50 == 0) w.execSQL("DELETE FROM translations WHERE message NOT IN (SELECT message FROM translations ORDER BY saved DESC LIMIT " + KEEP + ")");
        } catch (Exception ignored) {}
    }

    /** Whether you'd switched this message to its original. */
    static void showingOriginal(Context c, String message, boolean original) {
        try { ContentValues v = new ContentValues(); v.put("original", original ? 1 : 0); db(c).getWritableDatabase().update("translations", v, "message=?", new String[]{message}); } catch (Exception ignored) {}
    }

    /** A chat's saved translations, by message. */
    static Map<String, Saved> forChat(Context c, String chat) {
        Map<String, Saved> out = new LinkedHashMap<>();
        try (Cursor q = db(c).getReadableDatabase().rawQuery("SELECT message,text,source,target,words,original,automatic,engine FROM translations WHERE chat=? ORDER BY saved", new String[]{chat})) {
            while (q.moveToNext()) out.put(q.getString(0), new Saved(q.getString(1), q.getString(2), q.getString(3), q.getString(4), q.getInt(5) == 1, q.getInt(6) == 1, q.getString(7)));
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Whether a saved translation still applies: made from these words (not edited since), and if it
     * was automatic, into the language you read in now with the translator you use now (otherwise the
     * message is translated again). One you asked for yourself stays.
     */
    static boolean applies(Saved s, String words, String readLanguage, String engine) {
        if (s == null || !s.words.equals(words)) return false;
        return !s.automatic || (s.target.equals(readLanguage) && s.engine.equals(engine));
    }

    static void removeMessage(Context c, String message) { try { db(c).getWritableDatabase().delete("translations", "message=?", new String[]{message}); } catch (Exception ignored) {} }
    static void removeChat(Context c, String chat) { try { db(c).getWritableDatabase().delete("translations", "chat=?", new String[]{chat}); } catch (Exception ignored) {} }
    static void clear(Context c) { try { db(c).getWritableDatabase().delete("translations", null, null); } catch (Exception ignored) {} }
}
