package io.github.bags156.messagecenter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.bags156.gm.bridge.Bridge;
import io.github.bags156.gm.bridge.Client;

/** Own network connection and notifications; no NotificationListener dependency. */
public final class PairedService extends Service {
    static final String CHANGED="io.github.bags156.messagecenter.PAIRED_CHANGED";
    private static final String CONNECTION="paired-connection",MESSAGES="paired-messages";
    private static final int FOREGROUND_ID=100;
    private static volatile PairedService current;
    private static volatile String pendingCookies;
    private final ExecutorService network=Executors.newSingleThreadExecutor(),events=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private volatile Client client;
    private volatile boolean ready;
    private volatile String status="Disconnected",emoji="";
    private PairedStore store; private SessionVault vault;
    interface Operation {String run(Client client) throws Exception;}
    interface Result {void done(String value,String error);}

    static void start(Context context) {context.getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();context.startForegroundService(new Intent(context,PairedService.class));}
    static void pair(Context context,String cookies) {pendingCookies=cookies;context.startForegroundService(new Intent(context,PairedService.class).setAction("PAIR"));}
    static String status(Context context) {PairedService s=current;return s==null?"Connection stopped":s.status;}
    static String emoji() {PairedService s=current;return s==null?"":s.emoji;}
    static boolean ready() {PairedService s=current;return s!=null && s.ready;}
    static void execute(Operation work,Result result) {
        PairedService s=current;
        if(s==null || !s.ready || s.client==null) {result.done("","Connect to Google Messages first.");return;}
        s.network.execute(()-> {
            String value="",error="";
            try {if(!s.ready||s.client==null)throw new IllegalStateException("The connection is not ready.");value=work.run(s.client);}
            catch(Exception e){error=e.getMessage()==null ? "This operation failed.":e.getMessage();}
            String v=value,err=error;s.main.post(()->result.done(v,err));
        });
    }
    static void disconnect(Context context,boolean forget) {
        context.getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();
        if(forget) {new SessionVault(context).clear();try(PairedStore db=new PairedStore(context)){db.clear();}}
        PairedService s=current;if(s!=null){s.generation.incrementAndGet();s.ready=false;}
        context.stopService(new Intent(context,PairedService.class));
    }
    @Override public void onCreate() {
        super.onCreate();current=this;store=new PairedStore(this);vault=new SessionVault(this);
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CONNECTION,"Background connection",NotificationManager.IMPORTANCE_LOW));
        NotificationChannel messages=new NotificationChannel(MESSAGES,"Incoming messages",NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription("Messages received through your paired Google Messages connection");manager.createNotificationChannel(messages);
        Notification foreground=connectionNotification();
        if(Build.VERSION.SDK_INT>=34)startForeground(FOREGROUND_ID,foreground,ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        else startForeground(FOREGROUND_ID,foreground);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "PAIR".equals(intent.getAction())) {
            String cookies=pendingCookies;pendingCookies=null;
            if(cookies!=null)network.execute(()->open(cookies));
        } else if(client==null && vault.exists()) network.execute(()->{if(client==null)open(null);});
        else if(client==null && !vault.exists()) {update("Sign in to connect");stopSelf();}
        return vault.exists()?START_STICKY:START_NOT_STICKY;
    }
    private void open(String cookies) {
        int token=generation.incrementAndGet();ready=false;emoji="";
        Client previous=client;client=null;if(previous!=null)previous.close();
        try {
            update(cookies==null?"Connecting to Google Messages…":"Starting device pairing…");
            Client fresh=Bridge.newClient(cookies==null?vault.load():"",(kind,payload)->{
                if(!events.isShutdown())events.execute(()->{if(generation.get()==token)event(token,kind,payload);});
            });client=fresh;
            if(cookies!=null) {
                fresh.pair(cookies);
                if(generation.get()!=token)return;
                // A newly paired phone/account must not inherit another session's IDs.
                store.clear();vault.save(fresh.exportSession());emoji="";
                getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();
                update("Paired. Connecting…");
            }
            if(generation.get()!=token)return;
            fresh.start();vault.save(fresh.exportSession());
        } catch(Exception e) {
            ready=false;emoji="";
            update(e.getMessage()==null?"Could not open the saved connection. Sign in again.":e.getMessage());
        }
    }
    private void event(int token,String kind,String payload) {
        try {
            switch(kind) {
                case "PAIR_EMOJI" -> {emoji=new JSONObject(payload).optString("emoji");update("Approve the matching emoji in Google Messages");}
                case "READY" -> {
                    ready=true;emoji="";update("Connected to Google Messages");
                    saveSession(token);
                    network.execute(()->{if(generation.get()==token)try{chats(client,"",1);}catch(Exception e){update("Connected • tap Refresh to load conversations");}});
                }
                case "SAVE_SESSION" -> saveSession(token);
                case "CONVERSATION" -> {store.chat(new JSONObject(payload));changed(kind);}
                case "MESSAGE" -> {
                    JSONObject envelope=new JSONObject(payload),message=envelope.getJSONObject("message");
                    boolean existed=store.message(message,0,true);touch(message);
                    if(PairedProtocol.notifyLive(envelope.optBoolean("old",true),existed,message))notifyMessage(message);
                    changed(kind);
                }
                case "AUTH_REQUIRED" -> {ready=false;getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();update("Google session expired. Sign in again.");}
                case "DISCONNECTED" -> {ready=false;update("Connection ended. Tap Reconnect in Settings.");}
                case "RECONNECTING" -> {ready=false;update("Waiting for Google Messages • check network and battery settings");}
                case "RECOVERED" -> {ready=true;update("Connected to Google Messages");}
                default -> { }
            }
        }catch(Exception e){update("A Google update could not be saved. Refresh the conversation.");}
    }
    private void saveSession(int token) {
        if(!network.isShutdown())network.execute(()->{if(generation.get()==token && client!=null && ready)try{vault.save(client.exportSession());}catch(Exception e){update("Session could not be saved. Keep the app open and reconnect.");}});
    }
    private void touch(JSONObject message) throws Exception {
        String id=message.optString("conversationID");JSONObject chat=store.chat(id);
        if(chat==null) {chat=new JSONObject().put("conversationID",id).put("status","ACTIVE");
            if(!network.isShutdown())network.execute(()->{try{store.chat(new JSONObject(client.chat(id)));changed("CONVERSATION");}catch(Exception ignored){}});}
        if(PairedProtocol.time(message)>=PairedProtocol.time(chat)) {
            chat.put("lastMessageTimestamp",String.valueOf(message.optLong("timestamp")));
            chat.put("latestMessage",new JSONObject().put("displayContent",PairedProtocol.body(message)));
            store.chat(chat);
        }
    }
    private String chats(Client source,String cursor,int folder) throws Exception {
        JSONObject response=new JSONObject(source.chats(cursor,folder));JSONArray chats=response.optJSONArray("conversations");
        if(chats!=null)for(int i=0;i<chats.length();i++)store.chat(chats.getJSONObject(i));
        String next=PairedProtocol.cursor(response),note="";
        if(!next.isEmpty() && next.equals(cursor)){next="";note="Google repeated a history cursor; stopped to prevent a loop.";}
        if(next.isEmpty())note=response.optString("cursorBytes","").isEmpty()?"End of the conversation pages Google supplied.":"Google returned an unsupported conversation cursor. Some older chats may be unavailable.";
        store.page("folder:"+folder,next,note);changed("CONVERSATION");return "";
    }
    static void loadChats(String cursor,int folder,Result done) {
        execute(c->current.chats(c,cursor,folder),done);
    }
    static void loadMessages(String id,String cursor,Result done) {
        execute(c->{PairedService s=current;long version=s.store.snapshot();JSONObject response=new JSONObject(c.messages(id,cursor));
            JSONArray messages=response.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++)s.store.message(messages.getJSONObject(i),version,false);
            String next=PairedProtocol.cursor(response),note="";
            if(!next.isEmpty() && next.equals(cursor)){next="";note="Google repeated a history cursor; stopped to prevent a loop.";}
            if(next.isEmpty() && note.isEmpty())note=response.optString("someBytes","").isEmpty()?"End of the history pages Google supplied. This does not prove the full phone archive was shared.":"Google returned a history format this version cannot page. More messages may exist in Google Messages.";
            // Refreshing the newest page must not rewind an existing older-page cursor.
            if(!cursor.isEmpty() || s.store.page("messages:"+id,true).isEmpty() && s.store.page("messages:"+id,false).isEmpty())s.store.page("messages:"+id,next,note);
            try{s.store.chat(new JSONObject(c.chat(id)));}catch(Exception ignored){}s.changed("MESSAGE");return "";
        },done);
    }
    private PendingIntent openIntent(String id) {
        Intent intent=new Intent(this,PairedActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("chat",id);
        return PendingIntent.getActivity(this,id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification connectionNotification() {
        return new Notification.Builder(this,CONNECTION).setSmallIcon(R.drawable.ic_app).setContentTitle("Message Center connection")
            .setContentText(status).setContentIntent(openIntent("")).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
    }
    private void notifyMessage(JSONObject m) {
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        String id=m.optString("conversationID");JSONObject chat=store.chat(id);String title=chat==null?"New message":PairedProtocol.title(chat);
        String sender=PairedProtocol.person(chat,m.optString("participantID"),m.optJSONObject("senderParticipant"));
        Notification notification=new Notification.Builder(this,MESSAGES).setSmallIcon(R.drawable.ic_app).setContentTitle(title)
            .setContentText(sender+": "+PairedProtocol.body(m)).setStyle(new Notification.BigTextStyle().bigText(sender+": "+PairedProtocol.body(m)))
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(openIntent(id)).setAutoCancel(true).setWhen(PairedProtocol.time(m)).build();
        getSystemService(NotificationManager.class).notify("chat:"+id,1,notification);
    }
    private void update(String value) {status=value;getSystemService(NotificationManager.class).notify(FOREGROUND_ID,connectionNotification());changed("STATUS");}
    private void changed(String kind) {sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()).putExtra("kind",kind));}
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public void onDestroy() {
        generation.incrementAndGet();ready=false;if(current==this)current=null;
        Client old=client;client=null;network.shutdownNow();events.shutdownNow();
        if(old!=null)new Thread(old::close,"paired-disconnect").start();
        // SQLiteOpenHelper stays alive until in-flight JNI calls return; the process
        // reclaims it. Closing here races with a final incoming update.
        super.onDestroy();
    }
}
