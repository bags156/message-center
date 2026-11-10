package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bundled, on-device OCR of downloaded photos. Never downloads a picture just to index it. */
final class PhotoText extends SQLiteOpenHelper {
    interface Done {void done(String text,String error);}
    private static final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final AtomicInteger generation=new AtomicInteger();
    private static final Object indexLock=new Object();
    private static final Map<String,List<Done>> waiting=new HashMap<>();
    private static long lastBackfill;
    PhotoText(Context c){super(c,"photo-text.db",null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS photos(id TEXT PRIMARY KEY,message TEXT NOT NULL,chat TEXT NOT NULL,text TEXT NOT NULL,signature TEXT NOT NULL,scanned INTEGER NOT NULL)");db.execSQL("CREATE INDEX IF NOT EXISTS photo_message ON photos(message)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unsupported picture index version");}
    static boolean enabled(Context c){return c.getSharedPreferences("paired",0).getBoolean("photo_text",true);}
    synchronized String cached(String key,String signature){try(Cursor c=getReadableDatabase().rawQuery("SELECT text FROM photos WHERE id=? AND signature=?",new String[]{key,signature})){return c.moveToFirst()?c.getString(0):null;}}
    synchronized void save(String key,String message,String chat,String text,String signature){ContentValues v=new ContentValues();v.put("id",key);v.put("message",message);v.put("chat",chat);v.put("text",text);v.put("signature",signature);v.put("scanned",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("photos",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    synchronized List<JSONObject> search(String words,String chat,int limit){List<JSONObject> out=new ArrayList<>();String q=words.trim();if(q.length()<2)return out;
        String where="instr(lower(text),lower(?))>0";List<String> args=new ArrayList<>();args.add(q);if(!chat.isEmpty()){where+=" AND chat=?";args.add(chat);}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT message,chat,text FROM photos WHERE "+where+" ORDER BY scanned DESC LIMIT "+Math.max(1,Math.min(2000,limit)),args.toArray(new String[0]))){
            while(c.moveToNext()){JSONObject hit=new JSONObject();MediaOutbox.put(hit,"message",c.getString(0));MediaOutbox.put(hit,"chat",c.getString(1));MediaOutbox.put(hit,"text",c.getString(2));out.add(hit);}}return out;}
    synchronized int count(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM photos",null)){c.moveToFirst();return c.getInt(0);}}
    static void clear(Context c){synchronized(indexLock){generation.incrementAndGet();lastBackfill=0;try(PhotoText db=new PhotoText(c)){db.getWritableDatabase().delete("photos",null,null);}}}
    static void removeMessage(Context c,String id){synchronized(indexLock){try(PhotoText db=new PhotoText(c)){db.getWritableDatabase().delete("photos","message=?",new String[]{id});}}}
    static void removeChat(Context c,String id){synchronized(indexLock){try(PhotoText db=new PhotoText(c)){db.getWritableDatabase().delete("photos","chat=?",new String[]{id});}}}
    private static boolean currentPicture(Context c,String message,String chat,String key){try(PairedStore store=new PairedStore(c)){JSONObject m=store.message(message);if(m==null||!chat.equals(m.optString("conversationID"))||PairedProtocol.status(m).contains("DELETED"))return false;
        for(JSONObject media:PairedProtocol.media(m))if(MediaCache.file(c,media,false).getName().equals(key))return true;}return false;}
    static void downloaded(Context c,JSONObject media,File file){if(enabled(c)&&MediaCache.mime(media).startsWith("image/")&&!media.optString("_message").isEmpty())scan(c,media,file,false,null);}
    static void scan(Context context,JSONObject media,File file,boolean force,Done done){Context c=context.getApplicationContext();String message=media.optString("_message"),chat=media.optString("_chat");
        if(!file.isFile()){if(done!=null)done.done("","Download the full photo first.");return;}String key=file.getName(),signature=file.length()+":"+file.lastModified()+":latin-v1";
        String task=key+"|"+signature;int token=generation.get();
        synchronized(waiting){List<Done> callbacks=waiting.get(task);if(callbacks!=null){if(done!=null)callbacks.add(done);return;}callbacks=new ArrayList<>();if(done!=null)callbacks.add(done);waiting.put(task,callbacks);}
        worker.execute(()->{String text="",error="";boolean changed=false;Bitmap bitmap=null;TextRecognizer recognizer=null;try{
            if(token!=generation.get())return;
            String cached;try(PhotoText db=new PhotoText(c)){cached=force?null:db.cached(key,signature);}if(cached!=null)text=cached;
            else{Bitmap shared=MediaCache.decode(file,2600,false);bitmap=shared==null?null:shared.copy(Bitmap.Config.ARGB_8888,false);if(bitmap==null)throw new java.io.IOException("This picture couldn't be read.");
                recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);text=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),60,TimeUnit.SECONDS).getText();
                synchronized(indexLock){if(token==generation.get()&&enabled(c)&&!message.isEmpty()&&currentPicture(c,message,chat,key))try(PhotoText db=new PhotoText(c)){db.save(key,message,chat,text,signature);changed=true;}}}
        }catch(Exception|OutOfMemoryError e){error="Text recognition couldn't finish. Try a sharper or smaller picture.";}
        finally{if(recognizer!=null)recognizer.close();if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();String result=text,problem=error;boolean indexed=changed;
            main.post(()->{List<Done> callbacks;synchronized(waiting){callbacks=waiting.remove(task);}if(callbacks!=null)for(Done callback:callbacks)callback.done(result,problem);
                if(indexed&&token==generation.get())c.sendBroadcast(new android.content.Intent(PairedService.CHANGED).setPackage(c.getPackageName()).putExtra("kind","PHOTO_TEXT"));});}});
    }
    /** Backfills only files already on this phone; remote history/downloads are left to the normal photo viewer. */
    static void indexDownloaded(Context context,boolean force){Context c=context.getApplicationContext();if(!enabled(c))return;
        synchronized(PhotoText.class){if(!force&&lastBackfill!=0)return;lastBackfill=System.currentTimeMillis();}int token=generation.get();
        worker.execute(()->{List<JSONObject> candidates;try(PairedStore store=new PairedStore(c)){candidates=store.recent(20000,0);}
            int queued=0;try(PhotoText index=new PhotoText(c)){for(JSONObject message:candidates){if(!force&&queued>=100)break;for(JSONObject media:PairedProtocol.media(message))if(MediaCache.mime(media).startsWith("image/")){
                File file=MediaCache.file(c,media,false);if(file.isFile()&&index.cached(file.getName(),file.length()+":"+file.lastModified()+":latin-v1")==null){synchronized(indexLock){if(token!=generation.get()||!enabled(c))return;scan(c,media,file,false,null);queued++;}}}}}});
    }
    static List<JSONObject> matches(Context c,PairedStore store,String words,String chat,int limit){List<JSONObject> out=new ArrayList<>();if(!enabled(c))return out;
        try(PhotoText index=new PhotoText(c)){for(JSONObject hit:index.search(words,chat,limit)){JSONObject m=store.message(hit.optString("message"));if(m==null||PairedProtocol.status(m).contains("DELETED"))continue;
            if(!hit.optString("chat").equals(m.optString("conversationID")))continue;String full=hit.optString("text");int at=full.toLowerCase(java.util.Locale.ROOT).indexOf(words.trim().toLowerCase(java.util.Locale.ROOT));int start=Math.max(0,at-45),end=Math.min(full.length(),start+200);
            MediaOutbox.put(m,"_photoText",(start>0?"…":"")+full.substring(start,end)+(end<full.length()?"…":""));out.add(m);}}return out;}
}
