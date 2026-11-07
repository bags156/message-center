package io.github.bags156.messagecenter;

import android.Manifest;
import android.app.Activity;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentValues;
import android.os.Environment;
import android.os.PowerManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.ProgressBar;
import java.util.Date;
import java.util.Locale;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Experimental, phone-hosted paired client. Does not read other apps' notifications. */
public class PairedActivity extends Activity {
    static final String OPEN_CHAT="io.github.bags156.messagecenter.OPEN_CHAT";
    private static final int BLUE=0xff2864f0;
    private static final long LOGIN_POLL_MS=1500;
    private static final String[] REACTIONS={"❤️","👍","👎","😂","😮","😢","❓","🎉"};
    private int bg,card,ink,muted,surface,fab,onFab,danger,folder=1,limit=300;
    private boolean dark;
    private PairedStore store;private ChatSettings chatSettings;
    private FrameLayout frame;private LinearLayout root,items,replyPanel,typingRow;private TextView statusView,replyLabel,replyQuote,typingLabel;private ObjectAnimator typingPulse;
    private ScrollView scroll;private EditText composer;private WebView login;private LinearLayout searchRow;
    private String screen="inbox",chatId="",query="",replyId="",markingRead="";
    private final Map<String,String> drafts=new HashMap<>();
    private final Map<String,Bitmap> photos=new HashMap<>();
    private final Set<String> photosRequested=new HashSet<>();
    private boolean registered,pairSubmitted,busy,resumed,anchorOlder;private String shownEmoji="";
    private long lastInboxRefresh,lastTypingSent;
    /** True while the chat is scrolled to the newest message, so late layout changes keep it there. */
    private boolean pinnedBottom=true;
    private final Map<String,View> messageViews=new HashMap<>();
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable typingTick=this::updateTyping;
    /** True when this screen is shown inside an Android conversation bubble (see BubbleActivity). */
    protected boolean bubble(){return false;}
    private TextView loginAddress;private int signedInPolls,loginSkips;
    private final android.os.Handler loginTimer=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable loginPoll=this::pollLogin;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if("TYPING".equals(i.getStringExtra("kind"))){updateTyping();return;}
        if(!bubble() && !PairedService.emoji().isEmpty() && !shownEmoji.equals(PairedService.emoji())){destroyLogin();pairing();return;}
        if(screen.equals("pair") && PairedService.ready()){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();return;}
        updateStatus();
        if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat")){renderMessages(false);markReadIfAllowed();updateTyping();}
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);store=new PairedStore(this);chatSettings=new ChatSettings(this);outbox=new Outbox(this);
        if(state!=null){String shot=state.getString("capture");if(shot!=null)pendingCapture=new File(shot);chatId=state.getString("chat","");replyId=state.getString("reply","");drafts.put(chatId,state.getString("draft",""));}
        String target=getIntent().getStringExtra("chat");if(target!=null&&!target.isEmpty())chatId=target;
        if(bubble()&&chatId.isEmpty()){finish();return;}
        if(!chatId.isEmpty())thread(chatId);else inbox();
        if(state==null&&!bubble())receiveShare(getIntent());
        if(new SessionVault(this).exists() && getSharedPreferences("paired",MODE_PRIVATE).getBoolean("enabled",true))PairedService.start(this);
    }
    /** A tapped notification (or bubble) delivers its chat here when this screen is already open. */
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(!bubble()&&receiveShare(intent))return;String id=intent.getStringExtra("chat");if(id!=null&&!id.isEmpty()){if(login!=null)destroyLogin();thread(id);}}
    @Override protected void onResume(){super.onResume();resumed=true;if(!registered){registerReceiver(receiver,new IntentFilter(PairedService.CHANGED),Context.RECEIVER_NOT_EXPORTED);registered=true;}
        if(PairedService.ready()&&screen.equals("pair")){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();}
        else if(!bubble()&&!PairedService.emoji().isEmpty()&&login==null)pairing();
        else {updateStatus();PairedService.syncNow();
            // Updates that arrived while this screen was hidden (or a bubble was collapsed) are shown now, then refreshed from Google.
            if(screen.equals("chat")){renderMessages(false);if(PairedService.ready())loadMessages(false);markReadIfAllowed();updateTyping();if(!bubble())PairedService.clearNotification(this,chatId);}
            else if(screen.equals("inbox")){if(batteryCard!=null&&!showBatteryCard()){root.removeView(batteryCard);batteryCard=null;}renderInbox();refreshInbox(false);}}}
    @Override protected void onPause(){resumed=false;remember();ui.removeCallbacks(typingTick);if(recorder!=null)finishRecording(false);if(player!=null&&player.isPlaying()){player.pause();updatePlayLabel();}if(registered){unregisterReceiver(receiver);registered=false;}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){remember();if(pendingCapture!=null)out.putString("capture",pendingCapture.getPath());out.putString("chat",chatId);out.putString("reply",replyId);out.putString("draft",drafts.getOrDefault(chatId,""));super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(screen.equals("details")){thread(chatId);return;}if(bubble()){super.onBackPressed();return;}if(login!=null){destroyLogin();inbox();}else if(screen.equals("inbox")&&folder!=1){folder=1;inbox();}else if(!screen.equals("inbox")){remember();inbox();}else super.onBackPressed();}

    // ---- Shared building blocks ----
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void base(String name){
        if(recorder!=null)cancelRecording();recordBar=null;composeRow=null;sendButton=null;recordClock=null;screen=name;batteryCard=null;statusView=null;composer=null;items=null;scroll=null;searchRow=null;attachStrip=null;attachScroll=null;replyPanel=null;typingRow=null;messageViews.clear();ui.removeCallbacks(typingTick);if(typingPulse!=null){typingPulse.cancel();typingPulse=null;}
        dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        bg=dark?0xff111318:0xfff8f9fc;card=dark?0xff1d2026:Color.WHITE;surface=dark?0xff23252b:0xffe9edf4;ink=dark?0xffe3e2e9:0xff1a1b20;muted=dark?0xffabaeb8:0xff5d5f67;
        fab=dark?0xff3b4766:0xffd3e3fd;onFab=dark?0xffd8e2ff:0xff0b1f3a;danger=dark?0xfff2b8b5:0xffb3261e;
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        frame=new FrameLayout(this);frame.setBackgroundColor(bg);
        root=vertical();root.setPadding(dp(16),dp(8),dp(16),dp(8));frame.addView(root,new FrameLayout.LayoutParams(-1,-1));
        frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(0,bars.top,0,bars.bottom);return insets;});
        setContentView(frame);frame.requestApplyInsets();
    }
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable oval(int color){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);return d;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private void gap(LinearLayout target,int h){target.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h)));}
    private TextView button(String title,boolean primary,Runnable action){TextView b=text(title,15,primary?Color.WHITE:BLUE,true);b.setGravity(Gravity.CENTER);b.setPadding(dp(14),dp(12),dp(14),dp(12));b.setMinHeight(dp(48));b.setBackground(shape(primary?BLUE:card,16));b.setOnClickListener(v->action.run());return b;}
    private ImageView icon(int drawable,int color,String label,Runnable action){
        ImageView i=new ImageView(this);i.setImageResource(drawable);i.setImageTintList(ColorStateList.valueOf(color));i.setPadding(dp(12),dp(12),dp(12),dp(12));
        i.setContentDescription(label);if(action!=null){i.setOnClickListener(v->action.run());i.setBackground(ripple());}
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return i;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextColor(ink);e.setHintTextColor(muted);e.setTextSize(16);e.setPadding(dp(16),dp(12),dp(16),dp(12));e.setBackground(shape(card,18));return e;}
    private void header(String title,Runnable back){LinearLayout r=row();if(back!=null)r.addView(icon(R.drawable.ic_back,ink,"Back",back));TextView heading=text(title,24,ink,false);heading.setPadding(dp(back==null?4:8),0,dp(8),0);r.addView(heading,new LinearLayout.LayoutParams(0,-2,1));root.addView(r);gap(root,10);}
    private void connection(){statusView=text(PairedService.status(this),13,muted,false);statusView.setPadding(dp(4),0,dp(4),dp(8));root.addView(statusView);updateStatus();}
    /** The connection line only shows while something needs attention. */
    private void updateStatus(){if(statusView==null)return;statusView.setText(PairedService.status(this));
        boolean quiet=(screen.equals("inbox")||screen.equals("chat"))&&PairedService.ready();statusView.setVisibility(quiet?View.GONE:View.VISIBLE);}
    private void note(LinearLayout parent,String value){TextView t=text(value,14,muted,false);t.setPadding(dp(15),dp(12),dp(15),dp(12));t.setBackground(shape(card,18));parent.addView(t);gap(parent,10);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void error(String value){if(!isFinishing())new AlertDialog.Builder(this).setTitle("Message Center").setMessage(value).setPositiveButton("OK",null).show();}
    private void remember(){if(composer!=null&&!chatId.isEmpty())drafts.put(chatId,composer.getText().toString());}
    private void hideKeyboard(View v){getSystemService(InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(),0);}

    // ---- Avatars ----
    private int avatarColor(JSONObject chat){
        JSONObject other=PairedProtocol.other(chat);String title=chat==null?"":PairedProtocol.title(chat);
        int fallback=Avatars.fallbackColor(title);
        String hex=other!=null?other.optString("avatarHexColor",""):"";if(hex.isEmpty()&&chat!=null)hex=chat.optString("avatarHexColor","");
        return PairedProtocol.color(hex,fallback);
    }
    private Bitmap photo(JSONObject chat){
        String id=PairedProtocol.participantID(PairedProtocol.other(chat));if(id.isEmpty())return null;
        if(photos.containsKey(id))return photos.get(id);
        File f=Avatars.file(this,id);Bitmap b=f.isFile()?BitmapFactory.decodeFile(f.getPath()):null;if(b!=null)photos.put(id,b);return b;
    }
    private View avatar(JSONObject chat,int size){
        FrameLayout box=new FrameLayout(this);box.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));
        Bitmap b=photo(chat);
        if(b!=null){box.addView(roundImage(b),new FrameLayout.LayoutParams(-1,-1));return box;}
        box.setBackground(oval(avatarColor(chat)));
        if(chat!=null&&PairedProtocol.other(chat)==null&&chat.optBoolean("isGroupChat")){
            ImageView people=new ImageView(this);people.setImageResource(R.drawable.ic_people);people.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            int pad=dp(size/4f);people.setPadding(pad,pad,pad,pad);box.addView(people,new FrameLayout.LayoutParams(-1,-1));return box;}
        TextView letter=text(PairedProtocol.initial(chat==null?"":PairedProtocol.title(chat)),Math.round(size*0.42f),Color.WHITE,false);letter.setGravity(Gravity.CENTER);
        box.addView(letter,new FrameLayout.LayoutParams(-1,-1));return box;
    }
    /** A soft press ripple in the text color (the old list selector flashed yellow on some phones). */
    private android.graphics.drawable.Drawable ripple(){return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf((ink&0x00ffffff)|0x24000000),null,new ColorDrawable(Color.WHITE));}
    private ImageView roundImage(Bitmap b){ImageView img=new ImageView(this);img.setImageBitmap(b);img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setOval(0,0,v.getWidth(),v.getHeight());}});img.setClipToOutline(true);return img;}
    /** One person's photo, or their initial on their Google color. */
    private View personAvatar(JSONObject p,String name,int size){
        FrameLayout box=new FrameLayout(this);box.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));String id=PairedProtocol.participantID(p);Bitmap b=null;
        if(!id.isEmpty()){if(photos.containsKey(id))b=photos.get(id);else{File f=Avatars.file(this,id);b=f.isFile()?BitmapFactory.decodeFile(f.getPath()):null;if(b!=null)photos.put(id,b);}}
        if(b!=null){box.addView(roundImage(b),new FrameLayout.LayoutParams(-1,-1));return box;}
        box.setBackground(oval(PairedProtocol.color(p==null?"":p.optString("avatarHexColor"),Avatars.fallbackColor(name))));
        TextView letter=text(PairedProtocol.initial(name),Math.round(size*0.42f),Color.WHITE,false);letter.setGravity(Gravity.CENTER);box.addView(letter,new FrameLayout.LayoutParams(-1,-1));return box;}
    /** Photos for everyone in a group, for the details screen and reaction lists. */
    private void requestMemberPhotos(JSONObject c,Runnable after){
        if(!PairedService.ready())return;List<String> ids=new ArrayList<>();
        for(JSONObject p:PairedProtocol.members(c)){String id=PairedProtocol.participantID(p);if(id.isEmpty()||photosRequested.contains(id)||Avatars.file(this,id).isFile())continue;photosRequested.add(id);ids.add(id);if(ids.size()==25)break;}
        if(!ids.isEmpty())PairedService.loadPhotos(ids,(v,e)->{if(e.isEmpty())after.run();});}
    /** Requests contact photos once per launch for one-to-one chats that do not have one cached. */
    private void requestPhotos(List<JSONObject> chats){
        if(!PairedService.ready())return;List<String> ids=new ArrayList<>();
        for(JSONObject c:chats){String id=PairedProtocol.participantID(PairedProtocol.other(c));
            if(id.isEmpty()||photosRequested.contains(id)||Avatars.file(this,id).isFile())continue;photosRequested.add(id);ids.add(id);if(ids.size()==25)break;}
        if(ids.isEmpty())return;
        PairedService.loadPhotos(ids,(v,e)->{if(!e.isEmpty())return;if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat"))thread(chatId);});
    }

    // ---- Time labels ----
    private String timeOfDay(long t){return DateFormat.getTimeFormat(this).format(new Date(t));}
    private String inboxStamp(long t){
        switch(InboxOrder.stamp(t,System.currentTimeMillis(),TimeZone.getDefault())){
            case TIME:return timeOfDay(t);
            case WEEKDAY:return new SimpleDateFormat("EEE",Locale.getDefault()).format(new Date(t));
            case MONTH_DAY:return new SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(),"MMMd"),Locale.getDefault()).format(new Date(t));
            default:return DateFormat.getDateFormat(this).format(new Date(t));
        }
    }
    private String separatorStamp(long t){
        InboxOrder.Stamp s=InboxOrder.stamp(t,System.currentTimeMillis(),TimeZone.getDefault());
        return s==InboxOrder.Stamp.TIME?timeOfDay(t):inboxStamp(t)+" • "+timeOfDay(t);
    }

    // ---- Inbox ----
    private void inbox(){if(bubble()){if(!chatId.isEmpty())thread(chatId);else finish();return;}remember();chatId="";replyId="";base("inbox");
        LinearLayout bar=row();TextView title=text(folder==2?"Archived":folder==3?"Blocked & spam":"Messages",26,ink,false);title.setPadding(dp(folder!=1?8:4),0,0,0);
        if(folder!=1)bar.addView(icon(R.drawable.ic_back,ink,"Back",()->{folder=1;inbox();}));
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if(new SessionVault(this).exists()){bar.addView(icon(R.drawable.ic_search,ink,"Search",this::toggleSearch));bar.addView(icon(R.drawable.ic_more,ink,"More options",null));
            View more=bar.getChildAt(bar.getChildCount()-1);more.setBackground(ripple());more.setOnClickListener(this::inboxMenu);}
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));connection();
        if(!new SessionVault(this).exists()){
            note(root,"On-device pairing • experimental\n\nConnect directly to your Google Messages account. Google Messages stays installed as your default SMS/RCS app. No extra server is required.");
            root.addView(button("Connect Google Messages",true,this::signIn));gap(root,12);
            note(root,"This replaces the earlier notification companion. Your old notification captures are separate from synced conversations. Full archive coverage and background delivery need testing on your phone.");return;
        }
        if(hasIncoming())root.addView(shareBanner());
        else if(folder==1&&showBatteryCard())root.addView(batteryCard=batteryBanner());
        searchRow=row();EditText search=input("Search conversations");search.setSingleLine(true);search.setText(query);searchRow.addView(search,new LinearLayout.LayoutParams(-1,-2));
        searchRow.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);root.addView(searchRow);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){query=s.toString();renderInbox();}public void afterTextChanged(Editable e){}});
        scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);items=vertical();items.setPadding(0,dp(4),0,dp(96));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{View child=scroll.getChildAt(0);if(child!=null&&y+scroll.getHeight()>=child.getHeight()-dp(200))loadOlderChats();});
        TextView start=new TextView(this);start.setText("Start chat");start.setTextSize(16);start.setTextColor(onFab);start.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        start.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_chat,0,0,0);start.setCompoundDrawableTintList(ColorStateList.valueOf(onFab));start.setCompoundDrawablePadding(dp(12));
        start.setGravity(Gravity.CENTER_VERTICAL);start.setPadding(dp(20),0,dp(24),0);start.setBackground(shape(fab,18));start.setElevation(dp(3));start.setOnClickListener(v->newMessage());
        FrameLayout.LayoutParams fl=new FrameLayout.LayoutParams(-2,dp(56),Gravity.BOTTOM|Gravity.END);fl.setMargins(0,0,dp(16),dp(16));frame.addView(start,fl);
        renderInbox();refreshInbox(false);
    }
    private void toggleSearch(){if(searchRow==null)return;boolean show=searchRow.getVisibility()!=View.VISIBLE;searchRow.setVisibility(show?View.VISIBLE:View.GONE);
        EditText field=(EditText)searchRow.getChildAt(0);if(show){field.requestFocus();getSystemService(InputMethodManager.class).showSoftInput(field,0);}else{field.setText("");hideKeyboard(field);}}
    private void inboxMenu(View anchor){PopupMenu menu=new PopupMenu(this,anchor);Menu m=menu.getMenu();
        m.add(0,1,0,folder==1?"Archived":"Inbox");if(folder==1)m.add(0,4,1,"Blocked & spam");m.add(0,2,2,"Refresh");m.add(0,3,3,"Settings");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 1->{folder=folder==1?2:1;inbox();}case 4->{folder=3;inbox();}case 2->refreshInbox(true);case 3->settings();default->{}}return true;});menu.show();}
    private void refreshInbox(boolean force){if(!PairedService.ready()||busy)return;long now=System.currentTimeMillis();if(!force&&now-lastInboxRefresh<30_000)return;lastInboxRefresh=now;loadChats(false);}
    private void loadOlderChats(){if(busy||!PairedService.ready())return;if(store.page("folder:"+folder,false).isEmpty())return;loadChats(true);}
    private void renderInbox(){if(items==null||!screen.equals("inbox"))return;items.removeAllViews();
        List<JSONObject> chats=InboxOrder.sort(store.chats(query,folder),chatSettings::pinned);
        if(chats.isEmpty()){TextView empty=text(PairedService.ready()?(query.isEmpty()?"No conversations here yet.":"No matches."):"Your conversations will appear once the connection is ready.",15,muted,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(empty);}
        for(JSONObject c:chats)items.addView(inboxRow(c));
        String note=store.page("folder:"+folder,true);if(!chats.isEmpty()&&store.page("folder:"+folder,false).isEmpty()&&!note.isEmpty()){TextView end=text(note,12,muted,false);end.setGravity(Gravity.CENTER);end.setPadding(dp(16),dp(16),dp(16),dp(8));items.addView(end);}
        requestPhotos(chats);
    }
    private View inboxRow(JSONObject c){
        String id=c.optString("conversationID");boolean unread=chatSettings.unread(c),pinned=chatSettings.pinned(c);String draft=drafts.getOrDefault(id,"").trim();
        LinearLayout entry=row();entry.setPadding(dp(4),dp(12),dp(4),dp(12));entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setBackground(ripple());
        entry.addView(avatar(c,52));
        LinearLayout middle=vertical();middle.setPadding(dp(16),0,dp(8),0);
        TextView title=text(PairedProtocol.title(c),18,ink,unread);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);middle.addView(title);
        String snippet=PairedProtocol.string(c.optJSONObject("latestMessage"),"displayContent");boolean[] mine={PairedProtocol.latestFromMe(c)};
        // Google leaves the preview empty for some events (drafts, some reactions); fall back to the newest synced message.
        if(snippet.isEmpty()){JSONObject last=latestStored(id);if(last!=null)snippet=previewOf(last,c,mine);}
        boolean placeholder=snippet.isEmpty()&&draft.isEmpty();if(placeholder){snippet="Open to load messages";mine[0]=false;}
        if(!draft.isEmpty())snippet="You: "+draft;else if(mine[0]&&!snippet.isEmpty())snippet="You: "+snippet;
        TextView preview=text(snippet,15,unread?ink:muted,unread);preview.setMaxLines(2);preview.setEllipsize(TextUtils.TruncateAt.END);
        if(!draft.isEmpty()||placeholder)preview.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.ITALIC));
        gap(middle,2);middle.addView(preview);entry.addView(middle,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout side=vertical();side.setGravity(Gravity.END|Gravity.TOP);
        long when=PairedProtocol.time(c);TextView stamp=text(!draft.isEmpty()?"Draft":(when>0?inboxStamp(when):""),13,unread?ink:muted,unread);
        if(!draft.isEmpty())stamp.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.ITALIC));
        // The time keeps its own width; a match-parent child here collapsed the column to the pin's width and wrapped the time.
        stamp.setSingleLine(true);LinearLayout.LayoutParams st=new LinearLayout.LayoutParams(-2,-2);st.gravity=Gravity.END;side.addView(stamp,st);
        if(chatSettings.muted(id)){TextView quiet=text("🔕",12,muted,false);quiet.setContentDescription("Muted");LinearLayout.LayoutParams ql=new LinearLayout.LayoutParams(-2,-2);ql.gravity=Gravity.END;ql.topMargin=dp(6);side.addView(quiet,ql);}
        if(pinned){ImageView pin=new ImageView(this);pin.setImageResource(R.drawable.ic_pin);pin.setImageTintList(ColorStateList.valueOf(muted));pin.setContentDescription("Pinned");
            LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(dp(18),dp(18));pl.gravity=Gravity.END;pl.topMargin=dp(10);side.addView(pin,pl);}
        else if(unread){View dot=new View(this);dot.setBackground(oval(ChatTheme.of("default").accent(dark)));LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(dp(10),dp(10));dl.gravity=Gravity.END;dl.topMargin=dp(12);side.addView(dot,dl);}
        entry.addView(side,new LinearLayout.LayoutParams(-2,-1));
        entry.setOnClickListener(v->thread(id));entry.setOnLongClickListener(v->{chatOptions(c);return true;});
        return entry;
    }
    private JSONObject latestStored(String id){List<JSONObject> l=store.messages(id,5);for(int i=l.size()-1;i>=0;i--)if(!PairedProtocol.body(l.get(i)).isEmpty())return l.get(i);return null;}
    /** Inbox preview text for a synced message; mine[0] says whether to prefix "You: ". */
    private String previewOf(JSONObject m,JSONObject c,boolean[] mine){String body=PairedProtocol.body(m);boolean out=PairedProtocol.outgoing(m,c);
        String line=ChatText.reactionLine(out?"You":ChatText.firstName(senderName(m,c)),body);if(line!=null){mine[0]=false;return line;}
        mine[0]=out;String reply=ChatText.replyFallbackBody(body);return reply!=null?reply:body;}
    private String senderName(JSONObject m,JSONObject c){return PairedProtocol.person(c,m.optString("participantID"),m.optJSONObject("senderParticipant"));}
    /** Message text without an iPhone "Replied to a message:" prefix. */
    private static String displayBody(JSONObject m){String body=PairedProtocol.body(m),reply=ChatText.replyFallbackBody(body);return reply!=null?reply:body;}
    /** Pin, theme and read-receipt options for one conversation (long-press in the list). */
    private void chatOptions(JSONObject c){String id=c.optString("conversationID");boolean pinned=chatSettings.pinned(c),receipts=chatSettings.receipts(id),group=PairedProtocol.other(c)==null;
        boolean archived=c.optString("status").contains("ARCHIVED"),blocked=folder==3,unread=chatSettings.unread(c),muted=chatSettings.muted(id);
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        if(!blocked){labels.add(pinned?"Unpin":"Pin to top");actions.add(()->togglePin(c));
            labels.add(unread?"Mark as read":"Mark as unread");actions.add(()->{if(unread){chatSettings.setMarkedUnread(id,false);chatSettings.markSeen(id,PairedProtocol.time(c));renderInbox();}else toggleUnread(id);});
            labels.add(muted?"Unmute notifications":"Mute notifications");actions.add(()->toggleMute(id));
            labels.add(archived?"Unarchive":"Archive");actions.add(()->archive(c,!archived));
            labels.add("Chat theme…");actions.add(()->chooseTheme(id));
            labels.add(receipts?"Turn off read receipts for this chat":"Turn on read receipts for this chat");actions.add(()->toggleReceipts(id));
            labels.add(group?"Group details":"Details");actions.add(()->{chatId=id;details();});
            labels.add("Open as bubble");actions.add(()->openBubble(id));}
        labels.add(blocked?"Unblock":"Block…");actions.add(()->block(c));
        labels.add("Delete conversation…");actions.add(()->deleteChat(c));
        menuSheet(PairedProtocol.title(c),labels,actions);}
    private void openBubble(String id){String problem=PairedService.showBubble(this,id);if(!problem.isEmpty())toast(problem);}
    private void togglePin(JSONObject c){String id=c.optString("conversationID");boolean now=!chatSettings.pinned(c);chatSettings.setPinned(id,now);
        toast(now?"Pinned to the top of Message Center":"Unpinned");if(screen.equals("inbox"))renderInbox();}
    private void chooseTheme(String id){
        LinearLayout list=vertical();Dialog[] dialog=new Dialog[1];TextView heading=text("Chat theme",18,ink,true);heading.setPadding(dp(8),0,dp(8),dp(6));list.addView(heading);
        String current=chatSettings.theme(id).key;
        for(ChatTheme t:ChatTheme.ALL){LinearLayout r=row();r.setPadding(dp(16),dp(10),dp(16),dp(10));r.setBackground(ripple());
            View swatch=new View(this);GradientDrawable sw=oval(t.bubble(dark));sw.setStroke(dp(3),t.accent(dark));swatch.setBackground(sw);r.addView(swatch,new LinearLayout.LayoutParams(dp(32),dp(32)));
            TextView label=text(t.label,16,ink,t.key.equals(current));label.setPadding(dp(16),0,0,0);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if(t.key.equals(current))r.addView(text("✓",18,t.accent(dark),true));
            r.setOnClickListener(v->{chatSettings.setTheme(id,t.key);dialog[0].dismiss();if(screen.equals("chat")&&chatId.equals(id))thread(id);else if(screen.equals("details"))details();});list.addView(r);}
        dialog[0]=sheet(list);}
    private void toggleReceipts(String id){boolean on=!chatSettings.receipts(id);chatSettings.setReceipts(id,on);
        if(!on)toast("Read receipts off for this chat. Opening it here won't mark it read, so it stays unread in Google Messages.");
        else if(chatSettings.phoneReceipts()==0)toast("Read receipts on for this chat, but Google Messages' own \"Send read receipts\" setting is off, so none are sent.");
        else {toast("Read receipts on for this chat.");markReadIfAllowed();}
        if(screen.equals("details"))details();}
    private void loadChats(boolean more){if(busy)return;busy=true;int selected=folder;String cursor=more?store.page("folder:"+selected,false):"";
        PairedService.loadChats(cursor,selected,(v,e)->{busy=false;if(!e.isEmpty()&&!more)toast(e);if(screen.equals("inbox"))renderInbox();});}

    // ---- Conversation ----
    private void thread(String id){remember();chatId=id;limit=300;replyId="";base("chat");JSONObject c=store.chat(id);ChatTheme theme=chatSettings.theme(id);
        root.setPadding(dp(4),dp(4),dp(4),dp(8));
        LinearLayout bar=row();if(!bubble())bar.addView(icon(R.drawable.ic_back,ink,"Back",this::inbox));else bar.setPadding(dp(12),0,0,0);
        LinearLayout who=row();View face=avatar(c,40);who.addView(face);
        TextView name=text(c==null?"Conversation":PairedProtocol.title(c),20,ink,false);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(dp(14),0,dp(8),0);
        who.addView(name,new LinearLayout.LayoutParams(0,-2,1));who.setContentDescription("Conversation details");who.setOnClickListener(v->details());
        bar.addView(who,new LinearLayout.LayoutParams(0,-1,1));
        String number=PairedProtocol.number(PairedProtocol.other(c));
        if(!number.isEmpty())bar.addView(icon(R.drawable.ic_call,ink,"Call",()->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));}catch(Exception e){toast("No phone app is available.");}}));
        ImageView more=icon(R.drawable.ic_more,ink,"More options",null);more.setBackground(ripple());more.setOnClickListener(this::chatMenu);bar.addView(more);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));connection();
        scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);items=vertical();items.setPadding(dp(12),dp(8),dp(12),dp(8));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        // Chats never animate on their own; only an explicit jump (search, reply quote) scrolls smoothly.
        scroll.setSmoothScrollingEnabled(false);pinnedBottom=true;
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{View ch=scroll.getChildAt(0);pinnedBottom=ch!=null&&ch.getHeight()-scroll.getHeight()-y<dp(80);if(y==0&&oy>0)loadOlderMessages();});
        ScrollView chatScroll=scroll;View.OnLayoutChangeListener keepBottom=(v,l,t,r,b,ol,ot,or,ob)->{if(pinnedBottom&&scroll==chatScroll&&(b-t)!=(ob-ot))chatScroll.scrollTo(0,items.getHeight());};
        items.addOnLayoutChangeListener(keepBottom);scroll.addOnLayoutChangeListener(keepBottom);
        typingRow=row();typingRow.setPadding(dp(14),dp(2),dp(14),dp(6));typingRow.setVisibility(View.GONE);
        TextView dots=text("• • •",13,muted,true);dots.setPadding(dp(14),dp(6),dp(14),dp(6));dots.setBackground(shape(surface,16));typingRow.addView(dots);
        typingLabel=text("",13,muted,false);typingLabel.setPadding(dp(10),0,0,0);typingRow.addView(typingLabel);root.addView(typingRow);
        typingPulse=ObjectAnimator.ofFloat(dots,"alpha",1f,0.35f);typingPulse.setDuration(650);typingPulse.setRepeatCount(ValueAnimator.INFINITE);typingPulse.setRepeatMode(ValueAnimator.REVERSE);
        replyPanel=row();replyPanel.setVisibility(View.GONE);replyPanel.setPadding(dp(16),dp(6),dp(4),dp(6));
        View accentBar=new View(this);accentBar.setBackground(shape(theme.accent(dark),2));replyPanel.addView(accentBar,new LinearLayout.LayoutParams(dp(3),dp(38)));
        LinearLayout replyTexts=vertical();replyTexts.setPadding(dp(10),0,dp(8),0);replyLabel=text("",13,theme.accent(dark),true);replyQuote=text("",14,muted,false);
        replyQuote.setSingleLine(true);replyQuote.setEllipsize(TextUtils.TruncateAt.END);replyTexts.addView(replyLabel);replyTexts.addView(replyQuote);
        replyPanel.addView(replyTexts,new LinearLayout.LayoutParams(0,-2,1));replyPanel.addView(icon(R.drawable.ic_close,muted,"Cancel reply",this::cancelReply));root.addView(replyPanel);
        if(!id.equals(outgoingChat)){for(Outgoing o:outgoing)if(!o.sending)o.file.delete();outgoing.removeIf(o->!o.sending);outgoingChat=id;}
        attachStrip=row();attachScroll=new HorizontalScrollView(this);attachScroll.setHorizontalScrollBarEnabled(false);attachScroll.setPadding(dp(16),dp(4),dp(16),dp(6));attachScroll.addView(attachStrip);root.addView(attachScroll);
        recordBar=row();recordBar.setPadding(dp(16),dp(8),dp(8),dp(4));recordBar.setVisibility(View.GONE);recordClock=text("● Recording",16,danger,true);recordBar.addView(recordClock,new LinearLayout.LayoutParams(0,-2,1));
        TextView cancelRec=text("Cancel",16,muted,true);cancelRec.setPadding(dp(12),dp(10),dp(12),dp(10));cancelRec.setOnClickListener(v->cancelRecording());recordBar.addView(cancelRec);
        TextView sendRec=text("Send",16,theme.onAccent(dark),true);sendRec.setPadding(dp(18),dp(10),dp(18),dp(10));sendRec.setBackground(shape(theme.accent(dark),20));sendRec.setOnClickListener(v->finishRecording(true));recordBar.addView(sendRec);root.addView(recordBar);
        LinearLayout compose=row();compose.setPadding(dp(4),dp(4),dp(8),0);composeRow=compose;
        compose.addView(icon(R.drawable.ic_add,ink,"Attach photos, videos, or files",this::attach));
        composer=new EditText(this);composer.setHint(PairedProtocol.rcs(c)?"RCS message":"Text message");composer.setTextColor(ink);composer.setHintTextColor(muted);composer.setTextSize(17);
        composer.setBackground(shape(surface,28));composer.setPadding(dp(22),dp(14),dp(22),dp(14));composer.setMaxLines(5);
        composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setText(drafts.getOrDefault(id,""));
        composer.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){maybeSendTyping(s);updateComposeButton();}public void afterTextChanged(Editable e){}});
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        sendButton=new ImageView(this);sendButton.setImageResource(R.drawable.ic_send);sendButton.setImageTintList(ColorStateList.valueOf(theme.onAccent(dark)));sendButton.setBackground(oval(theme.accent(dark)));
        sendButton.setPadding(dp(14),dp(14),dp(14),dp(14));sendButton.setContentDescription("Send");sendButton.setOnClickListener(v->sendOrRecord());
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(dp(52),dp(52));sl.setMarginStart(dp(8));compose.addView(sendButton,sl);root.addView(compose);renderAttachments();updateComposeButton();applyIncoming();
        chatSettings.setMarkedUnread(id,false);if(!bubble())PairedService.clearNotification(this,id);renderMessages(true);if(PairedService.ready())loadMessages(false);markReadIfAllowed();updateTyping();
        if(c!=null)requestPhotos(java.util.Collections.singletonList(c));
    }
    private void chatMenu(View anchor){JSONObject c=store.chat(chatId);if(c==null)return;PopupMenu menu=new PopupMenu(this,anchor);Menu m=menu.getMenu();
        m.add(0,1,0,chatSettings.pinned(c)?"Unpin":"Pin to top");m.add(0,2,1,"Chat theme");
        m.add(0,3,2,"Read receipts for this chat").setCheckable(true).setChecked(chatSettings.receipts(chatId));
        m.add(0,6,0,PairedProtocol.other(c)==null?"Group details":"Details");m.add(0,9,0,"Search in chat");m.add(0,10,4,chatSettings.muted(chatId)?"Unmute notifications":"Mute notifications");
        boolean archived=c.optString("status").contains("ARCHIVED"),blocked=c.optString("status").equals("BLOCKED_FOLDER")||c.optString("status").equals("SPAM_FOLDER");
        if(!bubble()){m.add(0,11,8,archived?"Unarchive":"Archive");m.add(0,12,9,blocked?"Unblock":"Block…");m.add(0,13,10,"Delete conversation…");}
        if(bubble())m.add(0,8,5,"Open in Message Center");else m.add(0,7,5,"Open as bubble");
        m.add(0,4,6,"Refresh");m.add(0,5,7,"Load older messages");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 1->togglePin(c);case 2->chooseTheme(chatId);case 3->toggleReceipts(chatId);case 4->loadMessages(false);case 5->loadOlderMessages();
            case 6->details();case 7->openBubble(chatId);case 8->startActivity(new Intent(this,PairedActivity.class).setAction(OPEN_CHAT).putExtra("chat",chatId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP));
            case 9->searchChat();case 10->toggleMute(chatId);case 11->archive(c,!archived);case 12->block(c);case 13->deleteChat(c);default->{}}return true;});menu.show();}
    private void renderMessages(boolean bottom){if(items==null||!screen.equals("chat"))return;
        int y=scroll.getScrollY();View content=scroll.getChildAt(0);int before=content.getHeight();
        boolean atBottom=bottom||before-scroll.getHeight()-y<dp(80);items.removeAllViews();
        JSONObject c=store.chat(chatId);ChatTheme theme=chatSettings.theme(chatId);boolean group=c!=null&&PairedProtocol.other(c)==null;
        List<JSONObject> messages=new ArrayList<>();for(JSONObject m:store.messages(chatId,limit))if(!PairedProtocol.body(m).isEmpty())messages.add(m);
        if(messages.size()>=limit-5)items.addView(button("Show more saved messages",false,()->{limit+=300;renderMessages(false);}));
        if(messages.isEmpty()){TextView empty=text(PairedService.ready()?"No messages have synced for this conversation yet.":"Messages will appear once the connection is ready.",14,muted,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(empty);}
        else if(store.page("messages:"+chatId,false).isEmpty()&&!store.page("messages:"+chatId,true).isEmpty()){TextView end=text(store.page("messages:"+chatId,true),12,muted,false);end.setGravity(Gravity.CENTER);end.setPadding(dp(16),dp(8),dp(16),dp(8));items.addView(end);}
        int n=messages.size();boolean[] out=new boolean[n],replyTo=new boolean[n],reacted=new boolean[n];String[] who=new String[n],line=new String[n];long[] at=new long[n];
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);out[i]=PairedProtocol.outgoing(m,c);who[i]=out[i]?"":m.optString("participantID");at[i]=PairedProtocol.time(m);
            line[i]=ChatText.reactionLine(out[i]?"You":ChatText.firstName(senderName(m,c)),PairedProtocol.body(m));
            replyTo[i]=!PairedProtocol.string(m.optJSONObject("replyMessage"),"messageID").isEmpty()||ChatText.replyFallbackBody(PairedProtocol.body(m))!=null;
            reacted[i]=!PairedProtocol.reactors(m).isEmpty();}
        int lastOut=-1;for(int i=n-1;i>=0;i--)if(out[i]&&line[i]==null){lastOut=i;break;}
        TimeZone zone=TimeZone.getDefault();
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);
            boolean sep=InboxOrder.separator(i==0?0:at[i-1],at[i],zone);
            if(sep){TextView s=text(separatorStamp(at[i]),13,muted,false);s.setGravity(Gravity.CENTER);s.setPadding(0,dp(i==0?8:20),0,dp(10));items.addView(s,new LinearLayout.LayoutParams(-1,-2));}
            // iPhone reactions sent as text ("Loved an image") become one quiet line instead of a bubble.
            if(line[i]!=null){if(!sep&&i>0)gap(items,8);TextView t=text(line[i],13,muted,false);t.setGravity(out[i]?Gravity.END:Gravity.START);t.setPadding(dp(14),dp(2),dp(14),dp(2));items.addView(t,new LinearLayout.LayoutParams(-1,-2));continue;}
            boolean joinPrev=!sep&&i>0&&line[i-1]==null&&!replyTo[i]&&!reacted[i-1]&&InboxOrder.grouped(out[i-1],who[i-1],at[i-1],out[i],who[i],at[i]);
            boolean joinNext=i+1<n&&line[i+1]==null&&!replyTo[i+1]&&!reacted[i]&&!InboxOrder.separator(at[i],at[i+1],zone)&&InboxOrder.grouped(out[i],who[i],at[i],out[i+1],who[i+1],at[i+1]);
            if(!sep&&i>0)gap(items,joinPrev?3:12);
            if(group&&!out[i]&&!joinPrev&&!replyTo[i]){TextView sender=text(senderName(m,c),12,muted,false);sender.setPadding(dp(14),0,0,dp(4));items.addView(sender);}
            View b=bubble(m,c,theme,out[i],joinPrev,joinNext);items.addView(b);messageViews.put(m.optString("messageID"),b);
            List<String[]> reactors=PairedProtocol.reactors(m);
            if(!reactors.isEmpty()){LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(-1,-2);rl.topMargin=-dp(7);items.addView(reactionPill(m,c,reactors,out[i]),rl);}
            if(i==lastOut||(out[i]&&PairedProtocol.failed(m)))items.addView(statusRow(m,theme));
        }
        renderOutbox(theme);
        // Position before the next frame is drawn, so the chat appears already at the right place instead of sliding there.
        ScrollView sv=scroll;android.view.ViewTreeObserver.OnPreDrawListener[] once=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        once[0]=()->{sv.getViewTreeObserver().removeOnPreDrawListener(once[0]);if(scroll!=sv||sv.getChildAt(0)==null)return true;
            if(anchorOlder){anchorOlder=false;sv.scrollTo(0,y+Math.max(0,sv.getChildAt(0).getHeight()-before));}
            else if(atBottom){sv.scrollTo(0,sv.getChildAt(0).getHeight());pinnedBottom=true;}else sv.scrollTo(0,y);return true;};
        sv.getViewTreeObserver().addOnPreDrawListener(once[0]);
    }
    private View bubble(JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,boolean joinPrev,boolean joinNext){
        LinearLayout wrap=vertical();String body=PairedProtocol.text(m),fallback=ChatText.replyFallbackBody(body);List<JSONObject> media=PairedProtocol.media(m);
        String target=PairedProtocol.string(m.optJSONObject("replyMessage"),"messageID");
        if(!target.isEmpty()||fallback!=null)addReplyContext(wrap,m,c,theme,outgoing,target);
        for(int k=0;k<media.size();k++){if(k>0)gap(wrap,4);wrap.addView(mediaView(m,media.get(k),outgoing,theme),new LinearLayout.LayoutParams(-1,-2));}
        if(!media.isEmpty()&&(fallback!=null?fallback:body).isEmpty())return wrap;
        if(!media.isEmpty())gap(wrap,4);
        LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);
        LinearLayout b=vertical();b.setPadding(dp(16),dp(11),dp(16),dp(11));
        float big=dp(22),small=dp(5);float top=joinPrev?small:big,bottomR=joinNext?small:big;
        GradientDrawable shape=new GradientDrawable();shape.setColor(outgoing?theme.bubble(dark):surface);
        shape.setCornerRadii(outgoing?new float[]{big,big,top,top,bottomR,bottomR,big,big}:new float[]{top,top,big,big,big,big,bottomR,bottomR});b.setBackground(shape);
        TextView text=text(fallback!=null?fallback:body,17,outgoing?theme.onBubble(dark):ink,false);text.setLineSpacing(0,1.1f);text.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.addView(text);
        if(Linkify.addLinks(text,Linkify.WEB_URLS|Linkify.EMAIL_ADDRESSES|Linkify.PHONE_NUMBERS)){text.setLinkTextColor(outgoing?theme.onBubble(dark):ink);text.setHighlightColor(((outgoing?theme.onBubble(dark):ink)&0x00ffffff)|0x26000000);text.setMovementMethod(LinkMovementMethod.getInstance());text.setOnLongClickListener(v->{messageSheet(m);return true;});}
        b.setOnLongClickListener(v->{messageSheet(m);return true;});b.setOnClickListener(v->{});
        line.addView(b,new LinearLayout.LayoutParams(-2,-2));wrap.addView(line,new LinearLayout.LayoutParams(-1,-2));
        String url=linkPreviewsOn()?ChatText.firstUrl(fallback!=null?fallback:body):"";if(!url.isEmpty()){gap(wrap,4);wrap.addView(linkCard(url,outgoing),new LinearLayout.LayoutParams(-1,-2));}
        return wrap;
    }
    /**
     * Like iPhone: who replied to whom, the original as a faded bubble above, and a thread
     * line down to the reply. Tapping the original scrolls to it.
     */
    private void addReplyContext(LinearLayout wrap,JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,String target){
        JSONObject original=target.isEmpty()?null:store.message(target);boolean originalOut=original!=null&&PairedProtocol.outgoing(original,c);
        String from=outgoing?"You":ChatText.firstName(senderName(m,c));
        String to=original==null?"":" to "+(originalOut?(outgoing?"yourself":"you"):ChatText.firstName(senderName(original,c)));
        TextView label=text("↩ "+from+" replied"+to,12,muted,false);label.setGravity(outgoing?Gravity.END:Gravity.START);label.setPadding(dp(14),0,dp(14),dp(4));
        wrap.addView(label,new LinearLayout.LayoutParams(-1,-2));
        String quote=original!=null?displayBody(original):(target.isEmpty()?"Original message not included":"Original message not loaded yet");
        LinearLayout quoteLine=row();quoteLine.setGravity(outgoing?Gravity.END:Gravity.START);
        TextView q=text(quote,14,originalOut?theme.onBubble(dark):ink,false);q.setMaxLines(2);q.setEllipsize(TextUtils.TruncateAt.END);q.setPadding(dp(14),dp(8),dp(14),dp(8));
        q.setBackground(shape(originalOut?theme.bubble(dark):surface,18));q.setAlpha(original==null?0.45f:0.6f);q.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.62f));
        if(original!=null){q.setContentDescription("Original message: "+quote+". Tap to show it.");q.setOnClickListener(v->jumpTo(target));}
        LinearLayout.LayoutParams ql=new LinearLayout.LayoutParams(-2,-2);if(outgoing)ql.setMarginEnd(dp(22));else ql.setMarginStart(dp(22));quoteLine.addView(q,ql);
        wrap.addView(quoteLine,new LinearLayout.LayoutParams(-1,-2));
        View thread=new View(this){final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);{paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(muted);paint.setAlpha(120);}
            @Override protected void onDraw(Canvas canvas){float x=outgoing?getWidth()-dp(36):dp(36);canvas.drawLine(x,dp(1),x,getHeight()-dp(1),paint);}};
        wrap.addView(thread,new LinearLayout.LayoutParams(-1,dp(10)));
    }
    private void jumpTo(String id){View v=messageViews.get(id);
        if(v==null||scroll==null){toast("That message isn't loaded here. Scroll up to load older messages.");return;}
        scroll.smoothScrollTo(0,Math.max(0,v.getTop()-dp(96)));
        v.animate().alpha(0.35f).setDuration(180).withEndAction(()->v.animate().alpha(1f).setDuration(420).start()).start();}
    /** One pill per message: up to three emoji and how many people reacted. Tap to see who. */
    private View reactionPill(JSONObject m,JSONObject c,List<String[]> reactors,boolean outgoing){
        LinearLayout r=row();r.setGravity(outgoing?Gravity.END:Gravity.START);r.setPadding(dp(14),0,dp(14),0);
        TextView chip=text(ChatText.reactionSummary(reactors),14,ink,false);chip.setPadding(dp(9),dp(3),dp(9),dp(4));
        GradientDrawable d=shape(surface,14);d.setStroke(dp(2),bg);chip.setBackground(d);chip.setContentDescription(reactors.size()+(reactors.size()==1?" reaction":" reactions")+". Tap to see who reacted.");
        chip.setOnClickListener(v->reactionSheet(m,c));r.addView(chip);return r;
    }
    /** A sheet that slides up from the bottom of the screen. */
    private Dialog sheet(View content){
        Dialog d=new Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box=vertical();box.setPadding(dp(20),dp(10),dp(20),dp(28));GradientDrawable panel=new GradientDrawable();panel.setColor(card);float r=dp(28);panel.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});box.setBackground(panel);
        View handle=new View(this);handle.setBackground(shape(muted,2));handle.setAlpha(0.45f);LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(dp(32),dp(4));hl.gravity=Gravity.CENTER_HORIZONTAL;hl.bottomMargin=dp(14);box.addView(handle,hl);
        box.addView(content,new LinearLayout.LayoutParams(-1,-2));d.setContentView(box);d.show();
        Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setLayout(-1,-2);w.setGravity(Gravity.BOTTOM);}
        return d;
    }
    /** Everyone who reacted, with photo or initial, filterable by emoji. */
    private void reactionSheet(JSONObject m,JSONObject c){
        List<String[]> all=PairedProtocol.reactors(m);if(all.isEmpty())return;
        LinearLayout content=vertical();content.addView(text("Reactions",20,ink,true));gap(content,12);
        Map<String,Integer> counts=new LinkedHashMap<>();for(String[] r:all)counts.merge(r[0],1,Integer::sum);
        LinearLayout chips=row();HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);hs.addView(chips);
        if(counts.size()>1){content.addView(hs);gap(content,8);}
        LinearLayout list=vertical();ScrollView ls=new ScrollView(this);ls.addView(list);content.addView(ls,new LinearLayout.LayoutParams(-1,-2));
        String[] filter={""};List<TextView> chipViews=new ArrayList<>();Runnable[] fill=new Runnable[1];
        fill[0]=()->{for(TextView t:chipViews){boolean on=filter[0].equals(t.getTag());t.setBackground(shape(on?fab:surface,16));t.setTextColor(on?onFab:ink);}
            list.removeAllViews();
            for(String[] r:all){if(!filter[0].isEmpty()&&!filter[0].equals(r[0]))continue;JSONObject p=PairedProtocol.participant(c,r[1]);String name=PairedProtocol.person(c,r[1],null);
                LinearLayout row=row();row.setPadding(0,dp(10),0,dp(10));row.addView(personAvatar(p,name,40));
                TextView n=text(name,16,ink,false);n.setPadding(dp(16),0,dp(8),0);n.setSingleLine(true);n.setEllipsize(TextUtils.TruncateAt.END);row.addView(n,new LinearLayout.LayoutParams(0,-2,1));
                row.addView(text(r[0],24,ink,false));list.addView(row);}};
        List<String> keys=new ArrayList<>();keys.add("");keys.addAll(counts.keySet());
        for(String key:keys){TextView chip=text(key.isEmpty()?"All  "+all.size():key+"  "+counts.get(key),15,ink,true);chip.setTag(key);chip.setPadding(dp(14),dp(8),dp(14),dp(8));
            chip.setOnClickListener(v->{filter[0]=key;fill[0].run();});LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-2,-2);cl.setMarginEnd(dp(8));chips.addView(chip,cl);chipViews.add(chip);}
        fill[0].run();sheet(content);
        requestMemberPhotos(c,()->{});
    }
    private View statusRow(JSONObject m,ChatTheme theme){
        LinearLayout r=row();r.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);r.setPadding(0,dp(4),dp(6),0);String s=PairedProtocol.status(m);
        if(PairedProtocol.failed(m)){TextView t=text("Not sent • Tap to retry",12,danger,true);t.setOnClickListener(v->retryFailed(m));r.addView(t);return r;}
        switch(s){
            case "OUTGOING_DISPLAYED","OUTGOING_DELIVERED"->{ImageView ticks=new ImageView(this);ticks.setImageResource(R.drawable.ic_ticks);
                boolean read=s.equals("OUTGOING_DISPLAYED");ticks.setImageTintList(ColorStateList.valueOf(read?theme.onAccent(dark):muted));if(read)ticks.setBackground(oval(theme.accent(dark)));
                ticks.setPadding(dp(3),dp(3),dp(3),dp(3));ticks.setContentDescription(read?"Read":"Delivered");r.addView(ticks,new LinearLayout.LayoutParams(dp(22),dp(22)));}
            case "OUTGOING_COMPLETE"->r.addView(text("Sent",12,muted,false));
            case "OUTGOING_SENDING","OUTGOING_YET_TO_SEND","OUTGOING_RESENDING","OUTGOING_VALIDATING","OUTGOING_AWAITING_RETRY"->r.addView(text("Sending…",12,muted,false));
            default->{}
        }
        return r;
    }
    private void loadOlderMessages(){if(busy||!screen.equals("chat"))return;String cursor=store.page("messages:"+chatId,false);if(cursor.isEmpty())return;anchorOlder=true;loadMessages(true);}
    private void loadMessages(boolean more){if(busy)return;String id=chatId,cursor=more?store.page("messages:"+id,false):"";
        if(more&&cursor.isEmpty()){anchorOlder=false;String note=store.page("messages:"+id,true);toast(note.isEmpty()?"Refresh this conversation first.":note);return;}
        busy=true;PairedService.loadMessages(id,cursor,(v,e)->{busy=false;if(!e.isEmpty()){anchorOlder=false;toast(e);}if(screen.equals("chat")&&chatId.equals(id)){if(more)limit+=100;renderMessages(!more);markReadIfAllowed();}});}
    /**
     * Viewing a chat here records it as seen. If read receipts are on for this
     * chat, it is also marked read on the phone, and Google Messages sends RCS
     * read receipts if its own setting allows them.
     */
    private void markReadIfAllowed(){if(!resumed||!screen.equals("chat")||chatId.isEmpty())return;String id=chatId;
        JSONObject c=store.chat(id),latest=null;for(JSONObject m:store.messages(id,50))if(!PairedProtocol.outgoing(m,c))latest=m;
        long seenAt=Math.max(c==null?0:PairedProtocol.time(c),latest==null?0:PairedProtocol.time(latest));if(seenAt>0)chatSettings.markSeen(id,seenAt);
        if(latest==null||!chatSettings.receipts(id)||!PairedService.ready())return;String mid=latest.optString("messageID");
        if(mid.isEmpty()||mid.equals(chatSettings.markedRead(id))||mid.equals(markingRead))return;markingRead=mid;
        PairedService.markRead(id,mid,(v,e)->{markingRead="";if(e.isEmpty())chatSettings.setMarkedRead(id,mid);});}
    private void send(){if(busy||composer==null)return;String body=composer.getText().toString(),id=chatId,reply=replyId;JSONObject c=store.chat(id);
        boolean files=false;for(Outgoing o:outgoing)if(!o.sending)files=true;
        if(body.trim().isEmpty()&&!files)return;if(c==null){error("Refresh this conversation before sending.");return;}
        if(files){sendAttachments(c,body.trim(),reply);return;}
        busy=true;PairedService.sendOrQueue(this,id,body,reply,(value,e)->{busy=false;
            if(!e.isEmpty()){error(e);return;}if("queued".equals(value))toast("Saved. It will send when Message Center reconnects.");drafts.remove(id);if(screen.equals("chat")&&chatId.equals(id)){composer.setText("");cancelReply();}
            if(screen.equals("chat")&&chatId.equals(id))loadMessages(false);});}
    private void startChat(String typed){if(!PairedService.ready()){toast("Connect first, then start a chat.");return;}String n=ChatText.normalizeNumber(typed),value=n.isEmpty()?typed:n;
        PairedService.execute(c->c.createChat(value),(raw,e)->{if(!e.isEmpty()){error(e);return;}try{JSONObject chat=new JSONObject(raw);store.chat(chat);
            if(bubble()){startActivity(new Intent(this,PairedActivity.class).setAction(OPEN_CHAT).putExtra("chat",chat.getString("conversationID")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP));return;}
            thread(chat.getString("conversationID"));}catch(Exception ex){error("The new conversation could not be opened.");}});}
    /** Long-press a message: react (tap your current reaction to remove it), reply, copy, or see reactions. */
    private void messageSheet(JSONObject m){JSONObject c=store.chat(chatId);LinearLayout content=vertical();Dialog[] d=new Dialog[1];
        List<String[]> reactors=PairedProtocol.reactors(m);String mine="";for(String[] r:reactors)if(PairedProtocol.isMe(c,r[1]))mine=r[0];
        List<String> choices=new ArrayList<>(java.util.Arrays.asList(REACTIONS));if(!mine.isEmpty()&&!choices.contains(mine)){choices.add(0,mine);choices.remove(choices.size()-1);}
        LinearLayout emojis=row();String current=mine;
        for(String e:choices){TextView t=text(e,26,ink,false);t.setGravity(Gravity.CENTER);boolean selected=e.equals(current);if(selected)t.setBackground(oval(fab));
            t.setContentDescription(selected?"Remove your "+e+" reaction":"React with "+e);
            t.setOnClickListener(v->{d[0].dismiss();react(m,e,selected?"remove":current.isEmpty()?"add":"switch");});emojis.addView(t,new LinearLayout.LayoutParams(0,dp(50),1));}
        content.addView(emojis);gap(content,10);
        sheetAction(content,"Reply",()->{d[0].dismiss();startReply(m);});
        String typed=PairedProtocol.text(m),plain=ChatText.replyFallbackBody(typed)!=null?ChatText.replyFallbackBody(typed):typed;
        if(!plain.isEmpty())sheetAction(content,"Copy text",()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Message",plain));});
        for(JSONObject media:PairedProtocol.media(m)){String transcript=Transcriber.saved(this,PairedProtocol.string(media,"mediaID"));
            if(transcript!=null)sheetAction(content,"Copy transcript",()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Transcript",transcript));});
            String kind=PairedProtocol.mediaKind(media),what=kind.equals("image")?"photo":kind.equals("video")?"video":kind.equals("audio")?"audio":"file";
            sheetAction(content,"Save "+what+" to phone",()->{d[0].dismiss();withFile(media,"Downloading…",file->saveToPhone(file,media));});
            sheetAction(content,"Share "+what,()->{d[0].dismiss();withFile(media,"Downloading…",file->share(file,media));});}
        if(!reactors.isEmpty())sheetAction(content,"See who reacted",()->{d[0].dismiss();reactionSheet(m,c);});
        sheetAction(content,"Delete message…",()->{d[0].dismiss();deleteMessage(m);});
        d[0]=sheet(content);}
    private TextView sheetAction(LinearLayout parent,String label,Runnable action){TextView t=text(label,17,ink,false);t.setPadding(dp(8),dp(14),dp(8),dp(14));
        t.setBackground(ripple());t.setOnClickListener(v->action.run());parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    /** Choices in a panel from the bottom of the screen, in place of Android's plain list dialog. Delete and block are shown in red. */
    private void menuSheet(String title,List<String> labels,List<Runnable> actions){LinearLayout content=vertical();Dialog[] d=new Dialog[1];
        if(title!=null&&!title.isEmpty()){TextView t=text(title,18,ink,true);t.setPadding(dp(8),0,dp(8),dp(6));t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);content.addView(t);}
        LinearLayout list=vertical();ScrollView sc=new ScrollView(this);sc.setVerticalScrollBarEnabled(false);sc.addView(list);content.addView(sc,new LinearLayout.LayoutParams(-1,-2));
        for(int i=0;i<labels.size();i++){Runnable a=actions.get(i);String label=labels.get(i);TextView row=sheetAction(list,label,()->{d[0].dismiss();a.run();});
            if(label.startsWith("Delete")||label.startsWith("Block"))row.setTextColor(danger);}
        d[0]=sheet(content);}
    private void react(JSONObject m,String emoji,String action){JSONObject chat=store.chat(chatId);if(chat==null){error("Refresh this conversation first.");return;}
        PairedService.execute(cl->{cl.react(chat.toString(),m.optString("messageID"),emoji,action);return "";},(v,e)->{if(!e.isEmpty())error(e);else loadMessages(false);});}
    private void startReply(JSONObject m){if(replyPanel==null||composer==null)return;JSONObject c=store.chat(chatId);replyId=m.optString("messageID");
        replyLabel.setText(PairedProtocol.outgoing(m,c)?"Replying to yourself":"Replying to "+ChatText.firstName(senderName(m,c)));replyQuote.setText(displayBody(m));
        replyPanel.setVisibility(View.VISIBLE);composer.requestFocus();getSystemService(InputMethodManager.class).showSoftInput(composer,0);}
    private void cancelReply(){replyId="";if(replyPanel!=null)replyPanel.setVisibility(View.GONE);}
    /** Shows who is typing, and hides the row again once Google stops reporting it. */
    private void updateTyping(){ui.removeCallbacks(typingTick);if(typingRow==null||!screen.equals("chat"))return;
        List<String> names=PairedProtocol.typingNames(store.chat(chatId),PairedService.typing(chatId));
        if(names.isEmpty()){typingRow.setVisibility(View.GONE);if(typingPulse!=null)typingPulse.cancel();return;}
        typingLabel.setText(ChatText.typingLabel(names));typingRow.setVisibility(View.VISIBLE);if(typingPulse!=null&&!typingPulse.isRunning())typingPulse.start();
        ui.postDelayed(typingTick,1000);}
    /** Lets the other side see you typing (RCS chats only), at most every 8 seconds; can be turned off in Settings. */
    private void maybeSendTyping(CharSequence s){if(s.length()==0||!getSharedPreferences("paired",MODE_PRIVATE).getBoolean("send_typing",true))return;
        long now=System.currentTimeMillis();if(now-lastTypingSent<8000)return;JSONObject c=store.chat(chatId);if(c==null||!PairedProtocol.rcs(c))return;
        lastTypingSent=now;PairedService.sendTyping(c.toString());}
    /** Everyone in the conversation, plus this chat's settings. */
    private void details(){JSONObject c=store.chat(chatId);if(c==null)return;remember();String id=chatId;boolean group=PairedProtocol.other(c)==null;
        base("details");header(group?"Group details":"Details",()->thread(id));
        ScrollView sc=new ScrollView(this);sc.setVerticalScrollBarEnabled(false);LinearLayout content=vertical();content.setPadding(dp(8),0,dp(8),dp(24));sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout top=vertical();top.setGravity(Gravity.CENTER_HORIZONTAL);top.addView(avatar(c,88));gap(top,12);
        TextView t=text(PairedProtocol.title(c),22,ink,false);t.setGravity(Gravity.CENTER);top.addView(t);List<JSONObject> people=PairedProtocol.members(c);
        if(group){gap(top,4);TextView count=text((people.size()+1)+" people, including you",14,muted,false);count.setGravity(Gravity.CENTER);top.addView(count);}
        content.addView(top,new LinearLayout.LayoutParams(-1,-2));gap(content,20);
        content.addView(text(group?"Members":"Contact",14,muted,true));gap(content,4);
        for(JSONObject p:people){String name=PairedProtocol.name(p),number=p.optString("formattedNumber",PairedProtocol.number(p));
            LinearLayout r=row();r.setPadding(dp(4),dp(10),dp(4),dp(10));r.setBackground(ripple());r.addView(personAvatar(p,name,44));
            LinearLayout texts=vertical();texts.setPadding(dp(16),0,dp(8),0);texts.addView(text(name,17,ink,false));if(!number.isEmpty()&&!number.equals(name))texts.addView(text(number,14,muted,false));
            r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));r.setOnClickListener(v->memberActions(p));content.addView(r);}
        if(group){LinearLayout me=row();me.setPadding(dp(4),dp(10),dp(4),dp(10));me.addView(personAvatar(null,"You",44));TextView you=text("You",17,ink,false);you.setPadding(dp(16),0,0,0);me.addView(you);content.addView(me);}
        gap(content,20);content.addView(text("This chat",14,muted,true));gap(content,4);
        sheetAction(content,chatSettings.pinned(c)?"Unpin":"Pin to top",()->{togglePin(c);details();});
        sheetAction(content,"Chat theme: "+chatSettings.theme(id).label,()->chooseTheme(id));
        sheetAction(content,"Read receipts: "+(chatSettings.receipts(id)?"on":"off"),()->toggleReceipts(id));
        sheetAction(content,chatSettings.muted(id)?"Notifications: muted (tap to unmute)":"Notifications: on (tap to mute)",()->toggleMute(id));
        sheetAction(content,"Search in chat",()->{thread(id);searchChat();});
        if(!bubble()){boolean archived=c.optString("status").contains("ARCHIVED"),blocked=c.optString("status").equals("BLOCKED_FOLDER")||c.optString("status").equals("SPAM_FOLDER");
            sheetAction(content,archived?"Unarchive":"Archive",()->archive(c,!archived));sheetAction(content,blocked?"Unblock":"Block…",()->block(c));sheetAction(content,"Delete conversation…",()->deleteChat(c));}
        if(!bubble())sheetAction(content,"Open as bubble",()->openBubble(id));
        requestMemberPhotos(c,()->{if(screen.equals("details")&&chatId.equals(id))details();});}
    private void memberActions(JSONObject p){String number=PairedProtocol.number(p);if(number.isEmpty())return;String name=PairedProtocol.name(p);
        menuSheet(name,java.util.Arrays.asList("Message","Call","Copy number"),java.util.Arrays.<Runnable>asList(()->startChat(number),
            ()->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));}catch(Exception e){toast("No phone app is available.");}},
            ()->getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Number",number))));}
    // ---- Attachments ----
    private static final int PICK_MEDIA=41,PICK_FILES=42;
    private static final long MAX_ATTACHMENT=100L<<20;
    private final java.util.concurrent.ExecutorService decoder=java.util.concurrent.Executors.newFixedThreadPool(2);
    private final List<Outgoing> outgoing=new ArrayList<>();private String outgoingChat="";
    private LinearLayout attachStrip;private HorizontalScrollView attachScroll;
    /** A file picked to send, copied into this app's cache first. */
    private static final class Outgoing{final File file;final String name,mime;boolean sending;Outgoing(File f,String n,String m){file=f;name=n;mime=m;}}
    /** A photo or video shown in the chat at its own shape, or a file/audio card. Tap to open, long-press for options. */
    private View mediaView(JSONObject m,JSONObject media,boolean outgoing,ChatTheme theme){
        String kind=PairedProtocol.mediaKind(media);LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);
        if(kind.equals("image")||kind.equals("video")){
            int width=Math.round(getResources().getDisplayMetrics().widthPixels*0.66f);JSONObject dims=media.optJSONObject("dimensions");
            long w=dims==null?0:dims.optLong("width"),h=dims==null?0:dims.optLong("height");float ratio=w>0&&h>0?(float)h/w:0.75f;ratio=Math.max(0.45f,Math.min(1.5f,ratio));
            FrameLayout box=new FrameLayout(this);box.setBackground(shape(surface,20));
            box.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(20));}});box.setClipToOutline(true);
            ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);box.addView(img,new FrameLayout.LayoutParams(-1,-1));
            if(kind.equals("video")){TextView play=text("▶",20,Color.WHITE,false);play.setGravity(Gravity.CENTER);play.setBackground(oval(0x99000000));box.addView(play,new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.CENTER));}
            box.setContentDescription(kind.equals("video")?"Video. Tap to play.":"Photo. Tap to view.");
            box.setOnClickListener(v->openMedia(media));box.setOnLongClickListener(v->{messageSheet(m);return true;});
            int height=Math.round(width*ratio);line.addView(box,new LinearLayout.LayoutParams(width,height));loadPreview(media,img,kind.equals("video"),width,height);
        } else {
            LinearLayout card=row();card.setPadding(dp(14),dp(12),dp(18),dp(12));card.setBackground(shape(outgoing?theme.bubble(dark):surface,22));int fg=outgoing?theme.onBubble(dark):ink;
            card.addView(text(kind.equals("audio")?"🎤":"📄",24,fg,false));LinearLayout texts=vertical();texts.setPadding(dp(12),0,0,0);
            String name=PairedProtocol.string(media,"mediaName");TextView title=text(name.isEmpty()?(kind.equals("audio")?"Audio message":"File"):name,15,fg,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.55f));texts.addView(title);
            String size=PairedProtocol.size(media.optLong("size"));boolean audio=kind.equals("audio");
            TextView status=text(audio?"▶ Tap to play"+(size.isEmpty()?"":" • "+size):(size.isEmpty()?"":size+" • ")+"Tap to open",13,outgoing?fg:muted,false);texts.addView(status);card.addView(texts);
            if(audio&&PairedProtocol.string(media,"mediaID").equals(playingId)){playingLabel=status;updatePlayLabel();}
            card.setContentDescription(title.getText()+". Tap to open.");card.setOnClickListener(v->{if(audio)playAudio(media,status);else openMedia(media);});card.setOnLongClickListener(v->{messageSheet(m);return true;});
            line.addView(card,new LinearLayout.LayoutParams(-2,-2));
            if(audio){LinearLayout col=vertical();col.addView(line,new LinearLayout.LayoutParams(-1,-2));col.addView(transcriptView(m,media,outgoing,theme),new LinearLayout.LayoutParams(-1,-2));return col;}
        }
        return line;
    }
    /** Small preview: Google's thumbnail when there is one, else the photo itself (videos over 15 MB keep the placeholder). */
    private boolean fullPhotos(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("full_photos",true);}
    /**
     * Google's small preview appears first, then the full photo replaces it so it's sharp at
     * chat size (unless "previews only" is chosen in Settings). Videos keep their preview frame.
     */
    private void loadPreview(JSONObject media,ImageView img,boolean video,int width,int height){
        boolean thumb=MediaCache.hasThumbnail(media);long size=media.optLong("size");
        boolean wantFull=!video&&(!thumb||(fullPhotos()&&size<=(25L<<20)));File full=MediaCache.file(this,media,false);
        if(wantFull&&full.isFile()){Bitmap b=MediaCache.peekCover(full,width,height);if(b!=null){img.setTag("full");img.setImageBitmap(b);return;}}
        if(thumb){File t=MediaCache.file(this,media,true);Bitmap b=t.isFile()?MediaCache.peekCover(t,width,height):null;
            if(b!=null)img.setImageBitmap(b);
            else if(t.isFile()||PairedService.ready())MediaCache.fetch(this,media,true,(file,err)->{if(file==null)return;
                decoder.execute(()->{Bitmap d=MediaCache.decodeToCover(file,width,height,false);if(d!=null)img.post(()->{if(img.getTag()==null)img.setImageBitmap(d);});});});}
        else if(video&&size>(15L<<20))return;
        if(!wantFull&&thumb)return;
        if(!full.isFile()&&!PairedService.ready())return;
        MediaCache.fetch(this,media,false,(file,err)->{if(file==null)return;
            decoder.execute(()->{Bitmap d=MediaCache.decodeToCover(file,width,height,video&&!thumb);if(d!=null)img.post(()->{img.setTag("full");img.setImageBitmap(d);});});});
    }
    /** Photos open in the viewer; videos, audio and files open in another app. */
    private void openMedia(JSONObject media){
        if(PairedProtocol.mediaKind(media).equals("image")){viewer(media);return;}
        withFile(media,"Downloading…",file->openExternally(file,media));
    }
    private interface FileAction{void run(File file);}
    /** Downloads (or reuses) the full attachment, showing a cancellable wait for anything not cached. */
    private void withFile(JSONObject media,String waiting,FileAction action){
        File cached=MediaCache.file(this,media,false);if(cached.isFile()){action.run(cached);return;}
        if(!PairedService.ready()){toast("Connect to Google Messages to download this attachment.");return;}
        boolean[] cancelled={false};ProgressBar spinner=new ProgressBar(this);spinner.setPadding(0,dp(16),0,dp(16));
        AlertDialog wait=new AlertDialog.Builder(this).setMessage(waiting).setView(spinner).setNegativeButton("Cancel",(d,w)->cancelled[0]=true).show();
        MediaCache.fetch(this,media,false,(file,error)->{if(cancelled[0])return;wait.dismiss();if(file==null){error(error);return;}action.run(file);});
    }
    private Uri shareUri(File file,JSONObject media){return MediaProvider.uri(file,displayName(media));}
    private String displayName(JSONObject media){String name=PairedProtocol.string(media,"mediaName");
        return name.isEmpty()?"Message-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+"."+MediaCache.extension(media):name;}
    private void openExternally(File file,JSONObject media){
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(shareUri(file,media),MediaCache.mime(media)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(view);}catch(Exception e){toast("No app on this phone can open this kind of file. Try Share instead.");}
    }
    private void share(File file,JSONObject media){Uri uri=shareUri(file,media);
        Intent send=new Intent(Intent.ACTION_SEND).setType(MediaCache.mime(media)).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(displayName(media),uri));startActivity(Intent.createChooser(send,"Share"));}
    /** Copies into Pictures, Movies or Downloads → "Message Center", where the gallery and Files apps can see it. */
    private void saveToPhone(File file,JSONObject media){String kind=PairedProtocol.mediaKind(media),mime=MediaCache.mime(media);
        decoder.execute(()->{String where;Uri collection;
            if(kind.equals("image")){collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);where=Environment.DIRECTORY_PICTURES;}
            else if(kind.equals("video")){collection=MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);where=Environment.DIRECTORY_MOVIES;}
            else {collection=MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);where=Environment.DIRECTORY_DOWNLOADS;}
            ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,displayName(media));v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,where+"/Message Center");v.put(MediaStore.MediaColumns.IS_PENDING,1);
            String result;Uri item=null;
            try{item=getContentResolver().insert(collection,v);if(item==null)throw new java.io.IOException("insert");
                try(java.io.InputStream in=new java.io.FileInputStream(file);java.io.OutputStream out=getContentResolver().openOutputStream(item)){if(out==null)throw new java.io.IOException("open");in.transferTo(out);}
                v.clear();v.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(item,v,null,null);result="Saved to "+where+"/Message Center";}
            catch(Exception e){if(item!=null)getContentResolver().delete(item,null,null);result="Could not save it to your phone.";}
            String r=result;ui.post(()->toast(r));});}
    /** Full screen, pinch or double-tap to zoom. Shows the preview at once and the full photo when it arrives. */
    private void viewer(JSONObject media){
        Dialog d=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);FrameLayout box=new FrameLayout(this);box.setBackgroundColor(Color.BLACK);
        ZoomImageView image=new ZoomImageView(this);box.addView(image,new FrameLayout.LayoutParams(-1,-1));
        ProgressBar spinner=new ProgressBar(this);box.addView(spinner,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));
        LinearLayout bar=row();bar.setPadding(dp(8),dp(36),dp(8),dp(8));bar.setBackgroundColor(0x66000000);
        bar.addView(icon(R.drawable.ic_close,Color.WHITE,"Close",d::dismiss));View space=new View(this);bar.addView(space,new LinearLayout.LayoutParams(0,1,1));
        File[] full={null};
        for(String[] a:new String[][]{{"Save"},{"Share"}}){TextView t=text(a[0],16,Color.WHITE,true);t.setPadding(dp(14),dp(10),dp(14),dp(10));
            t.setOnClickListener(v->{if(full[0]==null){toast("Still downloading…");return;}if(a[0].equals("Save"))saveToPhone(full[0],media);else share(full[0],media);});bar.addView(t);}
        box.addView(bar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));image.setOnTap(()->bar.setVisibility(bar.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        d.setContentView(box);d.show();
        File thumb=MediaCache.file(this,media,true);if(thumb.isFile()){Bitmap t=MediaCache.peek(thumb,Math.round(getResources().getDisplayMetrics().widthPixels*0.66f));if(t!=null)image.setImageBitmap(t);}
        if(!MediaCache.file(this,media,false).isFile()&&!PairedService.ready()){spinner.setVisibility(View.GONE);toast("Connect to Google Messages to download the full photo.");return;}
        MediaCache.fetch(this,media,false,(file,error)->{if(file==null){spinner.setVisibility(View.GONE);if(d.isShowing())toast(error);return;}full[0]=file;
            decoder.execute(()->{Bitmap b=MediaCache.decode(file,2560,false);ui.post(()->{spinner.setVisibility(View.GONE);if(b!=null)image.setImageBitmap(b);else toast("This photo can't be shown here. Use Share to open it in another app.");});});});
    }
    private static final int TAKE_PHOTO=43;private File pendingCapture;
    /** ⊕ button: a panel with Photos & videos (Android's photo picker, no storage permission), Camera, and Files. */
    private void attach(){LinearLayout content=vertical();Dialog[] d=new Dialog[1];LinearLayout tiles=row();tiles.setGravity(Gravity.CENTER);
        attachTile(tiles,R.drawable.ic_gallery,"Photos & videos",()->{d[0].dismiss();pickMedia();});
        attachTile(tiles,R.drawable.ic_camera,"Camera",()->{d[0].dismiss();takePhoto();});
        attachTile(tiles,R.drawable.ic_file,"Files",()->{d[0].dismiss();pickFiles();});
        content.addView(tiles,new LinearLayout.LayoutParams(-1,-2));d[0]=sheet(content);}
    private void attachTile(LinearLayout parent,int icon,String label,Runnable action){ChatTheme theme=chatSettings.theme(chatId);
        LinearLayout tile=vertical();tile.setGravity(Gravity.CENTER_HORIZONTAL);tile.setPadding(dp(4),dp(10),dp(4),dp(10));tile.setBackground(ripple());
        ImageView i=new ImageView(this);i.setImageResource(icon);i.setImageTintList(ColorStateList.valueOf(theme.onAccent(dark)));i.setBackground(oval(theme.accent(dark)));i.setPadding(dp(17),dp(17),dp(17),dp(17));
        tile.addView(i,new LinearLayout.LayoutParams(dp(62),dp(62)));gap(tile,10);TextView t=text(label,14,ink,false);t.setGravity(Gravity.CENTER);tile.addView(t);
        tile.setContentDescription(label);tile.setOnClickListener(v->action.run());parent.addView(tile,new LinearLayout.LayoutParams(0,-2,1));}
    private void pickMedia(){Intent pick=new Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,Math.min(10,MediaStore.getPickImagesMaxLimit()));
        try{startActivityForResult(pick,PICK_MEDIA);}catch(Exception e){startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addCategory(Intent.CATEGORY_OPENABLE),PICK_MEDIA);}}
    private void pickFiles(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),PICK_FILES);}
    /** Opens the camera; the photo is saved straight into this app's outgoing folder, then added to the message. */
    private void takePhoto(){File dir=new File(getCacheDir(),"outgoing");if(!dir.isDirectory())dir.mkdirs();pendingCapture=new File(dir,java.util.UUID.randomUUID()+"-camera.jpg");
        Uri uri=MediaProvider.outgoingUri(pendingCapture);
        Intent shoot=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shoot.setClipData(ClipData.newRawUri("Photo",uri));
        try{startActivityForResult(shoot,TAKE_PHOTO);}catch(Exception e){pendingCapture=null;toast("No camera app is available.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==TAKE_PHOTO){File shot=pendingCapture;pendingCapture=null;if(shot==null)return;
            if(result==RESULT_OK&&shot.length()>0){outgoing.add(new Outgoing(shot,"Photo.jpg","image/jpeg"));outgoingChat=chatId;renderAttachments();}else shot.delete();return;}
        if((request!=PICK_MEDIA&&request!=PICK_FILES)||result!=RESULT_OK||data==null)return;List<Uri> uris=new ArrayList<>();
        if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());
        decoder.execute(()->{List<String> problems=new ArrayList<>();List<Outgoing> added=new ArrayList<>();
            for(Uri uri:uris){try{added.add(copyIn(uri));}catch(Exception e){problems.add(e.getMessage()==null?"One attachment could not be added.":e.getMessage());}}
            ui.post(()->{outgoing.addAll(added);renderAttachments();if(!problems.isEmpty())toast(problems.get(0));});});}
    /** Copies a picked file into the cache, so it stays readable while it uploads. */
    private Outgoing copyIn(Uri uri) throws Exception{String name="attachment";long size=-1;
        try(android.database.Cursor q=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
            if(q!=null&&q.moveToFirst()){if(!q.isNull(0))name=q.getString(0);if(!q.isNull(1))size=q.getLong(1);}}
        if(size>MAX_ATTACHMENT)throw new Exception(name+" is over 100 MB, the most Google Messages sends.");
        String mime=getContentResolver().getType(uri);if(mime==null)mime="application/octet-stream";
        File dir=new File(getCacheDir(),"outgoing");if(!dir.isDirectory())dir.mkdirs();File target=new File(dir,java.util.UUID.randomUUID()+"-"+name.replaceAll("[^A-Za-z0-9._-]","_"));
        long copied;try(java.io.InputStream in=getContentResolver().openInputStream(uri);java.io.OutputStream out=new java.io.FileOutputStream(target)){if(in==null)throw new Exception("That file can't be read.");copied=in.transferTo(out);}
        if(copied>MAX_ATTACHMENT){target.delete();throw new Exception(name+" is over 100 MB, the most Google Messages sends.");}
        return new Outgoing(target,name,mime);}
    /** The row of picked attachments above the message box, each removable until sent. */
    private void renderAttachments(){if(attachStrip==null)return;attachStrip.removeAllViews();attachScroll.setVisibility(outgoing.isEmpty()?View.GONE:View.VISIBLE);
        for(Outgoing o:outgoing){FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(surface,14));
            tile.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline ol){ol.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(14));}});tile.setClipToOutline(true);
            boolean visual=o.mime.startsWith("image/")||o.mime.startsWith("video/");
            if(visual){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(img,new FrameLayout.LayoutParams(-1,-1));
                decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(o.file,dp(72),dp(72),o.mime.startsWith("video/"));if(b!=null)img.post(()->img.setImageBitmap(b));});}
            else {TextView t=text("📄\n"+o.name,11,ink,false);t.setGravity(Gravity.CENTER);t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);t.setPadding(dp(4),dp(4),dp(4),dp(4));tile.addView(t,new FrameLayout.LayoutParams(-1,-1));}
            if(o.sending){TextView s=text("Sending…",11,Color.WHITE,true);s.setGravity(Gravity.CENTER);s.setBackgroundColor(0x99000000);tile.addView(s,new FrameLayout.LayoutParams(-1,-1));}
            else {ImageView x=new ImageView(this);x.setImageResource(R.drawable.ic_close);x.setImageTintList(ColorStateList.valueOf(Color.WHITE));x.setBackground(oval(0x99000000));x.setPadding(dp(3),dp(3),dp(3),dp(3));
                x.setContentDescription("Remove "+o.name);x.setOnClickListener(v->{outgoing.remove(o);o.file.delete();renderAttachments();});tile.addView(x,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.TOP|Gravity.END));}
            LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(dp(72),dp(72));tl.setMarginEnd(dp(8));attachStrip.addView(tile,tl);}updateComposeButton();}
    /** Sends the picked attachments one message each; the typed text goes with the first as its caption. */
    private void sendAttachments(JSONObject c,String caption,String reply){String id=chatId;List<Outgoing> batch=new ArrayList<>();for(Outgoing o:outgoing)if(!o.sending)batch.add(o);
        if(batch.isEmpty())return;for(Outgoing o:batch)o.sending=true;renderAttachments();busy=true;
        if(composer!=null){composer.setText("");}drafts.remove(id);cancelReply();sendNext(c,batch,0,caption,reply,id);}
    private void sendNext(JSONObject c,List<Outgoing> batch,int index,String caption,String reply,String id){
        if(index>=batch.size()){busy=false;if(screen.equals("chat")&&chatId.equals(id))loadMessages(false);return;}
        Outgoing o=batch.get(index);String text=index==0?caption:"",replyTo=index==0?reply:"";
        PairedService.executeMedia(client->client.sendMedia(c.toString(),o.file.getPath(),o.name,o.mime,text,replyTo),(value,e)->{
            if(!e.isEmpty()){busy=false;for(Outgoing r:batch)r.sending=false;renderAttachments();if(index==0&&!caption.isEmpty()&&composer!=null&&composer.getText().length()==0)composer.setText(caption);error(o.name+": "+e);return;}
            outgoing.remove(o);o.file.delete();renderAttachments();sendNext(c,batch,index+1,caption,reply,id);});}
    // ---- Voice message transcripts ----
    private final Set<String> transcribing=new HashSet<>();private JSONObject pendingTranscription;
    /** Under a voice message: a Transcribe link, "Transcribing…", or the saved transcript. */
    private View transcriptView(JSONObject m,JSONObject media,boolean outgoing,ChatTheme theme){String id=PairedProtocol.string(media,"mediaID"),saved=Transcriber.saved(this,id);
        LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);line.setPadding(dp(14),dp(5),dp(14),0);TextView t;
        if(saved!=null){t=text(saved,14,ink,false);t.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.ITALIC));t.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));
            t.setGravity(outgoing?Gravity.END:Gravity.START);t.setContentDescription("Transcript: "+saved);t.setOnLongClickListener(v->{messageSheet(m);return true;});}
        else if(transcribing.contains(id))t=text("Transcribing…",13,muted,false);
        else if(id.isEmpty())return line;
        else {t=text("Transcribe",14,theme.accent(dark),true);t.setPadding(0,dp(2),0,dp(2));t.setContentDescription("Transcribe this voice message");t.setOnClickListener(v->transcribe(media));}
        line.addView(t);return line;}
    private void transcribe(JSONObject media){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){pendingTranscription=media;
            toast("Android's speech recognizer needs microphone access, even to read a message. Nothing is recorded.");requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},78);return;}
        String id=PairedProtocol.string(media,"mediaID");if(id.isEmpty()||transcribing.contains(id))return;
        withFile(media,"Downloading…",file->{transcribing.add(id);renderMessages(false);
            Transcriber.transcribe(this,file,id,(text,err)->{transcribing.remove(id);renderMessages(false);if(!err.isEmpty())error(err);});});}

    // ---- 0.6: reliability, outbox, contacts, management, search, voice, sharing, links ----
    private Outbox outbox;
    private JSONObject contacts;private boolean groupMode;private String contactQuery="";private final Map<String,String> picked=new LinkedHashMap<>();
    private final List<Outgoing> incoming=new ArrayList<>();private String incomingText;
    private MediaRecorder recorder;private File recording;private long recordStart;private LinearLayout recordBar,composeRow;private ImageView sendButton;private TextView recordClock;
    private final Runnable recordTick=this::updateRecordClock;
    private MediaPlayer player;private String playingId="";private TextView playingLabel;private final Runnable playTick=this::updatePlayLabel;

    private boolean batteryRestricted(){return !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());}
    /** Android's own prompt to stop putting this app to sleep, so messages arrive on time. */
    private void allowBackground(){
        try{startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}
        catch(Exception e){try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception ignored){toast("Open Settings → Apps → Message Center → Battery.");}}}
    private void openAppSettings(){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    private View batteryCard;
    /** Shown until you allow it or tap Not now; ColorOS can keep reporting "restricted" even after you allow it. */
    private boolean showBatteryCard(){android.content.SharedPreferences sp=getSharedPreferences("paired",MODE_PRIVATE);
        return batteryRestricted()&&!sp.getBoolean("battery_hint_dismissed",false)&&!sp.getBoolean("battery_hint_done",false);}
    private View batteryBanner(){LinearLayout card=vertical();card.setPadding(dp(18),dp(14),dp(18),dp(10));card.setBackground(shape(surface,20));
        card.addView(text("Keep messages arriving on time",16,ink,true));gap(card,4);
        card.addView(text("Android can pause Message Center in the background, which delays or drops notifications. Allow it to run in the background.",14,muted,false));
        LinearLayout actions=row();actions.setGravity(Gravity.END);TextView later=text("Not now",15,muted,true);later.setPadding(dp(12),dp(10),dp(12),dp(6));
        later.setOnClickListener(v->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("battery_hint_dismissed",true).apply();inbox();});
        TextView allow=text("Allow",15,fab==0?ink:onFab,true);allow.setPadding(dp(18),dp(8),dp(18),dp(8));allow.setBackground(shape(fab,16));allow.setOnClickListener(v->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("battery_hint_done",true).apply();allowBackground();});
        actions.addView(later);actions.addView(allow);gap(card,8);card.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(12),dp(4),dp(12),dp(8));card.setLayoutParams(lp);return card;}

    /** Saved messages for this chat, shown at the end with their state; tap to retry or delete. */
    private void renderOutbox(ChatTheme theme){
        for(JSONObject o:outbox.forChat(chatId)){String state=o.optString("state");gap(items,8);
            LinearLayout line=row();line.setGravity(Gravity.END);TextView b=text(o.optString("text"),17,theme.onBubble(dark),false);b.setPadding(dp(16),dp(11),dp(16),dp(11));
            b.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.setBackground(shape(theme.bubble(dark),22));b.setAlpha(0.7f);line.addView(b);items.addView(line);
            String label=switch(state){case Outbox.SENDING->"Sending…";case Outbox.UNSURE->"Not sure this sent • Tap for options";case Outbox.FAILED->"Not sent • Tap to retry";default->"Waiting for connection • will send automatically";};
            TextView s=text(label,12,state.equals(Outbox.FAILED)||state.equals(Outbox.UNSURE)?danger:muted,state.equals(Outbox.FAILED));s.setGravity(Gravity.END);s.setPadding(0,dp(4),dp(6),0);items.addView(s,new LinearLayout.LayoutParams(-1,-2));
            View.OnClickListener act=v->outboxOptions(o);b.setOnClickListener(act);s.setOnClickListener(act);}}
    private void outboxOptions(JSONObject o){String id=o.optString("id"),state=o.optString("state");if(state.equals(Outbox.SENDING))return;
        String msg=state.equals(Outbox.UNSURE)?"Google didn't confirm this message. It may already have been sent; check the chat in Google Messages before sending again.":o.optString("error");
        new AlertDialog.Builder(this).setMessage(msg.isEmpty()?"This message hasn't been sent yet.":msg).setPositiveButton(state.equals(Outbox.WAITING)?"Send now":"Send again",(d,w)->PairedService.retryOutbox(this,id))
            .setNegativeButton("Delete",(d,w)->{outbox.remove(id);renderMessages(false);}).setNeutralButton("Cancel",null).show();}
    /** A message Google reports as failed: send its text again, then remove the failed copy. */
    private void retryFailed(JSONObject m){String text=PairedProtocol.text(m);
        if(text.isEmpty()){error("Attachments can't be resent automatically. Send them again with the + button.");return;}
        new AlertDialog.Builder(this).setMessage("Send this message again?").setPositiveButton("Send again",(d,w)->{
            PairedService.sendOrQueue(this,chatId,text,"",(v,e)->{if(!e.isEmpty()){error(e);return;}
                String mid=m.optString("messageID");if(!mid.isEmpty()&&!"queued".equals(v))PairedService.execute(c->{c.deleteMessage(mid);return "";},(x,err)->{if(err.isEmpty())store.deleteMessage(mid);loadMessages(false);});
                else loadMessages(false);});}).setNegativeButton("Cancel",null).show();}

    // Contacts and new conversations
    private void newMessage(){if(!PairedService.ready()){toast("Connect first, then start a chat.");return;}picked.clear();groupMode=false;contactQuery="";newChat();if(contacts==null)loadContacts();}
    private void loadContacts(){PairedService.execute(c->c.contacts(),(raw,e)->{if(!e.isEmpty()){toast(e);return;}try{contacts=new JSONObject(raw);}catch(Exception ex){return;}if(screen.equals("newchat"))renderContacts();});}
    private void newChat(){remember();base("newchat");header(groupMode?"New group":"New conversation",()->{if(groupMode){groupMode=false;picked.clear();newChat();}else inbox();});
        EditText search=input(groupMode?"Add people by name or number":"Type a name or phone number");search.setSingleLine(true);search.setText(contactQuery);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.setMargins(dp(16),0,dp(16),dp(8));root.addView(search,sp);
        scroll=new ScrollView(this);items=vertical();items.setPadding(dp(8),0,dp(8),dp(96));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(groupMode){TextView next=text(picked.size()<2?"Pick at least 2 people":"Next • "+picked.size()+" people",16,picked.size()<2?muted:onFab,true);next.setGravity(Gravity.CENTER);
            next.setPadding(0,dp(14),0,dp(14));next.setBackground(shape(picked.size()<2?surface:fab,18));next.setOnClickListener(v->{if(picked.size()>=2)nameGroup();});
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(dp(16),dp(4),dp(16),dp(12));root.addView(next,np);}
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){contactQuery=s.toString();renderContacts();}public void afterTextChanged(Editable e){}});
        renderContacts();search.requestFocus();}
    private void renderContacts(){if(items==null||!screen.equals("newchat"))return;items.removeAllViews();String q=contactQuery.trim(),number=ChatText.normalizeNumber(q);
        if(!groupMode&&q.isEmpty()){LinearLayout g=row();g.setPadding(dp(8),dp(10),dp(8),dp(10));View face=new FrameLayout(this);face.setBackground(oval(fab));
            TextView plus=text("👥",20,onFab,false);plus.setGravity(Gravity.CENTER);((FrameLayout)face).addView(plus,new FrameLayout.LayoutParams(-1,-1));g.addView(face,new LinearLayout.LayoutParams(dp(44),dp(44)));
            TextView t=text("Create group",17,ink,false);t.setPadding(dp(16),0,0,0);g.addView(t);g.setOnClickListener(v->{groupMode=true;picked.clear();newChat();});items.addView(g);}
        if(!picked.isEmpty()){items.addView(sectionLabel("Selected"));for(Map.Entry<String,String> e:new ArrayList<>(picked.entrySet()))items.addView(contactRow(e.getValue(),e.getKey(),"",""));}
        if(!number.isEmpty()&&!picked.containsKey(number))items.addView(contactRow("Use "+number,number,"",""));
        if(contacts==null){items.addView(sectionLabel("Loading contacts…"));return;}
        String ql=q.toLowerCase(Locale.ROOT),qd=ChatText.digits(q);Set<String> shown=new HashSet<>(picked.keySet());
        if(q.isEmpty()){org.json.JSONArray top=contacts.optJSONArray("top");if(top!=null&&top.length()>0){items.addView(sectionLabel("Frequent"));addContacts(top,"","",shown,20);}}
        org.json.JSONArray all=contacts.optJSONArray("all");if(all!=null){items.addView(sectionLabel(q.isEmpty()?"All contacts":"Contacts"));addContacts(all,ql,qd,shown,q.isEmpty()?400:60);}}
    private void addContacts(org.json.JSONArray list,String ql,String qd,Set<String> shown,int max){int added=0;
        for(int i=0;i<list.length()&&added<max;i++){JSONObject ct=list.optJSONObject(i);if(ct==null)continue;String name=ct.optString("name");JSONObject num=ct.optJSONObject("number");
            String n=num==null?"":ChatText.normalizeNumber(PairedProtocol.string(num,"number"));if(n.isEmpty()&&num!=null&&PairedProtocol.string(num,"number").startsWith("+"))n=PairedProtocol.string(num,"number");
            if(n.isEmpty()||shown.contains(n))continue;String formatted=num==null?n:num.optString("formattedNumber",n);
            if(!ql.isEmpty()&&!name.toLowerCase(Locale.ROOT).contains(ql)&&(qd.length()<3||!ChatText.digits(n).contains(qd)))continue;
            shown.add(n);items.addView(contactRow(name.isEmpty()?formatted:name,n,formatted,ct.optString("avatarHexColor")));added++;}}
    private TextView sectionLabel(String label){TextView t=text(label,14,muted,true);t.setPadding(dp(8),dp(14),dp(8),dp(6));return t;}
    private View contactRow(String name,String number,String formatted,String hex){LinearLayout r=row();r.setPadding(dp(8),dp(8),dp(8),dp(8));r.setBackground(ripple());
        JSONObject p=new JSONObject();try{p.put("avatarHexColor",hex);}catch(Exception ignored){}r.addView(personAvatar(p,name,44));
        LinearLayout texts=vertical();texts.setPadding(dp(16),0,dp(8),0);texts.addView(text(name,17,ink,false));if(!formatted.isEmpty()&&!formatted.equals(name))texts.addView(text(formatted,14,muted,false));
        r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        if(groupMode){boolean on=picked.containsKey(number);TextView check=text(on?"✓":"",18,onFab,true);check.setGravity(Gravity.CENTER);check.setBackground(on?oval(fab):shape(Color.TRANSPARENT,13));
            if(!on){GradientDrawable ring=shape(Color.TRANSPARENT,13);ring.setStroke(dp(2),muted);check.setBackground(ring);}r.addView(check,new LinearLayout.LayoutParams(dp(26),dp(26)));
            r.setOnClickListener(v->{if(picked.containsKey(number))picked.remove(number);else picked.put(number,name.startsWith("Use +")?number:name);contactQuery="";newChat();});}
        else r.setOnClickListener(v->startChat(number));
        return r;}
    private void nameGroup(){EditText name=input("Group name (optional)");name.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Name this group").setMessage("RCS groups can have a name everyone sees. Picture-message (MMS) groups can't.").setView(name)
            .setNegativeButton("Back",null).setPositiveButton("Create",(d,w)->{String numbers=String.join(",",picked.keySet()),title=name.getText().toString().trim();
                PairedService.execute(c->c.createGroup(numbers,title),(raw,e)->{if(!e.isEmpty()){error(e);return;}try{JSONObject chat=new JSONObject(raw);store.chat(chat);picked.clear();groupMode=false;thread(chat.getString("conversationID"));}catch(Exception ex){error("The new group could not be opened.");}});}).show();}

    // Conversation management
    private void archive(JSONObject c,boolean on){String id=c.optString("conversationID");
        PairedService.execute(cl->{cl.archiveChat(id,on);return "";},(v,e)->{if(!e.isEmpty()){error(e);return;}setStatus(c,on?"ARCHIVED":"ACTIVE");toast(on?"Archived":"Moved to inbox");if(!screen.equals("inbox"))inbox();else renderInbox();});}
    private void setStatus(JSONObject c,String status){try{JSONObject copy=new JSONObject(c.toString());copy.put("status",status);store.chat(copy);}catch(Exception ignored){}}
    private void toggleMute(String id){boolean on=!chatSettings.muted(id);chatSettings.setMuted(id,on);toast(on?"Notifications muted for this chat":"Notifications on for this chat");
        if(on)PairedService.clearNotification(this,id);if(PairedService.ready())PairedService.execute(c->{c.muteChat(id,on);return "";},(v,e)->{});if(screen.equals("details"))details();else if(screen.equals("inbox"))renderInbox();}
    private void toggleUnread(String id){boolean on=!chatSettings.markedUnread(id);chatSettings.setMarkedUnread(id,on);if(on&&!screen.equals("inbox"))inbox();else renderInbox();}
    private void deleteChat(JSONObject c){String id=c.optString("conversationID");
        new AlertDialog.Builder(this).setTitle("Delete this conversation?").setMessage("It will be deleted from Google Messages on your phone. This can't be undone.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->PairedService.execute(cl->{cl.deleteChat(c.toString());return "";},(v,e)->{if(!e.isEmpty()){error(e);return;}
                store.deleteChat(id);PairedService.clearNotification(this,id);getSystemService(NotificationManager.class).cancel("chat:"+id,1);toast("Conversation deleted");inbox();})).show();}
    private void block(JSONObject c){String id=c.optString("conversationID");boolean blocked=c.optString("status").equals("BLOCKED_FOLDER")||c.optString("status").equals("SPAM_FOLDER");
        if(blocked){PairedService.execute(cl->{cl.blockChat(c.toString(),"unblock");return "";},(v,e)->{if(!e.isEmpty()){error(e);return;}setStatus(c,"ACTIVE");toast("Unblocked");inbox();});return;}
        new AlertDialog.Builder(this).setTitle("Block "+PairedProtocol.title(c)+"?").setMessage("You won't get messages or notifications from this conversation. You can unblock it from ⋮ → Blocked & spam.")
            .setNegativeButton("Cancel",null).setNeutralButton("Block & report spam",(d,w)->doBlock(c,id,"report")).setPositiveButton("Block",(d,w)->doBlock(c,id,"block")).show();}
    private void doBlock(JSONObject c,String id,String action){PairedService.execute(cl->{cl.blockChat(c.toString(),action);return "";},(v,e)->{if(!e.isEmpty()){error(e);return;}
        setStatus(c,action.equals("report")?"SPAM_FOLDER":"BLOCKED_FOLDER");PairedService.clearNotification(this,id);getSystemService(NotificationManager.class).cancel("chat:"+id,1);toast(action.equals("report")?"Blocked and reported":"Blocked");inbox();});}
    private void deleteMessage(JSONObject m){String mid=m.optString("messageID");if(mid.isEmpty())return;
        new AlertDialog.Builder(this).setMessage("Delete this message? It's removed from Google Messages on your phone, not from the other person's phone.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->PairedService.execute(c->{c.deleteMessage(mid);return "";},(v,e)->{if(!e.isEmpty()){error(e);return;}store.deleteMessage(mid);renderMessages(false);})).show();}

    // Search inside a chat
    private void searchChat(){LinearLayout content=vertical();EditText q=input("Search this conversation");q.setSingleLine(true);content.addView(q);gap(content,8);
        LinearLayout results=vertical();ScrollView rs=new ScrollView(this);rs.addView(results);content.addView(rs,new LinearLayout.LayoutParams(-1,Math.round(getResources().getDisplayMetrics().heightPixels*0.45f)));
        TextView hint=text("Searches messages synced to this phone. Use ⋮ → Load older messages to include more.",13,muted,false);results.addView(hint);
        Dialog d=sheet(content);String id=chatId;JSONObject c=store.chat(id);int[] generation={0};
        q.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int cc,int a){}public void afterTextChanged(Editable e){}
            public void onTextChanged(CharSequence s,int st,int b,int cc){String query=s.toString();int g=++generation[0];
                decoder.execute(()->{List<JSONObject> found=query.trim().length()<2?new ArrayList<>():store.search(id,query,60);ui.post(()->{if(g!=generation[0])return;results.removeAllViews();
                    if(query.trim().length()<2){results.addView(hint);return;}if(found.isEmpty()){results.addView(text("No matches in synced messages",14,muted,false));return;}
                    for(JSONObject m:found){LinearLayout r=vertical();r.setPadding(dp(4),dp(10),dp(4),dp(10));r.setBackground(ripple());
                        r.addView(text((PairedProtocol.outgoing(m,c)?"You":senderName(m,c))+" • "+separatorStamp(PairedProtocol.time(m)),12,muted,false));
                        TextView t=text(PairedProtocol.text(m),15,ink,false);t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);r.addView(t);
                        r.setOnClickListener(v->{d.dismiss();showMessage(m);});results.addView(r);}});});}});
        q.requestFocus();}
    private void showMessage(JSONObject m){if(!screen.equals("chat"))return;int needed=store.countSince(chatId,PairedProtocol.time(m));if(needed>limit)limit=needed+20;
        renderMessages(false);String mid=m.optString("messageID");scroll.postDelayed(()->jumpTo(mid),150);}

    // Voice messages
    private void updateComposeButton(){if(sendButton==null||composer==null)return;boolean empty=composer.getText().toString().trim().isEmpty();boolean files=false;for(Outgoing o:outgoing)if(!o.sending)files=true;
        boolean mic=empty&&!files;sendButton.setImageResource(mic?R.drawable.ic_mic:R.drawable.ic_send);sendButton.setContentDescription(mic?"Record voice message":"Send");}
    private void sendOrRecord(){boolean files=false;for(Outgoing o:outgoing)if(!o.sending)files=true;if(composer!=null&&composer.getText().toString().trim().isEmpty()&&!files)startRecording();else send();}
    private void startRecording(){if(recorder!=null)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},77);return;}
        File dir=new File(getCacheDir(),"outgoing");if(!dir.isDirectory())dir.mkdirs();recording=new File(dir,java.util.UUID.randomUUID()+"-voice.m4a");
        try{recorder=new MediaRecorder(this);recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);recorder.setAudioEncodingBitRate(64000);recorder.setAudioChannels(1);recorder.setMaxDuration(10*60*1000);recorder.setOutputFile(recording.getPath());
            recorder.setOnInfoListener((r,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)finishRecording(true);});recorder.prepare();recorder.start();}
        catch(Exception e){if(recorder!=null){recorder.release();recorder=null;}recording.delete();error("The microphone could not be started.");return;}
        recordStart=System.currentTimeMillis();if(recordBar!=null){recordBar.setVisibility(View.VISIBLE);composeRow.setVisibility(View.GONE);}updateRecordClock();}
    private void updateRecordClock(){ui.removeCallbacks(recordTick);if(recorder==null||recordClock==null)return;long s=(System.currentTimeMillis()-recordStart)/1000;
        recordClock.setText(String.format(Locale.US,"● Recording  %d:%02d",s/60,s%60));ui.postDelayed(recordTick,500);}
    /** Stops recording; keep=true adds it to the message (sent right away when send=true). */
    private void finishRecording(boolean send){if(recorder==null)return;ui.removeCallbacks(recordTick);boolean ok=System.currentTimeMillis()-recordStart>700;
        try{recorder.stop();}catch(Exception e){ok=false;}recorder.release();recorder=null;if(recordBar!=null){recordBar.setVisibility(View.GONE);composeRow.setVisibility(View.VISIBLE);}
        if(!ok){recording.delete();toast("Too short to send");return;}
        outgoing.add(new Outgoing(recording,"Voice message.m4a","audio/mp4"));outgoingChat=chatId;renderAttachments();updateComposeButton();
        if(send){JSONObject c=store.chat(chatId);if(c!=null)sendAttachments(c,"",replyId);}}
    private void cancelRecording(){if(recorder==null)return;ui.removeCallbacks(recordTick);try{recorder.stop();}catch(Exception ignored){}recorder.release();recorder=null;recording.delete();
        if(recordBar!=null){recordBar.setVisibility(View.GONE);composeRow.setVisibility(View.VISIBLE);}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);
        if(request==77){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startRecording();else toast("Message Center needs the microphone to record voice messages.");}
        if(request==78){JSONObject pending=pendingTranscription;pendingTranscription=null;if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&pending!=null)transcribe(pending);else if(pending!=null)toast("Transcription needs microphone access.");}}
    /** Plays an audio attachment in place; tap again to pause. */
    private void playAudio(JSONObject media,TextView label){String id=PairedProtocol.string(media,"mediaID");
        if(player!=null&&id.equals(playingId)){if(player.isPlaying())player.pause();else player.start();playingLabel=label;updatePlayLabel();return;}
        stopAudio();withFile(media,"Downloading…",file->{try{player=new MediaPlayer();player.setDataSource(file.getPath());player.prepare();player.setOnCompletionListener(mp->{stopAudio();renderMessages(false);});player.start();
            playingId=id;playingLabel=label;updatePlayLabel();}catch(Exception e){stopAudio();openExternally(file,media);}});}
    private void updatePlayLabel(){ui.removeCallbacks(playTick);if(player==null||playingLabel==null)return;
        try{int at=player.getCurrentPosition()/1000,len=Math.max(0,player.getDuration()/1000);playingLabel.setText(String.format(Locale.US,"%s %d:%02d / %d:%02d",player.isPlaying()?"⏸":"▶",at/60,at%60,len/60,len%60));}catch(Exception ignored){}
        if(player.isPlaying())ui.postDelayed(playTick,500);}
    private void stopAudio(){ui.removeCallbacks(playTick);if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}playingId="";playingLabel=null;}

    // Sharing into the app
    /** Text, photos, or files shared from another app: copied in, then a chat is chosen (or opened directly from Android's share sheet). */
    private boolean receiveShare(Intent intent){String action=intent==null?null:intent.getAction();if(!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_SEND_MULTIPLE.equals(action))return false;
        if(!new SessionVault(this).exists()){toast("Connect Message Center to Google Messages first.");return false;}
        CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);incomingText=text==null?null:text.toString();List<Uri> uris=new ArrayList<>();
        if(Intent.ACTION_SEND.equals(action)){Uri u=intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class);if(u!=null)uris.add(u);}
        else {ArrayList<Uri> list=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM,Uri.class);if(list!=null)uris.addAll(list);}
        String shortcut=intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID);String target=shortcut!=null&&shortcut.startsWith("chat-")?shortcut.substring(5):"";
        incoming.clear();
        decoder.execute(()->{List<Outgoing> copied=new ArrayList<>();String problem=null;for(Uri u:uris){try{copied.add(copyIn(u));}catch(Exception e){problem=e.getMessage();}}String p=problem;
            ui.post(()->{incoming.addAll(copied);if(p!=null)toast(p);if(!target.isEmpty()&&store.chat(target)!=null)thread(target);else{folder=1;inbox();}});});
        return true;}
    private boolean hasIncoming(){return !incoming.isEmpty()||(incomingText!=null&&!incomingText.isEmpty());}
    private void applyIncoming(){if(!hasIncoming()||composer==null)return;outgoingChat=chatId;outgoing.addAll(incoming);incoming.clear();
        if(incomingText!=null){String now=composer.getText().toString();composer.setText(now.isEmpty()?incomingText:now+" "+incomingText);composer.setSelection(composer.getText().length());incomingText=null;}
        renderAttachments();updateComposeButton();}
    private View shareBanner(){LinearLayout card=row();card.setPadding(dp(18),dp(12),dp(10),dp(12));card.setBackground(shape(fab,18));
        int n=incoming.size();TextView t=text(n==0?"Pick a conversation to share to":"Sharing "+n+(n==1?" item":" items")+" • pick a conversation",15,onFab,true);card.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        TextView cancel=text("Cancel",15,onFab,false);cancel.setPadding(dp(10),dp(4),dp(8),dp(4));cancel.setOnClickListener(v->{for(Outgoing o:incoming)o.file.delete();incoming.clear();incomingText=null;inbox();});card.addView(cancel);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(12),dp(4),dp(12),dp(8));card.setLayoutParams(lp);return card;}

    // Links
    private boolean linkPreviewsOn(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("link_previews",true);}
    /** Title and picture for the first link in a message, under its bubble; tap opens the link. */
    private View linkCard(String url,boolean outgoing){LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);line.setVisibility(View.GONE);
        LinearLayout card=vertical();card.setBackground(shape(surface,18));card.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(18));}});card.setClipToOutline(true);
        int width=Math.round(getResources().getDisplayMetrics().widthPixels*0.66f);line.addView(card,new LinearLayout.LayoutParams(width,-2));
        LinkPreviews.Done fill=p->{card.removeAllViews();String image=p.optString("image");
            if(!image.isEmpty()){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(img,new LinearLayout.LayoutParams(-1,dp(150)));
                decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(new File(image),width,dp(150),false);if(b!=null)img.post(()->img.setImageBitmap(b));});}
            LinearLayout texts=vertical();texts.setPadding(dp(14),dp(10),dp(14),dp(12));TextView title=text(p.optString("title"),15,ink,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);texts.addView(title);
            String site=p.optString("site");texts.addView(text(site.isEmpty()?ChatText.domain(url):site,13,muted,false));card.addView(texts);line.setVisibility(View.VISIBLE);};
        card.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("No browser is available.");}});
        JSONObject ready=LinkPreviews.peek(url);if(ready!=null){if(!ready.optString("title").isEmpty())fill.done(ready);}else LinkPreviews.load(this,url,fill);
        return line;}
    private void settings(){remember();base("settings");header("Settings",this::inbox);connection();ScrollView sc=new ScrollView(this);LinearLayout content=vertical();sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        note(content,"Message Center "+BuildConfig.VERSION_NAME+" • experimental paired client\n\nThe connection runs on this phone and talks directly to Google. Google Messages must remain your default messaging app and have network/background access.");
        int phone=chatSettings.phoneReceipts();
        note(content,"Read receipts\n\nOff for every chat unless you turn them on for that chat (long-press it in the list, or tap its name at the top of the chat). When on, opening the chat here marks it read on your phone and Google Messages sends a receipt. When off, the chat is not marked read, so it stays unread in Google Messages.\n\nGoogle Messages' own \"Send read receipts\" setting: "+(phone==1?"On":phone==0?"Off: no receipts are sent for any chat":"not reported yet")+".\n\nPins and chat themes are kept in this app. Google's protocol has no pin action, so pinning here does not change Google Messages.");
        boolean typingOn=getSharedPreferences("paired",MODE_PRIVATE).getBoolean("send_typing",true);
        content.addView(button(typingOn?"Typing indicators: on (others see when you type)":"Typing indicators: off",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("send_typing",!typingOn).apply();settings();}));gap(content,8);
        content.addView(button("Bubble settings",false,()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))));gap(content,8);
        content.addView(button("Reconnect",true,()->{PairedService.disconnect(this,false);new android.os.Handler().postDelayed(()->{PairedService.start(this);settings();},300);}));gap(content,8);
        content.addView(button("Sign in / pair again",false,this::signIn));gap(content,8);
        content.addView(button("Allow Message Center notifications",false,()->{if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}));gap(content,8);
        note(content,"Background reliability\n\nAndroid reports battery optimization: "+(batteryRestricted()?"on. If you already allowed background activity in ColorOS's own settings, that is enough; Android may still say on.":"off ✓")+"\n\nOn ColorOS, also open App settings → Battery and allow background activity and auto-launch, for both Message Center and Google Messages.");
        if(batteryRestricted()){content.addView(button("Allow running in the background",true,this::allowBackground));gap(content,8);}
        content.addView(button("App settings (battery, auto-launch)",false,this::openAppSettings));gap(content,8);
        boolean full=fullPhotos();content.addView(button(full?"Photos in chats: full quality":"Photos in chats: previews only (uses less data)",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("full_photos",!full).apply();settings();}));gap(content,8);
        boolean links=linkPreviewsOn();content.addView(button(links?"Link previews: on (your phone opens the page to read its title)":"Link previews: off",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("link_previews",!links).apply();settings();}));gap(content,8);
        note(content,"History loads in pages from Google. Some older messages may not be available. Attachments download when you open them and are kept in the app's cache.");
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
    @Override protected void onDestroy(){destroyLogin();cancelRecording();stopAudio();decoder.shutdownNow();super.onDestroy();}
}
