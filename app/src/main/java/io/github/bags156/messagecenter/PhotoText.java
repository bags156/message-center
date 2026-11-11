package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bundled, on-device OCR of downloaded photos with Tesseract (open source, Apache-2.0) and its
 * English model, packed in the app. Nothing is sent anywhere: no account, no usage reporting.
 * Never downloads a picture just to index it.
 */
final class PhotoText extends SQLiteOpenHelper {
    /** The model in assets/tessdata, and the tag saved with each result (older results are kept). */
    private static final String LANGUAGE="eng",RECOGNIZER="tess-eng-v1";
    /** Words Tesseract is less sure of than this are dropped: on photos without text it otherwise "reads" noise. */
    private static final float LEAST_CONFIDENCE=55f;
    /** The worker's own recognizer, kept a minute after use (loading the model takes a moment). */
    private static TessBaseAPI tess;private static long tessUsed;
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
    /** A saved result for this exact file (by size and time), from this recognizer or an earlier one. */
    synchronized String cached(String key,String signature){String file=fileSignature(signature);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT text FROM photos WHERE id=? AND (signature=? OR signature LIKE ?)",new String[]{key,signature,file+":%"})){return c.moveToFirst()?c.getString(0):null;}}
    private static String fileSignature(String signature){int last=signature.lastIndexOf(':');return last>0?signature.substring(0,last):signature;}
    private static String signature(File file){return file.length()+":"+file.lastModified()+":"+RECOGNIZER;}
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
        if(!file.isFile()){if(done!=null)done.done("","Download the full photo first.");return;}String key=file.getName(),signature=signature(file);
        String task=key+"|"+signature;int token=generation.get();
        synchronized(waiting){List<Done> callbacks=waiting.get(task);if(callbacks!=null){if(done!=null)callbacks.add(done);return;}callbacks=new ArrayList<>();if(done!=null)callbacks.add(done);waiting.put(task,callbacks);}
        worker.execute(()->{String text="",error="";boolean changed=false;Bitmap bitmap=null;try{
            if(token!=generation.get())return;
            String cached;try(PhotoText db=new PhotoText(c)){cached=force?null:db.cached(key,signature);}if(cached!=null)text=cached;
            else{Bitmap shared=MediaCache.decode(file,2400,false);bitmap=shared==null?null:shared.copy(Bitmap.Config.ARGB_8888,false);if(bitmap==null)throw new java.io.IOException("This picture couldn't be read.");
                text=recognize(c,bitmap);
                synchronized(indexLock){if(token==generation.get()&&enabled(c)&&!message.isEmpty()&&currentPicture(c,message,chat,key))try(PhotoText db=new PhotoText(c)){db.save(key,message,chat,text,signature);changed=true;}}}
        }catch(Exception|OutOfMemoryError|UnsatisfiedLinkError e){error="Text recognition couldn't finish. Try a sharper or smaller picture.";Diagnostics.note("Picture text recognition failed: "+e.getClass().getSimpleName());}
        finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();String result=text,problem=error;boolean indexed=changed;letGoLater();
            main.post(()->{List<Done> callbacks;synchronized(waiting){callbacks=waiting.remove(task);}if(callbacks!=null)for(Done callback:callbacks)callback.done(result,problem);
                if(indexed&&token==generation.get())c.sendBroadcast(new android.content.Intent(PairedService.CHANGED).setPackage(c.getPackageName()).putExtra("kind","PHOTO_TEXT"));});}});
    }
    /** Backfills only files already on this phone; remote history/downloads are left to the normal photo viewer. */
    static void indexDownloaded(Context context,boolean force){Context c=context.getApplicationContext();if(!enabled(c))return;
        synchronized(PhotoText.class){if(!force&&lastBackfill!=0)return;lastBackfill=System.currentTimeMillis();}int token=generation.get();
        worker.execute(()->{List<JSONObject> candidates;try(PairedStore store=new PairedStore(c)){candidates=store.recent(20000,0);}
            int queued=0;try(PhotoText index=new PhotoText(c)){for(JSONObject message:candidates){if(!force&&queued>=100)break;for(JSONObject media:PairedProtocol.media(message))if(MediaCache.mime(media).startsWith("image/")){
                File file=MediaCache.file(c,media,false);if(file.isFile()&&index.cached(file.getName(),signature(file))==null){synchronized(indexLock){if(token!=generation.get()||!enabled(c))return;scan(c,media,file,false,null);queued++;}}}}}});
    }
    /** Runs on the worker thread only (Tesseract isn't thread-safe). Words it's unsure of are left out. */
    private static String recognize(Context c,Bitmap bitmap)throws java.io.IOException{
        if(tess==null){File data=install(c);TessBaseAPI api=new TessBaseAPI();
            if(!api.init(data.getPath(),LANGUAGE,TessBaseAPI.OEM_LSTM_ONLY)){api.recycle();throw new java.io.IOException("The text model couldn't be loaded.");}
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);api.setVariable("user_defined_dpi","300");tess=api;}
        tessUsed=System.currentTimeMillis();tess.setImage(bitmap);tess.getUTF8Text();
        StringBuilder out=new StringBuilder();ResultIterator words=tess.getResultIterator();boolean[] lineBreak={false};
        if(words!=null)try{words.begin();do{if(words.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))lineBreak[0]=true;
                String word=words.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                if(word==null||word.trim().isEmpty())continue;
                if(words.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)<LEAST_CONFIDENCE||!word.matches(".*[\\p{L}\\p{N}].*"))continue;
                if(out.length()>0)out.append(lineBreak[0]?'\n':' ');lineBreak[0]=false;out.append(word.trim());
            }while(words.next(TessBaseAPI.PageIteratorLevel.RIL_WORD));}finally{words.delete();}
        tess.clear();tessUsed=System.currentTimeMillis();
        // Stray single characters are noise, not text worth searching.
        String text=out.toString().trim();return text.replaceAll("[^\\p{L}\\p{N}]","").length()<3?"":text;}
    /** Frees the recognizer a minute after it was last used. */
    private static void letGoLater(){main.removeCallbacksAndMessages(LET_GO);main.postAtTime(()->worker.execute(()->{if(tess!=null&&System.currentTimeMillis()-tessUsed>=55_000){tess.recycle();tess=null;}}),LET_GO,android.os.SystemClock.uptimeMillis()+60_000);}
    private static final Object LET_GO=new Object();
    /** Tesseract reads its model from a file, so the one packed in the app is copied out once. */
    private static File install(Context c)throws java.io.IOException{File dir=new File(c.getFilesDir(),"tesseract"),models=new File(dir,"tessdata"),model=new File(models,LANGUAGE+".traineddata"),stamp=new File(models,LANGUAGE+".version");
        String version=BuildConfig.VERSION_CODE+"";
        if(model.isFile()&&model.length()>0&&stamp.isFile()&&version.equals(new String(java.nio.file.Files.readAllBytes(stamp.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim()))return dir;
        if(!models.isDirectory()&&!models.mkdirs())throw new java.io.IOException("There isn't room for the text model.");
        File part=new File(models,LANGUAGE+".traineddata.part");
        try(java.io.InputStream in=c.getAssets().open("tessdata/"+LANGUAGE+".traineddata");java.io.OutputStream out=new java.io.FileOutputStream(part)){in.transferTo(out);}
        if(!part.renameTo(model)){part.delete();throw new java.io.IOException("The text model couldn't be saved.");}
        java.nio.file.Files.write(stamp.toPath(),version.getBytes(java.nio.charset.StandardCharsets.UTF_8));return dir;}
    static List<JSONObject> matches(Context c,PairedStore store,String words,String chat,int limit){List<JSONObject> out=new ArrayList<>();if(!enabled(c))return out;
        try(PhotoText index=new PhotoText(c)){for(JSONObject hit:index.search(words,chat,limit)){JSONObject m=store.message(hit.optString("message"));if(m==null||PairedProtocol.status(m).contains("DELETED"))continue;
            if(!hit.optString("chat").equals(m.optString("conversationID")))continue;String full=hit.optString("text");int at=full.toLowerCase(java.util.Locale.ROOT).indexOf(words.trim().toLowerCase(java.util.Locale.ROOT));int start=Math.max(0,at-45),end=Math.min(full.length(),start+200);
            MediaOutbox.put(m,"_photoText",(start>0?"…":"")+full.substring(start,end)+(end<full.length()?"…":""));out.add(m);}}return out;}
}
