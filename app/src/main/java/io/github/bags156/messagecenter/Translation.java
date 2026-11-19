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
    static final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"message-translation");t.setDaemon(true);return t;});
    static volatile String lastStep="";
    static final class Result {
        final String text,from,to; final boolean same;
        Result(String text,String from,String to,boolean same){this.text=text;this.from=from;this.to=to;this.same=same;}
    }
    static final class Problem extends Exception { Problem(String message){super(message);} }
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
    static String detect(Context c,String words){
        try{TextLanguage found=c.getSystemService(TextClassificationManager.class).getTextClassifier().detectLanguage(new TextLanguage.Request.Builder(words).build());
            if(found.getLocaleHypothesisCount()>0){ULocale best=found.getLocale(0);if(found.getConfidenceScore(best)>=.6f)return TranslationPlan.language(best.toLanguageTag());}}
        catch(Exception ignored){}
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
        for(String pair:route)prepare(c,pair,progress,cancelled);
        check(cancelled);progress.accept("Translating on your phone…");String said=words;
        for(String pair:route){check(cancelled);String[] codes=pair.split("-");JSONObject files=models(c).getJSONObject(pair);
            try{byte[] answer=TranslationNative.translate(utf8(said),utf8(codes[0]),utf8(codes[1]),utf8(file(c,files.getJSONObject("model")).getPath()),utf8(file(c,files.getJSONObject("vocab")).getPath()),utf8(file(c,files.getJSONObject("lex")).getPath()));
                said=string(answer);if(said.trim().isEmpty())throw new Problem("Couldn't translate that on this phone. Your original is unchanged.");}
            catch(LinkageError e){throw new Problem("The offline translation engine couldn't start. Your original is unchanged.");}}
        check(cancelled);Result result=new Result(said,from,to,false);cache.put(key,result);lastStep="offline translated "+from+" to "+to;return result;
    }
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
