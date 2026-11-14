package io.github.bags156.messagecenter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Separate from notification captures: only real protocol IDs/history live here. */
final class PairedStore extends SQLiteOpenHelper {
    private final Context appContext;
    PairedStore(Context context) {super(context,"paired-messages.db",null,1);appContext=context.getApplicationContext();setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db) {
        // IF NOT EXISTS: two parts of the app opening the database at once on first run must not collide.
        db.execSQL("CREATE TABLE IF NOT EXISTS chats(id TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL, folder INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS items(id TEXT PRIMARY KEY,chat TEXT NOT NULL,data TEXT NOT NULL,time INTEGER NOT NULL,version INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS items_chat_time ON items(chat,time)");
        db.execSQL("CREATE TABLE IF NOT EXISTS pages(id TEXT PRIMARY KEY,cursor TEXT NOT NULL,note TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(id INTEGER PRIMARY KEY,value INTEGER NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO meta VALUES(1,0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int before,int after) {throw new IllegalStateException("Unsupported database version");}
    private static JSONObject json(String s) {try{return new JSONObject(s);}catch(Exception e){return new JSONObject();}}
    synchronized long snapshot() { try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM meta WHERE id=1",null)){c.moveToFirst();return c.getLong(0);} }
    /** Saves a chat; returns false (and writes nothing) when it's the same as what's saved. */
    synchronized boolean chat(JSONObject data) {
        String id=data.optString("conversationID");if(id.isEmpty())return false;
        int folder=folder(data.optString("status"));if(folder==0){deleteChat(id);return true;}
        String text=data.toString();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM chats WHERE id=?",new String[]{id})){if(c.moveToFirst()&&text.equals(c.getString(0)))return false;}
        ContentValues v=new ContentValues();v.put("id",id);v.put("data",text);v.put("updated",PairedProtocol.time(data));
        v.put("folder",folder);
        getWritableDatabase().insertWithOnConflict("chats",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }
    /** 1 inbox, 2 archived, 3 blocked or spam (hidden), 0 deleted (removed). */
    static int folder(String status) {
        if(status.equals("DELETED"))return 0;
        if(status.contains("ARCHIVED"))return 2;
        if(status.equals("SPAM_FOLDER")||status.equals("BLOCKED_FOLDER"))return 3;
        return 1;
    }
    synchronized void deleteChat(String id) {SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{db.delete("chats","id=?",new String[]{id});db.delete("items","chat=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}PhotoText.removeChat(appContext,id);}
    synchronized void deleteMessage(String id) {getWritableDatabase().delete("items","id=?",new String[]{id});PhotoText.removeMessage(appContext,id);}
    /** Synced messages in a chat whose text contains the query, newest first. */
    synchronized List<JSONObject> search(String chat,String query,int max) {
        List<JSONObject> out=new ArrayList<>();String q=query.trim().toLowerCase(java.util.Locale.ROOT);if(q.isEmpty())return out;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? ORDER BY time DESC,id DESC",new String[]{chat})) {
            while(c.moveToNext()&&out.size()<max){JSONObject m=json(c.getString(0));if(PairedProtocol.text(m).toLowerCase(java.util.Locale.ROOT).contains(q))out.add(m);}
        }
        return out;
    }
    /** Synced messages in any chat whose text contains the query, newest first. */
    synchronized List<JSONObject> searchAll(String query,int max) {
        List<JSONObject> out=new ArrayList<>();String q=query.trim().toLowerCase(java.util.Locale.ROOT);if(q.length()<2)return out;
        // A rough LIKE on the stored JSON narrows the rows; the real match is on the message text only.
        String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE lower(data) LIKE ? ESCAPE '\\' ORDER BY time DESC LIMIT 2000",new String[]{like})) {
            while(c.moveToNext()&&out.size()<max){JSONObject m=json(c.getString(0));if(PairedProtocol.text(m).toLowerCase(java.util.Locale.ROOT).contains(q))out.add(m);}
        }
        return out;
    }
    /** First meaningful incoming or successfully sent outgoing message: enough to decide who owes a reply. */
    synchronized List<JSONObject> replyBoundary(String chat,JSONObject conversation){List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? ORDER BY time DESC,id DESC",new String[]{chat})){
            while(c.moveToNext()){JSONObject m=json(c.getString(0));if(!NeedsReply.meaningful(m))continue;
                if(!PairedProtocol.outgoing(m,conversation)||NeedsReply.sent(m)){out.add(m);break;}}}return out;}
    /** Every synced message with an attachment in this chat, newest first. */
    /** The newest messages across all chats (optionally only since a time), for filtered search and cleanup. */
    synchronized List<JSONObject> recent(int limit,long since) {
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE time>=? ORDER BY time DESC LIMIT ?",new String[]{String.valueOf(since),String.valueOf(limit)})){
            while(c.moveToNext())out.add(json(c.getString(0)));}
        return out;
    }
    synchronized List<JSONObject> mediaMessages(String chat,int max) {
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? AND data LIKE '%mediaContent%' ORDER BY time DESC LIMIT ?",new String[]{chat,String.valueOf(max)})) {
            while(c.moveToNext()){JSONObject m=json(c.getString(0));if(!PairedProtocol.media(m).isEmpty())out.add(m);}
        }
        return out;
    }
    /**
     * A message you sent can show up first under a stand-in ID and then again under its real one
     * (same tmpID). The stand-in would sit at "Sending…" forever, so once the other copy arrives,
     * a stand-in that's still sending is removed. Real messages are never touched.
     */
    private void dropStandIns(SQLiteDatabase db,String id,String chat,JSONObject data){
        String tmp=data.optString("tmpID");if(tmp.isEmpty()||tmp.equals(id))return;
        List<String> gone=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT id,data FROM items WHERE chat=? AND id<>? AND instr(data,?)>0",new String[]{chat,id,tmp})){
            while(c.moveToNext()){JSONObject other=json(c.getString(1));
                String status=PairedProtocol.status(other);
                boolean stillSending=status.equals("OUTGOING_SENDING")||status.equals("OUTGOING_YET_TO_SEND")||status.equals("OUTGOING_RESENDING")||status.equals("OUTGOING_VALIDATING")||status.equals("OUTGOING_AWAITING_RETRY");
                if(tmp.equals(other.optString("tmpID"))&&stillSending)gone.add(c.getString(0));}
        }
        for(String g:gone)db.delete("items","id=?",new String[]{g});
    }
    /** Whether Google's copy of a message sent under this temporary ID is saved in the chat yet. */
    synchronized boolean hasSent(String chat,String tmpID){if(tmpID==null||tmpID.isEmpty())return false;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM items WHERE chat=? AND (id=? OR instr(data,?)>0) LIMIT 1",new String[]{chat,tmpID,"\"tmpID\":\""+tmpID+"\""})){return c.moveToFirst();}}
    /**
     * Messages someone sent you in a chat since a time (your last read, 0 if unknown), counting back from
     * the newest and stopping at your own last message (anything before your reply has been seen), up to max.
     */
    synchronized int incomingSince(String chat,long since,int max){int n=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? AND time>? ORDER BY time DESC LIMIT ?",new String[]{chat,String.valueOf(since),String.valueOf(max+50)})){
            while(c.moveToNext()&&n<max){JSONObject m=json(c.getString(0));if(PairedProtocol.outgoing(m))break;if(PairedProtocol.status(m).startsWith("INCOMING"))n++;}}
        return n;}
    /** Google's copy of a text sent under this temporary ID, or null when it hasn't arrived. */
    synchronized JSONObject sentCopy(String chat,String tmpID){if(tmpID==null||tmpID.isEmpty())return null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? AND (id=? OR instr(data,?)>0) LIMIT 1",new String[]{chat,tmpID,"\"tmpID\":\""+tmpID+"\""})){return c.moveToFirst()?json(c.getString(0)):null;}}
    /** Messages in a chat that may hold a web link (the caller finds the links), newest first. */
    synchronized List<JSONObject> linkMessages(String chat,int max) {
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? AND (data LIKE '%http%' OR data LIKE '%www.%') ORDER BY time DESC LIMIT ?",new String[]{chat,String.valueOf(max)})) {
            while(c.moveToNext())out.add(json(c.getString(0)));
        }
        return out;
    }
    /** How many synced messages in the chat are at or after this time (to load enough to show it). */
    synchronized int countSince(String chat,long time) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM items WHERE chat=? AND time>=?",new String[]{chat,String.valueOf(time)})){c.moveToFirst();return c.getInt(0);}
    }
    synchronized JSONObject chat(String id) {try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM chats WHERE id=?",new String[]{id})){return c.moveToFirst()?json(c.getString(0)):null;}}
    /** The saved text of each chat chats() handed out, so the inbox can tell cheaply whether a chat changed. */
    private final java.util.Map<JSONObject,String> saved=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /**
     * Chats as parsed from their saved text, reused while the text is unchanged: the inbox reads
     * every chat on each redraw. So chats() hands out shared objects, which callers must not change.
     */
    private final java.util.Map<String,Object[]> parsed=new java.util.HashMap<>();
    /** The chat as saved, when it came from chats(); otherwise its text now. */
    String savedText(JSONObject chat){String text=saved.get(chat);return text!=null?text:chat.toString();}
    synchronized List<JSONObject> chats(String query,int folder) {
        List<JSONObject> out=new ArrayList<>();String q=query.toLowerCase(java.util.Locale.ROOT);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,data FROM chats WHERE folder=? ORDER BY updated DESC",new String[]{String.valueOf(folder)})) {
            while(c.moveToNext()) {String id=c.getString(0),text=c.getString(1);Object[] hit=parsed.get(id);JSONObject o;
                if(hit!=null&&text.equals(hit[0]))o=(JSONObject)hit[1];else{o=json(text);parsed.put(id,new Object[]{text,o});}saved.put(o,text);String title=PairedProtocol.title(o),preview=PairedProtocol.string(o.optJSONObject("latestMessage"),"displayContent");
                if(q.isEmpty() || (title+" "+preview).toLowerCase(java.util.Locale.ROOT).contains(q))out.add(o);}
        }return out;
    }
    /** A history request cannot overwrite a live update received since it started. */
    synchronized boolean message(JSONObject data,long requestVersion,boolean live) {
        String id=data.optString("messageID"),chat=data.optString("conversationID");if(id.isEmpty()||chat.isEmpty())return false;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            boolean existed=false;long version=0;
            try(Cursor c=db.rawQuery("SELECT version FROM items WHERE id=?",new String[]{id})){if(c.moveToFirst()){existed=true;version=c.getLong(0);}}
            if(!live && existed && version>requestVersion) {db.setTransactionSuccessful();return true;}
            data=ReplyReferences.preserve(message(id),data);
            long next=snapshot()+1;db.execSQL("UPDATE meta SET value=? WHERE id=1",new Object[]{next});
            ContentValues v=new ContentValues();v.put("id",id);v.put("chat",chat);v.put("data",data.toString());v.put("time",PairedProtocol.time(data));v.put("version",next);
            db.insertWithOnConflict("items",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            dropStandIns(db,id,chat,data);
            db.setTransactionSuccessful();return existed;
        }finally{db.endTransaction();}
    }
    /** When the newest saved message in a chat was sent, or 0 if none is saved. */
    synchronized long newestTime(String chat) {try(Cursor c=getReadableDatabase().rawQuery("SELECT MAX(time) FROM items WHERE chat=?",new String[]{chat})){return c.moveToFirst()&&!c.isNull(0)?c.getLong(0):0;}}
    synchronized List<JSONObject> messages(String id,int limit) {
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM (SELECT data,time,id FROM items WHERE chat=? ORDER BY time DESC,id DESC LIMIT ?) ORDER BY time,id",new String[]{id,String.valueOf(limit)})){
            while(c.moveToNext())out.add(json(c.getString(0)));
        }return out;
    }
    synchronized JSONObject message(String id) {try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE id=?",new String[]{id})){return c.moveToFirst()?json(c.getString(0)):null;}}
    synchronized JSONObject latestIncoming(String chat) {
        JSONObject conversation=chat(chat);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? ORDER BY time DESC,id DESC",new String[]{chat})){
            while(c.moveToNext()){JSONObject m=json(c.getString(0));if(ReadReceiptQueue.eligible(m,conversation))return m;}}
        return null;
    }
    /** Match known provider identities within this conversation; ambiguous references are not linked. */
    synchronized String replyTarget(JSONObject reply) {
        if(!ReplyReferences.isReply(reply))return "";
        String chat=reply.optString("conversationID"),self=reply.optString("messageID");long before=PairedProtocol.time(reply);
        java.util.Set<String> found=new java.util.LinkedHashSet<>();
        String primary=ReplyReferences.primary(reply);
        for(String ref:ReplyReferences.references(reply)) {
            JSONObject direct=message(ref);
            if(validOriginal(direct,chat,self,before)) {if(ref.equals(primary))return ref;found.add(ref);}
            String pattern="%\""+ref.replace("\\","\\\\").replace("%","\\%").replace("_","\\_").replace("\"","\\\"")+"\"%";
            try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? AND time<=? AND data LIKE ? ESCAPE '\\' LIMIT 200",new String[]{chat,String.valueOf(before),pattern})){
                while(c.moveToNext()){JSONObject m=json(c.getString(0));if(validOriginal(m,chat,self,before)&&ReplyReferences.identities(m).contains(ref))found.add(m.optString("messageID"));}}
        }
        if(found.size()==1)return found.iterator().next();
        JSONObject explicit=primary.isEmpty()?null:message(primary);
        if(!primary.isEmpty()&&!primary.equals(self)&&(explicit==null||chat.equals(explicit.optString("conversationID"))))return primary;
        String manual=page("reply-link:"+self,false);JSONObject original=manual.isEmpty()?null:message(manual);
        return validOriginal(original,chat,self,before)?manual:"";
    }
    private static boolean validOriginal(JSONObject m,String chat,String self,long before){return m!=null&&!self.equals(m.optString("messageID"))&&chat.equals(m.optString("conversationID"))&&PairedProtocol.time(m)<=before;}
    synchronized JSONObject messageByReference(String chat,String reference){
        JSONObject direct=message(reference);if(direct!=null&&chat.equals(direct.optString("conversationID")))return direct;
        String pattern="%\""+reference.replace("\\","\\\\").replace("%","\\%").replace("_","\\_").replace("\"","\\\"")+"\"%";
        JSONObject found=null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? AND data LIKE ? ESCAPE '\\' LIMIT 200",new String[]{chat,pattern})){
            while(c.moveToNext()){JSONObject m=json(c.getString(0));if(ReplyReferences.identities(m).contains(reference)){if(found!=null)return null;found=m;}}}
        return found;
    }
    synchronized void linkReply(JSONObject reply,String original) {
        if(!validOriginal(message(original),reply.optString("conversationID"),reply.optString("messageID"),PairedProtocol.time(reply)))throw new IllegalArgumentException("Choose an earlier message in this conversation.");
        page("reply-link:"+reply.optString("messageID"),original,"Selected on this phone");
    }
    synchronized List<JSONObject> replyCandidates(JSONObject reply,String query,int max){
        List<JSONObject> out=new ArrayList<>();String q=query.trim().toLowerCase(java.util.Locale.ROOT);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE chat=? AND time<=? AND id!=? ORDER BY time DESC,id DESC",new String[]{reply.optString("conversationID"),String.valueOf(PairedProtocol.time(reply)),reply.optString("messageID")})){
            while(c.moveToNext()&&out.size()<max){JSONObject m=json(c.getString(0));String body=PairedProtocol.body(m);if(!body.isEmpty()&&(q.isEmpty()||body.toLowerCase(java.util.Locale.ROOT).contains(q)))out.add(m);}}
        return out;
    }
    synchronized void page(String id,String cursor,String note) {
        ContentValues v=new ContentValues();v.put("id",id);v.put("cursor",cursor);v.put("note",note);getWritableDatabase().insertWithOnConflict("pages",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized String page(String id,boolean note) {try(Cursor c=getReadableDatabase().rawQuery("SELECT cursor,note FROM pages WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getString(note?1:0):"";}}
    synchronized void clear() {parsed.clear();SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(String table:new String[]{"chats","items","pages"})db.delete(table,null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}}
}
