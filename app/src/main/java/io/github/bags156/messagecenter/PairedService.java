package io.github.bags156.messagecenter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Person;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.bags156.gm.bridge.Bridge;
import io.github.bags156.gm.bridge.Client;

/** Own network connection and notifications; no NotificationListener dependency. */
public final class PairedService extends Service {
    static final String CHANGED="io.github.bags156.messagecenter.PAIRED_CHANGED";
    private static final String OLD_CONNECTION="paired-connection",MESSAGES="paired-messages";
    /** The quiet "running" notification Android requires, and the alert shown only when the connection is lost. */
    static final String RUNNING="paired-running",PROBLEMS="paired-problems";
    private static final int FOREGROUND_ID=100,PROBLEM_ID=101;
    /** How long a dropped connection may try to recover by itself before you are told (network switches are brief). */
    private static final long PROBLEM_GRACE_MS=3*60_000L;
    private static volatile PairedService current;
    private static volatile String pendingCookies;
    private final ExecutorService network=Executors.newSingleThreadExecutor(),events=Executors.newSingleThreadExecutor();
    /** Attachment downloads and uploads run here so a large file never delays sending or loading. */
    private final ExecutorService sending=Executors.newSingleThreadExecutor(),media=Executors.newFixedThreadPool(2),phoneRequests=Executors.newSingleThreadExecutor(),readRequests=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private volatile Client client;
    private volatile boolean ready;
    private volatile String status="Disconnected",emoji="";
    private PairedStore store; private SessionVault vault; private Outbox outbox; private boolean flushing,syncing,inactive;
    private ReadReceiptQueue receipts;private boolean readingReceipt;
    private MediaOutbox attachmentOutbox;private boolean flushingMedia;
    private final java.util.Set<String> mediaRefreshes=new java.util.HashSet<>();
    private final java.util.concurrent.atomic.AtomicBoolean refreshingIcons=new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable receiptRetry=this::flushReadReceipts;
    private long lastSync;private final Runnable syncTick=()->requestSync(false);
    /** Per chat: the Google chat time already fetched up to, so the same chat isn't fetched again for nothing. */
    private final Map<String,Long> caughtUp=new ConcurrentHashMap<>();
    /**
     * Backstop checks, in case Google quietly stops pushing: every minute while Message Center
     * is on screen, every 5 minutes otherwise, every 15 with the screen off. Unlocking, opening
     * the app and network changes still check right away.
     */
    private static final long SYNC_VISIBLE_MS=60_000,SYNC_SCREEN_ON_MS=5*60_000,SYNC_SCREEN_OFF_MS=15*60_000,SYNC_MIN_GAP_MS=10_000;
    /** Message Center windows on screen (the app and its bubbles). */
    private static int visibleScreens;
    /** For the problem report: background checks and chat saves since the connection started. */
    private static final java.util.concurrent.atomic.AtomicInteger syncs=new java.util.concurrent.atomic.AtomicInteger(),chatWrites=new java.util.concurrent.atomic.AtomicInteger();
    private static final long startedAt=System.currentTimeMillis();
    static void screenVisible(boolean visible){visibleScreens=Math.max(0,visibleScreens+(visible?1:-1));PairedService s=current;if(s!=null)s.main.post(s::scheduleSync);}
    static String backgroundSummary(){long hours=Math.max(1,(System.currentTimeMillis()-startedAt)/3_600_000);
        return "Background: "+syncs.get()+" catch-up checks and "+chatWrites.get()+" chat saves in about "+hours+" h; checking every "+(visibleScreens>0?"minute (app open)":"5 min (screen on) / 15 min (screen off)");}
    private android.content.BroadcastReceiver screenReceiver;private android.net.ConnectivityManager.NetworkCallback networkCallback;
    static final String KEY_REPLY="reply";
    /** Shortcut category matched by res/xml/shortcuts.xml, so recent chats appear in Android's share sheet. */
    static final String SHARE_CATEGORY="io.github.bags156.messagecenter.SHARE_TARGET";
    /** conversationID -> (phone number -> time the typing indicator expires). */
    private static final Map<String,Map<String,Long>> typing=new ConcurrentHashMap<>();
    private static final long TYPING_TIMEOUT_MS=15_000;
    /** Recent incoming messages per chat, shown together in that chat's notification until it is opened. */
    private final Map<String,List<JSONObject>> notified=new HashMap<>();
    interface Operation {String run(Client client) throws Exception;}
    interface Result {void done(String value,String error);}

    static void start(Context context) {context.getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();context.startForegroundService(new Intent(context,PairedService.class));}
    static void pair(Context context,String cookies) {pendingCookies=cookies;context.startForegroundService(new Intent(context,PairedService.class).setAction("PAIR"));}
    static String status(Context context) {PairedService s=current;return s==null?"Connection stopped":s.status;}
    static String emoji() {PairedService s=current;return s==null?"":s.emoji;}
    static boolean ready() {PairedService s=current;return s!=null && s.ready;}
    static void execute(Operation work,Result result) {run(work,result,0);}
    static void executeMedia(Operation work,Result result) {run(work,result,1);}
    /**
     * Sending a photo or file. It has a line of its own: downloads (every photo in a chat, its
     * thumbnails, voice messages) share the media line two at a time, and a send queued behind
     * them could wait minutes.
     */
    static void executeSend(Operation work,Result result) {run(work,result,2);}
    private static void run(Operation work,Result result,int lane) {
        PairedService s=current;
        if(s==null || !s.ready || s.client==null) {result.done("","Connect to Google Messages first.");return;}
        (lane==2?s.sending:lane==1?s.media:s.network).execute(()-> {
            String value="",error="";
            try {if(!s.ready||s.client==null)throw new IllegalStateException("The connection is not ready.");value=work.run(s.client);}
            catch(Exception e){error=e.getMessage()==null ? "This operation failed.":e.getMessage();}
            String v=value,err=error;s.main.post(()->result.done(v,err));
        });
    }
    static void disconnect(Context context,boolean forget) {
        context.getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();
        if(forget) {new SessionVault(context).clear();try(PairedStore db=new PairedStore(context)){db.clear();}Avatars.clear(context);new ChatSettings(context).clear();new ReadReceiptQueue(context).clear();new MediaOutbox(context).clear();PhotoText.clear(context);}
        PairedService s=current;if(s!=null){s.generation.incrementAndGet();s.ready=false;}
        context.stopService(new Intent(context,PairedService.class));
    }
    @Override public void onCreate() {
        super.onCreate();current=this;Diagnostics.init(this);HevcSandbox.init(this);GivenNames.init(this);Nicknames.load(this);store=new PairedStore(this);vault=new SessionVault(this);outbox=new Outbox(this);receipts=new ReadReceiptQueue(this);attachmentOutbox=new MediaOutbox(this);attachmentOutbox.recover();outbox.recover();watchWakeAndNetwork();
        NotificationManager manager=getSystemService(NotificationManager.class);
        try{manager.deleteNotificationChannel(OLD_CONNECTION);}catch(Exception ignored){}
        NotificationChannel running=new NotificationChannel(RUNNING,"Running in background",NotificationManager.IMPORTANCE_MIN);
        running.setDescription("Android requires this while Message Center stays connected. You can turn it off; messages keep arriving.");running.setShowBadge(false);manager.createNotificationChannel(running);
        NotificationChannel problems=new NotificationChannel(PROBLEMS,"Connection problems",NotificationManager.IMPORTANCE_DEFAULT);
        problems.setDescription("Only when Message Center loses its connection to Google Messages and can't get it back.");problems.setSound(null,null);manager.createNotificationChannel(problems);
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
        if(ready)main.post(this::flushMediaOutbox);
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
                store.clear();receipts.clear();attachmentOutbox.clear();PhotoText.clear(this);Avatars.clear(this);vault.save(fresh.exportSession());emoji="";
                getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();
                update("Paired. Connecting…");
            }
            if(generation.get()!=token)return;
            fresh.start();vault.save(fresh.exportSession());
        } catch(Exception e) {
            ready=false;emoji="";
            update(e.getMessage()==null?"Could not open the saved connection. Sign in again.":e.getMessage());
            if(cookies==null&&generation.get()==token)main.post(()->scheduleAutoReconnect("couldn't connect"));
        }
    }
    // ---- Reconnecting on its own ----
    /**
     * While stuck (Google Messages not answering, the connection ended, or it couldn't connect),
     * the connection is opened fresh from the saved session, like tapping Reconnect: after 1, 2,
     * 5 and 15 minutes, then every 30. Only while online; recovering cancels it.
     */
    private static final long[] AUTO_RECONNECT_MS={60_000,120_000,300_000,900_000,1_800_000};
    private int autoTries;private String autoWhy="";
    private final Runnable autoReconnect=this::autoReconnectNow;
    private void scheduleAutoReconnect(String why){
        if(main.hasCallbacks(autoReconnect))return;autoWhy=why;
        main.postDelayed(autoReconnect,AUTO_RECONNECT_MS[Math.min(autoTries,AUTO_RECONNECT_MS.length-1)]);}
    private void stopAutoReconnect(){main.removeCallbacks(autoReconnect);autoTries=0;}
    private void autoReconnectNow(){
        if(ready||inactive||!vault.exists()||!getSharedPreferences("paired",MODE_PRIVATE).getBoolean("enabled",false))return;
        // Offline: a new connection can't help yet. Look again in a minute (the network watcher also nudges a sync when it's back).
        if(!online()){main.postDelayed(autoReconnect,60_000);return;}
        autoTries++;Diagnostics.note("Reconnecting on its own (try "+autoTries+", "+autoWhy+")");
        network.execute(()->open(null));
        // If this try doesn't connect, the next one comes later; connecting cancels it.
        scheduleAutoReconnect(autoWhy);}
    private boolean online(){try{android.net.ConnectivityManager cm=getSystemService(android.net.ConnectivityManager.class);android.net.Network n=cm.getActiveNetwork();
        android.net.NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);return c!=null&&c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(Exception e){return true;}}
    /** Reconnect (the inbox, chats and Settings): restarts the connection from the saved session, like turning it off and on. */
    static void restart(Context context){Diagnostics.note("Reconnect tapped");disconnect(context,false);new Handler(android.os.Looper.getMainLooper()).postDelayed(()->start(context),300);}
    /** Whether Reconnect would help now: signed in and not connected, and not already connecting or waiting on a sign-in. */
    static boolean canReconnect(Context context){if(ready())return false;String s=status(context);
        if(s==null||s.startsWith("Connecting")||s.startsWith("Sign in")||s.startsWith("Approve")||s.startsWith("Starting device pairing")||s.startsWith("Paired.")||s.startsWith("Google session expired")||s.startsWith("Google Messages for web is open"))return false;
        return new SessionVault(context).exists();}
    private void event(int token,String kind,String payload) {
        try {
            switch(kind) {
                case "PAIR_EMOJI" -> {emoji=new JSONObject(payload).optString("emoji");update("Approve the matching emoji in Google Messages");}
                case "READY" -> {
                    ready=true;emoji="";stopAutoReconnect();update("Connected to Google Messages");scheduleCodeSweep();
                    saveSession(token);main.post(this::flushOutbox);main.post(this::flushReadReceipts);main.post(this::flushMediaOutbox);
                    // Catch up on anything that came in while disconnected, right away.
                    main.post(()->requestSync(false));
                    network.execute(()->{if(generation.get()==token)try{chats(client,"",1);refreshConversationIcons();}catch(Exception e){update("Connected • tap Refresh to load conversations");}});
                }
                case "SAVE_SESSION" -> saveSession(token);
                case "SETTINGS" -> {JSONObject rcs=new JSONObject(payload);ChatSettings settings=new ChatSettings(this);int previous=settings.receiptAvailability();
                    settings.setPhoneRcs(rcs.optBoolean("rcsEnabled"));settings.setPhoneReceipts(rcs.optBoolean("sendReadReceipts"));
                    if(previous==0&&settings.receiptAvailability()==1)receipts.googleEnabled();main.post(this::flushReadReceipts);changed(kind);}
                // Google repeats unchanged chats (every reconnect sends them all): only a real change redraws.
                case "CONVERSATION" -> {if(applyChat(new JSONObject(payload)))changed(kind);}
                case "MESSAGE" -> {
                    JSONObject envelope=new JSONObject(payload),message=envelope.getJSONObject("message");
                    boolean existed=store.message(message,0,true);touch(message);fullArrived(message);
                    if(attachmentOutbox.reconcile(message)){attachmentOutbox.retireCompleted();changed("MEDIA_OUTBOX");}
                    if(!PairedProtocol.outgoing(message) && typing.remove(message.optString("conversationID"))!=null)changed("TYPING");
                    // Answered on the phone: this chat's notification here is no longer needed.
                    if(PairedProtocol.outgoing(message)&&!envelope.optBoolean("old",true))dismiss(message.optString("conversationID"));
                    // Reminders count from when a message reached this app, even if it was sent long before (phone offline, app restarted).
                    if(!existed&&!PairedProtocol.outgoing(message))Nudges.arrived(this,message);
                    if(PairedProtocol.notifyLive(envelope.optBoolean("old",true),existed,message)){autoCopy(message);notifyMessage(message);maybeAutoReply(message);}
                    changed(kind);
                }
                case "TYPING" -> {
                    JSONObject t=new JSONObject(payload);String conv=t.optString("conversationID"),number=t.optString("number");if(conv.isEmpty())return;
                    Map<String,Long> people=typing.computeIfAbsent(conv,k->new ConcurrentHashMap<>());
                    if(t.optBoolean("typing"))people.put(number,System.currentTimeMillis()+TYPING_TIMEOUT_MS);else if(number.isEmpty())people.clear();else people.remove(number);
                    changed(kind);
                }
                case "MEDIA_PROGRESS" -> {JSONObject p=new JSONObject(payload);String tag=p.optString("job");int split=tag.lastIndexOf('|');if(split>0){
                    attachmentOutbox.progress(tag.substring(0,split),Long.parseLong(tag.substring(split+1)),p.optString("phase"),-1,p.optString("tmpID"));changed("MEDIA_OUTBOX");}}
                case "AUTH_REQUIRED" -> {ready=false;stopAutoReconnect();getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();update("Google session expired. Sign in again.");}
                case "DISCONNECTED" -> {ready=false;update("Connection ended • reconnecting on its own");scheduleAutoReconnect("connection ended");}
                case "RECONNECTING" -> {ready=false;update("Waiting for Google Messages • reconnecting on its own");scheduleAutoReconnect("waiting for Google Messages");}
                case "RECOVERED" -> {ready=true;stopAutoReconnect();update("Connected to Google Messages");scheduleCodeSweep();main.post(this::flushOutbox);main.post(this::flushReadReceipts);main.post(this::flushMediaOutbox);main.post(()->requestSync(false));}
                case "STALE" -> main.post(()->requestSync(true));
                case "INACTIVE" -> {inactive=true;stopAutoReconnect();update("Google Messages for web is open somewhere else. Open Message Center to switch back.");}
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
            chat.put("latestMessage",new JSONObject().put("displayContent",Effects.INK.equals(PairedProtocol.effect(message))?"Sent with invisible ink":PairedProtocol.body(message)));
            store.chat(chat);
        }
    }
    private String chats(Client source,String cursor,int folder) throws Exception {
        /* app folder 3 = Google's spam & blocked folder (5) */JSONObject response=new JSONObject(source.chats(cursor,folder==3?5:folder));JSONArray chats=response.optJSONArray("conversations");
        if(chats!=null)for(int i=0;i<chats.length();i++)applyChat(chats.getJSONObject(i));
        String next=PairedProtocol.cursor(response),note="";
        if(!next.isEmpty() && next.equals(cursor)){next="";note="Google repeated a history cursor; stopped to prevent a loop.";}
        if(next.isEmpty())note=response.optString("cursorBytes","").isEmpty()?"End of the conversation pages Google supplied.":"Google returned an unsupported conversation cursor. Some older chats may be unavailable.";
        store.page("folder:"+folder,next,note);changed("CONVERSATION");return "";
    }
    /** Saves a chat from Google; once Google says it's read (on the phone or anywhere), this app's notification goes too. */
    /** Saves a chat from Google; true if anything about it changed. */
    private boolean applyChat(JSONObject c) {boolean changed=store.chat(c);if(changed)chatWrites.incrementAndGet();if(!c.optBoolean("unread"))dismiss(c.optString("conversationID"));return changed;}
    /** Drops a chat's notification if this app posted one (bubbles stay). */
    private void dismiss(String id) {synchronized(notified){if(notified.remove(id)==null)return;}clearNotification(this,id);}
    /** Called when the app is opened: catch up with Google now (at most every 20 seconds). */
    static void syncNow() {PairedService s=current;if(s==null)return;
        if(s.inactive&&s.ready){s.inactive=false;s.network.execute(()->{try{if(s.client!=null){s.client.reactivate();s.update("Connected to Google Messages");}}catch(Exception ignored){}});}
        s.requestSync(true);}
    private void requestSync(boolean throttled) {
        if(!ready||syncing||network.isShutdown())return;long now=System.currentTimeMillis();if(throttled&&now-lastSync<SYNC_MIN_GAP_MS)return;
        syncing=true;lastSync=now;int token=generation.get();
        network.execute(()->{try{if(generation.get()==token&&client!=null&&ready)sync(client);}catch(Exception ignored){}
            finally{main.post(()->{syncing=false;scheduleSync();});}});
    }
    /**
     * Compares Google's newest chats with what arrived live. Google can stop pushing to a
     * paired session without an error; when that happened, the missed messages are fetched,
     * recent unread ones are notified, and the session is re-armed.
     */
    private void sync(Client source) throws Exception {
        // Only the newest few chats: a missed message always moves its chat to the top.
        JSONObject response=new JSONObject(source.latest(8));JSONArray chats=response.optJSONArray("conversations");if(chats==null)return;
        // Missed means Google's chat is newer than the newest message saved here. (Not the saved chat:
        // after a reconnect Google sends the chat list first, so that already matches.) Chats already
        // fetched up to that time aren't fetched again, in case the time moved for something that
        // isn't a message, like a reaction.
        List<String> missed=new ArrayList<>();Map<String,Long> times=new HashMap<>();boolean anyChanged=false;syncs.incrementAndGet();
        for(int i=0;i<chats.length();i++){JSONObject c=chats.getJSONObject(i);String id=c.optString("conversationID");long at=PairedProtocol.time(c);
            if(at>Math.max(store.newestTime(id),caughtUp.getOrDefault(id,0L))+1500){missed.add(id);times.put(id,at);}anyChanged|=applyChat(c);}
        if(!missed.isEmpty()){
            long recent=System.currentTimeMillis()-30*60_000;ChatSettings settings=new ChatSettings(this);
            // Five chats per check; any others are picked up by the next one.
            for(String id:missed.subList(0,Math.min(5,missed.size()))){JSONObject chat=store.chat(id);long version=store.snapshot();
                JSONArray ms=new JSONObject(source.messages(id,"")).optJSONArray("messages");if(ms==null)continue;caughtUp.put(id,times.get(id));
                int fresh=0;
                for(int j=0;j<ms.length();j++){JSONObject m=ms.getJSONObject(j);boolean existed=store.message(m,version,false);
                    if(!existed&&!PairedProtocol.outgoing(m,chat)){fresh++;Nudges.arrived(this,m);}
                    // Only the last half hour is notified, so a long outage doesn't bring a flood of old alerts.
                    if(!existed&&!PairedProtocol.outgoing(m,chat)&&PairedProtocol.time(m)>recent&&chat!=null&&chat.optBoolean("unread")&&!quiet(id,m)){autoCopy(m);main.post(()->postConversation(id,m,false,false));}}
                if(fresh>0)Diagnostics.note("Caught up "+fresh+" missed message"+(fresh==1?"":"s")+" in a chat");}
            try{source.reactivate();}catch(Exception ignored){}
            changed("MESSAGE");
        }
        // Nothing new: no redraw, widget refresh or reminder reschedule.
        if(anyChanged||!missed.isEmpty())changed("CONVERSATION");
    }
    private void scheduleSync() {main.removeCallbacks(syncTick);if(!ready)return;
        boolean screenOn=getSystemService(android.os.PowerManager.class).isInteractive();
        long wait=!screenOn?SYNC_SCREEN_OFF_MS:visibleScreens>0?SYNC_VISIBLE_MS:SYNC_SCREEN_ON_MS;
        // Already overdue for the new rhythm (say, the app just opened): check now.
        main.postDelayed(syncTick,Math.max(0,Math.min(wait,lastSync+wait-System.currentTimeMillis())));}
    /** Pushes most often stall when the phone wakes or changes networks, so check right then. */
    private void watchWakeAndNetwork() {
        screenReceiver=new android.content.BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            if(Intent.ACTION_SCREEN_OFF.equals(i.getAction()))scheduleSync();else requestSync(true);}};
        android.content.IntentFilter filter=new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        networkCallback=new android.net.ConnectivityManager.NetworkCallback(){
            // Give the connection a few seconds to come back on the new network before checking.
            @Override public void onAvailable(android.net.Network n){main.postDelayed(()->requestSync(true),4000);}};
        try{getSystemService(android.net.ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);}catch(Exception e){networkCallback=null;}
    }
    static void loadChats(String cursor,int folder,Result done) {
        execute(c->current.chats(c,cursor,folder),done);
    }
    static void loadMessages(String id,String cursor,Result done) {
        execute(c->{PairedService s=current;long version=s.store.snapshot();JSONObject response=new JSONObject(c.messages(id,cursor));
            JSONArray messages=response.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);s.store.message(m,version,false);JSONObject saved=s.store.message(m.optString("messageID"));if(saved!=null)s.attachmentOutbox.reconcile(saved);s.store.page("source-page:"+m.optString("messageID"),cursor,"known");}s.attachmentOutbox.retireCompleted();
            String next=PairedProtocol.cursor(response),note="";
            if(!next.isEmpty() && next.equals(cursor)){next="";note="Google repeated a history cursor; stopped to prevent a loop.";}
            if(next.isEmpty() && note.isEmpty())note=response.optString("someBytes","").isEmpty()?"End of the history pages Google supplied. This does not prove the full phone archive was shared.":"Google returned a history format this version cannot page. More messages may exist in Google Messages.";
            // Refreshing the newest page must not rewind an existing older-page cursor.
            if(!cursor.isEmpty() || s.store.page("messages:"+id,true).isEmpty() && s.store.page("messages:"+id,false).isEmpty())s.store.page("messages:"+id,next,note);
            try{s.store.chat(new JSONObject(c.chat(id)));}catch(Exception ignored){}s.changed("MESSAGE");return "";
        },done);
    }
    /** Refresh the provider page containing a reply, without rewinding the user's older-history cursor. */
    static void refreshReply(String chat,String message,Result done){execute(c->{PairedService s=current;
        boolean known="known".equals(s.store.page("source-page:"+message,true));String cursor=known?s.store.page("source-page:"+message,false):"";
        java.util.Set<String> visited=new java.util.HashSet<>();
        for(int page=0;page<(known?1:8);page++){if(!visited.add(cursor))break;long version=s.store.snapshot();JSONObject response=new JSONObject(c.messages(chat,cursor));
            JSONArray messages=response.optJSONArray("messages");boolean found=false;
            if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);s.store.message(m,version,false);s.store.page("source-page:"+m.optString("messageID"),cursor,"known");if(message.equals(m.optString("messageID")))found=true;}
            if(found){s.changed("MESSAGE");return "";}String next=PairedProtocol.cursor(response);if(next.isEmpty()||next.equals(cursor))break;cursor=next;
        }
        s.changed("MESSAGE");return "";
    },done);}
    /** Downloads contact photos for these participant IDs into the private photo cache. */
    /**
     * Asks the phone to send the full copy of an attachment that arrived without one. The phone
     * answers at once but sends the photo later, as an update to the message. If it hasn't come
     * after a few minutes, the next look at the chat asks again (the phone may have been asleep).
     */
    private static final Map<String,Long> fullAskedAt=new ConcurrentHashMap<>();
    private static final Map<String,Long> fullFailedAt=new ConcurrentHashMap<>();private static final Map<String,String> fullFailure=new ConcurrentHashMap<>();
    static final long ASK_AGAIN_MS=180_000;
    /** Asks the phone answered without ever sending the attachment, saved so it survives restarts. After this many it's taken as not on the phone. */
    static final int MISSING_AFTER=3;
    private static SharedPreferences askedPrefs(Context c){return c.getSharedPreferences("phone_media",MODE_PRIVATE);}
    /** The phone was asked for this attachment MISSING_AFTER times and never sent it: it most likely doesn't have it any more. */
    static boolean missingOnPhone(Context c,JSONObject media){return askedPrefs(c).getInt(media.optString("_message")+":"+media.optString("_part"),0)>=MISSING_AFTER;}
    /** When the phone was last asked for this attachment, or 0. */
    static long askedAt(JSONObject media){Long t=fullAskedAt.get(media.optString("_message")+":"+media.optString("_part"));return t==null?0:t;}
    static void requestFullMedia(JSONObject media,boolean again,Result done) {
        String message=media.optString("_message"),part=media.optString("_part");
        if(message.isEmpty()||part.isEmpty()){done.done("","This attachment can't be requested from your phone.");return;}
        String key=message+":"+part;long now=System.currentTimeMillis();Long failed=fullFailedAt.get(key),asked=fullAskedAt.get(key);
        // After a failure, wait a couple of minutes before asking again on its own (Retry asks right away).
        if(!again&&failed!=null&&now-failed<120_000){done.done("",fullFailure.getOrDefault(key,"Your phone didn't send it."));return;}
        // Asked a moment ago: the phone may still be sending it.
        if(!again&&asked!=null&&now-asked<ASK_AGAIN_MS){done.done("","");return;}
        // Its own queue: the phone can take a while to answer, and that must not hold up sending or downloads.
        PairedService s=current;if(s==null||!s.ready||s.client==null){done.done("","Connect to Google Messages first.");return;}
        fullAskedAt.put(key,now);
        s.phoneRequests.execute(()->{String err="";try{s.client.requestFullMedia(message,part);}catch(Exception e){err=e.getMessage()==null?"Your phone didn't respond.":e.getMessage();}
            String fe=err;if(!fe.isEmpty()){fullAskedAt.remove(key);fullFailedAt.put(key,System.currentTimeMillis());fullFailure.put(key,fe);}
            else{fullFailedAt.remove(key);SharedPreferences asks=askedPrefs(s);asks.edit().putInt(key,asks.getInt(key,0)+1).apply();}
            Diagnostics.note(fe.isEmpty()?"Asked phone for attachment "+shortId(key)+(asked!=null?" (again)":""):"Phone refused attachment "+shortId(key)+": "+fe);
            s.main.post(()->done.done("",fe));});
    }
    /** Notes in the problem report when an attachment the phone was asked for shows up (IDs only, never content). */
    private void fullArrived(JSONObject message){
        for(JSONObject media:PairedProtocol.media(message)){if(!MediaCache.hasFull(media))continue;String key=media.optString("_message")+":"+media.optString("_part");
            if(askedPrefs(this).contains(key))askedPrefs(this).edit().remove(key).apply();
            Long asked=fullAskedAt.remove(key);if(asked!=null)Diagnostics.note("Phone sent attachment "+shortId(key)+" after "+Math.max(0,(System.currentTimeMillis()-asked)/1000)+"s");}
    }
    private static String shortId(String key){String digits=key.replaceAll("[^A-Za-z0-9:]","");return digits.length()>14?"…"+digits.substring(digits.length()-14):digits;}
    static void loadPhotos(java.util.List<String> ids,Result done) {
        execute(c->{JSONObject photos=new JSONObject(c.thumbnails(String.join(",",ids)));
            for(java.util.Iterator<String> keys=photos.keys();keys.hasNext();){String id=keys.next();
                try{Avatars.save(current,id,android.util.Base64.decode(photos.getString(id),android.util.Base64.DEFAULT));}catch(Exception ignored){}}
            return "";},done);
    }
    /** Marks the conversation read on the phone up to this message; Google Messages then sends RCS read receipts if its own setting allows. */
    static void markRead(String conversationID,String messageID,Result done) {
        execute(c->{c.markRead(conversationID,messageID);return "";},done);
    }
    /** Persist the exact message viewed even when disconnected. Read requests are idempotent. */
    static void viewedForReceipts(Context context,JSONObject chat,JSONObject message,boolean force){
        if(chat==null||message==null||!new ChatSettings(context).receipts(chat.optString("conversationID")))return;
        ReadReceiptQueue queue=new ReadReceiptQueue(context);boolean changed=queue.viewed(chat,message,force);PairedService s=current;
        if(s!=null){if(changed)s.changed("READ_RECEIPT");s.main.post(s::flushReadReceipts);}
    }
    static void refreshReceiptSettings(){PairedService s=current;if(s==null||!s.ready)return;
        s.readRequests.execute(()->{try{if(s.client!=null)s.client.reactivate();}catch(Exception ignored){}s.main.post(s::flushReadReceipts);});}
    private void flushReadReceipts(){
        main.removeCallbacks(receiptRetry);if(readingReceipt||!ready||client==null||readRequests.isShutdown())return;
        ChatSettings settings=new ChatSettings(this);int google=settings.receiptAvailability();
        java.util.function.Predicate<String> enabled=id->settings.receipts(id)&&store.chat(id)!=null;
        JSONObject next=receipts.next(System.currentTimeMillis(),google,enabled);
        if(next==null){long delay=receipts.retryDelay(System.currentTimeMillis(),google,enabled);if(delay>=0)main.postDelayed(receiptRetry,Math.max(500,delay));return;}
        String chat=next.optString("chat"),message=next.optString("messageID");long revision=next.optLong("revision");int token=generation.get();Client source=client;readingReceipt=true;
        readRequests.execute(()->{String error="";try{source.markRead(chat,message);}catch(Exception e){error="Could not deliver the read request to Google Messages.";}
            String failure=error;main.post(()->{readingReceipt=false;if(generation.get()!=token){main.post(receiptRetry);return;}
                if(failure.isEmpty())receipts.accepted(chat,message,revision,System.currentTimeMillis());else receipts.failed(chat,message,revision,failure,System.currentTimeMillis());
                changed("READ_RECEIPT");main.post(receiptRetry);});});
    }
    /** Phone numbers currently typing in this conversation; expired entries are dropped. */
    static List<String> typing(String conversationID) {
        List<String> out=new ArrayList<>();Map<String,Long> people=typing.get(conversationID);if(people==null)return out;long now=System.currentTimeMillis();
        for(Map.Entry<String,Long> e:people.entrySet()){if(e.getValue()<now)people.remove(e.getKey());else out.add(e.getKey());}
        return out;
    }
    static void sendTyping(String chatJSON) {PairedService s=current;if(s==null||!s.ready)return;execute(c->{c.setTyping(chatJSON);return "";},(v,e)->{});}
    /** Opening a chat clears its notification and the messages collected in it. */
    /** The running service's database, so background checks don't open a second copy. */
    static PairedStore sharedStore(){PairedService s=current;return s==null?null:s.store;}
    static void clearNotification(Context context,String id) {
        PairedService s=current;if(s!=null)synchronized(s.notified){s.notified.remove(id);}
        NotificationManager nm=context.getSystemService(NotificationManager.class);Nudges.cancel(context,id);
        // Cancelling a bubbled chat's notification would also close its bubble; quiet it instead (no dot, out of the shade).
        for(StatusBarNotification n:nm.getActiveNotifications())if(("chat:"+id).equals(n.getTag())&&(n.getNotification().flags&Notification.FLAG_BUBBLE)!=0){quietBubble(context,nm,n);return;}
        nm.cancel("chat:"+id,1);
    }
    /** Re-posts a bubble's notification as "seen": the bubble stays, its red dot goes, and it leaves the notification shade. */
    private static void quietBubble(Context context,NotificationManager nm,StatusBarNotification sbn){
        try{Notification old=sbn.getNotification();Notification.BubbleMetadata meta=old.getBubbleMetadata();if(meta==null||meta.isNotificationSuppressed())return;
            Notification.BubbleMetadata.Builder b=meta.getShortcutId()!=null?new Notification.BubbleMetadata.Builder(meta.getShortcutId()):new Notification.BubbleMetadata.Builder(meta.getIntent(),meta.getIcon());
            if(meta.getDesiredHeight()>0)b.setDesiredHeight(meta.getDesiredHeight());if(meta.getDeleteIntent()!=null)b.setDeleteIntent(meta.getDeleteIntent());
            b.setAutoExpandBubble(false).setSuppressNotification(true);
            nm.notify(sbn.getTag(),sbn.getId(),Notification.Builder.recoverBuilder(context,old).setBubbleMetadata(b.build()).setOnlyAlertOnce(true).build());
        }catch(Exception ignored){}
    }
    /**
     * Floats this conversation as a bubble. Returns "" on success, or what to tell the
     * person when Android's bubble settings are off (and opens those settings).
     */
    static String showBubble(Context context,String id) {
        NotificationManager nm=context.getSystemService(NotificationManager.class);
        if(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return "Allow Message Center notifications first; bubbles are a kind of notification.";
        if(!nm.areBubblesEnabled()||nm.getBubblePreference()==NotificationManager.BUBBLE_PREFERENCE_NONE){
            try{context.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception ignored){}
            return "Turn on bubbles for Message Center (and in your phone's notification settings), then try again.";}
        PairedService s=current;if(s==null)return "Connect to Google Messages first, then try again.";
        if(!NotificationPrivacy.showName(context,id))return "Bubbles show the chat's name and photo, so they're off while this chat's notifications show nothing. Change it in this chat's Details, or in Settings → Notifications.";
        s.postConversation(id,null,true,true);return "";
    }
    /**
     * Each chat gets its own PendingIntent (the chat ID is in the intent data, so
     * Android never merges two chats' intents), and it opens that conversation.
     */
    private PendingIntent openIntent(String id) {
        Intent intent=new Intent(this,PairedActivity.class).setAction(PairedActivity.OPEN_CHAT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if(!id.isEmpty())intent.setData(chatUri(id)).putExtra("chat",id);
        return PendingIntent.getActivity(this,id.isEmpty()?0:1,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    /** The notification Android requires while connected: the same quiet text always, with Hide to turn it off. */
    private Notification connectionNotification() {
        PendingIntent hide=PendingIntent.getActivity(this,0x41de,channelSettings(this,RUNNING),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,RUNNING).setSmallIcon(R.drawable.ic_notification).setContentTitle("Message Center is running")
            .setContentText("Keeps your messages coming. Tap Hide to turn off this notification.").setContentIntent(openIntent("")).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null,"Hide",hide).build()).build();
    }
    /** Android's settings page for one of this app's notification channels. */
    static Intent channelSettings(Context context,String channel){return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,channel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
    private long problemSince;
    private final Runnable problemCheck=()->showProblem(false);
    /** Connected: clear any alert. Not connected: alert at once if you need to act, otherwise only if it lasts. */
    private void checkProblem(){
        String now=status==null?"":status;
        if(ready&&!inactive){problemSince=0;main.removeCallbacks(problemCheck);getSystemService(NotificationManager.class).cancel(PROBLEM_ID);return;}
        // Signing in and pairing are things you're doing right now; nothing to warn about.
        if(now.startsWith("Sign in")||now.startsWith("Approve")||now.startsWith("Starting device pairing")||now.startsWith("Paired.")){main.removeCallbacks(problemCheck);problemSince=0;return;}
        boolean act=inactive||now.startsWith("Google session expired")||now.startsWith("Could not open")||now.startsWith("Session could not");
        if(act){showProblem(true);return;}
        if(problemSince==0){problemSince=System.currentTimeMillis();main.removeCallbacks(problemCheck);main.postDelayed(problemCheck,PROBLEM_GRACE_MS);}
        else if(isProblemShowing())showProblem(true); // keep its text current
    }
    private boolean isProblemShowing(){for(StatusBarNotification n:getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==PROBLEM_ID)return true;return false;}
    private void showProblem(boolean now){
        if(ready&&!inactive)return;
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        String text=status==null||status.isEmpty()?"Trying to reconnect…":status;
        getSystemService(NotificationManager.class).notify(PROBLEM_ID,new Notification.Builder(this,PROBLEMS).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(inactive?"Message Center is paused":"Message Center is disconnected").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent("")).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_STATUS).build());
    }
    /** Message IDs whose code was copied automatically, so each code is copied once. */
    private final java.util.Set<String> autoCopied=java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());private volatile String copiedCode="";
    static boolean autoCopyCodes(Context context){return context.getSharedPreferences("paired",MODE_PRIVATE).getBoolean("auto_copy_codes",true);}
    static boolean autoDeleteCodes(Context context){return context.getSharedPreferences("paired",MODE_PRIVATE).getBoolean("auto_delete_codes",true);}
    private final Runnable codeSweep=this::sweepCodes;
    /** A couple of minutes after connecting (once syncing settles), then every 6 hours while connected. */
    private void scheduleCodeSweep(){main.post(()->{main.removeCallbacks(codeSweep);main.postDelayed(codeSweep,2*60_000L);});}
    /**
     * Deletes verification-code texts more than a day old, from Google Messages too. Only from
     * senders who aren't in your contacts, and never in group chats, so a friend's text that
     * happens to contain a code is left alone.
     */
    private void sweepCodes(){
        main.removeCallbacks(codeSweep);if(!ready)return;main.postDelayed(codeSweep,6*3_600_000L);
        if(!autoDeleteCodes(this))return;long cutoff=System.currentTimeMillis()-86_400_000L;
        events.execute(()->{List<String> ids=new ArrayList<>();
            try{for(JSONObject m:store.recent(5000,0)){
                if(PairedProtocol.outgoing(m)||PairedProtocol.time(m)>=cutoff||ChatText.oneTimeCode(PairedProtocol.text(m)).isEmpty())continue;
                JSONObject chat=store.chat(m.optString("conversationID"));JSONObject other=chat==null?null:PairedProtocol.other(chat);
                if(other==null||PairedProtocol.known(other))continue;
                ids.add(m.optString("messageID"));if(ids.size()>=40)break;}}catch(Exception e){Diagnostics.note("Code cleanup failed: "+e);return;}
            if(ids.isEmpty())return;Diagnostics.note("Deleting "+ids.size()+" verification codes older than a day");
            for(String mid:ids)execute(c->{c.deleteMessage(mid);return "";},(v,e)->{if(e.isEmpty()){store.deleteMessage(mid);changed("MESSAGE");}});});
    }
    /** A verification code in a new incoming message goes straight to the clipboard (Settings → Auto-copy verification codes). */
    private void autoCopy(JSONObject m){
        if(!autoCopyCodes(this)||PairedProtocol.outgoing(m)||System.currentTimeMillis()-PairedProtocol.time(m)>10*60_000)return;
        String code=ChatText.oneTimeCode(PairedProtocol.text(m));if(code.isEmpty()||new ChatSettings(this).muted(m.optString("conversationID")))return;
        if(!autoCopied.add(m.optString("messageID")))return;
        copiedCode=code;main.post(()->{try{NotificationActions.copyCode(this,code);}catch(Exception e){copiedCode="";}});}
    /** A conversation-style notification: who sent it, the chat (for groups), the text, and their photo when one is cached. */
    private void notifyMessage(JSONObject m) {String id=m.optString("conversationID");if(quiet(id,m))return;postConversation(id,m,false,false);}
    /** Names that count as an @mention of you: Settings → Mention alerts, plus your name as Google lists it. */
    static List<String> mentionNames(Context context,JSONObject chat) {
        List<String> out=new ArrayList<>();
        for(String n:context.getSharedPreferences("paired",MODE_PRIVATE).getString("mention_name","").split(","))if(!n.trim().isEmpty())out.add(n.trim());
        JSONArray ps=chat==null?null:chat.optJSONArray("participants");
        if(ps!=null)for(int i=0;i<ps.length();i++){JSONObject p=ps.optJSONObject(i);if(p==null||!p.optBoolean("isMe"))continue;
            for(String k:new String[]{"fullName","firstName"}){String v=p.optString(k).trim();if(!v.isEmpty()){out.add(v);out.add(v.split("\\s+")[0]);}}}
        return out;
    }
    /** Muted or snoozed chats stay quiet, unless the message @-mentions you or has one of your alert keywords. */
    private boolean quiet(String id,JSONObject m) {
        ChatSettings s=new ChatSettings(this);if(!s.muted(id)&&!s.snoozed(id))return false;
        String text=PairedProtocol.text(m);
        if(!SmartText.keyword(text,SmartText.splitList(getSharedPreferences("paired",MODE_PRIVATE).getString("alert_keywords",""))).isEmpty())return false;
        return !ChatText.mentions(text,mentionNames(this,store.chat(id)));
    }
    // ---- Busy auto-reply ----
    static long autoReplyUntil(Context c){return c.getSharedPreferences("paired",MODE_PRIVATE).getLong("autoreply_until",0);}
    static void setAutoReply(Context c,long until){c.getSharedPreferences("paired",MODE_PRIVATE).edit().putLong("autoreply_until",until).apply();BusyTile.changed(c);}
    static String autoReplyText(Context c){return c.getSharedPreferences("paired",MODE_PRIVATE).getString("autoreply_text","Auto-reply: I'm busy right now and will text you back soon.");}
    /**
     * While auto-reply is on: one reply per person per hour, only in one-to-one chats with saved
     * contacts (never groups, short codes, or strangers), and only for messages arriving now.
     */
    private void maybeAutoReply(JSONObject m){
        if(autoReplyUntil(this)<=System.currentTimeMillis())return;
        String id=m.optString("conversationID");JSONObject chat=store.chat(id);JSONObject other=PairedProtocol.other(chat);
        if(other==null||!PairedProtocol.known(other)||SmartText.shortCodeSender(PairedProtocol.number(other)))return;
        if(System.currentTimeMillis()-PairedProtocol.time(m)>10*60_000L)return;
        android.content.SharedPreferences p=getSharedPreferences("autoreply",MODE_PRIVATE);long last=p.getLong(id,0);
        if(System.currentTimeMillis()-last<3_600_000L)return;
        p.edit().putLong(id,System.currentTimeMillis()).apply();
        String reply=autoReplyText(this);main.post(()->sendOrQueue(this,id,reply,"",(v,e)->{}));
    }
    /**
     * Posts (or refreshes) a chat's notification. A new message is added to it; with
     * bubbleNow the chat opens as an expanded bubble without alerting.
     */
    private void postConversation(String id,JSONObject m,boolean quiet,boolean bubbleNow) {
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        JSONObject chat=store.chat(id);
        String title=chat==null?"New message":PairedProtocol.title(chat);boolean group=chat!=null&&PairedProtocol.other(chat)==null;
        List<JSONObject> recent;
        synchronized(notified){List<JSONObject> list=notified.computeIfAbsent(id,k->new ArrayList<>());if(m!=null)list.add(m);while(list.size()>6)list.remove(0);recent=new ArrayList<>(list);}
        if(recent.isEmpty()){List<JSONObject> saved=store.messages(id,3);for(JSONObject r:saved)if(!PairedProtocol.body(r).isEmpty())recent.add(r);}
        // What notifications show (this chat's own choice, or Settings): "Name only" swaps what was said for a count; "Nothing" hides who it's from too.
        boolean showText=NotificationPrivacy.showText(this,id),showName=NotificationPrivacy.showName(this,id)||bubbleNow;
        int incoming=0;JSONObject lastIn=null;for(JSONObject r:recent)if(!PairedProtocol.outgoing(r,chat)){incoming++;lastIn=r;}
        boolean replied=!recent.isEmpty()&&PairedProtocol.outgoing(recent.get(recent.size()-1),chat);
        String hidden=replied?"You replied":NotificationPrivacy.count(incoming);
        if(!showName){postNameless(id,chat,recent,hidden,quiet);return;}
        Notification.MessagingStyle style=new Notification.MessagingStyle(new Person.Builder().setName("You").build());
        if(group){style.setConversationTitle(title);style.setGroupConversation(true);}
        Bitmap lastPhoto=null;String lastText="";
        if(!showText){
            if(lastIn!=null){String pid=lastIn.optString("participantID"),sender=PairedProtocol.person(chat,pid,lastIn.optJSONObject("senderParticipant"));Bitmap photo=photo(chat,pid,sender);
                Person.Builder who=new Person.Builder().setName(sender).setKey(pid.isEmpty()?sender:pid);if(photo!=null)who.setIcon(Icon.createWithBitmap(photo));
                String count=NotificationPrivacy.count(incoming);style.addMessage(new Notification.MessagingStyle.Message(count,PairedProtocol.time(lastIn),who.build()));lastPhoto=photo;lastText=group?sender+": "+count:count;}
            if(replied||lastIn==null){style.addMessage(new Notification.MessagingStyle.Message(hidden,recent.isEmpty()?System.currentTimeMillis():PairedProtocol.time(recent.get(recent.size()-1)),(Person)null));lastText=hidden;}
        }
        else for(JSONObject r:recent){boolean mine=PairedProtocol.outgoing(r,chat);
            String pid=r.optString("participantID"),sender=PairedProtocol.person(chat,pid,r.optJSONObject("senderParticipant"));
            if(mine){style.addMessage(new Notification.MessagingStyle.Message(notificationText("You",r),PairedProtocol.time(r),(Person)null));lastText="You: "+notificationText("You",r);continue;}
            String text=notificationText(sender,r);Bitmap photo=photo(chat,pid,sender);
            Person.Builder who=new Person.Builder().setName(sender).setKey(pid.isEmpty()?sender:pid);if(photo!=null)who.setIcon(Icon.createWithBitmap(photo));
            style.addMessage(new Notification.MessagingStyle.Message(text,PairedProtocol.time(r),who.build()));lastPhoto=photo;lastText=group?sender+": "+text:text;
        }
        Notification.Builder builder=new Notification.Builder(this,MESSAGES).setSmallIcon(R.drawable.ic_notification).setStyle(style).setContentTitle(title).setContentText(lastText)
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(openIntent(id)).setAutoCancel(true)
            .setWhen(recent.isEmpty()?System.currentTimeMillis():PairedProtocol.time(recent.get(recent.size()-1))).setShowWhen(true).setNumber(m==null?0:recent.size());
        if(!group&&lastPhoto!=null)builder.setLargeIcon(lastPhoto);
        builder.addAction(replyAction(id)).addAction(readAction(id));
        // A login or verification code in the newest message gets its own Copy button.
        String code=newestCode(recent,chat);
        if(!code.isEmpty()){builder.addAction(copyAction(id,code,showText));if(code.equals(copiedCode))builder.setSubText(showText?"Code "+code+" copied":"Code copied");}
        // Opening a bubble yourself should not ring. A lone notification in its own group with
        // GROUP_ALERT_SUMMARY never alerts (this is what AndroidX's setSilent does).
        if(quiet)builder.setGroup("quiet-"+id).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).setOnlyAlertOnce(true);
        // A long-lived shortcut makes this a "conversation" notification, which Android requires for bubbles.
        Bitmap icon=chatIcon(chat,title);String shortcut=publishShortcut(id,title,icon);
        if(shortcut!=null){builder.setShortcutId(shortcut);builder.setBubbleMetadata(bubble(id,icon,bubbleNow));}
        getSystemService(NotificationManager.class).notify("chat:"+id,1,builder.build());
    }
    private Bitmap chatIcon(JSONObject chat,String title) {
        int size=Math.max(216,Math.min(288,Math.round(72*getResources().getDisplayMetrics().density)));
        if(Avatars.isGroup(chat)){List<JSONObject> people=Avatars.groupMembers(chat);for(int i=0;i<Math.min(3,people.size());i++){JSONObject p=people.get(i);Avatars.fromPhone(this,PairedProtocol.participantID(p),PairedProtocol.number(p));}
            Bitmap group=Avatars.group(this,chat,size);return Avatars.adaptive(group,"",0xff334766,size);}
        JSONObject other=PairedProtocol.other(chat);String pid=PairedProtocol.participantID(other);Bitmap photo=null;
        if(!pid.isEmpty()){Avatars.fromPhone(this,pid,PairedProtocol.number(other));java.io.File f=Avatars.best(this,pid);if(f.isFile())photo=BitmapFactory.decodeFile(f.getPath());}
        String hex=other!=null?other.optString("avatarHexColor",""):chat==null?"":chat.optString("avatarHexColor","");
        // Android keeps conversation icons at up to 72dp (adaptive), so match that for the sharpest bubble.
        return Avatars.adaptive(photo,PairedProtocol.initial(title),PairedProtocol.color(hex,Avatars.fallbackColor(title)),size);
    }
    static void refreshConversationIcons(){PairedService s=current;if(s==null||s.media.isShutdown()||!s.refreshingIcons.compareAndSet(false,true))return;
        try{s.media.execute(()->{try{s.refreshGroupBubbles();}finally{s.refreshingIcons.set(false);}});}catch(java.util.concurrent.RejectedExecutionException ignored){s.refreshingIcons.set(false);}}
    private void refreshGroupBubbles(){NotificationManager manager=getSystemService(NotificationManager.class);
        for(StatusBarNotification sbn:manager.getActiveNotifications()){String tag=sbn.getTag();if(tag==null||!tag.startsWith("chat:"))continue;String id=tag.substring(5);JSONObject chat=store.chat(id);
            Notification old=sbn.getNotification();Notification.BubbleMetadata meta=old.getBubbleMetadata();if(meta==null||!Avatars.isGroup(chat)||!NotificationPrivacy.showName(this,id))continue;
            try{Bitmap icon=chatIcon(chat,PairedProtocol.title(chat));publishShortcut(id,PairedProtocol.title(chat),icon);
                Notification.BubbleMetadata.Builder bubble=meta.getShortcutId()!=null?new Notification.BubbleMetadata.Builder(meta.getShortcutId()):new Notification.BubbleMetadata.Builder(meta.getIntent(),Icon.createWithAdaptiveBitmap(icon));
                if(meta.getDesiredHeight()>0)bubble.setDesiredHeight(meta.getDesiredHeight());else if(meta.getDesiredHeightResId()!=0)bubble.setDesiredHeightResId(meta.getDesiredHeightResId());
                if(meta.getDeleteIntent()!=null)bubble.setDeleteIntent(meta.getDeleteIntent());bubble.setAutoExpandBubble(false).setSuppressNotification(meta.isNotificationSuppressed());
                manager.notify(tag,sbn.getId(),Notification.Builder.recoverBuilder(this,old).setBubbleMetadata(bubble.build()).setOnlyAlertOnce(true).build());
            }catch(Exception ignored){}}
    }
    private String publishShortcut(String id,String title,Bitmap icon) {
        try {
            Intent open=new Intent(this,PairedActivity.class).setAction(PairedActivity.OPEN_CHAT).setData(chatUri(id)).putExtra("chat",id);
            ShortcutInfo info=new ShortcutInfo.Builder(this,"chat-"+id).setShortLabel(title).setLongLived(true).setIntent(open)
                .setIcon(Icon.createWithAdaptiveBitmap(icon)).setPerson(new Person.Builder().setName(title).setKey(id).build())
                .setCategories(new java.util.HashSet<>(java.util.Arrays.asList(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION,SHARE_CATEGORY))).build();
            getSystemService(ShortcutManager.class).pushDynamicShortcut(info);return info.getId();
        } catch(Exception e) {return null;}
    }
    /** The bubble opens the same chat screen in BubbleActivity, which Android can embed in a floating window. */
    private Notification.BubbleMetadata bubble(String id,Bitmap icon,boolean expand) {
        Intent intent=new Intent(this,BubbleActivity.class).setAction(Intent.ACTION_VIEW).setData(chatUri(id)).putExtra("chat",id);
        // Android requires a mutable PendingIntent for bubbles; the intent is explicit to this app.
        PendingIntent pending=PendingIntent.getActivity(this,2,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        return new Notification.BubbleMetadata.Builder(pending,Icon.createWithAdaptiveBitmap(icon)).setDesiredHeight(640).setAutoExpandBubble(expand).setSuppressNotification(expand).build();
    }
    /** Marks an action as working in the background, the extra AndroidX writes and Android Auto reads. */
    private static android.os.Bundle noUi() {android.os.Bundle b=new android.os.Bundle();b.putBoolean("android.support.action.showsUserInterface",false);return b;}
    /** Reply straight from the notification (also what Android Auto reads and answers). */
    private Notification.Action replyAction(String id) {
        Intent intent=new Intent(this,NotificationActions.class).setAction(NotificationActions.REPLY).setData(chatUri(id)).putExtra("chat",id);
        // The reply text is added to this intent by Android, so it must be mutable; it is explicit to this app.
        PendingIntent pending=PendingIntent.getBroadcast(this,3,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this,R.drawable.ic_send),"Reply",pending)
            .addRemoteInput(new RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .addExtras(noUi()).setAllowGeneratedReplies(true).build();
    }
    private Notification.Action copyAction(String id,String code,boolean showCode) {
        Intent intent=new Intent(this,NotificationActions.class).setAction(NotificationActions.COPY).setData(chatUri(id)).putExtra("chat",id).putExtra("code",code);
        PendingIntent pending=PendingIntent.getBroadcast(this,6,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this,R.drawable.ic_notification),showCode?"Copy "+code:"Copy code",pending).addExtras(noUi()).build();
    }
    /** The newest login or verification code someone sent in these messages, or "". */
    private static String newestCode(List<JSONObject> recent,JSONObject chat){
        String code="";for(JSONObject r:recent)if(!PairedProtocol.outgoing(r,chat)){String found=ChatText.oneTimeCode(PairedProtocol.text(r));if(!found.isEmpty())code=found;}return code;}
    /**
     * "Nothing" in Settings: a plain "New message" with no name, photo or text. It can't be a
     * conversation notification (Android shows the chat's name and photo on those), so it has no bubble.
     */
    private void postNameless(String id,JSONObject chat,List<JSONObject> recent,String line,boolean quiet) {
        Notification.Builder b=new Notification.Builder(this,MESSAGES).setSmallIcon(R.drawable.ic_notification).setContentTitle(line)
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(openIntent(id)).setAutoCancel(true)
            .setWhen(recent.isEmpty()?System.currentTimeMillis():PairedProtocol.time(recent.get(recent.size()-1))).setShowWhen(true);
        b.addAction(replyAction(id)).addAction(readAction(id));
        String code=newestCode(recent,chat);if(!code.isEmpty()){b.addAction(copyAction(id,code,false));if(code.equals(copiedCode))b.setSubText("Code copied");}
        if(quiet)b.setGroup("quiet-"+id).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).setOnlyAlertOnce(true);
        getSystemService(NotificationManager.class).notify("chat:"+id,1,b.build());
    }
    /** Redraws the chat notifications already on screen after "What notifications show" changes, without sound. */
    static void refreshChatNotifications(Context context) {
        PairedService s=current;if(s==null)return;
        s.main.post(()->{for(android.service.notification.StatusBarNotification sbn:s.getSystemService(NotificationManager.class).getActiveNotifications()){
            String tag=sbn.getTag();if(tag!=null&&tag.startsWith("chat:"))s.postConversation(tag.substring(5),null,true,false);}});
    }
    /**
     * Opens Android's settings for this one conversation (sound, vibration, priority, Do Not
     * Disturb), creating its own channel first so it can differ from other chats.
     */
    static void openConversationSettings(Context context,String id,String title) {
        NotificationManager nm=context.getSystemService(NotificationManager.class);String shortcut="chat-"+id;
        if(nm.getNotificationChannel(MESSAGES,shortcut)==null){
            NotificationChannel parent=nm.getNotificationChannel(MESSAGES);
            NotificationChannel conv=new NotificationChannel(MESSAGES+":"+id,title,parent==null?NotificationManager.IMPORTANCE_HIGH:parent.getImportance());
            conv.setConversationId(MESSAGES,shortcut);if(parent!=null){conv.setSound(parent.getSound(),parent.getAudioAttributes());conv.enableVibration(parent.shouldVibrate());}
            nm.createNotificationChannel(conv);}
        Intent settings=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName())
            .putExtra(Settings.EXTRA_CHANNEL_ID,MESSAGES).putExtra(Settings.EXTRA_CONVERSATION_ID,shortcut).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{context.startActivity(settings);}catch(Exception e){context.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    }
    /** Publishes a chat's conversation shortcut (needed before it can have its own notification settings). */
    static void ensureShortcut(Context context,String id) {PairedService s=current;if(s==null)return;JSONObject chat=s.store.chat(id);if(chat==null)return;
        String title=PairedProtocol.title(chat);s.publishShortcut(id,title,s.chatIcon(chat,title));}
    private Notification.Action readAction(String id) {
        Intent intent=new Intent(this,NotificationActions.class).setAction(NotificationActions.READ).setData(chatUri(id)).putExtra("chat",id);
        PendingIntent pending=PendingIntent.getBroadcast(this,4,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this,R.drawable.ic_ticks),"Mark as read",pending)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).addExtras(noUi()).build();
    }
    /** Sends a reply typed into a notification, or saves it to send once connected. */
    static void replyFromNotification(Context context,String chat,String text,Runnable done) {
        PairedService s=current;
        if(s==null||!s.ready||s.store.chat(chat)==null){new Outbox(context).add(chat,text,"");if(s!=null){s.changed("OUTBOX");s.showReply(chat,text,"will send when connected");}else{try{start(context);}catch(Exception ignored){}}done.run();return;}
        JSONObject c=s.store.chat(chat);
        execute(cl->cl.sendText(c.toString(),text,""),(v,e)->{
            if(e.isEmpty())s.showReply(chat,text,null);
            else {Outbox box=new Outbox(context);JSONObject o=box.add(chat,text,"");box.setState(o.optString("id"),e.startsWith("Send outcome unknown")?Outbox.UNSURE:Outbox.FAILED,e);s.changed("OUTBOX");s.showReply(chat,text,"not sent, open the chat to retry");}
            done.run();});
    }
    /** Adds your reply to the chat's notification (Android expects the notification to update after a reply). */
    private void showReply(String chat,String text,String note) {
        JSONObject m=new JSONObject();
        try{m.put("conversationID",chat).put("timestamp",System.currentTimeMillis()*1000).put("messageStatus",new JSONObject().put("status","OUTGOING_COMPLETE"))
            .put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content",note==null?text:text+" ("+note+")"))));}catch(Exception ignored){}
        synchronized(notified){List<JSONObject> list=notified.computeIfAbsent(chat,k->new ArrayList<>());list.add(m);while(list.size()>6)list.remove(0);}
        postConversation(chat,null,true,false);
    }
    /** Clears the notification; sends a read receipt only if this chat has receipts turned on. */
    static void markReadFromNotification(Context context,String chat,Runnable done) {
        PairedService s=current;
        if(s!=null)synchronized(s.notified){s.notified.remove(chat);}
        context.getSystemService(NotificationManager.class).cancel("chat:"+chat,1);Nudges.cancel(context,chat);
        ChatSettings settings=new ChatSettings(context);settings.setMarkedUnread(chat,false);
        // Clear the unread dot here even when no read receipt goes to the sender.
        PairedStore own=s==null?new PairedStore(context):null,store=own!=null?own:s.store;JSONObject c=store.chat(chat),latest=store.latestIncoming(chat);if(own!=null)own.close();
        long seenAt=Math.max(c==null?0:PairedProtocol.time(c),latest==null?0:PairedProtocol.time(latest));if(seenAt>0)settings.markSeen(chat,seenAt);
        if(settings.receipts(chat)&&latest!=null){viewedForReceipts(context,c,latest,false);if(s==null&&new SessionVault(context).exists())try{start(context);}catch(RuntimeException ignored){}}
        if(s!=null)s.changed("CONVERSATION");done.run();
    }
    /** A saved attachment keeps running independently of any Activity or bubble. */
    static void flushAttachments(Context context){PairedService s=current;if(s!=null)s.main.post(s::flushMediaOutbox);
        else if(new SessionVault(context).exists())try{start(context);}catch(Exception ignored){}}
    private void flushMediaOutbox(){
        if(flushingMedia||!ready||client==null||sending.isShutdown())return;JSONObject next=attachmentOutbox.next();if(next==null)return;
        String id=next.optString("id"),chat=next.optString("chat");JSONObject conversation=store.chat(chat);
        if(conversation==null){attachmentOutbox.failed(id,next.optLong("attempt"),"This conversation isn't synced. Open it, then retry.");changed("MEDIA_OUTBOX");main.post(this::flushMediaOutbox);return;}
        JSONObject job=attachmentOutbox.begin(id);if(job==null)return;long attempt=job.optLong("attempt");flushingMedia=true;Client source=client;int token=generation.get();changed("MEDIA_OUTBOX");
        sending.execute(()->{try{
            if(!job.optString("mediaTmpID").isEmpty()){
                attachmentOutbox.captionAccepted(id,attempt,source.sendText(conversation.toString(),job.optString("caption"),job.optString("reply")));
            }else{
                java.io.File original=new java.io.File(job.optString("file"));if(!original.isFile())throw new java.io.IOException("The saved attachment is missing. Choose it again.");
                boolean mms=job.optBoolean("forceSmall")||!PairedProtocol.rcs(conversation);PhotoPrep.Ready prepared=null;java.io.File cached=new java.io.File(job.optString("prepared"));
                if(cached.isFile()&&job.optBoolean("mms")==mms)prepared=new PhotoPrep.Ready(cached,job.optString("preparedName"),job.optString("preparedMime"));
                if(prepared==null){if(job.optString("mime").startsWith("video/")){int[] bucket={-1};prepared=VideoPrep.prepare(this,original,job.optString("name"),job.optString("mime"),mms,percent->{
                    if(percent/5!=bucket[0]){bucket[0]=percent/5;attachmentOutbox.progress(id,attempt,MediaOutbox.PREPARING,percent,"");changed("MEDIA_OUTBOX");}});
                }else prepared=PhotoPrep.prepare(original,job.optString("name"),job.optString("mime"),mms,job.optString("quality"));
                    if(!prepared.error.isEmpty())throw new java.io.IOException(prepared.error);attachmentOutbox.prepared(id,attempt,prepared);}
                if(prepared.file.length()>PhotoQuality.bytes(PhotoQuality.FULL,mms))throw new java.io.IOException(mms?"This attachment is too large for MMS. Choose a smaller file or use an RCS chat.":"This attachment is over 100 MB.");
                if(current!=this||generation.get()!=token||!ready)throw new java.io.IOException("The connection changed before submission. Retry when connected.");
                // Persist this BEFORE entering JNI. A process death anywhere in upload/submission is reviewed, never blindly resent.
                attachmentOutbox.bridgeStarting(id,attempt);attachmentOutbox.progress(id,attempt,MediaOutbox.UPLOADING,-1,"");changed("MEDIA_OUTBOX");
                JSONObject result=new JSONObject(source.sendMediaTracked(conversation.toString(),prepared.file.getPath(),prepared.name,prepared.mime,
                    MediaOutbox.sendCaption(job)?job.optString("caption"):"",job.optString("reply"),id+"|"+attempt));
                attachmentOutbox.finish(id,attempt,result);
            }
            // A fast echo can precede the submit response. Reconcile exact IDs against the cached messages again.
            for(JSONObject m:store.messages(chat,100))attachmentOutbox.reconcile(m);
            attachmentOutbox.retireCompleted();
        }catch(Exception e){attachmentOutbox.failed(id,attempt,e.getMessage()==null?"This attachment couldn't be sent.":e.getMessage());}
        finally{main.post(()->{flushingMedia=false;if(current==this){changed("MEDIA_OUTBOX");changed("MESSAGE");
            // History checks use the normal network lane, never hold the next attachment behind them.
            if(mediaRefreshes.add(chat))main.postDelayed(()->{mediaRefreshes.remove(chat);if(current==this&&ready)loadMessages(chat,"",(v,e)->{});},1000);
            flushMediaOutbox();}});}});
    }
    /** Sends saved messages one at a time, oldest first, while connected. */
    private void flushOutbox() {
        if(flushing||!ready)return;JSONObject o=outbox.next();if(o==null)return;
        String id=o.optString("id");JSONObject chat=store.chat(o.optString("chat"));
        if(chat==null){outbox.setState(id,Outbox.FAILED,"This conversation isn't synced yet. Open it, then retry.");changed("OUTBOX");main.post(this::flushOutbox);return;}
        flushing=true;outbox.setState(id,Outbox.SENDING,"");changed("OUTBOX");
        execute(c->c.sendText(chat.toString(),o.optString("text"),o.optString("reply")),(v,e)->{flushing=false;
            if(e.isEmpty())outbox.remove(id);
            else if(e.equals("Connect to Google Messages first.")){outbox.setState(id,Outbox.WAITING,"");changed("OUTBOX");return;}
            else outbox.setState(id,e.startsWith("Send outcome unknown")?Outbox.UNSURE:Outbox.FAILED,e);
            changed("OUTBOX");flushOutbox();});
    }
    /** Sends now if connected, otherwise saves it for later. The result value is "queued" when saved. */
    static void sendOrQueue(Context context,String chat,String text,String reply,Result done) {
        PairedService s=current;
        if(s==null||!s.ready){new Outbox(context).add(chat,text,reply);if(s!=null)s.changed("OUTBOX");done.done("queued","");return;}
        JSONObject c=s.store.chat(chat);if(c==null){done.done("","Refresh this conversation before sending.");return;}
        execute(cl->cl.sendText(c.toString(),text,reply),done);
    }
    /** Sends whatever is waiting in the outbox now, starting the connection first if it isn't running. */
    static void flushOutboxNow(Context context) {PairedService s=current;
        if(s!=null){s.main.post(()->{s.changed("OUTBOX");s.flushOutbox();});}else{try{start(context);}catch(Exception ignored){}}}
    /** Puts a saved message back in line to send (after the person chose Retry). */
    static void retryOutbox(Context context,String id) {new Outbox(context).setState(id,Outbox.WAITING,"");PairedService s=current;if(s!=null){s.changed("OUTBOX");s.flushOutbox();}}
    private static Uri chatUri(String id) {return new Uri.Builder().scheme("messagecenter").authority("chat").appendPath(id).build();}
    private static String notificationText(String sender,JSONObject m) {
        // Invisible ink stays hidden until it's tapped in the chat.
        String fx=PairedProtocol.effect(m);if(Effects.INK.equals(fx))return "Sent with invisible ink";
        String body=PairedProtocol.body(m),reply=ChatText.replyFallbackBody(body);if(body.isEmpty()&&!fx.isEmpty())return "Sent with "+Effects.label(fx);
        if(reply!=null)return "↩ "+reply;
        String line=ChatText.reactionLine("",body);if(line!=null)return line.trim();
        return m.optJSONObject("replyMessage")!=null&&!PairedProtocol.string(m.optJSONObject("replyMessage"),"messageID").isEmpty()?"↩ "+body:body;
    }
    /** The sender's cached contact photo, or a colored initial like the app shows. */
    private Bitmap photo(JSONObject chat,String participantID,String name) {
        if(!participantID.isEmpty()){java.io.File f=Avatars.best(this,participantID);if(f.isFile()){Bitmap b=BitmapFactory.decodeFile(f.getPath());if(b!=null)return Avatars.circle(b,128);}}
        JSONObject p=PairedProtocol.participant(chat,participantID);
        return Avatars.initial(PairedProtocol.initial(name),PairedProtocol.color(p==null?"":p.optString("avatarHexColor"),Avatars.fallbackColor(name)),128);
    }
    private void update(String value) {if(value!=null&&!value.equals(status))Diagnostics.note("Connection: "+value);status=value;main.post(this::checkProblem);changed("STATUS");}
    private final Runnable widgetRefresh=()->{try{MessagesWidget.update(this);}catch(Exception ignored){}new Thread(()->Nudges.reschedule(this),"nudges").start();};
    private void changed(String kind) {sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()).putExtra("kind",kind));
        // The home screen widget follows chat changes, at most every two seconds.
        if(kind.equals("CONVERSATION")||kind.equals("MESSAGE")){main.removeCallbacks(widgetRefresh);main.postDelayed(widgetRefresh,2000);}}
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public void onDestroy() {
        generation.incrementAndGet();ready=false;main.removeCallbacks(autoReconnect);main.removeCallbacks(syncTick);main.removeCallbacks(problemCheck);main.removeCallbacks(codeSweep);main.removeCallbacks(receiptRetry);if(current==this)current=null;
        try{if(screenReceiver!=null)unregisterReceiver(screenReceiver);}catch(Exception ignored){}
        try{if(networkCallback!=null)getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        Client old=client;client=null;network.shutdownNow();events.shutdownNow();media.shutdownNow();sending.shutdownNow();phoneRequests.shutdownNow();readRequests.shutdownNow();
        if(old!=null)new Thread(old::close,"paired-disconnect").start();
        // SQLiteOpenHelper stays alive until in-flight JNI calls return; the process
        // reclaims it. Closing here races with a final incoming update.
        super.onDestroy();
    }
}
