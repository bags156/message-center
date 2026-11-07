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
    PairedStore(Context context) {super(context,"paired-messages.db",null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL, folder INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY,chat TEXT NOT NULL,data TEXT NOT NULL,time INTEGER NOT NULL,version INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX items_chat_time ON items(chat,time)");
        db.execSQL("CREATE TABLE pages(id TEXT PRIMARY KEY,cursor TEXT NOT NULL,note TEXT NOT NULL)");
        db.execSQL("CREATE TABLE meta(id INTEGER PRIMARY KEY,value INTEGER NOT NULL)");
        db.execSQL("INSERT INTO meta VALUES(1,0)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int before,int after) {throw new IllegalStateException("Unsupported database version");}
    private static JSONObject json(String s) {try{return new JSONObject(s);}catch(Exception e){return new JSONObject();}}
    synchronized long snapshot() { try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM meta WHERE id=1",null)){c.moveToFirst();return c.getLong(0);} }
    synchronized void chat(JSONObject data) {
        String id=data.optString("conversationID");if(id.isEmpty())return;
        ContentValues v=new ContentValues();v.put("id",id);v.put("data",data.toString());v.put("updated",PairedProtocol.time(data));
        v.put("folder",data.optString("status").contains("ARCHIVED") ? 2:1);
        getWritableDatabase().insertWithOnConflict("chats",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized JSONObject chat(String id) {try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM chats WHERE id=?",new String[]{id})){return c.moveToFirst()?json(c.getString(0)):null;}}
    synchronized List<JSONObject> chats(String query,int folder) {
        List<JSONObject> out=new ArrayList<>();String q=query.toLowerCase(java.util.Locale.ROOT);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM chats WHERE folder=? ORDER BY updated DESC",new String[]{String.valueOf(folder)})) {
            while(c.moveToNext()) {JSONObject o=json(c.getString(0));String title=PairedProtocol.title(o),preview=PairedProtocol.string(o.optJSONObject("latestMessage"),"displayContent");
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
            long next=snapshot()+1;db.execSQL("UPDATE meta SET value=? WHERE id=1",new Object[]{next});
            ContentValues v=new ContentValues();v.put("id",id);v.put("chat",chat);v.put("data",data.toString());v.put("time",PairedProtocol.time(data));v.put("version",next);
            db.insertWithOnConflict("items",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();return existed;
        }finally{db.endTransaction();}
    }
    synchronized List<JSONObject> messages(String id,int limit) {
        List<JSONObject> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM (SELECT data,time,id FROM items WHERE chat=? ORDER BY time DESC,id DESC LIMIT ?) ORDER BY time,id",new String[]{id,String.valueOf(limit)})){
            while(c.moveToNext())out.add(json(c.getString(0)));
        }return out;
    }
    synchronized JSONObject message(String id) {try(Cursor c=getReadableDatabase().rawQuery("SELECT data FROM items WHERE id=?",new String[]{id})){return c.moveToFirst()?json(c.getString(0)):null;}}
    synchronized void page(String id,String cursor,String note) {
        ContentValues v=new ContentValues();v.put("id",id);v.put("cursor",cursor);v.put("note",note);getWritableDatabase().insertWithOnConflict("pages",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized String page(String id,boolean note) {try(Cursor c=getReadableDatabase().rawQuery("SELECT cursor,note FROM pages WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getString(note?1:0):"";}}
    synchronized void clear() {SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(String table:new String[]{"chats","items","pages"})db.delete(table,null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}}
}
