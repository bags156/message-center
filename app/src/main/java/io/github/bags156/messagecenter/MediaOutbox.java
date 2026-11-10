package io.github.bags156.messagecenter;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Durable attachments. Only requests known not to have been submitted resume automatically. */
final class MediaOutbox {
    static final String WAITING="waiting",PREPARING="preparing",UPLOADING="uploading",SUBMITTING="submitting",ACCEPTED="accepted",SENT="sent",DELIVERED="delivered",FAILED="failed",UNSURE="unsure";
    interface Storage {String read();void write(String data);}
    private static final Object LOCK=new Object();
    private final Storage storage;private final File directory;
    MediaOutbox(Context context){directory=new File(context.getFilesDir(),"media-outbox");directory.mkdirs();AtomicFile file=new AtomicFile(new File(directory,"jobs.json"));
        storage=new Storage(){public String read(){try{return new String(file.readFully(),StandardCharsets.UTF_8);}catch(java.io.FileNotFoundException e){return "[]";}catch(Exception e){throw new IllegalStateException("The attachment queue couldn't be read.",e);}}
            public void write(String data){java.io.FileOutputStream out=null;try{out=file.startWrite();out.write(data.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw new IllegalStateException("The attachment queue couldn't be saved. Check free space.",e);}}};}
    MediaOutbox(Storage storage){this.storage=storage;directory=null;}
    private List<JSONObject> read(){try{JSONArray a=new JSONArray(storage.read());List<JSONObject> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(a.getJSONObject(i));return out;}catch(org.json.JSONException e){throw new IllegalStateException("The attachment queue needs recovery; it was not discarded.",e);}}
    private void save(List<JSONObject> items){JSONArray a=new JSONArray();for(JSONObject item:items)a.put(item);storage.write(a.toString());}
    static void put(JSONObject o,String k,Object value){try{o.put(k,value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static JSONObject copy(JSONObject o){try{return new JSONObject(o.toString());}catch(Exception e){throw new IllegalStateException(e);}}
    List<JSONObject> all(){synchronized(LOCK){return read();}}
    List<JSONObject> forChat(String chat){List<JSONObject> out=all();out.removeIf(j->!chat.equals(j.optString("chat")));return out;}
    JSONObject get(String id){synchronized(LOCK){for(JSONObject j:read())if(id.equals(j.optString("id")))return j;return null;}}
    void record(JSONObject job){synchronized(LOCK){List<JSONObject> items=read();items.add(copy(job));save(items);}}
    /** Called off the UI thread; source files are moved into private files, outside Android's evictable cache. */
    JSONObject enqueue(String chat,File source,String name,String mime,String caption,String reply,String quality,boolean mms,PhotoPrep.Ready prepared)throws Exception{
        return enqueue(chat,source,name,mime,caption,reply,quality,mms,false,prepared);
    }
    JSONObject enqueue(String chat,File source,String name,String mime,String caption,String reply,String quality,boolean mms,boolean forceSmall,PhotoPrep.Ready prepared)throws Exception{
        String id=UUID.randomUUID().toString();File dir=new File(directory,id);if(!dir.mkdirs())throw new java.io.IOException("There isn't room to save this attachment.");
        File original=new File(dir,"original"),ready=null;boolean moved=false,readyMoved=false;
        try{Files.move(source.toPath(),original.toPath());moved=true;
            JSONObject j=new JSONObject().put("id",id).put("chat",chat).put("file",original.getPath()).put("name",name).put("mime",mime).put("caption",caption).put("reply",reply)
                .put("quality",PhotoQuality.normalize(quality)).put("mms",mms).put("forceSmall",forceSmall).put("created",System.currentTimeMillis()).put("state",WAITING).put("captionState",caption.isEmpty()?"none":"pending").put("attempt",0);
            if(prepared!=null&&prepared.file.isFile()){
                if(prepared.file.equals(source))ready=original;else{ready=new File(dir,"prepared");Files.move(prepared.file.toPath(),ready.toPath());readyMoved=true;}
                j.put("prepared",ready.getPath()).put("preparedName",prepared.name).put("preparedMime",prepared.mime);}
            else if(prepared!=null&&prepared.file.equals(source)){j.put("prepared",original.getPath()).put("preparedName",name).put("preparedMime",mime);}
            record(j);return j;
        }catch(Exception e){if(readyMoved&&ready!=null)try{Files.move(ready.toPath(),prepared.file.toPath());}catch(Exception ignored){}if(moved)try{Files.move(original.toPath(),source.toPath());}catch(Exception ignored){}dir.delete();throw e;}
    }
    boolean change(String id,long attempt,Consumer<JSONObject> action){synchronized(LOCK){List<JSONObject> items=read();for(JSONObject j:items)if(id.equals(j.optString("id"))){
        if(attempt>=0&&attempt!=j.optLong("attempt"))return false;action.accept(j);save(items);return true;}return false;}}
    JSONObject next(){synchronized(LOCK){for(JSONObject j:read())if(WAITING.equals(j.optString("state"))||WAITING.equals(j.optString("captionState")))return j;return null;}}
    JSONObject begin(String id){change(id,-1,j->{put(j,"attempt",j.optLong("attempt")+1);put(j,"finished",false);put(j,"error","");
        put(j,"bridgeStarted",false);if(j.optString("mediaTmpID").isEmpty()){put(j,"state",PREPARING);put(j,"progress",0);}else put(j,"captionState",SUBMITTING);});return get(id);}
    void bridgeStarting(String id,long attempt){change(id,attempt,j->put(j,"bridgeStarted",true));}
    boolean progress(String id,long attempt,String phase,int percent,String mediaTmp){return change(id,attempt,j->{
        if(j.optBoolean("finished"))return;
        if(phase.equals("media_accepted")){put(j,"mediaTmpID",mediaTmp);if(!SENT.equals(j.optString("state"))&&!DELIVERED.equals(j.optString("state")))put(j,"state",ACCEPTED);}
        else if(phase.equals("caption_submitting")){if(!j.optString("captionState").equals(ACCEPTED)){put(j,"captionState",SUBMITTING);put(j,"captionSubmitTmpID",mediaTmp);}}
        else if(j.optString("mediaTmpID").isEmpty()){put(j,"state",phase);put(j,"progress",percent);if(phase.equals(SUBMITTING))put(j,"submitTmpID",mediaTmp);}
    });}
    void prepared(String id,long attempt,PhotoPrep.Ready ready){change(id,attempt,j->{put(j,"prepared",ready.file.getPath());put(j,"preparedName",ready.name);put(j,"preparedMime",ready.mime);});}
    void finish(String id,long attempt,JSONObject result){change(id,attempt,j->{put(j,"finished",true);put(j,"mediaTmpID",result.optString("mediaTmpID",j.optString("mediaTmpID")));
        if(!SENT.equals(j.optString("state"))&&!DELIVERED.equals(j.optString("state")))put(j,"state",ACCEPTED);
        String error=result.optString("captionError");if(!error.isEmpty()){put(j,"captionState",result.optBoolean("captionUncertain")?UNSURE:FAILED);put(j,"captionError",error);}
        else if(!result.optString("captionTmpID").isEmpty()){put(j,"captionTmpID",result.optString("captionTmpID"));put(j,"captionState",ACCEPTED);put(j,"captionError","");}
    });}
    void captionAccepted(String id,long attempt,String tmp){change(id,attempt,j->{put(j,"finished",true);put(j,"captionTmpID",tmp);put(j,"captionState",ACCEPTED);put(j,"captionError","");});}
    void failed(String id,long attempt,String error){change(id,attempt,j->{put(j,"finished",true);String state=error.startsWith("Send outcome unknown")?UNSURE:FAILED;
        if(j.optString("mediaTmpID").isEmpty()){put(j,"state",state);put(j,"error",error);}else{put(j,"captionState",state);put(j,"captionError",error);}});}
    /** Process death while submitting may have sent it. Never turn that into an automatic duplicate. */
    void recover(){synchronized(LOCK){List<JSONObject> items=read();for(JSONObject j:items){String s=j.optString("state");
        if(j.optBoolean("bridgeStarted")&&!j.optBoolean("finished")&&j.optString("mediaTmpID").isEmpty()||s.equals(SUBMITTING)){put(j,"state",UNSURE);put(j,"error","The app stopped during the upload or submission. Check Google Messages before retrying.");}
        else if(s.equals(PREPARING)||s.equals(UPLOADING))put(j,"state",WAITING);
        if(!j.optString("mediaTmpID").isEmpty()&&(j.optString("captionState").equals(SUBMITTING)||j.optString("captionState").equals("pending"))){put(j,"captionState",UNSURE);put(j,"captionError","The photo was accepted but the text's outcome is unconfirmed. Check Google Messages before retrying.");}
        put(j,"attempt",j.optLong("attempt")+1);put(j,"finished",true);}
        save(items);}}
    void retry(String id,boolean caption){change(id,-1,j->{put(j,"attempt",j.optLong("attempt")+1);put(j,"finished",true);
        if(caption){put(j,"captionState",WAITING);put(j,"captionSubmitTmpID","");put(j,"captionError","");}
        else{put(j,"state",WAITING);put(j,"mediaTmpID","");put(j,"submitTmpID","");put(j,"mediaEcho","");put(j,"mediaPresent",false);put(j,"error","");}});}
    static boolean sendCaption(JSONObject j){return !j.optString("caption").isEmpty()&&j.optString("captionState").equals("pending");}
    /** Exact protocol identities only; another photo or a matching caption cannot claim this job. */
    boolean reconcile(JSONObject message){String tmp=message.optString("tmpID"),mid=message.optString("messageID"),chat=message.optString("conversationID");if(tmp.isEmpty()&&mid.isEmpty())return false;
        List<String> identities=ReplyReferences.identities(message);
        synchronized(LOCK){List<JSONObject> items=read();boolean changed=false;for(JSONObject j:items){if(!chat.equals(j.optString("chat")))continue;
            String media=j.optString("mediaTmpID"),caption=j.optString("captionTmpID"),status=PairedProtocol.status(message);if(media.isEmpty())media=j.optString("submitTmpID");if(caption.isEmpty())caption=j.optString("captionSubmitTmpID");
            if(!media.isEmpty()&&identities.contains(media)){put(j,"mediaTmpID",media);put(j,"mediaEcho",mid);put(j,"mediaPresent",!PairedProtocol.media(message).isEmpty());changed=true;
                if(PairedProtocol.failed(message)){put(j,"state",FAILED);put(j,"error","Google Messages reports that the attachment failed. Tap for options.");}
                else if(status.equals("OUTGOING_DELIVERED")||status.equals("OUTGOING_DISPLAYED"))put(j,"state",DELIVERED);else if(status.equals("OUTGOING_COMPLETE"))put(j,"state",SENT);}
            if(!caption.isEmpty()&&identities.contains(caption)){put(j,"captionEcho",mid);changed=true;if(PairedProtocol.failed(message)){put(j,"captionState",FAILED);put(j,"captionError","Google Messages reports that this text failed.");}}
        }if(changed)save(items);return changed;}}
    void remove(String id){synchronized(LOCK){List<JSONObject> items=read();items.removeIf(j->id.equals(j.optString("id")));save(items);if(directory!=null)erase(new File(directory,id));}}
    void clear(){synchronized(LOCK){save(new ArrayList<>());if(directory!=null){File[] files=directory.listFiles();if(files!=null)for(File f:files)if(f.isDirectory())erase(f);}}}
    private static void erase(File file){File[] children=file.listFiles();if(children!=null)for(File f:children)erase(f);file.delete();}
    void retireCompleted(){for(JSONObject j:all())if(j.optBoolean("mediaPresent")&&(SENT.equals(j.optString("state"))||DELIVERED.equals(j.optString("state")))
        &&(j.optString("caption").isEmpty()||!j.optString("captionEcho").isEmpty()&&!FAILED.equals(j.optString("captionState"))&&!UNSURE.equals(j.optString("captionState"))))remove(j.optString("id"));}
    static String label(JSONObject j,boolean online){String s=j.optString("state");return switch(s){
        case PREPARING->j.optString("mime").startsWith("video/")?"Preparing video • "+Math.max(0,j.optInt("progress"))+"%":"Preparing photo…";
        case UPLOADING->"Uploading attachment…";case SUBMITTING->"Submitting to Google Messages…";case ACCEPTED->"Waiting for your phone to send…";
        case SENT->"Sent from your phone";case DELIVERED->"Delivered";case FAILED->"Not sent • Tap for options";case UNSURE->"Send unconfirmed • Tap to check";
        default->online?"Queued to send":"Saved • waiting for connection";};}
    static String captionLabel(JSONObject j){return switch(j.optString("captionState")){case SUBMITTING->"Sending text…";case FAILED->"Text not sent • Tap to retry";case UNSURE->"Text unconfirmed • Tap to check";case ACCEPTED->"Text submitted • waiting for your phone";case WAITING->"Text queued";default->"Text will follow the attachment";};}
}
