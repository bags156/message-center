package io.github.bags156.messagecenter;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MessageListener extends NotificationListenerService {
    public static final String GOOGLE_MESSAGES="com.google.android.apps.messaging";
    private static volatile MessageListener connected;
    private final Map<String, StatusBarNotification> active=new ConcurrentHashMap<>();
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private MessageStore store;

    @Override public void onCreate() { super.onCreate(); store=new MessageStore(this); }
    @Override public void onListenerConnected() {
        connected=this;
        try { for(StatusBarNotification n:getActiveNotifications()) onNotificationPosted(n); }
        catch(SecurityException ignored) { /* Access can be revoked during connection. */ }
        changed();
    }
    @Override public void onListenerDisconnected() { connected=null; active.clear(); changed(); }
    @Override public void onDestroy() {
        if(connected==this) connected=null;
        writer.shutdown(); super.onDestroy();
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if(!GOOGLE_MESSAGES.equals(sbn.getPackageName())) return;
        Notification n=sbn.getNotification();
        if((n.flags & Notification.FLAG_GROUP_SUMMARY)!=0) return;
        // Ignore sync/status/OTP service notifications; capture conversation notifications only.
        if(!Notification.CATEGORY_MESSAGE.equals(n.category)
                && Notification.MessagingStyle.extractMessagingStyleFromNotification(n)==null) return;
        String thread=threadId(sbn); active.put(thread,sbn);
        writer.execute(()-> { capture(sbn,thread); changed(); });
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        String thread=threadId(sbn);
        active.computeIfPresent(thread,(key,current)->current.getKey().equals(sbn.getKey()) ? null:current);
        // Dismissing a notification is not proof that a message was read.
        changed();
    }
    private void capture(StatusBarNotification sbn,String thread) {
        Notification n=sbn.getNotification();
        String title=title(n);
        Notification.MessagingStyle style=Notification.MessagingStyle.extractMessagingStyleFromNotification(n);
        if(style!=null) {
            List<Notification.MessagingStyle.Message> messages=new ArrayList<>(style.getHistoricMessages());
            messages.addAll(style.getMessages());
            for(Notification.MessagingStyle.Message m:messages) {
                if(m.getText()==null || m.getText().length()==0) continue;
                Person person=m.getSenderPerson(); Person self=style.getUser();
                boolean outgoing=person==null || isSelf(person,self);
                String sender=outgoing ? "You":person.getName()==null ? title:person.getName().toString();
                long time=m.getTimestamp()>0 ? m.getTimestamp():sbn.getPostTime();
                store.add(thread,title,sender,m.getText().toString(),time,outgoing,"observed");
            }
        } else {
            CharSequence body=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if(body==null) body=n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if(body!=null) store.add(thread,title,title,body.toString(),sbn.getPostTime(),false,"observed");
        }
    }
    private static boolean isSelf(Person p,Person self) {
        if(self==null) return false;
        // A display name is not a reliable identity, especially in group conversations.
        return p.getKey()!=null && p.getKey().equals(self.getKey());
    }
    private static String title(Notification n) {
        Notification.MessagingStyle style=Notification.MessagingStyle.extractMessagingStyleFromNotification(n);
        CharSequence title=style==null ? null:style.getConversationTitle();
        if(title==null) title=n.extras.getCharSequence(Notification.EXTRA_TITLE);
        return title==null ? "Conversation":title.toString();
    }
    private static String threadId(StatusBarNotification n) {
        String shortcut=n.getNotification().getShortcutId();
        return shortcut!=null ? "shortcut:"+shortcut:"notification:"+n.getKey();
    }
    private void changed() { sendBroadcast(new Intent(MessageStore.CHANGED).setPackage(getPackageName())); }
    public static boolean isConnected() { return connected!=null; }
    private static Notification.Action replyAction(Notification n) {
        if(n.actions==null) return null;
        for(Notification.Action a:n.actions) {
            if(a.actionIntent==null || !GOOGLE_MESSAGES.equals(a.actionIntent.getCreatorPackage())) continue;
            if(a.getSemanticAction()!=Notification.Action.SEMANTIC_ACTION_REPLY) continue;
            RemoteInput[] inputs=a.getRemoteInputs();
            if(inputs!=null) for(RemoteInput r:inputs) if(r.getAllowFreeFormInput()) return a;
        }
        return null;
    }
    public static boolean canReply(String thread) {
        MessageListener s=connected;
        if(s==null) return false;
        StatusBarNotification n=s.active.get(thread);
        return n!=null && replyAction(n.getNotification())!=null;
    }
    public static boolean reply(Context context,String thread,String title,String body) {
        MessageListener s=connected;
        if(s==null || body.trim().isEmpty()) return false;
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        if(keyguard!=null && keyguard.isDeviceLocked()) return false;
        StatusBarNotification n=s.active.get(thread);
        Notification.Action action=n==null ? null:replyAction(n.getNotification());
        if(action==null) return false;
        RemoteInput[] inputs=action.getRemoteInputs(); Bundle results=new Bundle();
        for(RemoteInput input:inputs) if(input.getAllowFreeFormInput()) results.putCharSequence(input.getResultKey(),body);
        Intent response=new Intent(); RemoteInput.addResultsToIntent(inputs,response,results);
        RemoteInput.setResultsSource(response,RemoteInput.SOURCE_FREE_FORM_INPUT);
        try {
            action.actionIntent.send(context,0,response);
            s.writer.execute(()-> {
                s.store.add(thread,title,"You",body,System.currentTimeMillis(),true,"submitted"); s.changed();
            });
            return true;
        } catch(PendingIntent.CanceledException | SecurityException e) { return false; }
    }
    public static boolean openThread(Context context,String thread) {
        MessageListener s=connected;
        StatusBarNotification n=s==null ? null:s.active.get(thread);
        PendingIntent intent=n==null ? null:n.getNotification().contentIntent;
        if(intent==null || !GOOGLE_MESSAGES.equals(intent.getCreatorPackage())) return false;
        try { intent.send(context,0,new Intent()); return true; }
        catch(PendingIntent.CanceledException | SecurityException e) { return false; }
    }
}
