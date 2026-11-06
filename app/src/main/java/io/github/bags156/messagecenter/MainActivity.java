package io.github.bags156.messagecenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private final int BLUE=Color.rgb(40,100,240);
    private int bg,card,ink,muted,line;
    private LinearLayout root,list,chat;
    private EditText composer;
    private ScrollView chatScroll;
    private MessageStore store;
    private String thread,title,query="";
    private boolean preview=false,registered=false;
    private final Map<String,String> drafts=new HashMap<>();
    private final BroadcastReceiver changes=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if(preview) return;
            if(thread==null) renderInbox(); else renderChat();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new MessageStore(this);
        if(state!=null) {
            thread=state.getString("thread"); title=state.getString("title"); preview=state.getBoolean("preview");
            if(thread!=null) drafts.put(thread,state.getString("draft",""));
        }
        if(thread==null) showInbox(); else showThread(thread,title);
    }
    @Override protected void onResume() {
        super.onResume();
        if(!registered) {
            IntentFilter filter=new IntentFilter(MessageStore.CHANGED);
            if(Build.VERSION.SDK_INT>=33) registerReceiver(changes,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(changes,filter,"io.github.bags156.messagecenter.permission.INTERNAL",null);
            registered=true;
        }
        if(thread==null) showInbox(); else renderChat();
    }
    @Override protected void onPause() {
        rememberDraft();
        if(registered) { unregisterReceiver(changes); registered=false; }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        rememberDraft(); state.putString("thread",thread); state.putString("title",title);
        state.putBoolean("preview",preview); state.putString("draft",thread==null ? "":drafts.get(thread));
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if(thread!=null) { rememberDraft(); showInbox(); }
        else if(preview) { preview=false; showInbox(); }
        else super.onBackPressed();
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private void theme() {
        boolean dark=(getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        bg=Color.parseColor(dark ? "#101219":"#F6F7FB");
        card=Color.parseColor(dark ? "#1B1F2A":"#FFFFFF");
        ink=Color.parseColor(dark ? "#F4F6FB":"#171D2C");
        muted=Color.parseColor(dark ? "#A9B1C4":"#6C7588");
        line=Color.parseColor(dark ? "#2A3040":"#E8ECF3");
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | (dark ? 0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
    }
    private void startScreen() {
        theme(); root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(bg);
        root.setPadding(dp(20),dp(16),dp(20),dp(12));
        root.setOnApplyWindowInsetsListener((v,insets)-> {
            v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(16),dp(20),insets.getSystemWindowInsetBottom()+dp(12));
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); if(bold) t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        return t;
    }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private LinearLayout vertical() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private void space(LinearLayout parent,int height) { View v=new View(this); parent.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    private TextView button(String label,boolean primary,Runnable action) {
        TextView b=text(label,15,primary ? Color.WHITE:BLUE,true);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(17),dp(13),dp(17),dp(13));
        b.setMinHeight(dp(48)); b.setBackground(shape(primary ? BLUE:card,16));
        b.setOnClickListener(v->action.run()); b.setContentDescription(label); return b;
    }
    private boolean hasAccess() {
        NotificationManager manager=getSystemService(NotificationManager.class);
        return manager!=null && manager.isNotificationListenerAccessGranted(new ComponentName(this,MessageListener.class));
    }
    private void connect() {
        new AlertDialog.Builder(this).setTitle("Connect Google Messages")
            .setMessage("Android will ask you to allow notification access. Message Center uses it to save Google Messages previews on this phone and offer available quick replies. Android grants broad notification access, but this app filters for Google Messages only.\n\nFull history and media stay in Google Messages. If Android blocks this setting for a downloaded APK, open App info → ⋮ → Allow restricted settings, then try again.")
            .setPositiveButton("Open notification access",(d,w)-> {
                try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                catch(ActivityNotFoundException e) { toast("Notification access settings are unavailable."); }
            }).setNegativeButton("Later",null).show();
    }
    private void showInbox() {
        rememberDraft(); thread=null; composer=null; startScreen();
        LinearLayout top=row();
        TextView kicker=text(preview ? "DESIGN PREVIEW":"GOOGLE MESSAGES COMPANION",11,muted,true);
        top.addView(kicker,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("Settings",false,this::settings)); root.addView(top); space(root,12);
        LinearLayout headline=row(); headline.addView(text("Messages",34,ink,true),new LinearLayout.LayoutParams(0,-2,1));
        headline.addView(button("＋",true,()->openGoogle(true))); root.addView(headline);
        space(root,5); root.addView(text("A calmer place for your conversations.",14,muted,false)); space(root,22);
        EditText search=new EditText(this); search.setSingleLine(true); search.setTextSize(15);
        search.setTextColor(ink); search.setHintTextColor(muted); search.setHint("Search recent conversations");
        search.setPadding(dp(16),dp(12),dp(16),dp(12)); search.setBackground(shape(card,16));
        search.setText(query); search.setContentDescription("Search recent conversations");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { query=s.toString(); renderInbox(); }
            public void afterTextChanged(Editable s) {}
        }); root.addView(search,new LinearLayout.LayoutParams(-1,dp(52))); space(root,18);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        list=vertical(); scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); renderInbox();
    }
    private void renderInbox() {
        if(list==null || thread!=null) return; list.removeAllViews();
        if(!preview && !hasAccess()) { connectionCard(); space(list,20); }
        if(preview) {
            TextView info=text("Sample conversations • no messages will be sent",12,muted,false);
            info.setPadding(0,0,0,dp(14)); list.addView(info);
        }
        List<MessageStore.ThreadInfo> threads=preview ? previewThreads():store.threads(query);
        if(threads.isEmpty()) {
            LinearLayout empty=vertical(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(16),dp(28),dp(16),dp(28));
            TextView icon=text("✦",48,BLUE,false); empty.addView(icon); space(empty,12);
            empty.addView(text(query.isEmpty() ? "Make room for a little calm":"No conversations found",22,ink,true)); space(empty,10);
            TextView detail=text(query.isEmpty() ? "New Google Messages notifications will appear here after you connect. Your existing conversations are still in Google Messages.":"Try a name or text from the latest message.",14,muted,false);
            detail.setGravity(Gravity.CENTER); detail.setLineSpacing(dp(4),1); empty.addView(detail); space(empty,24);
            empty.addView(button("Open Google Messages",true,()->openGoogle(false))); space(empty,10);
            empty.addView(button("Preview the design",false,()-> { preview=true; query=""; showInbox(); })); list.addView(empty);
        } else {
            TextView section=text("RECENT CONVERSATIONS",11,muted,true); section.setPadding(0,0,0,dp(12)); list.addView(section);
            LinearLayout container=vertical(); container.setBackground(shape(card,22));
            for(int i=0;i<threads.size();i++) {
                MessageStore.ThreadInfo t=threads.get(i); container.addView(conversation(t));
                if(i<threads.size()-1) { View divider=new View(this); divider.setBackgroundColor(line);
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1)); p.setMargins(dp(78),0,dp(16),0); container.addView(divider,p); }
            }
            list.addView(container); space(list,18);
            TextView foot=text("Saved from notifications on this phone. Open Google Messages for your full history.",12,muted,false);
            foot.setLineSpacing(dp(3),1); list.addView(foot);
        }
    }
    private void connectionCard() {
        LinearLayout box=vertical(); box.setPadding(dp(18),dp(18),dp(18),dp(18)); box.setBackground(shape(card,20));
        box.addView(text("Keep your RCS. Make it yours.",19,ink,true)); space(box,8);
        TextView detail=text("Connect notifications to collect recent messages and reply through Google Messages.",14,muted,false);
        detail.setLineSpacing(dp(3),1); box.addView(detail); space(box,16); box.addView(button("Connect Google Messages",true,this::connect)); list.addView(box);
    }
    private View conversation(MessageStore.ThreadInfo t) {
        LinearLayout r=row(); r.setPadding(dp(16),dp(18),dp(16),dp(18));
        TextView avatar=text(initials(t.title),18,BLUE,true); avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(shape(Color.argb(26,40,100,240),18)); r.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout content=vertical(); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1); cp.setMargins(dp(14),0,0,0); r.addView(content,cp);
        LinearLayout first=row(); TextView name=text(t.title,16,ink,true); name.setSingleLine(); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        first.addView(name,new LinearLayout.LayoutParams(0,-2,1)); first.addView(text(time(t.time),11,muted,false)); content.addView(first); space(content,5);
        TextView snippet=text(t.snippet,14,muted,false); snippet.setSingleLine(); snippet.setEllipsize(android.text.TextUtils.TruncateAt.END); content.addView(snippet);
        r.setOnClickListener(v->showThread(t.id,t.title)); r.setContentDescription("Conversation with "+t.title); return r;
    }
    private void showThread(String id,String name) {
        rememberDraft(); thread=id; title=name; list=null; startScreen();
        LinearLayout header=row(); header.addView(button("‹",false,this::showInbox));
        LinearLayout names=vertical(); TextView heading=text(name,19,ink,true); heading.setSingleLine(); heading.setEllipsize(android.text.TextUtils.TruncateAt.END); names.addView(heading);
        names.addView(text(preview ? "Sample conversation":"Via Google Messages",11,muted,false));
        LinearLayout.LayoutParams namesP=new LinearLayout.LayoutParams(0,-2,1); namesP.setMargins(dp(12),0,dp(8),0); header.addView(names,namesP);
        header.addView(button("Open ↗",false,this::openCurrentThread)); root.addView(header); space(root,14);
        TextView note=text("Recent notification history • RCS stays in Google Messages",11,muted,false); note.setGravity(Gravity.CENTER); root.addView(note); space(root,10);
        chatScroll=new ScrollView(this); chatScroll.setFillViewport(true); chat=vertical(); chat.setPadding(0,dp(16),0,dp(16)); chatScroll.addView(chat);
        root.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composeRow=row(); composeRow.setPadding(0,dp(8),0,0);
        composer=new EditText(this); composer.setHint(preview ? "Preview only":"Write a reply…"); composer.setTextColor(ink); composer.setHintTextColor(muted);
        composer.setTextSize(16); composer.setMaxLines(5); composer.setPadding(dp(16),dp(10),dp(16),dp(10)); composer.setBackground(shape(card,20));
        composer.setText(drafts.getOrDefault(thread,"")); composeRow.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        TextView send=button("Send",true,this::send); LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2); sp.setMargins(dp(8),0,0,0); composeRow.addView(send,sp);
        root.addView(composeRow); renderChat();
    }
    private void renderChat() {
        if(chat==null || thread==null) return;
        boolean bottom=chat.getHeight()-chatScroll.getScrollY()-chatScroll.getHeight()<dp(90);
        chat.removeAllViews();
        List<MessageStore.Message> messages=preview ? previewMessages(thread):store.messages(thread);
        for(MessageStore.Message m:messages) {
            if(m.hidden) continue;
            LinearLayout wrap=vertical(); wrap.setGravity(m.outgoing ? Gravity.END:Gravity.START);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2); wp.setMargins(m.outgoing ? dp(44):0,0,m.outgoing ? 0:dp(44),dp(12)); chat.addView(wrap,wp);
            if(!m.outgoing) { TextView sender=text(m.sender,11,muted,false); sender.setPadding(dp(8),0,0,dp(5)); wrap.addView(sender); }
            TextView bubble=text(m.text,16,m.outgoing ? Color.WHITE:ink,false); bubble.setTextIsSelectable(true);
            bubble.setPadding(dp(16),dp(13),dp(16),dp(13)); bubble.setLineSpacing(dp(3),1); bubble.setBackground(shape(m.outgoing ? BLUE:card,20));
            wrap.addView(bubble,new LinearLayout.LayoutParams(-2,-2));
            if(!m.reactions.isEmpty()) {
                StringBuilder emojis=new StringBuilder(); for(String emoji:m.reactions.values()) emojis.append(emoji).append(" ");
                TextView reaction=text(emojis.toString().trim(),15,ink,false); reaction.setBackground(shape(card,14)); reaction.setPadding(dp(9),dp(4),dp(9),dp(4));
                reaction.setOnClickListener(v->{StringBuilder who=new StringBuilder(); m.reactions.forEach((sender,emoji)->who.append(sender).append(" ").append(emoji).append("\n")); new AlertDialog.Builder(this).setTitle("Reactions").setMessage(who.toString().trim()).setPositiveButton("Done",null).show();});
                wrap.addView(reaction);
            }
            TextView stamp=text(time(m.time)+(m.status.equals("submitted") ? " · Handed to Google Messages":""),10,muted,false);
            stamp.setPadding(dp(6),dp(4),dp(6),0); wrap.addView(stamp);
        }
        if(!preview && !MessageListener.canReply(thread)) {
            TextView hint=text("Quick reply is unavailable for this conversation. Open Google Messages to send a message.",12,muted,false);
            hint.setGravity(Gravity.CENTER); hint.setPadding(dp(8),dp(12),dp(8),dp(12)); chat.addView(hint);
        }
        if(bottom || messages.size()<5) chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
    }
    private void rememberDraft() { if(thread!=null && composer!=null) drafts.put(thread,composer.getText().toString()); }
    private void send() {
        if(preview) { toast("Design preview — no message was sent."); return; }
        String body=composer.getText().toString().trim(); if(body.isEmpty()) return;
        if(MessageListener.reply(this,thread,title,body)) { composer.setText(""); drafts.remove(thread); toast("Reply handed to Google Messages."); }
        else new AlertDialog.Builder(this).setTitle("Continue in Google Messages")
            .setMessage("There is no active quick-reply action for this conversation. Your draft is still here; no message was sent.")
            .setPositiveButton("Open Messages",(d,w)->openCurrentThread()).setNegativeButton("Keep editing",null).show();
    }
    private void openCurrentThread() { if(!preview && MessageListener.openThread(this,thread)) return; openGoogle(false); }
    private void openGoogle(boolean compose) {
        try {
            Intent intent=compose ? new Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:")).setPackage(MessageListener.GOOGLE_MESSAGES):getPackageManager().getLaunchIntentForPackage(MessageListener.GOOGLE_MESSAGES);
            if(intent==null) throw new ActivityNotFoundException(); startActivity(intent);
        } catch(ActivityNotFoundException e) { toast("Install or enable Google Messages first."); }
    }
    private void settings() {
        String[] options={hasAccess() ? "Manage notification access":"Connect Google Messages", "How this companion works", "Get updates with Obtainium", "Clear saved messages", preview ? "Exit design preview":"Preview the design"};
        new AlertDialog.Builder(this).setTitle("Message Center · "+BuildConfig.VERSION_NAME).setItems(options,(d,which)-> {
            switch(which) {
                case 0: connect(); break;
                case 1: new AlertDialog.Builder(this).setTitle("Your messages. Your phone.")
                    .setMessage("Google Messages stays your default texting app and handles SMS, MMS, and RCS. This companion saves message text exposed in its notifications and uses available notification actions for replies.\n\nIt cannot import your complete RCS history, verify delivery or read receipts, start new RCS chats itself, or retrieve every attachment/reaction. Recognized English reaction texts attach only when the quoted message has one exact match; other messages stay visible.\n\nNo internet permission, analytics, or cloud backup. Saved text is kept in this app’s private storage for up to 90 days or 5,000 messages. Opening this view does not mark messages read in Google Messages.\n\nFirst alpha: test notification capture and replies on your phone.")
                    .setPositiveButton("Done",null).show(); break;
                case 2: new AlertDialog.Builder(this).setTitle("Updates through Obtainium")
                    .setMessage("In Obtainium, add:\nhttps://github.com/bags156/message-center\n\nChoose the message-center APK from releases. Updates keep your saved messages. You can also view the release page below.")
                    .setPositiveButton("View releases",(a,b)->openUrl("https://github.com/bags156/message-center/releases"))
                    .setNegativeButton("Done",null).show(); break;
                case 3: new AlertDialog.Builder(this).setTitle("Clear saved messages?")
                    .setMessage("Deletes this companion’s local history. Google Messages is unchanged.")
                    .setPositiveButton("Clear local history",(a,b)->{store.clear(); showInbox();})
                    .setNegativeButton("Cancel",null).show(); break;
                case 4: preview=!preview; query=""; showInbox(); break;
                default: break;
            }
        }).show();
    }
    private void openUrl(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); } catch(ActivityNotFoundException e) { toast("No browser is available."); } }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    private String time(long timestamp) { return DateFormat.getTimeFormat(this).format(new Date(timestamp)); }
    private String initials(String name) {
        String[] words=name.trim().split("\\s+");
        if(words.length==0 || words[0].isEmpty()) return "M";
        String first=words[0].substring(0,words[0].offsetByCodePoints(0,1));
        return (first+(words.length>1 ? words[words.length-1].substring(0,words[words.length-1].offsetByCodePoints(0,1)):"")).toUpperCase(java.util.Locale.ROOT);
    }
    private List<MessageStore.ThreadInfo> previewThreads() {
        List<MessageStore.ThreadInfo> samples=new ArrayList<>();
        String[] names={"Alex Morgan","Weekend plans","Sam Rivera"};
        String[] snippets={"That sounds perfect. See you there!","Let’s make a day of it ☀️","Sent you the address. Coffee at 10?"};
        for(int i=0;i<names.length;i++) {
            if(!(names[i]+" "+snippets[i]).toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) continue;
            MessageStore.ThreadInfo t=new MessageStore.ThreadInfo(); t.id="demo:"+i; t.title=names[i]; t.snippet=snippets[i]; t.time=System.currentTimeMillis()-i*900000L; samples.add(t);
        } return samples;
    }
    private List<MessageStore.Message> previewMessages(String id) {
        List<MessageStore.Message> samples=new ArrayList<>();
        String[] bodies={"Hey! Are we still on for this evening?","Absolutely. How does seven sound?","Loved “Absolutely. How does seven sound?”","That sounds perfect. See you there!"};
        for(int i=0;i<bodies.length;i++) {
            MessageStore.Message m=new MessageStore.Message(); m.id="sample:"+i; m.text=bodies[i]; m.outgoing=i==1;
            m.sender=m.outgoing ? "You":title; m.time=System.currentTimeMillis()-(4-i)*60000L; m.status="observed"; samples.add(m);
        } MessageStore.applyReactions(samples); return samples;
    }
}
