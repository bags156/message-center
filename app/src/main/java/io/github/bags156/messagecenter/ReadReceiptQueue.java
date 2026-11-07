package io.github.bags156.messagecenter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Only records messages the user has viewed, never a future/newer message. */
final class ReadReceiptQueue {
    interface Storage { String read(); void write(String value); }
    private static final Object LOCK=new Object();
    private final Storage storage;
    ReadReceiptQueue(Context context) { this(preferences(context)); }
    ReadReceiptQueue(Storage storage) { this.storage=storage; }
    private static Storage preferences(Context context) {
        SharedPreferences prefs=context.getSharedPreferences("read-receipt-requests",Context.MODE_PRIVATE);
        return new Storage(){public String read(){return prefs.getString("state","{}");}
            public void write(String value){prefs.edit().putString("state",value).apply();}};
    }
    private JSONObject load(){try{return new JSONObject(storage.read());}catch(Exception e){return new JSONObject();}}
    private void save(JSONObject root){storage.write(root.toString());}
    private static void put(JSONObject o,String key,Object value){try{o.put(key,value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static JSONObject copy(JSONObject o){try{return new JSONObject(o.toString());}catch(Exception e){return new JSONObject();}}
    private static List<String> keys(JSONObject o){List<String> result=new ArrayList<>();java.util.Iterator<String> it=o.keys();while(it.hasNext())result.add(it.next());return result;}
    /** The last actual incoming message, excluding membership events and empty status records. */
    static boolean eligible(JSONObject m,JSONObject chat) {
        String status=PairedProtocol.status(m);
        return !m.optString("messageID").isEmpty() && !PairedProtocol.outgoing(m,chat)
            && !status.contains("TOMBSTONE") && !status.contains("DELETED") && !PairedProtocol.body(m).isEmpty();
    }
    boolean viewed(JSONObject chat,JSONObject message,boolean force) {
        if(chat==null || message==null || !eligible(message,chat))return false;
        String id=chat.optString("conversationID"),mid=message.optString("messageID");
        if(id.isEmpty() || !id.equals(message.optString("conversationID")))return false;
        synchronized(LOCK){JSONObject root=load(),old=root.optJSONObject(id);long time=PairedProtocol.time(message);
            String kind=PairedProtocol.kind(message,chat);
            if(old!=null){if(time<old.optLong("time"))return false;
                if(!force && mid.equals(old.optString("messageID")) && kind.equals(old.optString("kind")))return false;}
            JSONObject e=new JSONObject();put(e,"chat",id);put(e,"messageID",mid);put(e,"time",time);put(e,"kind",kind);
            long revision=root.optLong("_revision")+1;put(root,"_revision",revision);put(e,"revision",revision);
            put(e,"submitted",false);put(e,"attempts",0);put(e,"nextAttempt",0);put(root,id,e);save(root);return true;
        }
    }
    JSONObject get(String chat){synchronized(LOCK){JSONObject e=load().optJSONObject(chat);return e==null?null:copy(e);}}
    long retryDelay(long now,int google,Predicate<String> enabled){synchronized(LOCK){JSONObject root=load();long delay=Long.MAX_VALUE;
        for(String id:keys(root)){JSONObject e=root.optJSONObject(id);if(e==null||e.optBoolean("submitted")||!enabled.test(id)||google==0&&"RCS".equals(e.optString("kind")))continue;
            delay=Math.min(delay,Math.max(0,e.optLong("nextAttempt")-now));}return delay==Long.MAX_VALUE?-1:delay;}}
    void cancel(String chat){synchronized(LOCK){JSONObject root=load();root.remove(chat);save(root);}}
    void clear(){synchronized(LOCK){storage.write("{}");}}
    /** Returns a due request, with the selected chat's opt-in checked again immediately before sending. */
    JSONObject next(long now,int google,Predicate<String> enabled) {
        synchronized(LOCK){JSONObject root=load(),found=null;List<String> remove=new ArrayList<>();
            for(String id:keys(root)){JSONObject e=root.optJSONObject(id);if(e==null)continue;
                if(!enabled.test(id)){remove.add(id);continue;}
                if(e.optBoolean("submitted") || e.optLong("nextAttempt")>now || google==0 && "RCS".equals(e.optString("kind")))continue;
                if(found==null || e.optLong("time")<found.optLong("time"))found=e;
            }
            for(String id:remove)root.remove(id);if(!remove.isEmpty())save(root);
            return found==null?null:copy(found);
        }
    }
    /** An older RPC finishing must not erase a newer viewed message queued meanwhile. */
    void accepted(String chat,String message,long revision,long now) {
        synchronized(LOCK){JSONObject root=load(),e=root.optJSONObject(chat);if(e==null || !message.equals(e.optString("messageID")) || e.optLong("revision")!=revision)return;
            put(e,"submitted",true);put(e,"requestedAt",now);put(e,"error","");put(e,"nextAttempt",0);save(root);}
    }
    void failed(String chat,String message,long revision,String error,long now) {
        synchronized(LOCK){JSONObject root=load(),e=root.optJSONObject(chat);if(e==null || !message.equals(e.optString("messageID")) || e.optLong("revision")!=revision)return;
            int attempts=e.optInt("attempts")+1;put(e,"attempts",attempts);put(e,"error",error);
            put(e,"nextAttempt",now+Math.min(120_000L,5_000L*(1L<<Math.min(5,attempts-1))));save(root);}
    }
    /** Google's master switch may have prevented a previously accepted request from producing a receipt. */
    void googleEnabled(){synchronized(LOCK){JSONObject root=load();for(String id:keys(root)){JSONObject e=root.optJSONObject(id);
        if(e!=null && "RCS".equals(e.optString("kind"))){long revision=root.optLong("_revision")+1;put(root,"_revision",revision);put(e,"revision",revision);put(e,"submitted",false);put(e,"nextAttempt",0);put(e,"error","");}}
        save(root);}}
    static String state(JSONObject e,boolean enabled,boolean connected,int google){
        if(!enabled)return "off";if(e==null)return "no_message";
        if(!"RCS".equals(e.optString("kind")))return "sms";
        if(google==0)return "google_off";
        if(e.optBoolean("submitted"))return "accepted";
        if(!connected)return "waiting";
        return e.optString("error").isEmpty()?"queued":"retrying";
    }
}
