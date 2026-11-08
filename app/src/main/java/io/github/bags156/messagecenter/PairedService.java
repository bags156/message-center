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
    private static final String CONNECTION="paired-connection",MESSAGES="paired-messages";
    private static final int FOREGROUND_ID=100;
    private static volatile PairedService current;
    private static volatile String pendingCookies;
    private final ExecutorService network=Executors.newSingleThreadExecutor(),events=Executors.newSingleThreadExecutor();
    /** Attachment downloads and uploads run here so a large file never delays sending or loading. */
    private final ExecutorService media=Executors.newFixedThreadPool(2),phoneRequests=Executors.newSingleThreadExecutor(),readRequests=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger generation=new AtomicInteger();
    private volatile Client client;
    private volatile boolean ready;
    private volatile String status="Disconnected",emoji="";
    private PairedStore store; private SessionVault vault; private Outbox outbox; private boolean flushing,syncing,inactive;
    private ReadReceiptQueue receipts;private boolean readingReceipt;
    private final Runnable receiptRetry=this::flushReadReceipts;
    private long lastSync;private final Runnable syncTick=()->requestSync(false);
    /** Backstop checks: every minute while you're using the phone, every 5 minutes with the screen off. */
    private static final long SYNC_SCREEN_ON_MS=60_000,SYNC_SCREEN_OFF_MS=5*60_000,SYNC_MIN_GAP_MS=10_000;
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
    static void execute(Operation work,Result result) {run(work,result,false);}
    static void executeMedia(Operation work,Result result) {run(work,result,true);}
    private static void run(Operation work,Result result,boolean forMedia) {
        PairedService s=current;
        if(s==null || !s.ready || s.client==null) {result.done("","Connect to Google Messages first.");return;}
        (forMedia?s.media:s.network).execute(()-> {
            String value="",error="";
            try {if(!s.ready||s.client==null)throw new IllegalStateException("The connection is not ready.");value=work.run(s.client);}
            catch(Exception e){error=e.getMessage()==null ? "This operation failed.":e.getMessage();}
            String v=value,err=error;s.main.post(()->result.done(v,err));
        });
    }
    static void disconnect(Context context,boolean forget) {
        context.getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();
        if(forget) {new SessionVault(context).clear();try(PairedStore db=new PairedStore(context)){db.clear();}Avatars.clear(context);new ChatSettings(context).clear();new ReadReceiptQueue(context).clear();}
        PairedService s=current;if(s!=null){s.generation.incrementAndGet();s.ready=false;}
        context.stopService(new Intent(context,PairedService.class));
    }
    @Override public void onCreate() {
        super.onCreate();current=this;Nicknames.load(this);store=new PairedStore(this);vault=new SessionVault(this);outbox=new Outbox(this);receipts=new ReadReceiptQueue(this);outbox.recover();watchWakeAndNetwork();
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
                store.clear();receipts.clear();Avatars.clear(this);vault.save(fresh.exportSession());emoji="";
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
                    saveSession(token);main.post(this::flushOutbox);main.post(this::flushReadReceipts);scheduleSync();
                    network.execute(()->{if(generation.get()==token)try{chats(client,"",1);}catch(Exception e){update("Connected • tap Refresh to load conversations");}});
                }
                case "SAVE_SESSION" -> saveSession(token);
                case "SETTINGS" -> {JSONObject rcs=new JSONObject(payload);ChatSettings settings=new ChatSettings(this);int previous=settings.receiptAvailability();
                    settings.setPhoneRcs(rcs.optBoolean("rcsEnabled"));settings.setPhoneReceipts(rcs.optBoolean("sendReadReceipts"));
                    if(previous==0&&settings.receiptAvailability()==1)receipts.googleEnabled();main.post(this::flushReadReceipts);changed(kind);}
                case "CONVERSATION" -> {applyChat(new JSONObject(payload));changed(kind);}
                case "MESSAGE" -> {
                    JSONObject envelope=new JSONObject(payload),message=envelope.getJSONObject("message");
                    boolean existed=store.message(message,0,true);touch(message);
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
                case "AUTH_REQUIRED" -> {ready=false;getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();update("Google session expired. Sign in again.");}
                case "DISCONNECTED" -> {ready=false;update("Connection ended. Tap Reconnect in Settings.");}
                case "RECONNECTING" -> {ready=false;update("Waiting for Google Messages • check network and battery settings");}
                case "RECOVERED" -> {ready=true;update("Connected to Google Messages");main.post(this::flushOutbox);main.post(this::flushReadReceipts);main.post(()->requestSync(false));}
                case "STALE" -> main.post(()->requestSync(true));
                case "INACTIVE" -> {inactive=true;update("Google Messages for web is open somewhere else. Open Message Center to switch back.");}
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
        /* app folder 3 = Google's spam & blocked folder (5) */JSONObject response=new JSONObject(source.chats(cursor,folder==3?5:folder));JSONArray chats=response.optJSONArray("conversations");
        if(chats!=null)for(int i=0;i<chats.length();i++)applyChat(chats.getJSONObject(i));
        String next=PairedProtocol.cursor(response),note="";
        if(!next.isEmpty() && next.equals(cursor)){next="";note="Google repeated a history cursor; stopped to prevent a loop.";}
        if(next.isEmpty())note=response.optString("cursorBytes","").isEmpty()?"End of the conversation pages Google supplied.":"Google returned an unsupported conversation cursor. Some older chats may be unavailable.";
        store.page("folder:"+folder,next,note);changed("CONVERSATION");return "";
    }
    /** Saves a chat from Google; once Google says it's read (on the phone or anywhere), this app's notification goes too. */
    private void applyChat(JSONObject c) {store.chat(c);if(!c.optBoolean("unread"))dismiss(c.optString("conversationID"));}
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
        List<String> missed=new ArrayList<>();
        for(int i=0;i<chats.length();i++){JSONObject c=chats.getJSONObject(i);String id=c.optString("conversationID");JSONObject old=store.chat(id);
            if(old!=null&&PairedProtocol.time(c)>PairedProtocol.time(old)+1500)missed.add(id);applyChat(c);}
        if(!missed.isEmpty()){
            long recent=System.currentTimeMillis()-30*60_000;ChatSettings settings=new ChatSettings(this);
            for(String id:missed.subList(0,Math.min(5,missed.size()))){JSONObject chat=store.chat(id);long version=store.snapshot();
                JSONArray ms=new JSONObject(source.messages(id,"")).optJSONArray("messages");if(ms==null)continue;
                for(int j=0;j<ms.length();j++){JSONObject m=ms.getJSONObject(j);boolean existed=store.message(m,version,false);
                    if(!existed&&!PairedProtocol.outgoing(m,chat)&&PairedProtocol.time(m)>recent&&chat!=null&&chat.optBoolean("unread")&&!quiet(id,m)){autoCopy(m);main.post(()->postConversation(id,m,false,false));}}}
            try{source.reactivate();}catch(Exception ignored){}
            changed("MESSAGE");
        }
        changed("CONVERSATION");
    }
    private void scheduleSync() {main.removeCallbacks(syncTick);if(!ready)return;
        boolean screenOn=getSystemService(android.os.PowerManager.class).isInteractive();main.postDelayed(syncTick,screenOn?SYNC_SCREEN_ON_MS:SYNC_SCREEN_OFF_MS);}
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
            JSONArray messages=response.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);s.store.message(m,version,false);s.store.page("source-page:"+m.optString("messageID"),cursor,"known");}
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
    /** Asks the phone (once per attachment) to send the full-size copy of an attachment that arrived as a preview. */
    private static final java.util.Set<String> fullRequested=java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String,Long> fullFailedAt=new ConcurrentHashMap<>();private static final Map<String,String> fullFailure=new ConcurrentHashMap<>();
    static void requestFullMedia(JSONObject media,boolean again,Result done) {
        String message=media.optString("_message"),part=media.optString("_part");
        if(message.isEmpty()||part.isEmpty()){done.done("","This attachment can't be requested from your phone.");return;}
        String key=message+":"+part;Long failed=fullFailedAt.get(key);
        // After a failure, wait a couple of minutes before asking again on its own (Retry asks right away).
        if(!again&&failed!=null&&System.currentTimeMillis()-failed<120_000){done.done("",fullFailure.getOrDefault(key,"Your phone didn't send it."));return;}
        if(!fullRequested.add(key)&&!again){done.done("","");return;}
        // Its own queue: the phone can take a while to answer, and that must not hold up sending or downloads.
        PairedService s=current;if(s==null||!s.ready||s.client==null){fullRequested.remove(message+":"+part);done.done("","Connect to Google Messages first.");return;}
        s.phoneRequests.execute(()->{String err="";try{s.client.requestFullMedia(message,part);}catch(Exception e){err=e.getMessage()==null?"Your phone didn't respond.":e.getMessage();}
            String fe=err;if(!fe.isEmpty()){fullRequested.remove(key);fullFailedAt.put(key,System.currentTimeMillis());fullFailure.put(key,fe);}else fullFailedAt.remove(key);
            s.main.post(()->done.done("",fe));});
    }
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
        // Cancelling a bubbled chat's notification would also close its bubble.
        for(StatusBarNotification n:nm.getActiveNotifications())if(("chat:"+id).equals(n.getTag())&&(n.getNotification().flags&Notification.FLAG_BUBBLE)!=0)return;
        nm.cancel("chat:"+id,1);
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
    private Notification connectionNotification() {
        return new Notification.Builder(this,CONNECTION).setSmallIcon(R.drawable.ic_notification).setContentTitle("Message Center connection")
            .setContentText(status).setContentIntent(openIntent("")).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
    }
    /** Message IDs whose code was copied automatically, so each code is copied once. */
    private final java.util.Set<String> autoCopied=java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());private volatile String copiedCode="";
    static boolean autoCopyCodes(Context context){return context.getSharedPreferences("paired",MODE_PRIVATE).getBoolean("auto_copy_codes",true);}
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
    static void setAutoReply(Context c,long until){c.getSharedPreferences("paired",MODE_PRIVATE).edit().putLong("autoreply_until",until).apply();}
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
        Notification.MessagingStyle style=new Notification.MessagingStyle(new Person.Builder().setName("You").build());
        if(group){style.setConversationTitle(title);style.setGroupConversation(true);}
        Bitmap lastPhoto=null;String lastText="";
        for(JSONObject r:recent){boolean mine=PairedProtocol.outgoing(r,chat);
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
        String code="";for(JSONObject r:recent)if(!PairedProtocol.outgoing(r,chat)){String found=ChatText.oneTimeCode(PairedProtocol.text(r));if(!found.isEmpty())code=found;}
        if(!code.isEmpty()){builder.addAction(copyAction(id,code));if(code.equals(copiedCode))builder.setSubText("Code "+code+" copied");}
        // Opening a bubble yourself should not ring. A lone notification in its own group with
        // GROUP_ALERT_SUMMARY never alerts (this is what AndroidX's setSilent does).
        if(quiet)builder.setGroup("quiet-"+id).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).setOnlyAlertOnce(true);
        // A long-lived shortcut makes this a "conversation" notification, which Android requires for bubbles.
        Bitmap icon=chatIcon(chat,title);String shortcut=publishShortcut(id,title,icon);
        if(shortcut!=null){builder.setShortcutId(shortcut);builder.setBubbleMetadata(bubble(id,icon,bubbleNow));}
        getSystemService(NotificationManager.class).notify("chat:"+id,1,builder.build());
    }
    private Bitmap chatIcon(JSONObject chat,String title) {
        JSONObject other=PairedProtocol.other(chat);String pid=PairedProtocol.participantID(other);Bitmap photo=null;
        if(!pid.isEmpty()){Avatars.fromPhone(this,pid,PairedProtocol.number(other));java.io.File f=Avatars.best(this,pid);if(f.isFile())photo=BitmapFactory.decodeFile(f.getPath());}
        String hex=other!=null?other.optString("avatarHexColor",""):chat==null?"":chat.optString("avatarHexColor","");
        // Android keeps conversation icons at up to 72dp (adaptive), so match that for the sharpest bubble.
        int size=Math.max(216,Math.min(288,Math.round(72*getResources().getDisplayMetrics().density)));
        return Avatars.adaptive(photo,PairedProtocol.initial(title),PairedProtocol.color(hex,Avatars.fallbackColor(title)),size);
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
    private Notification.Action copyAction(String id,String code) {
        Intent intent=new Intent(this,NotificationActions.class).setAction(NotificationActions.COPY).setData(chatUri(id)).putExtra("chat",id).putExtra("code",code);
        PendingIntent pending=PendingIntent.getBroadcast(this,6,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this,R.drawable.ic_notification),"Copy "+code,pending).addExtras(noUi()).build();
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
        String body=PairedProtocol.body(m),reply=ChatText.replyFallbackBody(body);
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
    private void update(String value) {status=value;if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)getSystemService(NotificationManager.class).notify(FOREGROUND_ID,connectionNotification());changed("STATUS");}
    private final Runnable widgetRefresh=()->{try{MessagesWidget.update(this);}catch(Exception ignored){}new Thread(()->Nudges.reschedule(this),"nudges").start();};
    private void changed(String kind) {sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()).putExtra("kind",kind));
        // The home screen widget follows chat changes, at most every two seconds.
        if(kind.equals("CONVERSATION")||kind.equals("MESSAGE")){main.removeCallbacks(widgetRefresh);main.postDelayed(widgetRefresh,2000);}}
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public void onDestroy() {
        generation.incrementAndGet();ready=false;main.removeCallbacks(syncTick);main.removeCallbacks(receiptRetry);if(current==this)current=null;
        try{if(screenReceiver!=null)unregisterReceiver(screenReceiver);}catch(Exception ignored){}
        try{if(networkCallback!=null)getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        Client old=client;client=null;network.shutdownNow();events.shutdownNow();media.shutdownNow();phoneRequests.shutdownNow();readRequests.shutdownNow();
        if(old!=null)new Thread(old::close,"paired-disconnect").start();
        // SQLiteOpenHelper stays alive until in-flight JNI calls return; the process
        // reclaims it. Closing here races with a final incoming update.
        super.onDestroy();
    }
}
