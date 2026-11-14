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
import android.view.MotionEvent;
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
    /** From a bubble: the full app, on this conversation (the bubble steps aside while it's open there). */
    private void openInApp(String id){startActivity(new Intent(this,PairedActivity.class).setAction(OPEN_CHAT).putExtra("chat",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP));}
    private static final int BLUE=0xff2864f0;
    private static final long LOGIN_POLL_MS=1500;
    private static final String[] REACTIONS={"❤️","👍","👎","😂","😮","😢","❓","🎉"};
    private int bg,card,ink,muted,surface,fab,onFab,danger,folder=1,limit=WINDOW;
    /**
     * Messages drawn when a chat opens; scrolling to the top draws this many more saved ones.
     * Every redraw lays out each drawn message, so drawing fewer saves battery.
     */
    private static final int WINDOW=120;private boolean moreSaved;
    private boolean dark;
    private PairedStore store;private ChatSettings chatSettings;
    private FrameLayout frame;private LinearLayout root,items,replyPanel,typingRow;private LinearLayout statusRow;private TextView reconnectButton,receiptsMark;private TextView statusView,replyLabel,replyQuote,typingLabel;private ObjectAnimator typingPulse;
    private ScrollView scroll;private EditText composer;private WebView login;private LinearLayout searchRow;private TextView receiptStatusView;
    private String screen="inbox",chatId="",query="",replyId="";
    private Drafts drafts;
    // Another window (the bubble, or the full app) changed this chat's draft: show it here too.
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener draftChanged=(prefs,key)->{if(key!=null&&key.equals(chatId)&&!hasWindowFocus())refreshDraft();};
    /**
     * Inbox rows as last drawn, by chat, with what they were drawn from. A row whose chat hasn't
     * changed is put back as it was, so going back from a chat doesn't rebuild the whole inbox.
     */
    private final Map<String,Object[]> rowCache=new HashMap<>();
    private final Set<String> photosRequested=new HashSet<>();
    private boolean registered,pairSubmitted,busy,resumed,anchorOlder;private String shownEmoji="";
    private long lastInboxRefresh,lastTypingSent;
    /** True while the chat is scrolled to the newest message, so late layout changes keep it there. */
    private boolean pinnedBottom=true;
    /** A place in a chat: a message and how far below the top of the screen it starts. */
    private static final class Spot{final String id;final int offset;Spot(String id,int offset){this.id=id;this.offset=offset;}}
    /** The chat redraw still waiting to be laid out, and where it will put the chat (the bottom, or a spot). */
    private android.view.ViewTreeObserver.OnPreDrawListener placing;private ScrollView placingIn;private boolean placeBottom;private Spot placeSpot;private int placeY,placeBefore;
    /** Where you were in a chat before opening its Details, and a spot the next redraw should go back to. */
    private String backChat="";private Spot backSpot,restoreSpot;private boolean backFromSaved;
    /**
     * While you're scrolled up in a chat: the message at the top of the screen and where it sits.
     * When something above it changes height after it was drawn (a photo or sticker finishing
     * loading, a link preview filling in), the chat shifts by the same amount, so what you're
     * reading stays still. Browsers call this scroll anchoring.
     */
    private Spot keepSpot;private boolean keeperAdded;
    /** iPhone reactions that arrived as text, placed on the messages they're about: message ID → {emoji, participant}. */
    private Map<String,List<String[]>> tapbacks=new HashMap<>();
    private final Map<String,View> messageViews=new HashMap<>();
    /** Each message's bubble (or its photo, when there's no text), for effects that light it up. */
    private final Map<String,View> bubbleViews=new HashMap<>();
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable typingTick=this::updateTyping;
    /** True when this screen is shown inside an Android conversation bubble (see BubbleActivity). */
    protected boolean bubble(){return false;}
    private TextView loginAddress;private int signedInPolls,loginSkips;
    private final android.os.Handler loginTimer=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable loginPoll=this::pollLogin;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if("PHOTO_TEXT".equals(i.getStringExtra("kind"))){searchedFor="";if(screen.equals("inbox")){ui.removeCallbacks(refreshScreen);ui.postDelayed(refreshScreen,REDRAW_BURST_MS);}return;}
        if("TYPING".equals(i.getStringExtra("kind"))){updateTyping();return;}
        if(!bubble() && !PairedService.emoji().isEmpty() && !shownEmoji.equals(PairedService.emoji())){destroyLogin();pairing();return;}
        if(screen.equals("pair") && PairedService.ready()){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();return;}
        updateStatus();
        updateReceiptStatus();
        // A connection-status change only needs the status line, unless being connected changed (photo tiles, empty states).
        boolean ready=PairedService.ready();if("STATUS".equals(i.getStringExtra("kind"))&&ready==drawnReady)return;drawnReady=ready;
        // Updates come in bursts (sending one message brings several within a moment): redraw once per burst.
        ui.removeCallbacks(refreshScreen);ui.postDelayed(refreshScreen,REDRAW_BURST_MS);
    }};
    private static final long REDRAW_BURST_MS=80;private boolean drawnReady;
    private final Runnable refreshScreen=()->{if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat")){renderMessages(false);markReadIfAllowed();updateTyping();}};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);Diagnostics.init(this);HevcSandbox.init(this);GivenNames.init(this);drafts=new Drafts(this);drafts.prefs().registerOnSharedPreferenceChangeListener(draftChanged);store=new PairedStore(this);chatSettings=new ChatSettings(this);outbox=new Outbox(this);starred=new Starred(this);pins=new ChatPins(this);Nicknames.load(this);
        if(state!=null){String shot=state.getString("capture");if(shot!=null)pendingCapture=new File(shot);chatId=state.getString("chat","");replyId=state.getString("reply","");if(drafts.getOrDefault(chatId,"").isEmpty())drafts.put(chatId,state.getString("draft",""));}
        String target=getIntent().getStringExtra("chat");if(target!=null&&!target.isEmpty())chatId=target;
        // Android closed this screen in the background (or the theme changed): reopen the chat where you were reading.
        if(state!=null&&state.getString("spot")!=null&&!chatId.isEmpty()&&chatId.equals(state.getString("chat",""))){backChat=chatId;backSpot=new Spot(state.getString("spot"),state.getInt("spot_offset"));backFromSaved=true;}
        if(bubble()&&chatId.isEmpty()){finish();return;}
        if(!chatId.isEmpty()){thread(chatId);showFromIntent(getIntent());}else if(state!=null&&"settings".equals(state.getString("screen"))&&!bubble()){pendingSettingsY=state.getInt("settings_y",0);settingsPage=state.getString("settings_page","");keepSettingsPage=true;settings();}else inbox();
        if(state==null&&!bubble())receiveShare(getIntent());
        if(state==null&&!bubble()&&"settings".equals(getIntent().getStringExtra("open")))openSettings();
        if(state==null&&MessagesWidget.NEW_CHAT.equals(getIntent().getAction()))newMessage();
        if(new SessionVault(this).exists() && getSharedPreferences("paired",MODE_PRIVATE).getBoolean("enabled",true))PairedService.start(this);
    }
    /** A tapped notification (or bubble) delivers its chat here when this screen is already open. */
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(!bubble()&&receiveShare(intent))return;if(MessagesWidget.NEW_CHAT.equals(intent.getAction())){if(login!=null)destroyLogin();inbox();newMessage();return;}String id=intent.getStringExtra("chat");if(id!=null&&!id.isEmpty()){if(login!=null)destroyLogin();thread(id);showFromIntent(intent);}}
    /** On screen (started), as opposed to resumed: a permission prompt over the chat doesn't count as leaving it. */
    private boolean started;
    @Override protected void onStart(){super.onStart();started=true;reportChatOnScreen();}
    @Override protected void onStop(){started=false;reportChatOnScreen();super.onStop();}
    /** Tells ChatBubbles which chat you're in (its Details and Shared count), so that chat's bubble steps aside. */
    private void reportChatOnScreen(){if(bubble())return;boolean inChat=screen.equals("chat")||screen.equals("details")||screen.equals("media")||screen.equals("notes")||screen.equals("note");
        ChatBubbles.showing(this,started&&inChat?chatId:"");}
    @Override protected void onResume(){super.onResume();resumed=true;timeFormat=null;dateFormat=null;PairedService.screenVisible(true);refreshDraft();if(!registered){registerReceiver(receiver,new IntentFilter(PairedService.CHANGED),Context.RECEIVER_NOT_EXPORTED);registered=true;}
        PhotoText.indexDownloaded(this,false);watchCalls(true);if(screen.equals("chat")&&!chatId.isEmpty())loadCalls(chatId);
        if(PairedService.ready()&&screen.equals("pair")){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();}
        else if(!bubble()&&!PairedService.emoji().isEmpty()&&login==null)pairing();
        else {updateStatus();PairedService.syncNow();
            // Updates that arrived while this screen was hidden (or a bubble was collapsed) are shown now, then refreshed from Google.
            if(screen.equals("chat")){renderMessages(false);if(PairedService.ready())loadMessages(false);markReadIfAllowed();updateTyping();if(!bubble())PairedService.clearNotification(this,chatId);}
            else if(screen.equals("inbox")){if(batteryCard!=null&&!showBatteryCard()){root.removeView(batteryCard);batteryCard=null;}renderInbox();refreshInbox(false);
                if(!bubble()&&PairedService.ready()&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)askContactPhotos();}}}
    @Override protected void onPause(){saveNote();watchCalls(false);if(resumed)PairedService.screenVisible(false);resumed=false;if(dictation!=null)stopDictation();remember();if(inline!=null)inline.pause();ui.removeCallbacks(typingTick);if(recorder!=null)finishRecording(false);if(player!=null&&!playingId.isEmpty())pauseVoice();if(registered){unregisterReceiver(receiver);registered=false;}super.onPause();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)refreshDraft();if(focused&&store!=null){markReadIfAllowed();updateReceiptStatus();}}
    /** Picks up a draft typed in the other window (bubble or full app) so you carry on where you left off. */
    private void refreshDraft(){if(composer==null||drafts==null||chatId.isEmpty()||!screen.equals("chat"))return;String stored=drafts.getOrDefault(chatId,"");
        if(stored.equals(composer.getText().toString()))return;composer.setText(stored);composer.setSelection(stored.length());}
    @Override protected void onSaveInstanceState(Bundle out){remember();out.putString("screen",screen);if(screen.equals("settings")&&settingsScroll!=null){out.putInt("settings_y",settingsScroll.getScrollY());out.putString("settings_page",settingsPage);}if(pendingCapture!=null)out.putString("capture",pendingCapture.getPath());out.putString("chat",chatId);if(screen.equals("chat")){Spot at=chatSpotNow();if(at!=null){out.putString("spot",at.id);out.putInt("spot_offset",at.offset);}}out.putString("reply",replyId);out.putString("draft",drafts.getOrDefault(chatId,""));super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(selecting){stopSelecting();return;}if(screen.equals("note")){leaveNote();return;}if(screen.equals("settings")){settingsBack();return;}if(screen.equals("notes")){leaveNotes();return;}if(screen.equals("media")){details();return;}if(screen.equals("details")){thread(chatId);return;}if(bubble()){super.onBackPressed();return;}if(login!=null){destroyLogin();inbox();}else if(screen.equals("inbox")&&(folder!=1||unknownOnly||promoOnly||needsReplyOnly)){folder=1;unknownOnly=false;promoOnly=false;needsReplyOnly=false;inbox();}else if(!screen.equals("inbox")){remember();inbox();}else super.onBackPressed();}

    // ---- Shared building blocks ----
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void base(String name){
        if(screen.equals("note"))saveNote();noteField=null;noteTags=null;noteFiles=null;notePin=null;noteStatus=null;editing=null;
        if(!name.equals("chat"))stopInline();
        if(screen.equals("inbox")&&!name.equals("inbox")&&scroll!=null){inboxScrollY=scroll.getScrollY();inboxBack=inboxSpotNow();inboxKey=inboxKey();repliedSinceInbox=false;}
        inboxPlacing=null;inboxSpots=null;keepSpot=null;
        // Leaving a chat for another screen (Details): note where you were, so coming back puts you there instead of at the bottom.
        if(screen.equals("chat")&&!name.equals("chat")&&scroll!=null){backChat=chatId;backSpot=chatSpotNow();}
        placing=null;placingIn=null;
        receiptStatusView=null;
        // Screenshots are always allowed (an older version could leave them blocked after signing in).
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);if(!name.equals("login"))setRecentsScreenshotEnabled(true);
        if(recorder!=null)cancelRecording();if(dictation!=null)stopDictation();recordBar=null;composeRow=null;sendButton=null;recordClock=null;recordWave=null;recordDot=null;screen=name;long perfBubbles=Perf.start();reportChatOnScreen();Perf.end("screen: bubbles",perfBubbles);batteryCard=null;jumpButton=null;statusView=null;statusRow=null;reconnectButton=null;receiptsMark=null;composer=null;items=null;scroll=null;searchRow=null;attachStrip=null;attachScroll=null;replyPanel=null;typingRow=null;messageViews.clear();bubbleViews.clear();playing=null;voiceCards.clear();voiceOrder.clear();ui.removeCallbacks(typingTick);if(typingPulse!=null){typingPulse.cancel();typingPulse=null;}
        dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        boolean black=dark&&"black".equals(getSharedPreferences("paired",MODE_PRIVATE).getString("theme","system"));
        if(blackForPictures){dark=true;black=true;}
        textScale=getSharedPreferences("paired",MODE_PRIVATE).getFloat("text_scale",1f);quickRow=null;quickScroll=null;searchChips=null;selecting=false;chosen.clear();selectionBar=null;chatFrame=null;
        int[] colors=palette(dark,black);
        bg=colors[0];card=colors[1];surface=colors[2];ink=colors[3];muted=colors[4];
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
        i.setContentDescription(label);if(action!=null){i.setOnClickListener(v->action.run());pressable(i);}
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return i;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextColor(ink);e.setHintTextColor(muted);e.setTextSize(16);e.setPadding(dp(16),dp(12),dp(16),dp(12));e.setBackground(shape(card,18));return e;}
    private void header(String title,Runnable back){LinearLayout r=row();if(back!=null)r.addView(icon(R.drawable.ic_back,ink,"Back",back));TextView heading=text(title,24,ink,false);heading.setPadding(dp(back==null?4:8),0,dp(8),0);r.addView(heading,new LinearLayout.LayoutParams(0,-2,1));root.addView(r);gap(root,10);}
    /** The connection line, with a Reconnect button on the inbox and in chats when that would help. */
    private void connection(){LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(4),0,dp(4),dp(8));statusRow=line;
        statusView=text(PairedService.status(this),13,muted,false);line.addView(statusView,new LinearLayout.LayoutParams(0,-2,1));
        int accent=ChatTheme.of("default").accent(dark);
        reconnectButton=text("Reconnect",14,accent,true);reconnectButton.setPadding(dp(14),dp(7),dp(14),dp(7));reconnectButton.setBackground(shape((accent&0x00FFFFFF)|0x26000000,16));
        reconnectButton.setContentDescription("Reconnect to Google Messages");reconnectButton.setOnClickListener(v->{PairedService.restart(this);reconnectButton.setVisibility(View.GONE);});
        LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-2,-2);bl.setMarginStart(dp(10));line.addView(reconnectButton,bl);root.addView(line);updateStatus();}
    /** The connection line only shows while something needs attention. */
    private final Runnable statusRecheck=this::updateStatus;
    private void updateStatus(){if(statusView==null)return;statusView.setText(PairedService.status(this));
        // A drop of a few seconds that's already on its way back isn't mentioned; look again once it's been long enough.
        long blip=PairedService.quietFor();ui.removeCallbacks(statusRecheck);if(blip>0)ui.postDelayed(statusRecheck,blip+200);
        boolean main=screen.equals("inbox")||screen.equals("chat"),quiet=main&&(PairedService.ready()||blip>0);
        if(statusRow!=null)statusRow.setVisibility(quiet?View.GONE:View.VISIBLE);
        if(reconnectButton!=null)reconnectButton.setVisibility(main&&!bubble()&&blip==0&&PairedService.canReconnect(this)?View.VISIBLE:View.GONE);}
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
    /** The other person's photo at the size it's shown (decoded once, then shared; see Avatars.face). */
    private Bitmap photo(JSONObject chat,int px){return Avatars.face(this,PairedProtocol.participantID(PairedProtocol.other(chat)),px);}
    private View avatar(JSONObject chat,int size){
        FrameLayout box=new FrameLayout(this);box.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));
        if(Avatars.isGroup(chat)){box.addView(roundImage(Avatars.group(this,chat,dp(size))),new FrameLayout.LayoutParams(-1,-1));box.setContentDescription(Avatars.groupLabel(chat));return box;}
        Bitmap b=photo(chat,dp(size));
        if(b!=null){box.addView(roundImage(b),new FrameLayout.LayoutParams(-1,-1));return box;}
        box.setBackground(oval(avatarColor(chat)));
        if(chat!=null&&PairedProtocol.other(chat)==null&&chat.optBoolean("isGroupChat")){
            ImageView people=new ImageView(this);people.setImageResource(R.drawable.ic_people);people.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            int pad=dp(size/4f);people.setPadding(pad,pad,pad,pad);box.addView(people,new FrameLayout.LayoutParams(-1,-1));return box;}
        TextView letter=text(PairedProtocol.initial(chat==null?"":PairedProtocol.title(chat)),Math.round(size*0.42f),Color.WHITE,false);letter.setGravity(Gravity.CENTER);
        box.addView(letter,new FrameLayout.LayoutParams(-1,-1));return box;
    }
    /** A soft press ripple in the text color (the old list selector flashed yellow on some phones). */
    /** Press feedback without a highlight box: the item dims a little while your finger is on it. */
    private static void pressable(View v){v.setBackground(null);pressDim(v);}
    /** The same dimming on press, for something that keeps its own background (a round button). */
    private static void pressDim(View v){android.animation.StateListAnimator dim=new android.animation.StateListAnimator();
        dim.addState(new int[]{android.R.attr.state_pressed},android.animation.ObjectAnimator.ofFloat(null,"alpha",0.55f).setDuration(70));
        dim.addState(new int[0],android.animation.ObjectAnimator.ofFloat(null,"alpha",1f).setDuration(160));v.setStateListAnimator(dim);}
    private ImageView roundImage(Bitmap b){ImageView img=new ImageView(this);img.setImageBitmap(b);img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setOval(0,0,v.getWidth(),v.getHeight());}});img.setClipToOutline(true);return img;}
    /** One person's photo, or their initial on their Google color. */
    private View personAvatar(JSONObject p,String name,int size){
        FrameLayout box=new FrameLayout(this);box.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));String id=PairedProtocol.participantID(p);Bitmap b=null;
        if(!id.isEmpty())b=Avatars.face(this,id,dp(size));
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
            ui.post(()->{rowCache.clear();PairedService.refreshConversationIcons();if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat"))renderMessages(false);});});}
    private void askContactPhotos(){android.content.SharedPreferences prefs=getSharedPreferences("paired",MODE_PRIVATE);
        if(Avatars.canUsePhonePhotos(this)||prefs.getBoolean("asked_contacts",false))return;prefs.edit().putBoolean("asked_contacts",true).apply();
        new AlertDialog.Builder(this).setTitle("Sharper contact photos?").setMessage("Google only sends small photos. Message Center can use the full-size photos from your phone's contacts for chats, bubbles, notifications, and the widget. They stay on this phone.")
            .setPositiveButton("Allow",(d,w)->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_PERMISSION)).setNegativeButton("Not now",null).show();}
    /** Requests contact photos once per launch for one-to-one chats that do not have one cached. */
    private void requestPhotos(List<JSONObject> chats){
        phonePhotos(chats);
        if(!PairedService.ready())return;List<String> ids=new ArrayList<>();
        outer:for(JSONObject c:chats){List<JSONObject> people=Avatars.isGroup(c)?Avatars.groupMembers(c):java.util.Collections.singletonList(PairedProtocol.other(c));
            for(int i=0;i<Math.min(3,people.size());i++){String id=PairedProtocol.participantID(people.get(i));
                if(id.isEmpty()||photosRequested.contains(id)||Avatars.file(this,id).isFile())continue;photosRequested.add(id);ids.add(id);if(ids.size()==25)break outer;}}
        if(ids.isEmpty())return;
        PairedService.loadPhotos(ids,(v,e)->{if(!e.isEmpty())return;PairedService.refreshConversationIcons();if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat"))renderMessages(false);});
    }

    // ---- Time labels ----
    // Made once per screen: building a date format is slow, and the inbox needs one per row.
    private java.text.DateFormat timeFormat,weekdayFormat,monthDayFormat,dateFormat;
    private String timeOfDay(long t){if(timeFormat==null)timeFormat=DateFormat.getTimeFormat(this);return timeFormat.format(new Date(t));}
    private String inboxStamp(long t){
        switch(InboxOrder.stamp(t,System.currentTimeMillis(),TimeZone.getDefault())){
            case TIME:return timeOfDay(t);
            case WEEKDAY:if(weekdayFormat==null)weekdayFormat=new SimpleDateFormat("EEE",Locale.getDefault());return weekdayFormat.format(new Date(t));
            case MONTH_DAY:if(monthDayFormat==null)monthDayFormat=new SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(),"MMMd"),Locale.getDefault());return monthDayFormat.format(new Date(t));
            default:if(dateFormat==null)dateFormat=DateFormat.getDateFormat(this);return dateFormat.format(new Date(t));
        }
    }
    private String separatorStamp(long t){
        InboxOrder.Stamp s=InboxOrder.stamp(t,System.currentTimeMillis(),TimeZone.getDefault());
        return s==InboxOrder.Stamp.TIME?timeOfDay(t):inboxStamp(t)+" • "+timeOfDay(t);
    }

    // ---- Inbox ----
    private void inbox(){if(bubble()){if(!chatId.isEmpty())thread(chatId);else finish();return;}long perfAll=Perf.start(),perf=perfAll;remember();chatId="";replyId="";base("inbox");searchedFor="";Perf.end("inbox: base",perf);perf=Perf.start();
        if(folder!=1){unknownOnly=false;promoOnly=false;needsReplyOnly=false;}
        LinearLayout bar=row();TextView title=text(folder==2?"Archived":folder==3?"Blocked & spam":unknownOnly?"Unknown senders":promoOnly?"Promotions":needsReplyOnly?"Needs a reply":"Messages",26,ink,false);title.setPadding(dp(folder!=1||unknownOnly||promoOnly||needsReplyOnly?8:4),0,0,0);
        if(folder!=1||unknownOnly||promoOnly||needsReplyOnly)bar.addView(icon(R.drawable.ic_back,ink,"Back",()->{folder=1;unknownOnly=false;promoOnly=false;needsReplyOnly=false;inbox();}));
        // The title, with how many messages are waiting to be read across your conversations beside it.
        LinearLayout heading=row();heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(title);
        unreadBadge=text("",14,onFab,true);unreadBadge.setGravity(Gravity.CENTER);unreadBadge.setMinWidth(dp(28));unreadBadge.setPadding(dp(9),dp(3),dp(9),dp(3));unreadBadge.setBackground(shape(fab,14));unreadBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams ubl=new LinearLayout.LayoutParams(-2,-2);ubl.setMarginStart(dp(10));heading.addView(unreadBadge,ubl);
        bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));updateUnreadBadge();
        if(new SessionVault(this).exists()){bar.addView(icon(R.drawable.ic_search,ink,"Search",this::toggleSearch));bar.addView(icon(R.drawable.ic_more,ink,"More options",null));
            View more=bar.getChildAt(bar.getChildCount()-1);pressable(more);more.setOnClickListener(this::inboxMenu);}
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));connection();
        if(!new SessionVault(this).exists()){
            note(root,"On-device pairing • experimental\n\nConnect directly to your Google Messages account. Google Messages stays installed as your default SMS/RCS app. No extra server is required.");
            root.addView(button("Connect Google Messages",true,this::signIn));gap(root,12);
            note(root,"This replaces the earlier notification companion. Your old notification captures are separate from synced conversations. Full archive coverage and background delivery need testing on your phone.");return;
        }
        if(folder==1){TextView follow=text(needsReplyOnly?"All conversations  ›":"Needs a reply  ›",14,muted,false);follow.setPadding(dp(16),dp(10),dp(16),dp(10));follow.setOnClickListener(v->{needsReplyOnly=!needsReplyOnly;unknownOnly=false;promoOnly=false;inbox();});root.addView(follow);}
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
        scroll=new InboxScrollView(this);scroll.setVerticalScrollBarEnabled(false);items=vertical();items.setPadding(0,dp(4),0,dp(96));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{View child=scroll.getChildAt(0);if(child!=null&&y+scroll.getHeight()>=child.getHeight()-dp(200))loadOlderChats();});
        TextView start=new TextView(this);start.setText("Start chat");start.setTextSize(16);start.setTextColor(onFab);start.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        start.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_chat,0,0,0);start.setCompoundDrawableTintList(ColorStateList.valueOf(onFab));start.setCompoundDrawablePadding(dp(12));
        start.setGravity(Gravity.CENTER_VERTICAL);start.setPadding(dp(20),0,dp(24),0);start.setBackground(shape(fab,18));start.setElevation(dp(3));start.setOnClickListener(v->newMessage());
        FrameLayout.LayoutParams fl=new FrameLayout.LayoutParams(-2,dp(56),Gravity.BOTTOM|Gravity.END);fl.setMargins(0,0,dp(16),dp(16));frame.addView(start,fl);
        // Back from a chat (or Settings): the same rows in view, or the top if you replied (that chat moved up).
        if(inboxScrollY>0&&inboxKey.equals(inboxKey())&&!repliedSinceInbox){inboxRestore=inboxBack;inboxRestoreY=inboxScrollY;}
        inboxBack=null;inboxScrollY=-1;repliedSinceInbox=false;Perf.end("inbox: screen",perf);
        renderInbox();refreshInbox(false);Perf.end("inbox: total",perfAll);
    }
    private int inboxScrollY=-1;private String inboxKey="";private boolean repliedSinceInbox;
    // ---- Inbox: the rows you're looking at stay still when the list changes around them ----
    /** A chat row on screen: its conversation, how far below the top of the list it starts, and its newest message's time when drawn. */
    private static final class RowSpot{final String id;final int offset;final long time;RowSpot(String id,int offset,long time){this.id=id;this.offset=offset;this.time=time;}}
    private String drawnInboxKey="";private android.view.ViewTreeObserver.OnPreDrawListener inboxPlacing;
    private List<RowSpot> inboxSpots,inboxBack,inboxRestore;private int inboxRestoreY;
    /** The chat rows on screen, top first, or null at the top of the list (where a chat that moves up should come into view). */
    private List<RowSpot> inboxRowsNow(){if(scroll==null||items==null)return null;int y=scroll.getScrollY();if(y<=0)return null;List<RowSpot> on=new ArrayList<>();
        for(int i=0;i<items.getChildCount()&&on.size()<4;i++){View v=items.getChildAt(i);Object id=v.getTag(R.id.chat_row);if(id==null||v.getBottom()<=y)continue;if(v.getTop()>=y+scroll.getHeight())break;
            Object t=v.getTag(R.id.chat_time);on.add(new RowSpot((String)id,v.getTop()-y,t instanceof Long?(Long)t:0L));}
        return on.isEmpty()?null:on;}
    /** Where the inbox is now, counting a redraw that hasn't been laid out yet. */
    private List<RowSpot> inboxSpotNow(){return inboxPlacing!=null?inboxSpots:inboxRowsNow();}
    /**
     * After a redraw, puts the first of these rows that's still there back where it was. A row
     * whose chat just got a message is skipped: it moved to the top, and the rows around it are
     * what you were looking at. Nothing to anchor to: fallbackY, if any.
     */
    private void placeInbox(List<RowSpot> keep,int fallbackY){ScrollView sv=scroll;android.view.ViewTreeObserver.OnPreDrawListener[] once=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        once[0]=()->{sv.getViewTreeObserver().removeOnPreDrawListener(once[0]);if(inboxPlacing!=once[0])return true;inboxPlacing=null;inboxSpots=null;
            if(scroll!=sv||!screen.equals("inbox"))return true;Map<String,View> rows=new HashMap<>();
            for(int i=0;i<items.getChildCount();i++){View v=items.getChildAt(i);Object id=v.getTag(R.id.chat_row);if(id!=null)rows.put((String)id,v);}
            for(RowSpot s:keep){View v=rows.get(s.id);Object t=v==null?null:v.getTag(R.id.chat_time);
                if(v==null||(t instanceof Long&&(Long)t!=s.time))continue;moveTo(sv,Math.max(0,v.getTop()-s.offset));return true;}
            if(fallbackY>0)moveTo(sv,fallbackY);return true;};
        inboxSpots=keep;inboxPlacing=once[0];sv.getViewTreeObserver().addOnPreDrawListener(once[0]);}
    /** Moves a list to y. If that's a real move, a fling still running is stopped, since it would pull the list straight back. */
    private static void moveTo(ScrollView sv,int y){int from=sv.getScrollY();sv.scrollTo(0,y);if(Math.abs(sv.getScrollY()-from)>1)sv.fling(0);}
    /** Keeps the message you're reading still while things above it change height (see keepSpot). */
    private boolean keepPlace(){
        if(keepSpot==null||placing!=null||pinnedBottom||!screen.equals("chat")||scroll==null||items==null||!scroll.isLaidOut())return true;
        View v=messageViews.get(keepSpot.id);if(v==null||v.getParent()!=items)return true;
        int want=Math.max(0,v.getTop()-keepSpot.offset);if(Math.abs(want-scroll.getScrollY())>1)scroll.scrollTo(0,want);return true;}
    private boolean needsReplyOnly;
    private String inboxKey(){return folder+"|"+unknownOnly+"|"+promoOnly+"|"+needsReplyOnly+"|"+query;}
    private boolean needsReply(JSONObject c){String id=c.optString("conversationID");return NeedsReply.pending(store.replyBoundary(id,c),c,chatSettings.replyDismissed(id),chatSettings.replySnoozed(id),System.currentTimeMillis());}
    private void replyFollowUp(JSONObject c){String id=c.optString("conversationID");long latest=NeedsReply.incoming(store.replyBoundary(id,c),c);
        String[] choices={"Mark handled","Show in this list in 1 hour","Show tomorrow","Show in 3 days","Clear follow-up snooze / handled mark"};
        List<Runnable> actions=new ArrayList<>();
        for(int i=0;i<choices.length;i++){int which=i;actions.add(()->{long now=System.currentTimeMillis();if(which==0)chatSettings.dismissReply(id,latest);
            else if(which==4){chatSettings.dismissReply(id,0);chatSettings.snoozeReply(id,0);}else chatSettings.snoozeReply(id,now+(which==1?3_600_000L:which==2?86_400_000L:3*86_400_000L));
            if(screen.equals("inbox"))renderInbox();else if(screen.equals("details"))details();});}
        menuSheet("Needs a reply",java.util.Arrays.asList(choices),actions);}
    private void toggleSearch(){if(searchRow==null)return;boolean show=searchRow.getVisibility()!=View.VISIBLE;searchRow.setVisibility(show?View.VISIBLE:View.GONE);
        EditText field=(EditText)searchRow.getChildAt(0);if(show){field.requestFocus();getSystemService(InputMethodManager.class).showSoftInput(field,0);}else{field.setText("");hideKeyboard(field);}}
    /**
     * The inbox's ⋮ menu, as a sheet like a chat's: how many conversations and unread up top, the
     * other lists as tiles with their counts (the one you're in is highlighted; tap it to go back
     * to all conversations), then Mark as read and Refresh, and Settings on its own.
     */
    private Dialog inboxMenu(View anchor){Dialog[] d=new Dialog[1];java.util.function.Function<Runnable,Runnable> closing=r->()->{if(d[0]!=null)d[0].dismiss();r.run();};
        List<JSONObject> inboxChats=store.chats("",1);inboxChats.removeIf(c->pendingDeletes.contains(c.optString("conversationID")));
        int unread=0,unknown=0;for(JSONObject c:inboxChats){if(chatSettings.unread(c))unread++;if(unknownSender(c))unknown++;}
        int archived=store.chats("",2).size(),spam=store.chats("",3).size(),stars=starred.all().size();
        LinearLayout content=vertical();
        LinearLayout top=vertical();top.setPadding(dp(6),0,dp(6),dp(16));TextView title=text("Messages",22,ink,true);top.addView(title);
        TextView line=text(inboxChats.size()+(inboxChats.size()==1?" conversation":" conversations")+" \u00b7 "+(unread==0?"all read":unread+" unread"),13,muted,false);line.setPadding(0,dp(2),0,0);top.addView(line);content.addView(top);
        // The other lists, with how much is in each.
        boolean archivedHere=folder==2,spamHere=folder==3,unknownHere=folder==1&&unknownOnly,starredHere=screen.equals("starred");
        Runnable allChats=()->{folder=1;unknownOnly=false;promoOnly=false;needsReplyOnly=false;inbox();};
        LinearLayout tiles=row();
        menuTile(tiles,R.drawable.ic_archive,"Archived",archived,archivedHere,closing.apply(archivedHere?allChats:()->{folder=2;unknownOnly=false;promoOnly=false;needsReplyOnly=false;inbox();}));
        menuTile(tiles,R.drawable.ic_star,"Starred",stars,starredHere,closing.apply(starredHere?allChats:this::starredScreen));
        menuTile(tiles,R.drawable.ic_person,"Unknown",unknown,unknownHere,closing.apply(unknownHere?allChats:()->{folder=1;unknownOnly=true;promoOnly=false;needsReplyOnly=false;inbox();}));
        menuTile(tiles,R.drawable.ic_block,"Spam",spam,spamHere,closing.apply(spamHere?allChats:()->{folder=3;unknownOnly=false;promoOnly=false;needsReplyOnly=false;inbox();}));
        content.addView(tiles,new LinearLayout.LayoutParams(-1,-2));gap(content,14);
        LinearLayout actions=menuGroup(content);
        if(unread>0)menuRow(actions,R.drawable.ic_done_all,0xff3b82f6,"Mark all as read",unread+" unread",false,closing.apply(this::markAllRead));
        else{LinearLayout done=menuLine(R.drawable.ic_done_all,0xff3b82f6,"Mark all as read",false);TextView v=text("All read",14,muted,false);done.addView(v);done.setAlpha(0.55f);done.setContentDescription("Mark all as read: all read");addSettingRow(actions,done);}
        menuRow(actions,R.drawable.ic_refresh,0xff14b8a6,"Refresh from Google",null,false,closing.apply(()->{if(!screen.equals("inbox"))allChats.run();refreshInbox(true);}));
        gap(content,12);
        LinearLayout more=menuGroup(content);menuRow(more,R.drawable.ic_settings,0xff64748b,"Settings","",false,closing.apply(this::openSettings));
        content.setPadding(dp(3),0,dp(3),dp(8));ScrollView sc=new ScrollView(this);sc.setVerticalScrollBarEnabled(false);sc.addView(content);
        View focused=getCurrentFocus();if(focused!=null)hideKeyboard(focused);
        d[0]=sheet(sc,WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);return d[0];}
    private void refreshInbox(boolean force){if(!PairedService.ready()||isBusy())return;long now=System.currentTimeMillis();if(!force&&now-lastInboxRefresh<30_000)return;lastInboxRefresh=now;loadChats(false);}
    private void loadOlderChats(){if(isBusy()||!PairedService.ready())return;if(store.page("folder:"+folder,false).isEmpty())return;loadChats(true);}
    private void renderInbox(){if(items==null||!screen.equals("inbox"))return;
        // Replacing a touched row cancels Android's gesture; restoring an anchor also stops a fling.
        // Coalesce live updates until the finger is up and scrolling has actually settled.
        if(scroll instanceof InboxScrollView&&((InboxScrollView)scroll).deferUpdate(this::renderInbox))return;
        // Note the rows on screen first, so a chat moving to the top doesn't slide the ones you're looking at.
        boolean same=inboxKey().equals(drawnInboxKey);drawnInboxKey=inboxKey();List<RowSpot> keep=same?inboxSpotNow():null;int fallbackY=0;
        if(inboxPlacing!=null){scroll.getViewTreeObserver().removeOnPreDrawListener(inboxPlacing);inboxPlacing=null;}
        if(inboxRestoreY>0){keep=inboxRestore;fallbackY=inboxRestoreY;inboxRestore=null;inboxRestoreY=0;}
        long perf=Perf.start();items.removeAllViews();Perf.end("inbox: clear",perf);perf=Perf.start();
        SmartText.Search filtered=SmartText.parseSearch(query);boolean filtering=SmartText.hasFilters(filtered);
        List<JSONObject> chats=filtering?new ArrayList<>():InboxOrder.sort(store.chats(query,folder),chatSettings::pinned);
        if(needsReplyOnly)chats.removeIf(c->!needsReply(c));
        chats.removeIf(c->pendingDeletes.contains(c.optString("conversationID")));
        if(unknownOnly){List<JSONObject> unknown=new ArrayList<>();for(JSONObject c:chats)if(unknownSender(c))unknown.add(c);chats=unknown;}
        // Promotions (texts from short codes like 28302) get their own list, unless you turned that off.
        List<JSONObject> promos=new ArrayList<>();
        if(promoOnly){List<JSONObject> only=new ArrayList<>();for(JSONObject c:chats)if(promotion(c))only.add(c);chats=only;}
        else if(folder==1&&!unknownOnly&&!needsReplyOnly&&query.trim().isEmpty()&&promotionsSeparate()){for(JSONObject c:chats)if(promotion(c))promos.add(c);chats.removeAll(promos);}
        if(chats.isEmpty()&&promos.isEmpty()&&!filtering){TextView empty=text(PairedService.ready()?(query.isEmpty()?(needsReplyOnly?"All caught up in your synced conversations.":promoOnly?"No promotions.":"No conversations here yet."):"No chats with that name."):"Your conversations will appear once the connection is ready.",15,muted,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(empty);}
        Perf.end("inbox: read",perf);perf=Perf.start();
        if(autoReplyActive())items.addView(autoReplyBanner());
        // Pinned chats sit in a grid at the top (three across), like iPhone Messages; the rest are rows.
        boolean grid=folder==1&&!unknownOnly&&!needsReplyOnly&&query.trim().isEmpty();List<JSONObject> pins=new ArrayList<>();
        if(grid)for(JSONObject c:chats)if(chatSettings.pinned(c))pins.add(c);
        if(!pins.isEmpty()){View g=pinnedGrid(pins);g.setTag(R.id.chat_row,"\npinned");items.addView(g);}
        if(!promos.isEmpty())items.addView(promotionsRow(promos));
        for(JSONObject c:chats)if(pins.isEmpty()||!chatSettings.pinned(c)){View row=chatRow(c);row.setTag(R.id.chat_row,c.optString("conversationID"));row.setTag(R.id.chat_time,PairedProtocol.time(c));items.addView(row);}
        Perf.end("inbox: rows",perf);perf=Perf.start();renderNoteHits();renderMessageHits();updateUnreadBadge();
        String note=store.page("folder:"+folder,true);if(!chats.isEmpty()&&store.page("folder:"+folder,false).isEmpty()&&!note.isEmpty()){TextView end=text(note,12,muted,false);end.setGravity(Gravity.CENTER);end.setPadding(dp(16),dp(16),dp(16),dp(8));items.addView(end);}
        requestPhotos(chats);
        if(keep!=null||fallbackY>0)placeInbox(keep==null?new ArrayList<>():keep,fallbackY);Perf.end("inbox: rest",perf);
    }
    private View inboxRow(JSONObject c){
        String id=c.optString("conversationID");boolean unread=chatSettings.unread(c),pinned=chatSettings.pinned(c);String draft=drafts.getOrDefault(id,"").trim();
        LinearLayout entry=row();entry.setPadding(dp(4),dp(9),dp(4),dp(9));entry.setGravity(Gravity.CENTER_VERTICAL);
        pressable(entry);
        entry.addView(avatar(c,46));
        LinearLayout middle=vertical();middle.setPadding(dp(14),0,dp(8),0);
        TextView title=text(PairedProtocol.title(c),16,ink,unread);title.setTextSize(16.5f*textScale);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);middle.addView(title);
        String snippet=Effects.preview(ChatText.withoutMediaEmoji(PairedProtocol.string(c.optJSONObject("latestMessage"),"displayContent")));boolean[] mine={PairedProtocol.latestFromMe(c)};
        // Google leaves the preview empty for some events (drafts, some reactions); fall back to the newest synced message.
        if(snippet.isEmpty()){JSONObject last=latestStored(id);if(last!=null)snippet=previewOf(last,c,mine);}
        // Google's preview of an iPhone reaction is its raw text ("❤️ to “…”"); show it as "Michael reacted ❤️ to “…”".
        else if(ChatText.tapback(snippet)!=null){String line=ChatText.reactionLine(mine[0]?"You":latestSenderFirst(c),snippet);if(line!=null){snippet=line;mine[0]=false;}}
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
        LinearLayout cell=vertical();cell.setGravity(Gravity.CENTER_HORIZONTAL);cell.setPadding(dp(4),dp(10),dp(4),dp(8));
        FrameLayout face=new FrameLayout(this);face.addView(avatar(c,74),new FrameLayout.LayoutParams(dp(74),dp(74)));
        if(unread){View dot=new View(this);GradientDrawable d=oval(ChatTheme.of("default").accent(dark));d.setStroke(dp(3),bg);dot.setBackground(d);
            FrameLayout.LayoutParams dl=new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.TOP|Gravity.END);face.addView(dot,dl);}
        else if(quiet){TextView q=text(chatSettings.muted(id)?"🔕":"💤",11,muted,false);q.setGravity(Gravity.CENTER);q.setBackground(oval(card));face.addView(q,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.BOTTOM|Gravity.END));}
        LinearLayout.LayoutParams fl=new LinearLayout.LayoutParams(dp(74),dp(74));cell.addView(face,fl);
        String title=PairedProtocol.title(c);boolean group=PairedProtocol.other(c)==null;String shown=group?title:PairedProtocol.firstName(PairedProtocol.other(c));if(shown.isEmpty())shown=title;
        TextView name=text(shown,13,unread?ink:muted,unread);name.setTextSize(13*textScale);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(6),0,0);
        cell.addView(name,new LinearLayout.LayoutParams(-1,-2));
        // Only the round photo opens the chat, and pressing it shrinks and dims the photo itself (no highlight box).
        face.setContentDescription(title+(unread?", unread":"")+", pinned");face.setOnClickListener(v->thread(id));face.setOnLongClickListener(v->{chatOptions(c);return true;});
        face.setOnTouchListener(roundPress(dp(37)));
        return cell;}
    /**
     * Touch handling for a round photo of the given radius: touches outside the circle do nothing,
     * and a press shrinks and dims the photo slightly instead of drawing a highlight.
     */
    private View.OnTouchListener roundPress(int radius){return (v,e)->{
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN->{float dx=e.getX()-v.getWidth()/2f,dy=e.getY()-v.getHeight()/2f;
                if(dx*dx+dy*dy>(float)radius*radius)return true;
                v.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.82f).setDuration(90).start();}
            case MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start();
            default->{}}
        return false;};}
    private JSONObject latestStored(String id){List<JSONObject> l=store.messages(id,5);for(int i=l.size()-1;i>=0;i--)if(!PairedProtocol.body(l.get(i)).isEmpty())return l.get(i);return null;}
    /** Inbox preview text for a synced message; mine[0] says whether to prefix "You: ". */
    private String previewOf(JSONObject m,JSONObject c,boolean[] mine){String body=PairedProtocol.body(m);boolean out=PairedProtocol.outgoing(m,c);
        String line=ChatText.reactionLine(out?"You":senderFirst(m,c),body);if(line!=null){mine[0]=false;return line;}
        mine[0]=out;String reply=ChatText.replyFallbackBody(body);return reply!=null?reply:body;}
    private String senderName(JSONObject m,JSONObject c){return PairedProtocol.person(c,m.optString("participantID"),m.optJSONObject("senderParticipant"));}
    /** First name of whoever sent a chat's latest message, from Google's chat preview. */
    private static String latestSenderFirst(JSONObject c){JSONObject other=PairedProtocol.other(c);if(other!=null){String f=PairedProtocol.firstName(other);if(!f.isEmpty())return f;}
        String n=PairedProtocol.string(c.optJSONObject("latestMessage"),"displayName").trim();return n.isEmpty()?"Someone":n.split("\\s+")[0];}
    private String senderFirst(JSONObject m,JSONObject c){return PairedProtocol.personFirst(c,m.optString("participantID"),m.optJSONObject("senderParticipant"));}
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
            labels.add("Needs a reply / mark handled…");actions.add(()->replyFollowUp(c));
            labels.add("Open as bubble");actions.add(()->openBubble(id));}
        labels.add(blocked?"Unblock":"Block…");actions.add(()->block(c));
        labels.add("Delete conversation…");actions.add(()->deleteChat(c));
        menuSheet(PairedProtocol.title(c),labels,actions);}
    private void openBubble(String id){String problem=PairedService.showBubble(this,id);if(!problem.isEmpty())toast(problem);}
    private void togglePin(JSONObject c){String id=c.optString("conversationID");boolean now=!chatSettings.pinned(c);chatSettings.setPinned(id,now);decoder.execute(()->Nudges.reschedule(this));
        toast(now?"Pinned to the top of Message Center":"Unpinned");if(screen.equals("inbox"))renderInbox();}
    /**
     * Colors for one chat, or (id "") for every chat. A chat can follow the all-chats colors ("Same as all
     * chats") or have its own, and its own colors can be made everyone's.
     */
    private void chooseTheme(String id){boolean all=id.isEmpty();
        LinearLayout list=vertical();Dialog[] dialog=new Dialog[1];TextView heading=text(all?"Color for all chats":"Chat theme",18,ink,true);heading.setPadding(dp(8),0,dp(8),dp(6));list.addView(heading);
        if(all){TextView sub=text("Any chat can still have its own: ⋮ → Theme in that chat.",13,muted,false);sub.setPadding(dp(8),0,dp(8),dp(8));list.addView(sub);}
        boolean own=!all&&chatSettings.ownTheme(id);String current=own||all?chatSettings.theme(id).key:"";
        // This chat follows the colors every chat uses (until you pick its own).
        if(!all){ChatTheme every=ChatTheme.of(chatSettings.allTheme());LinearLayout r=row();r.setPadding(dp(16),dp(10),dp(16),dp(10));pressable(r);
            View swatch=new View(this);GradientDrawable sw=oval(every.bubble(dark));sw.setStroke(dp(3),every.accent(dark));swatch.setBackground(sw);r.addView(swatch,new LinearLayout.LayoutParams(dp(32),dp(32)));
            TextView label=text("Same as all chats · "+every.label,16,ink,!own);label.setPadding(dp(16),0,0,0);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if(!own)r.addView(text("✓",18,every.accent(dark),true));
            r.setOnClickListener(v->{chatSettings.sameAsAll(id);dialog[0].dismiss();themeChanged(id);});list.addView(r);}
        for(ChatTheme t:ChatTheme.ALL){LinearLayout r=row();r.setPadding(dp(16),dp(10),dp(16),dp(10));pressable(r);
            View swatch=new View(this);GradientDrawable sw=oval(t.bubble(dark));sw.setStroke(dp(3),t.accent(dark));swatch.setBackground(sw);r.addView(swatch,new LinearLayout.LayoutParams(dp(32),dp(32)));
            TextView label=text(t.label,16,ink,t.key.equals(current));label.setPadding(dp(16),0,0,0);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if(t.key.equals(current))r.addView(text("✓",18,t.accent(dark),true));
            r.setOnClickListener(v->{dialog[0].dismiss();if(all)applyThemeToAll(t.key,"");else{chatSettings.setTheme(id,t.key);themeChanged(id);}});list.addView(r);}
        // Custom: any color, picked on the next sheet.
        {ChatTheme mine=chatSettings.theme(id);boolean on=(own||all)&&mine.custom();LinearLayout r=row();r.setPadding(dp(16),dp(10),dp(16),dp(10));pressable(r);
            View swatch=new View(this);GradientDrawable sw;if(on){sw=oval(mine.bubble(dark));sw.setStroke(dp(3),mine.accent(dark));}else{sw=new GradientDrawable();sw.setShape(GradientDrawable.OVAL);sw.setGradientType(GradientDrawable.SWEEP_GRADIENT);sw.setColors(RAINBOW);}
            swatch.setBackground(sw);r.addView(swatch,new LinearLayout.LayoutParams(dp(32),dp(32)));
            TextView label=text("Custom…",16,ink,on);label.setPadding(dp(16),0,0,0);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));if(on)r.addView(text("✓",18,mine.accent(dark),true));
            r.setOnClickListener(v->{dialog[0].dismiss();customTheme(id);});list.addView(r);}
        if(all){dialog[0]=sheet(list);return;}
        // This chat's colors for every chat.
        if(own){ChatTheme mine=chatSettings.theme(id);TextView everyone=text("Use "+mine.label+" for all chats",15,BLUE,true);everyone.setPadding(dp(16),dp(12),dp(16),dp(12));pressable(everyone);
            everyone.setOnClickListener(v->{dialog[0].dismiss();applyThemeToAll(mine.key,id);});list.addView(everyone);}
        TextView bgHeading=text("Background",18,ink,true);bgHeading.setPadding(dp(8),dp(14),dp(8),dp(8));list.addView(bgHeading);
        HorizontalScrollView strip=new HorizontalScrollView(this);strip.setHorizontalScrollBarEnabled(false);LinearLayout tiles=row();tiles.setPadding(dp(8),0,dp(8),dp(8));strip.addView(tiles);list.addView(strip);
        String currentBg=chatSettings.background(id);
        tiles.addView(backgroundTile(null,"None",currentBg.isEmpty(),()->{dialog[0].dismiss();setChatBackground(id,"");}));
        for(String[] stock:STOCK_BACKGROUNDS){String value="stock:"+stock[0];tiles.addView(backgroundTile(stock[0],stock[1],currentBg.equals(value),()->{dialog[0].dismiss();setChatBackground(id,value);}));}
        tiles.addView(backgroundTile("photo:"+id,"Your photo…",currentBg.equals("photo"),()->{dialog[0].dismiss();pickBackgroundPhoto(id);}));
        dialog[0]=sheet(list);}
    /** Redraws what shows a chat's colors after they change ("" = all chats). */
    private void themeChanged(String id){if(screen.equals("chat")&&(id.isEmpty()||chatId.equals(id)))thread(chatId);else if(screen.equals("details"))details();else if(screen.equals("settings"))settings();}
    /**
     * Colors for every chat. If some chats have their own, asks whether those switch too (from a chat's
     * "Use … for all chats", that chat itself just follows along). Any chat can still be changed after.
     */
    private void applyThemeToAll(String key,String fromChat){List<String> others=chatSettings.ownThemes();others.remove(fromChat);String name=ChatTheme.of(key).label;
        Runnable done=()->{if(!fromChat.isEmpty())chatSettings.sameAsAll(fromChat);toast("All chats now use "+name+".");themeChanged("");};
        if(others.isEmpty()){chatSettings.themeAll(key,false);done.run();return;}
        new AlertDialog.Builder(this).setTitle("Use "+name+" for all chats?").setMessage(others.size()+(others.size()==1?" chat has its own colors.":" chats have their own colors.")+" Change "+(others.size()==1?"it":"them")+" to "+name+" too?")
            .setPositiveButton("Change all",(d,w)->{chatSettings.themeAll(key,true);done.run();})
            .setNegativeButton("Keep theirs",(d,w)->{chatSettings.themeAll(key,false);done.run();}).setNeutralButton("Cancel",null).show();}
    private static final int[] RAINBOW={0xffff3b30,0xffffcc00,0xff34c759,0xff00c7be,0xff007aff,0xffaf52de,0xffff2d55,0xffff3b30};
    /** A rounded slider track in these colors, left to right. */
    private GradientDrawable track(int... colors){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,colors);d.setCornerRadius(dp(7));return d;}
    private android.widget.SeekBar colorSlider(LinearLayout parent,String label,int max,int value){
        TextView name=text(label,13,muted,false);name.setPadding(dp(4),dp(12),0,dp(6));parent.addView(name);
        android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(max);bar.setProgress(value);bar.setContentDescription(label);bar.setSplitTrack(false);bar.setMinHeight(dp(14));bar.setMaxHeight(dp(14));
        GradientDrawable thumb=oval(Color.WHITE);thumb.setStroke(dp(2),0x66000000);thumb.setSize(dp(26),dp(26));bar.setThumb(thumb);bar.setPadding(dp(14),dp(6),dp(14),dp(6));
        parent.addView(bar,new LinearLayout.LayoutParams(-1,dp(38)));return bar;}
    private static String hexOf(int color){return String.format("#%06X",color&0xffffff);}
    /** "#3366AA", "3366aa" or "#36A" as a color, or 0. */
    static int parseHex(String text){String t=text==null?"":text.trim();if(t.startsWith("#"))t=t.substring(1);
        if(t.length()==3)t=""+t.charAt(0)+t.charAt(0)+t.charAt(1)+t.charAt(1)+t.charAt(2)+t.charAt(2);
        if(!t.matches("[0-9A-Fa-f]{6}"))return 0;return 0xff000000|Integer.parseInt(t,16);}
    private List<Integer> recentCustomColors(){List<Integer> out=new ArrayList<>();for(String h:getSharedPreferences("paired",MODE_PRIVATE).getString("custom_colors","").split(",")){int c=parseHex(h);if(c!=0)out.add(c);}return out;}
    private void rememberCustomColor(int color){List<Integer> recent=recentCustomColors();recent.remove(Integer.valueOf(color|0xff000000));recent.add(0,color|0xff000000);
        StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(6,recent.size());i++){if(b.length()>0)b.append(',');b.append(String.format("%06x",recent.get(i)&0xffffff));}
        getSharedPreferences("paired",MODE_PRIVATE).edit().putString("custom_colors",b.toString()).apply();}
    /**
     * A chat theme in any color: color, intensity and brightness sliders (or a color code), with a sample
     * chat that changes as you drag. Your bubbles are exactly the color picked here; the app works out a
     * matching shade for the other light/dark look, readable words, and a send button that stands out.
     */
    private void customTheme(String id){ChatTheme now=chatSettings.theme(id);int start=now.custom()?ChatTheme.customColor(now.key):now.bubble(dark);
        int[] picked={start};boolean[] syncing={false};Dialog[] dialog=new Dialog[1];
        LinearLayout content=vertical();TextView heading=text(id.isEmpty()?"Custom color for all chats":"Custom color",18,ink,true);heading.setPadding(dp(8),0,dp(8),dp(10));content.addView(heading);
        // A sample chat in this chat's colors: their bubble, yours, and the send button.
        LinearLayout sample=vertical();sample.setPadding(dp(14),dp(14),dp(14),dp(12));sample.setBackground(shape(bg,20));
        TextView theirs=text("What color is this?",15,ink,false);theirs.setPadding(dp(14),dp(9),dp(14),dp(9));theirs.setBackground(shape(surface,18));
        LinearLayout.LayoutParams thl=new LinearLayout.LayoutParams(-2,-2);thl.gravity=Gravity.START;sample.addView(theirs,thl);
        TextView mine=text("My new favorite",15,Color.WHITE,false);mine.setPadding(dp(14),dp(9),dp(14),dp(9));mine.setContentDescription("Preview of your bubble");
        LinearLayout.LayoutParams mil=new LinearLayout.LayoutParams(-2,-2);mil.gravity=Gravity.END;mil.topMargin=dp(8);sample.addView(mine,mil);
        LinearLayout box=row();box.setGravity(Gravity.CENTER_VERTICAL);TextView typing=text("RCS message",14,muted,false);typing.setPadding(dp(14),dp(10),dp(14),dp(10));typing.setBackground(shape(surface,20));
        box.addView(typing,new LinearLayout.LayoutParams(0,-2,1));ImageView send=new ImageView(this);send.setImageResource(R.drawable.ic_send);send.setPadding(dp(10),dp(10),dp(10),dp(10));send.setContentDescription("Preview of the send button");
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(dp(40),dp(40));sl.setMarginStart(dp(8));box.addView(send,sl);
        LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,-2);bl.topMargin=dp(12);sample.addView(box,bl);content.addView(sample);
        float[] hsl=ChatTheme.hsl(start);
        android.widget.SeekBar hue=colorSlider(content,"Color",360,Math.round(hsl[0])),sat=colorSlider(content,"Intensity",100,Math.round(hsl[1]*100)),light=colorSlider(content,"Brightness",100,Math.round(hsl[2]*100));
        hue.setProgressDrawable(track(RAINBOW));
        EditText code=new EditText(this);code.setSingleLine(true);code.setTextColor(ink);code.setHintTextColor(muted);code.setHint("Color code, like #3366AA");code.setContentDescription("Color code");
        code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);code.setTypeface(Typeface.MONOSPACE);code.setBackground(shape(surface,14));code.setPadding(dp(14),dp(10),dp(14),dp(10));
        LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,-2);cl.topMargin=dp(14);content.addView(code,cl);
        Runnable refresh=()->{int c=picked[0];ChatTheme t=ChatTheme.custom(c,dark);float[] p=ChatTheme.hsl(c);
            mine.setBackground(shape(t.bubble(dark),18));mine.setTextColor(t.onBubble(dark));send.setBackground(oval(t.accent(dark)));send.setImageTintList(ColorStateList.valueOf(t.onAccent(dark)));
            sat.setProgressDrawable(track(ChatTheme.fromHsl(p[0],0f,p[2]),ChatTheme.fromHsl(p[0],1f,p[2])));
            light.setProgressDrawable(track(0xff000000,ChatTheme.fromHsl(p[0],p[1],0.5f),0xffffffff));
            if(!syncing[0]){syncing[0]=true;code.setText(hexOf(c));syncing[0]=false;}};
        android.widget.SeekBar.OnSeekBarChangeListener dragged=new android.widget.SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(android.widget.SeekBar b,int v,boolean byYou){if(!byYou||syncing[0])return;picked[0]=ChatTheme.fromHsl(hue.getProgress(),sat.getProgress()/100f,light.getProgress()/100f);refresh.run();}
            @Override public void onStartTrackingTouch(android.widget.SeekBar b){}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b){}};
        hue.setOnSeekBarChangeListener(dragged);sat.setOnSeekBarChangeListener(dragged);light.setOnSeekBarChangeListener(dragged);
        // Sets the sliders to a color typed or tapped (the code box keeps what you typed).
        java.util.function.Consumer<Integer> choose=c->{picked[0]=c;float[] p=ChatTheme.hsl(c);syncing[0]=true;hue.setProgress(Math.round(p[0]));sat.setProgress(Math.round(p[1]*100));light.setProgress(Math.round(p[2]*100));refresh.run();syncing[0]=false;};
        code.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence x,int a,int b,int c){}@Override public void onTextChanged(CharSequence x,int a,int b,int c){}
            @Override public void afterTextChanged(Editable e){if(syncing[0])return;int c=parseHex(e.toString());if(c!=0)choose.accept(c);}});
        List<Integer> recent=recentCustomColors();
        if(!recent.isEmpty()){TextView rl=text("Recent",13,muted,false);rl.setPadding(dp(4),dp(14),0,dp(6));content.addView(rl);LinearLayout dots=row();
            for(int c:recent){View dot=new View(this);GradientDrawable d=oval(c);d.setStroke(dp(1),0x33000000|(ink&0xffffff));dot.setBackground(d);dot.setContentDescription("Recent color "+hexOf(c));pressable(dot);dot.setOnClickListener(v->{choose.accept(c);syncing[0]=true;code.setText(hexOf(c));syncing[0]=false;});
                LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(dp(34),dp(34));dl.setMarginEnd(dp(10));dots.addView(dot,dl);}
            content.addView(dots);}
        LinearLayout buttons=row();buttons.setGravity(Gravity.END);
        buttons.addView(button("Cancel",false,()->dialog[0].dismiss()));
        LinearLayout.LayoutParams ul=new LinearLayout.LayoutParams(-2,-2);ul.setMarginStart(dp(10));
        buttons.addView(button("Use color",true,()->{String key=ChatTheme.custom(picked[0],dark).key;rememberCustomColor(picked[0]);dialog[0].dismiss();
            if(id.isEmpty())applyThemeToAll(key,"");else{chatSettings.setTheme(id,key);themeChanged(id);}}),ul);
        LinearLayout.LayoutParams bul=new LinearLayout.LayoutParams(-1,-2);bul.topMargin=dp(18);content.addView(buttons,bul);
        refresh.run();ScrollView scroller=new ScrollView(this);scroller.addView(content);
        dialog[0]=sheet(scroller,WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);}
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
    // ---- Group name (for everyone, RCS groups) and photo (this phone only) ----
    private static final int PICK_GROUP_PHOTO=86;private String groupPhotoFor="";
    private void renameGroup(String id){JSONObject c=store.chat(id);if(c==null)return;EditText name=input("Group name");name.setSingleLine(true);
        String now=PairedProtocol.string(c,"name");name.setText(now);name.setSelection(name.getText().length());FrameLayout box=new FrameLayout(this);box.setPadding(dp(20),dp(8),dp(20),0);box.addView(name);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rename group").setMessage("Everyone in the group sees the new name.").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Rename",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError("Give it a name");return;}
            if(n.equals(now)){dialog.dismiss();return;}if(!PairedService.ready()){toast("Connect first, then rename the group.");return;}dialog.dismiss();toast("Renaming…");
            PairedService.execute(cl->{cl.renameGroup(id,n);return "";},(value,e)->{if(!e.isEmpty()){error(e);return;}
                // Shown right away; Google's own update follows.
                JSONObject latest=store.chat(id);if(latest!=null){try{latest.put("name",n);store.chat(latest);}catch(Exception ignored){}}
                Avatars.forgetGroup(id);toast("Renamed to “"+n+"”.");if(screen.equals("details")&&chatId.equals(id))details();});}));
        dialog.show();name.requestFocus();}
    private void groupPhotoMenu(String id){boolean own=Avatars.ownGroupPhoto(this,id).isFile();
        List<String> labels=new ArrayList<>(java.util.Arrays.asList(own?"Choose a different photo":"Choose a photo"));List<Runnable> actions=new ArrayList<>(java.util.Arrays.<Runnable>asList(()->{groupPhotoFor=id;
            try{startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"),PICK_GROUP_PHOTO);}catch(Exception e){try{startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"),PICK_GROUP_PHOTO);}catch(Exception e2){toast("No photo picker is available.");}}}));
        if(own){labels.add("Use members' photos again");actions.add(()->{Avatars.ownGroupPhoto(this,id).delete();Avatars.forgetGroup(id);toast("Back to members' photos.");if(screen.equals("details"))details();});}
        menuSheet("Group photo (only you see it; Google can't change a group's photo from here)",labels,actions);}
    private void saveGroupPhoto(Uri uri){String id=groupPhotoFor;if(id.isEmpty())return;
        decoder.execute(()->{boolean ok=false;File target=Avatars.ownGroupPhoto(this,id);
            try{android.graphics.ImageDecoder.Source src=android.graphics.ImageDecoder.createSource(getContentResolver(),uri);
                Bitmap b=android.graphics.ImageDecoder.decodeBitmap(src,(dec,info,s2)->{int lw=info.getSize().getWidth(),lh=info.getSize().getHeight();float k=Math.min(1f,720f/Math.max(1,Math.min(lw,lh)));
                    dec.setTargetSize(Math.max(1,Math.round(lw*k)),Math.max(1,Math.round(lh*k)));dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);});
                Bitmap square=Avatars.scaleSquare(b,Math.min(720,Math.min(b.getWidth(),b.getHeight())));
                File dir=target.getParentFile();if(!dir.isDirectory())dir.mkdirs();File part=new File(target.getPath()+".part");
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(part)){square.compress(Bitmap.CompressFormat.JPEG,90,out);}ok=part.renameTo(target);}catch(Exception ignored){}
            boolean saved=ok;ui.post(()->{if(!saved){toast("That photo couldn't be used.");return;}Avatars.forgetGroup(id);toast("Group photo set on this phone.");if(screen.equals("details")&&chatId.equals(id))details();else if(screen.equals("inbox"))renderInbox();});});}
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
        if(screen.equals("details"))details();else if(screen.equals("chat")){updateReceiptsMark();renderMessages(false);}}
    private void updateReceiptsMark(){if(receiptsMark!=null)receiptsMark.setVisibility(!chatId.isEmpty()&&chatSettings.receipts(chatId)?View.VISIBLE:View.GONE);}
    /** "Read 2:34 PM" under the message a read receipt went out for, or why it hasn't (yet). */
    private View readLine(JSONObject entry){String state=ReadReceiptQueue.state(entry,true,PairedService.ready(),chatSettings.receiptAvailability());long at=entry.optLong("requestedAt");
        String label=switch(state){
            case "accepted"->"Read "+(android.text.format.DateUtils.isToday(at)?timeOnly(at):separatorStamp(at));
            case "google_off"->"Read receipt not sent • Google Messages' “Send read receipts” is off";
            case "waiting","queued","retrying"->"Read receipt sending…";
            default->null;};
        if(label==null)return null;TextView t=text(label,11,muted,false);t.setPadding(dp(16),dp(2),dp(16),0);t.setContentDescription(label+". Tap for details.");t.setOnClickListener(v->details());return t;}
    private void loadChats(boolean more){if(isBusy())return;busy=true;busySince=System.currentTimeMillis();int selected=folder;String cursor=more?store.page("folder:"+selected,false):"";
        PairedService.loadChats(cursor,selected,(v,e)->{busy=false;if(!e.isEmpty()&&!more)toast(e);if(screen.equals("inbox"))renderInbox();});}

    // ---- Conversation ----
    private void thread(String id){long perfAll=Perf.start(),perf=perfAll;
        if(!id.equals(chatId))stopInline();
        Spot back=(screen.equals("details")||screen.equals("notes")||screen.equals("note")||backFromSaved)&&id.equals(backChat)?backSpot:null;backChat="";backSpot=null;backFromSaved=false;
        // Opening a chat with unread messages: remember where they start, for the "New messages" line.
        if(!id.equals(chatId)||!screen.equals("chat")){JSONObject before=store.chat(id);unreadSince=before!=null&&chatSettings.unread(before)?chatSettings.seen(id):-1;unreadJump=unreadSince>0;}
        remember();chatId=id;if(back==null)limit=firstWindow(id);replyId="";base("chat");JSONObject c=store.chat(id);ChatTheme theme=chatSettings.theme(id);loadCalls(id);
        root.setPadding(dp(4),dp(4),dp(4),dp(8));
        LinearLayout bar=row();if(!bubble())bar.addView(icon(R.drawable.ic_back,ink,"Back",this::inbox));else bar.setPadding(dp(12),0,0,0);
        LinearLayout who=row();View face=avatar(c,40);who.addView(face);
        TextView name=text(c==null?"Conversation":PairedProtocol.title(c),20,ink,false);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);
        // Chats that send read receipts say so up top, so you know before reading.
        LinearLayout titles=vertical();titles.setGravity(Gravity.CENTER_VERTICAL);titles.setPadding(dp(14),0,dp(8),0);titles.addView(name);
        receiptsMark=text("Read receipts on",12,muted,false);receiptsMark.setSingleLine(true);receiptsMark.setCompoundDrawablePadding(dp(5));
        android.graphics.drawable.Drawable eye=getDrawable(R.drawable.ic_eye);if(eye!=null){eye=eye.mutate();eye.setTint(muted);eye.setBounds(0,0,dp(14),dp(14));receiptsMark.setCompoundDrawablesRelative(eye,null,null,null);}
        titles.addView(receiptsMark);updateReceiptsMark();
        who.addView(titles,new LinearLayout.LayoutParams(0,-2,1));who.setGravity(Gravity.CENTER_VERTICAL);who.setContentDescription("Conversation details");who.setOnClickListener(v->details());
        bar.addView(who,new LinearLayout.LayoutParams(0,-1,1));
        // In a bubble: one tap opens this conversation in the full app.
        if(bubble())bar.addView(icon(R.drawable.ic_open,ink,"Open in Message Center",()->openInApp(id)));
        // A notes button shows when you've written notes about them (or the group), as a reminder they're there.
        if(c!=null&&new Notes(this).has(notesKeyFor(c)))bar.addView(icon(R.drawable.ic_notes,ink,"Your notes",()->openNotes(store.chat(id),()->thread(id))));
        String number=PairedProtocol.number(PairedProtocol.other(c));
        if(SmartText.shortCodeSender(number)){TextView stop=text("Unsubscribe",14,ink,true);stop.setPadding(dp(12),dp(8),dp(12),dp(8));stop.setBackground(shape(surface,16));stop.setOnClickListener(v->unsubscribe(id,c));
            LinearLayout.LayoutParams ul=new LinearLayout.LayoutParams(-2,-2);ul.gravity=Gravity.CENTER_VERTICAL;ul.setMarginEnd(dp(4));bar.addView(stop,ul);}
        else if(!number.isEmpty())bar.addView(icon(R.drawable.ic_call,ink,"Call",()->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));}catch(Exception e){toast("No phone app is available.");}}));
        ImageView more=icon(R.drawable.ic_more,ink,"More options",null);pressable(more);more.setOnClickListener(this::chatMenu);bar.addView(more);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));connection();
        pinBar=vertical();root.addView(pinBar,new LinearLayout.LayoutParams(-1,-2));pinShown=0;renderPinBar();
        if(c!=null){View who2=whoIsThisCard(c);if(who2!=null)root.addView(who2);}
        scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);items=vertical();items.setPadding(dp(12),dp(8),dp(12),dp(8));
        // A short chat sits at the bottom, just above the message box, like any messaging app.
        scroll.setFillViewport(true);items.setGravity(Gravity.BOTTOM);scroll.addView(items);
        chatFrame=new FrameLayout(this);addChatBackground(chatFrame,id);
        // Dragging left slides the whole list over (one move, so a redraw mid-drag keeps it there) and the
        // times, waiting just past the right edge, come into view. Nothing clips the slide short of the screen's edges.
        items.setClipToPadding(false);items.setClipChildren(false);scroll.setClipChildren(false);LinearLayout listed=items;
        TimeRevealLayout reveal=new TimeRevealLayout(this,dp(TIME_REVEAL_DP),amount->revealTimes(listed,amount));reveal.addView(scroll,new FrameLayout.LayoutParams(-1,-1));chatFrame.addView(reveal,new FrameLayout.LayoutParams(-1,-1));root.addView(chatFrame,new LinearLayout.LayoutParams(-1,0,1));
        jumpButton=text("↓",15,ink,true);jumpButton.setPadding(dp(18),dp(10),dp(18),dp(10));jumpButton.setBackground(shape(card,22));jumpButton.setElevation(dp(4));jumpButton.setVisibility(View.GONE);
        jumpButton.setOnClickListener(v->jumpToLatest());FrameLayout.LayoutParams jl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);jl.setMargins(0,0,dp(16),dp(12));chatFrame.addView(jumpButton,jl);
        newWhileAway=0;newestSeenTime=0;
        // Chats never animate on their own; only an explicit jump (search, reply quote) scrolls smoothly.
        scroll.setSmoothScrollingEnabled(false);pinnedBottom=true;keepSpot=null;
        if(!keeperAdded){keeperAdded=true;getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(this::keepPlace);}
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{View ch=scroll.getChildAt(0);pinnedBottom=ch!=null&&ch.getHeight()-scroll.getHeight()-y<dp(80);
            if(placing==null)keepSpot=pinnedBottom?null:spotAt(y);updateJumpButton();if(y<oy&&moreSaved&&y<scroll.getHeight())showOlderSaved();else if(y==0&&oy>0)loadOlderMessages();});
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
        if(!id.equals(outgoingChat)){for(Outgoing o:outgoing)if(!o.sending)discardPicked(o);outgoing.removeIf(o->!o.sending);outgoingChat=id;}
        attachStrip=row();attachScroll=new HorizontalScrollView(this);attachScroll.setHorizontalScrollBarEnabled(false);attachScroll.setPadding(dp(16),dp(4),dp(16),dp(6));attachScroll.addView(attachStrip);root.addView(attachScroll);
        // Recording: cancel, a pulsing dot and the time, your voice as it comes in, and send.
        recordBar=row();recordBar.setGravity(Gravity.CENTER_VERTICAL);recordBar.setPadding(dp(4),dp(6),dp(10),dp(6));recordBar.setVisibility(View.GONE);
        recordBar.addView(icon(R.drawable.ic_close,muted,"Cancel recording",this::cancelRecording));
        recordDot=new View(this);recordDot.setBackground(oval(danger));LinearLayout.LayoutParams rd=new LinearLayout.LayoutParams(dp(10),dp(10));rd.setMarginStart(dp(2));rd.setMarginEnd(dp(8));recordBar.addView(recordDot,rd);
        recordClock=text("0:00",16,ink,true);recordClock.setFontFeatureSettings("tnum");recordBar.addView(recordClock);
        recordWave=new Waveform(this);recordWave.liveMode();recordWave.setColors(danger,danger);LinearLayout.LayoutParams rw=new LinearLayout.LayoutParams(0,dp(32),1);rw.setMarginStart(dp(14));rw.setMarginEnd(dp(14));recordBar.addView(recordWave,rw);
        ImageView sendRec=roundIcon(R.drawable.ic_send,theme.accent(dark),theme.onAccent(dark),44);pressDim(sendRec);sendRec.setContentDescription("Send voice message");sendRec.setOnClickListener(v->finishRecording(true));recordBar.addView(sendRec);root.addView(recordBar);
        quickRow=row();quickScroll=new HorizontalScrollView(this);quickScroll.setHorizontalScrollBarEnabled(false);quickScroll.setPadding(dp(12),dp(2),dp(12),dp(4));quickScroll.addView(quickRow);quickScroll.setVisibility(View.GONE);root.addView(quickScroll);
        LinearLayout compose=row();compose.setPadding(dp(4),dp(4),dp(8),0);composeRow=compose;
        compose.addView(icon(R.drawable.ic_add,ink,"Attach photos, videos, or files",this::attach));
        composerHint=PairedProtocol.rcs(c)?"RCS message":"Text message";composer=new EditText(this);composer.setHint(composerHint);composer.setTextColor(ink);composer.setHintTextColor(muted);composer.setTextSize(17*textScale);
        composer.setBackground(shape(surface,28));composer.setPadding(dp(22),dp(14),dp(22),dp(14));composer.setMaxLines(5);
        composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setText(drafts.getOrDefault(id,""));composer.setSelection(composer.length());
        composer.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){remember();maybeSendTyping(s);updateComposeButton();updateQuickRow();}public void afterTextChanged(Editable e){}});
        composer.setOnFocusChangeListener((v,focused)->updateQuickRow());
        composer.setOnReceiveContentListener(new String[]{"image/*","video/*"},keyboardContent);
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        // A long message grows the box, up to about 40% of the space the chat has (less with the keyboard up),
        // and never under 5 lines; past that it scrolls inside.
        EditText box=composer;LinearLayout screenRoot=root;
        screenRoot.addOnLayoutChangeListener((vw,l0,t0,r0,b0,ol0,ot0,or0,ob0)->{int tall=b0-t0;if(tall<=0||tall==ob0-ot0)return;
            int most=Math.max(box.getLineHeight()*5+box.getPaddingTop()+box.getPaddingBottom(),Math.round(tall*0.4f));
            if(box.getMaxHeight()!=most)box.post(()->box.setMaxHeight(most));});
        sendButton=new ImageView(this);sendButton.setImageResource(R.drawable.ic_send);sendButton.setImageTintList(ColorStateList.valueOf(theme.onAccent(dark)));sendButton.setBackground(oval(theme.accent(dark)));
        sendButton.setPadding(dp(14),dp(14),dp(14),dp(14));sendButton.setContentDescription("Send");sendButton.setOnClickListener(v->sendOrRecord());sendButton.setOnLongClickListener(v->{if(micShowing())startRecording();else if(dictation==null)scheduleMessage();return true;});
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(dp(52),dp(52));sl.setMarginStart(dp(8));compose.addView(sendButton,sl);root.addView(compose);renderAttachments();updateComposeButton();applyIncoming();Perf.end("open: screen",perf);
        perf=Perf.start();chatSettings.setMarkedUnread(id,false);if(!bubble())PairedService.clearNotification(this,id);Perf.end("open: notification",perf);
        if(back!=null){restoreSpot=back;renderMessages(false);}else renderMessages(true);perf=Perf.start();if(PairedService.ready())loadMessages(false);markReadIfAllowed();updateTyping();
        if(c!=null)requestPhotos(java.util.Collections.singletonList(c));Perf.end("open: after",perf);Perf.end("open: total",perfAll);
    }
    /**
     * The chat's ⋮ menu, as a sheet: who the chat is with, the four things you reach for most as
     * tiles, this chat's settings with switches, then where it opens and Archive, and Block and
     * Delete set apart in red at the bottom.
     */
    private Dialog chatMenu(View anchor){JSONObject c=store.chat(chatId);if(c==null)return null;String id=chatId;boolean group=PairedProtocol.other(c)==null;
        // The keyboard goes down, and the sheet takes no notice of it while it does, so it isn't laid out squeezed above it.
        View focused=getCurrentFocus();if(focused!=null)hideKeyboard(focused);
        LinearLayout content=vertical();Dialog[] d=new Dialog[1];
        java.util.function.Function<Runnable,Runnable> closing=r->()->{if(d[0]!=null)d[0].dismiss();r.run();};
        // Who it's with.
        LinearLayout who=row();who.setPadding(dp(4),0,dp(4),dp(16));who.addView(avatar(c,52));
        LinearLayout names=vertical();names.setPadding(dp(14),0,dp(4),0);TextView title=text(PairedProtocol.title(c),19,ink,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);names.addView(title);
        JSONObject other=PairedProtocol.other(c);String number=other==null?"":other.optString("formattedNumber",PairedProtocol.number(other));
        String kind=PairedProtocol.rcs(c)?"RCS":"SMS";
        TextView line=text(group?"Group \u00b7 "+(PairedProtocol.members(c).size()+1)+" people \u00b7 "+kind:(number.isEmpty()||number.equals(PairedProtocol.title(c))?"":number+" \u00b7 ")+kind,13,muted,false);
        line.setPadding(0,dp(2),0,0);names.addView(line);who.addView(names,new LinearLayout.LayoutParams(0,-2,1));content.addView(who);
        // The four you reach for most.
        LinearLayout tiles=row();int notes=new Notes(this).list(notesKeyFor(c)).size();
        menuTile(tiles,R.drawable.ic_info,group?"Group info":"Details",closing.apply(this::details));
        menuTile(tiles,R.drawable.ic_notes,notes>0?"Notes \u00b7 "+notes:"Notes",closing.apply(()->openNotes(store.chat(id),()->thread(id))));
        menuTile(tiles,R.drawable.ic_search,"Search",closing.apply(this::searchChat));
        menuTile(tiles,R.drawable.ic_palette,"Theme",closing.apply(()->chooseTheme(id)));
        content.addView(tiles,new LinearLayout.LayoutParams(-1,-2));gap(content,14);
        // This chat's settings.
        LinearLayout settings=menuGroup(content);
        menuSwitch(settings,R.drawable.ic_pin,0xfff59e0b,"Pin to top",chatSettings.pinned(c),on->togglePin(store.chat(id)));
        String notify=chatSettings.muted(id)?"Muted":chatSettings.snoozed(id)?"Snoozed until "+snoozeLabel(id):"On";
        menuRow(settings,R.drawable.ic_bell,0xff8b5cf6,"Notifications",notify,false,closing.apply(()->notificationChoices(id)));
        menuSwitch(settings,R.drawable.ic_eye,0xff3b82f6,"Read receipts",chatSettings.receipts(id),on->toggleReceipts(id));
        gap(content,12);
        // Where it opens, and putting it away.
        boolean archived=c.optString("status").contains("ARCHIVED"),blocked=c.optString("status").equals("BLOCKED_FOLDER")||c.optString("status").equals("SPAM_FOLDER");
        LinearLayout places=menuGroup(content);
        if(bubble())menuRow(places,R.drawable.ic_open,0xff14b8a6,"Open in Message Center",null,false,closing.apply(()->openInApp(id)));
        else{menuRow(places,R.drawable.ic_chat,0xff14b8a6,"Open as bubble",null,false,closing.apply(()->openBubble(id)));
            menuRow(places,R.drawable.ic_archive,0xff64748b,archived?"Unarchive":"Archive",null,false,closing.apply(()->archive(store.chat(id),!archived)));}
        // Set apart, in red.
        if(!bubble()){gap(content,12);LinearLayout risky=menuGroup(content);
            menuRow(risky,R.drawable.ic_block,0xffef4444,blocked?"Unblock":"Block\u2026",null,!blocked,closing.apply(()->block(store.chat(id))));
            menuRow(risky,R.drawable.ic_delete,0xffdc2626,"Delete conversation\u2026",null,true,closing.apply(()->deleteChat(store.chat(id))));}
        content.setPadding(dp(3),0,dp(3),dp(8));ScrollView sc=new ScrollView(this);sc.setVerticalScrollBarEnabled(false);sc.addView(content);
        d[0]=sheet(sc,WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);return d[0];}
    /** Cards in the chat menu: a step lighter than the sheet in dark themes (where shadows don't show), the usual card tint in light. */
    /** Device checks only: draw screens in the black theme to picture them, whatever the phone's set to. */
    static boolean blackForPictures;
    /**
     * Cards that sit on a screen (not on a sheet): in the black theme, the app's near-black card so
     * the screen stays jet black like the inbox and chats; otherwise a step up, like the sheets.
     */
    private int pageCard(){return dark&&bg==0xff000000?card:raised();}
    private int raised(){if(!dark)return surface;int r=Color.red(surface),g=Color.green(surface),b=Color.blue(surface);
        return Color.rgb(r+Math.round((Color.red(ink)-r)*0.07f),g+Math.round((Color.green(ink)-g)*0.07f),b+Math.round((Color.blue(ink)-b)*0.07f));}
    /** A tile at the top of the chat menu: a round icon over its name, on a raised card. */
    private void menuTile(LinearLayout tiles,int icon,String label,Runnable action){menuTile(tiles,icon,label,0,false,action);}
    /** A tile with a count on its icon (none for 0), filled in when it's where you are now. */
    private void menuTile(LinearLayout tiles,int icon,String label,int count,boolean here,Runnable action){LinearLayout t=vertical();t.setGravity(Gravity.CENTER_HORIZONTAL);t.setPadding(dp(4),dp(14),dp(4),dp(12));
        t.setBackground(shape(here?fab:raised(),20));t.setElevation(dp(2));pressDim(t);t.setOnClickListener(v->action.run());
        t.setContentDescription(here?label+", showing now: tap for all conversations":count>0?label+", "+count:label);
        FrameLayout face=new FrameLayout(this);ImageView round=roundIcon(icon,here?onFab:fab,here?fab:onFab,44);face.addView(round,new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER));
        if(count>0){TextView badge=text(count>99?"99+":String.valueOf(count),11,Color.WHITE,true);badge.setGravity(Gravity.CENTER);badge.setPadding(dp(5),0,dp(5),0);badge.setMinWidth(dp(20));badge.setBackground(shape(BLUE,10));
            face.addView(badge,new FrameLayout.LayoutParams(-2,dp(20),Gravity.TOP|Gravity.END));}
        t.addView(face,new LinearLayout.LayoutParams(dp(60),dp(46)));TextView name=text(label,13,here?onFab:ink,true);name.setGravity(Gravity.CENTER);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(0,dp(8),0,0);t.addView(name);
        LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(0,-2,1);if(tiles.getChildCount()>0)tl.setMarginStart(dp(8));tiles.addView(t,tl);}
    /** A raised, rounded card holding a few rows of the chat menu. */
    private LinearLayout menuGroup(LinearLayout content){LinearLayout g=vertical();g.setBackground(shape(raised(),20));g.setClipToOutline(true);g.setElevation(dp(2));
        LinearLayout.LayoutParams gl=new LinearLayout.LayoutParams(-1,-2);gl.setMargins(dp(1),0,dp(1),0);content.addView(g,gl);return g;}
    /** A colored icon chip, like the ones in Android's Settings, so rows are told apart at a glance. */
    private ImageView menuChip(int icon,int color){ImageView i=new ImageView(this);i.setImageResource(icon);i.setImageTintList(ColorStateList.valueOf(Color.WHITE));i.setBackground(shape(color,10));i.setPadding(dp(6),dp(6),dp(6),dp(6));return i;}
    private LinearLayout menuLine(int icon,int color,String label,boolean red){LinearLayout r=row();r.setPadding(dp(14),dp(10),dp(14),dp(10));r.setMinimumHeight(dp(58));
        r.addView(menuChip(icon,color),new LinearLayout.LayoutParams(dp(32),dp(32)));TextView name=text(label,16,red?danger:ink,false);name.setPadding(dp(14),0,dp(8),0);
        r.addView(name,new LinearLayout.LayoutParams(0,-2,1));r.setContentDescription(label);return r;}
    /** A row that does something (value shows its current setting; a › means it leads to more choices). */
    private void menuRow(LinearLayout group,int icon,int color,String label,String value,boolean red,Runnable action){LinearLayout r=menuLine(icon,color,label,red);pressable(r);
        if(value!=null){TextView v=text((value.isEmpty()?"":value+"  ")+"\u203a",14,muted,false);v.setSingleLine(true);r.addView(v);if(!value.isEmpty())r.setContentDescription(label+": "+value);}
        r.setOnClickListener(x->action.run());addSettingRow(group,r);}
    private void menuSwitch(LinearLayout group,int icon,int color,String label,boolean on,java.util.function.Consumer<Boolean> change){LinearLayout r=menuLine(icon,color,label,false);pressable(r);
        android.widget.Switch sw=new android.widget.Switch(this);sw.setChecked(on);sw.setContentDescription(label);int[][] states={{android.R.attr.state_checked},{}};
        sw.setThumbTintList(new ColorStateList(states,new int[]{BLUE,dark?0xffb8bcc6:0xfff4f5f8}));sw.setTrackTintList(new ColorStateList(states,new int[]{(BLUE&0x00ffffff)|0x80000000,(muted&0x00ffffff)|0x60000000}));
        sw.setOnCheckedChangeListener((b,checked)->change.accept(checked));r.setOnClickListener(x->sw.toggle());r.addView(sw);addSettingRow(group,r);}
    /** Mute or snooze this chat's notifications (or turn them back on). */
    private void notificationChoices(String id){boolean muted=chatSettings.muted(id),snoozed=chatSettings.snoozed(id);List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        labels.add(muted?"Unmute":"Mute");actions.add(()->toggleMute(id));
        if(!muted){labels.add(snoozed?"Snoozed until "+snoozeLabel(id)+": change\u2026":"Snooze\u2026");actions.add(()->snoozeMenu(id));}
        menuSheet("Notifications",labels,actions);}
    /** The first message on screen in the chat and how far down it sits, or null when you're at (or near) the newest message. */
    private Spot currentSpot(){if(scroll==null||items==null)return null;View content=scroll.getChildAt(0);int y=scroll.getScrollY();
        if(content==null||content.getHeight()-scroll.getHeight()-y<dp(80))return null;return spotAt(y);}
    /** Where the chat is right now, counting a redraw that hasn't been laid out yet. */
    private Spot chatSpotNow(){return placing!=null&&placingIn==scroll?(placeBottom?null:placeSpot):currentSpot();}
    private Spot spotAt(int y){String id=null;int top=0,offset=0;
        for(java.util.Map.Entry<String,View> e:messageViews.entrySet()){View v=e.getValue();if(v.getParent()!=items)continue;
            if(v.getBottom()>y&&(id==null||v.getTop()<top)){id=e.getKey();top=v.getTop();offset=v.getTop()-y;}}
        return id==null?null:new Spot(id,offset);}
    /**
     * Redraws the chat. It stays where you are unless {@code bottom} asks for the newest
     * message (opening a chat, sending) or you were already at the bottom; nothing else moves it.
     */
    // ---- Motion: new messages slide in, your sent message lifts into place, new reactions pop ----
    private static final int SLIDE=0,LIFT=1,POP=2,SLAM=3,LOUD=4,GENTLE=5;
    private String motionChat="";private final java.util.Set<String> motionSeen=new java.util.HashSet<>(),motionOutbox=new java.util.HashSet<>();
    private final Map<String,Integer> motionReactions=new HashMap<>();private final Map<String,long[]> motionRunning=new HashMap<>();
    private int motionOutboxBefore;private boolean motionReady;
    /**
     * Starts (start=true) or continues an entrance animation for the view drawn for key. Chats
     * redraw often (a read receipt right after a message arrives, say), so a redraw mid-animation
     * picks up where the last view left off instead of jumping to the end.
     */
    private void motion(View v,String key,int kind,boolean start){long now=android.os.SystemClock.uptimeMillis();long[] run=motionRunning.get(key);
        if(start&&run==null){run=new long[]{now,kind};motionRunning.put(key,run);}
        if(run==null)return;kind=(int)run[1];long duration=kind==LIFT?320:kind==POP?300:kind==SLAM?720:kind==LOUD?1400:kind==GENTLE?1900:240;float p=Math.min(1f,(now-run[0])/(float)duration);
        if(p>=1f){motionRunning.remove(key);return;}long left=Math.max(16,Math.round((1f-p)*duration));
        if(kind>=SLAM){int k=kind;ValueAnimator a=ValueAnimator.ofFloat(p,1f);a.setDuration(left);a.setInterpolator(new android.view.animation.LinearInterpolator());
            a.addUpdateListener(x->bubbleFrame(v,k,(float)x.getAnimatedValue()));bubbleFrame(v,k,p);a.start();
            if(k==SLAM&&p<0.3f)ui.postDelayed(()->slamShake(v),Math.round((0.3f-p)*duration));return;}
        android.view.animation.Interpolator ease=kind==SLIDE?new android.view.animation.DecelerateInterpolator(1.6f):new android.view.animation.OvershootInterpolator(kind==POP?2.2f:1.1f);
        v.setAlpha(p);
        if(kind==POP){float sc=0.3f+0.7f*p;v.setScaleX(sc);v.setScaleY(sc);v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(left).setInterpolator(ease).start();}
        else{v.setTranslationY((1f-p)*dp(kind==LIFT?40:14));v.animate().alpha(1f).translationY(0f).setDuration(left).setInterpolator(ease).start();}}
    /**
     * One frame of an iPhone bubble effect, f from 0 to 1. Slam drops the message in hard, Loud
     * swells and shakes it, Gentle lets it grow in slowly. It scales from the bubble's side.
     */
    private void bubbleFrame(View v,int kind,float f){v.setPivotX(dp(60));v.setPivotY(v.getHeight()/2f);float sx=1,sy=1,rot=0,alpha=1;
        if(f>=1f){v.setScaleX(1);v.setScaleY(1);v.setRotation(0);v.setAlpha(1);return;}
        if(kind==SLAM){if(f<0.3f){float q=f/0.3f;sx=sy=2.4f-1.4f*q*q;alpha=Math.min(1f,q*2.5f);}
            else if(f<0.55f){float q=(f-0.3f)/0.25f,bump=(float)Math.sin(Math.PI*q);sx=1+0.07f*bump;sy=1-0.09f*bump;}}
        else if(kind==LOUD){if(f<0.2f){float q=f/0.2f;sx=sy=1+0.45f*(1-(1-q)*(1-q));alpha=Math.min(1f,q*4);}
            else if(f<0.75f){sx=sy=1.45f;rot=4.5f*(float)Math.sin((f-0.2f)*2*Math.PI*7);}
            else{float q=(f-0.75f)/0.25f;sx=sy=1.45f-0.45f*q*q*(3-2*q);}}
        else{float q=1-(1-f)*(1-f)*(1-f);sx=sy=0.5f+0.5f*q;alpha=0.35f+0.65f*Math.min(1f,f*1.6f);}
        v.setScaleX(sx);v.setScaleY(sy);v.setRotation(rot);v.setAlpha(alpha);}
    /** Slam lands with a jolt: the chat shakes for a moment. */
    private void slamShake(View bubble){if(scroll==null||!bubble.isAttachedToWindow())return;View list=scroll;bubble.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        ValueAnimator a=ValueAnimator.ofFloat(0f,1f);a.setDuration(360);a.addUpdateListener(x->{float q=(float)x.getAnimatedValue();list.setTranslationY((float)(Math.sin(q*Math.PI*6)*(1-q)*dp(5)));});
        a.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator an){list.setTranslationY(0);}});a.start();}
    // ---- Message effects ----
    private boolean effectsOn(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("message_effects",true);}
    /** The effect a message plays: the one an iPhone sent it with, or one its words set off ("Happy birthday"). */
    private static String effectOf(JSONObject m){String e=PairedProtocol.effect(m);return e.isEmpty()?Effects.phrase(PairedProtocol.text(m)):e;}
    private EffectView playing;
    /** Plays a full-screen effect over the chat; mid is the message it's for (null for one you just sent). */
    private void playEffect(String effect,String mid,String text){
        if(chatFrame==null||!screen.equals("chat")||!Effects.fullScreen(effect)||!ValueAnimator.areAnimatorsEnabled())return;
        if(playing!=null)playing.stop();FrameLayout frame=chatFrame;
        playing=new EffectView(this,effect,()->{View v=mid==null?null:bubbleViews.get(mid);if(v==null||!v.isAttachedToWindow())return null;
            int[] a=new int[2],f=new int[2];v.getLocationInWindow(a);frame.getLocationInWindow(f);
            return new android.graphics.RectF(a[0]-f[0],a[1]-f[1],a[0]-f[0]+v.getWidth(),a[1]-f[1]+v.getHeight());},text,surface,ink);
        frame.addView(playing,new FrameLayout.LayoutParams(-1,-1));}
    /** Plays a message's effect again (long-press menu, or the "Sent with" line under it). */
    private void replayEffect(JSONObject m,String effect){String mid=m.optString("messageID");
        if(Effects.fullScreen(effect)){playEffect(effect,mid,PairedProtocol.text(m));return;}
        int kind=Effects.SLAM.equals(effect)?SLAM:Effects.LOUD.equals(effect)?LOUD:Effects.GENTLE.equals(effect)?GENTLE:-1;
        View row=messageViews.get(mid);if(kind<0||row==null||!row.isAttachedToWindow())return;motionRunning.remove("m:"+mid);motion(row,"m:"+mid,kind,true);}
    /** Under a message an iPhone sent with an effect: which one, and a tap plays it again. */
    private View effectLine(JSONObject m,String effect,boolean outgoing){TextView t=text("✨ Sent with "+Effects.label(effect)+" · Replay",11,muted,false);
        t.setGravity(outgoing?Gravity.END:Gravity.START);t.setPadding(dp(16),dp(3),dp(16),dp(1));t.setContentDescription("Sent with "+Effects.label(effect)+". Tap to replay it.");
        t.setOnClickListener(v->replayEffect(m,effect));return t;}
    private static final long INK_SHOWN_MS=9000;
    /** Invisible ink messages you've uncovered, and until when they stay uncovered. */
    private final Map<String,Long> inkShownUntil=new HashMap<>();
    /** Invisible ink: the message stays blurred until you tap it, and covers itself again after a few seconds. */
    private View invisibleInk(View content,JSONObject m){String mid=m.optString("messageID");FrameLayout box=new FrameLayout(this);box.addView(content,new FrameLayout.LayoutParams(-1,-2));
        View cover=new View(this);cover.setContentDescription("Invisible ink message. Double-tap to show it.");box.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        float blur=dp(10);Runnable hide=()->{cover.setVisibility(View.VISIBLE);content.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur,blur,android.graphics.Shader.TileMode.DECAL));};
        Runnable later=()->{Long until=inkShownUntil.get(mid);if(until!=null&&until>System.currentTimeMillis()+50)return;inkShownUntil.remove(mid);if(box.isAttachedToWindow())hide.run();};
        long left=inkShownUntil.getOrDefault(mid,0L)-System.currentTimeMillis();
        if(left>0){cover.setVisibility(View.GONE);box.postDelayed(later,left);}else hide.run();
        cover.setOnClickListener(v->{inkShownUntil.put(mid,System.currentTimeMillis()+INK_SHOWN_MS);cover.setVisibility(View.GONE);
            ValueAnimator a=ValueAnimator.ofFloat(blur,0f);a.setDuration(420);a.addUpdateListener(x->{float r=(float)x.getAnimatedValue();content.setRenderEffect(r<0.5f?null:android.graphics.RenderEffect.createBlurEffect(r,r,android.graphics.Shader.TileMode.DECAL));});
            a.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator an){content.setRenderEffect(null);}});a.start();box.postDelayed(later,INK_SHOWN_MS);});
        cover.setOnLongClickListener(v->{messageSheet(m);return true;});return box;}
    private void renderMessages(boolean bottom){if(items==null||!screen.equals("chat"))return;long perfAll=Perf.start(),perf=perfAll;refreshSavedAttachments();voiceCards.clear();voiceOrder.clear();
        ScrollView sv=scroll;View content=sv.getChildAt(0);int y,before;boolean atBottom;Spot spot;
        // If the last redraw hasn't been laid out yet, its new views have no positions; keep aiming where it was aiming.
        if(placing!=null&&placingIn==sv){sv.getViewTreeObserver().removeOnPreDrawListener(placing);placing=null;y=placeY;before=placeBefore;atBottom=bottom||placeBottom;spot=atBottom?null:placeSpot;}
        else{y=sv.getScrollY();before=content.getHeight();atBottom=bottom||before-sv.getHeight()-y<dp(80);spot=atBottom?null:spotAt(y);}
        boolean restoring=restoreSpot!=null&&!bottom;if(restoreSpot!=null){if(!bottom){spot=restoreSpot;atBottom=false;}restoreSpot=null;}
        placeY=y;placeBefore=before;placeBottom=atBottom;placeSpot=spot;
        boolean toBottom=atBottom;String keepId=spot==null?null:spot.id;int keepOffset=spot==null?0:spot.offset;
        items.removeAllViews();unreadDivider=null;inlineSeen=false;Perf.end("messages: clear",perf);perf=Perf.start();
        // Queued texts are read before the chat's messages: the service saves Google's copy of a sent text
        // before it drops the queued one, so reading in this order can show both (one is then left out) but never neither.
        List<JSONObject> queued=outbox.forChat(chatId);
        JSONObject c=store.chat(chatId);ChatTheme theme=chatSettings.theme(chatId);boolean group=c!=null&&PairedProtocol.other(c)==null;
        // Scrolled up reading: new messages don't push the oldest ones drawn out of the window (only at the bottom does it slide).
        if(!atBottom&&chatId.equals(oldestChat)&&oldestTime>0){int keep=store.countSince(chatId,oldestTime);if(keep>limit)limit=keep;}
        List<JSONObject> saved=store.messages(chatId,limit);if(!saved.isEmpty()){oldestChat=chatId;oldestTime=PairedProtocol.time(saved.get(0));}
        // Google's notices show as a centered line, or not at all; never as a message bubble.
        List<JSONObject> messages=new ArrayList<>();for(JSONObject m:saved)if(PairedProtocol.notice(m)?!PairedProtocol.event(m,c).isEmpty():!PairedProtocol.body(m).isEmpty()||!PairedProtocol.effect(m).isEmpty())messages.add(m);
        moreSaved=messages.size()>=limit-5;if(moreSaved)items.addView(button("Show more saved messages",false,()->{limit+=WINDOW;renderMessages(false);}));
        // iPhone reactions sent as text ("Laughed at an image") go on the message they're about, like any other reaction.
        tapbacks=new HashMap<>();messages=placeTapbacks(messages,tapbacks);
        // Calls with this person (from the phone's call log, shown only here) go among the messages by time.
        if(!group&&callsChat.equals(chatId)&&!chatCalls.isEmpty()&&callsOn())messages=withCalls(messages,chatCalls,moreSaved&&!messages.isEmpty()?PairedProtocol.time(messages.get(0)):0);
        if(messages.isEmpty()){TextView empty=text(PairedService.ready()?"No messages have synced for this conversation yet.":"Messages will appear once the connection is ready.",14,muted,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(empty);}
        else if(store.page("messages:"+chatId,false).isEmpty()&&!store.page("messages:"+chatId,true).isEmpty()){TextView end=text(store.page("messages:"+chatId,true),12,muted,false);end.setGravity(Gravity.CENTER);end.setPadding(dp(16),dp(8),dp(16),dp(8));items.addView(end);}
        Perf.end("messages: read",perf);perf=Perf.start();
        int n=messages.size();boolean[] out=new boolean[n],replyTo=new boolean[n],reacted=new boolean[n];String[] who=new String[n],line=new String[n],ev=new String[n];long[] at=new long[n];
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);if(CallLines.isCall(m)){who[i]="";at[i]=PairedProtocol.time(m);ev[i]=CallLines.label(m);line[i]=ev[i];continue;}
            out[i]=PairedProtocol.outgoing(m,c);who[i]=out[i]?"":m.optString("participantID");at[i]=PairedProtocol.time(m);
            line[i]=ChatText.reactionLine(out[i]?"You":senderFirst(m,c),PairedProtocol.body(m));
            ev[i]=PairedProtocol.event(m,c);if(!ev[i].isEmpty())line[i]=ev[i];
            // An effect sent on its own (an iPhone's "(sent with Confetti)" with no text) is a quiet line you can tap to replay.
            if(line[i]==null&&PairedProtocol.body(m).isEmpty())line[i]="✨ "+(out[i]?"You":senderFirst(m,c))+" sent "+Effects.label(PairedProtocol.effect(m));
            replyTo[i]=ReplyReferences.isReply(m);
            reacted[i]=!reactorsOf(m).isEmpty();}
        int lastOut=-1;for(int i=n-1;i>=0;i--)if(out[i]&&line[i]==null){lastOut=i;break;}
        // Opening a chat or loading older messages draws without motion; so does a catch-up that brings many at once.
        boolean fresh=!chatId.equals(motionChat)||anchorOlder;if(!chatId.equals(motionChat)){motionChat=chatId;motionSeen.clear();motionOutbox.clear();motionReactions.clear();motionRunning.clear();motionOutboxBefore=0;}
        int arriving=0;for(JSONObject m:messages)if(!motionSeen.contains(m.optString("messageID")))arriving++;
        motionReady=!fresh&&android.animation.ValueAnimator.areAnimatorsEnabled();boolean animate=motionReady&&arriving<=4;
        Map<String,JSONObject> remindersById=new HashMap<>();for(JSONObject r:Reminders.all(this))remindersById.put(r.optString("message"),r);
        // Read receipts on: when (or whether) the receipt went out, under the message it's for.
        JSONObject receipt=chatSettings.receipts(chatId)?new ReadReceiptQueue(this).get(chatId):null;
        // Link previews open the page, which tells its owner you looked; only do that for people you know or have written to.
        boolean wrote=false;for(boolean o:out)wrote|=o;chatTrusted=c!=null&&(PairedProtocol.allKnown(c)||wrote);
        TimeZone zone=TimeZone.getDefault();String[] screenEffect=null;Perf.end("messages: prepare",perf);perf=Perf.start();
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);String mid=m.optString("messageID");boolean isNew=motionSeen.add(mid);
            boolean sep=InboxOrder.separator(i==0?0:at[i-1],at[i],zone);
            if(sep){TextView s=text(separatorStamp(at[i]),13,muted,false);s.setGravity(Gravity.CENTER);s.setPadding(0,dp(i==0?8:20),0,dp(10));items.addView(s,new LinearLayout.LayoutParams(-1,-2));}
            if(CallLines.isCall(m)){if(!sep&&i>0)gap(items,8);items.addView(callLine(m),new LinearLayout.LayoutParams(-1,-2));continue;}
            // Group and chat events ("Sam left", "Now end-to-end encrypted") are centered notes.
            if(!ev[i].isEmpty()){if(!sep&&i>0)gap(items,8);TextView t=text(ev[i],12,muted,false);t.setGravity(Gravity.CENTER);t.setPadding(dp(24),dp(4),dp(24),dp(4));items.addView(t,new LinearLayout.LayoutParams(-1,-2));continue;}
            // iPhone reactions sent as text ("Loved an image") become one quiet line instead of a bubble.
            if(line[i]!=null){if(!sep&&i>0)gap(items,8);TextView t=text(line[i],13,muted,false);t.setGravity(out[i]?Gravity.END:Gravity.START);t.setPadding(dp(14),dp(2),dp(14),dp(2));items.addView(t,new LinearLayout.LayoutParams(-1,-2));
                String alone=PairedProtocol.body(m).isEmpty()?PairedProtocol.effect(m):"";
                if(!alone.isEmpty()){t.setOnClickListener(v->replayEffect(m,alone));if(animate&&isNew&&!out[i]&&effectsOn()&&Effects.fullScreen(alone))screenEffect=new String[]{alone,null,""};}
                continue;}
            boolean joinPrev=!sep&&i>0&&line[i-1]==null&&!replyTo[i]&&!reacted[i-1]&&InboxOrder.grouped(out[i-1],who[i-1],at[i-1],out[i],who[i],at[i]);
            boolean joinNext=i+1<n&&line[i+1]==null&&!replyTo[i+1]&&!reacted[i]&&!InboxOrder.separator(at[i],at[i+1],zone)&&InboxOrder.grouped(out[i],who[i],at[i],out[i+1],who[i+1],at[i+1]);
            if(!sep&&i>0)gap(items,joinPrev?3:12);
            if(unreadSince>0&&unreadDivider==null&&i>0&&!out[i]&&at[i]>unreadSince){int count=0;for(int k=i;k<n;k++)if(!out[k]&&at[k]>unreadSince&&ev[k].isEmpty()&&line[k]==null)count++;
                unreadCount=count;unreadDivider=newMessagesLine(count);items.addView(unreadDivider);}
            if(group&&!out[i]&&!joinPrev&&!replyTo[i]){TextView sender=text(senderName(m,c),12,muted,false);sender.setPadding(dp(14),0,0,dp(4));items.addView(sender);}
            long perfB=Perf.start();View bub=bubble(m,c,theme,out[i],joinPrev,joinNext);Perf.end("bubble: build",perfB);perfB=Perf.start();
            View b=timed(swipeToReply(bub,m),at[i],out[i]);if(selecting)b=selectable(b,m);items.addView(b);messageViews.put(m.optString("messageID"),b);Perf.end("bubble: wrap",perfB);perfB=Perf.start();
            // A new message's effect (an iPhone's Slam or Balloons, or "Happy birthday"): bubble effects replace the usual slide in.
            String fx=animate&&isNew&&!out[i]&&effectsOn()?effectOf(m):"";
            if(Effects.fullScreen(fx))screenEffect=new String[]{fx,mid,PairedProtocol.text(m)};
            int kind=Effects.SLAM.equals(fx)?SLAM:Effects.LOUD.equals(fx)?LOUD:Effects.GENTLE.equals(fx)?GENTLE:SLIDE;
            // A sent message already lifted in as its "Sending…" row, so it doesn't animate again when Google confirms it.
            motion(b,"m:"+mid,kind,animate&&isNew&&(!out[i]||motionOutboxBefore==0));
            List<String[]> reactors=reactorsOf(m);
            if(!reactors.isEmpty()){LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(-1,-2);rl.topMargin=-dp(7);View pill=reactionPill(m,c,reactors,out[i]);items.addView(pill,rl);
                Integer had=motionReactions.put(mid,reactors.size());motion(((ViewGroup)pill).getChildAt(0),"r:"+mid,POP,motionReady&&!isNew&&(had==null||reactors.size()>had));}
            else motionReactions.remove(mid);
            String noted=PairedProtocol.effect(m);if(!noted.isEmpty()&&!Effects.INK.equals(noted))items.addView(effectLine(m,noted,out[i]),new LinearLayout.LayoutParams(-1,-2));
            if(!out[i]){String code=TextScan.code(PairedProtocol.text(m));if(!code.isEmpty())items.addView(codeChip(code,false,theme));}
            JSONObject rem=remindersById.get(mid);
            if(rem!=null){TextView rt=text("⏰ Reminder "+separatorStamp(rem.optLong("at")),11,muted,false);rt.setGravity(out[i]?Gravity.END:Gravity.START);rt.setPadding(dp(16),dp(2),dp(16),0);rt.setOnClickListener(v->remindMenu(m));items.addView(rt,new LinearLayout.LayoutParams(-1,-2));}
            if(starred.has(m.optString("messageID"))){TextView st=text("★ Starred",11,muted,false);st.setGravity(out[i]?Gravity.END:Gravity.START);st.setPadding(dp(16),dp(2),dp(16),0);items.addView(st,new LinearLayout.LayoutParams(-1,-2));}
            if(!out[i]&&receipt!=null&&receipt.optString("messageID").equals(m.optString("messageID"))){View read=readLine(receipt);if(read!=null)items.addView(read,new LinearLayout.LayoutParams(-1,-2));}
            if(i==lastOut||(out[i]&&PairedProtocol.failed(m)))items.addView(statusRow(m,theme));Perf.end("bubble: extras",perfB);
        }
        Perf.end("messages: bubbles",perf);perf=Perf.start();
        renderOutbox(theme,OutboxEcho.toDraw(queued,messages,System.currentTimeMillis()));renderPendingMedia(theme);renderScheduled(theme);renderArrivals(theme);if(inline!=null&&!inlineSeen)stopInline();
        int pendingHere=0;for(PendingMedia p:pendingMedia)if(p.chat.equals(chatId))pendingHere++;motionOutboxBefore=outbox.forChat(chatId).size()+pendingHere;
        watchSending(messages,c);
        // Played once the chat is laid out, so it can find the message on screen.
        if(screenEffect!=null){String[] fx=screenEffect;ui.post(()->{if(screen.equals("chat"))playEffect(fx[0],fx[1],fx[2]);});}
        // Count messages that arrived while scrolled up, for the ↓ button.
        long newest=0;int arrived=0;for(int i=0;i<n;i++){newest=Math.max(newest,at[i]);if(newestSeenTime>0&&at[i]>newestSeenTime&&!out[i]&&ev[i].isEmpty())arrived++;}
        if(atBottom)newWhileAway=0;else newWhileAway+=arrived;newestSeenTime=Math.max(newestSeenTime,newest);if(atBottom)pinnedBottom=true;updateJumpButton();
        // Position before the next frame is drawn, so the chat appears already at the right place instead of sliding there.
        android.view.ViewTreeObserver.OnPreDrawListener[] once=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        once[0]=()->{sv.getViewTreeObserver().removeOnPreDrawListener(once[0]);if(placing!=once[0])return true;placing=null;placingIn=null;if(scroll!=sv||sv.getChildAt(0)==null)return true;
            if(anchorOlder){anchorOlder=false;moveTo(sv,y+Math.max(0,sv.getChildAt(0).getHeight()-before));}
            else if(toBottom&&unreadJump&&unreadDivider!=null&&sv.getChildAt(0).getHeight()-unreadDivider.getTop()>sv.getHeight()){
                // More unread than fits on screen: start at the first one, with ↓ to jump to the newest.
                unreadJump=false;sv.scrollTo(0,Math.max(0,unreadDivider.getTop()-dp(12)));pinnedBottom=false;newWhileAway=unreadCount;updateJumpButton();}
            else if(toBottom){sv.scrollTo(0,sv.getChildAt(0).getHeight());pinnedBottom=true;if(n>0)unreadJump=false;}
            else{View again=keepId==null?null:messageViews.get(keepId);boolean found=again!=null&&again.getParent()==items;
                // Going back to a message that isn't loaded any more: the newest is a better guess than the top.
                if(!found&&restoring){sv.scrollTo(0,sv.getChildAt(0).getHeight());pinnedBottom=true;}else{moveTo(sv,found?Math.max(0,again.getTop()-keepOffset):y);pinnedBottom=sv.getChildAt(0).getHeight()-sv.getHeight()-sv.getScrollY()<dp(80);updateJumpButton();}}
            keepSpot=pinnedBottom?null:spotAt(sv.getScrollY());return true;};
        placing=once[0];placingIn=sv;sv.getViewTreeObserver().addOnPreDrawListener(once[0]);Perf.end("messages: rest",perf);Perf.end("messages: total",perfAll);
    }
    /** A message's reactions, including iPhone reactions that came as text and were placed on it. */
    private List<String[]> reactorsOf(JSONObject m){List<String[]> all=PairedProtocol.reactors(m),more=tapbacks.get(m.optString("messageID"));
        if(more==null||more.isEmpty())return all;all=new ArrayList<>(all);all.addAll(more);return all;}
    /**
     * Puts iPhone reaction texts on the messages they're about (see ChatText.tapbackTarget) and
     * returns the messages left to show. A reaction it can't place stays as its own quiet line.
     */
    private List<JSONObject> placeTapbacks(List<JSONObject> messages,Map<String,List<String[]>> into){
        int n=messages.size();List<ChatText.Said> said=new ArrayList<>(n);ChatText.Tapback[] tb=new ChatText.Tapback[n];boolean[] skip=new boolean[n];boolean any=false;
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);String text=PairedProtocol.text(m),reply=ChatText.replyFallbackBody(text);List<JSONObject> media=PairedProtocol.media(m);
            boolean image=false,video=false,audio=false,file=false;
            for(JSONObject a:media){String k=PairedProtocol.mediaKind(a);image|=k.equals("image");video|=k.equals("video");audio|=k.equals("audio");file|=k.equals("file");}
            said.add(new ChatText.Said(m.optString("participantID"),reply!=null?reply:text,image,video,audio,file));
            if(media.isEmpty()&&PairedProtocol.location(m)==null){tb[i]=ChatText.tapback(text);skip[i]=tb[i]!=null;any|=skip[i];}}
        if(!any)return messages;
        List<JSONObject> shown=new ArrayList<>(n);
        for(int i=0;i<n;i++){ChatText.Tapback t=tb[i];if(t==null){shown.add(messages.get(i));continue;}
            int j=ChatText.tapbackTarget(t,i,said,skip);if(j<0){shown.add(messages.get(i));continue;}
            String who=said.get(i).sender;List<String[]> on=into.computeIfAbsent(messages.get(j).optString("messageID"),k->new ArrayList<>());
            // One reaction each, as on an iPhone: a new one replaces theirs, and a removal takes it off.
            on.removeIf(r->r[1].equals(who)&&(!t.removed||ReactionParser.sameEmoji(r[0],t.emoji)));
            if(!t.removed)on.add(new String[]{t.emoji,who});}
        return shown;}
    /** Puts a text's links (worked out once, see TextScan) on a bubble; false when it has none. */
    private static boolean applyLinks(TextView t,TextScan scan){if(scan.linkUrls.length==0)return false;SpannableString sp=new SpannableString(t.getText());boolean any=false;
        for(int k=0;k<scan.linkUrls.length;k++){int[] r=scan.linkRanges[k];if(r[0]<0||r[1]>sp.length()||r[0]>=r[1])continue;sp.setSpan(new URLSpan(scan.linkUrls[k]),r[0],r[1],Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);any=true;}
        if(any)t.setText(sp);return any;}
    /**
     * Holding a link in a message offers that link (open, copy, share) instead of the whole
     * message; holding anywhere else in the text offers the message. Lifting your finger after a
     * hold never opens the link.
     */
    private void holdLinks(TextView text,JSONObject m){float[] at={-1,-1};boolean[] held={false};
        text.setOnTouchListener((v,e)->{int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){at[0]=e.getX();at[1]=e.getY();held[0]=false;}
            else if(action==MotionEvent.ACTION_UP&&held[0]){held[0]=false;MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);v.onTouchEvent(cancel);cancel.recycle();return true;}
            return false;});
        text.setOnLongClickListener(v->{held[0]=true;URLSpan link=linkAt(text,at[0],at[1]);if(link!=null)linkSheet(link.getURL(),m);else messageSheet(m);return true;});}
    /** The link under a point in a text (view coordinates), or null. Only a touch on the link's own words counts, not the space past the end of its line. */
    static URLSpan linkAt(TextView t,float x,float y){android.text.Layout layout=t.getLayout();if(x<0||layout==null||!(t.getText() instanceof Spanned))return null;
        int line=layout.getLineForVertical((int)(y-t.getTotalPaddingTop()+t.getScrollY()));float lx=x-t.getTotalPaddingLeft()+t.getScrollX();
        if(lx<layout.getLineLeft(line)||lx>layout.getLineRight(line))return null;
        int offset=layout.getOffsetForHorizontal(line,lx);URLSpan[] spans=((Spanned)t.getText()).getSpans(offset,offset,URLSpan.class);
        for(URLSpan span:spans){int start=((Spanned)t.getText()).getSpanStart(span),end=((Spanned)t.getText()).getSpanEnd(span);if(offset>=start&&offset<=end)return span;}return null;}
    /** A link held in a message: open, copy or share it (call, text or copy a number; email or copy an address), or the message's own options. */
    private void linkSheet(String url,JSONObject m){String u=url==null?"":url,lower=u.toLowerCase(Locale.ROOT);List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();String title;
        ClipboardManager clip=getSystemService(ClipboardManager.class);
        if(lower.startsWith("mailto:")){String address=Uri.decode(u.substring(7));title=address;
            labels.add("Write an email");actions.add(()->openLink(u));labels.add("Copy email address");actions.add(()->clip.setPrimaryClip(ClipData.newPlainText("Email address",address)));}
        else if(lower.startsWith("tel:")){String number=Uri.decode(u.substring(4));title=readableNumber(number);
            labels.add("Call");actions.add(()->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse(u)));}catch(Exception e){toast("No phone app is installed.");}});
            labels.add("Text "+title);actions.add(()->startChat(number));labels.add("Copy number");actions.add(()->clip.setPrimaryClip(ClipData.newPlainText("Phone number",number)));}
        else{title=ChatText.shortLink(u);labels.add("Open link");actions.add(()->openLink(u));labels.add("Copy link");actions.add(()->clip.setPrimaryClip(ClipData.newPlainText("Link",u)));
            labels.add("Share link");actions.add(()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,u),"Share link")));
            MusicLinks.Link music=MusicApps.link(u);if(music!=null){boolean podcast=MusicApps.podcast(music);
                labels.add(podcast?"Choose podcast app\u2026":"Choose music app\u2026");actions.add(()->chooseMusicApp(podcast,chosen->renderMessages(false)));}}
        labels.add("Message options\u2026");actions.add(()->messageSheet(m));
        menuSheet(title,labels,actions);}
    private View bubble(JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,boolean joinPrev,boolean joinNext){
        View v=bubbleBody(m,c,theme,outgoing,joinPrev,joinNext);return Effects.INK.equals(PairedProtocol.effect(m))&&effectsOn()?invisibleInk(v,m):v;}
    private View bubbleBody(JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,boolean joinPrev,boolean joinNext){
        LinearLayout wrap=vertical();bubbleViews.put(m.optString("messageID"),wrap);String body=PairedProtocol.text(m),fallback=ChatText.replyFallbackBody(body);List<JSONObject> media=PairedProtocol.media(m);
        // Google's copy has only the caption so far: the photo you sent is shown here until your phone adds it.
        PendingMedia waiting=media.isEmpty()?echoFor(m.optString("messageID")):null;if(waiting!=null)wrap.addView(pendingThumb(waiting,theme),new LinearLayout.LayoutParams(-1,-2));
        String target=store.replyTarget(m);
        if(ReplyReferences.isReply(m))addReplyContext(wrap,m,c,theme,outgoing,target);
        // Several photos or videos sent together become one album grid; anything else is shown on its own.
        List<JSONObject> visual=new ArrayList<>(),rest=new ArrayList<>();for(JSONObject a:media){String k=PairedProtocol.mediaKind(a);if((k.equals("image")||k.equals("video"))&&!PairedProtocol.contactCard(a))visual.add(a);else rest.add(a);}
        if(visual.size()>=2){wrap.addView(albumView(m,visual,outgoing),new LinearLayout.LayoutParams(-1,-2));if(!rest.isEmpty())gap(wrap,4);}else rest=media;
        for(int k=0;k<rest.size();k++){if(k>0)gap(wrap,4);wrap.addView(mediaView(m,rest.get(k),outgoing,theme),new LinearLayout.LayoutParams(-1,-2));}
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
        TextScan scan=TextScan.of(text.getText().toString(),PairedProtocol.time(m)>0?PairedProtocol.time(m):System.currentTimeMillis());
        if(applyLinks(text,scan)){text.setLinkTextColor(outgoing?theme.onBubble(dark):ink);text.setHighlightColor(Color.TRANSPARENT);text.setMovementMethod(LinkMovementMethod.getInstance());text.setOnLongClickListener(v->{messageSheet(m);return true;});}
        // Street addresses open in your maps app.
        List<int[]> places=scan.places;
        if(!places.isEmpty()){Spannable sp=text.getText() instanceof Spannable?(Spannable)text.getText():new SpannableString(text.getText());int linkColor=outgoing?theme.onBubble(dark):ink;
            for(int[] r:places){if(sp.getSpans(r[0],r[1],URLSpan.class).length>0)continue;String where=sp.subSequence(r[0],r[1]).toString();
                sp.setSpan(new ClickableSpan(){@Override public void onClick(View w){openPlace(where);}@Override public void updateDrawState(TextPaint ds){ds.setColor(linkColor);ds.setUnderlineText(true);}},r[0],r[1],Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            text.setText(sp);text.setHighlightColor(Color.TRANSPARENT);text.setMovementMethod(LinkMovementMethod.getInstance());text.setOnLongClickListener(v->{messageSheet(m);return true;});}
        // Tracking numbers, flights, and "Friday at 6" are tappable too.
        List<SmartText.Hit> smart=scan.smart;
        if(!smart.isEmpty()){Spannable sp=text.getText() instanceof Spannable?(Spannable)text.getText():new SpannableString(text.getText());int linkColor=outgoing?theme.onBubble(dark):ink;boolean added=false;
            for(SmartText.Hit h:smart){if(sp.getSpans(h.start,h.end,URLSpan.class).length>0||sp.getSpans(h.start,h.end,ClickableSpan.class).length>0)continue;added=true;
                sp.setSpan(new ClickableSpan(){@Override public void onClick(View w){smartTap(h,m,c);}@Override public void updateDrawState(TextPaint ds){ds.setColor(linkColor);ds.setUnderlineText(true);}},h.start,h.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
            if(added){text.setText(sp);text.setHighlightColor(Color.TRANSPARENT);text.setMovementMethod(LinkMovementMethod.getInstance());text.setOnLongClickListener(v->{messageSheet(m);return true;});}}
        if(text.getMovementMethod()!=null)holdLinks(text,m);
        b.setOnLongClickListener(v->{messageSheet(m);return true;});b.setOnClickListener(v->tapped(m,b));if(text.getMovementMethod()!=null)text.setOnClickListener(v->tapped(m,b));
        line.addView(b,new LinearLayout.LayoutParams(-2,-2));wrap.addView(line,new LinearLayout.LayoutParams(-1,-2));bubbleViews.put(m.optString("messageID"),b);
        String url=linkPreviewsOn()&&(outgoing||chatTrusted)?ChatText.firstUrl(fallback!=null?fallback:body):"";if(!url.isEmpty()){gap(wrap,4);wrap.addView(linkCard(url,outgoing,m),new LinearLayout.LayoutParams(-1,-2));}
        return wrap;
    }
    /**
     * Like iPhone: who replied to whom, the original as a faded bubble above, and a thread
     * line down to the reply. Tapping the original scrolls to it.
     */
    private void addReplyContext(LinearLayout wrap,JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,String target){
        JSONObject original=target.isEmpty()?null:store.messageByReference(chatId,target);boolean originalOut=original!=null&&PairedProtocol.outgoing(original,c);
        String from=outgoing?"You":senderFirst(m,c);
        String to=original==null?"":" to "+(originalOut?(outgoing?"yourself":"you"):senderFirst(original,c));
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
        if(isBusy()){toast("Still loading. Try again in a moment.");return;}if(pagesLeft==6)toast("Finding the original message…");
        busy=true;busySince=System.currentTimeMillis();PairedService.loadMessages(id,cursor,(v,e)->{busy=false;if(!e.isEmpty()){toast(e);return;}if(screen.equals("chat")&&chatId.equals(id))findOriginal(target,pagesLeft-1);});}
    private void refreshReply(JSONObject message){
        if(isBusy()){toast("Still loading. Try again in a moment.");return;}String id=chatId,mid=message.optString("messageID");
        if(!PairedService.ready()){unlinkedReply(message);return;}
        busy=true;busySince=System.currentTimeMillis();toast("Refreshing this reply from Google Messages…");
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
                TextView option=text(label,15,ink,false);option.setMaxLines(3);option.setEllipsize(TextUtils.TruncateAt.END);option.setPadding(dp(12),dp(12),dp(12),dp(12));pressable(option);
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
    /** Calendar apps that can add an event (Settings → Calendar app). */
    private List<android.content.pm.ResolveInfo> calendarApps(){return getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI),0);}
    /** Adds an event in your chosen calendar app: the one you picked, a choice every time, or ask once and remember. */
    private void openCalendar(Intent add){List<android.content.pm.ResolveInfo> apps=calendarApps();if(apps.isEmpty()){toast("No calendar app is installed.");return;}
        String pkg=getSharedPreferences("paired",MODE_PRIVATE).getString("calendar_app","");
        boolean installed=false;for(android.content.pm.ResolveInfo r:apps)if(r.activityInfo.packageName.equals(pkg))installed=true;
        if(installed){try{startActivity(new Intent(add).setPackage(pkg));return;}catch(Exception ignored){}}
        if(apps.size()==1){try{startActivity(new Intent(add).setPackage(apps.get(0).activityInfo.packageName));}catch(Exception e){toast("No calendar app is installed.");}return;}
        if(pkg.equals("ask")){try{startActivity(Intent.createChooser(add,"Add to calendar"));}catch(Exception e){toast("No calendar app is installed.");}return;}
        chooseCalendarApp(chosen->{try{startActivity(new Intent(add).setPackage(chosen));}catch(Exception e){toast("That calendar app couldn't add the event.");}});}
    private void chooseCalendarApp(java.util.function.Consumer<String> then){List<android.content.pm.ResolveInfo> apps=calendarApps();if(apps.isEmpty()){toast("No calendar app is installed.");return;}
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();java.util.Set<String> seen=new java.util.HashSet<>();
        for(android.content.pm.ResolveInfo r:apps){String pkg=r.activityInfo.packageName;if(!seen.add(pkg))continue;labels.add(String.valueOf(r.loadLabel(getPackageManager())));
            actions.add(()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putString("calendar_app",pkg).apply();if(then!=null)then.accept(pkg);else if(screen.equals("settings"))settings();});}
        labels.add("Ask every time");actions.add(()->{getSharedPreferences("paired",MODE_PRIVATE).edit().putString("calendar_app","ask").apply();if(screen.equals("settings"))settings();});
        menuSheet("Add dates to",labels,actions);}
    private String calendarAppLabel(){String pkg=getSharedPreferences("paired",MODE_PRIVATE).getString("calendar_app","");if(pkg.isEmpty())return "Ask the first time";if(pkg.equals("ask"))return "Ask every time";
        try{return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)));}catch(Exception e){return "Ask the first time";}}
    private void jumpTo(String id){View v=messageViews.get(id);
        // Saved but older than what's drawn: draw back to it, then jump.
        if(v==null&&scroll!=null){JSONObject saved=store.message(id);if(saved!=null&&chatId.equals(saved.optString("conversationID"))&&store.countSince(chatId,PairedProtocol.time(saved))>limit){showMessage(saved);return;}}
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
    private Dialog sheet(View content){return sheet(content,-1);}
    /** A sheet whose window handles the keyboard as softInput says (a WindowManager.LayoutParams SOFT_INPUT_ mode), set before it shows. */
    private Dialog sheet(View content,int softInput){
        Dialog d=new Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);if(softInput>=0&&d.getWindow()!=null)d.getWindow().setSoftInputMode(softInput);
        LinearLayout box=vertical();box.setPadding(dp(20),dp(10),dp(20),dp(28));GradientDrawable panel=new GradientDrawable();panel.setColor(card);float r=dp(28);panel.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});box.setBackground(panel);
        View handle=new View(this);handle.setBackground(shape(muted,2));handle.setAlpha(0.45f);LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(dp(32),dp(4));hl.gravity=Gravity.CENTER_HORIZONTAL;hl.bottomMargin=dp(14);box.addView(handle,hl);
        box.addView(content,new LinearLayout.LayoutParams(-1,-2));d.setContentView(box);d.show();
        Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setLayout(-1,-2);w.setGravity(Gravity.BOTTOM);}
        return d;
    }
    /** Shows a message's text so part of it can be selected with the usual handles and copied or shared. */
    private void selectText(String body){LinearLayout content=vertical();
        TextView hint=text("Press and hold a word, drag the handles, then tap Copy.",13,muted,false);content.addView(hint);gap(content,10);
        TextView t=text(body,17,ink,false);t.setTextIsSelectable(true);t.setLineSpacing(0,1.15f);t.setPadding(dp(14),dp(12),dp(14),dp(12));t.setBackground(shape(surface,16));
        ScrollView scroll=new ScrollView(this);scroll.addView(t);int max=Math.round(getResources().getDisplayMetrics().heightPixels*0.55f);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,-2));scroll.post(()->{if(scroll.getHeight()>max){ViewGroup.LayoutParams lp=scroll.getLayoutParams();lp.height=max;scroll.setLayoutParams(lp);}});
        gap(content,12);Dialog[] d=new Dialog[1];
        sheetAction(content,"Copy all",()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Message",body));});
        d[0]=sheet(content);}
    /** Everyone who reacted, with photo or initial, filterable by emoji. */
    private void reactionSheet(JSONObject m,JSONObject c){
        List<String[]> all=reactorsOf(m);if(all.isEmpty())return;
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
    private static boolean sending(String status){return switch(status){case "OUTGOING_SENDING","OUTGOING_YET_TO_SEND","OUTGOING_RESENDING","OUTGOING_VALIDATING","OUTGOING_AWAITING_RETRY"->true;default->false;};}
    /** While a message you sent shows "Sending…", check with Google every 20 seconds (for up to 10 minutes), in case an update was missed. */
    private final Runnable recheckSending=()->{if(screen.equals("chat")&&PairedService.ready()&&!isBusy())loadMessages(false);};
    private void watchSending(List<JSONObject> messages,JSONObject c){ui.removeCallbacks(recheckSending);long now=System.currentTimeMillis();
        // A photo sent to your phone but not shown back by Google yet: keep checking too.
        for(PendingMedia p:pendingMedia)if(p.chat.equals(chatId)&&p.sent>0&&now-p.sent<10*60_000){ui.postDelayed(recheckSending,20_000);return;}
        for(int i=messages.size()-1;i>=0&&i>=messages.size()-30;i--){JSONObject m=messages.get(i);
            if(PairedProtocol.outgoing(m,c)&&sending(PairedProtocol.status(m))&&now-PairedProtocol.time(m)<10*60_000){ui.postDelayed(recheckSending,20_000);return;}}}
    private void stuckSendingHelp(){new AlertDialog.Builder(this).setTitle("Still sending")
        .setMessage("Google Messages on your phone hasn't finished sending this yet. Photos and videos go out from your phone, so check Google Messages there: it may be waiting for a connection, or need you to retry or send it as MMS.")
        .setPositiveButton("Open Google Messages",(d,w)->{Intent open=getPackageManager().getLaunchIntentForPackage("com.google.android.apps.messaging");if(open!=null)startActivity(open);else toast("Google Messages isn't on this phone.");})
        .setNegativeButton("OK",null).show();}
    private View statusRow(JSONObject m,ChatTheme theme){
        LinearLayout r=row();r.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);r.setPadding(0,dp(4),dp(6),0);String s=PairedProtocol.status(m);
        if(PairedProtocol.failed(m)){boolean again=new AutoResend(this).pending(m.optString("messageID"));
            TextView t=text(s.equals("OUTGOING_FAILED_TOO_LARGE")?"Not sent: too large • Tap to send smaller":again?"Couldn't send • will retry when you have signal":"Not sent • Tap to retry",12,danger,!again);t.setOnClickListener(v->retryFailed(m));r.addView(t);return r;}
        switch(s){
            case "OUTGOING_DISPLAYED","OUTGOING_DELIVERED"->{ImageView ticks=new ImageView(this);ticks.setImageResource(R.drawable.ic_ticks);
                boolean read=s.equals("OUTGOING_DISPLAYED");ticks.setImageTintList(ColorStateList.valueOf(read?theme.onAccent(dark):muted));if(read)ticks.setBackground(oval(theme.accent(dark)));
                ticks.setPadding(dp(3),dp(3),dp(3),dp(3));ticks.setContentDescription(read?"Read":"Delivered");r.addView(ticks,new LinearLayout.LayoutParams(dp(22),dp(22)));
                if(!PairedProtocol.media(m).isEmpty())r.addView(text(read?"Read":"Delivered",12,muted,false));}
            case "OUTGOING_COMPLETE"->r.addView(text("Sent",12,muted,false));
            case "OUTGOING_SENDING","OUTGOING_YET_TO_SEND","OUTGOING_RESENDING","OUTGOING_VALIDATING","OUTGOING_AWAITING_RETRY"->{
                // Two minutes on, it's most likely waiting on your phone, not this app.
                boolean slow=System.currentTimeMillis()-PairedProtocol.time(m)>120_000;TextView t=text(slow?"Still sending on your phone • Tap for help":!PairedProtocol.media(m).isEmpty()?"Waiting for your phone to send…":"Sending…",12,muted,false);
                if(slow)t.setOnClickListener(v->stuckSendingHelp());r.addView(t);}
            default->{}
        }
        return r;
    }
    private void loadOlderMessages(){if(isBusy()||!screen.equals("chat"))return;
        // Older saved messages first (no network), keeping your place; then older pages from Google.
        if(moreSaved){limit+=WINDOW;renderMessages(false);return;}String cursor=store.page("messages:"+chatId,false);if(cursor.isEmpty())return;anchorOlder=true;loadMessages(true);}
    private void loadMessages(boolean more){if(isBusy())return;String id=chatId,cursor=more?store.page("messages:"+id,false):"";
        if(more&&cursor.isEmpty()){anchorOlder=false;String note=store.page("messages:"+id,true);toast(note.isEmpty()?"Refresh this conversation first.":note);return;}
        busy=true;busySince=System.currentTimeMillis();PairedService.loadMessages(id,cursor,(v,e)->{busy=false;if(!e.isEmpty()){anchorOlder=false;toast(e);}if(screen.equals("chat")&&chatId.equals(id))messagesLoaded(more);});}
    /** Google sent this chat's messages: show them without moving you (renderMessages keeps your place unless you were at the bottom). */
    private void messagesLoaded(boolean more){if(more)limit+=100;renderMessages(false);markReadIfAllowed();}
    /**
     * Opening a chat draws just its newest messages (and all unread ones), so it shows at once and
     * every redraw while you're in it (Google's reply, read receipts, new messages) stays quick.
     * Older saved messages are added as you scroll up toward them (see showOlderSaved).
     */
    private static final int FIRST_WINDOW=30;
    /** The oldest message drawn, and its chat. */
    private String oldestChat="";private long oldestTime;
    private int firstWindow(String id){if(unreadSince<=0)return FIRST_WINDOW;return Math.min(WINDOW,Math.max(FIRST_WINDOW,store.countSince(id,unreadSince)+12));}
    /** Scrolling up within a screen of the oldest message drawn: adds the next older saved ones, keeping your place. */
    private void showOlderSaved(){if(!moreSaved||!screen.equals("chat")||placing!=null)return;limit+=WINDOW;renderMessages(false);}
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
    private boolean sendingText;
    private void send(){if(composer==null)return;repliedSinceInbox=true;unreadSince=-1;String body=composer.getText().toString(),id=chatId,reply=replyId;JSONObject c=store.chat(id);
        boolean files=false;for(Outgoing o:outgoing)if(!o.sending)files=true;
        if(body.trim().isEmpty()&&!files)return;if(c==null){error("Refresh this conversation before sending.");return;}
        if(sendButton!=null)sendButton.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        // "Happy birthday" sets off balloons here too, as it does on iPhones.
        String fx=effectsOn()?Effects.phrase(body):"";if(Effects.fullScreen(fx))ui.postDelayed(()->{if(screen.equals("chat")&&chatId.equals(id))playEffect(fx,null,body.trim());},280);
        if(files){sendAttachments(c,body.trim(),reply);return;}
        if(undoSendOn()){long hold=undoSendMs();JSONObject held=outbox.add(id,body,reply,hold);drafts.remove(id);composer.setText("");cancelReply();renderMessages(true);
            String hid=held.optString("id");ui.postDelayed(()->releaseHeld(hid),hold);return;}
        // Its own guard (not the one for loading): a slow or stuck load never stops you sending.
        if(sendingText)return;sendingText=true;ui.postDelayed(()->sendingText=false,30_000);
        PairedService.sendOrQueue(this,id,body,reply,(value,e)->{sendingText=false;
            if(!e.isEmpty()){error(e);return;}if("queued".equals(value))toast("Saved. It will send when Message Center reconnects.");drafts.remove(id);if(screen.equals("chat")&&chatId.equals(id)){composer.setText("");cancelReply();}
            if(screen.equals("chat")&&chatId.equals(id))loadMessages(false);});}
    private void startChat(String typed){if(!PairedService.ready()){toast("Connect first, then start a chat.");return;}String n=ChatText.normalizeNumber(typed),value=n.isEmpty()?typed:n;
        PairedService.execute(c->c.createChat(value),(raw,e)->{if(!e.isEmpty()){error(e);return;}try{JSONObject chat=new JSONObject(raw);store.chat(chat);
            if(bubble()){startActivity(new Intent(this,PairedActivity.class).setAction(OPEN_CHAT).putExtra("chat",chat.getString("conversationID")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP));return;}
            thread(chat.getString("conversationID"));}catch(Exception ex){error("The new conversation could not be opened.");}});}
    /** Long-press a message: react (tap your current reaction to remove it), reply, copy, or see reactions. */
    private void messageSheet(JSONObject m){JSONObject c=store.chat(chatId);LinearLayout content=vertical();Dialog[] d=new Dialog[1];
        int photoNumber=0;for(JSONObject media:PairedProtocol.media(m))if(MediaCache.mime(media).startsWith("image/")){
            int number=++photoNumber;sheetAction(content,"Read text in photo "+number,()->{d[0].dismiss();readPhotoText(media);});}
        List<String[]> reactors=reactorsOf(m);String mine=myReaction(m,c);
        List<String> choices=new ArrayList<>(java.util.Arrays.asList(REACTIONS));if(!mine.isEmpty()&&!choices.contains(mine)){choices.add(0,mine);choices.remove(choices.size()-1);}
        LinearLayout emojis=row();String current=mine;
        for(String e:choices){TextView t=text(e,24,ink,false);t.setGravity(Gravity.CENTER);boolean selected=e.equals(current);if(selected)t.setBackground(oval(fab));
            t.setContentDescription(selected?"Your reaction: "+e:"React with "+e);
            // Picking the reaction you already gave changes nothing, so nothing is sent.
            t.setOnClickListener(v->{d[0].dismiss();if(!selected)react(m,e,current.isEmpty()?"add":"switch");});emojis.addView(t,new LinearLayout.LayoutParams(0,dp(50),1));}
        // Any other emoji: Google's connection takes any emoji as a reaction.
        if(emojiIndex==null)new Thread(this::emojiIndex,"emoji-index").start();
        TextView more=text("+",26,muted,false);more.setGravity(Gravity.CENTER);more.setBackground(oval(surface));more.setContentDescription("React with another emoji");
        more.setOnClickListener(v->{d[0].dismiss();emojiPicker(e->{if(!ReactionParser.sameEmoji(e,current))react(m,e,current.isEmpty()?"add":"switch");});});
        emojis.addView(more,new LinearLayout.LayoutParams(0,dp(50),1));
        content.addView(emojis);gap(content,10);
        if(!mine.isEmpty())sheetAction(content,"Remove your "+mine+" reaction",()->{d[0].dismiss();react(m,current,"remove");});
        sheetAction(content,"Reply",()->{d[0].dismiss();startReply(m);});
        String fx=effectOf(m);if(!fx.isEmpty()&&!Effects.INK.equals(fx)&&ValueAnimator.areAnimatorsEnabled())sheetAction(content,"Replay "+Effects.label(fx),()->{d[0].dismiss();replayEffect(m,fx);});
        String code=PairedProtocol.outgoing(m,c)?"":ChatText.oneTimeCode(PairedProtocol.text(m));if(!code.isEmpty())sheetAction(content,"Copy code "+code,()->{d[0].dismiss();copyCode(code);});
        sheetAction(content,starred.has(m.optString("messageID"))?"Unstar":"Star",()->{d[0].dismiss();starred.toggle(m);renderMessages(false);});
        boolean pinnedHere=pins.has(chatId,m.optString("messageID"));
        sheetAction(content,pinnedHere?"Unpin from top of chat":"Pin to top of chat",()->{d[0].dismiss();String mid=m.optString("messageID");
            if(pinnedHere)pins.unpin(chatId,mid);else if(pins.pin(chatId,mid,pinText(m),PairedProtocol.time(m)))toast("Up to "+ChatPins.MAX+" pins per chat: the oldest was unpinned.");
            pinShown=0;renderPinBar();});
        String typed=PairedProtocol.text(m),plain=ChatText.replyFallbackBody(typed)!=null?ChatText.replyFallbackBody(typed):typed;
        if(!plain.isEmpty())sheetAction(content,"Copy text",()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Message",plain));});
        // Links in the message can be copied on their own (one, or each of the first three).
        List<String> links=new ArrayList<>(new java.util.LinkedHashSet<>(ChatText.urls(plain)));
        for(String link:links.subList(0,Math.min(3,links.size())))sheetAction(content,links.size()==1?"Copy link":"Copy link: "+ChatText.shortLink(link),()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Link",link));});
        if(!plain.isEmpty())sheetAction(content,"Select text…",()->{d[0].dismiss();selectText(plain);});
        // Keep a message, photo or file in your notes about them: as a new note, or added to one you have.
        String[] noteTo=c==null?null:noteTarget(m,c);
        if(noteTo!=null&&!noteTo[0].isEmpty()&&(!plain.isEmpty()||!PairedProtocol.media(m).isEmpty())){String what=noteWhat(m),these=what.endsWith("s")?"these ":"this ";
            sheetAction(content,"New note about "+noteTo[1]+" with "+these+what,()->{d[0].dismiss();messageToNote(m,c,false);});
            if(new Notes(this).has(noteTo[0]))sheetAction(content,"Add "+these+what+" to a note…",()->{d[0].dismiss();messageToNote(m,c,true);});}
        for(JSONObject media:PairedProtocol.media(m)){String transcript=Transcriber.saved(this,PairedProtocol.string(media,"mediaID"));
            if(transcript!=null){sheetAction(content,"Copy transcript",()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Transcript",transcript));});
                sheetAction(content,"Transcribe again",()->{d[0].dismiss();Transcriber.forget(this,PairedProtocol.string(media,"mediaID"));transcribe(media);});}
            String kind=PairedProtocol.mediaKind(media),what=kind.equals("image")?"photo":kind.equals("video")?"video":kind.equals("audio")?"audio":"file";
            if(kind.equals("image")||kind.equals("video"))sheetAction(content,"Copy "+what,()->{d[0].dismiss();copyToClipboard("",java.util.Collections.singletonList(media));});
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
        pressable(t);t.setOnClickListener(v->action.run());parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
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
    private static volatile EmojiIndex emojiIndex;
    /** Every emoji with its name and keywords (assets/emoji.tsv), read once; the message sheet starts reading it early. */
    private EmojiIndex emojiIndex(){EmojiIndex i=emojiIndex;if(i!=null)return i;
        synchronized(PairedActivity.class){if(emojiIndex==null){try(java.io.Reader r=new java.io.InputStreamReader(getAssets().open("emoji.tsv"),java.nio.charset.StandardCharsets.UTF_8)){emojiIndex=EmojiIndex.read(r);}
            catch(java.io.IOException e){android.util.Log.w("MessageCenter","emoji index",e);emojiIndex=EmojiIndex.empty();}}return emojiIndex;}}
    private static final Map<String,Boolean> emojiFont=new java.util.concurrent.ConcurrentHashMap<>();
    /** Whether this phone can draw the emoji: everything up to Emoji 14 comes with Android 13; newer ones are checked against its emoji font. */
    private static boolean canDraw(String emoji,double version){if(version<=14)return true;Boolean b=emojiFont.get(emoji);if(b==null){b=new Paint().hasGlyph(emoji);emojiFont.put(emoji,b);}return b;}
    private static final String EMOJI_PREFS="emoji",SEPARATOR="\u001f";
    /** Emoji you picked, newest first, exactly as picked (skin tone and all). */
    private List<String> recentEmoji(){List<String> out=new ArrayList<>();for(String e:getSharedPreferences(EMOJI_PREFS,MODE_PRIVATE).getString("recent","").split(SEPARATOR))if(!e.isEmpty())out.add(e);return out;}
    private void usedEmoji(String emoji){List<String> recent=recentEmoji();recent.remove(emoji);recent.add(0,emoji);while(recent.size()>40)recent.remove(recent.size()-1);
        getSharedPreferences(EMOJI_PREFS,MODE_PRIVATE).edit().putString("recent",String.join(SEPARATOR,recent)).apply();}
    /** The skin tone you last chose for this emoji, or the emoji itself. */
    private String skinOf(EmojiIndex.Emoji e){return e.hasSkins()?getSharedPreferences(EMOJI_PREFS,MODE_PRIVATE).getString("skin:"+e.emoji,e.emoji):e.emoji;}
    /** One emoji in the picker; a small corner mark when it has skin tones (press and hold for them). */
    private TextView emojiCell(){TextView t=new TextView(this){final Paint mark=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){super.onDraw(c);if(!Boolean.TRUE.equals(getTag()))return;float w=getWidth(),h=getHeight(),m=dp(6),z=dp(5);
                android.graphics.Path p=new android.graphics.Path();p.moveTo(w-m,h-m-z);p.lineTo(w-m,h-m);p.lineTo(w-m-z,h-m);p.close();mark.setColor(muted);c.drawPath(p,mark);}};
        t.setTextSize(26);t.setTextColor(ink);t.setGravity(Gravity.CENTER);t.setIncludeFontPadding(false);return t;}
    /**
     * The emoji picker: your recent emoji and each category in tabs, a search box that looks through
     * every emoji's name and keywords (or takes an emoji typed on the keyboard; Enter picks the first
     * match), and press-and-hold for skin tones. picked gets the emoji chosen.
     */
    private Dialog emojiPicker(java.util.function.Consumer<String> picked){EmojiIndex index=emojiIndex();
        LinearLayout content=vertical();Dialog[] d=new Dialog[1];boolean[] done={false};
        java.util.function.Consumer<String> choose=e->{if(done[0])return;done[0]=true;usedEmoji(e);if(d[0]!=null)d[0].dismiss();picked.accept(e);};
        EditText search=input("Search emoji");search.setSingleLine(true);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);search.setContentDescription("Search emoji");
        content.addView(search,new LinearLayout.LayoutParams(-1,-2));gap(content,8);
        TextView[] tabs=new TextView[EmojiIndex.CATEGORIES.length+1];LinearLayout tabRow=row();
        for(int i=0;i<tabs.length;i++){TextView t=text(i==0?"🕘":EmojiIndex.CATEGORY_ICONS[i-1],19,ink,false);t.setGravity(Gravity.CENTER);t.setContentDescription(i==0?"Recent":EmojiIndex.CATEGORY_NAMES[i-1]);
            tabs[i]=t;tabRow.addView(t,new LinearLayout.LayoutParams(0,dp(40),1));}
        content.addView(tabRow,new LinearLayout.LayoutParams(-1,-2));gap(content,6);
        List<String> faces=new ArrayList<>();List<EmojiIndex.Emoji> entries=new ArrayList<>();
        android.widget.GridView grid=new android.widget.GridView(this);grid.setNumColumns(android.widget.GridView.AUTO_FIT);grid.setColumnWidth(dp(44));
        grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);grid.setSelector(new ColorDrawable(Color.TRANSPARENT));grid.setContentDescription("Emoji");
        android.widget.BaseAdapter adapter=new android.widget.BaseAdapter(){
            public int getCount(){return faces.size();}public Object getItem(int i){return faces.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View old,ViewGroup parent){TextView cell=old instanceof TextView?(TextView)old:emojiCell();EmojiIndex.Emoji e=entries.get(i);
                cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,dp(48)));cell.setText(faces.get(i));cell.setTag(e!=null&&e.hasSkins());
                cell.setContentDescription(e!=null?e.name:faces.get(i));return cell;}};
        grid.setAdapter(adapter);
        TextView empty=text("",14,muted,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),0,dp(24),0);
        FrameLayout area=new FrameLayout(this);area.addView(grid,new FrameLayout.LayoutParams(-1,-1));area.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        content.addView(area,new LinearLayout.LayoutParams(-1,dp(300)));
        int[] tab={-1};
        Runnable shown=()->{adapter.notifyDataSetChanged();grid.setSelection(0);
            for(int i=0;i<tabs.length;i++){tabs[i].setAlpha(i==tab[0]?1f:0.45f);tabs[i].setBackground(i==tab[0]?shape(surface,14):null);}};
        java.util.function.IntConsumer showTab=t->{tab[0]=t;faces.clear();entries.clear();
            if(t==0)for(String r:recentEmoji()){faces.add(r);entries.add(index.find(r));}
            else for(EmojiIndex.Emoji e:index.category(EmojiIndex.CATEGORIES[t-1]))if(canDraw(e.emoji,e.version)){faces.add(skinOf(e));entries.add(e);}
            empty.setText(!faces.isEmpty()?"":t==0?"Emoji you pick show up here.":"No emoji here.");shown.run();};
        for(int i=0;i<tabs.length;i++){int t=i;tabs[i].setOnClickListener(v->{hideKeyboard(search);search.clearFocus();tab[0]=t;if(search.length()>0)search.setText("");else showTab.accept(t);});}
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}
            public void afterTextChanged(Editable s){String q=s.toString().trim();if(q.isEmpty()){showTab.accept(Math.max(tab[0],0));return;}
                faces.clear();entries.clear();String typed=ChatText.firstEmoji(q);
                if(!typed.isEmpty()){faces.add(typed);entries.add(index.find(typed));}
                else for(EmojiIndex.Emoji e:index.search(q,240))if(canDraw(e.emoji,e.version)){faces.add(skinOf(e));entries.add(e);}
                // While searching no tab is chosen; the one you were on comes back when the box is cleared.
                int was=tab[0];tab[0]=-1;empty.setText(faces.isEmpty()?"No emoji match \u201c"+q+"\u201d":"");shown.run();tab[0]=was;}});
        search.setOnEditorActionListener((v,action,event)->{if(search.getText().toString().trim().isEmpty()||faces.isEmpty()){hideKeyboard(search);return true;}choose.accept(faces.get(0));return true;});
        grid.setOnItemClickListener((parent,v,i,id)->choose.accept(faces.get(i)));
        grid.setOnItemLongClickListener((parent,v,i,id)->{EmojiIndex.Emoji e=entries.get(i);
            if(e!=null&&e.hasSkins())skinTones(v,e,face->{getSharedPreferences(EMOJI_PREFS,MODE_PRIVATE).edit().putString("skin:"+e.emoji,face).apply();choose.accept(face);});
            else toast(e!=null?e.name:faces.get(i));return true;});
        showTab.accept(recentEmoji().isEmpty()?1:0);
        d[0]=sheet(content);Window w=d[0].getWindow();if(w!=null)w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        return d[0];}
    /** Press and hold an emoji that has skin tones: the tones this phone can draw, in a small pop-up above it. */
    private android.widget.PopupWindow skinTones(View anchor,EmojiIndex.Emoji e,java.util.function.Consumer<String> picked){List<String> tones=new ArrayList<>();tones.add(e.emoji);
        for(int i=0;i<e.skins.length;i++)if(canDraw(e.skins[i],e.skinVersions[i]))tones.add(e.skins[i]);
        LinearLayout box=vertical();box.setPadding(dp(6),dp(6),dp(6),dp(6));box.setBackground(shape(card,16));box.setElevation(dp(8));
        android.widget.PopupWindow pop=new android.widget.PopupWindow(box,-2,-2,true);LinearLayout line=null;
        for(int i=0;i<tones.size();i++){if(i%6==0){line=row();box.addView(line);}String face=tones.get(i);TextView t=emojiCell();t.setText(face);t.setContentDescription(face);
            t.setOnClickListener(v->{pop.dismiss();picked.accept(face);});line.addView(t,new LinearLayout.LayoutParams(dp(44),dp(48)));}
        box.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);pop.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));pop.setOutsideTouchable(true);pop.setElevation(dp(8));
        pop.showAsDropDown(anchor,(anchor.getWidth()-box.getMeasuredWidth())/2,-(anchor.getHeight()+box.getMeasuredHeight()));return pop;}
    /** Sends a reaction, unless it is the one you already gave (so the other person isn't sent a repeat). */
    private void react(JSONObject m,String emoji,String action){JSONObject chat=store.chat(chatId);if(chat==null){error("Refresh this conversation first.");return;}
        String id=m.optString("messageID"),mine=myReaction(m,chat);
        if(action.equals("remove")?mine.isEmpty():sameEmoji(emoji,mine))return;
        getWindow().getDecorView().performHapticFeedback(action.equals("remove")?android.view.HapticFeedbackConstants.CONTEXT_CLICK:android.view.HapticFeedbackConstants.CONFIRM);
        String sent=action.equals("remove")?"":emoji;sentReactions.put(id,sent);sentReactionAt.put(id,android.os.SystemClock.uptimeMillis());
        PairedService.execute(cl->{cl.react(chat.toString(),id,emoji,mine.isEmpty()||action.equals("remove")?action:"switch");return "";},(v,e)->{
            if(!e.isEmpty()){if(sent.equals(sentReactions.get(id))){sentReactions.remove(id);sentReactionAt.remove(id);}error(e);}else loadMessages(false);});}
    private final Map<String,String> sentReactions=new HashMap<>();private final Map<String,Long> sentReactionAt=new HashMap<>();
    /** Double-tap a message to like it with 👍. Already liked: nothing is sent. */
    private long lastTapAt;private String lastTapId="";
    private void tapped(JSONObject m,View v){String id=m.optString("messageID");long now=android.os.SystemClock.uptimeMillis();
        if(id.equals(lastTapId)&&now-lastTapAt<=android.view.ViewConfiguration.getDoubleTapTimeout()+100){lastTapId="";quickLike(m,v);return;}
        lastTapId=id;lastTapAt=now;}
    private void quickLike(JSONObject m,View v){
        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).withEndAction(()->v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()).start();
        JSONObject c=store.chat(chatId);if(c==null)return;String mine=myReaction(m,c);if(sameEmoji(mine,"👍"))return;
        react(m,"👍",mine.isEmpty()?"add":"switch");}
    private void startReply(JSONObject m){if(replyPanel==null||composer==null)return;JSONObject c=store.chat(chatId);replyId=m.optString("messageID");
        replyLabel.setText(PairedProtocol.outgoing(m,c)?"Replying to yourself":"Replying to "+senderFirst(m,c));replyQuote.setText(displayBody(m));
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
            LinearLayout r=row();r.setPadding(dp(4),dp(10),dp(4),dp(10));pressable(r);r.addView(personAvatar(p,name,44));
            LinearLayout texts=vertical();texts.setPadding(dp(16),0,dp(8),0);texts.addView(text(name,17,ink,false));if(!number.isEmpty()&&!number.equals(name))texts.addView(text(number,14,muted,false));
            r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));r.setOnClickListener(v->memberActions(p));content.addView(r);}
        // One-to-one: the group chats you're both in.
        if(!group){JSONObject other=PairedProtocol.other(c);List<JSONObject> common=WhoIsThis.find(PairedProtocol.number(other),id,allChats(),null).groups;
            if(!common.isEmpty()){gap(content,16);content.addView(text("Groups in common",14,muted,true));gap(content,4);
                for(JSONObject g:common){LinearLayout r=row();r.setPadding(dp(4),dp(10),dp(4),dp(10));pressable(r);r.addView(avatar(g,44));
                    LinearLayout texts=vertical();texts.setPadding(dp(16),0,dp(8),0);texts.addView(text(PairedProtocol.title(g),17,ink,false));
                    texts.addView(text((PairedProtocol.members(g).size()+1)+" people",14,muted,false));r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
                    r.setOnClickListener(v->thread(g.optString("conversationID")));content.addView(r);}}}
        if(group){LinearLayout me=row();me.setPadding(dp(4),dp(10),dp(4),dp(10));me.addView(personAvatar(null,"You",44));TextView you=text("You",17,ink,false);you.setPadding(dp(16),0,0,0);me.addView(you);content.addView(me);}
        gap(content,16);notesCard(content,notesKeyFor(c),PairedProtocol.title(c),this::details);
        // What's been shared here; filled in once it's been looked up.
        LinearLayout shared=vertical();content.addView(shared,new LinearLayout.LayoutParams(-1,-2));
        loadShared(id,sh->{if(screen.equals("details")&&chatId.equals(id))fillSharedSummary(shared,sh);});
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
        String ownShows=NotificationPrivacy.own(this,id);
        sheetAction(content,"Notifications show: "+(ownShows.isEmpty()?"same as all chats ("+NotificationPrivacy.label(NotificationPrivacy.mode(this)).toLowerCase(Locale.ROOT)+")":NotificationPrivacy.label(ownShows).toLowerCase(Locale.ROOT)),()->chatNotificationContent(id));
        if(group){
            // Google's connection can rename RCS groups for everyone; a group photo can only be set here, on this phone.
            if(PairedProtocol.rcs(c))sheetAction(content,"Rename group…",()->renameGroup(id));
            sheetAction(content,Avatars.ownGroupPhoto(this,id).isFile()?"Group photo: yours, on this phone (change)":"Group photo on this phone…",()->groupPhotoMenu(id));}
        sheetAction(content,"Search in chat",()->{thread(id);searchChat();});
        sheetAction(content,"Refresh from Google",()->{thread(id);loadMessages(false);});
        sheetAction(content,"Load older messages",()->{thread(id);loadOlderMessages();});
        sheetAction(content,"Needs a reply / mark handled…",()->replyFollowUp(c));
        if(!group&&!people.isEmpty()){JSONObject p0=people.get(0);String nick=Nicknames.get(PairedProtocol.number(p0));sheetAction(content,nick.isEmpty()?"Set a nickname…":"Nickname: "+nick+" (change)",()->setNickname(p0));}
        sheetAction(content,"Export chat to a file",()->exportChat(id));
        sheetAction(content,"Notification sound & priority",()->{PairedService.ensureShortcut(this,id);PairedService.openConversationSettings(this,id,PairedProtocol.title(c));});
        if(!bubble()){boolean archived=c.optString("status").contains("ARCHIVED"),blocked=c.optString("status").equals("BLOCKED_FOLDER")||c.optString("status").equals("SPAM_FOLDER");
            sheetAction(content,archived?"Unarchive":"Archive",()->archive(c,!archived));sheetAction(content,blocked?"Unblock":"Block…",()->block(c));sheetAction(content,"Delete conversation…",()->deleteChat(c));}
        if(!bubble())sheetAction(content,"Open as bubble",()->openBubble(id));
        requestMemberPhotos(c,()->{if(screen.equals("details")&&chatId.equals(id))details();});}
    // ---- Pinned messages and "Who's this?" (both under a chat's header) ----
    private ChatPins pins;private LinearLayout pinBar;private int pinShown;
    /** A pinned message in a line: its words, or what it is when it's only a photo or file. */
    private String pinText(JSONObject m){String body=displayBody(m).trim();if(!body.isEmpty())return body;
        for(JSONObject media:PairedProtocol.media(m)){String kind=PairedProtocol.mediaKind(media);return kind.equals("image")?"Photo":kind.equals("video")?"Video":kind.equals("audio")?"Voice message":"File";}
        return "Message";}
    /**
     * The pinned message under the header: tap to go to it (with several, each tap moves on to the
     * next), copy it with the button, long-press to unpin. Only on this phone.
     */
    private void renderPinBar(){if(pinBar==null)return;pinBar.removeAllViews();List<JSONObject> list=pins.list(chatId);
        if(list.isEmpty()){pinBar.setVisibility(View.GONE);return;}pinBar.setVisibility(View.VISIBLE);if(pinShown>=list.size())pinShown=0;
        JSONObject p=list.get(pinShown);String mid=p.optString("id"),words=p.optString("text");
        LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(12),dp(6),0,dp(6));r.setBackground(shape(surface,16));pressable(r);
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.ic_pin);mark.setImageTintList(ColorStateList.valueOf(muted));r.addView(mark,new LinearLayout.LayoutParams(dp(18),dp(18)));
        LinearLayout texts=vertical();texts.setPadding(dp(10),0,dp(4),0);
        if(list.size()>1)texts.addView(text("Pinned "+(pinShown+1)+" of "+list.size(),12,muted,false));
        TextView t=text(words,15,ink,false);t.setMaxLines(2);t.setEllipsize(TextUtils.TruncateAt.END);texts.addView(t);r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        ImageView copy=icon(R.drawable.ic_copy,muted,"Copy pinned message",()->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Pinned message",words));toast("Copied");});r.addView(copy);
        r.setContentDescription("Pinned message: "+words+". Tap to go to it, long-press to unpin.");
        r.setOnClickListener(v->{if(store.message(mid)==null)toast("That message isn't on this phone anymore. Long-press to unpin it.");else jumpTo(mid);
            if(list.size()>1){pinShown=(pinShown+1)%list.size();renderPinBar();}});
        r.setOnLongClickListener(v->{List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
            for(JSONObject q:list){String qid=q.optString("id"),qt=q.optString("text");labels.add("Unpin “"+(qt.length()>40?qt.substring(0,39)+"…":qt)+"”");actions.add(()->{pins.unpin(chatId,qid);pinShown=0;renderPinBar();});}
            if(list.size()>1){labels.add("Unpin all");actions.add(()->{pins.clear(chatId);renderPinBar();});}
            menuSheet("Pinned messages",labels,actions);return true;});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(8),dp(2),dp(8),dp(4));pinBar.addView(r,lp);}
    /** Saved anywhere on this phone: Google's contact link first, then the phone's own contacts (when allowed). */
    private boolean savedContact(JSONObject other,String number){if(PairedProtocol.known(other))return true;if(!PhoneContacts.allowed(this))return false;
        try(android.database.Cursor c=getContentResolver().query(Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,Uri.encode(number)),new String[]{android.provider.ContactsContract.PhoneLookup._ID},null,null,null)){return c!=null&&c.moveToFirst();}
        catch(Exception e){return false;}}
    private List<JSONObject> allChats(){List<JSONObject> all=new ArrayList<>();for(int folder:new int[]{1,2})all.addAll(store.chats("",folder));return all;}
    /**
     * "Who's this?" for a number you haven't saved: the name Google has for them, the group chats
     * you're both in, and Save contact. Dismissed per chat; gone once they're saved.
     */
    private View whoIsThisCard(JSONObject c){JSONObject other=PairedProtocol.other(c);String id=chatId;if(other==null)return null;String number=PairedProtocol.number(other);
        android.content.SharedPreferences seen=getSharedPreferences("whois",MODE_PRIVATE);
        if(number.isEmpty()||SmartText.shortCodeSender(number)||seen.getBoolean(id,false)||savedContact(other,number))return null;
        WhoIsThis.Found f=WhoIsThis.find(number,id,allChats(),contacts==null?null:contacts.optJSONArray("all"));
        List<String> titles=new ArrayList<>();for(JSONObject g:f.groups)titles.add(PairedProtocol.title(g));
        LinearLayout card=vertical();card.setPadding(dp(14),dp(10),dp(4),dp(10));card.setBackground(shape(surface,16));
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);TextView head=text("Not in your contacts",15,ink,true);top.addView(head,new LinearLayout.LayoutParams(0,-2,1));
        View[] holder=new View[1];
        top.addView(icon(R.drawable.ic_close,muted,"Dismiss",()->{seen.edit().putBoolean(id,true).apply();if(holder[0]!=null)holder[0].setVisibility(View.GONE);}));card.addView(top);
        if(!f.name.isEmpty())card.addView(text("Google lists them as "+f.name,14,muted,false));
        TextView groups=text(WhoIsThis.alsoIn(titles),14,f.groups.isEmpty()?muted:ink,false);card.addView(groups);
        if(!f.groups.isEmpty()){pressable(groups);groups.setOnClickListener(v->{if(f.groups.size()==1){thread(f.groups.get(0).optString("conversationID"));return;}
            List<Runnable> open=new ArrayList<>();for(JSONObject g:f.groups)open.add(()->thread(g.optString("conversationID")));menuSheet("Groups you're both in",titles,open);});}
        TextView save=text("Save contact",15,fab,true);save.setPadding(0,dp(8),dp(12),0);pressable(save);
        save.setOnClickListener(v->{Intent i=new Intent(android.provider.ContactsContract.Intents.Insert.ACTION).setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE,number);if(!f.name.isEmpty())i.putExtra(android.provider.ContactsContract.Intents.Insert.NAME,f.name);
            try{startActivity(i);}catch(Exception e){toast("No contacts app is available.");}});
        card.addView(save,new LinearLayout.LayoutParams(-2,-2));
        FrameLayout wrap=new FrameLayout(this);FrameLayout.LayoutParams cl=new FrameLayout.LayoutParams(-1,-2);cl.setMargins(dp(8),dp(2),dp(8),dp(4));wrap.addView(card,cl);holder[0]=wrap;return wrap;}
    // ---- Notes: private notes about a person or group, kept on this phone ----
    private String notesKey="",notesWho="",notesQuery="";private Runnable notesBack;
    private Notes.Note editing;private String editingKey="",editingWho="",editingSnapshot="";private Runnable noteBack;
    private EditText noteField;private LinearLayout noteTags,noteFiles;private ImageView notePin;
    private final Runnable noteSaver=()->saveNote();
    private static final int PICK_NOTE_FILES=91,PICK_NOTE_PHOTOS=92;
    private final android.util.LruCache<String,Bitmap> noteThumbs=new android.util.LruCache<String,Bitmap>(8*1024*1024){@Override protected int sizeOf(String k,Bitmap v){return v.getByteCount();}};
    /** Whose notes a chat opens: the person's in a one-to-one chat, the group's in a group. */
    private String notesKeyFor(JSONObject chat){if(chat==null)return "";JSONObject other=PairedProtocol.other(chat);
        return other!=null?Notes.personKey(PairedProtocol.number(other)):Notes.groupKey(chat.optString("conversationID"));}
    private void openNotes(JSONObject chat,Runnable back){if(chat!=null)notesList(notesKeyFor(chat),PairedProtocol.title(chat),back);}
    /** "Nov 12" this year, "Nov 12, 2025" before. */
    private String noteDate(long when){return android.text.format.DateUtils.formatDateTime(this,when,android.text.format.DateUtils.FORMAT_SHOW_DATE|android.text.format.DateUtils.FORMAT_ABBREV_MONTH);}
    private TextView tagChip(String label,boolean on){TextView t=text(label,13,on?onFab:ink,false);t.setPadding(dp(12),dp(6),dp(12),dp(6));t.setBackground(shape(on?fab:surface,14));return t;}
    /**
     * Your notes about someone (or a group): pinned first, then newest. Search by words or #tag
     * (tap a tag to filter by it), tap a note to read or edit it, hold one to pin or delete it.
     */
    private void notesList(String key,String who,Runnable back){if(key==null||key.isEmpty()){toast("Notes need a phone number to go with.");return;}
        if(!key.equals(notesKey))notesQuery="";base("notes");notesKey=key;notesWho=who;notesBack=back;
        header("Notes",this::leaveNotes);
        TextView about=text(who+" · only on this phone, never sent",13,muted,false);about.setPadding(dp(16),0,dp(16),dp(10));root.addView(about);
        List<Notes.Note> all=new Notes(this).list(key);LinearLayout list=vertical();list.setPadding(0,dp(4),0,dp(96));
        LinearLayout chips=row();
        if(!all.isEmpty()){EditText search=input("Search notes or #tags");search.setSingleLine(true);search.setText(notesQuery);search.setContentDescription("Search notes");
            LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,-2);sl.setMargins(dp(4),0,dp(4),dp(8));root.addView(search,sl);
            List<String> tags=Notes.tagsOf(all);
            if(!tags.isEmpty()){HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);hs.addView(chips);hs.setPadding(dp(4),0,dp(4),dp(8));root.addView(hs);}
            Runnable[] draw=new Runnable[1];
            draw[0]=()->{chips.removeAllViews();for(String t:tags){boolean on=hasTagWord(notesQuery,t);TextView chip=tagChip("#"+t,on);chip.setContentDescription((on?"Stop filtering by ":"Filter by ")+t);
                    chip.setOnClickListener(v->{String next=on?withoutTagWord(notesQuery,t):(notesQuery.trim()+" #"+t.replace(' ','_')).trim();search.setText(next);search.setSelection(next.length());});
                    LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-2,-2);cl.setMarginEnd(dp(8));chips.addView(chip,cl);}
                renderNoteCards(list,all,key,who);};
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}
                public void afterTextChanged(Editable e){notesQuery=e.toString();draw[0].run();}});
            draw[0].run();}
        else renderNoteCards(list,all,key,who);
        ScrollView sc=new ScrollView(this);sc.setVerticalScrollBarEnabled(false);sc.addView(list);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        TextView add=new TextView(this);add.setText("New note");add.setTextSize(16);add.setTextColor(onFab);add.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        add.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add,0,0,0);add.setCompoundDrawableTintList(ColorStateList.valueOf(onFab));add.setCompoundDrawablePadding(dp(10));
        add.setGravity(Gravity.CENTER_VERTICAL);add.setPadding(dp(18),0,dp(22),0);add.setBackground(shape(fab,18));add.setElevation(dp(3));add.setContentDescription("New note");
        add.setOnClickListener(v->noteEditor(key,who,null,()->notesList(key,who,back)));
        FrameLayout.LayoutParams fl=new FrameLayout.LayoutParams(-2,dp(56),Gravity.BOTTOM|Gravity.END);fl.setMargins(0,0,dp(16),dp(16));frame.addView(add,fl);
        keyboardDown();}
    /** A screen whose first box is for searching opens with the keyboard down, not focused on the box (even coming back from typing a note). */
    private void keyboardDown(){root.setFocusableInTouchMode(true);root.requestFocus();View r=root;r.post(()->hideKeyboard(r));}
    /** "#gift" typed in the search (tags with spaces are typed with _ for them). */
    private static boolean hasTagWord(String query,String tag){for(String w:query.trim().split("\\s+"))if(w.startsWith("#")&&Notes.tag(w).replace('_',' ').equalsIgnoreCase(tag))return true;return false;}
    private static String withoutTagWord(String query,String tag){StringBuilder b=new StringBuilder();for(String w:query.trim().split("\\s+")){if(w.isEmpty()||(w.startsWith("#")&&Notes.tag(w).replace('_',' ').equalsIgnoreCase(tag)))continue;b.append(w).append(' ');}return b.toString().trim();}
    private void renderNoteCards(LinearLayout list,List<Notes.Note> all,String key,String who){list.removeAllViews();
        String q=notesQuery.trim().replace('_',' ');List<Notes.Note> shown=new ArrayList<>();for(Notes.Note n:all)if(Notes.matches(n,q))shown.add(n);
        if(shown.isEmpty()){TextView empty=text(all.isEmpty()?"No notes yet. Tap New note for gift ideas, birthdays, things to follow up on.\n\nYou can also hold a message or photo in the chat and save it as a note.":"No notes match.",15,muted,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(40),dp(24),dp(24));list.addView(empty);return;}
        for(Notes.Note n:shown){View card=noteCard(n,key,who,()->notesList(key,who,notesBack));LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,-2);cl.setMargins(dp(4),0,dp(4),dp(10));list.addView(card,cl);}}
    /** A note in the list: pin, date, the start of its words, its tags, and a picture or file count. */
    private View noteCard(Notes.Note n,String key,String who,Runnable reopen){LinearLayout view=row();view.setGravity(Gravity.TOP);view.setPadding(dp(16),dp(12),dp(12),dp(12));view.setBackground(shape(pageCard(),18));pressDim(view);view.setTag("note:"+n.id);
        LinearLayout texts=vertical();LinearLayout top=row();
        if(n.pinned){ImageView pin=new ImageView(this);pin.setImageResource(R.drawable.ic_pin);pin.setImageTintList(ColorStateList.valueOf(muted));LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(dp(14),dp(14));pl.setMarginEnd(dp(6));top.addView(pin,pl);}
        top.addView(text(noteDate(n.created)+(n.attachments.isEmpty()?"":"  ·  "+n.attachments.size()+(n.attachments.size()==1?" attachment":" attachments")),12,muted,false));texts.addView(top);
        String words=n.text.trim();if(!words.isEmpty()){TextView body=text(words,16,ink,false);body.setMaxLines(4);body.setEllipsize(TextUtils.TruncateAt.END);body.setPadding(0,dp(4),0,0);texts.addView(body);}
        if(!n.tags.isEmpty()){TextView tags=text("#"+String.join("  #",n.tags),13,BLUE,false);tags.setPadding(0,dp(6),0,0);texts.addView(tags);}
        view.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        Notes.Attachment first=null;for(Notes.Attachment a:n.attachments)if(a.image()||a.video()){first=a;break;}
        if(first!=null){File f=new Notes(this).file(n,first);if(f!=null&&f.isFile()){ImageView thumb=new ImageView(this);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);thumb.setClipToOutline(true);thumb.setBackground(shape(card,12));
            LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(dp(56),dp(56));tl.setMarginStart(dp(10));view.addView(thumb,tl);noteThumb(thumb,f,first.video(),dp(56));}}
        view.setContentDescription((n.pinned?"Pinned note, ":"Note, ")+noteDate(n.created)+": "+words+(n.tags.isEmpty()?"":", tagged "+String.join(", ",n.tags)));
        view.setOnClickListener(v->noteEditor(key,who,n,reopen));
        view.setOnLongClickListener(v->{menuSheet(words.isEmpty()?"Note":words,java.util.Arrays.asList(n.pinned?"Unpin":"Pin to top","Delete note…"),java.util.Arrays.<Runnable>asList(
            ()->{Notes.Note p=n.copy();p.pinned=!p.pinned;new Notes(this).save(key,who,p);reopen.run();},()->deleteNote(key,n.id,reopen)));return true;});
        return view;}
    private void deleteNote(String key,String id,Runnable after){new AlertDialog.Builder(this).setMessage("Delete this note? Its photos and files go with it.").setNegativeButton("Cancel",null)
        .setPositiveButton("Delete",(d,w)->{new Notes(this).delete(key,id);after.run();}).show();}
    /** A small square picture of a note's photo or video, made off the main thread. */
    private void noteThumb(ImageView view,File file,boolean video,int px){String k=file.getPath()+"|"+file.lastModified()+"|"+px;Bitmap hit=noteThumbs.get(k);view.setTag(k);if(hit!=null){view.setImageBitmap(hit);return;}
        decoder.execute(()->{Bitmap b=null;try{if(video)b=android.media.ThumbnailUtils.createVideoThumbnail(file,new android.util.Size(px,px),null);
                else b=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file),(dec,info,src)->{int w=info.getSize().getWidth(),h=info.getSize().getHeight(),s=1;
                    while(w/(s*2)>=px&&h/(s*2)>=px)s*=2;dec.setTargetSampleSize(s);dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);});}catch(Exception ignored){}
            if(b==null)return;Bitmap square=Avatars.scaleSquare(b,px);noteThumbs.put(k,square);ui.post(()->{if(k.equals(view.getTag()))view.setImageBitmap(square);});});}
    private void leaveNotes(){Runnable back=notesBack;notesBack=null;notesKey="";notesQuery="";if(back!=null)back.run();else inbox();}
    private TextView noteStatus;
    /**
     * One note, laid out like a page: who it's about and when at the top, its tags, the words,
     * where it came from and its photos or files on a raised card, and a toolbar along the bottom
     * (above the keyboard) to tag it or attach a photo or file. Saved as you go. n is null for a
     * new note, which is only kept once it has something in it. back is where the back arrow goes.
     */
    private void noteEditor(String key,String who,Notes.Note n,Runnable back){long now=System.currentTimeMillis();Notes.Note note=n==null?new Notes.Note(Notes.newId(now),"",now):n.copy();
        base("note");editing=note;editingKey=key;editingWho=who;noteBack=back;editingSnapshot=n==null?noteState(new Notes.Note(note.id,"",now)):noteState(note);
        // Who it's about, and when it was written.
        LinearLayout bar=row();bar.addView(icon(R.drawable.ic_back,ink,"Back",this::leaveNote));
        LinearLayout titles=vertical();titles.setPadding(dp(8),0,dp(8),0);TextView title=text(who,20,ink,true);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);titles.addView(title);
        String written=android.text.format.DateUtils.formatDateTime(this,note.created,android.text.format.DateUtils.FORMAT_SHOW_DATE|android.text.format.DateUtils.FORMAT_SHOW_TIME|android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
        TextView when=text(n==null?"New note":"Note · "+written,12,muted,false);when.setPadding(0,dp(1),0,0);titles.addView(when);bar.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        notePin=icon(R.drawable.ic_pin,note.pinned?BLUE:muted,note.pinned?"Unpin note":"Pin note",this::toggleNotePin);bar.addView(notePin);
        ImageView more=icon(R.drawable.ic_more,ink,"More options",null);pressable(more);more.setOnClickListener(v->menuSheet("Note",java.util.Arrays.asList("Delete note…"),java.util.Arrays.<Runnable>asList(()->{saveNote();Notes.Note e=editing;
            if(e!=null)deleteNote(key,e.id,()->{editing=null;Runnable b=noteBack;if(b!=null)b.run();else inbox();});})));bar.addView(more);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(60)));
        // The page: tags, words, where it came from, photos and files.
        LinearLayout page=vertical();page.setPadding(dp(18),dp(14),dp(18),dp(16));page.setBackground(shape(pageCard(),24));page.setElevation(dp(2));
        noteTags=row();HorizontalScrollView ts=new HorizontalScrollView(this);ts.setHorizontalScrollBarEnabled(false);ts.addView(noteTags);ts.setPadding(0,0,0,dp(6));page.addView(ts);
        EditText field=new EditText(this);field.setBackground(null);field.setPadding(0,dp(4),0,dp(4));field.setHint("Start writing…");field.setHintTextColor(muted);field.setTextColor(ink);
        field.setTextSize(18);field.setLineSpacing(0,1.2f);field.setGravity(Gravity.TOP|Gravity.START);field.setMinHeight(dp(220));field.setContentDescription("Note");
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        field.setText(note.text);field.setSelection(field.length());page.addView(field,new LinearLayout.LayoutParams(-1,-2));
        if(!note.source.isEmpty()){LinearLayout quote=row();quote.setPadding(0,dp(10),0,dp(2));View rule=new View(this);rule.setBackground(shape(BLUE,2));quote.addView(rule,new LinearLayout.LayoutParams(dp(3),dp(18)));
            TextView from=text("From "+note.source,13,muted,false);from.setPadding(dp(10),0,0,0);quote.addView(from,new LinearLayout.LayoutParams(0,-2,1));page.addView(quote);}
        noteFiles=row();HorizontalScrollView fs=new HorizontalScrollView(this);fs.setHorizontalScrollBarEnabled(false);fs.addView(noteFiles);fs.setPadding(0,dp(12),0,0);page.addView(fs);
        // Tapping the empty part of the page puts the cursor at the end of the words.
        page.setOnClickListener(v->{field.requestFocus();field.setSelection(field.length());getSystemService(InputMethodManager.class).showSoftInput(field,0);});
        LinearLayout holder=vertical();holder.setPadding(dp(12),dp(4),dp(12),dp(8));holder.addView(page,new LinearLayout.LayoutParams(-1,-2));
        TextView privacy=text("Only on this phone, never sent.",12,muted,false);privacy.setGravity(Gravity.CENTER);privacy.setPadding(0,dp(12),0,dp(4));holder.addView(privacy);
        ScrollView sc=new ScrollView(this);sc.setVerticalScrollBarEnabled(false);sc.addView(holder);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        // The toolbar: tag, photo, file, and whether it's saved.
        LinearLayout tools=row();tools.setGravity(Gravity.CENTER_VERTICAL);tools.setPadding(dp(6),0,dp(16),0);tools.setBackground(shape(pageCard(),28));tools.setElevation(dp(3));
        tools.addView(noteTool(R.drawable.ic_tag,"Tag",this::askNoteTags));tools.addView(noteTool(R.drawable.ic_gallery,"Photo",this::pickNotePhotos));tools.addView(noteTool(R.drawable.ic_file,"File",this::pickNoteFiles));
        noteStatus=text(n==null?"New":note.updated-note.created>60_000?"Edited "+android.text.format.DateUtils.getRelativeTimeSpanString(note.updated,now,android.text.format.DateUtils.MINUTE_IN_MILLIS):"Saved",12,muted,false);
        noteStatus.setGravity(Gravity.END);noteStatus.setSingleLine(true);tools.addView(noteStatus,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(-1,dp(56));tl.setMargins(dp(12),dp(2),dp(12),dp(10));root.addView(tools,tl);
        field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}
            public void afterTextChanged(Editable e){ui.removeCallbacks(noteSaver);ui.postDelayed(noteSaver,600);}});
        noteField=field;renderNoteTags();renderNoteFiles();
        if(note.text.trim().isEmpty()&&note.attachments.isEmpty()){field.requestFocus();field.post(()->getSystemService(InputMethodManager.class).showSoftInput(field,0));}}
    /** A toolbar button in a note: an icon and its name. */
    private View noteTool(int icon,String label,Runnable action){LinearLayout b=row();b.setGravity(Gravity.CENTER_VERTICAL);b.setPadding(dp(10),dp(8),dp(12),dp(8));pressDim(b);b.setContentDescription(label);
        ImageView ic=new ImageView(this);ic.setImageResource(icon);ic.setImageTintList(ColorStateList.valueOf(ink));b.addView(ic,new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView name=text(label,14,ink,true);name.setPadding(dp(6),0,0,0);b.addView(name);b.setOnClickListener(v->action.run());return b;}
    private void pickNotePhotos(){Intent pick=new Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,Math.min(10,MediaStore.getPickImagesMaxLimit()));
        try{startActivityForResult(pick,PICK_NOTE_PHOTOS);}catch(Exception e){toast("No photo picker on this phone.");}}
    private void pickNoteFiles(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),PICK_NOTE_FILES);}catch(Exception e){toast("No file picker is available.");}}
    /** What a note holds, to tell whether it changed (and only then call it edited). */
    private static String noteState(Notes.Note n){StringBuilder b=new StringBuilder(n.text).append('\u0000').append(n.pinned).append('\u0000').append(String.join("\u0001",n.tags)).append('\u0000');
        for(Notes.Attachment a:n.attachments)b.append(a.file).append('\u0001');return b.toString();}
    private void saveNote(){ui.removeCallbacks(noteSaver);if(editing==null||editingKey.isEmpty())return;if(noteField!=null)editing.text=noteField.getText().toString();
        String state=noteState(editing);if(state.equals(editingSnapshot))return;editing.updated=System.currentTimeMillis();new Notes(this).save(editingKey,editingWho,editing.copy());editingSnapshot=state;
        if(noteStatus!=null)noteStatus.setText("Saved");}
    private void leaveNote(){saveNote();if(noteField!=null)hideKeyboard(noteField);editing=null;Runnable back=noteBack;noteBack=null;if(back!=null)back.run();else inbox();}
    private void toggleNotePin(){if(editing==null)return;editing.pinned=!editing.pinned;saveNote();if(notePin!=null){notePin.setImageTintList(ColorStateList.valueOf(editing.pinned?BLUE:muted));notePin.setContentDescription(editing.pinned?"Unpin note":"Pin note");}
        toast(editing.pinned?"Pinned to the top of the notes.":"Unpinned.");}
    /** The note's tags at the top of its page (tap one to take it off); hidden while it has none. */
    private void renderNoteTags(){if(noteTags==null||editing==null)return;noteTags.removeAllViews();((View)noteTags.getParent()).setVisibility(editing.tags.isEmpty()?View.GONE:View.VISIBLE);
        for(String t:editing.tags){TextView chip=tagChip("#"+t+"  ✕",true);chip.setContentDescription("Remove tag "+t);chip.setOnClickListener(v->{editing.tags.removeIf(x->x.equals(t));saveNote();renderNoteTags();});
            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-2,-2);cl.setMarginEnd(dp(8));noteTags.addView(chip,cl);}}
    /** Adds tags: type them (commas between several), or tap ones you've used before. */
    private void askNoteTags(){if(editing==null)return;LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),0);
        EditText in=input("gift ideas, birthday");in.setSingleLine(true);in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);box.addView(in,new LinearLayout.LayoutParams(-1,-2));
        List<String> used=new ArrayList<>();for(String t:new Notes(this).allTags())if(!Notes.hasTag(editing,t))used.add(t);
        AlertDialog[] d=new AlertDialog[1];
        if(!used.isEmpty()){gap(box,10);box.addView(text("Tags you've used",13,muted,false));gap(box,6);LinearLayout wrap=vertical();LinearLayout line=null;int across=0;
            for(String t:used.subList(0,Math.min(24,used.size()))){if(line==null||across>=3){line=row();wrap.addView(line);gap(wrap,6);across=0;}
                TextView chip=tagChip("#"+t,false);chip.setOnClickListener(v->{addNoteTags(t);if(d[0]!=null)d[0].dismiss();});LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-2,-2);cl.setMarginEnd(dp(8));line.addView(chip,cl);across++;}
            ScrollView sc=new ScrollView(this);sc.addView(wrap);box.addView(sc,new LinearLayout.LayoutParams(-1,-2));}
        d[0]=new AlertDialog.Builder(this).setTitle("Add tags").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Add",(x,w)->addNoteTags(in.getText().toString())).create();
        in.setOnEditorActionListener((v,a,e)->{addNoteTags(in.getText().toString());d[0].dismiss();return true;});
        d[0].setOnShowListener(x->{in.requestFocus();Window w=d[0].getWindow();if(w!=null)w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);});d[0].show();}
    private void addNoteTags(String typed){if(editing==null)return;List<String> tags=editing.tags;for(String t:Notes.splitTags(typed))tags=Notes.withTag(tags,t);editing.tags=tags;saveNote();renderNoteTags();}
    /** The note's photos and files at the bottom of its page (tap to open, hold to share or remove); hidden while it has none. */
    private void renderNoteFiles(){if(noteFiles==null||editing==null)return;noteFiles.removeAllViews();Notes kept=new Notes(this);Notes.Note note=editing;
        ((View)noteFiles.getParent()).setVisibility(note.attachments.isEmpty()?View.GONE:View.VISIBLE);
        for(Notes.Attachment a:note.attachments){File f=kept.file(note,a);FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(surface,16));tile.setClipToOutline(true);pressDim(tile);
            if((a.image()||a.video())&&f!=null&&f.isFile()){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(img,new FrameLayout.LayoutParams(-1,-1));noteThumb(img,f,a.video(),dp(96));
                if(a.video()){ImageView play=new ImageView(this);play.setImageResource(R.drawable.ic_play);play.setPadding(dp(8),dp(8),dp(7),dp(8));play.setBackground(oval(0x8C000000));tile.addView(play,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER));}}
            else{LinearLayout inner=vertical();inner.setGravity(Gravity.CENTER);inner.setPadding(dp(8),dp(8),dp(8),dp(8));ImageView ic=new ImageView(this);ic.setImageResource(R.drawable.ic_file);ic.setImageTintList(ColorStateList.valueOf(muted));
                LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(dp(26),dp(26));il.gravity=Gravity.CENTER_HORIZONTAL;inner.addView(ic,il);
                TextView name=text(a.name,11,ink,false);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.MIDDLE);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(6),0,0);inner.addView(name,new LinearLayout.LayoutParams(-1,-2));
                tile.addView(inner,new FrameLayout.LayoutParams(-1,-1));}
            tile.setContentDescription(a.name);tile.setOnClickListener(v->openNoteFile(f,a));
            tile.setOnLongClickListener(v->{menuSheet(a.name,java.util.Arrays.asList("Share","Remove from note"),java.util.Arrays.<Runnable>asList(()->shareNoteFile(f,a),()->{note.attachments.remove(a);saveNote();if(f!=null)f.delete();renderNoteFiles();}));return true;});
            LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(dp(96),dp(96));tl.setMarginEnd(dp(8));noteFiles.addView(tile,tl);}}
    private void openNoteFile(File f,Notes.Attachment a){if(f==null||!f.isFile()){toast("This file is missing from the note.");return;}
        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(MediaProvider.noteUri(f,a.name),a.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(bubble())view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{startActivity(view);}catch(Exception e){toast("No app on this phone can open this kind of file. Hold it to share it instead.");}}
    private void shareNoteFile(File f,Notes.Attachment a){if(f==null||!f.isFile()){toast("This file is missing from the note.");return;}Uri uri=MediaProvider.noteUri(f,a.name);
        Intent send=new Intent(Intent.ACTION_SEND).setType(a.mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri(a.name,uri));startActivity(Intent.createChooser(send,"Share"));}
    /** Keeps picked photos or files with the note being edited (copied into the note's own folder). */
    private void addNoteFiles(List<Uri> uris){Notes.Note note=editing;String key=editingKey;if(note==null)return;File dir=Notes.dir(getFilesDir(),note.id);if(dir==null)return;
        decoder.execute(()->{List<Notes.Attachment> added=new ArrayList<>();int failed=0;dir.mkdirs();
            for(Uri uri:uris){String name="file";try(android.database.Cursor q=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(q!=null&&q.moveToFirst()&&!q.isNull(0))name=q.getString(0);}catch(Exception ignored){}
                if("file".equals(name)&&uri.getLastPathSegment()!=null)name=uri.getLastPathSegment();
                String mime=getContentResolver().getType(uri);if(mime==null){int dot=name.lastIndexOf('.');mime=dot<0?null:android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));}
                File to=noteFileFor(dir,name,note.attachments.size()+added.size()+1);
                try(java.io.InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException("unreadable");java.nio.file.Files.copy(in,to.toPath());added.add(new Notes.Attachment(to.getName(),name,mime));}
                catch(Exception e){to.delete();failed++;}}
            int lost=failed;ui.post(()->{if(editing==note&&key.equals(editingKey)){note.attachments.addAll(added);saveNote();renderNoteFiles();}
                else{Notes.Note saved=null;for(Notes.Note x:new Notes(this).list(key))if(x.id.equals(note.id))saved=x;if(saved!=null){saved.attachments.addAll(added);new Notes(this).save(key,editingWho,saved);}}
                if(lost>0)toast(lost==1?"One file couldn't be added.":lost+" files couldn't be added.");});});}
    private static File noteFileFor(File dir,String name,int number){File f;int n=number;do{f=new File(dir,Notes.fileName(name,n++));}while(f.exists());return f;}
    /**
     * Who a message goes to in notes: {key, whose notes, how a note says where it came from}. The
     * person's in a one-to-one chat; in a group, the sender's (or the group's, for your own).
     */
    private String[] noteTarget(JSONObject m,JSONObject c){boolean mine=PairedProtocol.outgoing(m,c);JSONObject other=PairedProtocol.other(c);String date=noteDate(PairedProtocol.time(m));
        if(other!=null){String who=PairedProtocol.title(c);return new String[]{Notes.personKey(PairedProtocol.number(other)),who,Notes.source(mine?"Your message":who+"'s message",date)};}
        String group=PairedProtocol.title(c);
        if(mine)return new String[]{Notes.groupKey(c.optString("conversationID")),group,Notes.source("Your message",date)};
        JSONObject sender=PairedProtocol.participant(c,m.optString("participantID"));String who=senderName(m,c);
        return new String[]{Notes.personKey(sender==null?"":PairedProtocol.number(sender)),who,Notes.source(who+"'s message in "+group,date)};}
    /** "photo", "photos", "video", "file" or "message": what holding a message offers to save. */
    private static String noteWhat(JSONObject m){List<JSONObject> media=PairedProtocol.media(m);if(media.isEmpty())return "message";String k=PairedProtocol.mediaKind(media.get(0));
        String one=k.equals("image")?"photo":k.equals("video")?"video":"file";return media.size()>1?one+"s":one;}
    /** Saves a message (its words and any photos or files) as a new note, or into one you pick, then opens that note. */
    private void messageToNote(JSONObject m,JSONObject c,boolean pick){String[] to=noteTarget(m,c);if(to[0].isEmpty())return;
        if(!pick){saveMessageInto(m,c,to,null);return;}
        List<Notes.Note> notes=new Notes(this).list(to[0]);if(notes.isEmpty()){saveMessageInto(m,c,to,null);return;}
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(Notes.Note n:notes.subList(0,Math.min(15,notes.size()))){String first=n.text.trim().split("\n")[0];if(first.length()>40)first=first.substring(0,40).trim()+"…";
            if(first.isEmpty())first=n.tags.isEmpty()?n.attachments.size()+" attachments":"#"+String.join(" #",n.tags);
            labels.add((n.pinned?"📌 ":"")+noteDate(n.created)+" · "+first);actions.add(()->saveMessageInto(m,c,to,n));}
        menuSheet("Add to which note about "+to[1]+"?",labels,actions);}
    private void saveMessageInto(JSONObject m,JSONObject c,String[] to,Notes.Note into){String typed=PairedProtocol.text(m),words=ChatText.replyFallbackBody(typed)!=null?ChatText.replyFallbackBody(typed):typed;
        List<JSONObject> media=PairedProtocol.media(m);String chat=chatId;
        noteDownloads(media,0,new ArrayList<>(),files->{long now=System.currentTimeMillis();Notes.Note n=into==null?new Notes.Note(Notes.newId(now),words.trim(),now):into.copy();
            if(into==null)n.source=to[2];else if(!words.trim().isEmpty())n.text=Notes.appended(n.text,words);
            File dir=Notes.dir(getFilesDir(),n.id);
            decoder.execute(()->{List<Notes.Attachment> added=new ArrayList<>();if(dir!=null){dir.mkdirs();
                    for(int i=0;i<files.size();i++){JSONObject media0=media.get(i);File from=files.get(i);String name=displayName(media0);File copy=noteFileFor(dir,name,n.attachments.size()+added.size()+1);
                        try{try{android.system.Os.link(from.getPath(),copy.getPath());}catch(Exception linkFailed){java.nio.file.Files.copy(from.toPath(),copy.toPath());}added.add(new Notes.Attachment(copy.getName(),name,MediaCache.mime(media0)));}catch(Exception e){copy.delete();}}}
                ui.post(()->{n.attachments.addAll(added);n.updated=now;new Notes(this).save(to[0],to[1],n);
                    toast(into==null?"Saved as a note about "+to[1]+".":"Added to the note.");noteEditor(to[0],to[1],n,()->thread(chat));});});});}
    /** Downloads (or reuses) each of a message's attachments in turn, then hands over the files. */
    private void noteDownloads(List<JSONObject> media,int i,List<File> got,java.util.function.Consumer<List<File>> done){if(i>=media.size()){done.accept(got);return;}
        withFile(media.get(i),"Downloading…",file->{got.add(file);noteDownloads(media,i+1,got,done);});}
    /** Details' notes card: how many, and the top note (pinned, or newest). Tap to see them all. */
    private void notesCard(LinearLayout content,String key,String who,Runnable back){if(key.isEmpty())return;List<Notes.Note> all=new Notes(this).list(key);
        content.addView(text("Notes",14,muted,true));gap(content,6);
        LinearLayout box=vertical();box.setPadding(dp(16),dp(12),dp(16),dp(12));box.setBackground(shape(pageCard(),18));pressDim(box);
        if(all.isEmpty()){box.addView(text("Add gift ideas, birthdays, things to follow up on. Only on this phone, never sent.",15,muted,false));box.setContentDescription("Notes about "+who);}
        else{Notes.Note top=all.get(0);box.addView(text(all.size()+(all.size()==1?" note":" notes")+"  ·  "+(top.pinned?"pinned":"newest")+" from "+noteDate(top.created),12,muted,false));
            String words=top.text.trim();if(!words.isEmpty()){TextView body=text(words,15,ink,false);body.setMaxLines(4);body.setEllipsize(TextUtils.TruncateAt.END);body.setPadding(0,dp(4),0,0);box.addView(body);}
            if(!top.tags.isEmpty()){TextView tags=text("#"+String.join("  #",top.tags),13,BLUE,false);tags.setPadding(0,dp(4),0,0);box.addView(tags);}
            box.setContentDescription(all.size()+" notes about "+who);}
        box.setOnClickListener(v->notesList(key,who,back));LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,-2);bl.setMargins(dp(4),0,dp(4),0);content.addView(box,bl);}
    /** Inbox search looks through your notes too (words and #tags): each matching note, newest first, with whose it is. */
    private void renderNoteHits(){if(items==null)return;SmartText.Search f=SmartText.parseSearch(query);String q=query.trim();
        if(q.length()<2||(SmartText.hasFilters(f)&&!q.startsWith("#")))return;
        List<Object[]> hits=new ArrayList<>();for(Map.Entry<String,List<Notes.Note>> e:new Notes(this).all().entrySet())for(Notes.Note n:e.getValue())if(Notes.matches(n,q.replace('_',' ')))hits.add(new Object[]{e.getKey(),n});
        if(hits.isEmpty())return;hits.sort((a,b)->Long.compare(((Notes.Note)b[1]).created,((Notes.Note)a[1]).created));
        List<JSONObject> chats=null;Map<String,JSONObject> chatFor=new HashMap<>();items.addView(sectionLabel("Notes"));
        for(Object[] h:hits.subList(0,Math.min(20,hits.size()))){String key=(String)h[0];Notes.Note n=(Notes.Note)h[1];
            if(!chatFor.containsKey(key)){JSONObject chat=null;if(key.startsWith("g:"))chat=store.chat(key.substring(2));
                else{if(chats==null)chats=allChats();for(JSONObject c:chats){JSONObject o=PairedProtocol.other(c);if(o!=null&&key.equals(Notes.personKey(PairedProtocol.number(o)))){chat=c;break;}}}
                chatFor.put(key,chat);}
            items.addView(noteResult(chatFor.get(key),key,n,q));}}
    private View noteResult(JSONObject chat,String key,Notes.Note n,String q){String saved=new Notes(this).name(key),title=chat!=null?PairedProtocol.title(chat):saved.isEmpty()?"Notes":saved;
        LinearLayout r=row();r.setPadding(dp(8),dp(10),dp(8),dp(10));pressable(r);r.addView(chat!=null?avatar(chat,44):personAvatar(null,title,44));
        LinearLayout texts=vertical();texts.setPadding(dp(14),0,dp(8),0);texts.addView(text(title+"  ·  "+noteDate(n.created),15,ink,false));
        String line=Notes.snippet(n.text,q);if(line.isEmpty())line=n.attachments.isEmpty()?"":n.attachments.size()+(n.attachments.size()==1?" attachment":" attachments");
        if(!line.isEmpty()){TextView l=text(line,14,muted,false);l.setMaxLines(2);l.setEllipsize(TextUtils.TruncateAt.END);texts.addView(l);}
        if(!n.tags.isEmpty()){TextView tags=text("#"+String.join("  #",n.tags),13,BLUE,false);texts.addView(tags);}
        r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));r.setContentDescription("Note about "+title+": "+line);
        r.setOnClickListener(v->noteEditor(key,title,n,this::inbox));return r;}
    private void memberActions(JSONObject p){String number=PairedProtocol.number(p);if(number.isEmpty())return;String name=PairedProtocol.name(p);
        Runnable back=screen.equals("details")?this::details:(Runnable)()->thread(chatId);
        menuSheet(name,java.util.Arrays.asList("Message","Call","Copy number","Set a nickname…","Notes about "+name),java.util.Arrays.<Runnable>asList(()->startChat(number),
            ()->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));}catch(Exception e){toast("No phone app is available.");}},
            ()->getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Number",number)),()->setNickname(p),()->notesList(Notes.personKey(number),name,back)));}
    // ---- Attachments ----
    private static final int PICK_MEDIA=41,PICK_FILES=42;
    private static final long MAX_ATTACHMENT=100L<<20,MAX_VIDEO=4L<<30;
    private final java.util.concurrent.ExecutorService decoder=java.util.concurrent.Executors.newFixedThreadPool(2);
    private final List<Outgoing> outgoing=new ArrayList<>();private String outgoingChat="";
    private LinearLayout attachStrip;private HorizontalScrollView attachScroll;
    /** A file picked to send, copied into this app's cache first. */
    private static final class Outgoing{final File file;final String name,mime;boolean sending,small;volatile PhotoPrep.Ready prepared;volatile String variant="",preparationError="";volatile int preparationToken;volatile boolean preparing;
        Outgoing(File f,String n,String m){file=f;name=n;mime=m;}}
    private static final java.util.concurrent.ExecutorService photoPreparation=java.util.concurrent.Executors.newSingleThreadExecutor();
    private String photoQuality(){return PhotoQuality.normalize(getSharedPreferences("paired",MODE_PRIVATE).getString("photo_quality",PhotoQuality.STANDARD));}
    /** Videos have their own Fast / Standard / Full choice (see VideoPlan). */
    private String videoQuality(){return PhotoQuality.normalize(getSharedPreferences("paired",MODE_PRIVATE).getString("video_quality",PhotoQuality.STANDARD));}
    private void chooseVideoQuality(){String[] values={PhotoQuality.FAST,PhotoQuality.STANDARD,PhotoQuality.FULL};String selected=videoQuality();int current=selected.equals(PhotoQuality.FAST)?0:selected.equals(PhotoQuality.FULL)?2:1;
        String[] labels=new String[3];for(int i=0;i<3;i++)labels[i]=PhotoQuality.label(values[i])+" • "+VideoPlan.describe(values[i]);
        new AlertDialog.Builder(this).setTitle("Video quality")
            .setSingleChoiceItems(labels,current,(d,which)->{getSharedPreferences("paired",MODE_PRIVATE).edit().putString("video_quality",values[which]).apply();d.dismiss();if(screen.equals("settings"))settings();else renderAttachments();})
            .setNeutralButton("About quality",(d,w)->new AlertDialog.Builder(this).setMessage("Videos are made smaller on this phone before sending, so they go faster. Fast keeps them small, Standard is sharp at a practical size, and Full sends your original whenever it's under 100 MB, the most RCS carries. Picture messages (MMS) always squeeze videos under 1 MB, whatever you choose.").setPositiveButton("OK",null).show())
            .setNegativeButton("Close",null).show();}
    private void prepareWhileTyping(Outgoing o){if(o.sending||!o.mime.startsWith("image/"))return;JSONObject c=store.chat(chatId);boolean mms=c==null||!PairedProtocol.rcs(c);String quality=photoQuality(),variant=quality+"|"+mms;
        if(variant.equals(o.variant))return;o.variant=variant;int token=++o.preparationToken;o.preparing=true;o.preparationError="";
        photoPreparation.execute(()->{PhotoPrep.Ready ready=PhotoPrep.prepare(o.file,o.name,o.mime,mms,quality);
            if(token!=o.preparationToken){if(!ready.file.equals(o.file))ready.file.delete();return;}
            PhotoPrep.Ready old=o.prepared;o.prepared=ready;o.preparing=false;o.preparationError=ready.error;
            if(old!=null&&!old.file.equals(o.file)&&!old.file.equals(ready.file))old.file.delete();
            ui.post(()->{if(outgoing.contains(o)&&screen.equals("chat"))renderAttachments();});});}
    private void choosePhotoQuality(){String[] values={PhotoQuality.FAST,PhotoQuality.STANDARD,PhotoQuality.FULL};String selected=photoQuality();int current=selected.equals(PhotoQuality.FAST)?0:selected.equals(PhotoQuality.FULL)?2:1;
        new AlertDialog.Builder(this).setTitle("Photo quality")
            .setSingleChoiceItems(new String[]{"Fast • smaller uploads","Standard • sharp, practical size","Full quality • larger files"},current,(d,which)->{getSharedPreferences("paired",MODE_PRIVATE).edit().putString("photo_quality",values[which]).apply();
                for(Outgoing o:outgoing)if(!o.sending){o.variant="";prepareWhileTyping(o);}d.dismiss();if(screen.equals("settings"))settings();else renderAttachments();}).setNeutralButton("About quality",(d,w)->new AlertDialog.Builder(this).setMessage("Fast uses smaller pictures. Standard keeps photos sharp at a practical size. Full quality keeps the original when supported; format conversion may still be needed. MMS always uses its smaller size limit.").setPositiveButton("OK",null).show()).setNegativeButton("Close",null).show();}
    private void discardPicked(Outgoing o){o.preparationToken++;o.file.delete();PhotoPrep.Ready ready=o.prepared;if(ready!=null&&!ready.file.equals(o.file))ready.file.delete();}
    /** A photo or video shown in the chat at its own shape, or a file/audio card. Tap to open, long-press for options. */
    private View mediaView(JSONObject m,JSONObject media,boolean outgoing,ChatTheme theme){
        String kind=PairedProtocol.mediaKind(media);LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);
        if(kind.equals("image")||kind.equals("video")){
            int width=Math.round(getResources().getDisplayMetrics().widthPixels*0.66f);JSONObject dims=media.optJSONObject("dimensions");
            long w=dims==null?0:dims.optLong("width"),h=dims==null?0:dims.optLong("height");float ratio=w>0&&h>0?(float)h/w:0.75f;ratio=Math.max(0.45f,Math.min(1.5f,ratio));
            FrameLayout box=new FrameLayout(this);box.setBackground(shape(surface,20));
            box.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(20));}});box.setClipToOutline(true);
            ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);box.addView(img,new FrameLayout.LayoutParams(-1,-1));
            View[] badge={null};
            if(kind.equals("video")){ImageView play=new ImageView(this);play.setImageResource(R.drawable.ic_play);play.setPadding(dp(15),dp(15),dp(13),dp(15));play.setBackground(oval(0x8C000000));play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                box.addView(play,new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER));badge[0]=play;}
            TextView state=text("",13,Color.WHITE,false);state.setPadding(dp(12),dp(6),dp(12),dp(6));state.setBackground(shape(0x99000000,14));state.setVisibility(View.GONE);
            FrameLayout.LayoutParams stl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);stl.bottomMargin=dp(12);box.addView(state,stl);img.setTag(R.id.tile_state,state);
            box.setContentDescription(kind.equals("video")?"Video. Tap to play here; full screen button while playing.":"Photo. Tap to view.");
            box.setOnClickListener(v->{String problem=mediaErrors.get(mediaKey(media));if(problem!=null&&!MediaCache.file(this,media,false).isFile())mediaProblem(media,problem,img,kind.equals("video"),box);else if(kind.equals("video"))playInline(media,box,img,badge[0]);else openMedia(media);});box.setOnLongClickListener(v->{messageSheet(m);return true;});
            int height=Math.round(width*ratio);
            // Genmoji and stickers (see-through pictures) sit on the chat like a big emoji, not in a photo frame.
            if(kind.equals("image")&&MediaCache.isSticker(MediaCache.file(this,media,false))){int[] sz=stickerSize(w,h);width=sz[0];height=sz[1];stickerStyle(box,img);}
            line.addView(box,new LinearLayout.LayoutParams(width,height));loadPreview(media,img,kind.equals("video"),width,height);
            // A video playing here keeps playing when the chat redraws: it moves into the new tile.
            if(kind.equals("video")&&inline!=null&&screen.equals("chat")&&inline.key.equals(mediaKey(media))){inline.attach(box,badge[0]);inlineSeen=true;}
        } else if(PairedProtocol.contactCard(media)){line.addView(contactCardView(m,media,outgoing,theme));
        } else if(kind.equals("audio")){
            line.addView(voiceCard(m,media,outgoing,theme),new LinearLayout.LayoutParams(-2,-2));
            LinearLayout col=vertical();col.addView(line,new LinearLayout.LayoutParams(-1,-2));col.addView(transcriptView(m,media,outgoing,theme),new LinearLayout.LayoutParams(-1,-2));return col;
        } else {
            LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(10),dp(10),dp(18),dp(10));card.setBackground(shape(outgoing?theme.bubble(dark):surface,22));int fg=outgoing?theme.onBubble(dark):ink;
            card.addView(roundIcon(R.drawable.ic_file,(fg&0x00ffffff)|0x22000000,fg,40));LinearLayout texts=vertical();texts.setPadding(dp(12),0,0,0);
            String name=PairedProtocol.string(media,"mediaName");TextView title=text(name.isEmpty()?"File":name,15,fg,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.55f));texts.addView(title);
            String size=PairedProtocol.size(media.optLong("size"));
            texts.addView(text((size.isEmpty()?"":size+" · ")+"Tap to open",13,outgoing?fg:muted,false));card.addView(texts);
            card.setContentDescription(title.getText()+". Tap to open.");card.setOnClickListener(v->openMedia(media));card.setOnLongClickListener(v->{messageSheet(m);return true;});
            line.addView(card,new LinearLayout.LayoutParams(-2,-2));
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
        // A GIF already playing from before the chat redrew is put straight back: nothing else to load.
        if(!video&&MediaCache.maybeAnimated(media)&&playAnimated(media,img,width,height))return;
        boolean thumb=MediaCache.hasThumbnail(media);long size=media.optLong("size");File full=MediaCache.file(this,media,false);
        boolean stickerFile=!video&&full.isFile()&&MediaCache.isSticker(full);
        // A full copy already on the phone (a photo you sent, or one opened before) is shown as it is: it costs no download.
        boolean wantFull=stickerFile||(video?full.isFile()||MediaCache.hasFull(media)&&(!thumb||fullPhotos()&&size>0&&size<=(20L<<20)):full.isFile()||MediaCache.hasFull(media)&&(!thumb||(fullPhotos()&&size<=(25L<<20))));
        // Google sometimes sends a photo with only a preview, or nothing at all, on linked devices (end-to-end
        // encrypted chats do this). The phone sends the full photo when asked, and the message updates by itself.
        boolean ask=!video&&MediaCache.needsPhone(media)&&(fullPhotos()||!thumb)&&!full.isFile();
        // Asked several times and never sent: say so, and stop asking on its own (Retry and the gallery still ask).
        boolean missing=ask&&PairedService.missingOnPhone(this,media);
        if(missing)tileState(img,media,"Not on your phone • Tap for details",NOT_ON_PHONE);
        else if(ask)tileState(img,media,!PairedService.ready()?"Waiting for the connection…":thumb?"Loading full photo…":"Getting photo from your phone…",null);
        if(ask&&!missing&&PairedService.ready())PairedService.requestFullMedia(media,false,(v,e)->{if(!e.isEmpty())tileState(img,media,thumb?"Only a preview • Tap for details":"Couldn't get this photo • Tap for details",e);else waitForPhone(img,media,thumb);});
        if(wantFull&&full.isFile()){Bitmap b=MediaCache.peekCover(full,width,height);if(b!=null){img.setTag("full");img.setImageBitmap(b);sharp(img);tileState(img,media,null,null);return;}}
        // No need to download Google's small preview when the full picture is already here.
        if(thumb&&!stickerFile&&!(full.isFile()&&!MediaCache.file(this,media,true).isFile())){File t=MediaCache.file(this,media,true);Bitmap b=t.isFile()?MediaCache.peekCover(t,width,height):null;
            if(b!=null){img.setImageBitmap(b);if(MediaCache.tiny(t))soft(img);}
            else if(t.isFile()||PairedService.ready()||MediaCache.hasInline(media))MediaCache.fetch(this,media,true,(file,err)->{if(file==null)return;
                decoder.execute(()->{Bitmap d=MediaCache.decodeToCover(file,width,height,false);boolean small=MediaCache.tiny(file);
                    if(d!=null)img.post(()->{if(img.getTag()==null){img.setImageBitmap(d);if(small)soft(img);}});});});}
        else if(video&&!wantFull&&size>(15L<<20))return;
        if(!wantFull&&thumb)return;
        if(!full.isFile()&&!PairedService.ready())return;
        if(!full.isFile()&&!MediaCache.hasFull(media))return; // waiting for the phone (above); nothing to download yet
        MediaCache.fetch(this,media,false,(file,err)->{if(file==null){tileState(img,media,thumb?"Only a preview • Tap for details":"Couldn't load • Tap for details",err);return;}
            decoder.execute(()->{Bitmap d=MediaCache.decodeToCover(file,width,height,video);img.post(()->{if("animated".equals(img.getTag()))return;if(d!=null){img.setTag("full");img.setImageBitmap(d);sharp(img);tileState(img,media,null,null);if(!video&&MediaCache.sticker(d))becomeSticker(img,d);}
                else if(!thumb)tileState(img,media,"Can't show this photo here • Tap for details","The file downloaded but Android couldn't read it as a picture.");});});});
    }
    private static final String NOT_ON_PHONE="Your phone was asked for this photo "+PairedService.MISSING_AFTER+" times and never sent it, so it most likely doesn't have it any more. Older picture messages often don't come across to a new phone. If it doesn't open in Google Messages on your phone either, ask the sender to send it again.";
    /** How long a photo may take to arrive after the phone is asked, before the tile says it hasn't come. */
    private static final long PHONE_PATIENCE_MS=45_000;
    /**
     * After the phone is asked for a photo: if it hasn't arrived within PHONE_PATIENCE_MS, the tile
     * says so (instead of "Getting photo…" forever) and a tap asks again. A newly arrived photo
     * redraws the chat, which replaces this tile, so nothing here fires for it.
     */
    private void waitForPhone(ImageView img,JSONObject media,boolean thumb){long asked=PairedService.askedAt(media);if(asked==0)return;
        img.postDelayed(()->{if(!img.isAttachedToWindow()||"full".equals(img.getTag()))return;
            JSONObject now=store.message(media.optString("_message"));if(now!=null)for(JSONObject a:PairedProtocol.media(now))if(a.optString("_part").equals(media.optString("_part"))&&MediaCache.hasFull(a))return;
            if(PairedService.missingOnPhone(this,media)){tileState(img,media,"Not on your phone • Tap for details",NOT_ON_PHONE);return;}
            tileState(img,media,thumb?"Only a preview • Tap to ask again":"Not sent yet • Tap to ask again",
                "Your phone was asked for this photo but hasn't sent it. It may be asleep or offline, or it may not have the photo any more. Check that it opens in Google Messages on your phone.");},
            Math.max(0,asked+PHONE_PATIENCE_MS-System.currentTimeMillis()));}
    /**
     * GIFs playing in chats, by file and size. The chat redraws often (new messages, read
     * receipts), and each redraw used to show the still preview, decode the GIF again and start it
     * over, which flashed. Now a redraw puts the same playing GIF straight back.
     */
    private final android.util.LruCache<String,android.graphics.drawable.Drawable> playingGifs=new android.util.LruCache<>(16);
    private static String playingKey(File f,int width,int height){return f.getPath()+"|"+f.length()+"|"+width+"x"+height;}
    /**
     * GIFs and animated stickers play right in the chat (once their file is here; small ones download
     * by themselves). True when one already playing was put back, so nothing else needs loading.
     */
    private boolean playAnimated(JSONObject media,ImageView img,int width,int height){File full=MediaCache.file(this,media,false);
        if(full.isFile()){android.graphics.drawable.Drawable hit=playingGifs.get(playingKey(full,width,height));if(hit!=null){showAnimated(img,media,hit);return true;}}
        Runnable go=()->decoder.execute(()->{android.graphics.drawable.Drawable d=MediaCache.animated(full,width,height);if(d==null)return;
            playingGifs.put(playingKey(full,width,height),d);img.post(()->{if(img.isAttachedToWindow())showAnimated(img,media,d);});});
        if(full.isFile())go.run();
        else if(MediaCache.hasFull(media)&&PairedService.ready()&&media.optLong("size")<=(15L<<20))MediaCache.fetch(this,media,false,(f,e)->{if(f!=null)go.run();});
        return false;}
    private void showAnimated(ImageView img,JSONObject media,android.graphics.drawable.Drawable d){img.setTag("animated");img.setImageDrawable(d);sharp(img);tileState(img,media,null,null);
        android.graphics.drawable.AnimatedImageDrawable a=(android.graphics.drawable.AnimatedImageDrawable)d;if(!a.isRunning())a.start();}
    /** A shared contact shown as a card (name, number) with Save, instead of a file. */
    private View contactCardView(JSONObject m,JSONObject media,boolean outgoing,ChatTheme theme){int fg=outgoing?theme.onBubble(dark):ink;
        LinearLayout card=vertical();card.setPadding(dp(14),dp(12),dp(14),dp(6));card.setBackground(shape(outgoing?theme.bubble(dark):surface,22));card.setMinimumWidth(dp(230));
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);TextView face=text("?",18,fg,true);face.setGravity(Gravity.CENTER);face.setBackground(oval((fg&0x00ffffff)|0x26000000));
        top.addView(face,new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout texts=vertical();texts.setPadding(dp(12),0,dp(4),0);
        TextView name=text("Contact",16,fg,true);name.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.5f));TextView detail=text("Loading…",13,outgoing?fg:muted,false);
        texts.addView(name);texts.addView(detail);top.addView(texts,new LinearLayout.LayoutParams(-2,-2));card.addView(top);
        View rule=new View(this);rule.setBackgroundColor((fg&0x00ffffff)|0x22000000);LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)/2));rl.topMargin=dp(10);card.addView(rule,rl);
        TextView save=text("Save contact",15,outgoing?fg:BLUE,true);save.setGravity(Gravity.CENTER);save.setPadding(0,dp(10),0,dp(6));card.addView(save,new LinearLayout.LayoutParams(-1,-2));
        VCard[] parsed={null};
        Runnable fill=()->{VCard v=parsed[0];if(v==null||v.empty()){String file=PairedProtocol.string(media,"mediaName");name.setText(file.isEmpty()?"Contact":file);detail.setText("Tap to open");return;}
            name.setText(v.name.isEmpty()?"Contact":v.name);face.setText(PairedProtocol.initial(v.name.isEmpty()?"?":v.name));
            detail.setText(!v.phones.isEmpty()?v.phones.get(0)+(v.phones.size()>1?"  +"+(v.phones.size()-1)+" more":""):!v.emails.isEmpty()?v.emails.get(0):v.org);
            card.setContentDescription("Contact card: "+name.getText()+", "+detail.getText()+". Tap for options.");};
        java.util.function.Consumer<File> read=file->decoder.execute(()->{VCard v=readCard(file);ui.post(()->{parsed[0]=v;fill.run();});});
        File have=MediaCache.file(this,media,false);
        if(have.isFile())read.accept(have);
        else if(PairedService.ready()&&MediaCache.hasFull(media))MediaCache.fetch(this,media,false,(file,err)->{if(file!=null)read.accept(file);else{detail.setText("Tap to load");}});
        else detail.setText("Tap to load");
        card.setOnClickListener(v->{if(parsed[0]!=null&&!parsed[0].empty())contactSheet(media,parsed[0]);else withFile(media,"Getting the contact…",file->{parsed[0]=readCard(file);fill.run();if(!parsed[0].empty())contactSheet(media,parsed[0]);else openExternally(file,media);});});
        save.setOnClickListener(v->{if(parsed[0]!=null&&!parsed[0].empty())saveContact(parsed[0]);else withFile(media,"Getting the contact…",file->{parsed[0]=readCard(file);fill.run();saveContact(parsed[0]);});});
        card.setOnLongClickListener(v->{messageSheet(m);return true;});save.setOnLongClickListener(v->{messageSheet(m);return true;});
        return card;}
    private static VCard readCard(File file){try{byte[] b=java.nio.file.Files.readAllBytes(file.toPath());if(b.length>(512<<10))b=java.util.Arrays.copyOf(b,512<<10);return VCard.parse(new String(b,java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){return VCard.parse("");}}
    /** Opens the phone's "new contact" screen filled in from the card. */
    private void saveContact(VCard v){if(v==null||v.empty()){toast("This contact card is empty.");return;}
        Intent i=new Intent(android.provider.ContactsContract.Intents.Insert.ACTION).setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE);
        if(!v.name.isEmpty())i.putExtra(android.provider.ContactsContract.Intents.Insert.NAME,v.name);if(!v.org.isEmpty())i.putExtra(android.provider.ContactsContract.Intents.Insert.COMPANY,v.org);
        String[] phoneKeys={android.provider.ContactsContract.Intents.Insert.PHONE,android.provider.ContactsContract.Intents.Insert.SECONDARY_PHONE,android.provider.ContactsContract.Intents.Insert.TERTIARY_PHONE};
        for(int k=0;k<Math.min(3,v.phones.size());k++)i.putExtra(phoneKeys[k],v.phones.get(k));
        String[] mailKeys={android.provider.ContactsContract.Intents.Insert.EMAIL,android.provider.ContactsContract.Intents.Insert.SECONDARY_EMAIL,android.provider.ContactsContract.Intents.Insert.TERTIARY_EMAIL};
        for(int k=0;k<Math.min(3,v.emails.size());k++)i.putExtra(mailKeys[k],v.emails.get(k));
        try{startActivity(i);}catch(Exception e){toast("No contacts app is available.");}}
    private void contactSheet(JSONObject media,VCard v){LinearLayout content=vertical();TextView title=text(v.name.isEmpty()?"Contact":v.name,20,ink,true);title.setPadding(dp(8),dp(4),dp(8),dp(10));content.addView(title);Dialog[] d=new Dialog[1];
        sheetAction(content,"Save contact",()->{d[0].dismiss();saveContact(v);});
        for(String phone:v.phones)sheetAction(content,"Call "+phone,()->{d[0].dismiss();try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(phone))));}catch(Exception e){toast("No phone app is available.");}});
        for(String mail:v.emails)sheetAction(content,"Email "+mail,()->{d[0].dismiss();try{startActivity(new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:"+mail)));}catch(Exception e){toast("No email app is available.");}});
        sheetAction(content,"Open in another app",()->{d[0].dismiss();withFile(media,"Getting the contact…",file->openExternally(file,media));});
        d[0]=sheet(content);}
    private long unreadSince=-1;private boolean unreadJump;private View unreadDivider;private int unreadCount;
    /** "3 new messages", across the chat where the unread ones start. */
    private View newMessagesLine(int count){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(8),dp(14),dp(8),dp(10));
        View left=new View(this);left.setBackgroundColor((BLUE&0x00ffffff)|0x66000000);r.addView(left,new LinearLayout.LayoutParams(0,Math.max(1,dp(1)),1));
        TextView t=text(count==1?"1 new message":count+" new messages",12,BLUE,true);t.setPadding(dp(10),0,dp(10),0);r.addView(t);
        View right=new View(this);right.setBackgroundColor((BLUE&0x00ffffff)|0x66000000);r.addView(right,new LinearLayout.LayoutParams(0,Math.max(1,dp(1)),1));return r;}
    /** Photos and videos sent together: a grid of up to four in one rounded block, "+N" on the last when there are more. */
    private View albumView(JSONObject m,List<JSONObject> parts,boolean outgoing){
        if(parts.size()>4&&PairedService.ready())for(JSONObject hidden:parts.subList(4,parts.size()))
            if(PairedProtocol.mediaKind(hidden).equals("image")&&MediaCache.needsPhone(hidden)&&!PairedService.missingOnPhone(this,hidden)&&(fullPhotos()||!MediaCache.hasThumbnail(hidden))&&!MediaCache.file(this,hidden,false).isFile())PairedService.requestFullMedia(hidden,false,(v,e)->{});
        int width=Math.round(getResources().getDisplayMetrics().widthPixels*0.66f),gap=dp(3),half=(width-gap)/2,n=parts.size();
        LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);LinearLayout grid=vertical();
        grid.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(20));}});grid.setClipToOutline(true);
        if(n==2){int h=Math.round(width*0.62f);LinearLayout r=row();r.addView(albumCell(m,parts,0,0),new LinearLayout.LayoutParams(half,h));
            LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(half,h);second.setMarginStart(gap);r.addView(albumCell(m,parts,1,0),second);grid.addView(r);}
        else if(n==3){grid.addView(albumCell(m,parts,0,0),new LinearLayout.LayoutParams(width,Math.round(width*0.56f)));int h=Math.round(half*0.9f);
            LinearLayout r=row();LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(-1,-2);rl.topMargin=gap;r.addView(albumCell(m,parts,1,0),new LinearLayout.LayoutParams(half,h));
            LinearLayout.LayoutParams third=new LinearLayout.LayoutParams(half,h);third.setMarginStart(gap);r.addView(albumCell(m,parts,2,0),third);grid.addView(r,rl);}
        else for(int rr=0;rr<2;rr++){LinearLayout r=row();LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(-1,-2);if(rr>0)rl.topMargin=gap;
            for(int col=0;col<2;col++){int i=rr*2+col;LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(half,half);if(col>0)cl.setMarginStart(gap);r.addView(albumCell(m,parts,i,i==3&&n>4?n-4:0),cl);}
            grid.addView(r,rl);}
        line.addView(grid,new LinearLayout.LayoutParams(width,-2));return line;}
    private View albumCell(JSONObject m,List<JSONObject> parts,int i,int more){JSONObject media=parts.get(i);boolean video=PairedProtocol.mediaKind(media).equals("video");
        FrameLayout cell=new FrameLayout(this);cell.setTag("album");cell.setBackgroundColor(surface);ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);cell.addView(img,new FrameLayout.LayoutParams(-1,-1));
        if(video&&more==0){ImageView play=new ImageView(this);play.setImageResource(R.drawable.ic_play);play.setPadding(dp(10),dp(10),dp(9),dp(10));play.setBackground(oval(0x8C000000));cell.addView(play,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));}
        TextView state=text("",11,Color.WHITE,false);state.setPadding(dp(8),dp(4),dp(8),dp(4));state.setBackground(shape(0x99000000,10));state.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);sl.bottomMargin=dp(8);cell.addView(state,sl);img.setTag(R.id.tile_state,state);
        if(more>0){TextView plus=text("+"+more,26,Color.WHITE,true);plus.setGravity(Gravity.CENTER);plus.setBackgroundColor(0x8C000000);cell.addView(plus,new FrameLayout.LayoutParams(-1,-1));}
        cell.setContentDescription((video?"Video ":"Photo ")+(i+1)+" of "+parts.size()+(more>0?", and "+more+" more":"")+". Tap to open.");
        cell.setOnClickListener(v->openMedia(more>0&&parts.size()>4?parts.get(3):media));cell.setOnLongClickListener(v->{messageSheet(m);return true;});
        cell.post(()->loadPreview(media,img,video,Math.max(1,cell.getWidth()),Math.max(1,cell.getHeight())));return cell;}
    private InlineVideo inline;private boolean inlineSeen;private String pendingInline="";
    /** Tap a video: it plays right in its tile (downloading first if needed). Tap again to pause. */
    private void playInline(JSONObject media,FrameLayout box,ImageView img,View badge){String key=mediaKey(media);
        if(inline!=null&&inline.key.equals(key)){inline.attach(box,badge);inline.toggle();return;}
        if(inline!=null){inline.release();inline=null;}
        File have=MediaCache.file(this,media,false);if(have.isFile()){startInline(media,box,badge,have);return;}
        if(!PairedService.ready()){toast("Connect to Google Messages to download the video.");return;}
        if(!MediaCache.hasFull(media)){openMedia(media);return;}
        tileState(img,media,"Loading video…",null);pendingInline=key;
        MediaCache.fetch(this,media,false,(file,err)->{if(file==null){tileState(img,media,"Couldn't load • Tap for details",err);return;}tileState(img,media,null,null);
            if(!key.equals(pendingInline)||!screen.equals("chat")||!box.isAttachedToWindow())return;pendingInline="";startInline(media,box,badge,file);});}
    private void startInline(JSONObject media,FrameLayout box,View badge,File file){
        inline=new InlineVideo(this,mediaKey(media),file,MediaCache.mime(media),false,new InlineVideo.Host(){
            @Override public void fullScreen(InlineVideo v){int at=v.position();boolean quiet=v.muted();v.pause();
                Intent play=VideoPlayerActivity.open(PairedActivity.this,v.file,v.mime,displayName(media)).putExtra(VideoPlayerActivity.AT,at).putExtra(VideoPlayerActivity.MUTED,quiet);
                if(bubble())play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(play);}
            @Override public void failed(InlineVideo v,String why){Diagnostics.badFile(v.file,why);v.release();if(inline==v)inline=null;openMedia(media);}});
        inline.attach(box,badge);}
    private void stopInline(){if(inline!=null){inline.release();inline=null;}pendingInline="";}
    private static final int STICKER_DP=150;
    /** A sticker's size on screen: STICKER_DP on its longest side, keeping its shape. */
    private int[] stickerSize(long w,long h){int side=dp(STICKER_DP);if(w<=0||h<=0)return new int[]{side,side};
        return w>=h?new int[]{side,Math.max(dp(40),Math.round(side*h/(float)w))}:new int[]{Math.max(dp(40),Math.round(side*w/(float)h)),side};}
    private void stickerStyle(FrameLayout box,ImageView img){box.setBackground(null);box.setClipToOutline(false);img.setScaleType(ImageView.ScaleType.FIT_CENTER);box.setContentDescription("Sticker. Tap to view.");}
    /** A picture that turned out to be see-through once decoded: drop the photo frame and shrink to sticker size. */
    private void becomeSticker(ImageView img,Bitmap b){if(!(img.getParent() instanceof FrameLayout))return;FrameLayout box=(FrameLayout)img.getParent();if("album".equals(box.getTag()))return;
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
                if(MediaCache.needsPhone(media))PairedService.requestFullMedia(media,true,(v,e)->{if(!e.isEmpty())tileState(img,media,"Couldn't get this photo • Tap for details",e);else waitForPhone(img,media,MediaCache.hasThumbnail(media));});
                else loadPreview(media,img,video,box.getWidth(),box.getHeight());})
            .setNegativeButton("Close",null).show();}
    /** Photos open in the viewer; videos, audio and files open in another app. */
    private void openMedia(JSONObject media){
        if(PairedProtocol.mediaKind(media).equals("image")){viewer(media);return;}
        if(PairedProtocol.mediaKind(media).equals("video")){withFile(media,"Downloading the video…",file->{Intent play=VideoPlayerActivity.open(this,file,MediaCache.mime(media),displayName(media));
            // From a bubble, play full screen rather than inside the small bubble window.
            if(bubble())play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(play);});return;}
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
    /** The open full-screen viewer: its views, which picture of the chat it shows, and that picture's file. */
    /** The viewer on screen, for the on-device test. */
    private Viewer shownViewer;
    private static final class Viewer { Dialog d; FrameLayout box; ZoomImageView image; ProgressBar spinner; TextView note,counter; ImageView play; List<JSONObject> gallery; int index,gen; File full; }
    /** Full-screen photos (and video posters) for this chat: pinch to zoom, swipe sideways for the next or previous one. */
    private void viewer(JSONObject start){Viewer v=new Viewer();
        // Every photo and video in this chat, oldest first, so swiping goes the way the chat reads.
        v.gallery=new ArrayList<>();
        if(!chatId.isEmpty()){List<JSONObject> msgs=store.mediaMessages(chatId,500);java.util.Collections.reverse(msgs);
            for(JSONObject m:msgs)for(JSONObject a:PairedProtocol.media(m)){String k=PairedProtocol.mediaKind(a);if(k.equals("image")||k.equals("video"))v.gallery.add(a);}}
        String key=mediaKey(start);v.index=-1;for(int i=0;i<v.gallery.size();i++)if(mediaKey(v.gallery.get(i)).equals(key)){v.index=i;break;}
        if(v.index<0){v.gallery.clear();v.gallery.add(start);v.index=0;}
        v.d=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);v.box=new FrameLayout(this);v.box.setBackgroundColor(Color.BLACK);
        v.image=new ZoomImageView(this);v.box.addView(v.image,new FrameLayout.LayoutParams(-1,-1));
        v.spinner=new ProgressBar(this);v.box.addView(v.spinner,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));
        v.play=new ImageView(this);v.play.setImageResource(R.drawable.ic_play);v.play.setPadding(dp(22),dp(22),dp(20),dp(22));v.play.setBackground(oval(0x8C000000));v.play.setContentDescription("Play video");
        v.play.setOnClickListener(x->{JSONObject media=v.gallery.get(v.index);withFile(media,"Downloading the video…",file->startActivity(VideoPlayerActivity.open(this,file,MediaCache.mime(media),displayName(media))));});
        v.box.addView(v.play,new FrameLayout.LayoutParams(dp(76),dp(76),Gravity.CENTER));
        v.note=text("",14,Color.WHITE,false);v.note.setPadding(dp(14),dp(8),dp(14),dp(8));v.note.setBackground(shape(0x99000000,16));v.note.setVisibility(View.GONE);
        FrameLayout.LayoutParams nl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);nl.bottomMargin=dp(48);v.box.addView(v.note,nl);
        LinearLayout bar=row();bar.setPadding(dp(8),dp(36),dp(8),dp(20));
        bar.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x99000000,0x00000000}));
        bar.addView(icon(R.drawable.ic_close,Color.WHITE,"Close",v.d::dismiss));
        v.counter=text("",14,0xCCFFFFFF,false);v.counter.setPadding(dp(8),0,0,0);bar.addView(v.counter,new LinearLayout.LayoutParams(0,-2,1));
        for(String a:new String[]{"Save","Share"}){TextView t=text(a,16,Color.WHITE,true);t.setPadding(dp(14),dp(10),dp(14),dp(10));
            t.setOnClickListener(x->{JSONObject media=v.gallery.get(v.index);File have=v.full!=null?v.full:MediaCache.file(this,media,false);
                if(!have.isFile()){if(PairedProtocol.mediaKind(media).equals("video"))withFile(media,"Downloading the video…",file->{if(a.equals("Save"))saveToPhone(file,media);else share(file,media);});else toast("Still downloading…");return;}
                if(a.equals("Save"))saveToPhone(have,media);else share(have,media);});bar.addView(t);}
        v.box.addView(bar,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        // The window reaches under the status bar, camera cutout and navigation bar: Close, Save and Share stay below them.
        v.box.setOnApplyWindowInsetsListener((x,insets)->{android.graphics.Insets b=android.graphics.Insets.max(insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()),insets.getInsets(WindowInsets.Type.displayCutout()));
            bar.setPadding(dp(8)+b.left,Math.max(dp(36),dp(12)+b.top),dp(8)+b.right,dp(20));nl.bottomMargin=dp(48)+b.bottom;v.note.setLayoutParams(nl);return insets;});
        v.image.setOnTap(()->bar.setVisibility(bar.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        v.image.setSwipe(new ZoomImageView.Swipe(){
            @Override public boolean can(int dir){int next=v.index+dir;return next>=0&&next<v.gallery.size();}
            @Override public void go(int dir){v.index+=dir;viewerShow(v);}});
        v.d.setOnDismissListener(x->{v.gen++;if(shownViewer==v)shownViewer=null;});shownViewer=v;
        v.d.setContentView(v.box);v.d.show();v.box.requestApplyInsets();viewerShow(v);}
    /** Shows the gallery's current picture: its preview at once, then the full photo (or a play button for a video). */
    private void viewerShow(Viewer v){int g=++v.gen;JSONObject media=v.gallery.get(v.index);boolean video=PairedProtocol.mediaKind(media).equals("video");v.full=null;
        v.counter.setText(v.gallery.size()>1?(v.index+1)+" of "+v.gallery.size():"");v.note.setVisibility(View.GONE);
        v.play.setVisibility(video?View.VISIBLE:View.GONE);v.spinner.setVisibility(video?View.GONE:View.VISIBLE);v.image.setImageDrawable(null);sharp(v.image);
        File thumb=MediaCache.file(this,media,true),file=MediaCache.file(this,media,false);int side=Math.round(getResources().getDisplayMetrics().widthPixels*0.66f);
        decoder.execute(()->{Bitmap pre=video&&file.isFile()?MediaCache.decode(file,2560,true):null;boolean tiny=false;
            if(pre==null&&thumb.isFile()){pre=MediaCache.decode(thumb,side,false);tiny=pre!=null&&MediaCache.tiny(thumb);}
            Bitmap shown=pre;boolean soft=tiny;ui.post(()->{if(v.gen!=g||shown==null)return;if(video||v.image.getDrawable()==null){v.image.setImageBitmap(shown);if(soft)soft(v.image);}});});
        if(video){if(file.isFile())v.full=file;return;}
        if(!file.isFile()&&!PairedService.ready()){v.spinner.setVisibility(View.GONE);viewerNote(v,"Connect to Google Messages to download the full photo.");return;}
        if(!MediaCache.hasFull(media)&&!file.isFile()){viewerNote(v,"Getting the full photo from your phone…");
            PairedService.requestFullMedia(media,true,(x,e)->{if(!e.isEmpty()&&v.gen==g){viewerNote(v,e);v.spinner.setVisibility(View.GONE);}});
            awaitFull(media,45_000,updated->{if(v.gen!=g)return;
                if(updated==null){v.spinner.setVisibility(View.GONE);viewerNote(v,PairedService.missingOnPhone(this,media)?"Your phone doesn't seem to have this photo any more.":thumb.isFile()?"Your phone hasn't sent the full photo yet. Showing the preview.":"Your phone hasn't sent this photo yet.");if(thumb.isFile())v.full=thumb;return;}
                v.note.setVisibility(View.GONE);viewerFull(v,updated,g);});
            return;}
        viewerFull(v,media,g);}
    private void viewerNote(Viewer v,String text){v.note.setText(text);v.note.setVisibility(View.VISIBLE);}
    private void viewerFull(Viewer v,JSONObject media,int g){
        MediaCache.fetch(this,media,false,(file,error)->{if(v.gen!=g)return;if(file==null){v.spinner.setVisibility(View.GONE);viewerNote(v,error);return;}v.full=file;
            decoder.execute(()->{android.graphics.drawable.Drawable moving=MediaCache.animated(file,0,0);Bitmap b=moving!=null?null:MediaCache.decode(file,2560,false);String what=b==null&&moving==null?MediaCache.describe(file):"";
                ui.post(()->{if(v.gen!=g)return;v.spinner.setVisibility(View.GONE);
                    if(moving!=null){v.image.setImageDrawable(moving);sharp(v.image);((android.graphics.drawable.AnimatedImageDrawable)moving).start();}
                    else if(b!=null){v.image.setImageBitmap(b);sharp(v.image);}else if(v.d.isShowing())photoProblem(file,media,what);});});});}
    private void awaitFull(JSONObject media,long timeout,java.util.function.Consumer<JSONObject> done){long until=android.os.SystemClock.uptimeMillis()+timeout;String id=media.optString("_message");int index=media.optInt("_index",-1);
        Runnable[] check=new Runnable[1];check[0]=()->{JSONObject m=store.message(id);
            if(m!=null)for(JSONObject part:PairedProtocol.media(m))if(part.optInt("_index",-2)==index&&MediaCache.hasFull(part)){done.accept(part);return;}
            if(android.os.SystemClock.uptimeMillis()>until){done.accept(null);return;}ui.postDelayed(check[0],1500);};
        ui.postDelayed(check[0],1500);}
    /** The full photo arrived but won't display: keep the preview on screen and say what the file is. */
    private void photoProblem(File file,JSONObject media,String what){
        android.util.Log.w("MessageCenter","Full photo won't decode: "+what+", sent as "+MediaCache.mime(media));Diagnostics.badFile(file,what+", sent as "+MediaCache.mime(media)+(HeifDecoder.lastProblem.isEmpty()?"":", "+HeifDecoder.lastProblem));
        boolean broken=what.contains("empty")||what.contains("web page")||what.contains("unrecognized");
        // A broken download is thrown away so opening the photo again fetches it fresh.
        if(broken&&file.delete())MediaCache.forget(file);
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("Can't show this photo").setPositiveButton("OK",null)
            .setMessage(broken?"The download didn't contain a photo ("+what+"). Open it again to retry.":"It downloaded ("+what+"), but Android couldn't display it. Share opens it in another app, such as Photos.");
        if(!broken)b.setNeutralButton("Share",(x,y)->share(file,media));b.setNegativeButton("Report…",(x,y)->reportProblem("A picture wouldn't open"));
        b.show();
    }
    private static final int TAKE_PHOTO=43;private File pendingCapture;
    /** ⊕ button: a panel with Photos & videos (Android's photo picker, no storage permission), Camera, Files, Location, Contact and Text on arrival. */
    private void attach(){LinearLayout content=vertical();Dialog[] d=new Dialog[1];LinearLayout tiles=row();tiles.setGravity(Gravity.CENTER);
        attachTile(tiles,R.drawable.ic_gallery,"Photos & videos",()->{d[0].dismiss();pickMedia();});
        attachTile(tiles,R.drawable.ic_camera,"Camera",()->{d[0].dismiss();takePhoto();});
        attachTile(tiles,R.drawable.ic_file,"Files",()->{d[0].dismiss();pickFiles();});
        attachTile(tiles,R.drawable.ic_place,"Location",()->{d[0].dismiss();shareLocation();});
        content.addView(tiles,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout more=row();more.setGravity(Gravity.CENTER);
        attachTile(more,R.drawable.ic_contact,"Contact",()->{d[0].dismiss();pickContact();});
        attachTile(more,R.drawable.ic_arrive,"Text on arrival",()->{d[0].dismiss();arrivalText(chatId);});
        // Spaces keep the tiles the same size as the ones in the row above.
        for(int k=0;k<2;k++)more.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        content.addView(more,new LinearLayout.LayoutParams(-1,-2));d[0]=sheet(content);}
    private static final int PICK_CONTACT=87,SHARE_CONTACT_PERMISSION=88;
    /**
     * ⊕ → Contact: a searchable list of your contacts, each person once, then "Name: number" goes
     * into the message box to send or edit. Android's own picker listed every saved copy of a
     * number as its own row and blocks screenshots, so it's only used without contacts access.
     */
    private void pickContact(){if(PhoneContacts.allowed(this)){showContactPicker();return;}
        new AlertDialog.Builder(this).setTitle("Share a contact").setMessage("To list your contacts here, Message Center needs to read them. They stay on this phone.")
            .setPositiveButton("Allow",(d,w)->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},SHARE_CONTACT_PERMISSION))
            .setNeutralButton("Use Android's picker",(d,w)->systemContactPicker()).setNegativeButton("Cancel",null).show();}
    private void systemContactPicker(){try{startActivityForResult(new Intent(Intent.ACTION_PICK).setType(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE),PICK_CONTACT);}
        catch(android.content.ActivityNotFoundException e){toast("There's no contacts app to pick from.");}}
    private void showContactPicker(){String chat=chatId;decoder.execute(()->{List<ContactPeople.Person> people=PhoneContacts.people(this);ui.post(()->{
        if(!screen.equals("chat")||!chatId.equals(chat)||isFinishing())return;
        if(people.isEmpty()){toast("No contacts with a phone number.");return;}
        LinearLayout box=vertical();box.setPadding(dp(16),dp(8),dp(16),0);EditText search=input("Search contacts");search.setSingleLine(true);box.addView(search,new LinearLayout.LayoutParams(-1,-2));
        List<ContactPeople.Person> shown=new ArrayList<>(people);android.widget.ListView list=new android.widget.ListView(this);list.setDivider(null);
        android.widget.BaseAdapter adapter=new android.widget.BaseAdapter(){public int getCount(){return shown.size();}public Object getItem(int i){return shown.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View reuse,android.view.ViewGroup parent){LinearLayout r=reuse instanceof LinearLayout?(LinearLayout)reuse:null;TextView name,detail;
                if(r==null){r=vertical();r.setPadding(dp(4),dp(10),dp(4),dp(10));name=text("",16,ink,false);detail=text("",13,muted,false);r.addView(name);r.addView(detail);}
                else{name=(TextView)r.getChildAt(0);detail=(TextView)r.getChildAt(1);}
                ContactPeople.Person p=shown.get(i);name.setText(p.name);ContactPeople.Number first=p.numbers.get(0);
                detail.setText(p.numbers.size()==1?(first.label.isEmpty()?first.shown:first.label+" · "+first.shown):p.numbers.size()+" numbers");return r;}};
        list.setAdapter(adapter);box.addView(list,new LinearLayout.LayoutParams(-1,dp(420)));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Share a contact").setView(box).setNegativeButton("Cancel",null).create();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void afterTextChanged(Editable e){}
            public void onTextChanged(CharSequence s,int st,int b,int c){String q=ContactSearch.fold(s.toString().trim()),qd=ChatText.digits(s.toString());shown.clear();
                for(ContactPeople.Person p:people){int best=-1;for(ContactPeople.Number n:p.numbers)best=Math.max(best,ContactSearch.score(p.folded,"",ChatText.digits(n.shown),q,qd));if(q.isEmpty()||best>=0)shown.add(p);}
                adapter.notifyDataSetChanged();}});
        list.setOnItemClickListener((parent,v,i,id)->{ContactPeople.Person p=shown.get(i);d.dismiss();chooseContactNumber(p);});
        d.show();});});}
    /** Which of their numbers, when there's more than one; then their email, if wanted. */
    private void chooseContactNumber(ContactPeople.Person p){if(p.numbers.size()==1){shareContact(p,p.numbers.get(0).shown);return;}
        String[] choices=new String[p.numbers.size()];for(int i=0;i<choices.length;i++){ContactPeople.Number n=p.numbers.get(i);choices[i]=n.label.isEmpty()?n.shown:n.label+" · "+n.shown;}
        List<Runnable> actions=new ArrayList<>();for(ContactPeople.Number n:p.numbers)actions.add(()->shareContact(p,n.shown));menuSheet(p.name,java.util.Arrays.asList(choices),actions);}
    private void shareContact(ContactPeople.Person p,String number){String chat=chatId,shown=readableNumber(number),who=p.name;decoder.execute(()->{List<String> emails=contactEmails(p.contact);ui.post(()->{
        if(!screen.equals("chat")||!chatId.equals(chat)||composer==null)return;
        if(emails.isEmpty()){insertContact(who,shown,"");return;}
        
        emailChoices(who,who,shown,emails);});});}
    /** Share just the number, or the number with one of the contact's email addresses. */
    private void emailChoices(String title,String who,String shown,List<String> emails){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        labels.add("Just the number");actions.add(()->insertContact(who,shown,""));
        for(String e:emails){labels.add("Add "+e);actions.add(()->insertContact(who,shown,e));}menuSheet(title,labels,actions);}
    /** A contact's email addresses, each once (needs the contacts permission). Off the main thread. */
    private List<String> contactEmails(long contact){List<String> emails=new ArrayList<>();
        if(contact<0||checkSelfPermission(android.Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)return emails;
        try(android.database.Cursor c=getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,new String[]{android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS},
                android.provider.ContactsContract.CommonDataKinds.Email.CONTACT_ID+"=?",new String[]{String.valueOf(contact)},null)){
            while(c!=null&&c.moveToNext()){String a=c.isNull(0)?"":c.getString(0).trim();boolean dup=false;for(String e:emails)if(e.equalsIgnoreCase(a))dup=true;if(!a.isEmpty()&&!dup)emails.add(a);}}
        catch(Exception ignored){}
        return emails;}
    private void sharePickedContact(Uri picked){String chat=chatId;decoder.execute(()->{String name="",number="";long contact=-1;List<String> emails=new ArrayList<>();
        String[] columns={android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID};
        try(android.database.Cursor c=getContentResolver().query(picked,columns,null,null,null)){if(c!=null&&c.moveToFirst()){name=c.isNull(0)?"":c.getString(0);number=c.isNull(1)?"":c.getString(1);contact=c.isNull(2)?-1:c.getLong(2);}}
        catch(Exception e){Diagnostics.note("Couldn't read the picked contact: "+e.getClass().getSimpleName());}
        // Their email too, offered only when the app may already read contacts (for names and photos).
        emails.addAll(contactEmails(contact));
        String shown=readableNumber(number),who=name;
        ui.post(()->{if(!screen.equals("chat")||!chatId.equals(chat)||composer==null)return;
            if(shown.isEmpty()){toast("That contact has no phone number.");return;}
            if(emails.isEmpty()){insertContact(who,shown,"");return;}
            
            emailChoices(who.isEmpty()?shown:who,who,shown,emails);});});}
    /** A number as people write it: "(479) 555-0134" for one saved as bare digits; anything already formatted stays as saved. */
    private String readableNumber(String number){number=number==null?"":number.trim();if(!number.matches("\\+?\\d{7,15}"))return number;
        String country="";try{country=getSystemService(android.telephony.TelephonyManager.class).getSimCountryIso();}catch(Exception ignored){}
        if(country==null||country.isEmpty())country=Locale.getDefault().getCountry();
        String formatted=android.telephony.PhoneNumberUtils.formatNumber(number,country.toUpperCase(Locale.ROOT));return formatted==null?number:formatted;}
    /** Puts "Name: number" where the cursor is (on its own line next to other words). */
    private void insertContact(String name,String number,String email){if(composer==null)return;String now=composer.getText().toString();
        ContactText.Inserted in=ContactText.insert(now,composer.getSelectionStart(),composer.getSelectionEnd(),ContactText.of(name,number,email));
        composer.setText(in.text);composer.setSelection(Math.min(in.cursor,composer.getText().length()));composer.requestFocus();}
    private void attachTile(LinearLayout parent,int icon,String label,Runnable action){ChatTheme theme=chatSettings.theme(chatId);
        LinearLayout tile=vertical();tile.setGravity(Gravity.CENTER_HORIZONTAL);tile.setPadding(dp(4),dp(10),dp(4),dp(10));pressable(tile);
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
        if(request==PICK_NOTE_FILES||request==PICK_NOTE_PHOTOS){if(result!=RESULT_OK||data==null)return;List<Uri> picked=new ArrayList<>();
            if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)picked.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)picked.add(data.getData());
            addNoteFiles(picked);return;}
        if(request==RESTORE_BACKUP){if(result==RESULT_OK&&data!=null&&data.getData()!=null)restoreBackup(data.getData());return;}
        if(request==PICK_BACKGROUND){if(result==RESULT_OK&&data!=null&&data.getData()!=null)saveBackgroundPhoto(data.getData());return;}
        if(request==PICK_GROUP_PHOTO){if(result==RESULT_OK&&data!=null&&data.getData()!=null)saveGroupPhoto(data.getData());return;}
        if(request==PICK_CONTACT){if(result==RESULT_OK&&data!=null&&data.getData()!=null)sharePickedContact(data.getData());return;}
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
        String mime=getContentResolver().getType(uri);if(mime==null)mime="application/octet-stream";
        // Videos are compressed before sending (see VideoPrep), so a long 4K one is fine; other files go as they are.
        boolean video=mime.startsWith("video/");long most=video?MAX_VIDEO:MAX_ATTACHMENT;String tooBig=video?name+" is over 4 GB, too big to send.":name+" is over 100 MB, the most Google Messages sends.";
        if(size>most)throw new Exception(tooBig);
        File dir=new File(getCacheDir(),"outgoing");if(!dir.isDirectory())dir.mkdirs();File target=new File(dir,java.util.UUID.randomUUID()+"-"+name.replaceAll("[^A-Za-z0-9._-]","_"));
        long copied;try(java.io.InputStream in=getContentResolver().openInputStream(uri);java.io.OutputStream out=new java.io.FileOutputStream(target)){if(in==null)throw new Exception("That file can't be read.");copied=in.transferTo(out);}
        catch(java.io.IOException e){target.delete();throw new Exception(dir.getUsableSpace()<(64L<<20)?"There isn't room on this phone to add "+name+". Free up some space and try again.":name+" couldn't be added.");}
        if(copied>most){target.delete();throw new Exception(tooBig);}
        return new Outgoing(target,name,mime);}
    /** The row of picked attachments above the message box, each removable until sent. */
    private void renderAttachments(){if(attachStrip==null)return;attachStrip.removeAllViews();attachScroll.setVisibility(outgoing.isEmpty()?View.GONE:View.VISIBLE);
        for(Outgoing o:outgoing)prepareWhileTyping(o);
        for(Outgoing o:outgoing){FrameLayout tile=new FrameLayout(this);tile.setBackground(shape(surface,14));
            tile.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline ol){ol.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(14));}});tile.setClipToOutline(true);
            boolean visual=o.mime.startsWith("image/")||o.mime.startsWith("video/");
            if(visual){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(img,new FrameLayout.LayoutParams(-1,-1));
                decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(o.file,dp(72),dp(72),o.mime.startsWith("video/"));if(b!=null)img.post(()->img.setImageBitmap(b));});}
            else {boolean voice=o.mime.startsWith("audio/");LinearLayout box=vertical();box.setGravity(Gravity.CENTER);ImageView ic=new ImageView(this);ic.setImageResource(voice?R.drawable.ic_mic:R.drawable.ic_file);
                ic.setImageTintList(ColorStateList.valueOf(ink));box.addView(ic,new LinearLayout.LayoutParams(dp(24),dp(24)));TextView t=text(voice?"Voice message":o.name,11,ink,false);t.setGravity(Gravity.CENTER);t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);t.setPadding(dp(4),dp(4),dp(4),0);box.addView(t);tile.addView(box,new FrameLayout.LayoutParams(-1,-1));}
            if(o.sending){TextView s=text("Sending…",11,Color.WHITE,true);s.setGravity(Gravity.CENTER);s.setBackgroundColor(0x99000000);tile.addView(s,new FrameLayout.LayoutParams(-1,-1));}
            else {ImageView x=new ImageView(this);x.setImageResource(R.drawable.ic_close);x.setImageTintList(ColorStateList.valueOf(Color.WHITE));x.setBackground(oval(0x99000000));x.setPadding(dp(3),dp(3),dp(3),dp(3));
                // A fingertip-sized target around the small ✕ (the ✕ alone was easy to miss).
                FrameLayout remove=new FrameLayout(this);remove.setContentDescription("Remove "+o.name);remove.setOnClickListener(v->{outgoing.remove(o);discardPicked(o);renderAttachments();});
                FrameLayout.LayoutParams xl=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.TOP|Gravity.END);xl.setMargins(0,dp(4),dp(4),0);remove.addView(x,xl);
                x.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(remove,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.TOP|Gravity.END));
                // Photos can be marked up before sending (not GIFs, which would stop moving).
                if(o.mime.startsWith("image/")&&!o.mime.equals("image/gif")){TextView pen=text("✎",12,Color.WHITE,true);pen.setGravity(Gravity.CENTER);pen.setBackground(oval(0x99000000));
                    tile.addView(pen,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.BOTTOM|Gravity.START));tile.setContentDescription("Mark up "+o.name);tile.setOnClickListener(v->markUp(o));}}
            LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(dp(72),dp(72));tl.setMarginEnd(dp(8));attachStrip.addView(tile,tl);}
        boolean photos=false,preparing=false;long bytes=0;for(Outgoing o:outgoing)if(o.mime.startsWith("image/")){photos=true;preparing|=o.preparing;bytes+=o.prepared!=null?o.prepared.file.length():Math.min(o.file.length(),PhotoQuality.bytes(photoQuality(),false));}
        if(photos){TextView quality=text("Photo quality: "+PhotoQuality.label(photoQuality())+"\n"+(preparing?"Preparing size…":PhotoQuality.size(bytes))+" • Tap to change",13,ink,false);quality.setPadding(dp(12),dp(10),dp(12),dp(10));quality.setBackground(shape(surface,14));
            quality.setContentDescription("Photo quality: "+PhotoQuality.label(photoQuality())+(preparing?", preparing":", "+PhotoQuality.size(bytes)));quality.setOnClickListener(v->{if(!isBusy())choosePhotoQuality();});attachStrip.addView(quality,new LinearLayout.LayoutParams(-2,dp(72)));}
        boolean videos=false;for(Outgoing o:outgoing)if(o.mime.startsWith("video/"))videos=true;
        if(videos){JSONObject c=store.chat(chatId);boolean mms=c==null||!PairedProtocol.rcs(c);
            TextView vq=text("Video quality: "+(mms?"picture message (under 1 MB)":PhotoQuality.label(videoQuality()))+"\n"+(mms?"Made to fit MMS":VideoPlan.describe(videoQuality()).replace(", otherwise sharp 1080p",""))+(mms?"":" • Tap to change"),13,ink,false);
            vq.setPadding(dp(12),dp(10),dp(12),dp(10));vq.setBackground(shape(surface,14));vq.setMaxWidth(dp(220));if(!mms)vq.setOnClickListener(v->{if(!isBusy())chooseVideoQuality();});
            LinearLayout.LayoutParams vl=new LinearLayout.LayoutParams(-2,dp(72));vl.setMarginStart(dp(8));attachStrip.addView(vq,vl);}
        updateComposeButton();}
    /** Opens an attached photo to draw on, add text to, or crop; the edited copy takes its place. */
    private void markUp(Outgoing o){if(o.sending)return;MarkupEditor.open(this,o.file,edited->{int i=outgoing.indexOf(o);if(i<0||o.sending){edited.delete();return;}
        String name=o.name.replaceAll("\\.[A-Za-z0-9]+$","")+".jpg";outgoing.set(i,new Outgoing(edited,name,"image/jpeg"));discardPicked(o);renderAttachments();});}
    /** Sends each attachment on its own, followed by the typed text after the first one. */
    /**
     * Sends what's in the attachment strip. Each one moves into the chat right away as a "Sending…"
     * bubble (so the strip doesn't hang around and then drop the chat down when it empties), and is
     * replaced by the real message when Google shows it.
     */
    /** Sends what's in the attachment strip, with the typed text and the reply you picked. */
    private void sendAttachments(JSONObject c,String caption,String reply){List<Outgoing> batch=new ArrayList<>();for(Outgoing o:outgoing)if(!o.sending)batch.add(o);
        queueAttachments(c,batch,caption,reply,"",true);}
    /**
     * Saves attachments to the durable queue (PairedService sends them). The reply link goes on the
     * first one only, so the original isn't quoted twice; the text goes on the last one and follows
     * once your phone has sent it. fromComposer: these came from the strip and the message box,
     * which are cleared once saved (a resend of a failed message leaves your draft alone).
     */
    private void queueAttachments(JSONObject c,List<Outgoing> batch,String caption,String reply,String replaces,boolean fromComposer){
        if(batch.isEmpty())return;String id=c.optString("conversationID"),quality=photoQuality(),videos=videoQuality();boolean mms=!PairedProtocol.rcs(c);
        for(Outgoing o:batch)o.sending=true;if(fromComposer){busy=true;busySince=System.currentTimeMillis();if(composer!=null)composer.setEnabled(false);renderAttachments();}
        android.content.Context app=getApplicationContext();
        photoPreparation.execute(()->{List<Outgoing> saved=new ArrayList<>();String problem="";MediaOutbox box=new MediaOutbox(app);
            for(int i=0;i<batch.size();i++){Outgoing o=batch.get(i);try{
                boolean fit=mms||o.small;String chosen=o.small?PhotoQuality.FAST:o.mime.startsWith("video/")?videos:quality;PhotoPrep.Ready ready=(chosen+"|"+fit).equals(o.variant)?o.prepared:null;
                if(ready!=null&&!ready.error.isEmpty())throw new java.io.IOException(ready.error);
                box.enqueue(id,o.file,o.name,o.mime,i==batch.size()-1?caption:"",i==0?reply:"",chosen,fit,o.small,ready,i==0?replaces:"");saved.add(o);
            }catch(Exception e){problem=e.getMessage()==null?"The attachment couldn't be saved. Check free space.":e.getMessage();break;}}
            String error=problem;ui.post(()->{for(Outgoing o:batch)o.sending=false;
                if(fromComposer){busy=false;outgoing.removeAll(saved);if(composer!=null)composer.setEnabled(true);
                    if(!saved.isEmpty()){drafts.remove(id);repliedSinceInbox=true;if(screen.equals("chat")&&chatId.equals(id)){if(composer!=null)composer.setText("");cancelReply();}}}
                else for(Outgoing o:batch)if(!saved.contains(o))discardPicked(o);
                if(screen.equals("chat")){if(fromComposer)renderAttachments();renderMessages(true);}if(!error.isEmpty())toast(error);
                if(!saved.isEmpty())PairedService.flushAttachments(app);
            });
        });
    }
    /** The queue as last read; it's read again only when something saved a change to it. */
    private long savedAttachmentsVersion=-1;
    private void refreshSavedAttachments(){long version=MediaOutbox.version();if(version==savedAttachmentsVersion&&savedAttachmentsVersion>=0)return;
        List<JSONObject> jobs=new MediaOutbox(this).all();savedAttachmentsVersion=version;Set<String> present=new HashSet<>();
        for(JSONObject j:jobs){String id=j.optString("id");present.add(id);PendingMedia p=null;for(PendingMedia old:pendingMedia)if(id.equals(old.queueId)){p=old;break;}
            if(p==null){p=new PendingMedia(j.optString("chat"),new Outgoing(new File(j.optString("file")),j.optString("name"),j.optString("mime")),j.optString("caption"),j.optString("reply"),j.optLong("created"));p.queueId=id;pendingMedia.add(p);thumbFor(p);}
            p.job=j;p.echo=j.optString("mediaEcho");p.sent=j.optString("mediaTmpID").isEmpty()?0:j.optLong("created");
            String state=j.optString("state");p.error=state.equals(MediaOutbox.FAILED)||state.equals(MediaOutbox.UNSURE)?j.optString("error"):"";
        }
        pendingMedia.removeIf(p->!present.contains(p.queueId));
    }
    private void renderSavedAttachment(PendingMedia p,ChatTheme theme){JSONObject j=p.job;if(j==null)return;JSONObject echo=p.echo.isEmpty()?null:store.message(p.echo);
        boolean actual=echo!=null&&!PairedProtocol.media(echo).isEmpty();
        if(!actual){gap(items,8);View thumb=pendingThumb(p,theme);items.addView(thumb,new LinearLayout.LayoutParams(-1,-2));motion(thumb,"pm:"+p.queueId,LIFT,motionPending.add(p.queueId)&&motionReady);TextView status=text(MediaOutbox.label(j,PairedService.ready()),12,p.error.isEmpty()?muted:danger,false);
            status.setGravity(Gravity.END);status.setPadding(dp(8),dp(3),dp(8),dp(6));status.setOnClickListener(v->savedAttachmentOptions(p,false));items.addView(status,new LinearLayout.LayoutParams(-1,-2));p.status=status;}
        JSONObject textEcho=store.message(j.optString("captionEcho"));
        if(!p.caption.isEmpty()&&(textEcho==null||PairedProtocol.text(textEcho).isEmpty())){
            LinearLayout line=row();line.setGravity(Gravity.END);TextView body=text(p.caption,17,theme.onBubble(dark),false);body.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));body.setPadding(dp(16),dp(11),dp(16),dp(11));body.setBackground(shape(theme.bubble(dark),22));body.setAlpha(0.75f);line.addView(body);items.addView(line);
            TextView state=text(MediaOutbox.captionLabel(j),12,muted,false);state.setGravity(Gravity.END);state.setPadding(dp(8),dp(3),dp(8),dp(6));items.addView(state,new LinearLayout.LayoutParams(-1,-2));
            body.setOnClickListener(v->savedAttachmentOptions(p,true));state.setOnClickListener(v->savedAttachmentOptions(p,true));}
    }
    private void savedAttachmentOptions(PendingMedia p,boolean text){MediaOutbox box=new MediaOutbox(this);JSONObject j=box.get(p.queueId);if(j==null)return;
        String state=j.optString(text?"captionState":"state"),problem=j.optString(text?"captionError":"error"),label=text?MediaOutbox.captionLabel(j):MediaOutbox.label(j,PairedService.ready(),PairedService.status(this));
        // Text waiting on its attachment can be sent right away instead.
        boolean waitingText=text&&MediaOutbox.captionWaiting(j);
        boolean canRetry=state.equals(MediaOutbox.FAILED)||state.equals(MediaOutbox.UNSURE)||waitingText,canRemove=state.equals(MediaOutbox.FAILED)||state.equals(MediaOutbox.UNSURE)||state.equals(MediaOutbox.WAITING)||state.equals(MediaOutbox.ACCEPTED)||state.equals(MediaOutbox.SENT)||state.equals(MediaOutbox.DELIVERED);
        String explanation=problem.isEmpty()?label:problem;if(state.equals(MediaOutbox.UNSURE))explanation="Google has this but hasn't confirmed it was delivered. That usually happens by itself once the signal's back. Send it again only if you're sure it didn't go, or they may get it twice.";
        else if(state.equals(MediaOutbox.WAITING)&&j.optInt("tries")>0)explanation="It couldn't reach Google (weak or no signal), so it hasn't gone anywhere. It sends by itself when the connection's back.";
        if(waitingText)explanation="This text goes once your phone has sent the attachment above it, so it arrives after it."+(j.optString("state").equals(MediaOutbox.FAILED)||j.optString("state").equals(MediaOutbox.UNSURE)?" The attachment hasn't gone, so the text is waiting for you.":"");
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(text?"Text with attachment":"Saved attachment").setMessage(explanation).setNegativeButton("Close",null);
        if(canRetry)dialog.setPositiveButton(waitingText?"Send text now":text?"Retry text only":"Retry attachment only",(d,w)->{JSONObject current=box.get(p.queueId);if(current==null)return;
            String now=current.optString(text?"captionState":"state");if(!now.equals(MediaOutbox.FAILED)&&!now.equals(MediaOutbox.UNSURE)&&!(text&&MediaOutbox.captionWaiting(current))){toast("This send is already running.");return;}
            box.retry(p.queueId,text);PairedService.flushAttachments(this);renderMessages(false);});
        if(canRemove)dialog.setNeutralButton("Remove saved copy",(d,w)->{JSONObject current=box.get(p.queueId);if(current==null)return;String now=current.optString("state");
            if(now.equals(MediaOutbox.PREPARING)||now.equals(MediaOutbox.UPLOADING)||now.equals(MediaOutbox.SUBMITTING)||current.optString("captionState").equals(MediaOutbox.SUBMITTING)){toast("This send is still running.");return;}
            new AlertDialog.Builder(this).setMessage("Remove this attachment and any text still waiting with it from the local queue? Anything already submitted to Google Messages stays there.").setNegativeButton("Keep",null).setPositiveButton("Remove",(a,b)->{box.remove(p.queueId);renderMessages(false);}).show();});
        else dialog.setNeutralButton("Google Messages",(d,w)->openGoogle());dialog.show();
    }
    // ---- Attachments on their way: shown at the bottom of the chat until Google shows the real message ----
    /**
     * An attachment in the durable queue (MediaOutbox), as shown in the chat until Google shows the real message.
     * echo: Google's copy of it; sent: when it was accepted (0 until then); status: its label while on screen.
     */
    private static final class PendingMedia{final String chat,caption,reply;final Outgoing item;final long started;long sent;String error="",echo="",queueId="";JSONObject job;Bitmap thumb;TextView status;
        PendingMedia(String chat,Outgoing item,String caption,String reply,long started){this.chat=chat;this.item=item;this.caption=caption;this.reply=reply;this.started=started;}}
    private final List<PendingMedia> pendingMedia=new ArrayList<>();
    private void thumbFor(PendingMedia p){Outgoing o=p.item;boolean visual=o.mime.startsWith("image/")||o.mime.startsWith("video/");if(!visual)return;
        decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(o.file,dp(240),dp(240),o.mime.startsWith("video/"));if(b!=null)ui.post(()->{p.thumb=b;if(screen.equals("chat")&&chatId.equals(p.chat))renderMessages(false);});});}
    /** The attachment on its way shown inside Google's copy of it, if any. */
    private PendingMedia echoFor(String messageID){for(PendingMedia p:pendingMedia)if(!messageID.isEmpty()&&messageID.equals(p.echo))return p;return null;}
    /** The photo (or file card) of an attachment on its way, at the right like any message you send. */
    private View pendingThumb(PendingMedia p,ChatTheme theme){LinearLayout line=row();line.setGravity(Gravity.END);int fg=theme.onBubble(dark);
        FrameLayout box=new FrameLayout(this);box.setBackground(shape(theme.bubble(dark),22));
        box.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline ol){ol.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(22));}});box.setClipToOutline(true);
        int w,h;if(p.thumb!=null){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setImageBitmap(p.thumb);
            w=dp(220);h=Math.max(dp(120),Math.min(dp(300),Math.round(w*p.thumb.getHeight()/(float)Math.max(1,p.thumb.getWidth()))));box.addView(img,new FrameLayout.LayoutParams(-1,-1));}
        else{boolean voice=p.item.mime.startsWith("audio/");LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(12),dp(10),dp(16),dp(10));
            card.addView(roundIcon(voice?R.drawable.ic_mic:R.drawable.ic_file,(fg&0x00ffffff)|0x22000000,fg,36));TextView n=text(voice?"Voice message":p.item.name,15,fg,true);n.setPadding(dp(10),0,0,0);n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.MIDDLE);n.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.5f));card.addView(n);box.addView(card);w=-2;h=-2;}
        if(p.error.isEmpty()&&p.sent==0){ProgressBar spin=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spin.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
            FrameLayout ring=new FrameLayout(this);ring.setBackground(oval(0x88000000));ring.addView(spin,new FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER));
            FrameLayout.LayoutParams rl=new FrameLayout.LayoutParams(dp(30),dp(30),Gravity.TOP|Gravity.END);rl.setMargins(0,dp(8),dp(8),0);box.addView(ring,rl);}
        box.setAlpha(p.error.isEmpty()?0.85f:0.6f);LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(w,h);bl.bottomMargin=dp(4);line.addView(box,bl);
        box.setOnClickListener(v->pendingOptions(p));return line;}
    /** Tap an attachment on its way: what's happening, and send again or remove when that makes sense. */
    private void pendingOptions(PendingMedia p){savedAttachmentOptions(p,false);}
    private void renderPendingMedia(ChatTheme theme){for(PendingMedia p:pendingMedia)if(p.chat.equals(chatId))renderSavedAttachment(p,theme);}
    private final Set<String> motionPending=new HashSet<>();
    // ---- 0.7: swipes, jump to latest, codes, jumbo emoji, details, starred, media, search, scheduling ----
    private Starred starred;private TextView jumpButton;private int newWhileAway;private long newestSeenTime;
    private String searchedFor="";private List<JSONObject> searchHits=new ArrayList<>();private int searchGeneration;

    /** How far the chat slides left to show the times: the time's own width plus the list's edge margin. */
    static final int TIME_REVEAL_DP=84,TIME_LABEL_DP=72,LIST_EDGE_DP=12;
    /**
     * A message row with its time waiting just past the right edge of the screen, out of sight until you
     * drag the chat left (the list's 12dp margin plus the label's own width puts all of it off the screen).
     */
    private View timed(View row,long at,boolean out){FrameLayout f=new FrameLayout(this);f.addView(row,new FrameLayout.LayoutParams(-1,-2));
        TextView t=text(timeOfDay(at),12,muted,false);t.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);t.setPadding(0,0,dp(2),0);t.setSingleLine(true);
        t.setTranslationX(dp(LIST_EDGE_DP+TIME_LABEL_DP));t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        f.addView(t,new FrameLayout.LayoutParams(dp(TIME_LABEL_DP),-1,Gravity.END));f.setTag(R.id.time_label,t);return f;}
    /**
     * Dragging the chat left: the whole conversation slides over as one, carrying each message's time
     * in from past the right edge. One move for the list (not one per row), so it's smooth in a long chat,
     * and rows redrawn mid-drag (a new message, a receipt) are already in the slid-over list.
     */
    private void revealTimes(LinearLayout list,float amount){list.setTranslationX(selecting?0:-amount);}
    /** A hint shown behind a swiped row: colored strip with an icon or label, at the start or end. */
    private View swipeHint(String label,int color,boolean atStart){FrameLayout f=new FrameLayout(this);f.setBackground(shape(color,22));
        TextView t=text(label,14,Color.WHITE,true);t.setPadding(dp(22),0,dp(22),0);f.addView(t,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL|(atStart?Gravity.START:Gravity.END)));return f;}
    /** Messages: swipe right to reply. */
    private View swipeToReply(View message,JSONObject m){
        View hint=new FrameLayout(this);TextView arrow=text("↩",22,muted,true);arrow.setPadding(dp(22),0,0,0);((FrameLayout)hint).addView(arrow,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL|Gravity.START));
        return new SwipeRow(this,message,hint,null,dp(64),dp(96)).onSwipeRight(()->startReply(m));}
    /** Inbox: swipe left to archive (or move back to the inbox), right to mark read or unread. */
    /** An inbox row: the one already drawn for this chat when nothing it shows has changed, else a new one. */
    private View chatRow(JSONObject c){long perf=Perf.start();String id=c.optString("conversationID"),key=rowKey(c);Object[] hit=key==null?null:rowCache.get(id);Perf.end("row: key",perf);
        if(hit!=null&&key.equals(hit[0])){View v=(View)hit[1];if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);
            if(v instanceof SwipeRow)((SwipeRow)v).settle();v.jumpDrawablesToCurrentState();return v;}
        View v=swipeableChat(c);if(key==null)rowCache.remove(id);else rowCache.put(id,new Object[]{key,v});return v;}
    /**
     * Everything an inbox row is drawn from; the same key means the row would come out the same.
     * Null for a row whose preview comes from saved messages instead (it's always drawn fresh).
     */
    private String rowKey(JSONObject c){if(PairedProtocol.string(c.optJSONObject("latestMessage"),"displayContent").isEmpty())return null;
        String id=c.optString("conversationID");long when=PairedProtocol.time(c);
        return store.savedText(c)+'\n'+folder+'|'+chatSettings.unread(c)+'|'+chatSettings.pinned(c)+'|'+chatSettings.muted(id)+'|'+chatSettings.snoozed(id)+'|'+dark+'|'+textScale
            +'|'+(when>0?inboxStamp(when):"")+'|'+PairedProtocol.title(c)+'|'+Avatars.changes()+'|'+drafts.getOrDefault(id,"");}
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
        LinearLayout r=vertical();r.setPadding(dp(12),dp(12),dp(12),dp(12));pressable(r);
        String who=PairedProtocol.outgoing(m,c)?"You":senderFirst(m,c);
        r.addView(text((showChat&&c!=null?PairedProtocol.title(c)+" • ":"")+who+" • "+separatorStamp(PairedProtocol.time(m)),12,muted,false));
        TextView t=text(m.has("_photoText")?"Text in picture: "+m.optString("_photoText"):displayBody(m),15,ink,false);t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);r.addView(t);
        r.setOnClickListener(v->openMessage(m));return r;}
    private void openMessage(JSONObject m){String chatID=m.optString("conversationID");if(store.chat(chatID)==null){toast("That conversation isn't synced here.");return;}
        thread(chatID);if(store.message(m.optString("messageID"))==null){toast("That message isn't in the synced history.");return;}ui.post(()->showMessage(m));}

    // ---- Shared in this chat: photos & videos, links, and files, newest first and by month ----
    /** One thing shared in a chat: a photo, video or file (media), or a link (url), and the message it came in. */
    private static final class SharedItem{final JSONObject message,media;final String url;final long time;
        SharedItem(JSONObject message,JSONObject media,String url){this.message=message;this.media=media;this.url=url;this.time=PairedProtocol.time(message);}}
    private static final class Shared{final List<SharedItem> photos=new ArrayList<>(),links=new ArrayList<>(),files=new ArrayList<>();boolean wrote;}
    /** Everything shared in a chat's synced history. A link sent more than once is listed once, at its newest. */
    private Shared sharedIn(String chat){Shared s=new Shared();JSONObject c=store.chat(chat);
        for(JSONObject m:store.mediaMessages(chat,600))for(JSONObject media:PairedProtocol.media(m)){String k=PairedProtocol.mediaKind(media);
            (k.equals("image")||k.equals("video")?s.photos:s.files).add(new SharedItem(m,media,null));}
        java.util.Set<String> seen=new HashSet<>();
        // A shared location's map link is a place, not a link someone sent.
        for(JSONObject m:store.linkMessages(chat,600)){if(PairedProtocol.location(m)!=null)continue;
            for(String url:ChatText.urls(PairedProtocol.text(m)))if(seen.add(url.toLowerCase(Locale.ROOT)))s.links.add(new SharedItem(m,null,url));}
        if(c!=null)for(JSONObject m:store.messages(chat,200))if(PairedProtocol.outgoing(m,c)){s.wrote=true;break;}
        return s;}
    private String sharedChat="";private Shared sharedData;private long sharedAt;private int sharedTab;
    /** Reads what's shared in the background (it goes through the whole chat) and hands it over on the main thread. */
    private void loadShared(String chat,java.util.function.Consumer<Shared> then){
        if(sharedData!=null&&sharedChat.equals(chat)&&System.currentTimeMillis()-sharedAt<30_000){then.accept(sharedData);return;}
        decoder.execute(()->{Shared s=sharedIn(chat);ui.post(()->{sharedChat=chat;sharedData=s;sharedAt=System.currentTimeMillis();then.accept(s);});});}
    /** In Details: the newest photos, then how many photos, links and files, each opening its list. */
    private void fillSharedSummary(LinearLayout box,Shared s){box.removeAllViews();gap(box,20);box.addView(text("Shared",14,muted,true));gap(box,6);
        if(s.photos.isEmpty()&&s.links.isEmpty()&&s.files.isEmpty()){TextView none=text("Nothing shared in the synced history yet.",15,muted,false);none.setPadding(dp(8),dp(8),dp(8),dp(8));box.addView(none);return;}
        if(!s.photos.isEmpty()){int cols=4,g=dp(4),tile=(getResources().getDisplayMetrics().widthPixels-dp(16)-g*(cols-1))/cols;LinearLayout strip=row();
            for(int i=0;i<Math.min(cols,s.photos.size());i++){LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(tile,tile);if(i>0)tl.setMarginStart(g);strip.addView(sharedTile(s.photos.get(i),tile),tl);}
            box.addView(strip);gap(box,4);}
        sharedRow(box,"Photos & videos",s.photos.size(),0);sharedRow(box,"Links",s.links.size(),1);sharedRow(box,"Files",s.files.size(),2);}
    private void sharedRow(LinearLayout box,String label,int count,int tab){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(8),dp(14),dp(8),dp(14));
        r.addView(text(label,17,ink,false),new LinearLayout.LayoutParams(0,-2,1));r.addView(text(count==0?"None":count+"  ›",15,muted,false));
        r.setContentDescription(label+", "+count);if(count>0){pressable(r);r.setOnClickListener(v->sharedScreen(tab));}box.addView(r);}
    private void mediaScreen(){sharedScreen(0);}
    /** Photos & videos, links, and files shared in this chat, in three tabs, newest first under month headings. */
    private void sharedScreen(int tab){JSONObject c=store.chat(chatId);if(c==null)return;String id=chatId;sharedTab=tab;base("media");header("Shared",this::details);
        LinearLayout tabs=row();tabs.setPadding(dp(12),0,dp(12),dp(8));root.addView(tabs);
        scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);items=vertical();items.setPadding(dp(8),0,dp(8),dp(32));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView wait=text("Loading…",15,muted,false);wait.setGravity(Gravity.CENTER);wait.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(wait);
        loadShared(id,s->{if(!screen.equals("media")||!chatId.equals(id))return;drawSharedTabs(tabs,s,c);drawShared(s,c);});}
    private void drawSharedTabs(LinearLayout tabs,Shared s,JSONObject c){tabs.removeAllViews();String[] names={"Photos & videos","Links","Files"};int[] counts={s.photos.size(),s.links.size(),s.files.size()};
        for(int k=0;k<3;k++){int which=k;boolean on=k==sharedTab;TextView chip=text(names[k]+"  "+counts[k],14,on?onFab:ink,on);chip.setPadding(dp(14),dp(8),dp(14),dp(8));chip.setBackground(shape(on?fab:surface,18));
            chip.setContentDescription(names[k]+", "+counts[k]+(on?", selected":""));pressable(chip);
            chip.setOnClickListener(v->{if(sharedTab==which)return;sharedTab=which;drawSharedTabs(tabs,s,c);drawShared(s,c);});
            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-2,-2);cl.setMarginEnd(dp(8));tabs.addView(chip,cl);}}
    private void drawShared(Shared s,JSONObject c){items.removeAllViews();scroll.scrollTo(0,0);List<SharedItem> list=sharedTab==0?s.photos:sharedTab==1?s.links:s.files;
        if(list.isEmpty()){TextView e=text(sharedTab==0?"No photos or videos in the synced history yet.":sharedTab==1?"No links in the synced history yet.":"No files or voice messages in the synced history yet.",15,muted,false);
            e.setGravity(Gravity.CENTER);e.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(e);return;}
        int cols=3,g=dp(4),tile=(getResources().getDisplayMetrics().widthPixels-dp(16)-g*(cols-1))/cols,previews=0;String month="";LinearLayout line=null;int inRow=0;
        // Link previews fetch the page, which tells its owner you looked: only where they're on and you know the people (as in the chat).
        boolean fetch=linkPreviewsOn()&&c!=null&&(PairedProtocol.allKnown(c)||s.wrote);
        for(SharedItem it:list){String m=monthLabel(it.time);if(!m.equals(month)){month=m;items.addView(sectionLabel(m));line=null;}
            if(sharedTab==0){if(line==null||inRow==cols){line=row();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=g;items.addView(line,lp);inRow=0;}
                LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(tile,tile);if(inRow>0)tl.setMarginStart(g);line.addView(sharedTile(it,tile),tl);inRow++;}
            else if(sharedTab==1)items.addView(linkRow(it,c,fetch&&previews++<30));
            else items.addView(fileRow(it,c));}}
    /** "November", or "November 2025" for another year. */
    private static String monthLabel(long time){java.util.Calendar now=java.util.Calendar.getInstance(),at=java.util.Calendar.getInstance();at.setTimeInMillis(time);
        String name=at.getDisplayName(java.util.Calendar.MONTH,java.util.Calendar.LONG,Locale.getDefault());
        return at.get(java.util.Calendar.YEAR)==now.get(java.util.Calendar.YEAR)?name:name+" "+at.get(java.util.Calendar.YEAR);}
    /** Who shared it and when: "You · Nov 8". */
    private String sharedBy(SharedItem it,JSONObject c){return (PairedProtocol.outgoing(it.message,c)?"You":senderFirst(it.message,c))+" · "+android.text.format.DateUtils.formatDateTime(this,it.time,android.text.format.DateUtils.FORMAT_SHOW_DATE|android.text.format.DateUtils.FORMAT_ABBREV_MONTH);}
    private View sharedTile(SharedItem it,int size){JSONObject media=it.media;boolean video=PairedProtocol.mediaKind(media).equals("video");FrameLayout box=new FrameLayout(this);box.setBackground(shape(surface,8));
        box.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(8));}});box.setClipToOutline(true);
        ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);box.addView(img,new FrameLayout.LayoutParams(-1,-1));
        if(video){TextView play=text("▶",16,Color.WHITE,false);play.setGravity(Gravity.CENTER);play.setBackground(oval(0x99000000));box.addView(play,new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER));}
        box.setContentDescription((video?"Video":"Photo")+". Hold for more.");pressable(box);box.setOnClickListener(v->openMedia(media));box.setOnLongClickListener(v->{sharedMenu(it);return true;});
        loadPreview(media,img,video,size,size);return box;}
    private View linkRow(SharedItem it,JSONObject c,boolean fetch){String url=it.url,host=ChatText.domain(url);LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(8),dp(10),dp(8),dp(10));pressable(r);
        FrameLayout icon=new FrameLayout(this);icon.setBackground(shape(surface,12));icon.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(12));}});icon.setClipToOutline(true);
        TextView letter=text(host.isEmpty()?"🔗":host.substring(0,1).toUpperCase(Locale.ROOT),18,muted,true);letter.setGravity(Gravity.CENTER);icon.addView(letter,new FrameLayout.LayoutParams(-1,-1));
        ImageView pic=new ImageView(this);pic.setScaleType(ImageView.ScaleType.CENTER_CROP);pic.setVisibility(View.GONE);icon.addView(pic,new FrameLayout.LayoutParams(-1,-1));r.addView(icon,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout texts=vertical();texts.setPadding(dp(14),0,0,0);TextView title=text(host.isEmpty()?url:host,15,ink,false);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);texts.addView(title);
        TextView where=text(url.replaceFirst("(?i)^https?://(www\\.)?",""),13,muted,false);where.setSingleLine(true);where.setEllipsize(TextUtils.TruncateAt.MIDDLE);texts.addView(where);
        texts.addView(text(sharedBy(it,c),12,muted,false));r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        LinkPreviews.Done fill=p->{String t=p.optString("title");if(!t.isEmpty())title.setText(t);String image=p.optString("image");
            if(image.isEmpty())return;File file=new File(image);Bitmap now=MediaCache.peekCover(file,dp(52),dp(52));if(now!=null){pic.setImageBitmap(now);pic.setVisibility(View.VISIBLE);return;}
            decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(file,dp(52),dp(52),false);if(b!=null)pic.post(()->{pic.setImageBitmap(b);pic.setVisibility(View.VISIBLE);});});};
        JSONObject ready=LinkPreviews.peekSaved(this,url);if(ready!=null){if(!ready.optString("title").isEmpty())fill.done(ready);}else if(fetch)LinkPreviews.load(this,url,fill);
        r.setContentDescription(host+". "+sharedBy(it,c)+". Hold for more.");r.setOnClickListener(v->openLink(url));r.setOnLongClickListener(v->{sharedMenu(it);return true;});return r;}
    private View fileRow(SharedItem it,JSONObject c){JSONObject media=it.media;String name=PairedProtocol.string(media,"mediaName"),kind=PairedProtocol.mediaKind(media);
        LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(8),dp(10),dp(8),dp(10));pressable(r);r.addView(roundIcon(kind.equals("audio")?R.drawable.ic_mic:R.drawable.ic_file,surface,ink,40));
        LinearLayout texts=vertical();texts.setPadding(dp(14),0,0,0);TextView n=text(name.isEmpty()?(kind.equals("audio")?"Voice message":"File"):name,15,ink,false);n.setSingleLine(true);n.setEllipsize(TextUtils.TruncateAt.MIDDLE);texts.addView(n);
        String sz=PairedProtocol.size(media.optLong("size"));texts.addView(text((sz.isEmpty()?"":sz+" · ")+sharedBy(it,c),13,muted,false));r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        r.setOnClickListener(v->openMedia(media));r.setOnLongClickListener(v->{sharedMenu(it);return true;});return r;}
    /** Hold a shared item: jump to it in the chat, or copy/share/save it. */
    private void sharedMenu(SharedItem it){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();JSONObject m=it.message;
        labels.add("Show in chat");actions.add(()->openMessage(m));
        if(it.url!=null){String url=it.url;labels.add("Copy link");actions.add(()->getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Link",url)));
            labels.add("Share link");actions.add(()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url),null)));}
        else{JSONObject media=it.media;String kind=PairedProtocol.mediaKind(media),what=kind.equals("image")?"photo":kind.equals("video")?"video":kind.equals("audio")?"audio":"file";
            labels.add("Save "+what+" to phone");actions.add(()->withFile(media,"Downloading…",file->saveToPhone(file,media)));
            labels.add("Share "+what);actions.add(()->withFile(media,"Downloading…",file->share(file,media)));}
        menuSheet(it.url!=null?ChatText.domain(it.url):"",labels,actions);}
    private void openLink(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("No browser is installed.");}}

    /** Inbox search also finds messages (not just chat names), searched in the background as you type. */
    private void renderMessageHits(){if(items==null)return;SmartText.Search f=SmartText.parseSearch(query);boolean filters=SmartText.hasFilters(f);
        if(f.text.trim().length()<2&&!filters){searchedFor="";searchHits.clear();return;}String q=query;
        if(!q.equals(searchedFor)){int g=++searchGeneration;decoder.execute(()->{List<JSONObject> hits=searchMessages(f,60);ui.post(()->{if(g!=searchGeneration)return;searchedFor=q;searchHits=hits;if(screen.equals("inbox")&&query.equals(q))renderInbox();});});return;}
        if(searchHits.isEmpty()){if(filters){TextView none=text("No messages match.",15,muted,false);none.setGravity(Gravity.CENTER);none.setPadding(dp(24),dp(32),dp(24),dp(24));items.addView(none);}return;}
        items.addView(sectionLabel("Messages"));for(JSONObject m:searchHits)items.addView(messageResult(m,true));}
    /** Words plus filters: from:name, has:photo, has:link, last:7d. */
    private List<JSONObject> searchMessages(SmartText.Search f,int max){long since=f.days>0?System.currentTimeMillis()-f.days*86_400_000L:0;
        List<JSONObject> candidates=f.text.trim().length()>=2?store.searchAll(f.text,2000):store.recent(4000,since);List<JSONObject> out=new ArrayList<>();
        if(f.text.trim().length()>=2)candidates=withPictureHits(candidates,f.text,"",2000);
        Map<String,JSONObject> chats=new HashMap<>();String from=f.from.toLowerCase(Locale.ROOT);
        for(JSONObject m:candidates){if(out.size()>=max)break;if(since>0&&PairedProtocol.time(m)<since)continue;
            if(f.photo){boolean pic=false;for(JSONObject a:PairedProtocol.media(m)){String k=PairedProtocol.mediaKind(a);if(k.equals("image")||k.equals("video"))pic=true;}if(!pic)continue;}
            if(f.link&&ChatText.firstUrl(PairedProtocol.text(m)).isEmpty())continue;
            if(!from.isEmpty()){String cid=m.optString("conversationID");JSONObject c=chats.computeIfAbsent(cid,store::chat);
                String who=PairedProtocol.outgoing(m,c)?"you":senderName(m,c).toLowerCase(Locale.ROOT);String chatName=c==null?"":PairedProtocol.title(c).toLowerCase(Locale.ROOT);
                if(!who.contains(from)&&!chatName.contains(from))continue;}
            if(needsReplyOnly){JSONObject conversation=chats.computeIfAbsent(m.optString("conversationID"),store::chat);if(conversation==null||!needsReply(conversation))continue;}
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
    // ---- Text them when I get there (see Arrivals and ArrivalPlan) ----
    private static final int ARRIVAL_PERMISSION=83,ARRIVAL_BACKGROUND=84;private String arrivalChat="";
    private String arrivalWho(String id){JSONObject c=store.chat(id);String t=c==null?"":PairedProtocol.title(c);return t.isEmpty()?"them":t;}
    /** Location permission (precise, then all the time), then where. Each step says why before Android asks. */
    private void arrivalText(String id){arrivalChat=id;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED&&!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)&&getSharedPreferences("paired",MODE_PRIVATE).getBoolean("asked_arrival",false)){
                toast("Turn on “Use precise location” for Message Center under Permissions › Location.");openAppSettings();return;}
            new AlertDialog.Builder(this).setTitle("Text them when you get there").setMessage("Pick a place, and when you arrive Message Center texts "+arrivalWho(id)+" for you. It needs your precise location to notice you've arrived. It only checks for the places you set up, and your location stays on this phone.")
                .setPositiveButton("Continue",(d,w)->{getSharedPreferences("paired",MODE_PRIVATE).edit().putBoolean("asked_arrival",true).apply();requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},ARRIVAL_PERMISSION);}).setNegativeButton("Not now",null).show();return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            new AlertDialog.Builder(this).setTitle("Allow location all the time").setMessage("You'll usually arrive with your phone in your pocket, so Message Center needs location “all the time” to notice. On the next screen, choose “Allow all the time”. It only checks for the places you set up here.")
                .setPositiveButton("Continue",(d,w)->requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},ARRIVAL_BACKGROUND)).setNegativeButton("Not now",null).show();return;}
        if(!getSystemService(android.location.LocationManager.class).isLocationEnabled()){toast("Turn on Location in your phone's settings first.");return;}
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(JSONObject p:Arrivals.places(this)){labels.add("📍 "+p.optString("name"));actions.add(()->arrivalDetails(id,p,-1));}
        labels.add("Where I am now…");actions.add(()->currentLocation(loc->{if(loc==null){toast("Couldn't find your location. Try again outside or with Wi-Fi on.");return;}namePlace(id,loc.getLatitude(),loc.getLongitude(),Arrivals.places(this).isEmpty()?"Home":"",0);}));
        labels.add("Search for an address…");actions.add(()->searchPlace(id));
        menuSheet("Text "+arrivalWho(id)+" when I arrive at…",labels,actions);}
    /** Your location now (or the last one Android knows), or null; on the main thread. */
    private void currentLocation(java.util.function.Consumer<android.location.Location> done){android.location.LocationManager lm=getSystemService(android.location.LocationManager.class);
        String provider=lm.hasProvider(android.location.LocationManager.FUSED_PROVIDER)&&lm.isProviderEnabled(android.location.LocationManager.FUSED_PROVIDER)?android.location.LocationManager.FUSED_PROVIDER
            :lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)?android.location.LocationManager.GPS_PROVIDER:android.location.LocationManager.NETWORK_PROVIDER;
        toast("Finding your location…");
        try{lm.getCurrentLocation(provider,null,getMainExecutor(),loc->{android.location.Location use=loc;
            if(use==null)for(String p:lm.getProviders(true)){try{android.location.Location last=lm.getLastKnownLocation(p);if(last!=null&&(use==null||last.getTime()>use.getTime()))use=last;}catch(SecurityException ignored){}}
            done.accept(use);});}
        catch(SecurityException|IllegalArgumentException e){done.accept(null);}}
    private void searchPlace(String id){EditText q=input("Address or place name");q.setSingleLine(true);FrameLayout box=new FrameLayout(this);box.setPadding(dp(20),dp(8),dp(20),0);box.addView(q);
        new AlertDialog.Builder(this).setTitle("Find a place").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Search",(d,w)->{String text=q.getText().toString().trim();if(text.isEmpty())return;
            if(!android.location.Geocoder.isPresent()){toast("Address search isn't available on this phone. Go there and choose “Where I am now”.");return;}
            toast("Searching…");new android.location.Geocoder(this).getFromLocationName(text,5,new android.location.Geocoder.GeocodeListener(){
                @Override public void onGeocode(List<android.location.Address> found){ui.post(()->{if(found.isEmpty()){toast("Nothing found for “"+text+"”.");return;}List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
                    for(android.location.Address a:found){String line=a.getMaxAddressLineIndex()>=0?a.getAddressLine(0):text;labels.add(line);String suggest=a.getFeatureName()!=null&&!a.getFeatureName().matches("\\d+")?a.getFeatureName():text;
                        actions.add(()->namePlace(id,a.getLatitude(),a.getLongitude(),suggest,-1));}
                    menuSheet("Which one?",labels,actions);});}
                @Override public void onError(String message){ui.post(()->toast("Address search didn't work. Check your connection, or go there and choose “Where I am now”."));}});}).show();q.requestFocus();}
    /** metersAway: how far you are from it now (0 when it's where you are), or -1 to find out. */
    private void namePlace(String id,double lat,double lng,String suggested,double metersAway){EditText name=input("Name it: Home, Work, Gym…");name.setSingleLine(true);name.setText(suggested);name.setSelection(name.getText().length());
        FrameLayout box=new FrameLayout(this);box.setPadding(dp(20),dp(8),dp(20),0);box.addView(name);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Save this place").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Next",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String n=name.getText().toString().trim();if(n.isEmpty()){name.setError("Give it a name");return;}
            dialog.dismiss();arrivalDetails(id,Arrivals.savePlace(this,n,lat,lng),metersAway);}));dialog.show();name.requestFocus();}
    /** When (arriving or leaving), what to say, once or every time, and an optional check-in time for arrivals. */
    private void arrivalDetails(String id,JSONObject place,double metersAway){String where=place.optString("name");LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),0);
        android.widget.RadioGroup when=new android.widget.RadioGroup(this);when.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.RadioButton arrive=new android.widget.RadioButton(this),leave=new android.widget.RadioButton(this);arrive.setText("When I arrive");leave.setText("When I leave");
        for(android.widget.RadioButton r:new android.widget.RadioButton[]{arrive,leave}){r.setId(View.generateViewId());r.setTextColor(ink);when.addView(r,new LinearLayout.LayoutParams(0,-2,1));}
        arrive.setChecked(true);box.addView(when);gap(box,6);
        EditText message=input("Message");message.setText(ArrivalPlan.defaultText(where));box.addView(message);
        android.widget.CheckBox every=new android.widget.CheckBox(this);every.setText("Every time");every.setTextColor(ink);LinearLayout.LayoutParams el=new LinearLayout.LayoutParams(-2,-2);el.topMargin=dp(8);box.addView(every,el);
        // Check-in: "if I'm not there by…", for a one-time arrival text.
        android.widget.CheckBox check=new android.widget.CheckBox(this);check.setText("If I'm not there by…");check.setTextColor(ink);box.addView(check);
        LinearLayout lateBox=vertical();lateBox.setVisibility(View.GONE);long[] deadline={0};
        TextView time=text("Pick a time",16,chatSettings.theme(id).accent(dark),true);time.setPadding(dp(4),dp(6),dp(4),dp(10));pressable(time);lateBox.addView(time);
        EditText late=input("Text if I'm late");late.setText(ArrivalPlan.defaultLateText(where));lateBox.addView(late);box.addView(lateBox);
        Runnable pickTime=()->{java.util.Calendar soon=java.util.Calendar.getInstance();soon.add(java.util.Calendar.HOUR_OF_DAY,1);
            new android.app.TimePickerDialog(this,(tp,h,m)->{deadline[0]=ArrivalPlan.deadline(System.currentTimeMillis(),java.util.TimeZone.getDefault(),h,m);
                time.setText("By "+android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(deadline[0]))+(deadline[0]-System.currentTimeMillis()>18*3_600_000L?" tomorrow":"")+" • change");},
                soon.get(java.util.Calendar.HOUR_OF_DAY),0,android.text.format.DateFormat.is24HourFormat(this)).show();};
        time.setOnClickListener(v->pickTime.run());
        check.setOnCheckedChangeListener((cb,on)->{lateBox.setVisibility(on?View.VISIBLE:View.GONE);if(on&&deadline[0]==0)pickTime.run();});
        TextView note=text("",13,muted,false);note.setPadding(dp(4),dp(4),dp(4),0);box.addView(note);
        String[] lastDefault={message.getText().toString()};
        Runnable sync=()->{boolean leaving=leave.isChecked(),repeat=every.isChecked();String fresh=leaving?ArrivalPlan.defaultLeaveText(where):ArrivalPlan.defaultText(where);
            // Swap the suggested words when switching, unless you've written your own.
            if(message.getText().toString().equals(lastDefault[0])){message.setText(fresh);lastDefault[0]=fresh;}
            every.setText(leaving?"Every time I leave here":"Every time I arrive here");boolean checkIn=!leaving&&!repeat;check.setVisibility(checkIn?View.VISIBLE:View.GONE);if(!checkIn)check.setChecked(false);
            note.setText((repeat?"Every time keeps going until you cancel it.":"Just once lasts a day.")+" Location stays on this phone; only the message is sent.");};
        when.setOnCheckedChangeListener((g,c2)->sync.run());every.setOnCheckedChangeListener((cb,on)->sync.run());sync.run();
        ScrollView sv=new ScrollView(this);sv.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Text "+arrivalWho(id)+" • "+where).setView(sv).setNegativeButton("Cancel",null).setPositiveButton("Turn on",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String text=message.getText().toString().trim();if(text.isEmpty()){message.setError("Write what to send");return;}
            boolean repeat=every.isChecked(),leaving=leave.isChecked(),checkIn=check.isChecked();String lateText=late.getText().toString().trim();
            if(checkIn&&deadline[0]<=System.currentTimeMillis()){toast("Pick the time you'll be there by.");pickTime.run();return;}
            if(checkIn&&lateText.isEmpty()){late.setError("Write what to send if you're late");return;}dialog.dismiss();
            java.util.function.Consumer<Double> start=away->{String state=ArrivalPlan.startState(away,ArrivalPlan.RADIUS_M,leaving);
                Arrivals.add(this,id,place,text,repeat,state,leaving,checkIn?deadline[0]:0,checkIn?lateText:"");String who=arrivalWho(id);
                toast(leaving?(ArrivalPlan.AWAY_FIRST.equals(state)?"It'll text "+who+" when you leave "+where+", after you get there.":"It'll text "+who+" when you leave "+where+".")
                    :ArrivalPlan.AWAY_FIRST.equals(state)?"You're there now, so it'll text "+who+" the next time you arrive.":"It'll text "+who+" when you arrive at "+where+(checkIn?", or at the check-in time if you're not there.":"."));
                if(screen.equals("chat"))renderMessages(false);};
            if(metersAway>=0)start.accept(metersAway);
            else currentLocation(loc->start.accept(loc==null?-1:ArrivalPlan.meters(loc.getLatitude(),loc.getLongitude(),place.optDouble("lat"),place.optDouble("lng"))));}));
        dialog.show();}
    private void renderArrivals(ChatTheme theme){for(JSONObject a:Arrivals.forChat(this,chatId)){String id=a.optString("id");gap(items,8);
        LinearLayout line=row();line.setGravity(Gravity.END);TextView b=text(a.optString("text"),17,theme.onBubble(dark),false);b.setPadding(dp(16),dp(11),dp(16),dp(11));
        b.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.setBackground(shape(theme.bubble(dark),22));b.setAlpha(0.55f);line.addView(b);items.addView(line);
        boolean away=ArrivalPlan.AWAY_FIRST.equals(a.optString("state")),leaving=Arrivals.leaving(a);String place=a.optString("place");
        String when=leaving?"leave "+place+(away?" (after you get there)":""):(away?"next ":"")+"arrive at "+place;
        String checkIn=a.optLong("deadline")<=0?"":a.optBoolean("lateSent")?" • late text sent":a.optLong("arrivedAt")>0?"":" • if you're not there by "+android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(a.optLong("deadline")))+": “"+a.optString("lateText")+"”";
        TextView s=text("🏁 Sends when you "+when+(a.optBoolean("everyTime")?" • every time":"")+checkIn+" • tap for options",12,muted,false);s.setGravity(Gravity.END);s.setPadding(0,dp(4),dp(6),0);items.addView(s,new LinearLayout.LayoutParams(-1,-2));
        View.OnClickListener act=v->menuSheet("Text on arrival",java.util.Arrays.asList("Cancel it"),java.util.Arrays.<Runnable>asList(()->{Arrivals.cancel(this,id);renderMessages(false);}));
        b.setOnClickListener(act);s.setOnClickListener(act);}}
    /** Settings: what's watching, and the places you've saved. */
    private void arrivalSettings(){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(JSONObject a:Arrivals.all(this)){String id=a.optString("id");labels.add("🏁 "+arrivalWho(a.optString("chat"))+(Arrivals.leaving(a)?" when you leave ":" at ")+a.optString("place")+(a.optBoolean("everyTime")?" (every time)":"")+(a.optLong("deadline")>0?" (with check-in)":"")+" • tap to cancel");actions.add(()->{Arrivals.cancel(this,id);toast("Canceled.");if(screen.equals("settings"))settings();});}
        for(JSONObject p:Arrivals.places(this)){String n=p.optString("name");labels.add("📍 Forget "+n);actions.add(()->{Arrivals.removePlace(this,n);toast("Forgot "+n+".");if(screen.equals("settings"))settings();});}
        if(labels.isEmpty()){toast("Set one up from a chat: ⊕ › Text on arrival.");return;}menuSheet("Text on arrival",labels,actions);}
    private void renderScheduled(ChatTheme theme){for(JSONObject o:Scheduled.forChat(this,chatId)){String id=o.optString("id");gap(items,8);
        LinearLayout line=row();line.setGravity(Gravity.END);TextView b=text(o.optString("text"),17,theme.onBubble(dark),false);b.setPadding(dp(16),dp(11),dp(16),dp(11));
        b.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.setBackground(shape(theme.bubble(dark),22));b.setAlpha(0.55f);line.addView(b);items.addView(line);
        TextView s=text("🕒 Scheduled for "+separatorStamp(o.optLong("at"))+" • tap for options",12,muted,false);s.setGravity(Gravity.END);s.setPadding(0,dp(4),dp(6),0);items.addView(s,new LinearLayout.LayoutParams(-1,-2));
        View.OnClickListener act=v->menuSheet("Scheduled message",java.util.Arrays.asList("Send now","Delete"),java.util.Arrays.<Runnable>asList(()->{Scheduled.sendNow(this,id);renderMessages(false);},()->{Scheduled.remove(this,id);renderMessages(false);}));
        b.setOnClickListener(act);s.setOnClickListener(act);}}
    // ---- 1.0: promotions, unsubscribe, old codes, smart taps, quick replies, @ names, location, keywords, auto-reply, search ----
    private boolean promoOnly;private LinearLayout searchChips,quickRow;private HorizontalScrollView quickScroll;
    private boolean unreadCountOn(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("inbox_unread_count",true);}
    private TextView unreadBadge;
    /**
     * Messages waiting to be read across the inbox: for each unread conversation, what they've sent since
     * you last read it (at least one). Promotions (with their own count) and archived chats aren't included.
     */
    private int inboxUnread(){int total=0;boolean promos=promotionsSeparate();
        for(JSONObject c:store.chats("",1)){String id=c.optString("conversationID");if(pendingDeletes.contains(id)||!chatSettings.unread(c)||(promos&&promotion(c)))continue;
            total+=Math.max(1,store.incomingSince(id,chatSettings.seen(id),99));}
        return total;}
    /** The count beside Messages: on the main inbox list only, when there's something unread and it isn't turned off. */
    private void updateUnreadBadge(){if(unreadBadge==null)return;boolean main=screen.equals("inbox")&&folder==1&&!unknownOnly&&!promoOnly&&!needsReplyOnly;
        int n=main&&unreadCountOn()?inboxUnread():0;unreadBadge.setVisibility(n>0?View.VISIBLE:View.GONE);
        if(n>0){unreadBadge.setText(n>99?"99+":String.valueOf(n));unreadBadge.setContentDescription(n+(n==1?" unread message":" unread messages"));}}
    private boolean promotionsSeparate(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("promotions_separate",true);}
    /** A one-to-one chat with a short code or business name that isn't a saved contact (a fresh verification code stays in the inbox). */
    private boolean promotion(JSONObject c){if(chatSettings.pinned(c))return false;JSONObject other=PairedProtocol.other(c);if(other==null||PairedProtocol.known(other))return false;
        if(!SmartText.shortCodeSender(PairedProtocol.number(other)))return false;
        JSONObject last=latestStored(c.optString("conversationID"));return last==null||TextScan.code(PairedProtocol.text(last)).isEmpty()||System.currentTimeMillis()-PairedProtocol.time(last)>3_600_000L;}
    private View promotionsRow(List<JSONObject> promos){int unread=0;for(JSONObject c:promos)if(chatSettings.unread(c))unread++;
        LinearLayout entry=row();entry.setPadding(dp(4),dp(9),dp(4),dp(9));entry.setGravity(Gravity.CENTER_VERTICAL);pressable(entry);
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
            openCalendar(add);return;}
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
            for(JSONObject p:PairedProtocol.members(c)){String first=PairedProtocol.firstName(p);if(first.isEmpty()||!first.toLowerCase(Locale.ROOT).startsWith(part))continue;
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
    /** Offers to put the Busy tile in Quick Settings (Android asks to confirm). */
    private void addBusyTile(){android.app.StatusBarManager bar=getSystemService(android.app.StatusBarManager.class);
        if(bar==null){toast("Pull down Quick Settings, tap the pencil to edit, and drag in the Busy tile.");return;}
        bar.requestAddTileService(new android.content.ComponentName(this,BusyTile.class),"Busy",android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_chat),getMainExecutor(),result->{
            if(result==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)toast("The Busy tile is already in Quick Settings.");
            else if(result==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)toast("Busy tile added. Pull down Quick Settings to use it.");
            else if(result!=android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)toast("Pull down Quick Settings, tap the pencil to edit, and drag in the Busy tile.");});}
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
        folder=1;unknownOnly=false;promoOnly=false;needsReplyOnly=false;inbox();}
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
        for(int i=0;i<labels.length;i++){Runnable a=actions[i];TextView b=text(labels[i],14,labels[i].equals("Delete")?danger:ink,true);b.setPadding(dp(10),dp(10),dp(10),dp(10));pressable(b);b.setOnClickListener(v->a.run());bar.addView(b);}
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);lp.setMargins(dp(8),dp(4),dp(8),0);chatFrame.addView(bar,lp);selectionBar=bar;}
    private void copySelected(List<JSONObject> list){JSONObject c=store.chat(chatId);StringBuilder b=new StringBuilder();List<JSONObject> media=new ArrayList<>();
        for(JSONObject m:list){media.addAll(PairedProtocol.media(m));String body=copyBody(m);if(body.isEmpty())continue;if(b.length()>0)b.append('\n');if(list.size()>1)b.append(PairedProtocol.outgoing(m,c)?"You":senderName(m,c)).append(": ");b.append(body);}
        stopSelecting();copyToClipboard(b.toString(),media);}
    /** What a message says, without "Photo"-style labels for attachments (those are copied as the files themselves). */
    private static String copyBody(JSONObject m){String typed=PairedProtocol.text(m),quoted=ChatText.replyFallbackBody(typed);String t=quoted!=null?quoted:typed;
        JSONObject place=PairedProtocol.location(m);if(place!=null&&!place.optString("text").isEmpty())t=t.isEmpty()?place.optString("text"):t+"\n"+place.optString("text");
        return t.isEmpty()&&PairedProtocol.media(m).isEmpty()?displayBody(m):t;}
    /**
     * Puts text and attachments on the clipboard together: text first (for apps that only paste
     * text), then each photo or video as a file other apps can paste. Downloads any that aren't
     * here yet; iPhone HEIC pictures (Genmoji) are copied as PNG, which every app can paste.
     */
    private void copyToClipboard(String text,List<JSONObject> media){
        if(media.isEmpty()){if(text.isEmpty())return;getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Message",text));return;}
        boolean[] cancelled={false};AlertDialog[] wait={null};
        boolean missing=false;for(JSONObject a:media)if(!MediaCache.file(this,a,false).isFile())missing=true;
        if(missing&&!PairedService.ready()){toast("Connect to Google Messages to download the attachment first.");return;}
        if(missing){ProgressBar spinner=new ProgressBar(this);spinner.setPadding(0,dp(16),0,dp(16));
            wait[0]=new AlertDialog.Builder(this).setMessage(media.size()==1?"Getting the attachment…":"Getting "+media.size()+" attachments…").setView(spinner).setNegativeButton("Cancel",(d,w)->cancelled[0]=true).show();}
        File[] files=new File[media.size()];int[] left={media.size()};
        Runnable finish=()->{if(wait[0]!=null)wait[0].dismiss();if(cancelled[0])return;
            decoder.execute(()->{List<Uri> uris=new ArrayList<>();for(int i=0;i<files.length;i++)if(files[i]!=null){Uri u=clipUri(files[i],media.get(i));if(u!=null)uris.add(u);}
                ui.post(()->{if(uris.isEmpty()&&text.isEmpty()){toast("The attachment couldn't be downloaded.");return;}
                    android.content.ContentResolver cr=getContentResolver();ClipData clip=text.isEmpty()?null:ClipData.newPlainText("Message",text);
                    for(Uri u:uris){if(clip==null)clip=ClipData.newUri(cr,"Message attachment",u);else clip.addItem(cr,new ClipData.Item(u));}
                    getSystemService(ClipboardManager.class).setPrimaryClip(clip);
                    if(uris.size()<media.size())toast((media.size()-uris.size())+" attachment"+(media.size()-uris.size()==1?"":"s")+" couldn't be copied.");});});};
        for(int i=0;i<media.size();i++){int at=i;MediaCache.fetch(this,media.get(i),false,(file,err)->{files[at]=file;if(--left[0]==0)finish.run();});}
    }
    /** A link to an attachment for the clipboard; HEIC pictures become a PNG copy (keeping transparency). */
    private Uri clipUri(File file,JSONObject media){
        if(PairedProtocol.mediaKind(media).equals("image")&&HeifDecoder.heif(file)){
            String base=displayName(media).replaceAll("\\.[^.]*$","");File png=new File(MediaCache.dir(this),"clip-"+file.getName().replaceAll("\\.[^.]*$","")+".png");
            if(!png.isFile()){Bitmap b=HeifDecoder.decode(file,4096);if(b==null)b=MediaCache.decode(file,2560,false);
                if(b!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(png)){b.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){png.delete();}}
            if(png.isFile())return MediaProvider.uri(png,base+".png");
        }
        return shareUri(file,media);}
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
    /**
     * Background, card, surface (incoming bubbles, fields), text and secondary text. Black is for
     * OLED screens: true black (those pixels switch off), soft white text so it doesn't glow, and
     * very dark gray bubbles so they still read as bubbles.
     */
    static int[] palette(boolean dark,boolean black){
        if(black)return new int[]{0xff000000,0xff121214,0xff1c1c1e,0xfff2f2f4,0xff98989f};
        return dark?new int[]{0xff111318,0xff1d2026,0xff23252b,0xffe3e2e9,0xffabaeb8}:new int[]{0xfff8f9fc,Color.WHITE,0xffe9edf4,0xff1a1b20,0xff5d5f67};}
    private void appTheme(String mode){getSharedPreferences("paired",MODE_PRIVATE).edit().putString("theme",mode).apply();applyThemeSetting(this);}
    static void applyThemeSetting(Context context){String mode=context.getSharedPreferences("paired",MODE_PRIVATE).getString("theme","system");
        context.getSystemService(android.app.UiModeManager.class).setApplicationNightMode(mode.equals("light")?android.app.UiModeManager.MODE_NIGHT_NO:mode.equals("dark")||mode.equals("black")?android.app.UiModeManager.MODE_NIGHT_YES:android.app.UiModeManager.MODE_NIGHT_AUTO);}
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
                for(JSONObject m:list){String event=PairedProtocol.event(m,c);if(event.isEmpty()&&PairedProtocol.notice(m))continue;String body=event.isEmpty()?displayBody(m):"— "+event+" —";if(body.isEmpty())continue;
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
            .setPositiveButton("Save",(d,w)->{Nicknames.set(this,number,e.getText().toString());rowCache.clear();MessagesWidget.update(this);if(screen.equals("details"))details();else if(screen.equals("inbox"))renderInbox();})
            .setNegativeButton("Cancel",null).show();}
    // ---- 0.8: undo send, undo delete, voice speed ----
    /** Seconds a sent message waits so it can be taken back (Settings → Undo send); 0 turns it off. */
    private static final int UNDO_SEND_MAX=300;
    private boolean undoSendOn(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("undo_send",true);}
    private int undoSendSeconds(){return Math.max(1,Math.min(UNDO_SEND_MAX,getSharedPreferences("paired",MODE_PRIVATE).getInt("undo_send_seconds",5)));}
    private long undoSendMs(){return undoSendSeconds()*1000L;}
    private void chooseUndoSend(){EditText e=input("Seconds");e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(undoSendOn()?undoSendSeconds():0));e.selectAll();
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Undo send").setMessage("Seconds a message waits after you tap send, so you can tap it to take it back. 0 turns it off (up to "+UNDO_SEND_MAX+"). It still sends if you close the app.").setView(e)
            .setPositiveButton("Save",(x,w)->{int n;try{n=Integer.parseInt(e.getText().toString().trim());}catch(NumberFormatException bad){toast("Enter a number of seconds.");return;}
                if(n<0||n>UNDO_SEND_MAX){toast("Enter 0 to "+UNDO_SEND_MAX+" seconds.");return;}
                android.content.SharedPreferences.Editor edit=getSharedPreferences("paired",MODE_PRIVATE).edit();if(n==0)edit.putBoolean("undo_send",false);else edit.putBoolean("undo_send",true).putInt("undo_send_seconds",n);
                edit.apply();if(screen.equals("settings"))settings();})
            .setNegativeButton("Cancel",null).create();
        d.setOnShowListener(x->{e.requestFocus();if(d.getWindow()!=null)d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});d.show();}
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
        chip.setText(speedLabel(next));if(player!=null&&!playingId.isEmpty()){try{boolean was=player.isPlaying();player.setPlaybackParams(player.getPlaybackParams().setSpeed(next));if(!was)player.pause();}catch(Exception ignored){}}}
    private static String speedLabel(float s){return s==1f?"1×":s==1.5f?"1.5×":"2×";}
    // ---- 0.8: unknown senders, snooze, mention alerts, safe sharing ----
    private boolean unknownOnly,chatTrusted=true;
    /** A one-to-one chat with a number that isn't saved in your contacts. */
    private boolean unknownSender(JSONObject c){JSONObject other=PairedProtocol.other(c);return other!=null&&!PairedProtocol.known(other);}
    /** Only content from other apps: never a raw file path or this app's own storage (another app could point a share at it). */
    private boolean safeIncoming(Uri u){if(u==null)return false;String scheme=u.getScheme(),auth=u.getAuthority();
        if(!"content".equals(scheme))return false;return auth==null||!auth.startsWith(getPackageName());}
    private String snoozeLabel(String id){long until=chatSettings.snoozedUntil(id);if(chatSettings.untilQuiet(id))return "it's quiet";
        boolean today=android.text.format.DateUtils.isToday(until);return (today?"":new SimpleDateFormat("EEE ",Locale.getDefault()).format(new Date(until)))+timeOnly(until);}
    /** Pause this chat's notifications for a while. @-mentions of you still come through. */
    private void snoozeMenu(String id){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();long now=System.currentTimeMillis();
        java.util.Calendar morning=java.util.Calendar.getInstance();morning.add(java.util.Calendar.DAY_OF_YEAR,1);morning.set(java.util.Calendar.HOUR_OF_DAY,8);morning.set(java.util.Calendar.MINUTE,0);morning.set(java.util.Calendar.SECOND,0);
        // Until it's quiet: off while the chat keeps going, back on by itself after half an hour without new messages.
        labels.add("Until it's quiet (30 minutes without messages)");actions.add(()->{chatSettings.snoozeUntilQuiet(id);if(!bubble())PairedService.clearNotification(this,id);
            toast("Quiet until this chat has no new messages for 30 minutes. @-mentions still notify you.");refreshAfterSetting();});
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
    /** "Transcribing… 40%" labels on screen, updated without redrawing the chat. */
    private final Map<String,TextView> transcribingLabels=new HashMap<>();private final Map<String,String> transcribeProgress=new HashMap<>();
    /** Under a voice message: a Transcribe link, "Transcribing…", or the saved transcript. */
    private View transcriptView(JSONObject m,JSONObject media,boolean outgoing,ChatTheme theme){String id=PairedProtocol.string(media,"mediaID"),saved=Transcriber.saved(this,id);
        LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);line.setPadding(dp(14),dp(5),dp(14),0);TextView t;
        if(saved!=null){t=text(saved,14,ink,false);t.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.ITALIC));t.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));
            t.setGravity(outgoing?Gravity.END:Gravity.START);t.setContentDescription("Transcript: "+saved);t.setOnLongClickListener(v->{messageSheet(m);return true;});}
        else if(transcribing.contains(id)){t=text("Transcribing…"+transcribeProgress.getOrDefault(id,""),13,muted,false);transcribingLabels.put(id,t);}
        else if(id.isEmpty())return line;
        else {t=text("Transcribe",14,theme.accent(dark),true);t.setPadding(0,dp(2),0,dp(2));t.setContentDescription("Transcribe this voice message");t.setOnClickListener(v->transcribe(media));}
        line.addView(t);return line;}
    private void transcribe(JSONObject media){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){pendingTranscription=media;
            toast("Android's speech recognizer needs microphone access, even to read a message. Nothing is recorded.");requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},78);return;}
        String id=PairedProtocol.string(media,"mediaID");if(id.isEmpty()||transcribing.contains(id))return;
        // Names from this chat help the recognizer spell them (and hear them at all).
        List<String> names=new ArrayList<>();JSONObject c=store.chat(chatId);
        if(c!=null)for(JSONObject p:PairedProtocol.members(c)){String n=PairedProtocol.name(p);if(!n.isEmpty()&&!n.startsWith("+")){names.add(n);String first=n.split("\\s+")[0];if(!names.contains(first))names.add(first);}
            String nick=Nicknames.get(PairedProtocol.number(p));if(!nick.isEmpty()&&!names.contains(nick))names.add(nick);}
        withFile(media,"Downloading…",file->{transcribing.add(id);renderMessages(false);
            Transcriber.transcribe(this,file,id,names,resumed,(done,total)->{String at=total>1?" "+Math.round(100f*done/total)+"%":"";transcribeProgress.put(id,at);
                TextView l=transcribingLabels.get(id);if(l!=null&&l.isAttachedToWindow())l.setText("Transcribing…"+at);},
                (text,err)->{transcribing.remove(id);transcribeProgress.remove(id);transcribingLabels.remove(id);renderMessages(false);if(!err.isEmpty())error(err);});});}

    // ---- 0.6: reliability, outbox, contacts, management, search, voice, sharing, links ----
    private Outbox outbox;
    private JSONObject contacts;private boolean contactsFailed;private List<PhoneContacts.Entry> phoneContacts;private List<Person> chatPeople;private boolean groupMode;private String contactQuery="";private final Map<String,String> picked=new LinkedHashMap<>();
    private final List<Outgoing> incoming=new ArrayList<>();private String incomingText;
    private MediaRecorder recorder;private File recording;private long recordStart;private LinearLayout recordBar,composeRow;private ImageView sendButton;private TextView recordClock;private Waveform recordWave;private View recordDot;
    private final Runnable recordTick=this::updateRecordClock;


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
    private void renderOutbox(ChatTheme theme,List<JSONObject> queued){
        for(JSONObject o:queued){String state=o.optString("state");gap(items,8);
            LinearLayout line=row();line.setGravity(Gravity.END);TextView b=text(o.optString("text"),17,theme.onBubble(dark),false);b.setPadding(dp(16),dp(11),dp(16),dp(11));
            b.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.setBackground(shape(theme.bubble(dark),22));b.setAlpha(0.7f);line.addView(b);items.addView(line);
            String oid=o.optString("id");motion(line,"o:"+oid,LIFT,motionOutbox.add(oid)&&motionReady);
            // What it's waiting for, in plain words: the signal (it couldn't reach Google), the reconnect, or Google confirming delivery.
            String label=Outbox.label(o,PairedService.ready(),PairedService.status(this),System.currentTimeMillis());boolean act=state.equals(Outbox.FAILED)||label.startsWith("Not confirmed");
            TextView s=text(label,12,act?danger:muted,state.equals(Outbox.FAILED));s.setGravity(Gravity.END);s.setPadding(0,dp(4),dp(6),0);items.addView(s,new LinearLayout.LayoutParams(-1,-2));
            View.OnClickListener open=v->outboxOptions(o);b.setOnClickListener(open);s.setOnClickListener(open);}}
    private void outboxOptions(JSONObject o){String id=o.optString("id"),state=o.optString("state");if(state.equals(Outbox.SENDING)||state.equals(Outbox.SENT))return;
        if(state.equals(Outbox.HOLD)){undoSend(id);return;}
        String msg=state.equals(Outbox.UNSURE)?"Google has this message but hasn't confirmed it was delivered. That usually happens by itself once the signal's back. Send it again only if you're sure it didn't go, or they may get it twice."
            :state.equals(Outbox.WAITING)&&o.optInt("tries")>0?"It couldn't reach Google (weak or no signal), so it hasn't gone anywhere. It sends by itself when the connection's back."
            :state.equals(Outbox.WAITING)?"It sends by itself as soon as Message Center is connected.":o.optString("error");
        new AlertDialog.Builder(this).setMessage(msg.isEmpty()?"This message hasn't been sent yet.":msg).setPositiveButton(state.equals(Outbox.WAITING)?"Send now":"Send again",(d,w)->PairedService.retryOutbox(this,id))
            .setNegativeButton("Delete",(d,w)->{outbox.remove(id);renderMessages(false);}).setNeutralButton("Cancel",null).show();}
    /** A message Google reports as failed: send its text again, then remove the failed copy. */
    /** Why Google Messages says a message didn't send, in a sentence. */
    private static String whyNotSent(JSONObject m){return switch(PairedProtocol.status(m)){
        case "OUTGOING_FAILED_TOO_LARGE"->"It was too large to send.";
        case "OUTGOING_FAILED_RECIPIENT_LOST_RCS"->"They can't get RCS messages right now.";
        case "OUTGOING_FAILED_EMERGENCY_NUMBER"->"Messages to emergency numbers can't be sent from here.";
        case "OUTGOING_CANCELED"->"It was canceled.";
        default->"Google Messages on your phone couldn't send it.";};}
    private void retryFailed(JSONObject m){PendingMedia attached=echoFor(m.optString("messageID"));if(attached!=null){pendingOptions(attached);return;}
        String text=PairedProtocol.text(m);List<JSONObject> media=PairedProtocol.media(m);
        if(!media.isEmpty()){retryFailedMedia(m,media,text);return;}
        boolean waiting=new AutoResend(this).pending(m.optString("messageID"));
        new AlertDialog.Builder(this).setMessage(waiting?"Your phone couldn't send this (usually no cell signal). It goes again by itself once when the signal's back, or you can send it now.":"Send this message again?").setPositiveButton("Send again",(d,w)->{
            // Handled by you now: it won't also go again by itself.
            new AutoResend(this).cancel(m.optString("messageID"));
            PairedService.sendOrQueue(this,chatId,text,"",(v,e)->{if(!e.isEmpty()){error(e);return;}
                String mid=m.optString("messageID");if(!mid.isEmpty()&&!"queued".equals(v))PairedService.execute(c->{c.deleteMessage(mid);return "";},(x,err)->{if(err.isEmpty())store.deleteMessage(mid);loadMessages(false);});
                else loadMessages(false);});}).setNegativeButton("Cancel",null).show();}

    /**
     * Sends a failed photo (or file) again: it's fetched back, made to fit a picture message if it
     * was too large (or the chat is SMS/MMS), sent like a new one, and the failed copy removed.
     */
    private void retryFailedMedia(JSONObject m,List<JSONObject> media,String caption){boolean tooBig=PairedProtocol.status(m).equals("OUTGOING_FAILED_TOO_LARGE");String id=chatId;
        new AlertDialog.Builder(this).setTitle("Not sent").setMessage(whyNotSent(m)+(tooBig?" It will be sent again smaller.":""))
            .setPositiveButton("Send again",(d,w)->{JSONObject c=store.chat(id);if(c==null)return;Outgoing[] parts=new Outgoing[media.size()];int[] left={media.size()};
                for(int i=0;i<media.size();i++){JSONObject part=media.get(i);int at=i;
                    withFile(part,"Getting it ready…",file->{try{File dir=new File(getCacheDir(),"outgoing");if(!dir.isDirectory())dir.mkdirs();
                            String name=PairedProtocol.string(part,"mediaName");if(name.isEmpty())name="attachment";File copy=new File(dir,java.util.UUID.randomUUID()+"-"+name.replaceAll("[^A-Za-z0-9._-]","_"));
                            java.nio.file.Files.copy(file.toPath(),copy.toPath());String mime=PairedProtocol.string(part,"mimeType");
                            Outgoing o=new Outgoing(copy,name,mime.isEmpty()?"application/octet-stream":mime);o.small=tooBig;parts[at]=o;}catch(Exception e){error("That attachment couldn't be read to send again.");}
                        if(--left[0]==0){List<Outgoing> batch=new ArrayList<>();for(Outgoing o:parts)if(o!=null)batch.add(o);
                            // In their original order; the failed copy is removed once the new one is accepted.
                            if(!batch.isEmpty())queueAttachments(c,batch,caption,"",m.optString("messageID"),false);}});}})
            .setNegativeButton("Cancel",null).show();}

    /** What this chat's notifications show, apart from the setting for all chats: e.g. just "New message" for one chat. */
    private void chatNotificationContent(String id){String all=NotificationPrivacy.label(NotificationPrivacy.mode(this)).toLowerCase(Locale.ROOT);
        List<String> labels=java.util.Arrays.asList("Same as all chats ("+all+")","Name and message","Name only","Nothing (just “New message”)");String[] values={"",NotificationPrivacy.ALL,NotificationPrivacy.NAME,NotificationPrivacy.NONE};
        List<Runnable> actions=new ArrayList<>();
        for(String v:values)actions.add(()->{NotificationPrivacy.setOwn(this,id,v);PairedService.refreshChatNotifications(this);
            if(v.equals(NotificationPrivacy.NONE))toast("Notifications from this chat will just say “New message”.");if(screen.equals("details"))details();});
        menuSheet("This chat's notifications show",labels,actions);}

    // Contacts and new conversations
    private void newMessage(){if(!PairedService.ready()){toast("Connect first, then start a chat.");return;}picked.clear();groupMode=false;contactQuery="";chatPeople=null;knownNames=null;newChat();
        loadPhoneContacts();if(contacts==null)loadContacts();}
    /** Google Messages' contacts: its "frequent" list, and the first few hundred of the rest. */
    private void loadContacts(){contactsFailed=false;PairedService.execute(c->c.contacts(),(raw,e)->{if(!e.isEmpty()){contactsFailed=true;if(screen.equals("newchat"))renderContacts();return;}
        try{contacts=new JSONObject(raw);}catch(Exception ex){contactsFailed=true;}knownNames=null;if(screen.equals("newchat"))renderContacts();});}
    /** This phone's contacts (everyone, unlike Google's list), read in the background each time you start a conversation. */
    private void loadPhoneContacts(){if(!PhoneContacts.allowed(this))return;decoder.execute(()->{List<PhoneContacts.Entry> all=PhoneContacts.all(this);
        ui.post(()->{phoneContacts=all;knownNames=null;if(screen.equals("newchat"))renderContacts();});});}
    private void newChat(){remember();base("newchat");header(groupMode?"New group":"New conversation",()->{if(groupMode){groupMode=false;picked.clear();newChat();}else inbox();});
        EditText search=input(groupMode?"Add people by name or number":"Type a name or phone number");search.setSingleLine(true);search.setText(contactQuery);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.setMargins(dp(16),0,dp(16),dp(8));root.addView(search,sp);
        scroll=new ScrollView(this);items=vertical();items.setPadding(dp(8),0,dp(8),dp(96));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(groupMode){TextView next=text(picked.size()<2?"Pick at least 2 people":"Next • "+picked.size()+" people",16,picked.size()<2?muted:onFab,true);next.setGravity(Gravity.CENTER);
            next.setPadding(0,dp(14),0,dp(14));next.setBackground(shape(picked.size()<2?surface:fab,18));next.setOnClickListener(v->{if(picked.size()>=2)nameGroup();});
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(dp(16),dp(4),dp(16),dp(12));root.addView(next,np);}
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){contactQuery=s.toString();renderContacts();}public void afterTextChanged(Editable e){}});
        renderContacts();search.requestFocus();}
    /** Someone you can start a conversation with: from your phone's contacts, Google's list, or a chat you're in. */
    private static final class Person{final String name,number,formatted,hex,folded,digits;
        Person(String name,String number,String formatted,String hex){this.name=name;this.number=number;this.formatted=formatted;this.hex=hex;folded=ContactSearch.fold(name);digits=ChatText.digits(number);}}
    private static final int CONTACTS_PERMISSION=81,CALL_LOG_PERMISSION=97;
    // ---- Calls in chats: a quiet line for each call with the person, from the phone's call log (never sent anywhere) ----
    /** Calls with the person in the open chat (callsChat), newest last. */
    private List<JSONObject> chatCalls=new ArrayList<>();private String callsChat="",callsSeen="";private android.database.ContentObserver callWatch;
    private boolean callsOn(){return getSharedPreferences("paired",MODE_PRIVATE).getBoolean("show_calls",true)&&checkSelfPermission(Manifest.permission.READ_CALL_LOG)==PackageManager.PERMISSION_GRANTED;}
    /** Reads this chat's calls from the call log in the background, then redraws the chat if they changed. One-on-one chats only. */
    private void loadCalls(String chat){if(!chat.equals(callsChat)){chatCalls=new ArrayList<>();callsChat=chat;callsSeen="";}
        JSONObject c=store.chat(chat);String number=c==null||PairedProtocol.other(c)==null?"":PairedProtocol.number(PairedProtocol.other(c));if(!callsOn()||number.isEmpty())return;
        decoder.execute(()->{List<JSONObject> found=new ArrayList<>();StringBuilder key=new StringBuilder();
            try(android.database.Cursor q=getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI,new String[]{android.provider.CallLog.Calls._ID,android.provider.CallLog.Calls.NUMBER,android.provider.CallLog.Calls.DATE,
                    android.provider.CallLog.Calls.DURATION,android.provider.CallLog.Calls.TYPE,android.provider.CallLog.Calls.FEATURES},null,null,android.provider.CallLog.Calls.DATE+" DESC")){
                int read=0;while(q!=null&&q.moveToNext()&&read++<3000&&found.size()<300){String n=q.getString(1);if(!CallLines.sameNumber(n,number))continue;
                    found.add(0,CallLines.item(q.getLong(0),q.getLong(2),q.getInt(4),q.getLong(3),(q.getInt(5)&android.provider.CallLog.Calls.FEATURES_VIDEO)!=0,n));key.append(q.getLong(0)).append(':').append(q.getLong(3)).append(',');}}
            catch(Exception e){Diagnostics.note("Couldn't read the call log");}
            String k=key.toString();runOnUiThread(()->{if(!chat.equals(callsChat)||k.equals(callsSeen))return;chatCalls=found;callsSeen=k;if(screen.equals("chat")&&chat.equals(chatId))renderMessages(false);});});}
    /** Messages with calls slotted in where they happened (calls before the oldest message drawn are left out). */
    static List<JSONObject> withCalls(List<JSONObject> messages,List<JSONObject> calls,long from){List<JSONObject> out=new ArrayList<>(messages.size()+calls.size());int j=0;
        List<JSONObject> shown=new ArrayList<>();for(JSONObject call:calls)if(PairedProtocol.time(call)>=from)shown.add(call);
        for(JSONObject m:messages){long t=PairedProtocol.time(m);while(j<shown.size()&&PairedProtocol.time(shown.get(j))<=t)out.add(shown.get(j++));out.add(m);}
        while(j<shown.size())out.add(shown.get(j++));return out;}
    /** "Outgoing call · 4 min 12 sec · 3:42 PM", centered like a note; missed calls in red. Tap to call back. */
    private View callLine(JSONObject call){int type=call.optInt("callType");boolean missed=CallLines.missed(type);int tone=missed?danger:muted;String when=timeOfDay(PairedProtocol.time(call));
        LinearLayout r=row();r.setGravity(Gravity.CENTER);r.setPadding(dp(16),dp(5),dp(16),dp(5));
        ImageView ic=new ImageView(this);ic.setImageResource(call.optBoolean("video")?R.drawable.ic_videocam:R.drawable.ic_call);ic.setImageTintList(ColorStateList.valueOf(tone));r.addView(ic,new LinearLayout.LayoutParams(dp(15),dp(15)));
        TextView t=text(CallLines.label(call)+" · "+when,12,tone,false);t.setPadding(dp(6),0,0,0);r.addView(t);
        String number=call.optString("number");r.setContentDescription(CallLines.label(call)+", "+when+". Tap to call back.");pressable(r);
        r.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+Uri.encode(number))));}catch(Exception e){toast("No phone app is available.");}});return r;}
    /** While a chat's open, a call ending (it lands in the call log) shows up in it straight away. */
    private void watchCalls(boolean on){android.content.ContentResolver r=getContentResolver();if(callWatch!=null){try{r.unregisterContentObserver(callWatch);}catch(Exception ignored){}callWatch=null;}
        if(!on||!callsOn())return;callWatch=new android.database.ContentObserver(ui){@Override public void onChange(boolean self){if(screen.equals("chat")&&!chatId.isEmpty())loadCalls(chatId);}};
        try{r.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI,true,callWatch);}catch(Exception e){callWatch=null;}}
    private void renderContacts(){if(items==null||!screen.equals("newchat"))return;items.removeAllViews();String q=contactQuery.trim(),number=ChatText.normalizeNumber(q);
        if(!groupMode&&q.isEmpty()){LinearLayout g=row();g.setPadding(dp(8),dp(10),dp(8),dp(10));pressable(g);View face=new FrameLayout(this);face.setBackground(oval(fab));
            TextView plus=text("👥",20,onFab,false);plus.setGravity(Gravity.CENTER);((FrameLayout)face).addView(plus,new FrameLayout.LayoutParams(-1,-1));g.addView(face,new LinearLayout.LayoutParams(dp(44),dp(44)));
            TextView t=text("Create group",17,ink,false);t.setPadding(dp(16),0,0,0);g.addView(t);g.setOnClickListener(v->{groupMode=true;picked.clear();newChat();});items.addView(g);}
        if(!picked.isEmpty()){items.addView(sectionLabel("Selected"));for(Map.Entry<String,String> e:new ArrayList<>(picked.entrySet()))items.addView(contactRow(e.getValue(),e.getKey(),"",""));}
        Set<String> shown=new HashSet<>(picked.keySet());
        if(!number.isEmpty()&&!picked.containsKey(number)){items.addView(contactRow("Use "+number,number,"",""));shown.add(number);}
        // Without the contacts permission, only Google's partial list can be searched; one row to fix that.
        if(!PhoneContacts.allowed(this))items.addView(allowContactsRow());
        String ql=ContactSearch.fold(q),qd=ChatText.digits(q);
        if(q.isEmpty()&&contacts!=null){org.json.JSONArray top=contacts.optJSONArray("top");if(top!=null&&top.length()>0){items.addView(sectionLabel("Frequent"));int added=0;
            Map<String,String> known=knownNames();for(Person g:googlePeople(top)){if(added>=20)break;if(!shown.add(g.number))continue;Person p=named(g,known);items.addView(contactRow(p.name,p.number,p.formatted,p.hex));added++;}}}
        List<Person> everyone=everyone();List<Person> hits=new ArrayList<>();List<Integer> scores=new ArrayList<>();
        for(Person p:everyone){if(shown.contains(p.number))continue;int sc=ContactSearch.score(p.folded,ContactSearch.fold(Nicknames.get(p.number)),p.digits,ql,qd);if(sc<0)continue;
            // Best matches first; ties keep the list's own (alphabetical) order.
            int at=hits.size();while(at>0&&scores.get(at-1)>sc)at--;hits.add(at,p);scores.add(at,sc);shown.add(p.number);}
        boolean loading=phoneContacts==null&&PhoneContacts.allowed(this)||contacts==null&&!contactsFailed;
        if(!hits.isEmpty()){items.addView(sectionLabel(q.isEmpty()?"All contacts":"Contacts"));int max=q.isEmpty()?600:80;
            for(int i=0;i<hits.size()&&i<max;i++){Person p=hits.get(i);items.addView(contactRow(p.name,p.number,p.formatted,p.hex));}}
        else if(loading)items.addView(sectionLabel("Loading contacts…"));
        else if(!q.isEmpty()&&number.isEmpty()){TextView none=text("No one called “"+q+"”. Type their number to message them.",15,muted,false);none.setPadding(dp(8),dp(16),dp(8),dp(8));items.addView(none);}
        if(contactsFailed&&phoneContacts==null){TextView note=text("Google Messages didn't send its contact list. You can still type a number.",13,muted,false);note.setPadding(dp(8),dp(12),dp(8),dp(4));items.addView(note);}}
    /**
     * Everyone to search: this phone's contacts, then anyone else from Google's list or your chats,
     * one row per number. Google often sends a number with no name, so names are filled in from
     * your contacts, your nicknames, the chat's own name and the people in your chats; anyone still
     * known only by number goes after everyone with a name, instead of crowding the top.
     */
    private List<Person> everyone(){Map<String,String> known=knownNames();List<Person> out=new ArrayList<>();Set<String> seen=new HashSet<>(),keys=new HashSet<>();
        if(phoneContacts!=null)for(PhoneContacts.Entry e:phoneContacts){String detail=e.label.isEmpty()?e.formatted:e.label+" · "+e.formatted;
            if(seen.add(e.name+"\n"+e.number)){out.add(named(new Person(e.name,e.number,detail,""),known));keys.add(ContactSearch.key(e.number));}}
        Set<String> phoneKeys=new HashSet<>(keys);List<Person> more=new ArrayList<>();
        if(contacts!=null){org.json.JSONArray all=contacts.optJSONArray("all");if(all!=null)more.addAll(googlePeople(all));}
        if(chatPeople==null)chatPeople=peopleFromChats();more.addAll(chatPeople);
        for(Person p:more){String k=ContactSearch.key(p.number);if(k.isEmpty()||phoneKeys.contains(k)||!keys.add(k))continue;out.add(named(p,known));}
        java.text.Collator order=java.text.Collator.getInstance();order.setStrength(java.text.Collator.PRIMARY);
        out.sort((a,b)->{boolean na=ContactSearch.numberLike(a.name),nb=ContactSearch.numberLike(b.name);if(na!=nb)return na?1:-1;return na?a.number.compareTo(b.number):order.compare(a.name,b.name);});
        return out;}
    /** Number key → a real name, strongest source first: your contacts, then a 1:1 chat's name, then names Google gives people in your chats or its list. */
    private Map<String,String> knownNames;
    private Map<String,String> knownNames(){if(knownNames!=null)return knownNames;Map<String,String> names=new HashMap<>();
        if(phoneContacts!=null)for(PhoneContacts.Entry e:phoneContacts)nameFor(names,e.number,e.name);
        List<JSONObject> chats=new ArrayList<>();for(int folder:new int[]{1,2})chats.addAll(store.chats("",folder));
        for(JSONObject c:chats){JSONObject o=PairedProtocol.other(c);if(o!=null)nameFor(names,PairedProtocol.number(o),PairedProtocol.string(c,"name"));}
        for(JSONObject c:chats)for(JSONObject p:PairedProtocol.members(c)){String full=PairedProtocol.string(p,"fullName");nameFor(names,PairedProtocol.number(p),full.isEmpty()?PairedProtocol.string(p,"firstName"):full);}
        if(contacts!=null)for(String list:new String[]{"top","all"}){org.json.JSONArray a=contacts.optJSONArray(list);if(a!=null)for(int i=0;i<a.length();i++){JSONObject ct=a.optJSONObject(i);JSONObject num=ct==null?null:ct.optJSONObject("number");
            if(num!=null)nameFor(names,PairedProtocol.string(num,"number"),ct.optString("name"));}}
        return knownNames=names;}
    private static void nameFor(Map<String,String> names,String number,String name){String k=ContactSearch.key(number);if(!k.isEmpty()&&!ContactSearch.numberLike(name))names.putIfAbsent(k,name.trim());}
    /** The person with their best name: your nickname for them, else a real name if theirs is only a number. */
    private static Person named(Person p,Map<String,String> known){String nick=Nicknames.get(p.number);if(!nick.isEmpty()&&!nick.equals(p.name))return new Person(nick,p.number,p.formatted,p.hex);
        if(!ContactSearch.numberLike(p.name))return p;String name=known.get(ContactSearch.key(p.number));return name==null?p:new Person(name,p.number,p.formatted,p.hex);}
    private List<Person> googlePeople(org.json.JSONArray list){List<Person> out=new ArrayList<>();
        for(int i=0;i<list.length();i++){JSONObject ct=list.optJSONObject(i);if(ct==null)continue;String name=ct.optString("name");JSONObject num=ct.optJSONObject("number");
            String raw=num==null?"":PairedProtocol.string(num,"number"),n=ChatText.normalizeNumber(raw);if(n.isEmpty()&&raw.startsWith("+"))n=raw;if(n.isEmpty())continue;
            String formatted=num==null?n:num.optString("formattedNumber",n);out.add(new Person(name.isEmpty()?formatted:name,n,formatted,ct.optString("avatarHexColor")));}
        return out;}
    /** People from your conversations (including archived ones), so anyone you've messaged can be found even if they aren't a contact. */
    private List<Person> peopleFromChats(){List<Person> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int folder:new int[]{1,2})for(JSONObject c:store.chats("",folder))for(JSONObject p:PairedProtocol.members(c)){String raw=PairedProtocol.number(p),n=ChatText.normalizeNumber(raw);
            if(n.isEmpty()&&raw.startsWith("+"))n=raw;if(n.isEmpty()||!seen.add(n))continue;String name=PairedProtocol.name(p),formatted=p.optString("formattedNumber",n);
            out.add(new Person(name.isEmpty()?formatted:name,n,formatted,p.optString("avatarHexColor")));}
        return out;}
    private View allowContactsRow(){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(8),dp(10),dp(8),dp(10));pressable(r);
        TextView icon=text("📇",22,ink,false);icon.setGravity(Gravity.CENTER);r.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout texts=vertical();texts.setPadding(dp(16),0,dp(8),0);texts.addView(text("Search all your contacts",17,ink,false));
        texts.addView(text("Google Messages only shares some of them. Allow contacts to search everyone on this phone; they stay here.",14,muted,false));r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
        r.setOnClickListener(v->askForContacts());return r;}
    /** The contacts permission; if Android won't ask again, its settings page for this app. */
    private void askForContacts(){android.content.SharedPreferences prefs=getSharedPreferences("paired",MODE_PRIVATE);
        if(shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)||!prefs.getBoolean("asked_contacts",false)){prefs.edit().putBoolean("asked_contacts",true).apply();requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_PERMISSION);return;}
        toast("Turn on Contacts under Permissions.");try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getPackageName(),null)));}catch(Exception ignored){}}
    private TextView sectionLabel(String label){TextView t=text(label,14,muted,true);t.setPadding(dp(8),dp(14),dp(8),dp(6));return t;}
    private View contactRow(String name,String number,String formatted,String hex){LinearLayout r=row();r.setPadding(dp(8),dp(8),dp(8),dp(8));pressable(r);
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
        TextView hint=text("Searches synced messages and indexed text in downloaded pictures. Use Details → Load older messages to include more history.",13,muted,false);results.addView(hint);
        Dialog d=sheet(content);String id=chatId;JSONObject c=store.chat(id);int[] generation={0};
        q.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int cc,int a){}public void afterTextChanged(Editable e){}
            public void onTextChanged(CharSequence s,int st,int b,int cc){String query=s.toString();int g=++generation[0];
                decoder.execute(()->{List<JSONObject> found=query.trim().length()<2?new ArrayList<>():withPictureHits(store.search(id,query,60),query,id,60);ui.post(()->{if(g!=generation[0])return;results.removeAllViews();
                    if(query.trim().length()<2){results.addView(hint);return;}if(found.isEmpty()){results.addView(text("No matches in synced messages",14,muted,false));return;}
                    for(JSONObject m:found){LinearLayout r=vertical();r.setPadding(dp(4),dp(10),dp(4),dp(10));pressable(r);
                        r.addView(text((PairedProtocol.outgoing(m,c)?"You":senderName(m,c))+" • "+separatorStamp(PairedProtocol.time(m)),12,muted,false));
                        TextView t=text(m.has("_photoText")?"Text in picture: "+m.optString("_photoText"):PairedProtocol.text(m),15,ink,false);t.setMaxLines(3);t.setEllipsize(TextUtils.TruncateAt.END);r.addView(t);
                        r.setOnClickListener(v->{d.dismiss();showMessage(m);});results.addView(r);}});});}});
        q.requestFocus();}
    private void showMessage(JSONObject m){if(!screen.equals("chat"))return;int needed=store.countSince(chatId,PairedProtocol.time(m));if(needed>limit)limit=needed+20;
        renderMessages(false);String mid=m.optString("messageID");scroll.postDelayed(()->jumpTo(mid),150);}

    // Voice messages
    private List<JSONObject> withPictureHits(List<JSONObject> messages,String words,String chat,int max){
        List<JSONObject> out=new ArrayList<>(messages);Set<String> seen=new HashSet<>();for(JSONObject m:out)seen.add(m.optString("messageID"));
        for(JSONObject m:PhotoText.matches(this,store,words,chat,max))if(seen.add(m.optString("messageID")))out.add(m);
        out.sort((a,b)->Long.compare(PairedProtocol.time(b),PairedProtocol.time(a)));return out.size()>max?new ArrayList<>(out.subList(0,max)):out;
    }
    private void readPhotoText(JSONObject media){withFile(media,"Getting the photo…",file->{toast("Recognizing text on this phone…");PhotoText.scan(this,media,file,true,(recognized,error)->{
        if(isFinishing()||isDestroyed())return;String shown=!error.isEmpty()?error:recognized.trim().isEmpty()?"No clear text was found in this picture. Printed text in screenshots and documents reads best.":recognized;
        TextView result=text(shown,16,ink,false);result.setTextIsSelectable(true);result.setPadding(dp(18),dp(12),dp(18),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(result);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Text in picture").setView(scroll).setNegativeButton("Close",null);
        if(error.isEmpty()&&!recognized.trim().isEmpty())dialog.setPositiveButton("Copy text",(d,w)->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Picture text",recognized));toast("Picture text copied");});dialog.show();});});}
    private void savedAttachments(){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(JSONObject j:new MediaOutbox(this).all()){JSONObject c=store.chat(j.optString("chat"));labels.add((c==null?"Conversation":PairedProtocol.title(c))+" • "+MediaOutbox.label(j,PairedService.ready()));String id=j.optString("chat");actions.add(()->thread(id));}
        if(labels.isEmpty()){toast("No saved attachments are waiting.");return;}menuSheet("Saved attachments",labels,actions);}
    private void updateComposeButton(){if(sendButton==null||composer==null)return;
        if(dictation!=null){sendButton.setImageResource(R.drawable.ic_stop);sendButton.setContentDescription("Stop dictating");return;}
        boolean mic=micShowing();sendButton.setImageResource(mic?R.drawable.ic_mic:R.drawable.ic_send);sendButton.setContentDescription(mic?"Dictate a message. Press and hold to record a voice message.":"Send");}
    /** The button shows the mic when there's nothing to send yet. */
    private boolean micShowing(){if(composer==null)return false;boolean files=false;for(Outgoing o:outgoing)if(!o.sending)files=true;return composer.getText().toString().trim().isEmpty()&&!files;}
    /** Tap: dictate (mic), stop dictating, or send. Press and hold the mic to record a voice message instead. */
    /** Loading chats or messages is underway (one at a time). A load that never answers stops counting after a minute, so nothing waits on it forever. */
    private long busySince;
    private boolean isBusy(){if(busy&&System.currentTimeMillis()-busySince>60_000){busy=false;Diagnostics.note("A load took over a minute; stopped waiting for it");}return busy;}
    private void sendOrRecord(){if(dictation!=null){stopDictation();return;}if(micShowing())startDictation();else send();}

    // ---- Dictation: tap the mic, talk, and the words go into the message box ----
    private android.speech.SpeechRecognizer dictation;private int dictateStart,dictateEnd;private String composerHint="";
    private void startDictation(){if(dictation!=null||composer==null)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},79);return;}
        if(!android.speech.SpeechRecognizer.isRecognitionAvailable(this)){error("Speech to text isn't available on this phone. Install or update the Google app, then try again. Press and hold the mic to record a voice message instead.");return;}
        android.speech.SpeechRecognizer r;try{r=android.speech.SpeechRecognizer.createSpeechRecognizer(this);}catch(Exception e){error("Speech to text couldn't start.");return;}
        dictation=r;composer.requestFocus();int at=Math.max(0,composer.getSelectionEnd());if(at>composer.length())at=composer.length();dictateStart=dictateEnd=at;
        r.setRecognitionListener(new android.speech.RecognitionListener(){
            @Override public void onReadyForSpeech(Bundle b){if(dictation==r&&composer!=null)composer.setHint("Listening…");}
            @Override public void onBeginningOfSpeech(){}
            @Override public void onRmsChanged(float v){}
            @Override public void onBufferReceived(byte[] b){}
            @Override public void onEndOfSpeech(){}
            @Override public void onPartialResults(Bundle b){if(dictation==r)dictated(b);}
            @Override public void onResults(Bundle b){if(dictation==r){dictated(b);stopDictation();}}
            @Override public void onEvent(int t,Bundle b){}
            @Override public void onError(int code){if(dictation!=r)return;stopDictation();
                if(code==android.speech.SpeechRecognizer.ERROR_NO_MATCH||code==android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)toast("Didn't catch that. Tap the mic and try again.");
                else if(code==android.speech.SpeechRecognizer.ERROR_NETWORK||code==android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT||code==android.speech.SpeechRecognizer.ERROR_SERVER)toast("Speech to text needs a connection right now.");
                else if(code==android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)toast("The Google app needs microphone access for speech to text.");
                else if(code!=android.speech.SpeechRecognizer.ERROR_CLIENT)toast("Speech to text stopped (error "+code+").");}});
        Intent listen=new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS,true).putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE,getPackageName());
        try{r.startListening(listen);}catch(Exception e){stopDictation();error("Speech to text couldn't start.");return;}
        updateComposeButton();}
    /** Puts what's been heard so far at the cursor, replacing the previous guess. */
    private void dictated(Bundle b){if(composer==null)return;java.util.ArrayList<String> heard=b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        if(heard==null||heard.isEmpty()||heard.get(0).trim().isEmpty())return;Editable e=composer.getText();
        int start=Math.min(dictateStart,e.length()),end=Math.max(start,Math.min(dictateEnd,e.length()));
        String words=ChatText.dictation(e.subSequence(0,start).toString(),heard.get(0));
        e.replace(start,end,words);dictateStart=start;dictateEnd=start+words.length();composer.setSelection(dictateEnd);}
    private void stopDictation(){android.speech.SpeechRecognizer r=dictation;dictation=null;if(r!=null){try{r.cancel();}catch(Exception ignored){}try{r.destroy();}catch(Exception ignored){}}
        if(composer!=null)composer.setHint(composerHint);updateComposeButton();}
    private void startRecording(){if(recorder!=null)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},77);return;}
        File dir=new File(getCacheDir(),"outgoing");if(!dir.isDirectory())dir.mkdirs();recording=new File(dir,java.util.UUID.randomUUID()+"-voice.m4a");
        try{recorder=new MediaRecorder(this);recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);recorder.setAudioEncodingBitRate(64000);recorder.setAudioChannels(1);recorder.setMaxDuration(10*60*1000);recorder.setOutputFile(recording.getPath());
            recorder.setOnInfoListener((r,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)finishRecording(true);});recorder.prepare();recorder.start();}
        catch(Exception e){if(recorder!=null){recorder.release();recorder=null;}recording.delete();error("The microphone could not be started.");return;}
        recordStart=System.currentTimeMillis();if(recordWave!=null)recordWave.liveMode();if(recordBar!=null){recordBar.setVisibility(View.VISIBLE);composeRow.setVisibility(View.GONE);}updateRecordClock();}
    /** Every 80 ms while recording: the time, how loud you are (into the live waveform), and the dot's pulse. */
    private void updateRecordClock(){ui.removeCallbacks(recordTick);if(recorder==null||recordClock==null)return;long ms=System.currentTimeMillis()-recordStart,s=ms/1000;
        recordClock.setText(String.format(Locale.US,"%d:%02d",s/60,s%60));recordClock.setContentDescription("Recording, "+s+" seconds");
        if(recordWave!=null){int amp=0;try{amp=recorder.getMaxAmplitude();}catch(Exception ignored){}recordWave.push((float)Math.sqrt(amp/32767f));}
        if(recordDot!=null)recordDot.setAlpha(0.35f+0.65f*(float)(0.5+0.5*Math.cos(ms*2*Math.PI/1200.0)));
        ui.postDelayed(recordTick,80);}
    /** Stops recording; keep=true adds it to the message (sent right away when send=true). */
    private void finishRecording(boolean send){if(recorder==null)return;ui.removeCallbacks(recordTick);boolean ok=System.currentTimeMillis()-recordStart>700;
        try{recorder.stop();}catch(Exception e){ok=false;}recorder.release();recorder=null;if(recordBar!=null){recordBar.setVisibility(View.GONE);composeRow.setVisibility(View.VISIBLE);}
        if(!ok){recording.delete();toast("Too short to send");return;}
        outgoing.add(new Outgoing(recording,"Voice message.m4a","audio/mp4"));outgoingChat=chatId;renderAttachments();updateComposeButton();
        if(send){JSONObject c=store.chat(chatId);if(c!=null)sendAttachments(c,"",replyId);}}
    private void cancelRecording(){if(recorder==null)return;ui.removeCallbacks(recordTick);try{recorder.stop();}catch(Exception ignored){}recorder.release();recorder=null;recording.delete();
        if(recordBar!=null){recordBar.setVisibility(View.GONE);composeRow.setVisibility(View.VISIBLE);}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);GivenNames.forget();
        if(request==77){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startRecording();else toast("Message Center needs the microphone to record voice messages.");}
        if(request==79){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startDictation();else toast("Message Center needs the microphone for speech to text.");}
        if(request==CALL_LOG_PERMISSION){boolean yes=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED;
            toast(yes?"Calls will show in your chats.":"Calls can't show in chats without access to your call log.");if(yes)watchCalls(true);if(screen.equals("settings"))settings();return;}
        if(request==ARRIVAL_PERMISSION){boolean any=false;for(int r:results)if(r==PackageManager.PERMISSION_GRANTED)any=true;if(any)arrivalText(arrivalChat);else toast("Location is needed to text them when you arrive.");return;}
        if(request==ARRIVAL_BACKGROUND){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)arrivalText(arrivalChat);else toast("Choose “Allow all the time” for Location, so the text can go while your phone is in your pocket.");return;}
        if(request==LOCATION_PERMISSION){boolean any=false;for(int r:results)if(r==PackageManager.PERMISSION_GRANTED)any=true;if(any)shareLocation();else toast("Location permission is needed to share where you are.");return;}
        if(request==CONTACTS_PERMISSION&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){phoneChecked.clear();rowCache.clear();
            if(screen.equals("inbox"))renderInbox();else if(screen.equals("settings"))settings();else if(screen.equals("newchat"))loadPhoneContacts();}
        if(request==CONTACTS_PERMISSION&&screen.equals("newchat"))renderContacts();
        if(request==SHARE_CONTACT_PERMISSION){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){phoneChecked.clear();rowCache.clear();showContactPicker();}
            else{toast("Without contacts access, Android's own picker is used.");systemContactPicker();}return;}
        if(request==78){JSONObject pending=pendingTranscription;pendingTranscription=null;if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&pending!=null)transcribe(pending);else if(pending!=null)toast("Transcription needs microphone access.");}}
    // ---- Voice messages: the card, and playing them ----
    /** The parts of a voice message card on screen, so playback can update them without redrawing the chat. */
    private static final class VoiceCard{final JSONObject message,media;final String id;final boolean outgoing;final ImageView icon;final ProgressBar spinner;final FrameLayout button;
        final Waveform wave;final TextView time,speed;final View dot;long ms;int iconShown;
        VoiceCard(JSONObject message,JSONObject media,String id,boolean outgoing,FrameLayout button,ImageView icon,ProgressBar spinner,Waveform wave,TextView time,TextView speed,View dot){
            this.message=message;this.media=media;this.id=id;this.outgoing=outgoing;this.button=button;this.icon=icon;this.spinner=spinner;this.wave=wave;this.time=time;this.speed=speed;this.dot=dot;}}
    private final Map<String,VoiceCard> voiceCards=new HashMap<>();
    /** Voice messages in the chat in order, as [mediaID, messageID]: when one ends, an unplayed one right after it from the same person plays next. */
    private final List<String[]> voiceOrder=new ArrayList<>();
    private MediaPlayer player;private String playingId="",preparingId="";private final Runnable playTick=this::voiceTick;
    private android.media.AudioFocusRequest focusRequest;private boolean noisyOn;
    private final android.content.BroadcastReceiver noisy=new android.content.BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){pauseVoice();}};
    private static final android.media.AudioAttributes SPEECH=new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build();

    private ImageView roundIcon(int drawable,int background,int tint,int sizeDp){ImageView i=new ImageView(this);i.setImageResource(drawable);i.setImageTintList(ColorStateList.valueOf(tint));
        i.setBackground(oval(background));int pad=Math.round(sizeDp*0.24f);i.setPadding(dp(pad),dp(pad),dp(pad),dp(pad));i.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp),dp(sizeDp)));return i;}

    /**
     * A voice message: a round play button, the waveform (drag it to move through the message),
     * the length (or time played), a dot until you've played one sent to you, and the speed while
     * it's playing. The waveform and length are worked out from the audio once it's on the phone.
     */
    private View voiceCard(JSONObject m,JSONObject media,boolean outgoing,ChatTheme theme){String id=PairedProtocol.string(media,"mediaID");
        int fg=outgoing?theme.onBubble(dark):ink,accent=outgoing?fg:theme.accent(dark),onAccent=outgoing?theme.bubble(dark):theme.onAccent(dark);
        LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(8),dp(8),dp(12),dp(8));card.setBackground(shape(outgoing?theme.bubble(dark):surface,26));
        FrameLayout button=new FrameLayout(this);button.setBackground(oval(accent));pressDim(button);
        ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_play);icon.setImageTintList(ColorStateList.valueOf(onAccent));icon.setPadding(dp(10),dp(10),dp(10),dp(10));button.addView(icon,new FrameLayout.LayoutParams(-1,-1));
        ProgressBar spinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);spinner.setIndeterminateTintList(ColorStateList.valueOf(onAccent));spinner.setVisibility(View.GONE);
        button.addView(spinner,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));card.addView(button,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout mid=vertical();mid.setPadding(dp(10),dp(2),0,0);
        Waveform wave=new Waveform(this);wave.setColors(fg,(fg&0x00ffffff)|(outgoing?0x66000000:0x55000000));
        int waveW=Math.min(dp(190),Math.round(getResources().getDisplayMetrics().widthPixels*0.42f));mid.addView(wave,new LinearLayout.LayoutParams(waveW,dp(30)));
        LinearLayout under=row();under.setGravity(Gravity.CENTER_VERTICAL);TextView time=text("",12,outgoing?fg:muted,false);time.setFontFeatureSettings("tnum");under.addView(time);
        View dot=new View(this);dot.setBackground(oval(theme.accent(dark)));LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(dp(7),dp(7));dl.setMarginStart(dp(7));under.addView(dot,dl);
        mid.addView(under);card.addView(mid);
        TextView speed=text(speedLabel(voiceSpeed()),12,fg,true);speed.setPadding(dp(9),dp(4),dp(9),dp(4));speed.setBackground(shape((fg&0x00ffffff)|0x22000000,12));speed.setFontFeatureSettings("tnum");
        speed.setContentDescription("Playback speed");speed.setOnClickListener(v->cycleVoiceSpeed(speed));LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-2,-2);sl.setMarginStart(dp(10));sl.gravity=Gravity.CENTER_VERTICAL;card.addView(speed,sl);
        VoiceCard vc=new VoiceCard(m,media,id,outgoing,button,icon,spinner,wave,time,speed,dot);
        VoiceNotes.Info info=VoiceNotes.saved(this,id);if(info!=null){wave.setPeaks(info.peaks);vc.ms=info.ms;}else prepareVoiceInfo(vc);
        if(!id.isEmpty()){voiceCards.put(id,vc);voiceOrder.add(new String[]{id,m.optString("messageID"),m.optString("participantID")});}
        button.setOnClickListener(v->toggleVoice(media,m));card.setOnClickListener(v->toggleVoice(media,m));card.setOnLongClickListener(v->{messageSheet(m);return true;});
        wave.setOnSeek((f,done)->seekVoice(vc,f,done));
        bindVoice(vc);return card;}
    /** Shows a card as it is now: playing, paused, loading or idle; where it's at; and whether you've played it. */
    private void bindVoice(VoiceCard vc){boolean active=vc.id.equals(playingId)&&player!=null,loading=vc.id.equals(preparingId),playing=active&&player.isPlaying();
        vc.icon.setVisibility(loading?View.INVISIBLE:View.VISIBLE);vc.spinner.setVisibility(loading?View.VISIBLE:View.GONE);
        // Called 20 times a second while playing, so only what changed is touched.
        int icon=playing?R.drawable.ic_pause:R.drawable.ic_play;if(vc.iconShown!=icon){vc.icon.setImageResource(icon);vc.iconShown=icon;}
        long at=0,len=vc.ms;if(active){try{at=player.getCurrentPosition();if(player.getDuration()>0)len=player.getDuration();}catch(Exception ignored){}}
        vc.wave.setProgress(active&&len>0?at/(float)len:0f);
        if(!vc.wave.dragging())setIfChanged(vc.time,active?VoiceNotes.clock(at):len>0?VoiceNotes.clock(len):"Voice message");
        setIfChanged(vc.speed,speedLabel(voiceSpeed()));vc.speed.setVisibility(active?View.VISIBLE:View.GONE);
        vc.dot.setVisibility(!vc.outgoing&&!VoiceNotes.played(this,vc.id)&&!active?View.VISIBLE:View.GONE);
        String what=len>0?"Voice message, "+VoiceNotes.clock(len):"Voice message";
        vc.button.setContentDescription(loading?"Loading "+what:(playing?"Pause ":"Play ")+what.substring(0,1).toLowerCase(Locale.ROOT)+what.substring(1));}
    private void bindVoice(String id){VoiceCard vc=voiceCards.get(id);if(vc!=null)bindVoice(vc);}
    private static void setIfChanged(TextView t,String value){if(!t.getText().toString().equals(value))t.setText(value);}
    /** The waveform and length, from the audio: worked out now if it's on the phone, or once it's downloaded (voice messages are small). */
    private void prepareVoiceInfo(VoiceCard vc){File cached=MediaCache.file(this,vc.media,false);
        VoiceNotes.Ready ready=info->{VoiceCard now=voiceCards.get(vc.id);if(now==null)return;now.ms=info.ms;now.wave.setPeaks(info.peaks);bindVoice(now);};
        if(cached.isFile()){VoiceNotes.analyze(this,cached,vc.id,ready);return;}
        if(!PairedService.ready()||!MediaCache.hasFull(vc.media)||MediaCache.needsPhone(vc.media))return;
        MediaCache.fetch(this,vc.media,false,(file,error)->{if(file!=null)VoiceNotes.analyze(this,file,vc.id,ready);});}
    /** Play or pause; a different message stops the one playing. */
    private void toggleVoice(JSONObject media,JSONObject m){String id=PairedProtocol.string(media,"mediaID");
        if(player!=null&&id.equals(playingId)){if(player.isPlaying())pauseVoice();else resumeVoice();return;}
        startVoice(media,m,0f);}
    private void startVoice(JSONObject media,JSONObject m,float fraction){String id=PairedProtocol.string(media,"mediaID");stopAudio();preparingId=id;bindVoice(id);
        File cached=MediaCache.file(this,media,false);
        if(cached.isFile()){playVoice(cached,media,fraction);return;}
        // An old message only on the phone goes through the usual "getting it from your phone" wait.
        if(MediaCache.needsPhone(media)||!PairedService.ready()){preparingId="";bindVoice(id);withFile(media,"Downloading…",file->{preparingId=id;playVoice(file,media,fraction);});return;}
        MediaCache.fetch(this,media,false,(file,error)->{if(!id.equals(preparingId))return;
            if(file==null){preparingId="";bindVoice(id);error(error);return;}playVoice(file,media,fraction);});}
    /** Prepares the player off the main thread, then starts at fraction of the way through. */
    private void playVoice(File file,JSONObject media,float fraction){String id=PairedProtocol.string(media,"mediaID");
        MediaPlayer mp=new MediaPlayer();player=mp;playingId="";
        try{mp.setAudioAttributes(SPEECH);mp.setDataSource(file.getPath());}catch(Exception e){releaseVoice();preparingId="";bindVoice(id);openExternally(file,media);return;}
        mp.setOnPreparedListener(p->{if(player!=p||!id.equals(preparingId))return;preparingId="";playingId=id;
            if(fraction>0&&p.getDuration()>0)p.seekTo(Math.round(fraction*p.getDuration()));
            VoiceNotes.markPlayed(this,id);
            // Its length, if the waveform isn't there yet.
            VoiceCard vc=voiceCards.get(id);if(vc!=null&&vc.ms<=0)vc.ms=p.getDuration();if(VoiceNotes.saved(this,id)==null)VoiceNotes.analyze(this,file,id,info->{VoiceCard now=voiceCards.get(id);if(now!=null){now.ms=info.ms;now.wave.setPeaks(info.peaks);bindVoice(now);}});
            resumeVoice();});
        mp.setOnCompletionListener(p->{if(player==p)voiceEnded(id);});
        mp.setOnErrorListener((p,what,extra)->{if(player!=p)return true;releaseVoice();preparingId="";bindVoice(id);toast("This voice message can't be played here. Opening it in another app.");openExternally(file,media);return true;});
        try{mp.prepareAsync();}catch(Exception e){releaseVoice();preparingId="";bindVoice(id);openExternally(file,media);}}
    private void resumeVoice(){if(player==null)return;
        // Other audio (music, a podcast) pauses while it plays and picks back up after.
        android.media.AudioManager am=getSystemService(android.media.AudioManager.class);
        if(focusRequest==null)focusRequest=new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(SPEECH)
            .setOnAudioFocusChangeListener(change->{if(change==android.media.AudioManager.AUDIOFOCUS_LOSS||change==android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)pauseVoice();},ui).build();
        am.requestAudioFocus(focusRequest);
        // Headphones unplugged or disconnected: pause rather than play out loud.
        if(!noisyOn){registerReceiver(noisy,new IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED);noisyOn=true;}
        try{player.start();if(voiceSpeed()!=1f)player.setPlaybackParams(player.getPlaybackParams().setSpeed(voiceSpeed()));}catch(Exception ignored){}bindVoice(playingId);voiceTick();}
    private void pauseVoice(){if(player!=null){try{if(player.isPlaying())player.pause();}catch(Exception ignored){}}letGoOfAudio();bindVoice(playingId);ui.removeCallbacks(playTick);}
    /** Drag the waveform: the time follows your finger; letting go plays from there (or moves a paused one there). */
    private void seekVoice(VoiceCard vc,float f,boolean done){boolean active=vc.id.equals(playingId)&&player!=null;long len=vc.ms;
        if(active){try{if(player.getDuration()>0)len=player.getDuration();}catch(Exception ignored){}}
        if(!done){if(len>0)vc.time.setText(VoiceNotes.clock(Math.round(f*len)));return;}
        if(active){try{player.seekTo(Math.round(f*len));}catch(Exception ignored){}bindVoice(vc);return;}
        startVoice(vc.media,vc.message,f);}
    private void voiceTick(){ui.removeCallbacks(playTick);if(player==null||playingId.isEmpty())return;bindVoice(playingId);
        boolean playing;try{playing=player.isPlaying();}catch(Exception e){playing=false;}if(playing)ui.postDelayed(playTick,50);}
    /** One ended: back to the start, and an unplayed voice message right after it from the same person plays next. */
    private void voiceEnded(String id){stopAudio();bindVoice(id);
        for(int i=0;i+1<voiceOrder.size();i++){if(!voiceOrder.get(i)[0].equals(id))continue;String[] next=voiceOrder.get(i+1);VoiceCard vc=voiceCards.get(next[0]);
            if(vc!=null&&!vc.outgoing&&next[2].equals(voiceOrder.get(i)[2])&&!VoiceNotes.played(this,next[0])&&adjacent(voiceOrder.get(i)[1],next[1]))startVoice(vc.media,vc.message,0f);break;}}
    /** Whether two messages are next to each other in the chat (nothing said in between). */
    private boolean adjacent(String a,String b){View va=messageViews.get(a),vb=messageViews.get(b);if(va==null||vb==null||va.getParent()!=items||vb.getParent()!=items)return false;
        int ia=items.indexOfChild(va),ib=items.indexOfChild(vb);if(ia<0||ib<=ia)return false;
        for(int k=ia+1;k<ib;k++)if(messageViews.containsValue(items.getChildAt(k)))return false;return true;}
    private void letGoOfAudio(){if(focusRequest!=null)getSystemService(android.media.AudioManager.class).abandonAudioFocusRequest(focusRequest);
        if(noisyOn){try{unregisterReceiver(noisy);}catch(Exception ignored){}noisyOn=false;}}
    private void releaseVoice(){ui.removeCallbacks(playTick);if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}letGoOfAudio();}
    private void stopAudio(){String was=playingId;releaseVoice();playingId="";if(!was.isEmpty())bindVoice(was);}

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
    private View linkCard(String url,boolean outgoing,JSONObject m){LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);line.setVisibility(View.GONE);
        LinearLayout card=vertical();card.setBackground(shape(surface,18));card.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(18));}});card.setClipToOutline(true);
        int width=Math.round(getResources().getDisplayMetrics().widthPixels*0.66f);line.addView(card,new LinearLayout.LayoutParams(width,-2));
        LinkPreviews.Done fill=p->{card.removeAllViews();String image=p.optString("image");int iw=p.optInt("imageW"),ih=p.optInt("imageH");
            // Big pictures go on top; small ones (logos, square product shots under 300px) sit beside the title instead of being stretched.
            boolean hero=!image.isEmpty()&&iw>=300&&ih>=150,thumb=!image.isEmpty()&&!hero;int heroH=hero?Math.max(dp(110),Math.min(dp(190),Math.round(width*(float)ih/iw))):0;
            // The picture's space is there from the start (so nothing in the chat moves when it arrives), and a
            // picture already decoded is put straight in, so a redraw (a new message, a receipt) doesn't blink it.
            if(hero){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackgroundColor(bg);card.addView(img,new LinearLayout.LayoutParams(-1,heroH));
                File pic=new File(image);Bitmap now=MediaCache.peekCover(pic,width,heroH);
                if(now!=null)img.setImageBitmap(now);
                else decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(pic,width,heroH,false);img.post(()->{if(b!=null)img.setImageBitmap(b);else img.setVisibility(View.GONE);});});}
            LinearLayout body=row();body.setGravity(Gravity.CENTER_VERTICAL);body.setPadding(dp(14),dp(10),dp(14),dp(12));
            if(thumb){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setBackground(shape(bg,10));
                img.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(10));}});img.setClipToOutline(true);
                LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(dp(56),dp(56));tl.setMarginEnd(dp(12));body.addView(img,tl);
                File pic=new File(image);Bitmap now=MediaCache.peekCover(pic,dp(56),dp(56));
                if(now!=null)img.setImageBitmap(now);else decoder.execute(()->{Bitmap b=MediaCache.decodeToCover(pic,dp(56),dp(56),false);if(b!=null)img.post(()->img.setImageBitmap(b));});}
            LinearLayout texts=vertical();TextView title=text(p.optString("title"),15,ink,true);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);texts.addView(title);
            String desc=p.optString("description");if(!desc.isEmpty()&&!desc.equals(p.optString("title"))){TextView d=text(desc,13,ink,false);d.setAlpha(0.8f);d.setMaxLines(2);d.setEllipsize(TextUtils.TruncateAt.END);d.setPadding(0,dp(2),0,0);texts.addView(d);}
            String site=p.optString("site");TextView sv=text(site.isEmpty()?ChatText.domain(url):site,13,muted,false);sv.setPadding(0,dp(2),0,0);texts.addView(sv);
            body.addView(texts,new LinearLayout.LayoutParams(0,-2,1));card.addView(body);card.setContentDescription("Link preview: "+p.optString("title")+". Tap to open, hold to copy or share.");
            View music=musicRow(url,m);if(music!=null)card.addView(music,new LinearLayout.LayoutParams(-1,-2));line.setVisibility(View.VISIBLE);};
        card.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("No browser is available.");}});
        // Holding the preview: the same choices as holding the link in the text (copy, share, open, the message's options).
        card.setOnLongClickListener(v->{linkSheet(url,m);return true;});
        JSONObject ready=LinkPreviews.peekSaved(this,url);if(ready!=null){if(!ready.optString("title").isEmpty())fill.done(ready);}else LinkPreviews.load(this,url,fill);
        return line;}
    /**
     * Under a song's (album's, artist's, podcast's) preview: "Open in Spotify", found there even when
     * the link is from Apple Music or YouTube Music. The first tap asks which app you listen with.
     * The row's height is set from the start, so nothing in the chat moves when the match arrives.
     */
    private View musicRow(String url,JSONObject m){MusicLinks.Link link=MusicApps.link(url);if(link==null)return null;boolean podcast=MusicApps.podcast(link);
        if(MusicApps.off(this,podcast))return null;MusicLinks.App app=MusicApps.chosen(this,podcast);if(app!=null&&app==link.app)return null;
        JSONObject known=app==null?null:MusicApps.peek(this,url,app);if(known!=null&&known.optString("how").equals("none"))return null;
        int accent=chatSettings.theme(chatId).accent(dark);
        LinearLayout wrap=vertical();View rule=new View(this);rule.setBackgroundColor((muted&0x00ffffff)|0x33000000);wrap.addView(rule,new LinearLayout.LayoutParams(-1,Math.max(1,dp(0.5f))));
        LinearLayout r=row();r.setPadding(dp(14),0,dp(14),0);wrap.addView(r,new LinearLayout.LayoutParams(-1,dp(44)));
        ImageView icon=new ImageView(this);android.graphics.drawable.Drawable own=app!=null&&MusicApps.installed(this,app)?appIcon(app.pkg):null;
        if(own!=null)icon.setImageDrawable(own);else{icon.setImageResource(podcast?R.drawable.ic_mic:R.drawable.ic_play);icon.setImageTintList(ColorStateList.valueOf(accent));}
        LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(dp(22),dp(22));il.setMarginEnd(dp(10));r.addView(icon,il);
        TextView label=text(app==null?"Open in…":"Open in "+app.label,14,accent,true);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView how=text("",13,muted,false);how.setSingleLine(true);how.setPadding(dp(8),0,0,0);r.addView(how);
        JSONObject[] found={known};boolean[] openWhenFound={false};
        Runnable show=()->{if(app==null){how.setText("Choose app");r.setContentDescription("Open in your "+(podcast?"podcast":"music")+" app. Choose which one.");return;}
            JSONObject f=found[0];if(f!=null&&f.optString("how").equals("none")){wrap.setVisibility(View.GONE);return;}
            String state=f==null?"Finding…":f.optString("how").equals("error")?"Tap to try again":MusicApps.how(f);
            how.setText(state);r.setContentDescription("Open in "+app.label+(state.isEmpty()?"":". "+state)+".");};
        show.run();
        Runnable lookUp=()->MusicApps.find(this,url,app,res->{found[0]=res;show.run();if(openWhenFound[0]){openWhenFound[0]=false;openMusic(res,app);}});
        if(app!=null&&known==null)lookUp.run();
        r.setOnClickListener(v->{
            if(app==null){chooseMusicApp(podcast,chosen->{renderMessages(false);if(chosen!=null)MusicApps.find(this,url,chosen,res->openMusic(res,chosen));});return;}
            JSONObject f=found[0];
            if(f==null){openWhenFound[0]=true;return;}
            if(f.optString("how").equals("error")){found[0]=null;openWhenFound[0]=true;show.run();lookUp.run();return;}
            openMusic(f,app);});
        r.setOnLongClickListener(v->{linkSheet(url,m);return true;});
        return wrap;}
    /** Opens what was found, if this screen is still the one in front (a slow lookup shouldn't jump into another app later). */
    private void openMusic(JSONObject found,MusicLinks.App app){if(!resumed)return;String how=found.optString("how");
        if(how.equals("none")){toast("Couldn't find this in "+app.label+".");return;}
        if(how.equals("error")){toast("Couldn't reach "+app.label+" to find this. Check the connection and try again.");return;}
        if(!MusicApps.open(this,found,app))toast("Couldn't open "+app.label+".");}
    private android.graphics.drawable.Drawable appIcon(String pkg){try{return getPackageManager().getApplicationIcon(pkg);}catch(Exception e){return null;}}
    /** Which app songs (or podcasts) open in: the ones on this phone first. */
    private void chooseMusicApp(boolean podcast,java.util.function.Consumer<MusicLinks.App> then){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        for(MusicLinks.App a:MusicApps.choices(this,podcast)){labels.add(a.label+(a.pkg.isEmpty()?" (on the web)":MusicApps.installed(this,a)?"":" (not on this phone)"));
            actions.add(()->{MusicApps.choose(this,podcast,a);if(then!=null)then.accept(a);});}
        labels.add(podcast?"Leave podcast links as they are":"Leave music links as they are");actions.add(()->{MusicApps.choose(this,podcast,null);if(then!=null)then.accept(null);});
        menuSheet(podcast?"Open podcasts in":"Open music in",labels,actions);}
    private ScrollView settingsScroll;private int pendingSettingsY;
    /** Which settings page is showing: "" for the main page, else messages, media, notifications, automation, appearance, apps, connection or data. */
    private String settingsPage="",settingsQuery="",settingsShown=null;private boolean keepSettingsPage;
    /** While searching settings: what was typed (rows that don't match aren't added), and the page being searched. */
    private String settingsFilter,settingsFilterPage="";private final List<View[]> settingsGroups=new ArrayList<>();
    private static final String[] SETTINGS_PAGES={"messages","media","notifications","automation","appearance","apps","connection","data"};
    private static String settingsTitle(String page){switch(page){case "messages":return "Messages";case "media":return "Photos & media";case "notifications":return "Notifications";
        case "automation":return "Automation";case "appearance":return "Appearance";case "apps":return "Apps";case "connection":return "Connection";case "data":return "Data & about";default:return "Settings";}}
    /** Words people might search for that a setting's own words don't say. */
    private static String settingKeywords(String title){switch(title){case "Theme":return "dark light black mode night colors";case "Text size":return "font bigger smaller larger";case "Unread count":return "badge number total inbox";case "Chat color":return "theme bubble colour all chats every conversation";case "Calls in chats":return "phone call log duration missed";
        case "Undo send":return "unsend delay take back cancel";case "Typing indicators":return "typing privacy";case "Read receipts":return "seen read privacy";case "Bubbles":return "floating chat heads";
        case "Busy auto-reply":return "away driving automatic reply";case "Notifications":return "sound vibration alerts";case "Background activity":return "battery optimization";
        case "Back up app data":return "backup export save";case "Restore from a backup":return "import backup";case "Photo quality":return "pictures compress size";
        case "Video quality":return "compress size";case "Music app":return "spotify apple youtube tidal deezer amazon soundcloud song songs convert links open in";case "Podcast app":return "pocket casts antennapod podcast addict show episode";case "Maps app for addresses":return "google maps waze directions";case "Calendar app for dates":return "event schedule";case "What notifications show":return "privacy preview";case "On the lock screen":return "lock screen locked preview privacy hide unlock";default:return "";}}
    /** Opens settings at the main page, from anywhere. */
    private void openSettings(){settingsPage="";settingsQuery="";settings();}
    private void openSettingsPage(String page){settingsPage=page;settingsQuery="";keepSettingsPage=true;settings();}
    /** Back from a settings page (or a search) to the main page; from the main page to the inbox. */
    private void settingsBack(){if(!settingsPage.isEmpty()||!settingsQuery.isEmpty()){settingsPage="";settingsQuery="";settings();}else inbox();}
    /** Draws the settings page that's showing (and keeps your place on it when it's redrawn after a change). */
    private void settings(){if(!screen.equals("settings")&&!keepSettingsPage){settingsPage="";settingsQuery="";}keepSettingsPage=false;
        String shown=settingsPage+"|"+(settingsQuery.isEmpty()?"":"search");
        int keep=screen.equals("settings")&&settingsScroll!=null&&shown.equals(settingsShown)?settingsScroll.getScrollY():pendingSettingsY;pendingSettingsY=0;settingsShown=shown;
        remember();base("settings");header(settingsTitle(settingsPage),this::settingsBack);
        if(settingsPage.isEmpty()||settingsPage.equals("connection"))connection();
        ScrollView sc=new ScrollView(this);settingsScroll=sc;LinearLayout content=vertical();content.setPadding(dp(4),0,dp(4),dp(32));sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        if(settingsPage.isEmpty()){EditText search=input("Search settings");search.setSingleLine(true);search.setText(settingsQuery);search.setSelection(search.length());search.setContentDescription("Search settings");
            LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,-2);sl.setMargins(0,dp(2),0,dp(6));content.addView(search,sl);
            LinearLayout body=vertical();content.addView(body,new LinearLayout.LayoutParams(-1,-2));settingsHome(body);
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}
                public void afterTextChanged(Editable e){settingsQuery=e.toString();settingsShown=settingsPage+"|"+(settingsQuery.isEmpty()?"":"search");body.removeAllViews();settingsHome(body);}});}
        else settingsPageRows(settingsPage,content);
        if(settingsQuery.isEmpty())keyboardDown();
        restoreScroll(sc,keep);}
    /** The main page: who you're connected as, then each page with what's set there; or, while searching, the settings that match. */
    private void settingsHome(LinearLayout body){String q=settingsQuery.trim();
        if(!q.isEmpty()){settingsFilter=q;int before=body.getChildCount();
            for(String page:SETTINGS_PAGES){settingsFilterPage=settingsTitle(page);settingsGroups.clear();settingsPageRows(page,body);
                for(View[] g:settingsGroups)if(((LinearLayout)g[1]).getChildCount()==0){body.removeView(g[0]);body.removeView(g[1]);}}
            settingsFilter=null;settingsFilterPage="";settingsGroups.clear();
            if(body.getChildCount()==before){TextView none=text("No settings match “"+q+"”.",15,muted,false);none.setGravity(Gravity.CENTER);none.setPadding(dp(24),dp(32),dp(24),dp(24));body.addView(none);}
            return;}
        android.content.SharedPreferences prefs=getSharedPreferences("paired",MODE_PRIVATE);
        // Who this is connected as.
        LinearLayout hero=row();hero.setPadding(dp(16),dp(16),dp(16),dp(16));hero.setBackground(shape(pageCard(),24));hero.setElevation(dp(2));pressDim(hero);
        ImageView logo=menuChip(R.drawable.ic_chat,BLUE);logo.setPadding(dp(11),dp(11),dp(11),dp(11));logo.setBackground(shape(BLUE,16));hero.addView(logo,new LinearLayout.LayoutParams(dp(52),dp(52)));
        LinearLayout who=vertical();who.setPadding(dp(14),0,dp(4),0);who.addView(text("Message Center",18,ink,true));
        TextView state=text(PairedService.status(this),13,muted,false);state.setPadding(0,dp(2),0,0);who.addView(state);TextView version=text("Version "+BuildConfig.VERSION_NAME,12,muted,false);version.setPadding(0,dp(2),0,0);who.addView(version);
        hero.addView(who,new LinearLayout.LayoutParams(0,-2,1));hero.addView(text("›",20,muted,false));hero.setContentDescription("Connection: "+PairedService.status(this));hero.setOnClickListener(v->openSettingsPage("connection"));
        LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(-1,-2);hl.setMargins(dp(1),dp(8),dp(1),dp(6));body.addView(hero,hl);
        // Each page, with a word on what's set there.
        String typing=prefs.getBoolean("send_typing",true)?"Typing shown":"Typing hidden",undo=undoSendOn()?"undo send "+undoSendSeconds()+" s":"no undo send";
        LinearLayout pages=settingsGroup(body,null);
        settingRow(pages,R.drawable.ic_chat,0xff3b82f6,"Messages",typing+" · "+undo+" · quick replies, effects, previews",null,()->openSettingsPage("messages"));
        settingRow(pages,R.drawable.ic_gallery,0xfff59e0b,"Photos & media","Photos "+PhotoQuality.label(photoQuality()).toLowerCase(Locale.ROOT)+" · videos "+PhotoQuality.label(videoQuality()).toLowerCase(Locale.ROOT)+" · text in pictures "+(PhotoText.enabled(this)?"on":"off"),null,()->openSettingsPage("media"));
        boolean allowed=checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;int nudge=Nudges.minutes(this);
        settingRow(pages,R.drawable.ic_bell,0xff8b5cf6,"Notifications",(allowed?NotificationPrivacy.label(NotificationPrivacy.mode(this)):"Off")+" · "+(nudge<=0?"no reminders":"reminders every "+(nudge==60?"hour":(nudge/60)+" hours"))+" · alerts, bubbles",null,()->openSettingsPage("notifications"));
        long arUntil=PairedService.autoReplyUntil(this);int arrivals=Arrivals.all(this).size();
        settingRow(pages,R.drawable.ic_bolt,0xff14b8a6,"Automation",(arUntil>System.currentTimeMillis()?"Busy reply on":"Busy reply off")+" · "+(arrivals>0?arrivals+" arrival "+(arrivals==1?"text":"texts"):"arrival texts")+" · codes",null,()->openSettingsPage("automation"));
        String themeMode=prefs.getString("theme","system");
        settingRow(pages,R.drawable.ic_palette,0xffec4899,"Appearance",(themeMode.equals("light")?"Light":themeMode.equals("dark")?"Dark":themeMode.equals("black")?"Black":"Matches phone")+" · text "+scaleLabel(textScale).toLowerCase(Locale.ROOT)+" · chat color",null,()->openSettingsPage("appearance"));
        MusicLinks.App music=MusicApps.chosen(this,false);
        settingRow(pages,R.drawable.ic_app,0xff0ea5e9,"Apps",(music!=null?"Music in "+music.label+" · podcasts":"Music, podcasts")+", maps & calendar",null,()->openSettingsPage("apps"));
        boolean restricted=batteryRestricted();
        settingRow(pages,R.drawable.ic_link,0xff22c55e,"Connection",restricted?"Background activity needs your OK":"Reconnect, pair again, background activity",null,()->openSettingsPage("connection"));
        settingRow(pages,R.drawable.ic_backup,0xff64748b,"Data & about","Back up, restore, report a problem, updates",null,()->openSettingsPage("data"));}
    /** One settings page's rows (also used, filtered, by search). */
    private void settingsPageRows(String page,LinearLayout content){android.content.SharedPreferences prefs=getSharedPreferences("paired",MODE_PRIVATE);
        switch(page){
        case "messages":{LinearLayout g=settingsGroup(content,"While you chat");
            settingSwitch(g,R.drawable.ic_keyboard,0xff3b82f6,"Typing indicators","Let others see when you're typing",prefs.getBoolean("send_typing",true),on->prefs.edit().putBoolean("send_typing",on).apply());
            int phone=chatSettings.phoneReceipts();
            settingRow(g,R.drawable.ic_eye,0xff6366f1,"Read receipts","Chosen per chat: long-press a chat to turn them on",null,()->new AlertDialog.Builder(this).setTitle("Read receipts")
                .setMessage("Off for every chat unless you turn them on for that chat (long-press it in the list, or tap its name at the top of the chat). When on, opening the chat here marks it read on your phone and Google Messages sends a receipt. When off, the chat isn't marked read, so it stays unread in Google Messages.\n\nGoogle Messages' own \"Send read receipts\" setting: "+(phone==1?"on.":phone==0?"off, so no receipts are sent for any chat.":"not reported yet.")+"\n\nPins and chat themes are kept in this app only; pinning here doesn't change Google Messages.").setPositiveButton("OK",null).show());
            settingRow(g,R.drawable.ic_undo,0xfff97316,"Undo send","How long you have to take a message back after tapping send",undoSendOn()?undoSendSeconds()+(undoSendSeconds()==1?" second":" seconds"):"Off",this::chooseUndoSend);
            settingRow(g,R.drawable.ic_send,0xff0ea5e9,"Quick replies","Saved phrases above the message box",quickRepliesOn()?quickReplies().size()+" saved":"Off",this::editQuickReplies);
            LinearLayout show=settingsGroup(content,"What chats show");
            settingSwitch(show,R.drawable.ic_sparkle,0xffa855f7,"Message effects","Balloons, Slam, invisible ink and other effects iPhones send, and phrases like “Happy birthday”",effectsOn(),on->prefs.edit().putBoolean("message_effects",on).apply());
            settingSwitch(show,R.drawable.ic_link,0xff14b8a6,"Link previews","Only for contacts and chats you've written in",linkPreviewsOn(),on->prefs.edit().putBoolean("link_previews",on).apply());
            settingSwitch(show,R.drawable.ic_call,0xff22c55e,"Calls in chats","A line for each call with the person and how long it lasted. Only on this phone, never sent",callsOn(),on->{
                prefs.edit().putBoolean("show_calls",on).apply();if(on&&checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG},CALL_LOG_PERMISSION);});
            settingSwitch(show,R.drawable.ic_tag,0xfff59e0b,"Separate promotions","Texts from businesses and short codes go in Promotions",promotionsSeparate(),on->prefs.edit().putBoolean("promotions_separate",on).apply());
            if(!Avatars.canUsePhonePhotos(this))settingRow(show,R.drawable.ic_contact,0xff22c55e,"Use names and photos from contacts","Full first names (like \"Hannah Kay\") and contact photos",null,()->{if(shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)||!prefs.getBoolean("asked_contacts",false)){prefs.edit().putBoolean("asked_contacts",true).apply();requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS_PERMISSION);}
                else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));});
            break;}
        case "media":{LinearLayout send=settingsGroup(content,"What you send");
            settingRow(send,R.drawable.ic_gallery,0xfff59e0b,"Photo quality","Photos prepare on this phone while you type",PhotoQuality.label(photoQuality()),this::choosePhotoQuality);
            settingRow(send,R.drawable.ic_videocam,0xffef4444,"Video quality",VideoPlan.describe(videoQuality()),PhotoQuality.label(videoQuality()),this::chooseVideoQuality);
            settingRow(send,R.drawable.ic_file,0xff64748b,"Saved attachments","Queued photos and videos survive closing the app; unconfirmed sends wait for review",String.valueOf(new MediaOutbox(this).all().size()),this::savedAttachments);
            LinearLayout get=settingsGroup(content,"What you receive");
            settingSwitch(get,R.drawable.ic_download,0xff3b82f6,"Download full-quality photos","Off shows previews only and uses less data",fullPhotos(),on->prefs.edit().putBoolean("full_photos",on).apply());
            settingSwitch(get,R.drawable.ic_search,0xff8b5cf6,"Search text in pictures","Reads printed English text in downloaded photos, on this phone (open-source Tesseract)",PhotoText.enabled(this),on->{prefs.edit().putBoolean("photo_text",on).apply();if(on)PhotoText.indexDownloaded(this,true);else PhotoText.clear(this);searchedFor="";});
            int indexed;try(PhotoText index=new PhotoText(this)){indexed=index.count();}
            settingRow(get,R.drawable.ic_refresh,0xff14b8a6,"Scan downloaded pictures","Includes older pictures already downloaded here; does not download remote photos",indexed+" indexed",()->{PhotoText.indexDownloaded(this,true);toast("Indexing downloaded pictures. Search results update as they finish.");});
            break;}
        case "notifications":{LinearLayout alerts=settingsGroup(content,null);
            boolean allowed=checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
            settingRow(alerts,R.drawable.ic_bell,0xff8b5cf6,"Notifications",allowed?"Sound, vibration and per-chat options":"Turn on to hear about new messages",allowed?"On":"Off",()->{if(!allowed)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));});
            String shows=NotificationPrivacy.mode(this);
            int ownChats=NotificationPrivacy.ownCount(this);String ownNote=ownChats==0?" Change it for one chat in its Details.":" "+ownChats+(ownChats==1?" chat has":" chats have")+" its own (in Details).";
            settingRow(alerts,R.drawable.ic_eye,0xff6366f1,"What notifications show",(shows.equals(NotificationPrivacy.ALL)?"Who it's from and what they said.":shows.equals(NotificationPrivacy.NAME)?"Who it's from, not what they said.":"Just “New message”. Bubbles are off, since they show who it is.")+ownNote,NotificationPrivacy.label(shows),()->{
                List<String> labels=java.util.Arrays.asList("Name and message","Name only","Nothing (just “New message”)");String[] values={NotificationPrivacy.ALL,NotificationPrivacy.NAME,NotificationPrivacy.NONE};List<Runnable> actions=new ArrayList<>();
                for(String v:values)actions.add(()->{NotificationPrivacy.set(this,v);PairedService.refreshChatNotifications(this);if(screen.equals("settings"))settings();});
                menuSheet("What notifications show",labels,actions);});
            String lock=NotificationPrivacy.lockMode(this);
            settingRow(alerts,R.drawable.ic_lock,0xff0ea5e9,"On the lock screen",lock.equals(NotificationPrivacy.NAME)?"Who it's from, not what they said, until you unlock.":lock.equals(NotificationPrivacy.NONE)?"Just “New message” until you unlock.":"Shows the same as when your phone is unlocked.",NotificationPrivacy.lockLabel(lock),()->{
                List<String> labels=java.util.Arrays.asList("Same as when unlocked","Name only","Nothing (just “New message”)");String[] values={NotificationPrivacy.SAME,NotificationPrivacy.NAME,NotificationPrivacy.NONE};List<Runnable> actions=new ArrayList<>();
                for(String v:values)actions.add(()->{NotificationPrivacy.setLockMode(this,v);PairedService.refreshChatNotifications(this);if(screen.equals("settings"))settings();});
                menuSheet("While your phone is locked, notifications show",labels,actions);});
            settingRow(alerts,R.drawable.ic_chat,0xff14b8a6,"Bubbles","Chats that float over other apps",null,()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
            settingRow(alerts,R.drawable.ic_notification,0xff64748b,"Hide \"Message Center is running\"","Android requires it while connected. Turn it off and messages still arrive; you're told if the connection drops.",null,()->{try{startActivity(PairedService.channelSettings(this,PairedService.RUNNING));}catch(Exception e){openAppSettings();}});
            LinearLayout words=settingsGroup(content,"Alerts that get through");
            String keywords=prefs.getString("alert_keywords","");
            settingRow(words,R.drawable.ic_search,0xfff59e0b,"Keyword alerts","Notify for these words, even in muted chats",keywords.isEmpty()?"None":keywords,this::editKeywords);
            String mentionNames=prefs.getString("mention_name","");
            settingRow(words,R.drawable.ic_at,0xff3b82f6,"Mention alerts","Notify when someone @mentions you",mentionNames.isEmpty()?"Off":"@"+mentionNames,this::editMentionNames);
            LinearLayout remind=settingsGroup(content,"Reminders");
            int nudge=Nudges.minutes(this);
            TextView reminderLine=settingRow(remind,R.drawable.ic_clock,0xffef4444,"Unread reminders",nudge>0?"Checking…":"For pinned chats you haven't read",nudge<=0?"Off":nudge==60?"Every hour":"Every "+(nudge/60)+" hours",()->{
                List<String> labels=java.util.Arrays.asList("Every hour","Every 2 hours","Every 4 hours","Off");int[] values={60,120,240,0};List<Runnable> actions=new ArrayList<>();
                for(int v:values)actions.add(()->{decoder.execute(()->Nudges.setMinutes(this,v));ui.postDelayed(()->{if(screen.equals("settings"))settings();},150);});
                menuSheet("Remind me about unread pinned chats",labels,actions);});
            if(nudge>0){if(settingsFilter==null)decoder.execute(()->{Nudges.reschedule(this);String line=Nudges.summary(this);ui.post(()->reminderLine.setText(line+"\nUp to 3 times, not 10 PM–8 AM, not for muted or snoozed chats."));});
                settingRow(remind,R.drawable.ic_send,0xff64748b,"Send a test reminder",null,null,()->{Nudges.test(this);toast("Sent. Check your notifications.");});}
            break;}
        case "automation":{LinearLayout busy=settingsGroup(content,"When you're busy");
            long arUntil=PairedService.autoReplyUntil(this);boolean arOn=arUntil>System.currentTimeMillis();
            settingRow(busy,R.drawable.ic_bolt,0xff14b8a6,"Busy auto-reply",arOn?"“"+PairedService.autoReplyText(this)+"”":"Answers saved contacts once an hour while you're busy",arOn?(arUntil==Long.MAX_VALUE?"On":"Until "+timeOnly(arUntil)):"Off",this::autoReplyMenu);
            settingRow(busy,R.drawable.ic_add,0xff64748b,"Busy tile","Turn busy auto-reply on or off from the pull-down shade",null,this::addBusyTile);
            LinearLayout places=settingsGroup(content,"Places");
            int arrivals=Arrivals.all(this).size(),placeCount=Arrivals.places(this).size();
            settingRow(places,R.drawable.ic_arrive,0xff22c55e,"Text on arrival","Texts someone when you get to a place you pick (set it up from a chat's ⊕)",arrivals>0?arrivals+" on":placeCount>0?placeCount+(placeCount==1?" place":" places"):"Off",this::arrivalSettings);
            LinearLayout codes=settingsGroup(content,"Verification codes");
            settingSwitch(codes,R.drawable.ic_copy,0xff3b82f6,"Auto-copy verification codes","New codes go straight to the clipboard",PairedService.autoCopyCodes(this),on->prefs.edit().putBoolean("auto_copy_codes",on).apply());
            settingSwitch(codes,R.drawable.ic_key,0xfff59e0b,"Delete codes after a day","Verification texts from businesses (not your contacts or group chats) are deleted, in Google Messages too",PairedService.autoDeleteCodes(this),on->prefs.edit().putBoolean("auto_delete_codes",on).apply());
            settingRow(codes,R.drawable.ic_delete,0xffef4444,"Delete old verification codes now",null,null,this::clearOldCodes);
            break;}
        case "appearance":{LinearLayout looks=settingsGroup(content,"Look");
            String themeMode=prefs.getString("theme","system");
            settingRow(looks,R.drawable.ic_palette,0xffec4899,"Theme",themeMode.equals("black")?"True black for OLED screens: those pixels switch off":null,themeMode.equals("light")?"Light":themeMode.equals("dark")?"Dark":themeMode.equals("black")?"Black":"Match phone",
                ()->menuSheet("Theme",java.util.Arrays.asList("Match phone","Light","Dark","Black (saves battery on OLED)"),java.util.Arrays.<Runnable>asList(()->{appTheme("system");settings();},()->{appTheme("light");settings();},()->{appTheme("dark");settings();},()->{appTheme("black");settings();})));
            settingRow(looks,R.drawable.ic_chat,0xff3b82f6,"Chat color","For every chat. Any chat can still have its own (⋮ → Theme in that chat)",ChatTheme.of(chatSettings.allTheme()).label,()->chooseTheme(""));
            settingSwitch(looks,R.drawable.ic_done_all,0xff0ea5e9,"Unread count","How many messages are unread, next to Messages at the top",unreadCountOn(),on->prefs.edit().putBoolean("inbox_unread_count",on).apply());
            settingRow(looks,R.drawable.ic_text_size,0xff8b5cf6,"Text size",null,scaleLabel(textScale),()->{float[] sizes={0.9f,1f,1.15f,1.3f};List<Runnable> actions=new ArrayList<>();
                for(float f:sizes)actions.add(()->{prefs.edit().putFloat("text_scale",f).apply();settings();});
                menuSheet("Text size",java.util.Arrays.asList("Small","Default","Large","Larger"),actions);});
            break;}
        case "apps":{
            LinearLayout apps=settingsGroup(content,"Music and podcasts");
            settingRow(apps,R.drawable.ic_play,0xff22c55e,"Music app","Songs from any service open here: “Open in…” under the link",MusicApps.label(this,false),()->chooseMusicApp(false,a->{if(screen.equals("settings"))settings();}));
            settingRow(apps,R.drawable.ic_mic,0xffa855f7,"Podcast app","Shows and episodes from any app open here",MusicApps.label(this,true),()->chooseMusicApp(true,a->{if(screen.equals("settings"))settings();}));
            LinearLayout places=settingsGroup(content,"Addresses and dates");
            settingRow(places,R.drawable.ic_place,0xffef4444,"Maps app for addresses","Tap an address in a message to open it",mapsAppLabel(),()->chooseMapsApp(pkg->{if(screen.equals("settings"))settings();}));
            settingRow(places,R.drawable.ic_event,0xff3b82f6,"Calendar app for dates","Tap a date or time in a message to add it",calendarAppLabel(),()->chooseCalendarApp(pkg->{if(screen.equals("settings"))settings();}));
            break;}
        case "connection":{LinearLayout link=settingsGroup(content,"Google Messages");
            settingRow(link,R.drawable.ic_refresh,0xff22c55e,"Reconnect","Restart the connection to Google Messages. It also does this on its own when it gets stuck.",null,()->{PairedService.restart(this);new android.os.Handler().postDelayed(()->{if(screen.equals("settings"))settings();},400);});
            boolean restricted=batteryRestricted();
            settingRow(link,R.drawable.ic_battery,0xfff59e0b,"Background activity",restricted?"Battery optimization is on. Allow background activity so messages keep arriving.":"Battery optimization is off. On ColorOS, also allow auto-launch for Message Center and Google Messages.",restricted?"Fix":"OK",restricted?this::allowBackground:this::openAppSettings);
            settingRow(link,R.drawable.ic_open,0xff3b82f6,"Open Google Messages","It must stay your default texting app",null,this::openGoogle);
            settingRow(link,R.drawable.ic_link,0xff8b5cf6,"Sign in / pair again",null,null,this::signIn);
            LinearLayout stop=settingsGroup(content,"Disconnect");
            settingRow(stop,R.drawable.ic_stop,0xff64748b,"Stop background connection","Messages stop arriving here until you reconnect",null,()->{PairedService.disconnect(this,false);settings();});
            TextView forget=settingRow(stop,R.drawable.ic_delete,0xffdc2626,"Forget pairing and synced messages",null,null,()->new AlertDialog.Builder(this).setTitle("Forget this connection?").setMessage("Removes this app's pairing and synced cache from this phone. Your Google Messages chats stay in Google Messages. Remove Message Center in Google's Device pairing list to revoke it there too.").setNegativeButton("Cancel",null).setPositiveButton("Forget",(d,w)->{PairedService.disconnect(this,true);CookieManager.getInstance().removeAllCookies(null);inbox();}).show());
            ((TextView)((LinearLayout)forget.getParent()).getChildAt(0)).setTextColor(danger);
            break;}
        case "data":{LinearLayout data=settingsGroup(content,"Your data");
            settingRow(data,R.drawable.ic_backup,0xff3b82f6,"Back up app data","Starred messages, nicknames, notes, reminders and settings, to Downloads",null,this::backupNow);
            settingRow(data,R.drawable.ic_history,0xff14b8a6,"Restore from a backup",null,null,()->{try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"}),RESTORE_BACKUP);}catch(Exception e){toast("No file picker is available.");}});
            LinearLayout about=settingsGroup(content,"About");
            settingRow(about,R.drawable.ic_info,0xff64748b,"Message Center","Connects this phone directly to Google. Older history loads in pages and may be incomplete.",BuildConfig.VERSION_NAME,null);
            settingRow(about,R.drawable.ic_bug,0xffef4444,"Report a problem","Makes a file with what went wrong for you to send",null,()->reportProblem(""));
            settingRow(about,R.drawable.ic_download,0xff22c55e,"Updates on GitHub",null,null,()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/bags156/message-center/releases"))));
            break;}
        default:break;}}
    /** Asks what happened, then bundles diagnostics (no message text) into a file to send however you like. */
    private void reportProblem(String what){LinearLayout content=vertical();content.setPadding(dp(20),dp(8),dp(20),0);
        EditText e=input("What went wrong? (optional)");e.setSingleLine(false);e.setMinLines(2);e.setText(what);content.addView(e);
        File bad=Diagnostics.lastBadFile();android.widget.CheckBox include=new android.widget.CheckBox(this);
        if(bad!=null){include.setText("Include the picture or video that wouldn't open ("+PairedProtocol.size(bad.length())+")");include.setChecked(true);include.setTextColor(ink);content.addView(include);}
        TextView fine=text("Includes the app's version, your phone model, the connection history and the app's error log. No message text. You choose where to send it next (email, Drive…).",13,muted,false);fine.setPadding(0,dp(10),0,0);content.addView(fine);
        new AlertDialog.Builder(this).setTitle("Report a problem").setView(content).setNegativeButton("Cancel",null).setPositiveButton("Create report",(d,w)->{String note=e.getText().toString().trim();boolean withFile=bad!=null&&include.isChecked();
            String details="Connection: "+PairedService.status(this)+(PairedService.ready()?" (ready)":" (not ready)")+"\n"+PairedService.backgroundSummary()+"\nPicture decoder: "+(HeifDecoder.lastProblem.isEmpty()?"no problems":HeifDecoder.lastProblem)
                +"\nNotifications allowed: "+(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)+"\nBattery optimized: "+batteryRestricted()+"\nReminders: "+Nudges.summary(this).replace('\n',' ');
            decoder.execute(()->{try{File zip=Diagnostics.buildReport(this,note,details,withFile);Uri uri=MediaProvider.uri(zip,zip.getName());
                ui.post(()->{Intent send=new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_SUBJECT,"Message Center problem report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(ClipData.newRawUri(zip.getName(),uri));startActivity(Intent.createChooser(send,"Send the report"));});}
            catch(Exception x){ui.post(()->toast("Couldn't make the report: "+x.getMessage()));}});}).show();}
    /** A group of settings on one raised, rounded card, under a small label (none when title is null). While searching, labelled with the page it's on. */
    private LinearLayout settingsGroup(LinearLayout content,String title){String label=settingsFilter!=null?settingsFilterPage+(title==null?"":" · "+title):title;
        TextView h=null;if(label!=null){h=text(label,13,muted,true);h.setPadding(dp(16),dp(18),dp(16),dp(8));content.addView(h);}
        LinearLayout g=vertical();g.setBackground(shape(pageCard(),22));g.setClipToOutline(true);g.setElevation(dp(2));
        LinearLayout.LayoutParams gl=new LinearLayout.LayoutParams(-1,-2);gl.setMargins(dp(1),label==null?dp(8):0,dp(1),dp(2));content.addView(g,gl);
        if(settingsFilter!=null)settingsGroups.add(new View[]{h==null?new View(this):h,g});return g;}
    private void addSettingRow(LinearLayout group,View row){if(group.getChildCount()>0){View line=new View(this);line.setBackgroundColor((ink&0x00ffffff)|0x14000000);LinearLayout.LayoutParams ll=new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)/2));ll.setMarginStart(dp(60));group.addView(line,ll);}
        group.addView(row,new LinearLayout.LayoutParams(-1,-2));}
    /** Whether a setting is one of the search's results: every word typed is in its name, description, value or page (or words people use for it). */
    private boolean settingShown(String title,String sub,String value){if(settingsFilter==null)return true;
        String hay=ContactSearch.fold(title+" "+(sub==null?"":sub)+" "+(value==null?"":value)+" "+settingsFilterPage+" "+settingKeywords(title));
        for(String w:settingsFilter.trim().split("\\s+")){String f=ContactSearch.fold(w);if(!f.isEmpty()&&!hay.contains(f))return false;}return true;}
    private LinearLayout settingLine(int icon,int color,String title,String sub,TextView[] lineOut){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(14),dp(12),dp(14),dp(12));r.setMinimumHeight(dp(62));
        if(icon!=0)r.addView(menuChip(icon,color),new LinearLayout.LayoutParams(dp(32),dp(32)));
        LinearLayout texts=vertical();texts.setPadding(icon!=0?dp(14):dp(2),0,dp(4),0);texts.addView(text(title,16,ink,false));
        TextView line=text(sub==null?"":sub,13,muted,false);line.setPadding(0,dp(2),0,0);line.setVisibility(sub==null?View.GONE:View.VISIBLE);line.setMaxLines(4);line.setEllipsize(TextUtils.TruncateAt.END);texts.addView(line);
        r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));lineOut[0]=line;return r;}
    /** A setting: a colored icon, its name, an optional line under it, and its current value with a › when tapping it changes it. Returns the line so it can update. */
    private TextView settingRow(LinearLayout group,int icon,int color,String title,String sub,String value,Runnable tap){TextView[] line=new TextView[1];LinearLayout r=settingLine(icon,color,title,sub,line);
        // A long value ("Name and message") goes under the name, so the name isn't squeezed onto two lines.
        boolean below=value!=null&&value.length()>14;
        if(below){TextView v=text(value,14,ink,true);v.setPadding(0,dp(2),0,0);v.setSingleLine(true);v.setEllipsize(TextUtils.TruncateAt.END);((LinearLayout)line[0].getParent()).addView(v,1);}
        String shown=(value==null||below?"":value)+(tap==null?"":(value==null||value.isEmpty()||below?"":"  ")+"›");
        if(!shown.isEmpty()){TextView v=text(shown,14,muted,false);v.setPadding(dp(10),0,0,0);v.setMaxWidth(dp(160));v.setSingleLine(true);v.setEllipsize(TextUtils.TruncateAt.MIDDLE);r.addView(v);}
        if(tap!=null){pressable(r);r.setOnClickListener(x->tap.run());}
        r.setContentDescription(title+(value==null?"":", "+value)+(sub==null?"":". "+sub));
        if(settingShown(title,sub,value))addSettingRow(group,r);return line[0];}
    /** An on/off setting with a switch; changing it saves at once and leaves the screen where it is. */
    private void settingSwitch(LinearLayout group,int icon,int color,String title,String sub,boolean on,java.util.function.Consumer<Boolean> change){TextView[] line=new TextView[1];LinearLayout r=settingLine(icon,color,title,sub,line);pressable(r);
        android.widget.Switch sw=new android.widget.Switch(this);sw.setChecked(on);sw.setContentDescription(title);
        int[][] states={{android.R.attr.state_checked},{}};
        sw.setThumbTintList(new ColorStateList(states,new int[]{BLUE,dark?0xffb8bcc6:0xfff4f5f8}));sw.setTrackTintList(new ColorStateList(states,new int[]{(BLUE&0x00ffffff)|0x80000000,(muted&0x00ffffff)|0x60000000}));
        sw.setOnCheckedChangeListener((b,checked)->change.accept(checked));r.setOnClickListener(x->sw.toggle());r.addView(sw);
        if(settingShown(title,sub,on?"on":"off"))addSettingRow(group,r);}
    /** Scrolls to y once the content has been laid out (scrolling before that is ignored). */
    private void restoreScroll(ScrollView s,int y){if(y<=0)return;s.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){int tries;
        @Override public void onGlobalLayout(){View c=s.getChildAt(0);if(c!=null&&c.getHeight()>0){s.scrollTo(0,y);s.getViewTreeObserver().removeOnGlobalLayoutListener(this);}else if(++tries>5)s.getViewTreeObserver().removeOnGlobalLayoutListener(this);}});}
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
    @Override protected void onDestroy(){stopInline();if(drafts!=null)drafts.prefs().unregisterOnSharedPreferenceChangeListener(draftChanged);destroyLogin();cancelRecording();stopAudio();decoder.shutdownNow();super.onDestroy();}
}
