package io.github.bags156.messagecenter;

import android.content.Context;
import android.icu.util.ULocale;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextLanguage;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Offline Bergamot translation. Only model files are fetched; message text never leaves the phone. */
final class Translation {
    /** Every translation runs here, one at a time; what you're waiting on goes first (TranslationWorker). */
    static final TranslationWorker worker=new TranslationWorker("message-translation");
    static volatile String lastStep="";
    static final class Result {
        final String text,from,to; final boolean same;
        Result(String text,String from,String to,boolean same){this.text=text;this.from=from;this.to=to;this.same=same;}
    }
    static class Problem extends Exception { Problem(String message){super(message);} }
    /** A language pack would download over mobile data, which wasn't allowed: ask (or wait for Wi-Fi). */
    static final class NeedsData extends Problem {
        final long bytes; final String pairs;
        NeedsData(String pairs,long bytes,String what){super(what+" needs a "+Math.max(1,Math.round(bytes/1e6))+" MB language pack, and you're on mobile data.");this.pairs=pairs;this.bytes=bytes;}
    }
    private static android.content.SharedPreferences settings(Context c){return c.getSharedPreferences("translation-packs",Context.MODE_PRIVATE);}
    /** Language packs download on mobile data without asking (Settings → Translation). Off by default: they wait for Wi-Fi, or you're asked. */
    static boolean mobileData(Context c){return settings(c).getBoolean("mobile_data",false);}
    static void setMobileData(Context c,boolean on){settings(c).edit().putBoolean("mobile_data",on).apply();}
    /** Pairs you said yes to downloading on mobile data this time ("Download now"). */
    private static final Set<String> allowedOnce=Collections.synchronizedSet(new HashSet<>());
    static void allowOnce(String pairs){allowedOnce.addAll(Arrays.asList(pairs.split(",")));}
    /** Stands in for the network being metered (the device checks; the emulator is on Wi-Fi). */
    static volatile Boolean meteredForTests;
    private static boolean metered(Context c){if(meteredForTests!=null)return meteredForTests;try{android.net.ConnectivityManager cm=c.getSystemService(android.net.ConnectivityManager.class);return cm!=null&&cm.isActiveNetworkMetered();}catch(Exception e){return false;}}
    /** The compact engine keeps the models it used loaded (fast for the next message) until it's been idle a while, or the app leaves the screen. */
    private static volatile boolean compactLoaded;
    private static final android.os.Handler idle=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable unloadCompact=()->worker.execute(()->{try{TranslationNative.unload();}catch(LinkageError ignored){}finally{compactLoaded=false;}});
    /** Lets go of loaded compact models (and the stronger one). */
    static void release(){QualityTranslation.release();if(!compactLoaded)return;idle.removeCallbacks(unloadCompact);idle.post(unloadCompact);}
    private static JSONObject models;
    // Bounded, memory-only cache: no translated private text is exported to backups or logs.
    private static final Map<String,Result> cache=new LinkedHashMap<String,Result>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Result> e){return size()>128;}};
    private Translation() {}
    private static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String string(byte[] b){return b==null?"":new String(b,StandardCharsets.UTF_8);}
    private static synchronized JSONObject models(Context c) {
        if(models==null)try(InputStream in=c.getAssets().open("translation-models.json")){models=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        catch(Exception e){models=new JSONObject();}
        return models;
    }
    static List<String> languages(Context c){Set<String> codes=new HashSet<>();Iterator<String> keys=models(c).keys();while(keys.hasNext())for(String code:keys.next().split("-"))codes.add(code);
        List<String> out=new ArrayList<>(codes);out.sort(Comparator.comparing(Translation::label));return out;}
    static String engineKey(Context c){return QualityTranslation.enabled(c)?"stronger-q8":"compact";}
    static String label(String code){return code==null||code.isEmpty()?"Off":Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault());}
    static String defaultLanguage(Context c){String code=TranslationPlan.language(Locale.getDefault().toLanguageTag());return languages(c).contains(code)?code:"en";}
    /** Confidence checked, local language ID. Unknown/very short text is left for a source-language choice. */
    static String detect(Context c,String words){return detect(c,words,.6f,true);}
    /**
     * What a draft you're sending is written in, or "" when that isn't clear. A short draft needs a
     * very confident answer from the phone's own language ID (the fallback guesses on so little).
     */
    static String writtenIn(Context c,String words){float sure=TranslationPlan.sureEnough(words);return sure>1?"":detect(c,words,sure,sure<=.6f);}
    private static String detect(Context c,String words,float sure,boolean fallback){
        try{TextLanguage found=c.getSystemService(TextClassificationManager.class).getTextClassifier().detectLanguage(new TextLanguage.Request.Builder(words).build());
            if(found.getLocaleHypothesisCount()>0){ULocale best=found.getLocale(0);if(found.getConfidenceScore(best)>=sure)return TranslationPlan.language(best.toLanguageTag());}}
        catch(Exception ignored){}
        if(!fallback)return "";
        try{String code=TranslationPlan.language(string(TranslationNative.detect(utf8(words))));return "und".equals(code)?"":code;}
        catch(LinkageError|RuntimeException ignored){return "";}
    }
    /** Called on worker. The caller supplies cancellation tied to the exact chat/draft. */
    static Result translate(Context c,String words,String source,String target,Consumer<String> progress,BooleanSupplier cancelled)throws Exception{
        check(cancelled);if(words.trim().isEmpty()||words.length()>12000)throw new Problem("Choose up to 12,000 characters to translate.");
        String from=TranslationPlan.language(source),to=TranslationPlan.language(target);
        if(from.isEmpty())from=detect(c,words);
        if(from.isEmpty())throw new Problem("Language unclear. Choose the message's language and try again.");
        if(from.equals(to))return new Result(words,from,to,true);
        boolean stronger=QualityTranslation.enabled(c);String key=(stronger?"stronger":"compact")+"\u0000"+from+"\u0000"+to+"\u0000"+words;Result saved=cache.get(key);if(saved!=null)return saved;
        if(stronger){String said=QualityTranslation.translate(c,words,from,to,progress,cancelled);check(cancelled);Result result=new Result(said,from,to,false);cache.put(key,result);lastStep="stronger offline translated "+from+" to "+to;return result;}
        String[] route=TranslationPlan.route(from,to);if(route.length==0)throw new Problem("Choose a translation language.");
        for(String pair:route)if(models(c).optJSONObject(pair)==null)throw new Problem(label(from)+" to "+label(to)+" is not available offline yet.");
        // On mobile data, a pack that isn't here yet waits for Wi-Fi unless you said it can use data.
        if(metered(c)&&!mobileData(c)){long need=0;List<String> missing=new ArrayList<>();
            for(String pair:route){if(allowedOnce.contains(pair))continue;long bytes=missingBytes(c,pair);if(bytes>0){need+=bytes;missing.add(pair);}}
            if(need>0)throw new NeedsData(String.join(",",missing),need,"Translating "+label(from)+" to "+label(to));}
        for(String pair:route)prepare(c,pair,progress,cancelled);
        for(String pair:route)allowedOnce.remove(pair);
        check(cancelled);progress.accept("Translating on your phone…");String said=words;idle.removeCallbacks(unloadCompact);compactLoaded=true;
        for(String pair:route){check(cancelled);String[] codes=pair.split("-");JSONObject files=models(c).getJSONObject(pair);
            try{byte[] answer=TranslationNative.translate(utf8(said),utf8(codes[0]),utf8(codes[1]),utf8(file(c,files.getJSONObject("model")).getPath()),utf8(file(c,files.getJSONObject("vocab")).getPath()),utf8(file(c,files.getJSONObject("lex")).getPath()));
                said=string(answer);if(said.trim().isEmpty())throw new Problem("Couldn't translate that on this phone. Your original is unchanged.");}
            catch(LinkageError e){throw new Problem("The offline translation engine couldn't start. Your original is unchanged.");}}
        check(cancelled);Result result=new Result(said,from,to,false);cache.put(key,result);lastStep="offline translated "+from+" to "+to;
        idle.removeCallbacks(unloadCompact);idle.postDelayed(unloadCompact,30_000);return result;
    }
    /** How much of this pair's pack still has to download (0 when it's all here). */
    private static long missingBytes(Context c,String pair){long need=0;try{JSONObject files=models(c).getJSONObject(pair);
            for(String kind:new String[]{"model","vocab","lex"}){JSONObject spec=files.getJSONObject(kind);File f=file(c,spec);if(!f.isFile()||f.length()!=spec.getLong("size")||!new File(f.getPath()+".ok").isFile())need+=spec.getLong("size");}}
        catch(Exception ignored){}return need;}
    private static void check(BooleanSupplier cancelled)throws CancellationException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static File file(Context c,JSONObject spec)throws Exception {return new File(new File(c.getFilesDir(),"translation-models"),spec.getString("sha256")+"-"+spec.getString("name"));}
    private static final Set<String> verified=new HashSet<>();
    private static boolean ready(File f,long size,String expectedHash){
        if(!f.isFile()||f.length()!=size||!new File(f.getPath()+".ok").isFile())return false;
        if(verified.contains(f.getPath()))return true;
        try(InputStream in=new FileInputStream(f)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);
            StringBuilder hash=new StringBuilder();for(byte v:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",v&255));if(!hash.toString().equals(expectedHash))return false;
            verified.add(f.getPath());return true;}catch(Exception e){return false;}
    }
    private static void prepare(Context c,String pair,Consumer<String> progress,BooleanSupplier cancelled)throws Exception{
        JSONObject files=models(c).getJSONObject(pair);for(String kind:new String[]{"model","vocab","lex"}){
            JSONObject spec=files.getJSONObject(kind);File dest=file(c,spec);long expected=spec.getLong("size");if(ready(dest,expected,spec.getString("sha256")))continue;
            check(cancelled);File dir=dest.getParentFile();if(!dir.isDirectory()&&!dir.mkdirs())throw new Problem("Not enough storage for the language pack.");
            if(dir.getUsableSpace()<expected+20_000_000L)throw new Problem("Free some storage to download this language pack.");
            File part=new File(dest.getPath()+".part");HttpURLConnection conn=null;
            try{
                URL url=new URL(spec.getString("url"));if(!"https".equals(url.getProtocol())||!"firefox-settings-attachments.cdn.mozilla.net".equals(url.getHost()))throw new IOException("model host");
                conn=(HttpURLConnection)url.openConnection();conn.setConnectTimeout(20000);conn.setReadTimeout(20000);conn.setInstanceFollowRedirects(false);conn.setRequestProperty("Accept-Encoding","identity");
                if(conn.getResponseCode()!=200)throw new IOException("model download");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0,last=0;
                try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(part)){byte[] buffer=new byte[65536];int n;
                    while((n=in.read(buffer))!=-1){check(cancelled);total+=n;if(total>expected)throw new IOException("model size");digest.update(buffer,0,n);out.write(buffer,0,n);
                        long now=System.currentTimeMillis();if(now-last>350){progress.accept("Downloading "+label(pair.split("-")[0])+" → "+label(pair.split("-")[1])+" · "+(total*100/expected)+"%");last=now;}}
                    out.getFD().sync();}
                StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
                if(total!=expected||!hash.toString().equals(spec.getString("sha256")))throw new IOException("model checksum");
                if(!part.renameTo(dest))throw new IOException("model install");verified.add(dest.getPath());try(FileOutputStream marker=new FileOutputStream(dest.getPath()+".ok")){marker.write(1);}
            }catch(CancellationException e){throw e;}catch(Exception e){throw new Problem("Language pack couldn't download. Check your connection and try again; your original is unchanged.");}
            finally{if(conn!=null)conn.disconnect();part.delete();}
        }
    }
}
