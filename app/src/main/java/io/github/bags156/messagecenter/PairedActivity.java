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
        super.onCreate(state);store=new PairedStore(this);chatSettings=new ChatSettings(this);
        if(state!=null){chatId=state.getString("chat","");replyId=state.getString("reply","");drafts.put(chatId,state.getString("draft",""));}
        String target=getIntent().getStringExtra("chat");if(target!=null&&!target.isEmpty())chatId=target;
        if(bubble()&&chatId.isEmpty()){finish();return;}
        if(!chatId.isEmpty())thread(chatId);else inbox();
        if(new SessionVault(this).exists() && getSharedPreferences("paired",MODE_PRIVATE).getBoolean("enabled",true))PairedService.start(this);
    }
    /** A tapped notification (or bubble) delivers its chat here when this screen is already open. */
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);String id=intent.getStringExtra("chat");if(id!=null&&!id.isEmpty()){if(login!=null)destroyLogin();thread(id);}}
    @Override protected void onResume(){super.onResume();resumed=true;if(!registered){registerReceiver(receiver,new IntentFilter(PairedService.CHANGED),Context.RECEIVER_NOT_EXPORTED);registered=true;}
        if(PairedService.ready()&&screen.equals("pair")){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();}
        else if(!bubble()&&!PairedService.emoji().isEmpty()&&login==null)pairing();
        else {updateStatus();if(screen.equals("chat")){markReadIfAllowed();updateTyping();if(!bubble())PairedService.clearNotification(this,chatId);}else if(screen.equals("inbox"))refreshInbox(false);}}
    @Override protected void onPause(){resumed=false;remember();ui.removeCallbacks(typingTick);if(registered){unregisterReceiver(receiver);registered=false;}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){remember();out.putString("chat",chatId);out.putString("reply",replyId);out.putString("draft",drafts.getOrDefault(chatId,""));super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(screen.equals("details")){thread(chatId);return;}if(bubble()){super.onBackPressed();return;}if(login!=null){destroyLogin();inbox();}else if(screen.equals("inbox")&&folder==2){folder=1;inbox();}else if(!screen.equals("inbox")){remember();inbox();}else super.onBackPressed();}

    // ---- Shared building blocks ----
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void base(String name){
        screen=name;statusView=null;composer=null;items=null;scroll=null;searchRow=null;replyPanel=null;typingRow=null;messageViews.clear();ui.removeCallbacks(typingTick);if(typingPulse!=null){typingPulse.cancel();typingPulse=null;}
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
        i.setContentDescription(label);if(action!=null){i.setOnClickListener(v->action.run());i.setBackground(getDrawable(android.R.drawable.list_selector_background));}
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
        LinearLayout bar=row();TextView title=text(folder==2?"Archived":"Messages",26,ink,false);title.setPadding(dp(folder==2?8:4),0,0,0);
        if(folder==2)bar.addView(icon(R.drawable.ic_back,ink,"Back",()->{folder=1;inbox();}));
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if(new SessionVault(this).exists()){bar.addView(icon(R.drawable.ic_search,ink,"Search",this::toggleSearch));bar.addView(icon(R.drawable.ic_more,ink,"More options",null));
            View more=bar.getChildAt(bar.getChildCount()-1);more.setBackground(getDrawable(android.R.drawable.list_selector_background));more.setOnClickListener(this::inboxMenu);}
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));connection();
        if(!new SessionVault(this).exists()){
            note(root,"On-device pairing • experimental\n\nConnect directly to your Google Messages account. Google Messages stays installed as your default SMS/RCS app. No extra server is required.");
            root.addView(button("Connect Google Messages",true,this::signIn));gap(root,12);
            note(root,"This replaces the earlier notification companion. Your old notification captures are separate from synced conversations. Full archive coverage and background delivery need testing on your phone.");return;
        }
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
        m.add(0,1,0,folder==1?"Archived":"Inbox");m.add(0,2,0,"Refresh");m.add(0,3,0,"Settings");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 1->{folder=folder==1?2:1;inbox();}case 2->refreshInbox(true);case 3->settings();default->{}}return true;});menu.show();}
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
        entry.setBackground(getDrawable(android.R.drawable.list_selector_background));
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
    private void chatOptions(JSONObject c){String id=c.optString("conversationID");boolean pinned=chatSettings.pinned(c),receipts=chatSettings.receipts(id);
        boolean group=PairedProtocol.other(c)==null;
        String[] options={pinned?"Unpin":"Pin to top","Chat theme…",receipts?"Turn off read receipts for this chat":"Turn on read receipts for this chat",group?"Group details":"Details","Open as bubble"};
        new AlertDialog.Builder(this).setTitle(PairedProtocol.title(c)).setItems(options,(d,which)->{
            switch(which){case 0->togglePin(c);case 1->chooseTheme(id);case 2->toggleReceipts(id);case 3->{chatId=id;details();}default->openBubble(id);}}).show();}
    private void openBubble(String id){String problem=PairedService.showBubble(this,id);if(!problem.isEmpty())toast(problem);}
    private void togglePin(JSONObject c){String id=c.optString("conversationID");boolean now=!chatSettings.pinned(c);chatSettings.setPinned(id,now);
        toast(now?"Pinned to the top of Message Center":"Unpinned");if(screen.equals("inbox"))renderInbox();}
    private void chooseTheme(String id){
        LinearLayout list=vertical();list.setPadding(dp(8),dp(8),dp(8),dp(8));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Chat theme").setView(list).setNegativeButton("Cancel",null).create();
        String current=chatSettings.theme(id).key;
        for(ChatTheme t:ChatTheme.ALL){LinearLayout r=row();r.setPadding(dp(16),dp(10),dp(16),dp(10));r.setBackground(getDrawable(android.R.drawable.list_selector_background));
            View swatch=new View(this);GradientDrawable sw=oval(t.bubble(dark));sw.setStroke(dp(3),t.accent(dark));swatch.setBackground(sw);r.addView(swatch,new LinearLayout.LayoutParams(dp(32),dp(32)));
            TextView label=text(t.label,16,ink,t.key.equals(current));label.setPadding(dp(16),0,0,0);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            if(t.key.equals(current))r.addView(text("✓",18,t.accent(dark),true));
            r.setOnClickListener(v->{chatSettings.setTheme(id,t.key);dialog.dismiss();if(screen.equals("chat")&&chatId.equals(id))thread(id);});list.addView(r);}
        dialog.show();}
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
        ImageView more=icon(R.drawable.ic_more,ink,"More options",null);more.setBackground(getDrawable(android.R.drawable.list_selector_background));more.setOnClickListener(this::chatMenu);bar.addView(more);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));connection();
        scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);items=vertical();items.setPadding(dp(12),dp(8),dp(12),dp(8));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{if(y==0&&oy>0)loadOlderMessages();});
        typingRow=row();typingRow.setPadding(dp(14),dp(2),dp(14),dp(6));typingRow.setVisibility(View.GONE);
        TextView dots=text("• • •",13,muted,true);dots.setPadding(dp(14),dp(6),dp(14),dp(6));dots.setBackground(shape(surface,16));typingRow.addView(dots);
        typingLabel=text("",13,muted,false);typingLabel.setPadding(dp(10),0,0,0);typingRow.addView(typingLabel);root.addView(typingRow);
        typingPulse=ObjectAnimator.ofFloat(dots,"alpha",1f,0.35f);typingPulse.setDuration(650);typingPulse.setRepeatCount(ValueAnimator.INFINITE);typingPulse.setRepeatMode(ValueAnimator.REVERSE);
        replyPanel=row();replyPanel.setVisibility(View.GONE);replyPanel.setPadding(dp(16),dp(6),dp(4),dp(6));
        View accentBar=new View(this);accentBar.setBackground(shape(theme.accent(dark),2));replyPanel.addView(accentBar,new LinearLayout.LayoutParams(dp(3),dp(38)));
        LinearLayout replyTexts=vertical();replyTexts.setPadding(dp(10),0,dp(8),0);replyLabel=text("",13,theme.accent(dark),true);replyQuote=text("",14,muted,false);
        replyQuote.setSingleLine(true);replyQuote.setEllipsize(TextUtils.TruncateAt.END);replyTexts.addView(replyLabel);replyTexts.addView(replyQuote);
        replyPanel.addView(replyTexts,new LinearLayout.LayoutParams(0,-2,1));replyPanel.addView(icon(R.drawable.ic_close,muted,"Cancel reply",this::cancelReply));root.addView(replyPanel);
        LinearLayout compose=row();compose.setPadding(dp(8),dp(4),dp(8),0);
        composer=new EditText(this);composer.setHint(PairedProtocol.rcs(c)?"RCS message":"Text message");composer.setTextColor(ink);composer.setHintTextColor(muted);composer.setTextSize(17);
        composer.setBackground(shape(surface,28));composer.setPadding(dp(22),dp(14),dp(22),dp(14));composer.setMaxLines(5);
        composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setText(drafts.getOrDefault(id,""));
        composer.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){maybeSendTyping(s);}public void afterTextChanged(Editable e){}});
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        ImageView sendButton=new ImageView(this);sendButton.setImageResource(R.drawable.ic_send);sendButton.setImageTintList(ColorStateList.valueOf(theme.onAccent(dark)));sendButton.setBackground(oval(theme.accent(dark)));
        sendButton.setPadding(dp(14),dp(14),dp(14),dp(14));sendButton.setContentDescription("Send");sendButton.setOnClickListener(v->send());
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(dp(52),dp(52));sl.setMarginStart(dp(8));compose.addView(sendButton,sl);root.addView(compose);
        if(!bubble())PairedService.clearNotification(this,id);renderMessages(true);if(PairedService.ready())loadMessages(false);markReadIfAllowed();updateTyping();
        if(c!=null)requestPhotos(java.util.Collections.singletonList(c));
    }
    private void chatMenu(View anchor){JSONObject c=store.chat(chatId);if(c==null)return;PopupMenu menu=new PopupMenu(this,anchor);Menu m=menu.getMenu();
        m.add(0,1,0,chatSettings.pinned(c)?"Unpin":"Pin to top");m.add(0,2,1,"Chat theme");
        m.add(0,3,2,"Read receipts for this chat").setCheckable(true).setChecked(chatSettings.receipts(chatId));
        m.add(0,6,0,PairedProtocol.other(c)==null?"Group details":"Details");
        if(bubble())m.add(0,8,5,"Open in Message Center");else m.add(0,7,5,"Open as bubble");
        m.add(0,4,6,"Refresh");m.add(0,5,7,"Load older messages");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 1->togglePin(c);case 2->chooseTheme(chatId);case 3->toggleReceipts(chatId);case 4->loadMessages(false);case 5->loadOlderMessages();
            case 6->details();case 7->openBubble(chatId);case 8->startActivity(new Intent(this,PairedActivity.class).setAction(OPEN_CHAT).putExtra("chat",chatId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP));default->{}}return true;});menu.show();}
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
        scroll.post(()->{if(scroll==null)return;if(anchorOlder){anchorOlder=false;scroll.scrollTo(0,y+Math.max(0,scroll.getChildAt(0).getHeight()-before));}else if(atBottom)scroll.fullScroll(View.FOCUS_DOWN);else scroll.scrollTo(0,y);});
    }
    private View bubble(JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,boolean joinPrev,boolean joinNext){
        LinearLayout wrap=vertical();String body=PairedProtocol.body(m),fallback=ChatText.replyFallbackBody(body);
        String target=PairedProtocol.string(m.optJSONObject("replyMessage"),"messageID");
        if(!target.isEmpty()||fallback!=null)addReplyContext(wrap,m,c,theme,outgoing,target);
        LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);
        LinearLayout b=vertical();b.setPadding(dp(16),dp(11),dp(16),dp(11));
        float big=dp(22),small=dp(5);float top=joinPrev?small:big,bottomR=joinNext?small:big;
        GradientDrawable shape=new GradientDrawable();shape.setColor(outgoing?theme.bubble(dark):surface);
        shape.setCornerRadii(outgoing?new float[]{big,big,top,top,bottomR,bottomR,big,big}:new float[]{top,top,big,big,big,big,bottomR,bottomR});b.setBackground(shape);
        TextView text=text(fallback!=null?fallback:body,17,outgoing?theme.onBubble(dark):ink,false);text.setLineSpacing(0,1.1f);text.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.addView(text);
        b.setOnLongClickListener(v->{messageSheet(m);return true;});b.setOnClickListener(v->{});
        line.addView(b,new LinearLayout.LayoutParams(-2,-2));wrap.addView(line,new LinearLayout.LayoutParams(-1,-2));return wrap;
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
        if(PairedProtocol.failed(m)){r.addView(text("Not sent",12,danger,true));return r;}
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
        if(body.trim().isEmpty())return;if(c==null){error("Refresh this conversation before sending.");return;}
        busy=true;PairedService.execute(client->client.sendText(c.toString(),body,reply),(value,e)->{busy=false;
            if(!e.isEmpty()){error(e);return;}drafts.remove(id);if(screen.equals("chat")&&chatId.equals(id)){composer.setText("");cancelReply();}
            if(screen.equals("chat")&&chatId.equals(id))loadMessages(false);});}
    private void newMessage(){if(!PairedService.ready()){toast("Connect first, then start a chat.");return;}
        EditText number=input("+15551234567");number.setInputType(InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(this).setTitle("Start chat").setMessage("Enter the full phone number including + and country code.").setView(number)
            .setNegativeButton("Cancel",null).setPositiveButton("Open conversation",(d,w)->{
                startChat(number.getText().toString().replaceAll("[\\s()\\-]",""));
            }).show();}
    private void startChat(String value){if(!PairedService.ready()){toast("Connect first, then start a chat.");return;}
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
        sheetAction(content,"Copy text",()->{d[0].dismiss();getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Message",displayBody(m)));});
        if(!reactors.isEmpty())sheetAction(content,"See who reacted",()->{d[0].dismiss();reactionSheet(m,c);});
        d[0]=sheet(content);}
    private void sheetAction(LinearLayout parent,String label,Runnable action){TextView t=text(label,17,ink,false);t.setPadding(dp(8),dp(14),dp(8),dp(14));
        t.setBackground(getDrawable(android.R.drawable.list_selector_background));t.setOnClickListener(v->action.run());parent.addView(t,new LinearLayout.LayoutParams(-1,-2));}
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
            LinearLayout r=row();r.setPadding(dp(4),dp(10),dp(4),dp(10));r.setBackground(getDrawable(android.R.drawable.list_selector_background));r.addView(personAvatar(p,name,44));
            LinearLayout texts=vertical();texts.setPadding(dp(16),0,dp(8),0);texts.addView(text(name,17,ink,false));if(!number.isEmpty()&&!number.equals(name))texts.addView(text(number,14,muted,false));
            r.addView(texts,new LinearLayout.LayoutParams(0,-2,1));r.setOnClickListener(v->memberActions(p));content.addView(r);}
        if(group){LinearLayout me=row();me.setPadding(dp(4),dp(10),dp(4),dp(10));me.addView(personAvatar(null,"You",44));TextView you=text("You",17,ink,false);you.setPadding(dp(16),0,0,0);me.addView(you);content.addView(me);}
        gap(content,20);content.addView(text("This chat",14,muted,true));gap(content,4);
        sheetAction(content,chatSettings.pinned(c)?"Unpin":"Pin to top",()->{togglePin(c);details();});
        sheetAction(content,"Chat theme: "+chatSettings.theme(id).label,()->chooseTheme(id));
        sheetAction(content,"Read receipts: "+(chatSettings.receipts(id)?"on":"off"),()->toggleReceipts(id));
        if(!bubble())sheetAction(content,"Open as bubble",()->openBubble(id));
        requestMemberPhotos(c,()->{if(screen.equals("details")&&chatId.equals(id))details();});}
    private void memberActions(JSONObject p){String number=PairedProtocol.number(p);if(number.isEmpty())return;String name=PairedProtocol.name(p);
        new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Message","Call","Copy number"},(d,which)->{
            if(which==0)startChat(number);
            else if(which==1){try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));}catch(Exception e){toast("No phone app is available.");}}
            else getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Number",number));}).show();}
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
