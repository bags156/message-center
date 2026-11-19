package io.github.bags156.messagecenter;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Optional, higher-precision offline model. Android manages its explicit, Wi-Fi-only download. */
final class QualityTranslation {
    static final long SIZE=4130417472L;
    static final String SHA="cb8ef0b14bc7f80297ac219399fb17c6504742b00b6b4c8173069fd5d55b1063";
    static final String NAME="translategemma-4b-q8-"+SHA.substring(0,12)+".gguf";
    static final String URL="https://huggingface.co/Menterium/translategemma-4b-it-GGUF/resolve/d1709f49e90452b9189e0144cc6826d199616921/translategemma-4b-it-Q8_0.gguf?download=true";
    private static volatile boolean usedNative;
    private static final java.util.concurrent.ExecutorService checks=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"translation-model-check");t.setDaemon(true);return t;});
    private static final AtomicBoolean verifying=new AtomicBoolean();
    private static final AtomicInteger generation=new AtomicInteger();
    private static final Handler idle=new Handler(Looper.getMainLooper());
    private static final Runnable unload=()->Translation.worker.execute(()->{try{QualityNative.unload();}catch(LinkageError ignored){}finally{usedNative=false;}});
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("translation-quality",Context.MODE_PRIVATE);}
    static File file(Context c){File root=c.getExternalFilesDir(null);return root==null?null:new File(new File(root,"quality-models"),NAME);}
    private static File marker(Context c){return new File(c.getFilesDir(),"quality-model-verified");}
    static boolean ready(Context c){File f=file(c);if(f==null||f.length()!=SIZE)return false;
        try{return (SHA+":"+f.lastModified()).equals(new String(java.nio.file.Files.readAllBytes(marker(c).toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){return false;}}
    static boolean enabled(Context c){return prefs(c).getBoolean("enabled",false)&&ready(c);}
    static void use(Context c,boolean stronger){prefs(c).edit().putBoolean("enabled",stronger).putBoolean("requested",stronger).apply();if(!stronger)release();}
    static void release(){if(!usedNative)return;idle.removeCallbacks(unload);idle.post(unload);}
    private static long id(Context c){return prefs(c).getLong("download",0);}
    private static DownloadManager manager(Context c){return c.getSystemService(DownloadManager.class);}
    static int state(Context c){long id=id(c);if(id==0)return 0;
        try(Cursor q=manager(c).query(new DownloadManager.Query().setFilterById(id))){return q!=null&&q.moveToFirst()?q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)):0;}catch(Exception ignored){return 0;}}
    static boolean pending(Context c){int s=state(c);return verifying.get()||s==DownloadManager.STATUS_PENDING||s==DownloadManager.STATUS_RUNNING||s==DownloadManager.STATUS_PAUSED;}
    static String status(Context c){if(ready(c))return enabled(c)?"Stronger translator · on this phone":"Compact translator · stronger model available";
        if(verifying.get())return "Checking the downloaded model…";String failed=prefs(c).getString("error","");if(!failed.isEmpty())return failed;
        long id=id(c);if(id!=0)try(Cursor q=manager(c).query(new DownloadManager.Query().setFilterById(id))){if(q!=null&&q.moveToFirst()){
            int s=q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if(s==DownloadManager.STATUS_SUCCESSFUL){check(c);return "Checking the downloaded model…";}
            if(s==DownloadManager.STATUS_RUNNING){long bytes=q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));return "Downloading stronger translator · "+Math.min(100,bytes*100/SIZE)+"%";}
            if(s==DownloadManager.STATUS_PENDING||s==DownloadManager.STATUS_PAUSED)return "Waiting for Wi-Fi or download retry";
            if(s==DownloadManager.STATUS_FAILED)return "Download stopped · tap to retry";
        }}catch(Exception ignored){}
        String error=prefs(c).getString("error","");return error.isEmpty()?"Compact translator · stronger model is a 4.1 GB download":error;}
    static void start(Context c)throws Exception{Context app=c.getApplicationContext();if(ready(app)){use(app,true);return;}
        if(pending(app)){prefs(app).edit().putBoolean("requested",true).apply();return;}
        File f=file(app);if(f==null)throw new IOException("Phone storage is unavailable.");File dir=f.getParentFile();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Could not create the model folder.");
        if(dir.getUsableSpace()<SIZE+512_000_000L)throw new IOException("Free at least 4.7 GB to download the stronger translator.");
        long previous=id(app);if(previous!=0)manager(app).remove(previous);f.delete();marker(app).delete();generation.incrementAndGet();
        DownloadManager.Request request=new DownloadManager.Request(Uri.parse(URL)).setTitle("Stronger message translation").setDescription("4.1 GB · stays on this phone")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI).setAllowedOverMetered(false).setAllowedOverRoaming(false).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationUri(Uri.fromFile(f));
        long next=manager(app).enqueue(request);prefs(app).edit().putLong("download",next).putBoolean("requested",true).putString("error","").apply();}
    static void remove(Context c){generation.incrementAndGet();long id=id(c);if(id!=0)manager(c).remove(id);File f=file(c);if(f!=null){f.delete();new File(f.getParentFile(),"translategemma-terms.txt").delete();new File(f.getParentFile(),"translategemma-notice.txt").delete();}marker(c).delete();prefs(c).edit().clear().apply();release();}
    /** No translated or original message text is written here: only the model's verified identity. */
    static void check(Context c){if(!prefs(c).getString("error","").isEmpty()||ready(c)||state(c)!=DownloadManager.STATUS_SUCCESSFUL||!verifying.compareAndSet(false,true))return;
        Context app=c.getApplicationContext();int gen=generation.get();checks.execute(()->{
            try{verify(app,()->generation.get()!=gen);prefs(app).edit().putBoolean("enabled",prefs(app).getBoolean("requested",false)).putString("error","").apply();}
            catch(CancellationException ignored){}catch(Exception e){prefs(app).edit().putString("error","Model verification failed · tap to download again").apply();}
            finally{verifying.set(false);}
        });}
    static void verify(Context c,BooleanSupplier cancelled)throws Exception{File f=file(c);if(f==null||f.length()!=SIZE)throw new IOException("The model download is incomplete.");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] buf=new byte[1024*1024];int n;while((n=in.read(buf))!=-1){if(cancelled.getAsBoolean())throw new CancellationException();hash.update(buf,0,n);}}
        StringBuilder digest=new StringBuilder();for(byte b:hash.digest())digest.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!SHA.equals(digest.toString()))throw new IOException("The model failed its integrity check.");if(cancelled.getAsBoolean())throw new CancellationException();
        for(String name:new String[]{"translategemma-terms.txt","translategemma-notice.txt"})try(InputStream in=c.getAssets().open(name);OutputStream out=new FileOutputStream(new File(f.getParentFile(),name))){in.transferTo(out);}
        try(FileOutputStream out=new FileOutputStream(marker(c))){out.write((SHA+":"+f.lastModified()).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
    /** One direct language pair, without an intermediate English translation. */
    static String translate(Context c,String words,String from,String to,Consumer<String> progress,BooleanSupplier cancelled)throws Exception{
        if(!ready(c))throw new Translation.Problem("Download the stronger translator in Settings → Translation first.");
        idle.removeCallbacks(unload);StringBuilder result=new StringBuilder();
        try{for(String piece:QualityPlan.fragments(words,from)){if(cancelled.getAsBoolean())throw new CancellationException();
                int first=0,last=piece.length();while(first<last&&Character.isWhitespace(piece.charAt(first)))first++;while(last>first&&Character.isWhitespace(piece.charAt(last-1)))last--;
                String body=piece.substring(first,last);if(!TranslationPlan.outgoingText(body)){result.append(piece);continue;}
                progress.accept("Translating on this phone…");usedNative=true;byte[] answer=QualityNative.translate(utf8(QualityPlan.prefix(from,to)),utf8(body),utf8(QualityPlan.SUFFIX),utf8(file(c).getAbsolutePath()),cancelled);
                if(cancelled.getAsBoolean())throw new CancellationException();if(answer==null)throw new Translation.Problem("The stronger translator could not finish. Your original is unchanged.");
                String text=new String(answer,StandardCharsets.UTF_8).trim();if(!QualityPlan.preserves(body,text))throw new Translation.Problem("Translation changed a link, code, time or emoji. Your original is unchanged.");
                result.append(piece,0,first).append(text).append(piece,last,piece.length());}
            String translated=result.toString();if(!QualityPlan.preserves(words,translated))throw new Translation.Problem("Translation changed a link, code, time or emoji. Your original is unchanged.");return translated;
        }catch(LinkageError e){throw new Translation.Problem("The stronger translation engine could not start. Your original is unchanged.");}
        catch(IllegalStateException e){if(cancelled.getAsBoolean())throw new CancellationException();throw new Translation.Problem(e.getMessage()==null?"Translation could not finish. Your original is unchanged.":e.getMessage());}
        finally{idle.removeCallbacks(unload);idle.postDelayed(unload,20_000);}
    }
    private static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private QualityTranslation() {}
}
