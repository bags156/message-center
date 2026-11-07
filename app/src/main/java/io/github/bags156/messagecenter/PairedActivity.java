package io.github.bags156.messagecenter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Experimental, phone-hosted paired client. Does not read other apps' notifications. */
public final class PairedActivity extends Activity {
    private static final int BLUE=0xff2864f0;
    private static final long LOGIN_POLL_MS=1500;
    private int bg,card,ink,muted,folder=1,limit=300;
    private PairedStore store;private LinearLayout root,items;private TextView statusView,replyView;
    private ScrollView scroll;private EditText composer;private WebView login;
    private String screen="inbox",chatId="",query="",replyId="";
    private final Map<String,String> drafts=new HashMap<>();
    private boolean registered,pairSubmitted,busy;private String shownEmoji="";
    private TextView loginAddress;private int signedInPolls,loginSkips;
    private final android.os.Handler loginTimer=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable loginPoll=this::pollLogin;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(!PairedService.emoji().isEmpty() && !shownEmoji.equals(PairedService.emoji())){destroyLogin();pairing();return;}
        if(screen.equals("pair") && PairedService.ready()){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();return;}
        if(statusView!=null)statusView.setText(PairedService.status(PairedActivity.this));
        if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat"))renderMessages(false);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);store=new PairedStore(this);
        if(state!=null){chatId=state.getString("chat","");replyId=state.getString("reply","");drafts.put(chatId,state.getString("draft",""));}
        String target=getIntent().getStringExtra("chat");if(target!=null&&!target.isEmpty())chatId=target;
        if(!chatId.isEmpty())thread(chatId);else inbox();
        if(new SessionVault(this).exists() && getSharedPreferences("paired",MODE_PRIVATE).getBoolean("enabled",true))PairedService.start(this);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);String id=intent.getStringExtra("chat");if(id!=null&&!id.isEmpty())thread(id);}
    @Override protected void onResume(){super.onResume();if(!registered){registerReceiver(receiver,new IntentFilter(PairedService.CHANGED),Context.RECEIVER_NOT_EXPORTED);registered=true;}
        if(PairedService.ready()&&screen.equals("pair")){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();}
        else if(!PairedService.emoji().isEmpty()&&login==null)pairing();
        else if(statusView!=null)statusView.setText(PairedService.status(this));}
    @Override protected void onPause(){remember();if(registered){unregisterReceiver(receiver);registered=false;}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){remember();out.putString("chat",chatId);out.putString("reply",replyId);out.putString("draft",drafts.getOrDefault(chatId,""));super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(login!=null){destroyLogin();inbox();}else if(!screen.equals("inbox")){remember();inbox();}else super.onBackPressed();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void base(String name){
        screen=name;statusView=null;composer=null;items=null;
        boolean dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        bg=dark?0xff101219:0xfff5f7fc;card=dark?0xff1c202b:Color.WHITE;ink=dark?0xfff5f6fa:0xff171d2c;muted=dark?0xffacb5c8:0xff606c83;
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root=vertical();root.setBackgroundColor(bg);root.setPadding(dp(20),dp(12),dp(20),dp(12));
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(dp(20),bars.top+dp(12),dp(20),bars.bottom+dp(12));return insets;});
        setContentView(root);root.requestApplyInsets();
    }
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void gap(LinearLayout target,int h){target.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h)));}
    private TextView button(String title,boolean primary,Runnable action){TextView b=text(title,15,primary?Color.WHITE:BLUE,true);b.setGravity(Gravity.CENTER);b.setPadding(dp(14),dp(12),dp(14),dp(12));b.setMinHeight(dp(48));b.setBackground(shape(primary?BLUE:card,16));b.setOnClickListener(v->action.run());return b;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextColor(ink);e.setHintTextColor(muted);e.setTextSize(16);e.setPadding(dp(16),dp(12),dp(16),dp(12));e.setBackground(shape(card,18));return e;}
    private void header(String title,Runnable back){LinearLayout r=row();if(back!=null)r.addView(button("‹",false,back));TextView heading=text(title,27,ink,true);heading.setPadding(dp(back==null?0:10),0,dp(10),0);r.addView(heading,new LinearLayout.LayoutParams(0,-2,1));root.addView(r);gap(root,10);}
    private void connection(){statusView=text(PairedService.status(this),13,muted,false);root.addView(statusView);gap(root,12);}
    private void note(LinearLayout parent,String value){TextView t=text(value,14,muted,false);t.setPadding(dp(15),dp(12),dp(15),dp(12));t.setBackground(shape(card,18));parent.addView(t);gap(parent,10);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void error(String value){if(!isFinishing())new AlertDialog.Builder(this).setTitle("Message Center").setMessage(value).setPositiveButton("OK",null).show();}
    private void remember(){if(composer!=null&&!chatId.isEmpty())drafts.put(chatId,composer.getText().toString());}
    private void inbox(){remember();chatId="";replyId="";base("inbox");
        LinearLayout r=row();TextView t=text("Messages",32,ink,true);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));r.addView(button("Settings",false,this::settings));root.addView(r);gap(root,10);connection();
        if(!new SessionVault(this).exists()){
            note(root,"On-device pairing • experimental\n\nConnect directly to your Google Messages account. Google Messages stays installed as your default SMS/RCS app. No extra server is required.");
            root.addView(button("Connect Google Messages",true,this::signIn));gap(root,12);
            note(root,"This replaces the earlier notification companion. Your old notification captures are separate from synced conversations. Full archive coverage and background delivery need testing on your phone.");return;
        }
        LinearLayout controls=row();controls.addView(button("+ New message",true,this::newMessage),new LinearLayout.LayoutParams(0,-2,1));controls.addView(button("Refresh",false,()->loadChats(false)));root.addView(controls);gap(root,12);
        EditText search=input("Search synced conversations");search.setSingleLine(true);search.setText(query);root.addView(search);gap(root,8);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){query=s.toString();renderInbox();}public void afterTextChanged(Editable e){}});
        root.addView(button(folder==1?"Inbox ▾":"Archived ▾",false,()->{folder=folder==1?2:1;inbox();if(PairedService.ready())loadChats(false);}));gap(root,8);
        scroll=new ScrollView(this);items=vertical();scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));renderInbox();
    }
    private void renderInbox(){if(items==null||!screen.equals("inbox"))return;items.removeAllViews();
        List<JSONObject> chats=store.chats(query,folder);
        if(chats.isEmpty())note(items,PairedService.ready()?"No synced conversations here yet. Tap Refresh to load them.":"Your synced chats will appear after the connection is ready.");
        for(JSONObject c:chats){LinearLayout entry=vertical();entry.setPadding(dp(16),dp(16),dp(16),dp(16));entry.setBackground(shape(card,20));
            TextView title=text(PairedProtocol.title(c),18,ink,true);title.setMaxLines(2);entry.addView(title);gap(entry,6);
            TextView preview=text(PairedProtocol.string(c.optJSONObject("latestMessage"),"displayContent"),15,muted,false);preview.setMaxLines(2);entry.addView(preview);
            entry.setOnClickListener(v->thread(c.optString("conversationID")));items.addView(entry);gap(items,8);}
        String cursor=store.page("folder:"+folder,false);if(!cursor.isEmpty())items.addView(button(busy?"Loading…":"Load older conversations",false,()->loadChats(true)));
        String note=store.page("folder:"+folder,true);if(!note.isEmpty())note(items,note);
    }
    private void loadChats(boolean more){if(busy)return;busy=true;int selected=folder;String cursor=more?store.page("folder:"+selected,false):"";
        PairedService.loadChats(cursor,selected,(v,e)->{busy=false;if(!e.isEmpty())error(e);if(screen.equals("inbox"))renderInbox();});}
    private void thread(String id){remember();chatId=id;limit=300;replyId="";base("chat");JSONObject c=store.chat(id);
        header(c==null?"Conversation":PairedProtocol.title(c),this::inbox);connection();
        LinearLayout tools=row();tools.addView(button("Refresh",false,()->loadMessages(false)));tools.addView(button("Older messages",false,()->loadMessages(true)));root.addView(tools);gap(root,8);
        scroll=new ScrollView(this);items=vertical();scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        replyView=text("",13,muted,false);replyView.setVisibility(View.GONE);replyView.setOnClickListener(v->{replyId="";replyView.setVisibility(View.GONE);});root.addView(replyView);gap(root,8);
        LinearLayout compose=row();composer=input("Write a message…");composer.setMaxLines(5);composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setText(drafts.getOrDefault(id,""));
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));compose.addView(button("Send",true,this::send));root.addView(compose);
        getSystemService(NotificationManager.class).cancel("chat:"+id,1);renderMessages(true);if(PairedService.ready())loadMessages(false);
    }
    private void renderMessages(boolean bottom){if(items==null||!screen.equals("chat"))return;
        int y=scroll.getScrollY();boolean atBottom=bottom || scroll.getChildAt(0).getHeight()-scroll.getHeight()-y<dp(80);items.removeAllViews();JSONObject c=store.chat(chatId);
        List<JSONObject> messages=store.messages(chatId,limit);
        if(messages.size()==limit)items.addView(button("Show more saved messages",false,()->{limit+=300;renderMessages(false);}));
        if(messages.isEmpty())note(items,"No messages have synced for this conversation yet. Tap Refresh when connected.");
        for(JSONObject m:messages){String body=PairedProtocol.body(m);if(body.isEmpty())continue;boolean outgoing=PairedProtocol.outgoing(m);
            LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);LinearLayout bubble=vertical();bubble.setPadding(dp(14),dp(11),dp(14),dp(11));bubble.setBackground(shape(outgoing?0xff203c78:card,22));
            if(!outgoing){String sender=PairedProtocol.person(c,m.optString("participantID"),m.optJSONObject("senderParticipant"));bubble.addView(text(sender,12,muted,false));gap(bubble,4);}
            JSONObject reply=m.optJSONObject("replyMessage");String target=PairedProtocol.string(reply,"messageID");
            if(!target.isEmpty()){JSONObject original=store.message(target);TextView quote=text(original==null?"Reply to a message not synced yet":PairedProtocol.body(original),13,muted,false);quote.setMaxLines(3);bubble.addView(quote);gap(bubble,6);}
            TextView content=text(body,17,outgoing?Color.WHITE:ink,false);content.setTextIsSelectable(true);bubble.addView(content);
            String label=DateFormat.getTimeFormat(this).format(new Date(PairedProtocol.time(m)));if(outgoing)label+=" · "+delivery(PairedProtocol.status(m));bubble.addView(text(label,11,outgoing?0xffc5d5f5:muted,false));
            Map<String,List<String>> reactions=PairedProtocol.reactions(m,c);
            if(!reactions.isEmpty()){LinearLayout badges=row();for(Map.Entry<String,List<String>> reaction:reactions.entrySet()){
                TextView badge=button(reaction.getKey()+" "+reaction.getValue().size(),false,()->new AlertDialog.Builder(this).setTitle(reaction.getKey()).setMessage(String.join("\n",reaction.getValue())).setPositiveButton("OK",null).show());badges.addView(badge);}
                bubble.addView(badges);}
            TextView actions=button("Reply / React",false,()->messageActions(m));bubble.addView(actions);
            line.addView(bubble,new LinearLayout.LayoutParams(-2,-2));items.addView(line);gap(items,10);}
        String note=store.page("messages:"+chatId,true);if(!note.isEmpty())note(items,note);
        scroll.post(()->{if(atBottom)scroll.fullScroll(View.FOCUS_DOWN);else scroll.scrollTo(0,y);});
    }
    private static String delivery(String s){return switch(s){case "OUTGOING_DISPLAYED"->"Read";case "OUTGOING_DELIVERED"->"Delivered";case "OUTGOING_COMPLETE"->"Sent";case "OUTGOING_SENDING","OUTGOING_YET_TO_SEND"->"Sending";default->s.contains("FAILED")?"Failed":"Status from Google: "+s;};}
    private void loadMessages(boolean more){if(busy)return;String id=chatId,cursor=more?store.page("messages:"+id,false):"";
        if(more&&cursor.isEmpty()){String note=store.page("messages:"+id,true);toast(note.isEmpty()?"Refresh this conversation first.":note);return;}
        busy=true;PairedService.loadMessages(id,cursor,(v,e)->{busy=false;if(!e.isEmpty())error(e);if(screen.equals("chat")&&chatId.equals(id)){if(more)limit+=100;renderMessages(!more);}});}
    private void send(){if(busy||composer==null)return;String body=composer.getText().toString(),id=chatId,reply=replyId;JSONObject c=store.chat(id);
        if(body.trim().isEmpty())return;if(c==null){error("Refresh this conversation before sending.");return;}
        busy=true;PairedService.execute(client->client.sendText(c.toString(),body,reply),(value,e)->{busy=false;
            if(!e.isEmpty()){error(e);return;}drafts.remove(id);if(screen.equals("chat")&&chatId.equals(id)){composer.setText("");replyId="";replyView.setVisibility(View.GONE);}
            toast("Accepted by Google • waiting for message confirmation");if(screen.equals("chat")&&chatId.equals(id))loadMessages(false);});}
    private void newMessage(){if(!PairedService.ready()){toast("Connect first, then create a conversation.");return;}
        EditText number=input("+15551234567");number.setInputType(InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(this).setTitle("New message").setMessage("Enter the full phone number including + and country code.").setView(number)
            .setNegativeButton("Cancel",null).setPositiveButton("Open conversation",(d,w)->{
                String value=number.getText().toString().replaceAll("[\\s()\\-]","");
                PairedService.execute(c->c.createChat(value),(raw,e)->{if(!e.isEmpty()){error(e);return;}try{JSONObject chat=new JSONObject(raw);store.chat(chat);thread(chat.getString("conversationID"));}catch(Exception ex){error("The new conversation could not be opened.");}});
            }).show();}
    private void messageActions(JSONObject m){String[] options={"Reply","React","Remove my reaction"};new AlertDialog.Builder(this).setItems(options,(d,which)->{
        if(which==0){replyId=m.optString("messageID");replyView.setText("Reply: "+PairedProtocol.body(m)+"  ×");replyView.setVisibility(View.VISIBLE);composer.requestFocus();return;}
        String[] emoji={"❤️","👍","👎","😂","😮","😢","❓","🎉"};
        new AlertDialog.Builder(this).setTitle(which==2?"Remove which reaction?":"React to message").setItems(emoji,(dialog,index)->{
            JSONObject chat=store.chat(chatId);if(chat==null){error("Refresh this conversation first.");return;}
            PairedService.execute(c->{c.react(chat.toString(),m.optString("messageID"),emoji[index],which==2);return "";},(v,e)->{if(!e.isEmpty())error(e);else{toast("Reaction accepted by Google");loadMessages(false);}});
        }).show();}).show();}
    private void settings(){remember();base("settings");header("Settings",this::inbox);connection();ScrollView sc=new ScrollView(this);LinearLayout content=vertical();sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        note(content,"Message Center "+BuildConfig.VERSION_NAME+" • experimental paired client\n\nThe connection runs on this phone and talks directly to Google. Google Messages must remain your default messaging app and have network/background access.");
        content.addView(button("Reconnect",true,()->{PairedService.disconnect(this,false);new android.os.Handler().postDelayed(()->{PairedService.start(this);settings();},300);}));gap(content,8);
        content.addView(button("Sign in / pair again",false,this::signIn));gap(content,8);
        content.addView(button("Allow Message Center notifications",false,()->{if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}));gap(content,8);
        content.addView(button("Background / battery settings",false,()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));gap(content,8);
        note(content,"On ColorOS, allow background activity and auto-launch for Message Center and Google Messages. Check both apps' battery settings. This build keeps an ongoing connection notification.\n\nAfter pairing, test an incoming message with Google Messages notifications off. Message Center uses its own notification channel.");
        note(content,"History loads in pages from Google. Some older messages may not be exposed. Attachments currently appear as labels; opening and sending attachments are not implemented in this test build.");
        content.addView(button("Open Google Messages",false,this::openGoogle));gap(content,8);
        content.addView(button("Updates on GitHub",false,()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/bags156/message-center/releases")))));gap(content,8);
        content.addView(button("Stop background connection",false,()->{PairedService.disconnect(this,false);settings();}));gap(content,8);
        content.addView(button("Forget pairing and synced messages",false,()->new AlertDialog.Builder(this).setTitle("Forget this connection?").setMessage("Removes this app's pairing and synced cache from this phone. Your Google Messages chats stay in Google Messages. Remove Message Center in Google's Device pairing list to revoke it there too.").setNegativeButton("Cancel",null).setPositiveButton("Forget",(d,w)->{PairedService.disconnect(this,true);CookieManager.getInstance().removeAllCookies(null);inbox();}).show()));
    }
    private void signIn(){remember();new AlertDialog.Builder(this).setTitle("Connect on this phone").setMessage("Sign in to the Google account selected in Google Messages → Device pairing. The app keeps the Google session on this phone, encrypted with Android Keystore after pairing, and sends it only to Google.\n\nApprove the matching emoji in Google Messages when asked. A new pairing replaces this app's synced cache. Google may reject sign-in in an embedded browser; this test does not bypass that restriction.")
        .setNegativeButton("Cancel",null).setPositiveButton("Sign in",(d,w)->login()).show();}
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void login(){base("login");pairSubmitted=false;signedInPolls=0;loginSkips=0;header("Connect Google",()->{destroyLogin();inbox();});
        LinearLayout bar=row();loginAddress=text("Google sign-in • accounts.google.com",13,muted,false);loginAddress.setSingleLine(true);loginAddress.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(loginAddress,new LinearLayout.LayoutParams(0,-2,1));bar.addView(button("Continue ›",false,this::continueLogin));root.addView(bar);gap(root,8);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);login=new WebView(this);login.getSettings().setJavaScriptEnabled(true);login.getSettings().setDomStorageEnabled(true);
        login.getSettings().setAllowFileAccess(false);login.getSettings().setAllowContentAccess(false);login.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);login.getSettings().setSafeBrowsingEnabled(true);
        CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(true);cm.setAcceptThirdPartyCookies(login,true);
        // Keep WebView's real user agent. No browser impersonation or SSL bypass.
        login.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){
                // Only top-level pages are restricted; Google's own frames inside a page must load or its buttons stop working.
                if(!request.isForMainFrame())return false;Uri u=request.getUrl();
                if(GoogleLogin.allowed(u.getScheme(),u.getHost()))return false;
                if(loginAddress!=null)loginAddress.setText("Blocked a non-Google page • "+(u.getHost()==null?u.getScheme():u.getHost()));return true;}
            @Override public void onPageFinished(WebView v,String url){Uri u=Uri.parse(url);
                if(loginAddress!=null&&!pairSubmitted)loginAddress.setText("Google sign-in • "+GoogleLogin.describe(u.getHost(),u.getPath()));
                if(GoogleLogin.isConfig(u.getScheme(),u.getHost(),u.getPath()))finishLogin();}
        });root.addView(login,new LinearLayout.LayoutParams(-1,0,1));
        cm.removeAllCookies(done->{if(login!=null){login.loadUrl(GoogleLogin.START);loginTimer.removeCallbacks(loginPoll);loginTimer.postDelayed(loginPoll,LOGIN_POLL_MS);}});
    }
    private boolean signedIn(){return GoogleLogin.signedIn(CookieManager.getInstance().getCookie("https://accounts.google.com"));}
    /** Moves on once Google has signed the account in, so optional prompts or a stalled redirect cannot block pairing. */
    private void pollLogin(){if(login==null||pairSubmitted)return;
        signedInPolls=signedIn()?signedInPolls+1:0;
        Uri u=Uri.parse(String.valueOf(login.getUrl()));boolean onConfig=GoogleLogin.isConfig(u.getScheme(),u.getHost(),u.getPath());
        if(onConfig&&signedInPolls>=GoogleLogin.STABLE_POLLS)finishLogin();
        else if(GoogleLogin.shouldSkipAhead(onConfig,signedInPolls,loginSkips)){loginSkips++;signedInPolls=0;loginAddress.setText("Signed in • skipping optional Google prompts…");login.loadUrl(GoogleLogin.CONFIG);}
        if(login!=null&&!pairSubmitted)loginTimer.postDelayed(loginPoll,LOGIN_POLL_MS);}
    private void continueLogin(){if(login==null||pairSubmitted)return;
        if(!signedIn()){toast("Google hasn't finished signing you in yet. Finish the current step, or go back and start again.");return;}
        loginAddress.setText("Signed in • continuing to pairing…");login.loadUrl(GoogleLogin.CONFIG);}
    private void finishLogin(){if(pairSubmitted||login==null)return;CookieManager cm=CookieManager.getInstance();
        try{JSONObject cookies=new JSONObject();for(String origin:new String[]{"https://accounts.google.com","https://www.google.com","https://messages.google.com"}){
            String raw=cm.getCookie(origin);if(raw==null)continue;for(String pair:raw.split(";")){int eq=pair.indexOf('=');if(eq>0){String key=pair.substring(0,eq).trim();if(GoogleLogin.cookieAllowed(key))cookies.put(key,pair.substring(eq+1));}}}
            if(cookies.has("SID")&&cookies.has("SAPISID")){pairSubmitted=true;loginTimer.removeCallbacks(loginPoll);PairedService.pair(PairedActivity.this,cookies.toString());destroyLogin();pairing();}
        }catch(Exception e){pairSubmitted=true;loginTimer.removeCallbacks(loginPoll);error("Google sign-in could not be completed. Go back and try again.");}
    }
    private void destroyLogin(){loginTimer.removeCallbacks(loginPoll);loginAddress=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);if(login!=null){login.stopLoading();((ViewGroup)login.getParent()).removeView(login);login.destroy();login=null;}}
    private void askNotifications(){if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !getSharedPreferences("paired",MODE_PRIVATE).getBoolean("asked_notifications",false)){getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("asked_notifications",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);}}
    private void pairing(){base("pair");header("Pair this phone",null);connection();String emoji=PairedService.emoji();shownEmoji=emoji;TextView symbol=text(emoji.isEmpty()?"…":emoji,72,ink,false);symbol.setGravity(Gravity.CENTER);root.addView(symbol);gap(root,16);
        note(root,"Open Google Messages → Device pairing and approve the matching emoji. Return here afterward.\n\nIf Google reports no device, check that Device pairing uses the same Google account you just signed into.");root.addView(button("Open Google Messages",true,this::openGoogle));gap(root,8);root.addView(button("Back to inbox",false,this::inbox));}
    private void openGoogle(){Intent intent=getPackageManager().getLaunchIntentForPackage("com.google.android.apps.messaging");if(intent!=null)startActivity(intent);else toast("Google Messages is not installed.");}
    @Override protected void onDestroy(){destroyLogin();super.onDestroy();}
}
