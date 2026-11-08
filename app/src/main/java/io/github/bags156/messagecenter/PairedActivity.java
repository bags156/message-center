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
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
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
    private ScrollView scroll;private EditText composer;private WebView login;private LinearLayout searchRow;private TextView receiptStatusView;
    private String screen="inbox",chatId="",query="",replyId="";
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
        updateReceiptStatus();
        if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat")){renderMessages(false);markReadIfAllowed();updateTyping();}
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);store=new PairedStore(this);chatSettings=new ChatSettings(this);outbox=new Outbox(this);starred=new Starred(this);Nicknames.load(this);
        if(state!=null){String shot=state.getString("capture");if(shot!=null)pendingCapture=new File(shot);chatId=state.getString("chat","");replyId=state.getString("reply","");drafts.put(chatId,state.getString("draft",""));}
        String target=getIntent().getStringExtra("chat");if(target!=null&&!target.isEmpty())chatId=target;
        if(bubble()&&chatId.isEmpty()){finish();return;}
        if(!chatId.isEmpty()){thread(chatId);showFromIntent(getIntent());}else inbox();
        if(state==null&&!bubble())receiveShare(getIntent());
        if(state==null&&MessagesWidget.NEW_CHAT.equals(getIntent().getAction()))newMessage();
        if(new SessionVault(this).exists() && getSharedPreferences("paired",MODE_PRIVATE).getBoolean("enabled",true))PairedService.start(this);
    }
    /** A tapped notification (or bubble) delivers its chat here when this screen is already open. */
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(!bubble()&&receiveShare(intent))return;if(MessagesWidget.NEW_CHAT.equals(intent.getAction())){if(login!=null)destroyLogin();inbox();newMessage();return;}String id=intent.getStringExtra("chat");if(id!=null&&!id.isEmpty()){if(login!=null)destroyLogin();thread(id);showFromIntent(intent);}}
    @Override protected void onResume(){super.onResume();resumed=true;if(!registered){registerReceiver(receiver,new IntentFilter(PairedService.CHANGED),Context.RECEIVER_NOT_EXPORTED);registered=true;}
        if(PairedService.ready()&&screen.equals("pair")){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();}
        else if(!bubble()&&!PairedService.emoji().isEmpty()&&login==null)pairing();
        else {updateStatus();PairedService.syncNow();
            // Updates that arrived while this screen was hidden (or a bubble was collapsed) are shown now, then refreshed from Google.
            if(screen.equals("chat")){renderMessages(false);if(PairedService.ready())loadMessages(false);markReadIfAllowed();updateTyping();if(!bubble())PairedService.clearNotification(this,chatId);}
            else if(screen.equals("inbox")){if(batteryCard!=null&&!showBatteryCard()){root.removeView(batteryCard);batteryCard=null;}renderInbox();refreshInbox(false);
                if(!bubble()&&PairedService.ready()&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)askContactPhotos();}}}
    @Override protected void onPause(){resumed=false;remember();ui.removeCallbacks(typingTick);if(recorder!=null)finishRecording(false);if(player!=null&&player.isPlaying()){player.pause();updatePlayLabel();}if(registered){unregisterReceiver(receiver);registered=false;}super.onPause();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&store!=null){markReadIfAllowed();updateReceiptStatus();}}
    @Override protected void onSaveInstanceState(Bundle out){remember();if(pendingCapture!=null)out.putString("capture",pendingCapture.getPath());out.putString("chat",chatId);out.putString("reply",replyId);out.putString("draft",drafts.getOrDefault(chatId,""));super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(selecting){stopSelecting();return;}if(screen.equals("media")){details();return;}if(screen.equals("details")){thread(chatId);return;}if(bubble()){super.onBackPressed();return;}if(login!=null){destroyLogin();inbox();}else if(screen.equals("inbox")&&(folder!=1||unknownOnly||promoOnly)){folder=1;unknownOnly=false;promoOnly=false;inbox();}else if(!screen.equals("inbox")){remember();inbox();}else super.onBackPressed();}

    // ---- Shared building blocks ----
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void base(String name){
        receiptStatusView=null;
        // Screenshots are always allowed (an older version could leave them blocked after signing in).
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);if(!name.equals("login"))setRecentsScreenshotEnabled(true);
        if(recorder!=null)cancelRecording();recordBar=null;composeRow=null;sendButton=null;recordClock=null;screen=name;batteryCard=null;jumpButton=null;statusView=null;composer=null;items=null;scroll=null;searchRow=null;attachStrip=null;attachScroll=null;replyPanel=null;typingRow=null;messageViews.clear();ui.removeCallbacks(typingTick);if(typingPulse!=null){typingPulse.cancel();typingPulse=null;}
        dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        textScale=getSharedPreferences("paired",MODE_PRIVATE).getFloat("text_scale",1f);quickRow=null;quickScroll=null;searchChips=null;selecting=false;chosen.clear();selectionBar=null;chatFrame=null;
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
        File f=Avatars.best(this,id);Bitmap b=f.isFile()?BitmapFactory.decodeFile(f.getPath()):null;if(b!=null)photos.put(id,b);return b;
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
        if(!id.isEmpty()){if(photos.containsKey(id))b=photos.get(id);else{File f=Avatars.best(this,id);b=f.isFile()?BitmapFactory.decodeFile(f.getPath()):null;if(b!=null)photos.put(id,b);}}
        if(b!=null){box.addView(roundImage(b),new FrameLayout.LayoutParams(-1,-1));return box;}
        box.setBackground(oval(PairedProtocol.color(p==null?"":p.optString("avatarHexColor"),Avatars.fallbackColor(name))));
        TextView letter=text(PairedProtocol.initial(name),Math.round(size*0.42f),Color.WHITE,false);letter.setGravity(Gravity.CENTER);box.addView(letter,new FrameLayout.LayoutParams(-1,-1));return box;}
    /** Photos for everyone in a group, for the details screen and reaction lists. */
    private void requestMemberPhotos(JSONObject c,Runnable after){
        if(!PairedService.ready())return;List<String> ids=new ArrayList<>();
        for(JSONObject p:PairedProtocol.members(c)){String id=PairedProtocol.participantID(p);if(id.isEmpty()||photosRequested.contains(id)||Avatars.file(this,id).isFile())continue;photosRequested.add(id);ids.add(id);if(ids.size()==25)break;}
        if(!ids.isEmpty())PairedService.loadPhotos(ids,(v,e)->{if(e.isEmpty())after.run();});}
    /** Full-size photos from the phone's contacts (when allowed), looked up in the background. */
    private final Set<String> phoneChecked=new HashSet<>();
    private void phonePhotos(List<JSONObject> chats){if(!Avatars.canUsePhonePhotos(this))return;List<String[]> todo=new ArrayList<>();
        for(JSONObject c:chats)for(JSONObject p:PairedProtocol.members(c)){String id=PairedProtocol.participantID(p),n=PairedProtocol.number(p);if(!id.isEmpty()&&!n.isEmpty()&&phoneChecked.add(id))todo.add(new String[]{id,n});}
        if(todo.isEmpty())return;
        decoder.execute(()->{boolean changed=false;for(String[] t:todo)changed|=Avatars.fromPhone(this,t[0],t[1]);if(!changed)return;
            ui.post(()->{photos.clear();if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat"))renderMessages(false);});});}
    private void askContactPhotos(){android.content.SharedPreferences prefs=getSharedPreferences("paired",MODE_PRIVATE);
        if(Avatars.canUsePhonePhotos(this)||prefs.getBoolean("asked_contacts",false))return;prefs.edit().putBoolean("asked_contacts",true).apply();
        new AlertDialog.Builder(this).setTitle("Sharper contact photos?").setMessage("Google only sends small photos. Message Center can use the full-size photos from your phone's contacts for chats, bubbles, notifications, and the widget. They stay on this phone.")
            .setPositiveButton("Allow",(d,w)->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},79)).setNegativeButton("Not now",null).show();}
    /** Requests contact photos once per launch for one-to-one chats that do not have one cached. */
    private void requestPhotos(List<JSONObject> chats){
        phonePhotos(chats);
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
        if(folder!=1){unknownOnly=false;promoOnly=false;}
        LinearLayout bar=row();TextView title=text(folder==2?"Archived":folder==3?"Blocked & spam":unknownOnly?"Unknown senders":promoOnly?"Promotions":"Messages",26,ink,false);title.setPadding(dp(folder!=1||unknownOnly||promoOnly?8:4),0,0,0);
        if(folder!=1||unknownOnly||promoOnly)bar.addView(icon(R.drawable.ic_back,ink,"Back",()->{folder=1;unknownOnly=false;promoOnly=false;inbox();}));
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
        // Search filters: tap to add or remove; they work with or without words.
        searchChips=row();HorizontalScrollView chipScroll=new HorizontalScrollView(this);chipScroll.setHorizontalScrollBarEnabled(false);chipScroll.addView(searchChips);chipScroll.setPadding(0,dp(6),0,dp(2));
        searchRow.setOrientation(LinearLayout.VERTICAL);searchRow.addView(chipScroll);
        String[][] filters={{"Photos","has:photo"},{"Links","has:link"},{"Last 7 days","last:7d"},{"Last 30 days","last:30d"},{"From…","from:"}};
        for(String[] f:filters){TextView chip=text(f[0],14,ink,false);chip.setPadding(dp(14),dp(8),dp(14),dp(8));chip.setBackground(shape(surface,18));
            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-2,-2);cl.setMarginEnd(dp(8));searchChips.addView(chip,cl);
            chip.setOnClickListener(v->{if(f[1].equals("from:")){pickFromFilter(search);return;}String t=search.getText().toString();
                String next=t.contains(f[1])?t.replace(f[1],"").replaceAll("\\s+"," ").trim():(t.replaceAll("last:\\d+d","").trim()+" "+f[1]).trim();search.setText(next);search.setSelection(next.length());});}
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
        m.add(0,1,0,folder==1?"Archived":"Inbox");if(folder==1)m.add(0,7,0,"Mark all as read");if(folder==1){m.add(0,4,1,"Blocked & spam");if(!unknownOnly)m.add(0,6,1,"Unknown senders");}m.add(0,5,2,"Starred messages");m.add(0,2,3,"Refresh");m.add(0,3,4,"Settings");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 1->{folder=folder==1?2:1;unknownOnly=false;promoOnly=false;inbox();}case 4->{folder=3;inbox();}case 5->starredScreen();case 6->{unknownOnly=true;inbox();}case 7->markAllRead();case 2->refreshInbox(true);case 3->settings();default->{}}return true;});menu.show();}
    private void refreshInbox(boolean force){if(!PairedService.ready()||busy)return;long now=System.currentTimeMillis();if(!force&&now-lastInboxRefresh<30_000)return;lastInboxRefresh=now;loadChats(false);}
    private void loadOlderChats(){if(busy||!PairedService.ready())return;if(store.page("folder:"+folder,false).isEmpty())return;loadChats(true);}
    private void renderInbox(){if(items==null||!screen.equals("inbox"))return;items.removeAllViews();
        SmartText.Search filtered=SmartText.parseSearch(query);boolean filtering=SmartText.hasFilters(filtered);
        List<JSONObject> chats=filtering?new ArrayList<>():InboxOrder.sort(store.chats(query,folder),chatSettings::pinned);
        chats.removeIf(c->pendingDeletes.contains(c.optString("conversationID")));
        if(unknownOnly){List<JSONObject> unknown=new ArrayList<>();for(JSONObject c:chats)if(unknownSender(c))unknown.add(c);chats=unknown;}
        // Promotions (texts from short codes like 28302) get their own list, unless you turned that off.
        List<JSONObject> promos=new ArrayList<>();
        if(promoOnly){List<JSONObject> only=new ArrayList<>();for(JSONObject c:chats)if(promotion(c))only.add(c);chats=only;}
        else if(folder==1&&!unknownOnly&&query.trim().isEmpty()&&promotionsSeparate()){for(JSONObject c:chats)if(promotion(c))promos.add(c);chats.removeAll(promos);}
        if(chats.isEmpty()&&promos.isEmpty()&&!filtering){TextView empty=text(PairedService.ready()?(query.isEmpty()?(promoOnly?"No promotions.":"No conversations here yet."):"No chats with that name."):"Your conversations will appear once the connection is ready.",15,muted,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(empty);}
        if(autoReplyActive())items.addView(autoReplyBanner());
        // Pinned chats sit in a grid at the top (three across), like iPhone Messages; the rest are rows.
        boolean grid=folder==1&&!unknownOnly&&query.trim().isEmpty();List<JSONObject> pins=new ArrayList<>();
        if(grid)for(JSONObject c:chats)if(chatSettings.pinned(c))pins.add(c);
        if(!pins.isEmpty())items.addView(pinnedGrid(pins));
        if(!promos.isEmpty())items.addView(promotionsRow(promos));
        for(JSONObject c:chats)if(pins.isEmpty()||!chatSettings.pinned(c))items.addView(swipeableChat(c));
        renderMessageHits();
        String note=store.page("folder:"+folder,true);if(!chats.isEmpty()&&store.page("folder:"+folder,false).isEmpty()&&!note.isEmpty()){TextView end=text(note,12,muted,false);end.setGravity(Gravity.CENTER);end.setPadding(dp(16),dp(16),dp(16),dp(8));items.addView(end);}
        requestPhotos(chats);
    }
    private View inboxRow(JSONObject c){
        String id=c.optString("conversationID");boolean unread=chatSettings.unread(c),pinned=chatSettings.pinned(c);String draft=drafts.getOrDefault(id,"").trim();
        LinearLayout entry=row();entry.setPadding(dp(4),dp(9),dp(4),dp(9));entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setBackground(ripple());
        entry.addView(avatar(c,46));
        LinearLayout middle=vertical();middle.setPadding(dp(14),0,dp(8),0);
        TextView title=text(PairedProtocol.title(c),16,ink,unread);title.setTextSize(16.5f*textScale);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);middle.addView(title);
        String snippet=ChatText.withoutMediaEmoji(PairedProtocol.string(c.optJSONObject("latestMessage"),"displayContent"));boolean[] mine={PairedProtocol.latestFromMe(c)};
        // Google leaves the preview empty for some events (drafts, some reactions); fall back to the newest synced message.
        if(snippet.isEmpty()){JSONObject last=latestStored(id);if(last!=null)snippet=previewOf(last,c,mine);}
        boolean placeholder=snippet.isEmpty()&&draft.isEmpty();if(placeholder){snippet="Open to load messages";mine[0]=false;}
        if(!draft.isEmpty())snippet="You: "+draft;else if(mine[0]&&!snippet.isEmpty())snippet="You: "+snippet;
        TextView preview=text(snippet,14,unread?ink:muted,unread);preview.setTextSize(14*textScale);preview.setMaxLines(2);preview.setEllipsize(TextUtils.TruncateAt.END);
        if(!draft.isEmpty()||placeholder)preview.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.ITALIC));
        gap(middle,2);middle.addView(preview);entry.addView(middle,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout side=vertical();side.setGravity(Gravity.END|Gravity.TOP);
        long when=PairedProtocol.time(c);TextView stamp=text(!draft.isEmpty()?"Draft":(when>0?inboxStamp(when):""),12,unread?ink:muted,unread);
        if(!draft.isEmpty())stamp.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.ITALIC));
        // The time keeps its own width; a match-parent child here collapsed the column to the pin's width and wrapped the time.
        stamp.setSingleLine(true);LinearLayout.LayoutParams st=new LinearLayout.LayoutParams(-2,-2);st.gravity=Gravity.END;side.addView(stamp,st);
        if(chatSettings.muted(id)||chatSettings.snoozed(id)){boolean z=!chatSettings.muted(id);TextView quiet=text(z?"💤":"🔕",12,muted,false);quiet.setContentDescription(z?"Snoozed":"Muted");LinearLayout.LayoutParams ql=new LinearLayout.LayoutParams(-2,-2);ql.gravity=Gravity.END;ql.topMargin=dp(6);side.addView(quiet,ql);}
        if(pinned){ImageView pin=new ImageView(this);pin.setImageResource(R.drawable.ic_pin);pin.setImageTintList(ColorStateList.valueOf(muted));pin.setContentDescription("Pinned");
            LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(dp(18),dp(18));pl.gravity=Gravity.END;pl.topMargin=dp(10);side.addView(pin,pl);}
        else if(unread){View dot=new View(this);dot.setBackground(oval(ChatTheme.of("default").accent(dark)));LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(dp(10),dp(10));dl.gravity=Gravity.END;dl.topMargin=dp(12);side.addView(dot,dl);}
        entry.addView(side,new LinearLayout.LayoutParams(-2,-1));
        entry.setOnClickListener(v->thread(id));entry.setOnLongClickListener(v->{chatOptions(c);return true;});
        return entry;
    }
    private View pinnedGrid(List<JSONObject> pins){LinearLayout grid=vertical();grid.setPadding(0,dp(4),0,dp(10));
        for(int i=0;i<pins.size();i+=3){LinearLayout line=row();line.setGravity(Gravity.TOP);
            for(int k=i;k<i+3;k++){if(k<pins.size())line.addView(pinnedCell(pins.get(k)),new LinearLayout.LayoutParams(0,-2,1));else line.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));}
            grid.addView(line);}
        View rule=new View(this);rule.setBackgroundColor((muted&0x00ffffff)|0x26000000);LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(-1,1);rl.setMargins(dp(8),dp(10),dp(8),0);grid.addView(rule,rl);
        return grid;}
    private View pinnedCell(JSONObject c){String id=c.optString("conversationID");boolean unread=chatSettings.unread(c),quiet=chatSettings.muted(id)||chatSettings.snoozed(id);
        LinearLayout cell=vertical();cell.setGravity(Gravity.CENTER_HORIZONTAL);cell.setPadding(dp(4),dp(10),dp(4),dp(8));cell.setBackground(ripple());
        FrameLayout face=new FrameLayout(this);face.addView(avatar(c,74),new FrameLayout.LayoutParams(dp(74),dp(74)));
        if(unread){View dot=new View(this);GradientDrawable d=oval(ChatTheme.of("default").accent(dark));d.setStroke(dp(3),bg);dot.setBackground(d);
            FrameLayout.LayoutParams dl=new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.TOP|Gravity.END);face.addView(dot,dl);}
        else if(quiet){TextView q=text(chatSettings.muted(id)?"🔕":"💤",11,muted,false);q.setGravity(Gravity.CENTER);q.setBackground(oval(card));face.addView(q,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.BOTTOM|Gravity.END));}
        LinearLayout.LayoutParams fl=new LinearLayout.LayoutParams(dp(74),dp(74));cell.addView(face,fl);
        String title=PairedProtocol.title(c);boolean group=PairedProtocol.other(c)==null;String shown=group?title:ChatText.firstName(title);if(shown.isEmpty())shown=title;
        TextView name=text(shown,13,unread?ink:muted,unread);name.setTextSize(13*textScale);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(6),0,0);
        cell.addView(name,new LinearLayout.LayoutParams(-1,-2));
        cell.setContentDescription(title+(unread?", unread":"")+", pinned");cell.setOnClickListener(v->thread(id));cell.setOnLongClickListener(v->{chatOptions(c);return true;});
        return cell;}
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
            labels.add(chatSettings.snoozed(id)?"Snoozed until "+snoozeLabel(id)+"…":"Snooze notifications…");actions.add(()->snoozeMenu(id));
            labels.add(archived?"Unarchive":"Archive");actions.add(()->archive(c,!archived));
            labels.add("Chat theme…");actions.add(()->chooseTheme(id));
            labels.add(receipts?"Turn off read receipts for this chat":"Turn on read receipts for this chat");actions.add(()->toggleReceipts(id));
            labels.add(group?"Group details":"Details");actions.add(()->{chatId=id;details();});
            labels.add("Open as bubble");actions.add(()->openBubble(id));}
        labels.add(blocked?"Unblock":"Block…");actions.add(()->block(c));
        labels.add("Delete conversation…");actions.add(()->deleteChat(c));
        menuSheet(PairedProtocol.title(c),labels,actions);}
    private void openBubble(String id){String problem=PairedService.showBubble(this,id);if(!problem.isEmpty())toast(problem);}
    private void togglePin(JSONObject c){String id=c.optString("conversationID");boolean now=!chatSettings.pinned(c);chatSettings.setPinned(id,now);decoder.execute(()->Nudges.reschedule(this));
        toast(now?"Pinned to the top of Message Center":"Unpinned");if(screen.equals("inbox"))renderInbox();}
    private void chooseTheme(String id){
        LinearLayout list=vertical();Dialog[] dialog=new Dialog[1];TextView heading=text("Chat theme",18,ink,true);heading.setPadding(dp(8),0,dp(8),dp(6));list.addView(heading);
        String current=chatSettings.theme(id).key;
        for(ChatTheme t:ChatTheme.ALL){LinearLayout r=row();r.setPadding(dp(16),dp(10),dp(16),dp(10));r.setBackground(ripple());
            View swatch=new View(this);GradientDrawable sw=oval(t.bubble(dark));sw.setStroke(dp(3),t.accent(dark));swatch.setBackground(sw);r.addView(swatch,new LinearLayout.LayoutParams(dp(32),dp(32)));
            TextView label=text(t.label,16,ink,t.key.equals(current));label.setPadding(dp(16),0,0,0);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if(t.key.equals(current))r.addView(text("✓",18,t.accent(dark),true));
            r.setOnClickListener(v->{chatSettings.setTheme(id,t.key);dialog[0].dismiss();if(screen.equals("chat")&&chatId.equals(id))thread(id);else if(screen.equals("details"))details();});list.addView(r);}
        TextView bgHeading=text("Background",18,ink,true);bgHeading.setPadding(dp(8),dp(14),dp(8),dp(8));list.addView(bgHeading);
        HorizontalScrollView strip=new HorizontalScrollView(this);strip.setHorizontalScrollBarEnabled(false);LinearLayout tiles=row();tiles.setPadding(dp(8),0,dp(8),dp(8));strip.addView(tiles);list.addView(strip);
        String currentBg=chatSettings.background(id);
        tiles.addView(backgroundTile(null,"None",currentBg.isEmpty(),()->{dialog[0].dismiss();setChatBackground(id,"");}));
        for(String[] stock:STOCK_BACKGROUNDS){String value="stock:"+stock[0];tiles.addView(backgroundTile(stock[0],stock[1],currentBg.equals(value),()->{dialog[0].dismiss();setChatBackground(id,value);}));}
        tiles.addView(backgroundTile("photo:"+id,"Your photo…",currentBg.equals("photo"),()->{dialog[0].dismiss();pickBackgroundPhoto(id);}));
        dialog[0]=sheet(list);}
    // ---- Chat backgrounds ----
    /** Built-in backgrounds (made for this app; no outside images): resource name, label. */
    private static final String[][] STOCK_BACKGROUNDS={{"dusk","Dusk"},{"forest","Misty forest"},{"ocean","Ocean"},{"aurora","Aurora"},{"bokeh","City lights"},{"stars","Starry night"}};
    private static final Map<String,Bitmap> backgroundCache=new LinkedHashMap<>();private static final int PICK_BACKGROUND=94;private String backgroundFor="";
    private File backgroundFile(String id){return new File(new File(getFilesDir(),"backgrounds"),MediaCache.hash(id)+".jpg");}
    private int stockRes(String name){return getResources().getIdentifier("chat_bg_"+name,"drawable",getPackageName());}
    /** The picture behind a chat's messages, dimmed a little so timestamps and names stay readable. */
    private void addChatBackground(FrameLayout frame,String id){String value=chatSettings.background(id);if(value.isEmpty())return;
        ImageView bg=new ImageView(this);bg.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(bg,new FrameLayout.LayoutParams(-1,-1));
        View scrim=new View(this);scrim.setBackgroundColor(dark?0x66000000:0x80ffffff);frame.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        File photo=backgroundFile(id);String key=value.equals("photo")?value+":"+id+":"+photo.lastModified():value;
        synchronized(backgroundCache){Bitmap hit=backgroundCache.get(key);if(hit!=null){bg.setImageBitmap(hit);return;}}
        int w=getResources().getDisplayMetrics().widthPixels,h=getResources().getDisplayMetrics().heightPixels;
        decoder.execute(()->{Bitmap b=null;
            try{if(value.startsWith("stock:")){int res=stockRes(value.substring(6));if(res!=0)b=BitmapFactory.decodeResource(getResources(),res);}
                else if(value.equals("photo")&&photo.isFile())b=MediaCache.decodeToCover(photo,w,h,false);}catch(Throwable ignored){}
            if(b==null)return;Bitmap done=b;synchronized(backgroundCache){backgroundCache.put(key,done);while(backgroundCache.size()>2)backgroundCache.remove(backgroundCache.keySet().iterator().next());}
            bg.post(()->bg.setImageBitmap(done));});}
    private View backgroundTile(String source,String label,boolean current,Runnable pick){LinearLayout tile=vertical();tile.setGravity(Gravity.CENTER_HORIZONTAL);tile.setPadding(dp(4),0,dp(4),0);
        FrameLayout box=new FrameLayout(this);GradientDrawable frameShape=shape(surface,14);if(current)frameShape.setStroke(dp(3),BLUE);box.setBackground(frameShape);box.setPadding(dp(3),dp(3),dp(3),dp(3));
        ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(11));}});img.setClipToOutline(true);box.addView(img,new FrameLayout.LayoutParams(-1,-1));
        if(source==null){TextView none=text("∅",22,muted,false);none.setGravity(Gravity.CENTER);box.addView(none,new FrameLayout.LayoutParams(-1,-1));}
        else if(source.startsWith("photo:")){File f=backgroundFile(source.substring(6));if(f.isFile())decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(f,dp(64),dp(112),false);if(b!=null)img.post(()->img.setImageBitmap(b));});
            else{TextView plus=text("+",26,muted,false);plus.setGravity(Gravity.CENTER);box.addView(plus,new FrameLayout.LayoutParams(-1,-1));}}
        else{int res=stockRes(source);if(res!=0)decoder.execute(()->{BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=8;Bitmap b=BitmapFactory.decodeResource(getResources(),res,o);if(b!=null)img.post(()->img.setImageBitmap(b));});}
        tile.addView(box,new LinearLayout.LayoutParams(dp(70),dp(118)));TextView name=text(label,12,current?ink:muted,current);name.setPadding(0,dp(4),0,0);tile.addView(name);
        tile.setContentDescription("Background: "+label+(current?", selected":""));tile.setOnClickListener(v->pick.run());return tile;}
    private void setChatBackground(String id,String value){chatSettings.setBackground(id,value);if(!value.equals("photo")){File f=backgroundFile(id);if(f.isFile())f.delete();}
        if(screen.equals("chat")&&chatId.equals(id))thread(id);else if(screen.equals("details"))details();}
    private void pickBackgroundPhoto(String id){backgroundFor=id;try{startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"),PICK_BACKGROUND);}
        catch(Exception e){try{startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"),PICK_BACKGROUND);}catch(Exception e2){toast("No photo picker is available.");}}}
    /** Keeps a screen-sized copy of the chosen photo, so the original can be moved or deleted. */
    private void saveBackgroundPhoto(Uri uri){String id=backgroundFor;if(id.isEmpty())return;toast("Setting background…");
        decoder.execute(()->{boolean ok=false;File target=backgroundFile(id);
            try{android.graphics.ImageDecoder.Source src=android.graphics.ImageDecoder.createSource(getContentResolver(),uri);
                Bitmap b=android.graphics.ImageDecoder.decodeBitmap(src,(dec,info,s2)->{int lw=info.getSize().getWidth(),lh=info.getSize().getHeight();float k=Math.min(1f,1800f/Math.max(lw,lh));dec.setTargetSize(Math.max(1,Math.round(lw*k)),Math.max(1,Math.round(lh*k)));dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);});
                File dir=target.getParentFile();if(!dir.isDirectory())dir.mkdirs();File part=new File(target.getPath()+".part");
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(part)){b.compress(Bitmap.CompressFormat.JPEG,88,out);}ok=part.renameTo(target);}catch(Exception ignored){}
            boolean saved=ok;ui.post(()->{if(!saved){toast("That photo couldn't be used.");return;}setChatBackground(id,"photo");});});}
    private void toggleReceipts(String id){boolean on=!chatSettings.receipts(id);chatSettings.setReceipts(id,on);
        if(!on){new ReadReceiptQueue(this).cancel(id);toast("Read receipts off for this chat.");}
        else {JSONObject latest=store.latestIncoming(id),chat=store.chat(id);
            if(latest!=null&&chatSettings.seen(id)>=PairedProtocol.time(latest))PairedService.viewedForReceipts(this,chat,latest,true);
            PairedService.refreshReceiptSettings();
            if(chatSettings.receiptAvailability()==0)new AlertDialog.Builder(this).setTitle("Google Messages setting required")
                .setMessage("This chat is enabled here. In Google Messages → Messages settings → RCS chats, turn on RCS and Send read receipts. Message Center cannot override Google's master switch.\n\nMessage Center only requests receipts for chats you enable here. Opening a chat in Google Messages itself follows Google's setting.")
                .setPositiveButton("Open Google Messages",(d,w)->openGoogle()).setNegativeButton("Close",null).show();
            else toast("Read receipts enabled. Viewed messages will be requested, including after reconnection.");
            markReadIfAllowed();}
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
        if(SmartText.shortCodeSender(number)){TextView stop=text("Unsubscribe",14,ink,true);stop.setPadding(dp(12),dp(8),dp(12),dp(8));stop.setBackground(shape(surface,16));stop.setOnClickListener(v->unsubscribe(id,c));
            LinearLayout.LayoutParams ul=new LinearLayout.LayoutParams(-2,-2);ul.gravity=Gravity.CENTER_VERTICAL;ul.setMarginEnd(dp(4));bar.addView(stop,ul);}
        else if(!number.isEmpty())bar.addView(icon(R.drawable.ic_call,ink,"Call",()->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));}catch(Exception e){toast("No phone app is available.");}}));
        ImageView more=icon(R.drawable.ic_more,ink,"More options",null);more.setBackground(ripple());more.setOnClickListener(this::chatMenu);bar.addView(more);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));connection();
        scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);items=vertical();items.setPadding(dp(12),dp(8),dp(12),dp(8));scroll.addView(items);
        chatFrame=new FrameLayout(this);addChatBackground(chatFrame,id);chatFrame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));root.addView(chatFrame,new LinearLayout.LayoutParams(-1,0,1));
        jumpButton=text("↓",15,ink,true);jumpButton.setPadding(dp(18),dp(10),dp(18),dp(10));jumpButton.setBackground(shape(card,22));jumpButton.setElevation(dp(4));jumpButton.setVisibility(View.GONE);
        jumpButton.setOnClickListener(v->jumpToLatest());FrameLayout.LayoutParams jl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);jl.setMargins(0,0,dp(16),dp(12));chatFrame.addView(jumpButton,jl);
        newWhileAway=0;newestSeenTime=0;
        // Chats never animate on their own; only an explicit jump (search, reply quote) scrolls smoothly.
        scroll.setSmoothScrollingEnabled(false);pinnedBottom=true;
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{View ch=scroll.getChildAt(0);pinnedBottom=ch!=null&&ch.getHeight()-scroll.getHeight()-y<dp(80);updateJumpButton();if(y==0&&oy>0)loadOlderMessages();});
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
        quickRow=row();quickScroll=new HorizontalScrollView(this);quickScroll.setHorizontalScrollBarEnabled(false);quickScroll.setPadding(dp(12),dp(2),dp(12),dp(4));quickScroll.addView(quickRow);quickScroll.setVisibility(View.GONE);root.addView(quickScroll);
        LinearLayout compose=row();compose.setPadding(dp(4),dp(4),dp(8),0);composeRow=compose;
        compose.addView(icon(R.drawable.ic_add,ink,"Attach photos, videos, or files",this::attach));
        composer=new EditText(this);composer.setHint(PairedProtocol.rcs(c)?"RCS message":"Text message");composer.setTextColor(ink);composer.setHintTextColor(muted);composer.setTextSize(17*textScale);
        composer.setBackground(shape(surface,28));composer.setPadding(dp(22),dp(14),dp(22),dp(14));composer.setMaxLines(5);
        composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setText(drafts.getOrDefault(id,""));
        composer.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){maybeSendTyping(s);updateComposeButton();updateQuickRow();}public void afterTextChanged(Editable e){}});
        composer.setOnFocusChangeListener((v,focused)->updateQuickRow());
        composer.setOnReceiveContentListener(new String[]{"image/*","video/*"},keyboardContent);
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        sendButton=new ImageView(this);sendButton.setImageResource(R.drawable.ic_send);sendButton.setImageTintList(ColorStateList.valueOf(theme.onAccent(dark)));sendButton.setBackground(oval(theme.accent(dark)));
        sendButton.setPadding(dp(14),dp(14),dp(14),dp(14));sendButton.setContentDescription("Send");sendButton.setOnClickListener(v->sendOrRecord());sendButton.setOnLongClickListener(v->{scheduleMessage();return true;});
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(dp(52),dp(52));sl.setMarginStart(dp(8));compose.addView(sendButton,sl);root.addView(compose);renderAttachments();updateComposeButton();applyIncoming();
        chatSettings.setMarkedUnread(id,false);if(!bubble())PairedService.clearNotification(this,id);renderMessages(true);if(PairedService.ready())loadMessages(false);markReadIfAllowed();updateTyping();
        if(c!=null)requestPhotos(java.util.Collections.singletonList(c));
    }
    private void chatMenu(View anchor){JSONObject c=store.chat(chatId);if(c==null)return;PopupMenu menu=new PopupMenu(this,anchor);Menu m=menu.getMenu();
        m.add(0,1,0,chatSettings.pinned(c)?"Unpin":"Pin to top");m.add(0,2,1,"Theme & background");
        m.add(0,3,2,"Read receipts for this chat").setCheckable(true).setChecked(chatSettings.receipts(chatId));
        m.add(0,6,0,PairedProtocol.other(c)==null?"Group details":"Details");m.add(0,9,0,"Search in chat");m.add(0,10,4,chatSettings.muted(chatId)?"Unmute notifications":"Mute notifications");m.add(0,14,4,chatSettings.snoozed(chatId)?"Snoozed until "+snoozeLabel(chatId)+"…":"Snooze notifications…");
        boolean archived=c.optString("status").contains("ARCHIVED"),blocked=c.optString("status").equals("BLOCKED_FOLDER")||c.optString("status").equals("SPAM_FOLDER");
        if(!bubble()){m.add(0,11,8,archived?"Unarchive":"Archive");m.add(0,12,9,blocked?"Unblock":"Block…");m.add(0,13,10,"Delete conversation…");}
        if(bubble())m.add(0,8,5,"Open in Message Center");else m.add(0,7,5,"Open as bubble");
        m.add(0,4,6,"Refresh");m.add(0,5,7,"Load older messages");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 1->togglePin(c);case 2->chooseTheme(chatId);case 3->toggleReceipts(chatId);case 4->loadMessages(false);case 5->loadOlderMessages();
            case 6->details();case 7->openBubble(chatId);case 8->startActivity(new Intent(this,PairedActivity.class).setAction(OPEN_CHAT).putExtra("chat",chatId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP));
            case 9->searchChat();case 10->toggleMute(chatId);case 11->archive(c,!archived);case 12->block(c);case 13->deleteChat(c);case 14->snoozeMenu(chatId);default->{}}return true;});menu.show();}
    private void renderMessages(boolean bottom){if(items==null||!screen.equals("chat"))return;
        int y=scroll.getScrollY();View content=scroll.getChildAt(0);int before=content.getHeight();
        boolean atBottom=bottom||before-scroll.getHeight()-y<dp(80);items.removeAllViews();
        JSONObject c=store.chat(chatId);ChatTheme theme=chatSettings.theme(chatId);boolean group=c!=null&&PairedProtocol.other(c)==null;
        List<JSONObject> messages=new ArrayList<>();for(JSONObject m:store.messages(chatId,limit))if(!PairedProtocol.body(m).isEmpty()||!PairedProtocol.event(m,c).isEmpty())messages.add(m);
        if(messages.size()>=limit-5)items.addView(button("Show more saved messages",false,()->{limit+=300;renderMessages(false);}));
        if(messages.isEmpty()){TextView empty=text(PairedService.ready()?"No messages have synced for this conversation yet.":"Messages will appear once the connection is ready.",14,muted,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(empty);}
        else if(store.page("messages:"+chatId,false).isEmpty()&&!store.page("messages:"+chatId,true).isEmpty()){TextView end=text(store.page("messages:"+chatId,true),12,muted,false);end.setGravity(Gravity.CENTER);end.setPadding(dp(16),dp(8),dp(16),dp(8));items.addView(end);}
        int n=messages.size();boolean[] out=new boolean[n],replyTo=new boolean[n],reacted=new boolean[n];String[] who=new String[n],line=new String[n],ev=new String[n];long[] at=new long[n];
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);out[i]=PairedProtocol.outgoing(m,c);who[i]=out[i]?"":m.optString("participantID");at[i]=PairedProtocol.time(m);
            line[i]=ChatText.reactionLine(out[i]?"You":ChatText.firstName(senderName(m,c)),PairedProtocol.body(m));
            ev[i]=PairedProtocol.event(m,c);if(!ev[i].isEmpty())line[i]=ev[i];
            replyTo[i]=ReplyReferences.isReply(m);
            reacted[i]=!PairedProtocol.reactors(m).isEmpty();}
        int lastOut=-1;for(int i=n-1;i>=0;i--)if(out[i]&&line[i]==null){lastOut=i;break;}
        // Link previews open the page, which tells its owner you looked; only do that for people you know or have written to.
        boolean wrote=false;for(boolean o:out)wrote|=o;chatTrusted=c!=null&&(PairedProtocol.allKnown(c)||wrote);
        TimeZone zone=TimeZone.getDefault();
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);
            boolean sep=InboxOrder.separator(i==0?0:at[i-1],at[i],zone);
            if(sep){TextView s=text(separatorStamp(at[i]),13,muted,false);s.setGravity(Gravity.CENTER);s.setPadding(0,dp(i==0?8:20),0,dp(10));items.addView(s,new LinearLayout.LayoutParams(-1,-2));}
            // Group and chat events ("Sam left", "Now end-to-end encrypted") are centered notes.
            if(!ev[i].isEmpty()){if(!sep&&i>0)gap(items,8);TextView t=text(ev[i],12,muted,false);t.setGravity(Gravity.CENTER);t.setPadding(dp(24),dp(4),dp(24),dp(4));items.addView(t,new LinearLayout.LayoutParams(-1,-2));continue;}
            // iPhone reactions sent as text ("Loved an image") become one quiet line instead of a bubble.
            if(line[i]!=null){if(!sep&&i>0)gap(items,8);TextView t=text(line[i],13,muted,false);t.setGravity(out[i]?Gravity.END:Gravity.START);t.setPadding(dp(14),dp(2),dp(14),dp(2));items.addView(t,new LinearLayout.LayoutParams(-1,-2));continue;}
            boolean joinPrev=!sep&&i>0&&line[i-1]==null&&!replyTo[i]&&!reacted[i-1]&&InboxOrder.grouped(out[i-1],who[i-1],at[i-1],out[i],who[i],at[i]);
            boolean joinNext=i+1<n&&line[i+1]==null&&!replyTo[i+1]&&!reacted[i]&&!InboxOrder.separator(at[i],at[i+1],zone)&&InboxOrder.grouped(out[i],who[i],at[i],out[i+1],who[i+1],at[i+1]);
            if(!sep&&i>0)gap(items,joinPrev?3:12);
            if(group&&!out[i]&&!joinPrev&&!replyTo[i]){TextView sender=text(senderName(m,c),12,muted,false);sender.setPadding(dp(14),0,0,dp(4));items.addView(sender);}
            View b=swipeToReply(bubble(m,c,theme,out[i],joinPrev,joinNext),m);if(selecting)b=selectable(b,m);items.addView(b);messageViews.put(m.optString("messageID"),b);
            List<String[]> reactors=PairedProtocol.reactors(m);
            if(!reactors.isEmpty()){LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(-1,-2);rl.topMargin=-dp(7);items.addView(reactionPill(m,c,reactors,out[i]),rl);}
            if(!out[i]){String code=ChatText.oneTimeCode(PairedProtocol.text(m));if(!code.isEmpty())items.addView(codeChip(code,false,theme));}
            JSONObject rem=Reminders.forMessage(this,m.optString("messageID"));
            if(rem!=null){TextView rt=text("⏰ Reminder "+separatorStamp(rem.optLong("at")),11,muted,false);rt.setGravity(out[i]?Gravity.END:Gravity.START);rt.setPadding(dp(16),dp(2),dp(16),0);rt.setOnClickListener(v->remindMenu(m));items.addView(rt,new LinearLayout.LayoutParams(-1,-2));}
            if(starred.has(m.optString("messageID"))){TextView st=text("★ Starred",11,muted,false);st.setGravity(out[i]?Gravity.END:Gravity.START);st.setPadding(dp(16),dp(2),dp(16),0);items.addView(st,new LinearLayout.LayoutParams(-1,-2));}
            if(i==lastOut||(out[i]&&PairedProtocol.failed(m)))items.addView(statusRow(m,theme));
        }
        renderOutbox(theme);renderScheduled(theme);
        // Count messages that arrived while scrolled up, for the ↓ button.
        long newest=0;int arrived=0;for(int i=0;i<n;i++){newest=Math.max(newest,at[i]);if(newestSeenTime>0&&at[i]>newestSeenTime&&!out[i]&&ev[i].isEmpty())arrived++;}
        if(atBottom)newWhileAway=0;else newWhileAway+=arrived;newestSeenTime=Math.max(newestSeenTime,newest);if(atBottom)pinnedBottom=true;updateJumpButton();
        // Position before the next frame is drawn, so the chat appears already at the right place instead of sliding there.
        ScrollView sv=scroll;android.view.ViewTreeObserver.OnPreDrawListener[] once=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        once[0]=()->{sv.getViewTreeObserver().removeOnPreDrawListener(once[0]);if(scroll!=sv||sv.getChildAt(0)==null)return true;
            if(anchorOlder){anchorOlder=false;sv.scrollTo(0,y+Math.max(0,sv.getChildAt(0).getHeight()-before));}
            else if(atBottom){sv.scrollTo(0,sv.getChildAt(0).getHeight());pinnedBottom=true;}else sv.scrollTo(0,y);return true;};
        sv.getViewTreeObserver().addOnPreDrawListener(once[0]);
    }
    private View bubble(JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,boolean joinPrev,boolean joinNext){
        LinearLayout wrap=vertical();String body=PairedProtocol.text(m),fallback=ChatText.replyFallbackBody(body);List<JSONObject> media=PairedProtocol.media(m);
        String target=store.replyTarget(m);
        if(ReplyReferences.isReply(m))addReplyContext(wrap,m,c,theme,outgoing,target);
        for(int k=0;k<media.size();k++){if(k>0)gap(wrap,4);wrap.addView(mediaView(m,media.get(k),outgoing,theme),new LinearLayout.LayoutParams(-1,-2));}
        JSONObject place=PairedProtocol.location(m);if(place!=null){if(!media.isEmpty())gap(wrap,4);wrap.addView(locationCard(m,place,outgoing,theme),new LinearLayout.LayoutParams(-1,-2));}
        if((!media.isEmpty()||place!=null)&&(fallback!=null?fallback:body).isEmpty())return wrap;
        if(!media.isEmpty()||place!=null)gap(wrap,4);
        LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);
        LinearLayout b=vertical();b.setPadding(dp(16),dp(11),dp(16),dp(11));
        float big=dp(22),small=dp(5);float top=joinPrev?small:big,bottomR=joinNext?small:big;
        GradientDrawable shape=new GradientDrawable();shape.setColor(outgoing?theme.bubble(dark):surface);
        shape.setCornerRadii(outgoing?new float[]{big,big,top,top,bottomR,bottomR,big,big}:new float[]{top,top,big,big,big,big,bottomR,bottomR});b.setBackground(shape);
        TextView text=text(fallback!=null?fallback:body,17,outgoing?theme.onBubble(dark):ink,false);text.setTextSize(17*textScale);text.setLineSpacing(0,1.1f);text.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.addView(text);
        // One to three emoji alone are shown large, without a bubble.
        if(media.isEmpty()&&target.isEmpty()&&fallback==null){int jumbo=ChatText.emojiOnly(body);if(jumbo>0){text.setTextSize(jumbo==1?48:jumbo==2?40:34);b.setBackground(null);b.setPadding(dp(4),0,dp(4),0);}}
        if(Linkify.addLinks(text,Linkify.WEB_URLS|Linkify.EMAIL_ADDRESSES|Linkify.PHONE_NUMBERS)){text.setLinkTextColor(outgoing?theme.onBubble(dark):ink);text.setHighlightColor(((outgoing?theme.onBubble(dark):ink)&0x00ffffff)|0x26000000);text.setMovementMethod(LinkMovementMethod.getInstance());text.setOnLongClickListener(v->{messageSheet(m);return true;});}
        // Street addresses open in your maps app.
        List<int[]> places=ChatText.addresses(text.getText().toString());
        if(!places.isEmpty()){Spannable sp=text.getText() instanceof Spannable?(Spannable)text.getText():new SpannableString(text.getText());int linkColor=outgoing?theme.onBubble(dark):ink;
            for(int[] r:places){if(sp.getSpans(r[0],r[1],URLSpan.class).length>0)continue;String where=sp.subSequence(r[0],r[1]).toString();
                sp.setSpan(new ClickableSpan(){@Override public void onClick(View w){openPlace(where);}@Override public void updateDrawState(TextPaint ds){ds.setColor(linkColor);ds.setUnderlineText(true);}},r[0],r[1],Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            text.setText(sp);text.setHighlightColor((linkColor&0x00ffffff)|0x26000000);text.setMovementMethod(LinkMovementMethod.getInstance());text.setOnLongClickListener(v->{messageSheet(m);return true;});}
        // Tracking numbers, flights, and "Friday at 6" are tappable too.
        List<SmartText.Hit> smart=SmartText.all(text.getText().toString(),PairedProtocol.time(m)>0?PairedProtocol.time(m):System.currentTimeMillis(),java.util.TimeZone.getDefault());
        if(!smart.isEmpty()){Spannable sp=text.getText() instanceof Spannable?(Spannable)text.getText():new SpannableString(text.getText());int linkColor=outgoing?theme.onBubble(dark):ink;boolean added=false;
            for(SmartText.Hit h:smart){if(sp.getSpans(h.start,h.end,URLSpan.class).length>0||sp.getSpans(h.start,h.end,ClickableSpan.class).length>0)continue;added=true;
                sp.setSpan(new ClickableSpan(){@Override public void onClick(View w){smartTap(h,m,c);}@Override public void updateDrawState(TextPaint ds){ds.setColor(linkColor);ds.setUnderlineText(true);}},h.start,h.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            if(added){text.setText(sp);text.setHighlightColor((linkColor&0x00ffffff)|0x26000000);text.setMovementMethod(LinkMovementMethod.getInstance());text.setOnLongClickListener(v->{messageSheet(m);return true;});}}
        b.setOnLongClickListener(v->{messageSheet(m);return true;});b.setOnClickListener(v->tapped(m,b));if(text.getMovementMethod()!=null)text.setOnClickListener(v->tapped(m,b));
        line.addView(b,new LinearLayout.LayoutParams(-2,-2));wrap.addView(line,new LinearLayout.LayoutParams(-1,-2));
        String url=linkPreviewsOn()&&(outgoing||chatTrusted)?ChatText.firstUrl(fallback!=null?fallback:body):"";if(!url.isEmpty()){gap(wrap,4);wrap.addView(linkCard(url,outgoing),new LinearLayout.LayoutParams(-1,-2));}
        return wrap;
    }
    /**
     * Like iPhone: who replied to whom, the original as a faded bubble above, and a thread
     * line down to the reply. Tapping the original scrolls to it.
     */
    private void addReplyContext(LinearLayout wrap,JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,String target){
        JSONObject original=target.isEmpty()?null:store.messageByReference(chatId,target);boolean originalOut=original!=null&&PairedProtocol.outgoing(original,c);
        String from=outgoing?"You":ChatText.firstName(senderName(m,c));
        String to=original==null?"":" to "+(originalOut?(outgoing?"yourself":"you"):ChatText.firstName(senderName(original,c)));
        TextView label=text("↩ "+from+" replied"+to,12,muted,false);label.setGravity(outgoing?Gravity.END:Gravity.START);label.setPadding(dp(14),0,dp(14),dp(4));
        wrap.addView(label,new LinearLayout.LayoutParams(-1,-2));
        String quote=original!=null?displayBody(original):(target.isEmpty()?"Find original message":"Original message not loaded yet");
        LinearLayout quoteLine=row();quoteLine.setGravity(outgoing?Gravity.END:Gravity.START);
        TextView q=text(quote,14,originalOut?theme.onBubble(dark):ink,false);q.setMaxLines(2);q.setEllipsize(TextUtils.TruncateAt.END);q.setPadding(dp(14),dp(8),dp(14),dp(8));
        q.setBackground(shape(originalOut?theme.bubble(dark):surface,18));q.setAlpha(original==null?0.45f:0.6f);q.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.62f));
        if(original!=null){q.setContentDescription("Original message: "+quote+". Tap to show it.");q.setOnClickListener(v->{if(messageViews.containsKey(target))jumpTo(target);else showMessage(original);});}
        else if(!target.isEmpty()){q.setContentDescription("Original message not loaded. Tap to find it.");q.setOnClickListener(v->findOriginal(target,6));}
        else {q.setContentDescription("Find the original message for this reply");q.setOnClickListener(v->refreshReply(m));}
        if(!store.page("reply-link:"+m.optString("messageID"),false).isEmpty())q.setOnLongClickListener(v->{
            menuSheet("Original linked on this phone",java.util.Arrays.asList("Choose a different original","Remove this local link"),java.util.Arrays.<Runnable>asList(()->chooseReplyOriginal(m),()->{store.page("reply-link:"+m.optString("messageID"),"","");renderMessages(false);}));return true;});
        LinearLayout.LayoutParams ql=new LinearLayout.LayoutParams(-2,-2);if(outgoing)ql.setMarginEnd(dp(22));else ql.setMarginStart(dp(22));quoteLine.addView(q,ql);
        wrap.addView(quoteLine,new LinearLayout.LayoutParams(-1,-2));
        View thread=new View(this){final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);{paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(muted);paint.setAlpha(120);}
            @Override protected void onDraw(Canvas canvas){float x=outgoing?getWidth()-dp(36):dp(36);canvas.drawLine(x,dp(1),x,getHeight()-dp(1),paint);}};
        wrap.addView(thread,new LinearLayout.LayoutParams(-1,dp(10)));
    }
    /** Loads older history page by page (up to a limit) until a replied-to message is found, then shows it. */
    private void findOriginal(String target,int pagesLeft){if(!screen.equals("chat"))return;JSONObject o=store.messageByReference(chatId,target);if(o!=null){showMessage(o);return;}
        String id=chatId,cursor=store.page("messages:"+id,false);
        if(pagesLeft<=0||cursor.isEmpty()||!PairedService.ready()){toast("The original message isn't in the synced history.");return;}
        if(busy){toast("Still loading. Try again in a moment.");return;}if(pagesLeft==6)toast("Finding the original message…");
        busy=true;PairedService.loadMessages(id,cursor,(v,e)->{busy=false;if(!e.isEmpty()){toast(e);return;}if(screen.equals("chat")&&chatId.equals(id))findOriginal(target,pagesLeft-1);});}
    private void refreshReply(JSONObject message){
        if(busy){toast("Still loading. Try again in a moment.");return;}String id=chatId,mid=message.optString("messageID");
        if(!PairedService.ready()){unlinkedReply(message);return;}
        busy=true;toast("Refreshing this reply from Google Messages…");
        PairedService.refreshReply(id,mid,(v,e)->{busy=false;if(!screen.equals("chat")||!chatId.equals(id))return;
            JSONObject current=store.message(mid);if(current==null)current=message;
            String target=store.replyTarget(current);renderMessages(false);
            if(!target.isEmpty()){findOriginal(target,12);return;}
            if(!e.isEmpty())toast(e);unlinkedReply(current);});
    }
    private void unlinkedReply(JSONObject message){
        new AlertDialog.Builder(this).setTitle("Reply reference unavailable")
            .setMessage("Google Messages may show the original on your phone, but this paired copy does not currently contain a usable link. You can select the original from this conversation and keep that link on this phone.")
            .setPositiveButton("Choose original",(d,w)->chooseReplyOriginal(message))
            .setNeutralButton("Connection details",(d,w)->replyDiagnostics(message)).setNegativeButton("Close",null).show();
    }
    private void replyDiagnostics(JSONObject message){String report=ReplyReferences.diagnostics(message);
        new AlertDialog.Builder(this).setTitle("Reply connection details").setMessage(report+"\n\nThis report contains no message text, names, phone numbers, account details, or message IDs.")
            .setPositiveButton("Copy",(d,w)->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Reply connection details",report));toast("Connection details copied");})
            .setNegativeButton("Close",null).show();
    }
    private void chooseReplyOriginal(JSONObject reply){
        LinearLayout content=vertical();TextView heading=text("Choose the original message",18,ink,true);content.addView(heading);gap(content,8);
        TextView info=text("This selection links the reply only in Message Center on this phone.",13,muted,false);content.addView(info);gap(content,8);
        EditText search=input("Search earlier synced messages");search.setSingleLine(true);content.addView(search);gap(content,8);
        ScrollView sc=new ScrollView(this);LinearLayout choices=vertical();sc.addView(choices);content.addView(sc,new LinearLayout.LayoutParams(-1,dp(320)));
        Dialog[] dialog=new Dialog[1];Runnable show=()->{choices.removeAllViews();List<JSONObject> candidates=store.replyCandidates(reply,search.getText().toString(),100);
            for(JSONObject original:candidates){String label=(PairedProtocol.outgoing(original,store.chat(chatId))?"You":senderName(original,store.chat(chatId)))+" · "+displayBody(original);
                TextView option=text(label,15,ink,false);option.setMaxLines(3);option.setEllipsize(TextUtils.TruncateAt.END);option.setPadding(dp(12),dp(12),dp(12),dp(12));option.setBackground(ripple());
                option.setOnClickListener(v->{store.linkReply(reply,original.optString("messageID"));dialog[0].dismiss();renderMessages(false);showMessage(original);});choices.addView(option);}
            if(candidates.isEmpty())choices.addView(text("No earlier synced messages match. Load more history in the chat, then try again.",14,muted,false));};
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int n){}public void onTextChanged(CharSequence s,int a,int b,int c){show.run();}public void afterTextChanged(Editable e){}});
        show.run();dialog[0]=sheet(content);
    }
    /** A shared location: the map preview (when sent), the place, and a tap to open it in a maps app. */
    private View locationCard(JSONObject m,JSONObject place,boolean outgoing,ChatTheme theme){
        LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);int w=Math.round(getResources().getDisplayMetrics().widthPixels*0.72f);
        LinearLayout card=vertical();card.setBackground(shape(outgoing?theme.bubble(dark):surface,22));
        card.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(22));}});card.setClipToOutline(true);
        JSONObject media=place.optJSONObject("media");int fg=outgoing?theme.onBubble(dark):ink;
        if(media!=null){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);int h=Math.round(w*0.55f);card.addView(img,new LinearLayout.LayoutParams(w,h));loadPreview(media,img,false,w,h);}
        String text=place.optString("text"),label=PairedProtocol.placeLabel(text);
        TextView title=text("\uD83D\uDCCD "+(label.isEmpty()?"Shared location":label),16,fg,true);title.setPadding(dp(16),dp(12),dp(16),dp(2));title.setMaxWidth(w);card.addView(title);
        TextView open=text("Open in Maps",14,fg,false);open.setAlpha(0.75f);open.setPadding(dp(16),0,dp(16),dp(12));card.addView(open);
        card.setContentDescription("Shared location: "+(label.isEmpty()?"map":label)+". Tap to open in Maps.");
        card.setOnClickListener(v->openMap(text));card.setOnLongClickListener(v->{messageSheet(m);return true;});
        line.addView(card,new LinearLayout.LayoutParams(-2,-2));return line;}
    private void openMap(String text){String url=ChatText.firstUrl(text);String label=PairedProtocol.placeLabel(text);
        // A shared pin: open its exact spot in your maps app (any app understands geo: coordinates).
        java.util.regex.Matcher at=java.util.regex.Pattern.compile("(-?\\d{1,2}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)").matcher(url.isEmpty()?text:Uri.decode(url));
        if(at.find()){String ll=at.group(1)+","+at.group(2);openMaps(Uri.parse("geo:"+ll+"?q="+Uri.encode(ll+(label.isEmpty()?"":" ("+label+")"))),url);return;}
        if(!label.isEmpty()){openPlace(label);return;}
        if(!url.isEmpty())openMaps(null,url);}
    private void openPlace(String query){openMaps(Uri.parse("geo:0,0?q="+Uri.encode(query.replaceAll("\\s+"," ").trim())),"");}
    /** Opens in the maps app chosen in Settings; the first time, asks which one (when there's more than one). */
    private void openMaps(Uri geo,String fallbackUrl){
        if(geo==null){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(fallbackUrl)));}catch(Exception e){toast("No app can open this map link.");}return;}
        Intent view=new Intent(Intent.ACTION_VIEW,geo);String pkg=getSharedPreferences("paired",MODE_PRIVATE).getString("maps_app","");
        if(!pkg.isEmpty()&&!pkg.equals("ask")){Intent pinned=new Intent(view).setPackage(pkg);if(pinned.resolveActivity(getPackageManager())!=null){startActivity(pinned);return;}}
        List<android.content.pm.ResolveInfo> apps=mapsApps();
        if(apps.isEmpty()){if(!fallbackUrl.isEmpty())openMaps(null,fallbackUrl);else toast("No maps app is installed.");return;}
        if(apps.size()==1||pkg.equals("ask")){startActivity(apps.size()==1?new Intent(view).setPackage(apps.get(0).activityInfo.packageName):Intent.createChooser(view,"Open with"));return;}
        chooseMapsApp(chosen->{Intent pinned=new Intent(view).setPackage(chosen);try{startActivity(pinned);}catch(Exception e){toast("That maps app couldn't open this place.");}});}
    private List<android.content.pm.ResolveInfo> mapsApps(){return getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0?q=coffee")),0);}
    private void chooseMapsApp(java.util.function.Consumer<String> then){List<android.content.pm.ResolveInfo> apps=mapsApps();if(apps.isEmpty()){toast("No maps app is installed.");return;}
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(android.content.pm.ResolveInfo r:apps){String pkg=r.activityInfo.packageName;labels.add(String.valueOf(r.loadLabel(getPackageManager())));
            actions.add(()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putString("maps_app",pkg).apply();if(then!=null)then.accept(pkg);else if(screen.equals("settings"))settings();});}
        labels.add("Ask every time");actions.add(()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putString("maps_app","ask").apply();if(screen.equals("settings"))settings();});
        menuSheet("Open places in",labels,actions);}
    private String mapsAppLabel(){String pkg=getSharedPreferences("paired",MODE_PRIVATE).getString("maps_app","");if(pkg.isEmpty())return "Ask the first time";if(pkg.equals("ask"))return "Ask every time";
        try{return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)));}catch(Exception e){return "Ask the first time";}}
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
        if(!hasWindowFocus())return;
        JSONObject c=store.chat(id),latest=store.latestIncoming(id);
        long seenAt=Math.max(c==null?0:PairedProtocol.time(c),latest==null?0:PairedProtocol.time(latest));if(seenAt>0)chatSettings.markSeen(id,seenAt);
        if(latest==null||!chatSettings.receipts(id))return;
        PairedService.viewedForReceipts(this,c,latest,false);}
    private void updateReceiptStatus(){if(receiptStatusView==null||chatId.isEmpty()||chatSettings==null)return;
        JSONObject entry=new ReadReceiptQueue(this).get(chatId);int google=chatSettings.receiptAvailability();
        String state=ReadReceiptQueue.state(entry,chatSettings.receipts(chatId),PairedService.ready(),google);
        String value=switch(state){
            case "off" -> "No read requests are sent for this chat.";
            case "no_message" -> "Enable this chat, then view an incoming message. Its read request status will appear here.";
            case "sms" -> "The last viewed incoming message is SMS/MMS. Sender read receipts require RCS.";
            case "google_off" -> chatSettings.phoneRcs()==0?"Blocked: Google Messages reports RCS is off. Enable RCS and Send read receipts there.":"Blocked: Google's Send read receipts setting is off. Enable it in Google Messages → Messages settings → RCS chats.";
            case "waiting" -> "Viewed message saved. Its read request will retry when the connection returns.";
            case "retrying" -> "Read request failed and is queued for retry. "+entry.optString("error");
            case "accepted" -> "Read request accepted by Google Messages at "+DateFormat.getTimeFormat(this).format(new Date(entry.optLong("requestedAt")))+". This is not confirmation that the other phone displayed it."+(google<0?" Google's master setting has not been reported; check Send read receipts in Google Messages.":"");
            default -> "Read request queued for Google Messages.";};
        receiptStatusView.setText(value);
    }
    private void send(){if(busy||composer==null)return;String body=composer.getText().toString(),id=chatId,reply=replyId;JSONObject c=store.chat(id);
        boolean files=false;for(Outgoing o:outgoing)if(!o.sending)files=true;
        if(body.trim().isEmpty()&&!files)return;if(c==null){error("Refresh this conversation before sending.");return;}
        if(files){sendAttachments(c,body.trim(),reply);return;}
        if(undoSendOn()){JSONObject held=outbox.add(id,body,reply,UNDO_SEND_MS);drafts.remove(id);composer.setText("");cancelReply();renderMessages(true);
            String hid=held.optString("id");ui.postDelayed(()->releaseHeld(hid),UNDO_SEND_MS);return;}
        busy=true;PairedService.sendOrQueue(this,id,body,reply,(value,e)->{busy=false;
            if(!e.isEmpty()){error(e);return;}if("queued".equals(value))toast("Saved. It will send when Message Center reconnects.");drafts.remove(id);if(screen.equals("chat")&&chatId.equals(id)){composer.setText("");cancelReply();}
            if(screen.equals("chat")&&chatId.equals(id))loadMessages(false);});}
    private void startChat(String typed){if(!PairedService.ready()){toast("Connect first, then start a chat.");return;}String n=ChatText.normalizeNumber(typed),value=n.isEmpty()?typed:n;
        PairedService.execute(c->c.createChat(value),(raw,e)->{if(!e.isEmpty()){error(e);return;}try{JSONObject chat=new JSONObject(raw);store.chat(chat);
            if(bubble()){startActivity(new Intent(this,PairedActivity.class).setAction(OPEN_CHAT).putExtra("chat",chat.getString("conversationID")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP));return;}
            thread(chat.getString("conversationID"));}catch(Exception ex){error("The new conversation could not be opened.");}});}
    /** Long-press a message: react (tap your current reaction to remove it), reply, copy, or see reactions. */
    private void messageSheet(JSONObject m){JSONObject c=store.chat(chatId);LinearLayout content=vertical();Dialog[] d=new Dialog[1];
        List<String[]> reactors=PairedProtocol.reactors(m);String mine=myReaction(m,c);
        List<String> choices=new ArrayList<>(java.util.Arrays.asList(REACTIONS));if(!mine.isEmpty()&&!choices.contains(mine)){choices.add(0,mine);choices.remove(choices.size()-1);}
        LinearLayout emojis=row();String current=mine;
        for(String e:choices){TextView t=text(e,26,ink,false);t.setGravity(Gravity.CENTER);boolean selected=e.equals(current);if(selected)t.setBackground(oval(fab));
            t.setContentDescription(selected?"Your reaction: "+e:"React with "+e);
            // Picking the reaction you already gave changes nothing, so nothing is sent.
            t.setOnClickListener(v->{d[0].dismiss();if(!selected)react(m,e,current.isEmpty()?"add":"switch");});emojis.addView(t,new LinearLayout.LayoutParams(0,dp(50),1));}
        content.addView(emojis);gap(content,10);
        if(!mine.isEmpty())sheetAction(content,"Remove your "+mine+" reaction",()->{d[0].dismiss();react(m,current,"remove");});
        sheetAction(content,"Reply",()->{d[0].dismiss();startReply(m);});
        String code=PairedProtocol.outgoing(m,c)?"":ChatText.oneTimeCode(PairedProtocol.text(m));if(!code.isEmpty())sheetAction(content,"Copy code "+code,()->{d[0].dismiss();copyCode(code);});
        sheetAction(content,starred.has(m.optString("messageID"))?"Unstar":"Star",()->{d[0].dismiss();starred.toggle(m);renderMessages(false);});
        String typed=PairedProtocol.text(m),plain=ChatText.replyFallbackBody(typed)!=null?ChatText.replyFallbackBody(typed):typed;
        if(!plain.isEmpty())sheetAction(content,"Copy text",()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Message",plain));});
        for(JSONObject media:PairedProtocol.media(m)){String transcript=Transcriber.saved(this,PairedProtocol.string(media,"mediaID"));
            if(transcript!=null)sheetAction(content,"Copy transcript",()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Transcript",transcript));});
            String kind=PairedProtocol.mediaKind(media),what=kind.equals("image")?"photo":kind.equals("video")?"video":kind.equals("audio")?"audio":"file";
            sheetAction(content,"Save "+what+" to phone",()->{d[0].dismiss();withFile(media,"Downloading…",file->saveToPhone(file,media));});
            sheetAction(content,"Share "+what,()->{d[0].dismiss();withFile(media,"Downloading…",file->share(file,media));});}
        if(!reactors.isEmpty())sheetAction(content,"See who reacted",()->{d[0].dismiss();reactionSheet(m,c);});
        JSONObject reminder=Reminders.forMessage(this,m.optString("messageID"));
        sheetAction(content,reminder==null?"Remind me…":"Reminder: "+separatorStamp(reminder.optLong("at"))+" (change)",()->{d[0].dismiss();remindMenu(m);});
        sheetAction(content,"Forward…",()->{d[0].dismiss();forward(java.util.Collections.singletonList(m));});
        sheetAction(content,"Select messages",()->{d[0].dismiss();startSelecting(m);});
        sheetAction(content,"Message details",()->{d[0].dismiss();messageDetails(m);});
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
    /** Your own reaction on a message, or "" — including one just sent that Google hasn't echoed back yet. */
    private String myReaction(JSONObject m,JSONObject c){String id=m.optString("messageID");Long at=sentReactionAt.get(id);
        if(at!=null&&android.os.SystemClock.uptimeMillis()-at<20_000)return sentReactions.get(id);
        String mine="";for(String[] r:PairedProtocol.reactors(m))if(PairedProtocol.isMe(c,r[1]))mine=r[0];return mine;}
    private static boolean sameEmoji(String a,String b){return a.replace("\uFE0F","").equals(b.replace("\uFE0F",""));}
    /** Sends a reaction, unless it is the one you already gave (so the other person isn't sent a repeat). */
    private void react(JSONObject m,String emoji,String action){JSONObject chat=store.chat(chatId);if(chat==null){error("Refresh this conversation first.");return;}
        String id=m.optString("messageID"),mine=myReaction(m,chat);
        if(action.equals("remove")?mine.isEmpty():sameEmoji(emoji,mine))return;
        String sent=action.equals("remove")?"":emoji;sentReactions.put(id,sent);sentReactionAt.put(id,android.os.SystemClock.uptimeMillis());
        PairedService.execute(cl->{cl.react(chat.toString(),id,emoji,mine.isEmpty()||action.equals("remove")?action:"switch");return "";},(v,e)->{
            if(!e.isEmpty()){if(sent.equals(sentReactions.get(id))){sentReactions.remove(id);sentReactionAt.remove(id);}error(e);}else loadMessages(false);});}
    private final Map<String,String> sentReactions=new HashMap<>();private final Map<String,Long> sentReactionAt=new HashMap<>();
    /** Double-tap a message to like it with 👍. Already liked: nothing is sent. */
    private long lastTapAt;private String lastTapId="";
    private void tapped(JSONObject m,View v){String id=m.optString("messageID");long now=android.os.SystemClock.uptimeMillis();
        if(id.equals(lastTapId)&&now-lastTapAt<=android.view.ViewConfiguration.getDoubleTapTimeout()+100){lastTapId="";quickLike(m,v);return;}
        lastTapId=id;lastTapAt=now;}
    private void quickLike(JSONObject m,View v){v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).withEndAction(()->v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();
        JSONObject c=store.chat(chatId);if(c==null)return;String mine=myReaction(m,c);if(sameEmoji(mine,"👍"))return;
        react(m,"👍",mine.isEmpty()?"add":"switch");}
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
        sheetAction(content,"Theme & background: "+chatSettings.theme(id).label+(chatSettings.background(id).isEmpty()?"":" + picture"),()->chooseTheme(id));
        sheetAction(content,"Read receipts: "+(chatSettings.receipts(id)?"on":"off"),()->toggleReceipts(id));
        receiptStatusView=text("",13,muted,false);receiptStatusView.setPadding(dp(16),0,dp(16),dp(8));content.addView(receiptStatusView);updateReceiptStatus();
        if(chatSettings.receipts(id)){sheetAction(content,"Retry read request / refresh Google setting",()->{JSONObject latest=store.latestIncoming(id);
            if(latest!=null&&chatSettings.seen(id)>=PairedProtocol.time(latest))PairedService.viewedForReceipts(this,store.chat(id),latest,true);
            PairedService.refreshReceiptSettings();updateReceiptStatus();});}
        sheetAction(content,chatSettings.muted(id)?"Notifications: muted (tap to unmute)":"Notifications: on (tap to mute)",()->toggleMute(id));
        sheetAction(content,chatSettings.snoozed(id)?"Snoozed until "+snoozeLabel(id)+" (tap to change)":"Snooze notifications…",()->snoozeMenu(id));
        sheetAction(content,"Search in chat",()->{thread(id);searchChat();});
        sheetAction(content,"Photos & files",this::mediaScreen);
        if(!group&&!people.isEmpty()){JSONObject p0=people.get(0);String nick=Nicknames.get(PairedProtocol.number(p0));sheetAction(content,nick.isEmpty()?"Set a nickname…":"Nickname: "+nick+" (change)",()->setNickname(p0));}
        sheetAction(content,"Export chat to a file",()->exportChat(id));
        sheetAction(content,"Notification sound & priority",()->{PairedService.ensureShortcut(this,id);PairedService.openConversationSettings(this,id,PairedProtocol.title(c));});
        if(!bubble()){boolean archived=c.optString("status").contains("ARCHIVED"),blocked=c.optString("status").equals("BLOCKED_FOLDER")||c.optString("status").equals("SPAM_FOLDER");
            sheetAction(content,archived?"Unarchive":"Archive",()->archive(c,!archived));sheetAction(content,blocked?"Unblock":"Block…",()->block(c));sheetAction(content,"Delete conversation…",()->deleteChat(c));}
        if(!bubble())sheetAction(content,"Open as bubble",()->openBubble(id));
        requestMemberPhotos(c,()->{if(screen.equals("details")&&chatId.equals(id))details();});}
    private void memberActions(JSONObject p){String number=PairedProtocol.number(p);if(number.isEmpty())return;String name=PairedProtocol.name(p);
        menuSheet(name,java.util.Arrays.asList("Message","Call","Copy number","Set a nickname…"),java.util.Arrays.<Runnable>asList(()->startChat(number),
            ()->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));}catch(Exception e){toast("No phone app is available.");}},
            ()->getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Number",number)),()->setNickname(p)));}
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
            TextView state=text("",13,Color.WHITE,false);state.setPadding(dp(12),dp(6),dp(12),dp(6));state.setBackground(shape(0x99000000,14));state.setVisibility(View.GONE);
            FrameLayout.LayoutParams stl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);stl.bottomMargin=dp(12);box.addView(state,stl);img.setTag(R.id.tile_state,state);
            box.setContentDescription(kind.equals("video")?"Video. Tap to play.":"Photo. Tap to view.");
            box.setOnClickListener(v->{String problem=mediaErrors.get(mediaKey(media));if(problem!=null&&!MediaCache.file(this,media,false).isFile())mediaProblem(media,problem,img,kind.equals("video"),box);else openMedia(media);});box.setOnLongClickListener(v->{messageSheet(m);return true;});
            int height=Math.round(width*ratio);
            // Genmoji and stickers (see-through pictures) sit on the chat like a big emoji, not in a photo frame.
            if(kind.equals("image")&&MediaCache.isSticker(MediaCache.file(this,media,false))){int[] sz=stickerSize(w,h);width=sz[0];height=sz[1];stickerStyle(box,img);}
            line.addView(box,new LinearLayout.LayoutParams(width,height));loadPreview(media,img,kind.equals("video"),width,height);
        } else {
            LinearLayout card=row();card.setPadding(dp(14),dp(12),dp(18),dp(12));card.setBackground(shape(outgoing?theme.bubble(dark):surface,22));int fg=outgoing?theme.onBubble(dark):ink;
            card.addView(text(kind.equals("audio")?"🎤":"📄",24,fg,false));LinearLayout texts=vertical();texts.setPadding(dp(12),0,0,0);
            String name=PairedProtocol.string(media,"mediaName");TextView title=text(name.isEmpty()?(kind.equals("audio")?"Audio message":"File"):name,15,fg,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.55f));texts.addView(title);
            String size=PairedProtocol.size(media.optLong("size"));boolean audio=kind.equals("audio");
            TextView status=text(audio?"▶ Tap to play"+(size.isEmpty()?"":" • "+size):(size.isEmpty()?"":size+" • ")+"Tap to open",13,outgoing?fg:muted,false);texts.addView(status);card.addView(texts);
            if(audio&&PairedProtocol.string(media,"mediaID").equals(playingId)){playingLabel=status;updatePlayLabel();}
            card.setContentDescription(title.getText()+". Tap to open.");card.setOnClickListener(v->{if(audio)playAudio(media,status);else openMedia(media);});card.setOnLongClickListener(v->{messageSheet(m);return true;});
            if(audio){TextView speed=text(speedLabel(voiceSpeed()),13,fg,true);speed.setPadding(dp(10),dp(4),dp(10),dp(4));speed.setBackground(shape((fg&0x00ffffff)|0x22000000,12));
                speed.setContentDescription("Playback speed");speed.setOnClickListener(v->cycleVoiceSpeed(speed));LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-2,-2);sl.setMarginStart(dp(12));sl.gravity=Gravity.CENTER_VERTICAL;card.addView(speed,sl);}
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
        boolean thumb=MediaCache.hasThumbnail(media);long size=media.optLong("size");File full=MediaCache.file(this,media,false);
        boolean stickerFile=!video&&full.isFile()&&MediaCache.isSticker(full);
        boolean wantFull=stickerFile||!video&&MediaCache.hasFull(media)&&(!thumb||(fullPhotos()&&size<=(25L<<20)));
        // Google sometimes sends a photo with only a preview, or nothing at all, on linked devices (end-to-end
        // encrypted chats do this). The phone sends the full photo when asked, and the message updates by itself.
        boolean ask=!video&&MediaCache.needsPhone(media)&&(fullPhotos()||!thumb)&&!full.isFile();
        if(ask)tileState(img,media,thumb?"Loading full photo…":"Getting photo from your phone…",null);
        if(ask&&PairedService.ready())PairedService.requestFullMedia(media,false,(v,e)->{if(!e.isEmpty())tileState(img,media,thumb?"Only a preview • Tap for details":"Couldn't get this photo • Tap for details",e);});
        if(wantFull&&full.isFile()){Bitmap b=MediaCache.peekCover(full,width,height);if(b!=null){img.setTag("full");img.setImageBitmap(b);sharp(img);tileState(img,media,null,null);return;}}
        if(thumb&&!stickerFile){File t=MediaCache.file(this,media,true);Bitmap b=t.isFile()?MediaCache.peekCover(t,width,height):null;
            if(b!=null){img.setImageBitmap(b);if(MediaCache.tiny(t))soft(img);}
            else if(t.isFile()||PairedService.ready()||MediaCache.hasInline(media))MediaCache.fetch(this,media,true,(file,err)->{if(file==null)return;
                decoder.execute(()->{Bitmap d=MediaCache.decodeToCover(file,width,height,false);boolean small=MediaCache.tiny(file);
                    if(d!=null)img.post(()->{if(img.getTag()==null){img.setImageBitmap(d);if(small)soft(img);}});});});}
        else if(video&&size>(15L<<20))return;
        if(!wantFull&&thumb)return;
        if(!full.isFile()&&!PairedService.ready())return;
        if(!full.isFile()&&!MediaCache.hasFull(media))return; // waiting for the phone (above); nothing to download yet
        MediaCache.fetch(this,media,false,(file,err)->{if(file==null){tileState(img,media,thumb?"Only a preview • Tap for details":"Couldn't load • Tap for details",err);return;}
            decoder.execute(()->{Bitmap d=MediaCache.decodeToCover(file,width,height,video&&!thumb);img.post(()->{if(d!=null){img.setTag("full");img.setImageBitmap(d);sharp(img);tileState(img,media,null,null);if(!video&&MediaCache.sticker(d))becomeSticker(img,d);}
                else if(!thumb)tileState(img,media,"Can't show this photo here • Tap for details","The file downloaded but Android couldn't read it as a picture.");});});});
    }
    private static final int STICKER_DP=150;
    /** A sticker's size on screen: STICKER_DP on its longest side, keeping its shape. */
    private int[] stickerSize(long w,long h){int side=dp(STICKER_DP);if(w<=0||h<=0)return new int[]{side,side};
        return w>=h?new int[]{side,Math.max(dp(40),Math.round(side*h/(float)w))}:new int[]{Math.max(dp(40),Math.round(side*w/(float)h)),side};}
    private void stickerStyle(FrameLayout box,ImageView img){box.setBackground(null);box.setClipToOutline(false);img.setScaleType(ImageView.ScaleType.FIT_CENTER);box.setContentDescription("Sticker. Tap to view.");}
    /** A picture that turned out to be see-through once decoded: drop the photo frame and shrink to sticker size. */
    private void becomeSticker(ImageView img,Bitmap b){if(!(img.getParent() instanceof FrameLayout))return;FrameLayout box=(FrameLayout)img.getParent();
        if(box.getBackground()==null)return;stickerStyle(box,img);ViewGroup.LayoutParams lp=box.getLayoutParams();if(lp==null)return;
        int[] sz=stickerSize(b.getWidth(),b.getHeight());lp.width=sz[0];lp.height=sz[1];box.setLayoutParams(lp);}
    /** A tiny preview is blurred while the full photo loads, instead of showing as big blocks. */
    private void soft(ImageView img){float r=dp(18);img.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r,r,android.graphics.Shader.TileMode.CLAMP));}
    private void sharp(ImageView img){img.setRenderEffect(null);}
    /** Why a photo couldn't load, by attachment, so tapping it can explain and retry. */
    private final Map<String,String> mediaErrors=new HashMap<>();
    private static String mediaKey(JSONObject media){return media.optString("_message")+":"+media.optInt("_index",-1);}
    private void tileState(ImageView img,JSONObject media,String label,String error){Object tag=img.getTag(R.id.tile_state);if(!(tag instanceof TextView))return;TextView state=(TextView)tag;
        if(error!=null)mediaErrors.put(mediaKey(media),error);else if(label==null)mediaErrors.remove(mediaKey(media));
        if(label==null){state.setVisibility(View.GONE);return;}state.setText(label);state.setVisibility(View.VISIBLE);}
    private void mediaProblem(JSONObject media,String problem,ImageView img,boolean video,FrameLayout box){
        new AlertDialog.Builder(this).setTitle("This photo didn't load").setMessage(problem+"\n\nRetry asks your phone to send it again.")
            .setPositiveButton("Retry",(d,w)->{mediaErrors.remove(mediaKey(media));tileState(img,media,"Getting photo from your phone…",null);
                if(MediaCache.needsPhone(media))PairedService.requestFullMedia(media,true,(v,e)->{if(!e.isEmpty())tileState(img,media,"Couldn't get this photo • Tap for details",e);});
                else loadPreview(media,img,video,box.getWidth(),box.getHeight());})
            .setNegativeButton("Close",null).show();}
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
        boolean ask=MediaCache.needsPhone(media);
        AlertDialog wait=new AlertDialog.Builder(this).setMessage(ask?"Getting it from your phone…":waiting).setView(spinner).setNegativeButton("Cancel",(d,w)->cancelled[0]=true).show();
        if(ask){PairedService.requestFullMedia(media,true,(v,e)->{if(!e.isEmpty()&&!cancelled[0]){cancelled[0]=true;wait.dismiss();error(e);}});
            awaitFull(media,60_000,updated->{if(cancelled[0])return;if(updated==null){wait.dismiss();toast("Your phone hasn't sent it yet. Try again in a moment.");return;}
                MediaCache.fetch(this,updated,false,(file,error)->{if(cancelled[0])return;wait.dismiss();if(file==null){error(error);return;}action.run(file);});});
            return;}
        if(!MediaCache.hasFull(media)){wait.dismiss();toast("This attachment wasn't sent with download details.");return;}
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
        File thumb=MediaCache.file(this,media,true);if(thumb.isFile()){int side=Math.round(getResources().getDisplayMetrics().widthPixels*0.66f);Bitmap t=MediaCache.peek(thumb,side);
            if(t!=null){image.setImageBitmap(t);if(MediaCache.tiny(thumb))soft(image);}
            // Not decoded yet (the chat was scrolled, or reopened): decode it now so the screen is never just black.
            else decoder.execute(()->{Bitmap tb=MediaCache.decode(thumb,side,false);boolean tiny=tb!=null&&MediaCache.tiny(thumb);ui.post(()->{if(tb!=null&&d.isShowing()&&image.getDrawable()==null){image.setImageBitmap(tb);if(tiny)soft(image);}});});}
        if(!MediaCache.file(this,media,false).isFile()&&!PairedService.ready()){spinner.setVisibility(View.GONE);toast("Connect to Google Messages to download the full photo.");return;}
        if(!MediaCache.hasFull(media)&&!MediaCache.file(this,media,false).isFile()){
            TextView note=text("Getting the full photo from your phone…",14,Color.WHITE,false);note.setPadding(dp(14),dp(8),dp(14),dp(8));note.setBackground(shape(0x99000000,16));
            FrameLayout.LayoutParams nl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);nl.bottomMargin=dp(48);box.addView(note,nl);
            PairedService.requestFullMedia(media,true,(v,e)->{if(!e.isEmpty()&&d.isShowing()){note.setText(e);spinner.setVisibility(View.GONE);}});
            awaitFull(media,45_000,updated->{if(!d.isShowing())return;
                if(updated==null){spinner.setVisibility(View.GONE);note.setText("Your phone hasn't sent the full photo yet. Showing the preview.");if(thumb.isFile())full[0]=thumb;return;}
                box.removeView(note);showFullPhoto(updated,d,image,spinner,full);});
            return;}
        showFullPhoto(media,d,image,spinner,full);
    }
    /** Waits (up to timeout) for the phone to update a preview-only attachment with its download details. */
    private void awaitFull(JSONObject media,long timeout,java.util.function.Consumer<JSONObject> done){long until=android.os.SystemClock.uptimeMillis()+timeout;String id=media.optString("_message");int index=media.optInt("_index",-1);
        Runnable[] check=new Runnable[1];check[0]=()->{JSONObject m=store.message(id);
            if(m!=null)for(JSONObject part:PairedProtocol.media(m))if(part.optInt("_index",-2)==index&&MediaCache.hasFull(part)){done.accept(part);return;}
            if(android.os.SystemClock.uptimeMillis()>until){done.accept(null);return;}ui.postDelayed(check[0],1500);};
        ui.postDelayed(check[0],1500);}
    private void showFullPhoto(JSONObject media,Dialog d,ZoomImageView image,ProgressBar spinner,File[] full){
        MediaCache.fetch(this,media,false,(file,error)->{if(file==null){spinner.setVisibility(View.GONE);if(d.isShowing())toast(error);return;}full[0]=file;
            decoder.execute(()->{Bitmap b=MediaCache.decode(file,2560,false);String what=b==null?MediaCache.describe(file):"";
                ui.post(()->{spinner.setVisibility(View.GONE);if(b!=null){image.setImageBitmap(b);sharp(image);}else if(d.isShowing())photoProblem(file,media,what);});});});
    }
    /** The full photo arrived but won't display: keep the preview on screen and say what the file is. */
    private void photoProblem(File file,JSONObject media,String what){
        android.util.Log.w("MessageCenter","Full photo won't decode: "+what+", sent as "+MediaCache.mime(media));
        boolean broken=what.contains("empty")||what.contains("web page")||what.contains("unrecognized");
        // A broken download is thrown away so opening the photo again fetches it fresh.
        if(broken&&file.delete())MediaCache.forget(file);
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("Can't show this photo").setPositiveButton("OK",null)
            .setMessage(broken?"The download didn't contain a photo ("+what+"). Open it again to retry.":"It downloaded ("+what+"), but Android couldn't display it. Share opens it in another app, such as Photos.");
        if(!broken)b.setNeutralButton("Share",(x,y)->share(file,media));
        b.show();
    }
    private static final int TAKE_PHOTO=43;private File pendingCapture;
    /** ⊕ button: a panel with Photos & videos (Android's photo picker, no storage permission), Camera, and Files. */
    private void attach(){LinearLayout content=vertical();Dialog[] d=new Dialog[1];LinearLayout tiles=row();tiles.setGravity(Gravity.CENTER);
        attachTile(tiles,R.drawable.ic_gallery,"Photos & videos",()->{d[0].dismiss();pickMedia();});
        attachTile(tiles,R.drawable.ic_camera,"Camera",()->{d[0].dismiss();takePhoto();});
        attachTile(tiles,R.drawable.ic_file,"Files",()->{d[0].dismiss();pickFiles();});
        attachTile(tiles,R.drawable.ic_place,"Location",()->{d[0].dismiss();shareLocation();});
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
        if(request==RESTORE_BACKUP){if(result==RESULT_OK&&data!=null&&data.getData()!=null)restoreBackup(data.getData());return;}
        if(request==PICK_BACKGROUND){if(result==RESULT_OK&&data!=null&&data.getData()!=null)saveBackgroundPhoto(data.getData());return;}
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
    // ---- 0.7: swipes, jump to latest, codes, jumbo emoji, details, starred, media, search, scheduling ----
    private Starred starred;private TextView jumpButton;private int newWhileAway;private long newestSeenTime;
    private String searchedFor="";private List<JSONObject> searchHits=new ArrayList<>();private int searchGeneration;

    /** A hint shown behind a swiped row: colored strip with an icon or label, at the start or end. */
    private View swipeHint(String label,int color,boolean atStart){FrameLayout f=new FrameLayout(this);f.setBackground(shape(color,22));
        TextView t=text(label,14,Color.WHITE,true);t.setPadding(dp(22),0,dp(22),0);f.addView(t,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL|(atStart?Gravity.START:Gravity.END)));return f;}
    /** Messages: swipe right to reply. */
    private View swipeToReply(View message,JSONObject m){
        View hint=new FrameLayout(this);TextView arrow=text("↩",22,muted,true);arrow.setPadding(dp(22),0,0,0);((FrameLayout)hint).addView(arrow,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL|Gravity.START));
        return new SwipeRow(this,message,hint,null,dp(64),dp(96)).onSwipeRight(()->startReply(m));}
    /** Inbox: swipe left to archive (or move back to the inbox), right to mark read or unread. */
    private View swipeableChat(JSONObject c){View row=inboxRow(c);if(folder==3)return row;String id=c.optString("conversationID");boolean unread=chatSettings.unread(c),archived=folder==2;
        SwipeRow s=new SwipeRow(this,row,swipeHint(unread?"Mark read":"Mark unread",0xff3b6fd8,true),swipeHint(archived?"Unarchive":"Archive",0xff2e7d5b,false),dp(96),dp(160));
        s.onSwipeLeft(()->archive(c,!archived));
        s.onSwipeRight(()->{if(unread)PairedService.markReadFromNotification(this,id,()->ui.post(this::renderInbox));else toggleUnread(id);});
        return s;}

    /** The ↓ button over a chat: shown when scrolled up, with how many new messages arrived meanwhile. */
    private void updateJumpButton(){if(jumpButton==null)return;if(pinnedBottom){jumpButton.setVisibility(View.GONE);newWhileAway=0;return;}
        jumpButton.setText(newWhileAway>0?"↓  "+newWhileAway+" new":"↓");jumpButton.setContentDescription(newWhileAway>0?newWhileAway+" new messages. Jump to latest.":"Jump to latest");jumpButton.setVisibility(View.VISIBLE);}
    private void jumpToLatest(){if(scroll==null)return;View ch=scroll.getChildAt(0);if(ch!=null)scroll.smoothScrollTo(0,ch.getHeight());pinnedBottom=true;newWhileAway=0;updateJumpButton();}

    /** GIFs and stickers inserted from the keyboard become attachments. */
    private final android.view.OnReceiveContentListener keyboardContent=(view,payload)->{
        android.content.ClipData clip=payload.getClip();List<Uri> uris=new ArrayList<>();for(int i=0;i<clip.getItemCount();i++)if(clip.getItemAt(i).getUri()!=null)uris.add(clip.getItemAt(i).getUri());
        uris.removeIf(u->!safeIncoming(u));if(uris.isEmpty())return payload; // plain text: let the box handle it
        decoder.execute(()->{List<Outgoing> added=new ArrayList<>();String problem=null;for(Uri u:uris){try{added.add(copyIn(u));}catch(Exception e){problem=e.getMessage();}}
            String p=problem;ui.post(()->{outgoingChat=chatId;outgoing.addAll(added);renderAttachments();if(p!=null)toast(p);});});
        return null;};

    private void copyCode(String code){NotificationActions.copyCode(this,code);toast("Code copied");}
    /** Under a message with a login or verification code: one tap copies it. */
    private View codeChip(String code,boolean outgoing,ChatTheme theme){LinearLayout r=row();r.setGravity(outgoing?Gravity.END:Gravity.START);r.setPadding(dp(14),dp(4),dp(14),0);
        TextView t=text("Copy code  "+code,14,theme.accent(dark),true);t.setPadding(dp(14),dp(6),dp(14),dp(6));GradientDrawable g=shape(Color.TRANSPARENT,16);g.setStroke(dp(1),theme.accent(dark));t.setBackground(g);
        t.setContentDescription("Copy code "+code);t.setOnClickListener(v->copyCode(code));r.addView(t);return r;}

    /** What Google says about one message. It reports the current status only, not delivery or read times. */
    private void messageDetails(JSONObject m){JSONObject c=store.chat(chatId);if(c==null)return;boolean out=PairedProtocol.outgoing(m,c);LinearLayout content=vertical();
        content.addView(text("Message details",18,ink,true));gap(content,8);
        java.text.DateFormat full=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL,java.text.DateFormat.SHORT);
        detailRow(content,"Type",PairedProtocol.kind(m,c).equals("RCS")?"Chat message (RCS)":PairedProtocol.kind(m,c).equals("MMS")?"Picture message (MMS)":"Text message (SMS)");
        if(out){detailRow(content,"From","You");List<JSONObject> people=PairedProtocol.members(c);
            detailRow(content,"To",people.size()==1?PairedProtocol.name(people.get(0))+numberSuffix(people.get(0)):people.size()+" people in "+PairedProtocol.title(c));}
        else {JSONObject p=PairedProtocol.participant(c,m.optString("participantID"));detailRow(content,"From",senderName(m,c)+(p==null?"":numberSuffix(p)));}
        detailRow(content,out?"Sent":"Received",full.format(new Date(PairedProtocol.time(m))));
        if(out)detailRow(content,"Status",PairedProtocol.statusLabel(m));
        long size=0;for(JSONObject media:PairedProtocol.media(m))size+=media.optLong("size");if(size>0)detailRow(content,"Attachments",PairedProtocol.media(m).size()+" • "+PairedProtocol.size(size));
        gap(content,8);content.addView(text("Google shares each message's current status, not the exact time it was delivered or read.",13,muted,false));sheet(content);}
    private String numberSuffix(JSONObject p){String n=p.optString("formattedNumber",PairedProtocol.number(p));return n.isEmpty()||n.equals(PairedProtocol.name(p))?"":" ("+n+")";}
    private void detailRow(LinearLayout parent,String label,String value){LinearLayout r=vertical();r.setPadding(dp(8),dp(8),dp(8),dp(8));
        r.addView(text(label,13,muted,false));TextView v=text(value,16,ink,false);v.setTextIsSelectable(true);r.addView(v);parent.addView(r);}

    /** Starred messages from every chat; tap one to open it in its chat. */
    private void starredScreen(){remember();base("starred");header("Starred messages",this::inbox);
        scroll=new ScrollView(this);items=vertical();items.setPadding(dp(8),0,dp(8),dp(32));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        List<JSONObject> list=starred.all();
        if(list.isEmpty()){TextView e=text("Long-press any message and tap Star to keep it here.",15,muted,false);e.setGravity(Gravity.CENTER);e.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(e);return;}
        for(JSONObject m:list)items.addView(messageResult(m,true));}
    /** A found or starred message: chat • sender • date, then its text. */
    private View messageResult(JSONObject m,boolean showChat){String chatID=m.optString("conversationID");JSONObject c=store.chat(chatID);
        LinearLayout r=vertical();r.setPadding(dp(12),dp(12),dp(12),dp(12));r.setBackground(ripple());
        String who=PairedProtocol.outgoing(m,c)?"You":ChatText.firstName(senderName(m,c));
        r.addView(text((showChat&&c!=null?PairedProtocol.title(c)+" • ":"")+who+" • "+separatorStamp(PairedProtocol.time(m)),12,muted,false));
        TextView t=text(displayBody(m),15,ink,false);t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);r.addView(t);
        r.setOnClickListener(v->openMessage(m));return r;}
    private void openMessage(JSONObject m){String chatID=m.optString("conversationID");if(store.chat(chatID)==null){toast("That conversation isn't synced here.");return;}
        thread(chatID);if(store.message(m.optString("messageID"))==null){toast("That message isn't in the synced history.");return;}ui.post(()->showMessage(m));}

    /** Every photo, video, and file shared in this chat (from synced history). */
    private void mediaScreen(){JSONObject c=store.chat(chatId);if(c==null)return;String id=chatId;base("media");header("Photos & files",this::details);
        scroll=new ScrollView(this);items=vertical();items.setPadding(dp(8),0,dp(8),dp(32));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        List<JSONObject> visual=new ArrayList<>(),files=new ArrayList<>();
        for(JSONObject m:store.mediaMessages(id,400))for(JSONObject media:PairedProtocol.media(m)){String k=PairedProtocol.mediaKind(media);if(k.equals("image")||k.equals("video"))visual.add(media);else files.add(media);}
        if(visual.isEmpty()&&files.isEmpty()){TextView e=text("No photos or files in the synced history yet.",15,muted,false);e.setGravity(Gravity.CENTER);e.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(e);return;}
        int cols=3,gapPx=dp(4),tile=(getResources().getDisplayMetrics().widthPixels-dp(16)-gapPx*(cols-1))/cols;LinearLayout line=null;
        for(int i=0;i<visual.size();i++){if(i%cols==0){line=row();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=gapPx;items.addView(line,lp);}
            JSONObject media=visual.get(i);boolean video=PairedProtocol.mediaKind(media).equals("video");FrameLayout box=new FrameLayout(this);box.setBackground(shape(surface,8));
            box.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(8));}});box.setClipToOutline(true);
            ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);box.addView(img,new FrameLayout.LayoutParams(-1,-1));
            if(video){TextView play=text("▶",16,Color.WHITE,false);play.setGravity(Gravity.CENTER);play.setBackground(oval(0x99000000));box.addView(play,new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER));}
            box.setContentDescription(video?"Video":"Photo");box.setOnClickListener(v->openMedia(media));
            LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(tile,tile);if(i%cols!=0)tl.setMarginStart(gapPx);line.addView(box,tl);loadPreview(media,img,video,tile,tile);}
        if(!files.isEmpty()){items.addView(sectionLabel("Files & audio"));for(JSONObject media:files){String name=PairedProtocol.string(media,"mediaName"),kind=PairedProtocol.mediaKind(media);
            LinearLayout r=row();r.setPadding(dp(8),dp(10),dp(8),dp(10));r.setBackground(ripple());r.addView(text(kind.equals("audio")?"🎤":"📄",22,ink,false));
            LinearLayout texts=vertical();texts.setPadding(dp(14),0,0,0);TextView n=text(name.isEmpty()?(kind.equals("audio")?"Audio message":"File"):name,15,ink,false);n.setSingleLine(true);n.setEllipsize(TextUtils.TruncateAt.MIDDLE);texts.addView(n);
            String sz=PairedProtocol.size(media.optLong("size"));if(!sz.isEmpty())texts.addView(text(sz,13,muted,false));r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
            r.setOnClickListener(v->openMedia(media));items.addView(r);}}}

    /** Inbox search also finds messages (not just chat names), searched in the background as you type. */
    private void renderMessageHits(){if(items==null)return;SmartText.Search f=SmartText.parseSearch(query);boolean filters=SmartText.hasFilters(f);
        if(f.text.trim().length()<2&&!filters){searchedFor="";searchHits.clear();return;}String q=query;
        if(!q.equals(searchedFor)){int g=++searchGeneration;decoder.execute(()->{List<JSONObject> hits=searchMessages(f,60);ui.post(()->{if(g!=searchGeneration)return;searchedFor=q;searchHits=hits;if(screen.equals("inbox")&&query.equals(q))renderInbox();});});return;}
        if(searchHits.isEmpty()){if(filters){TextView none=text("No messages match.",15,muted,false);none.setGravity(Gravity.CENTER);none.setPadding(dp(24),dp(32),dp(24),dp(24));items.addView(none);}return;}
        items.addView(sectionLabel("Messages"));for(JSONObject m:searchHits)items.addView(messageResult(m,true));}
    /** Words plus filters: from:name, has:photo, has:link, last:7d. */
    private List<JSONObject> searchMessages(SmartText.Search f,int max){long since=f.days>0?System.currentTimeMillis()-f.days*86_400_000L:0;
        List<JSONObject> candidates=f.text.trim().length()>=2?store.searchAll(f.text,2000):store.recent(4000,since);List<JSONObject> out=new ArrayList<>();
        Map<String,JSONObject> chats=new HashMap<>();String from=f.from.toLowerCase(Locale.ROOT);
        for(JSONObject m:candidates){if(out.size()>=max)break;if(since>0&&PairedProtocol.time(m)<since)continue;
            if(f.photo){boolean pic=false;for(JSONObject a:PairedProtocol.media(m)){String k=PairedProtocol.mediaKind(a);if(k.equals("image")||k.equals("video"))pic=true;}if(!pic)continue;}
            if(f.link&&ChatText.firstUrl(PairedProtocol.text(m)).isEmpty())continue;
            if(!from.isEmpty()){String cid=m.optString("conversationID");JSONObject c=chats.computeIfAbsent(cid,store::chat);
                String who=PairedProtocol.outgoing(m,c)?"you":senderName(m,c).toLowerCase(Locale.ROOT);String chatName=c==null?"":PairedProtocol.title(c).toLowerCase(Locale.ROOT);
                if(!who.contains(from)&&!chatName.contains(from))continue;}
            out.add(m);}
        return out;}
    private void pickFromFilter(EditText search){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(JSONObject c:InboxOrder.sort(store.chats("",1),chatSettings::pinned)){if(labels.size()>=25)break;String name=PairedProtocol.title(c);String token="from:\""+name+"\"";
            labels.add(name);actions.add(()->{String t=search.getText().toString().replaceAll("from:(\"[^\"]*\"|\\S+)","").trim();String next=(t+" "+token).trim();search.setText(next);search.setSelection(next.length());});}
        menuSheet("Messages from",labels,actions);}

    // Scheduled send
    /** Long-press send: pick when to send. Text only; it goes out through the outbox at that time. */
    private void scheduleMessage(){if(composer==null)return;String text=composer.getText().toString().trim();if(text.isEmpty()){toast("Type a message first, then hold send to schedule it.");return;}
        java.util.Calendar now=java.util.Calendar.getInstance();List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        java.util.Calendar evening=(java.util.Calendar)now.clone();evening.set(java.util.Calendar.HOUR_OF_DAY,18);evening.set(java.util.Calendar.MINUTE,0);evening.set(java.util.Calendar.SECOND,0);
        if(evening.after(now)){long at=evening.getTimeInMillis();labels.add("Later today, "+timeOnly(at));actions.add(()->schedule(text,at));}
        java.util.Calendar morning=(java.util.Calendar)now.clone();morning.add(java.util.Calendar.DAY_OF_YEAR,1);morning.set(java.util.Calendar.HOUR_OF_DAY,8);morning.set(java.util.Calendar.MINUTE,0);morning.set(java.util.Calendar.SECOND,0);
        long tomorrow=morning.getTimeInMillis();labels.add("Tomorrow, "+timeOnly(tomorrow));actions.add(()->schedule(text,tomorrow));
        labels.add("Pick date & time…");actions.add(()->pickWhen(text));menuSheet("Schedule message",labels,actions);}
    private String timeOnly(long at){return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(at));}
    private void pickWhen(String text){java.util.Calendar c=java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this,(dp,y,mo,d)->new android.app.TimePickerDialog(this,(tp,h,mi)->{java.util.Calendar at=java.util.Calendar.getInstance();at.set(y,mo,d,h,mi,0);
            if(at.getTimeInMillis()<System.currentTimeMillis()+30_000){toast("Pick a time in the future.");return;}schedule(text,at.getTimeInMillis());},c.get(java.util.Calendar.HOUR_OF_DAY),(c.get(java.util.Calendar.MINUTE)/5+1)*5%60,android.text.format.DateFormat.is24HourFormat(this)).show(),
            c.get(java.util.Calendar.YEAR),c.get(java.util.Calendar.MONTH),c.get(java.util.Calendar.DAY_OF_MONTH)).show();}
    private void schedule(String text,long at){Scheduled.add(this,chatId,text,replyId,at);drafts.remove(chatId);if(composer!=null)composer.setText("");cancelReply();renderMessages(true);
        if(!getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms())
            new AlertDialog.Builder(this).setMessage("Scheduled. Android may send it a few minutes late while the phone sleeps unless Message Center is allowed to set exact alarms.")
                .setPositiveButton("Allow exact timing",(d,w)->{try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}catch(Exception ignored){}}).setNegativeButton("OK",null).show();
        else toast("Scheduled for "+separatorStamp(at));}
    /** Scheduled messages for this chat, after the conversation, faded, with send now / delete. */
    private void renderScheduled(ChatTheme theme){for(JSONObject o:Scheduled.forChat(this,chatId)){String id=o.optString("id");gap(items,8);
        LinearLayout line=row();line.setGravity(Gravity.END);TextView b=text(o.optString("text"),17,theme.onBubble(dark),false);b.setPadding(dp(16),dp(11),dp(16),dp(11));
        b.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.setBackground(shape(theme.bubble(dark),22));b.setAlpha(0.55f);line.addView(b);items.addView(line);
        TextView s=text("🕒 Scheduled for "+separatorStamp(o.optLong("at"))+" • tap for options",12,muted,false);s.setGravity(Gravity.END);s.setPadding(0,dp(4),dp(6),0);items.addView(s,new LinearLayout.LayoutParams(-1,-2));
        View.OnClickListener act=v->menuSheet("Scheduled message",java.util.Arrays.asList("Send now","Delete"),java.util.Arrays.<Runnable>asList(()->{Scheduled.sendNow(this,id);renderMessages(false);},()->{Scheduled.remove(this,id);renderMessages(false);}));
        b.setOnClickListener(act);s.setOnClickListener(act);}}
    // ---- 1.0: promotions, unsubscribe, old codes, smart taps, quick replies, @ names, location, keywords, auto-reply, search ----
    private boolean promoOnly;private LinearLayout searchChips,quickRow;private HorizontalScrollView quickScroll;
    private boolean promotionsSeparate(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("promotions_separate",true);}
    /** A one-to-one chat with a short code or business name that isn't a saved contact (a fresh verification code stays in the inbox). */
    private boolean promotion(JSONObject c){if(chatSettings.pinned(c))return false;JSONObject other=PairedProtocol.other(c);if(other==null||PairedProtocol.known(other))return false;
        if(!SmartText.shortCodeSender(PairedProtocol.number(other)))return false;
        JSONObject last=latestStored(c.optString("conversationID"));return last==null||ChatText.oneTimeCode(PairedProtocol.text(last)).isEmpty()||System.currentTimeMillis()-PairedProtocol.time(last)>3_600_000L;}
    private View promotionsRow(List<JSONObject> promos){int unread=0;for(JSONObject c:promos)if(chatSettings.unread(c))unread++;
        LinearLayout entry=row();entry.setPadding(dp(4),dp(9),dp(4),dp(9));entry.setGravity(Gravity.CENTER_VERTICAL);entry.setBackground(ripple());
        TextView icon=text("🏷",20,ink,false);icon.setGravity(Gravity.CENTER);icon.setBackground(oval(surface));entry.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout middle=vertical();middle.setPadding(dp(14),0,dp(8),0);TextView t=text("Promotions",16,ink,unread>0);t.setTextSize(16.5f*textScale);middle.addView(t);
        JSONObject latest=promos.get(0);TextView sub=text(PairedProtocol.title(latest)+" and "+(promos.size()-1)+" more",14,muted,false);if(promos.size()==1)sub.setText(PairedProtocol.title(latest));sub.setTextSize(14*textScale);sub.setSingleLine(true);sub.setEllipsize(TextUtils.TruncateAt.END);middle.addView(sub);
        entry.addView(middle,new LinearLayout.LayoutParams(0,-2,1));
        if(unread>0){TextView n=text(String.valueOf(unread),12,onFab,true);n.setGravity(Gravity.CENTER);n.setPadding(dp(8),dp(2),dp(8),dp(2));n.setBackground(shape(fab,10));entry.addView(n);}
        entry.setContentDescription("Promotions, "+promos.size()+" chats"+(unread>0?", "+unread+" unread":""));entry.setOnClickListener(v->{promoOnly=true;inbox();});return entry;}
    private void unsubscribe(String id,JSONObject c){String who=c==null?"this sender":PairedProtocol.title(c);
        new AlertDialog.Builder(this).setTitle("Unsubscribe from "+who+"?").setMessage("This sends STOP, which businesses must honor for marketing texts. Standard message rates may apply.")
            .setNegativeButton("Cancel",null).setPositiveButton("Send STOP",(d,w)->PairedService.sendOrQueue(this,id,"STOP","",(v,e)->{if(!e.isEmpty())error(e);else toast("Sent STOP to "+who);if(screen.equals("chat"))loadMessages(false);})).show();}
    /** Settings: remove verification-code messages older than a day or a week from Google Messages on your phone. */
    private void clearOldCodes(){List<String> labels=java.util.Arrays.asList("Older than 1 day","Older than 1 week");long[] ages={86_400_000L,7*86_400_000L};List<Runnable> actions=new ArrayList<>();
        for(long age:ages)actions.add(()->decoder.execute(()->{long cutoff=System.currentTimeMillis()-age;List<JSONObject> codes=new ArrayList<>();
            for(JSONObject m:store.recent(20000,0)){if(PairedProtocol.time(m)>=cutoff||PairedProtocol.outgoing(m))continue;if(!ChatText.oneTimeCode(PairedProtocol.text(m)).isEmpty())codes.add(m);}
            ui.post(()->{if(codes.isEmpty()){toast("No old verification codes found.");return;}if(!PairedService.ready()){toast("Connect first, then try again.");return;}
                new AlertDialog.Builder(this).setMessage("Delete "+codes.size()+" old verification code "+(codes.size()==1?"message":"messages")+" from Google Messages on your phone?")
                    .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{toast("Deleting "+codes.size()+"…");deleteNext(codes,0,0);}).show();});}));
        menuSheet("Delete old verification codes",labels,actions);}
    private void smartTap(SmartText.Hit h,JSONObject m,JSONObject c){
        if(h.kind.equals("date")){String who=c==null?"":PairedProtocol.title(c);
            Intent add=new Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME,h.time).putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME,h.time+3_600_000L)
                .putExtra(android.provider.CalendarContract.Events.TITLE,who.isEmpty()?"Plans":"With "+who).putExtra(android.provider.CalendarContract.Events.DESCRIPTION,PairedProtocol.text(m));
            try{startActivity(add);}catch(Exception e){toast("No calendar app is installed.");}return;}
        String url=h.kind.equals("flight")?SmartText.flightUrl(h):SmartText.trackingUrl(h);
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("No browser is installed.");}}
    // Quick replies and @ names: one row of chips above the message box.
    private static final String DEFAULT_QUICK="On my way\nRunning a few minutes late\nCall you in 10\nCan't talk now, text me\nSounds good!\nThank you!";
    private List<String> quickReplies(){List<String> out=new ArrayList<>();for(String line:getSharedPreferences("paired",MODE_PRIVATE).getString("quick_replies",DEFAULT_QUICK).split("\n"))if(!line.trim().isEmpty())out.add(line.trim());return out;}
    private void updateQuickRow(){if(quickRow==null||composer==null)return;String t=composer.getText().toString();int cursor=Math.max(0,composer.getSelectionStart());
        JSONObject c=store.chat(chatId);
        // Typing @ in a group suggests members.
        java.util.regex.Matcher at=java.util.regex.Pattern.compile("(?:^|\\s)@([\\p{L}'-]*)$").matcher(t.substring(0,Math.min(cursor,t.length())));
        if(c!=null&&PairedProtocol.other(c)==null&&at.find()){String part=at.group(1).toLowerCase(Locale.ROOT);quickRow.removeAllViews();int start=at.start(1)-1;
            for(JSONObject p:PairedProtocol.members(c)){String full=PairedProtocol.name(p),first=ChatText.firstName(full);if(first.isEmpty()||!first.toLowerCase(Locale.ROOT).startsWith(part))continue;
                quickRow.addView(quickChip("@"+first,()->{String now=composer.getText().toString();int end=Math.min(cursor,now.length());String next=now.substring(0,start)+"@"+first+" "+now.substring(end);composer.setText(next);composer.setSelection(Math.min(next.length(),start+first.length()+2));},null));}
            quickScroll.setVisibility(quickRow.getChildCount()>0?View.VISIBLE:View.GONE);return;}
        boolean show=composer.hasFocus()&&t.trim().isEmpty()&&quickRepliesOn();quickScroll.setVisibility(show?View.VISIBLE:View.GONE);if(!show)return;
        quickRow.removeAllViews();for(String q:quickReplies())quickRow.addView(quickChip(q,()->{composer.setText(q);composer.setSelection(q.length());},()->removeQuickReply(q)));
        quickRow.addView(quickChip("+ Add",this::addQuickReply,null));}
    private boolean quickRepliesOn(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("quick_replies_on",true);}
    private View quickChip(String label,Runnable tap,Runnable longPress){TextView chip=text(label,14,ink,false);chip.setPadding(dp(14),dp(8),dp(14),dp(8));chip.setBackground(shape(surface,18));chip.setSingleLine(true);
        chip.setOnClickListener(v->tap.run());if(longPress!=null)chip.setOnLongClickListener(v->{longPress.run();return true;});
        LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(-2,-2);l.setMarginEnd(dp(8));chip.setLayoutParams(l);return chip;}
    private void addQuickReply(){EditText e=input("A phrase you send often");e.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Add quick reply").setView(e).setNegativeButton("Cancel",null).setPositiveButton("Add",(d,w)->{String v=e.getText().toString().trim().replace("\n"," ");if(v.isEmpty())return;
            List<String> list=new ArrayList<>(quickReplies());list.add(v);saveQuickReplies(list);updateQuickRow();}).show();}
    private void removeQuickReply(String q){new AlertDialog.Builder(this).setMessage("Remove \u201c"+q+"\u201d from quick replies?").setNegativeButton("Cancel",null)
        .setPositiveButton("Remove",(d,w)->{List<String> list=new ArrayList<>(quickReplies());list.remove(q);saveQuickReplies(list);updateQuickRow();}).show();}
    private void saveQuickReplies(List<String> list){getSharedPreferences("paired",MODE_PRIVATE).edit().putString("quick_replies",String.join("\n",list)).apply();}
    private void editQuickReplies(){EditText e=input("One per line");e.setSingleLine(false);e.setMinLines(4);e.setText(String.join("\n",quickReplies()));
        new AlertDialog.Builder(this).setTitle("Quick replies").setMessage("Shown above the message box when it's empty. Tap one to use it; hold one to remove it.").setView(e)
            .setNeutralButton(quickRepliesOn()?"Turn off":"Turn on",(d,w)->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("quick_replies_on",!quickRepliesOn()).apply();settings();})
            .setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{List<String> list=new ArrayList<>();for(String line:e.getText().toString().split("\n"))if(!line.trim().isEmpty())list.add(line.trim());saveQuickReplies(list);settings();}).show();}
    // Send my location
    private static final int LOCATION_PERMISSION=95;
    private void shareLocation(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_PERMISSION);return;}
        android.location.LocationManager lm=getSystemService(android.location.LocationManager.class);
        if(!lm.isLocationEnabled()){toast("Turn on Location in your phone's settings first.");return;}
        String provider=lm.hasProvider(android.location.LocationManager.FUSED_PROVIDER)&&lm.isProviderEnabled(android.location.LocationManager.FUSED_PROVIDER)?android.location.LocationManager.FUSED_PROVIDER
            :lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)?android.location.LocationManager.GPS_PROVIDER:android.location.LocationManager.NETWORK_PROVIDER;
        toast("Finding your location…");String chat=chatId;
        try{lm.getCurrentLocation(provider,null,getMainExecutor(),loc->{android.location.Location use=loc;
            if(use==null)for(String p:lm.getProviders(true)){try{android.location.Location last=lm.getLastKnownLocation(p);if(last!=null&&(use==null||last.getTime()>use.getTime()))use=last;}catch(SecurityException ignored){}}
            if(use==null){toast("Couldn't find your location. Try again outside or with Wi-Fi on.");return;}
            String ll=String.format(Locale.US,"%.5f,%.5f",use.getLatitude(),use.getLongitude());String link="My location: https://maps.google.com/?q="+ll;
            if(!screen.equals("chat")||!chatId.equals(chat)||composer==null)return;String now=composer.getText().toString().trim();composer.setText(now.isEmpty()?link:now+"\n"+link);composer.setSelection(composer.getText().length());});}
        catch(SecurityException e){toast("Location permission is needed to share where you are.");}}
    // Keyword alerts
    private void editKeywords(){EditText e=input("Words or phrases, separated by commas");e.setText(getSharedPreferences("paired",MODE_PRIVATE).getString("alert_keywords",""));
        new AlertDialog.Builder(this).setTitle("Keyword alerts").setMessage("When a message contains one of these, you're notified even if that chat is muted or snoozed.").setView(e)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{getSharedPreferences("paired",MODE_PRIVATE).edit().putString("alert_keywords",e.getText().toString().trim()).apply();settings();}).show();}
    // Busy auto-reply
    private boolean autoReplyActive(){return PairedService.autoReplyUntil(this)>System.currentTimeMillis();}
    private View autoReplyBanner(){LinearLayout card=row();card.setPadding(dp(16),dp(10),dp(8),dp(10));card.setBackground(shape(fab,16));long until=PairedService.autoReplyUntil(this);
        TextView t=text("Auto-reply is on"+(until==Long.MAX_VALUE?"":" until "+(android.text.format.DateUtils.isToday(until)?"":new SimpleDateFormat("EEE ",Locale.getDefault()).format(new Date(until)))+timeOnly(until)),14,onFab,true);card.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        TextView off=text("Turn off",14,onFab,false);off.setPadding(dp(10),dp(4),dp(8),dp(4));off.setOnClickListener(v->{PairedService.setAutoReply(this,0);renderInbox();});card.addView(off);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(4),dp(4),dp(4),dp(8));card.setLayoutParams(lp);return card;}
    private void autoReplyMenu(){java.util.Calendar morning=java.util.Calendar.getInstance();morning.add(java.util.Calendar.DAY_OF_YEAR,1);morning.set(java.util.Calendar.HOUR_OF_DAY,8);morning.set(java.util.Calendar.MINUTE,0);morning.set(java.util.Calendar.SECOND,0);
        long now=System.currentTimeMillis();List<String> labels=new ArrayList<>(java.util.Arrays.asList("On for 1 hour","On for 3 hours","On until tomorrow, "+timeOnly(morning.getTimeInMillis()),"On until I turn it off","Edit the reply…"));
        long[] untils={now+3_600_000L,now+3*3_600_000L,morning.getTimeInMillis(),Long.MAX_VALUE};List<Runnable> actions=new ArrayList<>();
        for(long u:untils)actions.add(()->{PairedService.setAutoReply(this,u);toast("Auto-reply on");settings();});
        actions.add(()->{EditText e=input("Reply text");e.setText(PairedService.autoReplyText(this));new AlertDialog.Builder(this).setTitle("Auto-reply").setView(e).setNegativeButton("Cancel",null)
            .setPositiveButton("Save",(d,w)->{String v=e.getText().toString().trim();if(!v.isEmpty())getSharedPreferences("paired",MODE_PRIVATE).edit().putString("autoreply_text",v).apply();settings();}).show();});
        if(autoReplyActive()){labels.add("Turn off");actions.add(()->{PairedService.setAutoReply(this,0);settings();});}
        menuSheet("Busy auto-reply",labels,actions);}
    // ---- 0.9: forward, select, mark all read, theme, text size, backup ----
    private float textScale=1f;private boolean forwardingNow,selecting;private final LinkedHashMap<String,JSONObject> chosen=new LinkedHashMap<>();
    private LinearLayout selectionBar;private FrameLayout chatFrame;private static final int RESTORE_BACKUP=93;
    private static String scaleLabel(float s){return s<1f?"Small":s<1.15f?"Default":s<1.3f?"Large":"Larger";}
    /** Forward: copies the text and downloads the attachments, then you pick the chat (like sharing in). */
    private void forward(List<JSONObject> messages){if(bubble()){toast("Open this chat in Message Center to forward.");return;}List<JSONObject> list=new ArrayList<>(messages);list.sort((a,b)->Long.compare(PairedProtocol.time(a),PairedProtocol.time(b)));
        StringBuilder text=new StringBuilder();List<JSONObject> media=new ArrayList<>();
        for(JSONObject m:list){String t=PairedProtocol.text(m);String quoted=ChatText.replyFallbackBody(t);if(quoted!=null)t=quoted;
            JSONObject place=PairedProtocol.location(m);if(place!=null&&!place.optString("text").isEmpty())t=t.isEmpty()?place.optString("text"):t+"\n"+place.optString("text");
            if(!t.isEmpty()){if(text.length()>0)text.append('\n');text.append(t);}media.addAll(PairedProtocol.media(m));}
        String body=text.toString();if(media.isEmpty()){startForward(body,new ArrayList<>(),0);return;}
        boolean[] cancelled={false};ProgressBar spinner=new ProgressBar(this);spinner.setPadding(0,dp(16),0,dp(16));
        AlertDialog wait=new AlertDialog.Builder(this).setMessage("Getting "+(media.size()==1?"the attachment":media.size()+" attachments")+" ready…").setView(spinner).setNegativeButton("Cancel",(d,w)->cancelled[0]=true).show();
        Outgoing[] files=new Outgoing[media.size()];int[] left={media.size()},missing={0};
        for(int i=0;i<media.size();i++){int at=i;JSONObject a=media.get(i);
            MediaCache.fetch(this,a,false,(file,err)->{if(cancelled[0])return;if(file==null){missing[0]++;if(--left[0]==0){wait.dismiss();startForward(body,files,missing[0]);}return;}
                decoder.execute(()->{Outgoing copy=null;try{copy=copyFile(file,PairedProtocol.string(a,"mediaName"),PairedProtocol.string(a,"mimeType"));}catch(Exception ignored){}
                    Outgoing o=copy;ui.post(()->{if(cancelled[0]){if(o!=null)o.file.delete();return;}if(o==null)missing[0]++;else files[at]=o;if(--left[0]==0){wait.dismiss();startForward(body,files,missing[0]);}});});});}}
    private void startForward(String body,Outgoing[] files,int missing){List<Outgoing> list=new ArrayList<>();for(Outgoing o:files)if(o!=null)list.add(o);startForward(body,list,missing);}
    private void startForward(String body,List<Outgoing> files,int missing){
        if(body.isEmpty()&&files.isEmpty()){toast(missing>0?"The attachment isn't downloaded yet, so it can't be forwarded.":"Nothing to forward.");return;}
        for(Outgoing o:incoming)o.file.delete();incoming.clear();incoming.addAll(files);incomingText=body.isEmpty()?null:body;forwardingNow=true;
        if(missing>0)toast(missing==1?"One attachment isn't downloaded yet and was left out.":missing+" attachments aren't downloaded yet and were left out.");
        if(selecting)stopSelecting();if(bubble()){startActivity(new Intent(this,PairedActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        folder=1;unknownOnly=false;promoOnly=false;inbox();}
    private Outgoing copyFile(File source,String name,String mime) throws Exception{if(name==null||name.isEmpty())name="attachment";if(mime==null||mime.isEmpty())mime="application/octet-stream";
        File dir=new File(getCacheDir(),"outgoing");if(!dir.isDirectory())dir.mkdirs();File target=new File(dir,java.util.UUID.randomUUID()+"-"+name.replaceAll("[^A-Za-z0-9._-]","_"));
        java.nio.file.Files.copy(source.toPath(),target.toPath());return new Outgoing(target,name,mime);}
    /** Selection mode: tap messages to pick them, then copy, forward, star, or delete them together. */
    private void startSelecting(JSONObject m){selecting=true;chosen.clear();chosen.put(m.optString("messageID"),m);renderMessages(false);showSelectionBar();}
    private void stopSelecting(){selecting=false;chosen.clear();if(selectionBar!=null&&chatFrame!=null)chatFrame.removeView(selectionBar);selectionBar=null;if(screen.equals("chat"))renderMessages(false);}
    private void toggleChosen(JSONObject m){String id=m.optString("messageID");if(chosen.remove(id)==null)chosen.put(id,m);if(chosen.isEmpty()){stopSelecting();return;}renderMessages(false);showSelectionBar();}
    private View selectable(View row,JSONObject m){FrameLayout f=new FrameLayout(this);f.addView(row,new FrameLayout.LayoutParams(-1,-2));boolean on=chosen.containsKey(m.optString("messageID"));
        View cover=new View(this);cover.setBackground(shape(on?((fab&0x00ffffff)|0x66000000):0x00000000,16));cover.setContentDescription(on?"Selected message":"Message, tap to select");
        cover.setOnClickListener(v->toggleChosen(m));cover.setOnLongClickListener(v->{toggleChosen(m);return true;});f.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);f.setLayoutParams(lp);return f;}
    private void showSelectionBar(){if(chatFrame==null)return;if(selectionBar!=null)chatFrame.removeView(selectionBar);
        LinearLayout bar=row();bar.setPadding(dp(8),dp(6),dp(8),dp(6));bar.setBackground(shape(card,18));bar.setElevation(dp(6));
        bar.addView(icon(R.drawable.ic_back,ink,"Stop selecting",this::stopSelecting));
        TextView count=text(chosen.size()+" selected",16,ink,true);count.setPadding(dp(6),0,dp(6),0);bar.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        List<JSONObject> list=new ArrayList<>(chosen.values());list.sort((a,b)->Long.compare(PairedProtocol.time(a),PairedProtocol.time(b)));
        boolean allStarred=true;for(JSONObject m:list)if(!starred.has(m.optString("messageID")))allStarred=false;boolean unstar=allStarred;
        String[] labels={"Copy","Forward",unstar?"Unstar":"Star","Delete"};
        Runnable[] actions={()->copySelected(list),()->forward(list),()->{for(JSONObject m:list)if(starred.has(m.optString("messageID"))==unstar)starred.toggle(m);toast(unstar?"Unstarred":"Starred");stopSelecting();},()->deleteSelected(list)};
        for(int i=0;i<labels.length;i++){Runnable a=actions[i];TextView b=text(labels[i],14,labels[i].equals("Delete")?danger:ink,true);b.setPadding(dp(10),dp(10),dp(10),dp(10));b.setBackground(ripple());b.setOnClickListener(v->a.run());bar.addView(b);}
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);lp.setMargins(dp(8),dp(4),dp(8),0);chatFrame.addView(bar,lp);selectionBar=bar;}
    private void copySelected(List<JSONObject> list){JSONObject c=store.chat(chatId);StringBuilder b=new StringBuilder();
        for(JSONObject m:list){String body=displayBody(m);if(body.isEmpty())continue;if(b.length()>0)b.append('\n');if(list.size()>1)b.append(PairedProtocol.outgoing(m,c)?"You":senderName(m,c)).append(": ");b.append(body);}
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Messages",b.toString()));toast(list.size()==1?"Copied":"Copied "+list.size()+" messages");stopSelecting();}
    private void deleteSelected(List<JSONObject> list){int n=list.size();
        new AlertDialog.Builder(this).setMessage("Delete "+(n==1?"this message":n+" messages")+"? They're removed from Google Messages on your phone, not from the other person's phone.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{stopSelecting();deleteNext(list,0,0);}).show();}
    private void deleteNext(List<JSONObject> list,int i,int failed){if(i>=list.size()){renderMessages(false);if(failed>0)toast(failed+" couldn't be deleted. Check your connection.");return;}
        String mid=list.get(i).optString("messageID");PairedService.execute(c->{c.deleteMessage(mid);return "";},(v,e)->{if(e.isEmpty())store.deleteMessage(mid);deleteNext(list,i+1,failed+(e.isEmpty()?0:1));});}
    /** Mark every chat in the inbox read (read receipts go only to chats where you turned them on). */
    private void markAllRead(){List<String> ids=new ArrayList<>();for(JSONObject c:store.chats("",1))if(chatSettings.unread(c))ids.add(c.optString("conversationID"));
        if(ids.isEmpty()){toast("Everything's already read.");return;}
        new AlertDialog.Builder(this).setMessage("Mark "+(ids.size()==1?"1 conversation":ids.size()+" conversations")+" as read?").setNegativeButton("Cancel",null)
            .setPositiveButton("Mark read",(d,w)->{for(String id:ids)PairedService.markReadFromNotification(this,id,()->{});ui.postDelayed(()->{if(screen.equals("inbox"))renderInbox();},300);toast("Marked "+ids.size()+" as read");}).show();}
    /** Light, dark, or match the phone; Android applies it to every screen of the app (and remembers it). */
    private void appTheme(String mode){getSharedPreferences("paired",MODE_PRIVATE).edit().putString("theme",mode).apply();applyThemeSetting(this);}
    static void applyThemeSetting(Context context){String mode=context.getSharedPreferences("paired",MODE_PRIVATE).getString("theme","system");
        context.getSystemService(android.app.UiModeManager.class).setApplicationNightMode(mode.equals("light")?android.app.UiModeManager.MODE_NIGHT_NO:mode.equals("dark")?android.app.UiModeManager.MODE_NIGHT_YES:android.app.UiModeManager.MODE_NIGHT_AUTO);}
    /** Saves starred messages, nicknames, reminders, scheduled messages, chat settings, and app settings to Downloads/Message Center. */
    private void backupNow(){toast("Backing up…");decoder.execute(()->{String result;Uri item=null;
        try{String json=Backup.export(this);String name="message-center-backup-"+new SimpleDateFormat("yyyy-MM-dd-HHmm",Locale.US).format(new Date())+".json";
            ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Message Center");v.put(MediaStore.MediaColumns.IS_PENDING,1);
            item=getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),v);if(item==null)throw new java.io.IOException("insert");
            try(java.io.OutputStream out=getContentResolver().openOutputStream(item)){out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(item,done,null,null);
            result="Saved to Downloads/Message Center/"+name+"\n\nIt includes your starred messages' text, nicknames, reminders, and scheduled messages, so keep it private. Your messages stay in Google Messages, and your Google sign-in isn't included.";}
        catch(Exception e){if(item!=null)getContentResolver().delete(item,null,null);item=null;result="The backup could not be saved.";}
        String r=result;Uri saved=item;ui.post(()->{if(saved==null){error(r);return;}
            new AlertDialog.Builder(this).setTitle("Backup saved").setMessage(r).setPositiveButton("Share",(d,w)->{try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share backup"));}catch(Exception ex){toast("No app can share this.");}})
                .setNegativeButton("Done",null).show();});});}
    private void restoreBackup(Uri uri){
        new AlertDialog.Builder(this).setTitle("Restore this backup?").setMessage("Starred messages, nicknames, reminders, scheduled messages, and chat settings are replaced with the ones in the backup.")
            .setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->decoder.execute(()->{String result;
                try(java.io.InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException("open");byte[] data=in.readNBytes(5<<20);
                    int n=Backup.restore(this,new String(data,java.nio.charset.StandardCharsets.UTF_8));result="Restored "+n+" items.";}
                catch(IllegalArgumentException e){result=e.getMessage();}catch(Exception e){result="That file couldn't be read as a Message Center backup.";}
                String r=result;ui.post(()->{starred=new Starred(this);chatSettings=new ChatSettings(this);applyThemeSetting(this);toast(r);settings();});})).show();}
    // ---- 0.8: reminders, export, nicknames ----
    /** A reminder notification opens the chat at its message. */
    private void showFromIntent(Intent intent){String mid=intent==null?null:intent.getStringExtra("message");if(mid==null||mid.isEmpty())return;intent.removeExtra("message");
        ui.postDelayed(()->{JSONObject m=store.message(mid);if(m!=null&&screen.equals("chat"))showMessage(m);},250);}
    private void remindMenu(JSONObject m){java.util.Calendar now=java.util.Calendar.getInstance();List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        long hour=now.getTimeInMillis()+3_600_000L;labels.add("In 1 hour");actions.add(()->remind(m,hour));
        java.util.Calendar tonight=(java.util.Calendar)now.clone();tonight.set(java.util.Calendar.HOUR_OF_DAY,20);tonight.set(java.util.Calendar.MINUTE,0);tonight.set(java.util.Calendar.SECOND,0);
        if(tonight.getTimeInMillis()>now.getTimeInMillis()+30*60_000L){long t=tonight.getTimeInMillis();labels.add("Tonight, "+timeOnly(t));actions.add(()->remind(m,t));}
        java.util.Calendar morning=(java.util.Calendar)now.clone();morning.add(java.util.Calendar.DAY_OF_YEAR,1);morning.set(java.util.Calendar.HOUR_OF_DAY,8);morning.set(java.util.Calendar.MINUTE,0);morning.set(java.util.Calendar.SECOND,0);
        long t2=morning.getTimeInMillis();labels.add("Tomorrow, "+timeOnly(t2));actions.add(()->remind(m,t2));
        labels.add("Pick date & time…");actions.add(()->pickDateTime(at->remind(m,at)));
        JSONObject existing=Reminders.forMessage(this,m.optString("messageID"));if(existing!=null){labels.add("Remove reminder");actions.add(()->{Reminders.remove(this,existing.optString("id"));toast("Reminder removed");renderMessages(false);});}
        menuSheet("Remind me about this message",labels,actions);}
    private void remind(JSONObject m,long at){JSONObject c=store.chat(chatId);String who=PairedProtocol.outgoing(m,c)?"You":senderName(m,c);
        String title=c==null?who:(PairedProtocol.other(c)==null?PairedProtocol.title(c)+" • "+who:PairedProtocol.title(c));String text=displayBody(m);
        Reminders.add(this,chatId,m.optString("messageID"),title,text.isEmpty()?"Message":text,at);renderMessages(false);
        if(!getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms())toast("Reminder set. Allow \"Alarms & reminders\" for exact timing.");else toast("I'll remind you "+separatorStamp(at));}
    private void pickDateTime(java.util.function.LongConsumer done){java.util.Calendar c=java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this,(dpk,y,mo,d)->new android.app.TimePickerDialog(this,(tp,h,mi)->{java.util.Calendar at=java.util.Calendar.getInstance();at.set(y,mo,d,h,mi,0);
            if(at.getTimeInMillis()<System.currentTimeMillis()+30_000){toast("Pick a time in the future.");return;}done.accept(at.getTimeInMillis());},c.get(java.util.Calendar.HOUR_OF_DAY),0,android.text.format.DateFormat.is24HourFormat(this)).show(),
            c.get(java.util.Calendar.YEAR),c.get(java.util.Calendar.MONTH),c.get(java.util.Calendar.DAY_OF_MONTH)).show();}
    /** Saves every synced message in a chat as a text file in Downloads/Message Center. */
    private void exportChat(String id){JSONObject c=store.chat(id);if(c==null)return;String title=PairedProtocol.title(c);toast("Exporting…");
        decoder.execute(()->{String result;Uri item=null;
            try{List<JSONObject> list=store.messages(id,100_000);java.text.DateFormat f=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT);
                StringBuilder b=new StringBuilder();b.append(title).append("\nExported from Message Center on ").append(f.format(new Date())).append(" • ").append(list.size()).append(" messages synced to this phone\n\n");
                for(JSONObject m:list){String event=PairedProtocol.event(m,c);String body=event.isEmpty()?displayBody(m):"— "+event+" —";if(body.isEmpty())continue;
                    b.append('[').append(f.format(new Date(PairedProtocol.time(m)))).append("] ");if(event.isEmpty())b.append(PairedProtocol.outgoing(m,c)?"You":senderName(m,c)).append(": ");b.append(body).append('\n');
                    for(String[] r:PairedProtocol.reactors(m))b.append("    ").append(r[0]).append(' ').append(PairedProtocol.isMe(c,r[1])?"You":PairedProtocol.person(c,r[1],null)).append('\n');}
                String name=("Chat - "+title.replaceAll("[\\\\/:*?\"<>|]","_")+" - "+new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date())+".txt");
                ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Message Center");v.put(MediaStore.MediaColumns.IS_PENDING,1);
                item=getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),v);if(item==null)throw new java.io.IOException("insert");
                try(java.io.OutputStream out=getContentResolver().openOutputStream(item)){out.write(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(item,done,null,null);result="Saved to Downloads/Message Center/"+name;}
            catch(Exception e){if(item!=null)getContentResolver().delete(item,null,null);item=null;result="The chat could not be exported.";}
            String r=result;Uri saved=item;ui.post(()->{if(saved==null){error(r);return;}
                new AlertDialog.Builder(this).setMessage(r).setPositiveButton("Share",(d,w)->{try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share chat"));}catch(Exception ex){toast("No app can share this.");}})
                    .setNegativeButton("Done",null).show();});});}
    /** A name for this person on this phone only. */
    private void setNickname(JSONObject p){String number=PairedProtocol.number(p);if(number.isEmpty()){toast("This person has no number to attach a nickname to.");return;}
        EditText e=input("Nickname");e.setSingleLine(true);e.setText(Nicknames.get(number));
        new AlertDialog.Builder(this).setTitle("Nickname").setMessage("Shown instead of their name in this app, on this phone only. Leave empty to use their contact name.").setView(e)
            .setPositiveButton("Save",(d,w)->{Nicknames.set(this,number,e.getText().toString());photos.clear();MessagesWidget.update(this);if(screen.equals("details"))details();else if(screen.equals("inbox"))renderInbox();})
            .setNegativeButton("Cancel",null).show();}
    // ---- 0.8: undo send, undo delete, voice speed ----
    private static final long UNDO_SEND_MS=5000;
    private boolean undoSendOn(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("undo_send",true);}
    /** The undo window passed: send it (or keep it queued until the connection is back). */
    private void releaseHeld(String id){JSONObject o=outbox.get(id);if(o==null||!Outbox.HOLD.equals(o.optString("state")))return;outbox.setState(id,Outbox.WAITING,"");PairedService.flushOutboxNow(this);if(screen.equals("chat"))renderMessages(false);}
    /** Takes a just-sent message back and puts its text in the message box again. */
    private void undoSend(String id){JSONObject o=outbox.get(id);if(o==null||!Outbox.HOLD.equals(o.optString("state"))){toast("Already sent");return;}outbox.remove(id);
        if(screen.equals("chat")&&chatId.equals(o.optString("chat"))&&composer!=null){String now=composer.getText().toString();composer.setText(now.isEmpty()?o.optString("text"):o.optString("text")+" "+now);composer.setSelection(composer.getText().length());
            JSONObject target=o.optString("reply").isEmpty()?null:store.message(o.optString("reply"));if(target!=null)startReply(target);renderMessages(false);}
        toast("Message not sent");}
    private final Set<String> pendingDeletes=new HashSet<>();private View undoBar;
    /** A bar at the bottom with Undo; the action happens when it times out. */
    private void showUndo(String message,long ms,Runnable undo,Runnable commit){ViewGroup host=findViewById(android.R.id.content);if(undoBar!=null)host.removeView(undoBar);
        LinearLayout bar=row();bar.setPadding(dp(18),dp(12),dp(8),dp(12));bar.setBackground(shape(dark?0xffe3e2e9:0xff2f3036,14));bar.setElevation(dp(6));
        TextView t=text(message,15,dark?0xff1a1b20:Color.WHITE,false);bar.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        TextView u=text("Undo",15,dark?0xff3b5bd9:0xffa8c7fa,true);u.setPadding(dp(14),dp(6),dp(14),dp(6));bar.addView(u);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);lp.setMargins(dp(12),0,dp(12),dp(96));host.addView(bar,lp);undoBar=bar;
        boolean[] done={false};Runnable finish=()->{if(done[0])return;done[0]=true;host.removeView(bar);if(undoBar==bar)undoBar=null;commit.run();};
        u.setOnClickListener(v->{if(done[0])return;done[0]=true;host.removeView(bar);if(undoBar==bar)undoBar=null;undo.run();});ui.postDelayed(finish,ms);}
    /** Voice messages: 1×, 1.5×, or 2×, remembered between messages. */
    private float voiceSpeed(){return getSharedPreferences("paired",MODE_PRIVATE).getFloat("voice_speed",1f);}
    private void cycleVoiceSpeed(TextView chip){float next=voiceSpeed()==1f?1.5f:voiceSpeed()==1.5f?2f:1f;getSharedPreferences("paired",MODE_PRIVATE).edit().putFloat("voice_speed",next).apply();
        chip.setText(speedLabel(next));if(player!=null&&player.isPlaying()){try{player.setPlaybackParams(player.getPlaybackParams().setSpeed(next));}catch(Exception ignored){}}}
    private static String speedLabel(float s){return s==1f?"1×":s==1.5f?"1.5×":"2×";}
    // ---- 0.8: unknown senders, snooze, mention alerts, safe sharing ----
    private boolean unknownOnly,chatTrusted=true;
    /** A one-to-one chat with a number that isn't saved in your contacts. */
    private boolean unknownSender(JSONObject c){JSONObject other=PairedProtocol.other(c);return other!=null&&!PairedProtocol.known(other);}
    /** Only content from other apps: never a raw file path or this app's own storage (another app could point a share at it). */
    private boolean safeIncoming(Uri u){if(u==null)return false;String scheme=u.getScheme(),auth=u.getAuthority();
        if(!"content".equals(scheme))return false;return auth==null||!auth.startsWith(getPackageName());}
    private String snoozeLabel(String id){long until=chatSettings.snoozedUntil(id);
        boolean today=android.text.format.DateUtils.isToday(until);return (today?"":new SimpleDateFormat("EEE ",Locale.getDefault()).format(new Date(until)))+timeOnly(until);}
    /** Pause this chat's notifications for a while. @-mentions of you still come through. */
    private void snoozeMenu(String id){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();long now=System.currentTimeMillis();
        java.util.Calendar morning=java.util.Calendar.getInstance();morning.add(java.util.Calendar.DAY_OF_YEAR,1);morning.set(java.util.Calendar.HOUR_OF_DAY,8);morning.set(java.util.Calendar.MINUTE,0);morning.set(java.util.Calendar.SECOND,0);
        long[] times={now+3_600_000L,now+8*3_600_000L,morning.getTimeInMillis()};String[] names={"For 1 hour","For 8 hours","Until tomorrow, "+timeOnly(morning.getTimeInMillis())};
        for(int i=0;i<times.length;i++){long until=times[i];labels.add(names[i]);actions.add(()->{chatSettings.setSnooze(id,until);if(!bubble())PairedService.clearNotification(this,id);toast("Snoozed until "+snoozeLabel(id)+". @-mentions still notify you.");refreshAfterSetting();});}
        if(chatSettings.snoozed(id)){labels.add("Turn off snooze");actions.add(()->{chatSettings.setSnooze(id,0);toast("Notifications on for this chat");refreshAfterSetting();});}
        menuSheet("Snooze notifications",labels,actions);}
    private void refreshAfterSetting(){if(screen.equals("details"))details();else if(screen.equals("inbox"))renderInbox();}
    /** Settings: which names count as an @mention of you (alerts even in muted or snoozed chats). */
    private void editMentionNames(){EditText e=input("Your first name, or names separated by commas");e.setText(getSharedPreferences("paired",MODE_PRIVATE).getString("mention_name",""));
        new AlertDialog.Builder(this).setTitle("Mention alerts").setMessage("When someone writes @ and one of these names in a group, you're notified even if the chat is muted or snoozed.").setView(e)
            .setPositiveButton("Save",(d,w)->{getSharedPreferences("paired",MODE_PRIVATE).edit().putString("mention_name",e.getText().toString().trim()).apply();settings();}).setNegativeButton("Cancel",null).show();}
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
            String label=switch(state){case Outbox.HOLD->"Sending… • Tap to undo";case Outbox.SENDING->"Sending…";case Outbox.UNSURE->"Not sure this sent • Tap for options";case Outbox.FAILED->"Not sent • Tap to retry";default->"Waiting for connection • will send automatically";};
            TextView s=text(label,12,state.equals(Outbox.FAILED)||state.equals(Outbox.UNSURE)?danger:muted,state.equals(Outbox.FAILED));s.setGravity(Gravity.END);s.setPadding(0,dp(4),dp(6),0);items.addView(s,new LinearLayout.LayoutParams(-1,-2));
            View.OnClickListener act=v->outboxOptions(o);b.setOnClickListener(act);s.setOnClickListener(act);}}
    private void outboxOptions(JSONObject o){String id=o.optString("id"),state=o.optString("state");if(state.equals(Outbox.SENDING))return;
        if(state.equals(Outbox.HOLD)){undoSend(id);return;}
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
    /** Hides the chat right away and deletes it from your phone after a few seconds, unless Undo is tapped. */
    private void deleteChat(JSONObject c){String id=c.optString("conversationID");pendingDeletes.add(id);if(!screen.equals("inbox"))inbox();else renderInbox();
        showUndo("Conversation deleted",6000,()->{pendingDeletes.remove(id);if(screen.equals("inbox"))renderInbox();},
            ()->PairedService.execute(cl->{cl.deleteChat(c.toString());return "";},(v,e)->{pendingDeletes.remove(id);if(!e.isEmpty()){error(e);if(screen.equals("inbox"))renderInbox();return;}
                store.deleteChat(id);PairedService.clearNotification(this,id);getSystemService(NotificationManager.class).cancel("chat:"+id,1);if(screen.equals("inbox"))renderInbox();}));}
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
        if(request==LOCATION_PERMISSION){boolean any=false;for(int r:results)if(r==PackageManager.PERMISSION_GRANTED)any=true;if(any)shareLocation();else toast("Location permission is needed to share where you are.");return;}
        if(request==79&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){phoneChecked.clear();photos.clear();if(screen.equals("inbox"))renderInbox();else if(screen.equals("settings"))settings();}
        if(request==78){JSONObject pending=pendingTranscription;pendingTranscription=null;if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&pending!=null)transcribe(pending);else if(pending!=null)toast("Transcription needs microphone access.");}}
    /** Plays an audio attachment in place; tap again to pause. */
    private void playAudio(JSONObject media,TextView label){String id=PairedProtocol.string(media,"mediaID");
        if(player!=null&&id.equals(playingId)){if(player.isPlaying())player.pause();else player.start();playingLabel=label;updatePlayLabel();return;}
        stopAudio();withFile(media,"Downloading…",file->{try{player=new MediaPlayer();player.setDataSource(file.getPath());player.prepare();player.setOnCompletionListener(mp->{stopAudio();renderMessages(false);});player.start();
            if(voiceSpeed()!=1f){try{player.setPlaybackParams(player.getPlaybackParams().setSpeed(voiceSpeed()));}catch(Exception ignored){}}
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
        int before=uris.size();uris.removeIf(u->!safeIncoming(u));if(uris.size()<before)toast("Skipped a file that can't be shared from there.");
        String shortcut=intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID);String target=shortcut!=null&&shortcut.startsWith("chat-")?shortcut.substring(5):"";
        incoming.clear();
        decoder.execute(()->{List<Outgoing> copied=new ArrayList<>();String problem=null;for(Uri u:uris){try{copied.add(copyIn(u));}catch(Exception e){problem=e.getMessage();}}String p=problem;
            ui.post(()->{incoming.addAll(copied);if(p!=null)toast(p);if(!target.isEmpty()&&store.chat(target)!=null)thread(target);else{folder=1;inbox();}});});
        return true;}
    private boolean hasIncoming(){return !incoming.isEmpty()||(incomingText!=null&&!incomingText.isEmpty());}
    private void applyIncoming(){if(!hasIncoming()||composer==null)return;forwardingNow=false;outgoingChat=chatId;outgoing.addAll(incoming);incoming.clear();
        if(incomingText!=null){String now=composer.getText().toString();composer.setText(now.isEmpty()?incomingText:now+" "+incomingText);composer.setSelection(composer.getText().length());incomingText=null;}
        renderAttachments();updateComposeButton();}
    private View shareBanner(){LinearLayout card=row();card.setPadding(dp(18),dp(12),dp(10),dp(12));card.setBackground(shape(fab,18));
        int n=incoming.size();String verb=forwardingNow?"Forwarding":"Sharing";TextView t=text(n==0?(forwardingNow?"Forwarding • pick a conversation":"Pick a conversation to share to"):verb+" "+n+(n==1?" item":" items")+" • pick a conversation",15,onFab,true);card.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        TextView cancel=text("Cancel",15,onFab,false);cancel.setPadding(dp(10),dp(4),dp(8),dp(4));cancel.setOnClickListener(v->{for(Outgoing o:incoming)o.file.delete();incoming.clear();incomingText=null;forwardingNow=false;inbox();});card.addView(cancel);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(12),dp(4),dp(12),dp(8));card.setLayoutParams(lp);return card;}

    // Links
    private boolean linkPreviewsOn(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("link_previews",true);}
    /** Title and picture for the first link in a message, under its bubble; tap opens the link. */
    private View linkCard(String url,boolean outgoing){LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);line.setVisibility(View.GONE);
        LinearLayout card=vertical();card.setBackground(shape(surface,18));card.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(18));}});card.setClipToOutline(true);
        int width=Math.round(getResources().getDisplayMetrics().widthPixels*0.66f);line.addView(card,new LinearLayout.LayoutParams(width,-2));
        LinkPreviews.Done fill=p->{card.removeAllViews();String image=p.optString("image");int iw=p.optInt("imageW"),ih=p.optInt("imageH");
            // Big pictures go on top; small ones (logos, square product shots under 300px) sit beside the title instead of being stretched.
            boolean hero=!image.isEmpty()&&iw>=300&&ih>=150,thumb=!image.isEmpty()&&!hero;int heroH=hero?Math.max(dp(110),Math.min(dp(190),Math.round(width*(float)ih/iw))):0;
            if(hero){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setVisibility(View.GONE);card.addView(img,new LinearLayout.LayoutParams(-1,heroH));
                decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(new File(image),width,heroH,false);if(b!=null)img.post(()->{img.setImageBitmap(b);img.setVisibility(View.VISIBLE);});});}
            LinearLayout body=row();body.setGravity(Gravity.CENTER_VERTICAL);body.setPadding(dp(14),dp(10),dp(14),dp(12));
            if(thumb){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackground(shape(bg,10));
                img.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(10));}});img.setClipToOutline(true);
                LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(dp(56),dp(56));tl.setMarginEnd(dp(12));body.addView(img,tl);
                decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(new File(image),dp(56),dp(56),false);if(b!=null)img.post(()->img.setImageBitmap(b));});}
            LinearLayout texts=vertical();TextView title=text(p.optString("title"),15,ink,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);texts.addView(title);
            String desc=p.optString("description");if(!desc.isEmpty()&&!desc.equals(p.optString("title"))){TextView d=text(desc,13,ink,false);d.setAlpha(0.8f);d.setMaxLines(2);d.setEllipsize(TextUtils.TruncateAt.END);d.setPadding(0,dp(2),0,0);texts.addView(d);}
            String site=p.optString("site");TextView sv=text(site.isEmpty()?ChatText.domain(url):site,13,muted,false);sv.setPadding(0,dp(2),0,0);texts.addView(sv);
            body.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(body);line.setVisibility(View.VISIBLE);};
        card.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("No browser is available.");}});
        JSONObject ready=LinkPreviews.peek(url);if(ready!=null){if(!ready.optString("title").isEmpty())fill.done(ready);}else LinkPreviews.load(this,url,fill);
        return line;}
    private void settings(){remember();base("settings");header("Settings",this::inbox);connection();ScrollView sc=new ScrollView(this);LinearLayout content=vertical();sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        note(content,"Message Center "+BuildConfig.VERSION_NAME+" • experimental paired client\n\nThe connection runs on this phone and talks directly to Google. Google Messages must remain your default messaging app and have network/background access.");
        int phone=chatSettings.phoneReceipts();
        note(content,"Read receipts\n\nOff for every chat unless you turn them on for that chat (long-press it in the list, or tap its name at the top of the chat). When on, opening the chat here marks it read on your phone and Google Messages sends a receipt. When off, the chat is not marked read, so it stays unread in Google Messages.\n\nGoogle Messages' own \"Send read receipts\" setting: "+(phone==1?"On":phone==0?"Off: no receipts are sent for any chat":"not reported yet")+".\n\nPins and chat themes are kept in this app. Google's protocol has no pin action, so pinning here does not change Google Messages.");
        boolean typingOn=getSharedPreferences("paired",MODE_PRIVATE).getBoolean("send_typing",true);
        content.addView(button(typingOn?"Typing indicators: on (others see when you type)":"Typing indicators: off",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("send_typing",!typingOn).apply();settings();}));gap(content,8);
        String themeMode=getSharedPreferences("paired",MODE_PRIVATE).getString("theme","system");
        content.addView(button("Theme: "+(themeMode.equals("light")?"Light":themeMode.equals("dark")?"Dark":"Match phone")+" (tap to change)",false,()->{String next=themeMode.equals("system")?"light":themeMode.equals("light")?"dark":"system";appTheme(next);}));gap(content,8);
        content.addView(button("Text size: "+scaleLabel(textScale)+" (tap to change)",false,()->{float next=textScale<1f?1f:textScale<1.15f?1.15f:textScale<1.3f?1.3f:0.9f;getSharedPreferences("paired",MODE_PRIVATE).edit().putFloat("text_scale",next).apply();settings();}));gap(content,8);
        long arUntil=PairedService.autoReplyUntil(this);
        content.addView(button(arUntil>System.currentTimeMillis()?"Busy auto-reply: on"+(arUntil==Long.MAX_VALUE?"":" until "+timeOnly(arUntil)):"Busy auto-reply: off",false,this::autoReplyMenu));
        note(content,"Replies once an hour per person, only to saved contacts in one-to-one chats: \u201c"+PairedService.autoReplyText(this)+"\u201d");
        String keywords=getSharedPreferences("paired",MODE_PRIVATE).getString("alert_keywords","");
        content.addView(button(keywords.isEmpty()?"Keyword alerts: add words":"Keyword alerts: "+keywords,false,this::editKeywords));gap(content,8);
        content.addView(button("Quick replies"+(quickRepliesOn()?" ("+quickReplies().size()+")":": off"),false,this::editQuickReplies));gap(content,8);
        boolean promosOn=promotionsSeparate();
        content.addView(button(promosOn?"Promotions: kept out of the inbox":"Promotions: shown in the inbox",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("promotions_separate",!promosOn).apply();settings();}));gap(content,8);
        content.addView(button("Delete old verification codes…",false,this::clearOldCodes));gap(content,8);
        int nudge=Nudges.minutes(this);
        content.addView(button(nudge<=0?"Unread reminders for pinned chats: off":"Unread reminders for pinned chats: every "+(nudge<60?nudge+" minutes":nudge==60?"hour":(nudge/60)+" hours"),false,()->{
            List<String> labels=java.util.Arrays.asList("Every hour","Every 2 hours","Every 4 hours","Off");int[] values={60,120,240,0};List<Runnable> actions=new ArrayList<>();
            for(int v:values)actions.add(()->{decoder.execute(()->Nudges.setMinutes(this,v));ui.postDelayed(this::settings,150);});
            menuSheet("Remind me about unread pinned chats",labels,actions);}));
        note(content,"When someone in a pinned chat messages you and it's still unread (or marked unread), you get a reminder, up to 3 times. Not between 10 PM and 8 AM, and not for muted or snoozed chats. \"Not until next message\" on the reminder stops it for that chat.");
        content.addView(button("Maps app for addresses: "+mapsAppLabel(),false,()->chooseMapsApp(null)));gap(content,8);
        content.addView(button("Back up app data",false,this::backupNow));gap(content,8);
        content.addView(button("Restore from a backup",false,()->{try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"}),RESTORE_BACKUP);}catch(Exception e){toast("No file picker is available.");}}));gap(content,8);
        String mentionNames=getSharedPreferences("paired",MODE_PRIVATE).getString("mention_name","");
        content.addView(button(mentionNames.isEmpty()?"Mention alerts: add your name":"Mention alerts for @"+mentionNames,false,this::editMentionNames));gap(content,8);
        boolean undoOn=undoSendOn();content.addView(button(undoOn?"Undo send: on (5 seconds to take a message back)":"Undo send: off",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("undo_send",!undoOn).apply();settings();}));gap(content,8);
        boolean copyOn=PairedService.autoCopyCodes(this);
        content.addView(button(copyOn?"Auto-copy verification codes: on":"Auto-copy verification codes: off",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("auto_copy_codes",!copyOn).apply();settings();}));gap(content,8);
        content.addView(button("Bubble settings",false,()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))));gap(content,8);
        content.addView(button("Reconnect",true,()->{PairedService.disconnect(this,false);new android.os.Handler().postDelayed(()->{PairedService.start(this);settings();},300);}));gap(content,8);
        content.addView(button("Sign in / pair again",false,this::signIn));gap(content,8);
        content.addView(button("Allow Message Center notifications",false,()->{if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}));gap(content,8);
        if(!Avatars.canUsePhonePhotos(this)){content.addView(button("Use full-size photos from my contacts",false,()->{if(shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)||!getSharedPreferences("paired",MODE_PRIVATE).getBoolean("asked_contacts",false)){getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("asked_contacts",true).apply();requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},79);}
            else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}));gap(content,8);}
        note(content,"Background reliability\n\nAndroid reports battery optimization: "+(batteryRestricted()?"on. If you already allowed background activity in ColorOS's own settings, that is enough; Android may still say on.":"off ✓")+"\n\nOn ColorOS, also open App settings → Battery and allow background activity and auto-launch, for both Message Center and Google Messages.");
        if(batteryRestricted()){content.addView(button("Allow running in the background",true,this::allowBackground));gap(content,8);}
        content.addView(button("App settings (battery, auto-launch)",false,this::openAppSettings));gap(content,8);
        boolean full=fullPhotos();content.addView(button(full?"Photos in chats: full quality":"Photos in chats: previews only (uses less data)",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("full_photos",!full).apply();settings();}));gap(content,8);
        boolean links=linkPreviewsOn();content.addView(button(links?"Link previews: on for contacts and chats you've written in":"Link previews: off",false,()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("link_previews",!links).apply();settings();}));gap(content,8);
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
        setRecentsScreenshotEnabled(false);login=new WebView(this);login.getSettings().setJavaScriptEnabled(true);login.getSettings().setDomStorageEnabled(true);
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
    private void destroyLogin(){loginTimer.removeCallbacks(loginPoll);loginAddress=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);setRecentsScreenshotEnabled(true);if(login!=null){login.stopLoading();((ViewGroup)login.getParent()).removeView(login);login.destroy();login=null;}}
    private void askNotifications(){if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !getSharedPreferences("paired",MODE_PRIVATE).getBoolean("asked_notifications",false)){getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("asked_notifications",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);}}
    private void pairing(){base("pair");header("Pair this phone",null);connection();String emoji=PairedService.emoji();shownEmoji=emoji;TextView symbol=text(emoji.isEmpty()?"…":emoji,72,ink,false);symbol.setGravity(Gravity.CENTER);root.addView(symbol);gap(root,16);
        note(root,"Open Google Messages → Device pairing and approve the matching emoji. Return here afterward.\n\nIf Google reports no device, check that Device pairing uses the same Google account you just signed into.");root.addView(button("Open Google Messages",true,this::openGoogle));gap(root,8);root.addView(button("Back to inbox",false,this::inbox));}
    private void openGoogle(){Intent intent=getPackageManager().getLaunchIntentForPackage("com.google.android.apps.messaging");if(intent!=null)startActivity(intent);else toast("Google Messages is not installed.");}
    @Override protected void onDestroy(){destroyLogin();cancelRecording();stopAudio();decoder.shutdownNow();super.onDestroy();}
}
