package io.github.bags156.messagecenter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.graphics.Outline;
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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Experimental, phone-hosted paired client. Does not read other apps' notifications. */
public final class PairedActivity extends Activity {
    private static final int BLUE=0xff2864f0;
    private static final long LOGIN_POLL_MS=1500;
    private static final String[] REACTIONS={"❤️","👍","👎","😂","😮","😢","❓","🎉"};
    private int bg,card,ink,muted,surface,fab,onFab,danger,folder=1,limit=300;
    private boolean dark;
    private PairedStore store;private ChatSettings chatSettings;
    private FrameLayout frame;private LinearLayout root,items;private TextView statusView,replyView;
    private ScrollView scroll;private EditText composer;private WebView login;private LinearLayout searchRow;
    private String screen="inbox",chatId="",query="",replyId="",markingRead="";
    private final Map<String,String> drafts=new HashMap<>();
    private final Map<String,Bitmap> photos=new HashMap<>();
    private final Set<String> photosRequested=new HashSet<>();
    private boolean registered,pairSubmitted,busy,resumed,anchorOlder;private String shownEmoji="";
    private long lastInboxRefresh;
    private TextView loginAddress;private int signedInPolls,loginSkips;
    private final android.os.Handler loginTimer=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable loginPoll=this::pollLogin;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(!PairedService.emoji().isEmpty() && !shownEmoji.equals(PairedService.emoji())){destroyLogin();pairing();return;}
        if(screen.equals("pair") && PairedService.ready()){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();return;}
        updateStatus();
        if(screen.equals("inbox"))renderInbox();else if(screen.equals("chat")){renderMessages(false);markReadIfAllowed();}
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);store=new PairedStore(this);chatSettings=new ChatSettings(this);
        if(state!=null){chatId=state.getString("chat","");replyId=state.getString("reply","");drafts.put(chatId,state.getString("draft",""));}
        String target=getIntent().getStringExtra("chat");if(target!=null&&!target.isEmpty())chatId=target;
        if(!chatId.isEmpty())thread(chatId);else inbox();
        if(new SessionVault(this).exists() && getSharedPreferences("paired",MODE_PRIVATE).getBoolean("enabled",true))PairedService.start(this);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);String id=intent.getStringExtra("chat");if(id!=null&&!id.isEmpty())thread(id);}
    @Override protected void onResume(){super.onResume();resumed=true;if(!registered){registerReceiver(receiver,new IntentFilter(PairedService.CHANGED),Context.RECEIVER_NOT_EXPORTED);registered=true;}
        if(PairedService.ready()&&screen.equals("pair")){CookieManager.getInstance().removeAllCookies(null);inbox();askNotifications();}
        else if(!PairedService.emoji().isEmpty()&&login==null)pairing();
        else {updateStatus();if(screen.equals("chat"))markReadIfAllowed();else if(screen.equals("inbox"))refreshInbox(false);}}
    @Override protected void onPause(){resumed=false;remember();if(registered){unregisterReceiver(receiver);registered=false;}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){remember();out.putString("chat",chatId);out.putString("reply",replyId);out.putString("draft",drafts.getOrDefault(chatId,""));super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){if(login!=null){destroyLogin();inbox();}else if(screen.equals("inbox")&&folder==2){folder=1;inbox();}else if(!screen.equals("inbox")){remember();inbox();}else super.onBackPressed();}

    // ---- Shared building blocks ----
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void base(String name){
        screen=name;statusView=null;composer=null;items=null;scroll=null;searchRow=null;replyView=null;
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
    private static final int[] FALLBACK={0xff1a73e8,0xff188038,0xffe37400,0xffa142f4,0xffd93025,0xff007b83,0xff9334e6,0xffc5221f};
    private int avatarColor(JSONObject chat){
        JSONObject other=PairedProtocol.other(chat);String title=chat==null?"":PairedProtocol.title(chat);
        int fallback=FALLBACK[Math.floorMod(title.hashCode(),FALLBACK.length)];
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
        if(b!=null){ImageView img=new ImageView(this);img.setImageBitmap(b);img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline o){o.setOval(0,0,v.getWidth(),v.getHeight());}});img.setClipToOutline(true);
            box.addView(img,new FrameLayout.LayoutParams(-1,-1));return box;}
        box.setBackground(oval(avatarColor(chat)));
        if(chat!=null&&PairedProtocol.other(chat)==null&&chat.optBoolean("isGroupChat")){
            ImageView people=new ImageView(this);people.setImageResource(R.drawable.ic_people);people.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            int pad=dp(size/4f);people.setPadding(pad,pad,pad,pad);box.addView(people,new FrameLayout.LayoutParams(-1,-1));return box;}
        TextView letter=text(PairedProtocol.initial(chat==null?"":PairedProtocol.title(chat)),Math.round(size*0.42f),Color.WHITE,false);letter.setGravity(Gravity.CENTER);
        box.addView(letter,new FrameLayout.LayoutParams(-1,-1));return box;
    }
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
    private void inbox(){remember();chatId="";replyId="";base("inbox");
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
        String snippet=PairedProtocol.string(c.optJSONObject("latestMessage"),"displayContent");
        if(!draft.isEmpty())snippet="You: "+draft;else if(PairedProtocol.latestFromMe(c)&&!snippet.isEmpty())snippet="You: "+snippet;
        TextView preview=text(snippet,15,unread?ink:muted,unread);preview.setMaxLines(2);preview.setEllipsize(TextUtils.TruncateAt.END);
        if(!draft.isEmpty())preview.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.ITALIC));
        gap(middle,2);middle.addView(preview);entry.addView(middle,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout side=vertical();side.setGravity(Gravity.END|Gravity.TOP);
        long when=PairedProtocol.time(c);TextView stamp=text(!draft.isEmpty()?"Draft":(when>0?inboxStamp(when):""),13,unread?ink:muted,unread);
        if(!draft.isEmpty())stamp.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.ITALIC));side.addView(stamp);
        if(pinned){ImageView pin=new ImageView(this);pin.setImageResource(R.drawable.ic_pin);pin.setImageTintList(ColorStateList.valueOf(muted));pin.setContentDescription("Pinned");
            LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(dp(22),dp(22));pl.gravity=Gravity.END;pl.topMargin=dp(8);side.addView(pin,pl);}
        else if(unread){View dot=new View(this);dot.setBackground(oval(ChatTheme.of("default").accent(dark)));LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(dp(10),dp(10));dl.gravity=Gravity.END;dl.topMargin=dp(12);side.addView(dot,dl);}
        entry.addView(side,new LinearLayout.LayoutParams(-2,-1));
        entry.setOnClickListener(v->thread(id));entry.setOnLongClickListener(v->{chatOptions(c);return true;});
        return entry;
    }
    /** Pin, theme and read-receipt options for one conversation (long-press in the list). */
    private void chatOptions(JSONObject c){String id=c.optString("conversationID");boolean pinned=chatSettings.pinned(c),receipts=chatSettings.receipts(id);
        String[] options={pinned?"Unpin":"Pin to top","Chat theme…",receipts?"Turn off read receipts for this chat":"Turn on read receipts for this chat"};
        new AlertDialog.Builder(this).setTitle(PairedProtocol.title(c)).setItems(options,(d,which)->{
            if(which==0)togglePin(c);else if(which==1)chooseTheme(id);else toggleReceipts(id);}).show();}
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
        else {toast("Read receipts on for this chat.");markReadIfAllowed();}}
    private void loadChats(boolean more){if(busy)return;busy=true;int selected=folder;String cursor=more?store.page("folder:"+selected,false):"";
        PairedService.loadChats(cursor,selected,(v,e)->{busy=false;if(!e.isEmpty()&&!more)toast(e);if(screen.equals("inbox"))renderInbox();});}

    // ---- Conversation ----
    private void thread(String id){remember();chatId=id;limit=300;replyId="";base("chat");JSONObject c=store.chat(id);ChatTheme theme=chatSettings.theme(id);
        root.setPadding(dp(4),dp(4),dp(4),dp(8));
        LinearLayout bar=row();bar.addView(icon(R.drawable.ic_back,ink,"Back",this::inbox));View face=avatar(c,40);bar.addView(face);
        TextView name=text(c==null?"Conversation":PairedProtocol.title(c),20,ink,false);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(dp(14),0,dp(8),0);
        bar.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        String number=PairedProtocol.number(PairedProtocol.other(c));
        if(!number.isEmpty())bar.addView(icon(R.drawable.ic_call,ink,"Call",()->{try{startActivity(new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"+number)));}catch(Exception e){toast("No phone app is available.");}}));
        ImageView more=icon(R.drawable.ic_more,ink,"More options",null);more.setBackground(getDrawable(android.R.drawable.list_selector_background));more.setOnClickListener(this::chatMenu);bar.addView(more);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));connection();
        scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);items=vertical();items.setPadding(dp(12),dp(8),dp(12),dp(8));scroll.addView(items);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{if(y==0&&oy>0)loadOlderMessages();});
        replyView=text("",13,muted,false);replyView.setVisibility(View.GONE);replyView.setPadding(dp(16),dp(8),dp(16),dp(8));replyView.setOnClickListener(v->{replyId="";replyView.setVisibility(View.GONE);});root.addView(replyView);
        LinearLayout compose=row();compose.setPadding(dp(8),dp(4),dp(8),0);
        composer=new EditText(this);composer.setHint(PairedProtocol.rcs(c)?"RCS message":"Text message");composer.setTextColor(ink);composer.setHintTextColor(muted);composer.setTextSize(17);
        composer.setBackground(shape(surface,28));composer.setPadding(dp(22),dp(14),dp(22),dp(14));composer.setMaxLines(5);
        composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setText(drafts.getOrDefault(id,""));
        compose.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        ImageView sendButton=new ImageView(this);sendButton.setImageResource(R.drawable.ic_send);sendButton.setImageTintList(ColorStateList.valueOf(theme.onAccent(dark)));sendButton.setBackground(oval(theme.accent(dark)));
        sendButton.setPadding(dp(14),dp(14),dp(14),dp(14));sendButton.setContentDescription("Send");sendButton.setOnClickListener(v->send());
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(dp(52),dp(52));sl.setMarginStart(dp(8));compose.addView(sendButton,sl);root.addView(compose);
        getSystemService(NotificationManager.class).cancel("chat:"+id,1);renderMessages(true);if(PairedService.ready())loadMessages(false);markReadIfAllowed();
        if(c!=null)requestPhotos(java.util.Collections.singletonList(c));
    }
    private void chatMenu(View anchor){JSONObject c=store.chat(chatId);if(c==null)return;PopupMenu menu=new PopupMenu(this,anchor);Menu m=menu.getMenu();
        m.add(0,1,0,chatSettings.pinned(c)?"Unpin":"Pin to top");m.add(0,2,1,"Chat theme");
        m.add(0,3,2,"Read receipts for this chat").setCheckable(true).setChecked(chatSettings.receipts(chatId));
        m.add(0,4,3,"Refresh");m.add(0,5,4,"Load older messages");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 1->togglePin(c);case 2->chooseTheme(chatId);case 3->toggleReceipts(chatId);case 4->loadMessages(false);case 5->loadOlderMessages();default->{}}return true;});menu.show();}
    private void renderMessages(boolean bottom){if(items==null||!screen.equals("chat"))return;
        int y=scroll.getScrollY();View content=scroll.getChildAt(0);int before=content.getHeight();
        boolean atBottom=bottom||before-scroll.getHeight()-y<dp(80);items.removeAllViews();
        JSONObject c=store.chat(chatId);ChatTheme theme=chatSettings.theme(chatId);boolean group=c!=null&&PairedProtocol.other(c)==null;
        List<JSONObject> messages=new ArrayList<>();for(JSONObject m:store.messages(chatId,limit))if(!PairedProtocol.body(m).isEmpty())messages.add(m);
        if(messages.size()>=limit-5)items.addView(button("Show more saved messages",false,()->{limit+=300;renderMessages(false);}));
        if(messages.isEmpty()){TextView empty=text(PairedService.ready()?"No messages have synced for this conversation yet.":"Messages will appear once the connection is ready.",14,muted,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(48),dp(24),dp(24));items.addView(empty);}
        else if(store.page("messages:"+chatId,false).isEmpty()&&!store.page("messages:"+chatId,true).isEmpty()){TextView end=text(store.page("messages:"+chatId,true),12,muted,false);end.setGravity(Gravity.CENTER);end.setPadding(dp(16),dp(8),dp(16),dp(8));items.addView(end);}
        int n=messages.size();boolean[] out=new boolean[n];String[] who=new String[n];long[] at=new long[n];
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);out[i]=PairedProtocol.outgoing(m,c);who[i]=out[i]?"":m.optString("participantID");at[i]=PairedProtocol.time(m);}
        int lastOut=-1;for(int i=n-1;i>=0;i--)if(out[i]){lastOut=i;break;}
        TimeZone zone=TimeZone.getDefault();
        for(int i=0;i<n;i++){JSONObject m=messages.get(i);
            boolean sep=InboxOrder.separator(i==0?0:at[i-1],at[i],zone);
            if(sep){TextView s=text(separatorStamp(at[i]),13,muted,false);s.setGravity(Gravity.CENTER);s.setPadding(0,dp(i==0?8:20),0,dp(10));items.addView(s,new LinearLayout.LayoutParams(-1,-2));}
            boolean joinPrev=!sep&&i>0&&InboxOrder.grouped(out[i-1],who[i-1],at[i-1],out[i],who[i],at[i]);
            boolean joinNext=i+1<n&&!InboxOrder.separator(at[i],at[i+1],zone)&&InboxOrder.grouped(out[i],who[i],at[i],out[i+1],who[i+1],at[i+1]);
            if(!sep&&i>0)gap(items,joinPrev?3:12);
            if(group&&!out[i]&&!joinPrev){TextView sender=text(PairedProtocol.person(c,m.optString("participantID"),m.optJSONObject("senderParticipant")),12,muted,false);sender.setPadding(dp(14),0,0,dp(4));items.addView(sender);}
            items.addView(bubble(m,c,theme,out[i],joinPrev,joinNext));
            Map<String,List<String>> reactions=PairedProtocol.reactions(m,c);if(!reactions.isEmpty())items.addView(reactionRow(reactions,out[i]));
            if(i==lastOut||(out[i]&&PairedProtocol.failed(m)))items.addView(statusRow(m,theme));
        }
        scroll.post(()->{if(scroll==null)return;if(anchorOlder){anchorOlder=false;scroll.scrollTo(0,y+Math.max(0,scroll.getChildAt(0).getHeight()-before));}else if(atBottom)scroll.fullScroll(View.FOCUS_DOWN);else scroll.scrollTo(0,y);});
    }
    private View bubble(JSONObject m,JSONObject c,ChatTheme theme,boolean outgoing,boolean joinPrev,boolean joinNext){
        LinearLayout line=row();line.setGravity(outgoing?Gravity.END:Gravity.START);
        LinearLayout b=vertical();b.setPadding(dp(16),dp(11),dp(16),dp(11));
        float big=dp(22),small=dp(5);float top=joinPrev?small:big,bottomR=joinNext?small:big;
        GradientDrawable shape=new GradientDrawable();shape.setColor(outgoing?theme.bubble(dark):surface);
        shape.setCornerRadii(outgoing?new float[]{big,big,top,top,bottomR,bottomR,big,big}:new float[]{top,top,big,big,big,big,bottomR,bottomR});b.setBackground(shape);
        int textColor=outgoing?theme.onBubble(dark):ink;
        JSONObject reply=m.optJSONObject("replyMessage");String target=PairedProtocol.string(reply,"messageID");
        if(!target.isEmpty()){JSONObject original=store.message(target);LinearLayout quote=row();View barLine=new View(this);barLine.setBackgroundColor(textColor);barLine.setAlpha(0.5f);quote.addView(barLine,new LinearLayout.LayoutParams(dp(3),-1));
            TextView q=text(original==null?"Reply to an earlier message":PairedProtocol.body(original),14,textColor,false);q.setAlpha(0.75f);q.setMaxLines(3);q.setEllipsize(TextUtils.TruncateAt.END);q.setPadding(dp(8),0,0,0);quote.addView(q);
            b.addView(quote);gap(b,6);}
        TextView body=text(PairedProtocol.body(m),17,textColor,false);body.setLineSpacing(0,1.1f);body.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels*0.72f));b.addView(body);
        b.setOnLongClickListener(v->{messageActions(m);return true;});b.setOnClickListener(v->{});
        line.addView(b,new LinearLayout.LayoutParams(-2,-2));return line;
    }
    private View reactionRow(Map<String,List<String>> reactions,boolean outgoing){
        LinearLayout r=row();r.setGravity(outgoing?Gravity.END:Gravity.START);r.setPadding(dp(8),dp(2),dp(8),0);
        for(Map.Entry<String,List<String>> e:reactions.entrySet()){
            TextView chip=text(e.getKey()+(e.getValue().size()>1?" "+e.getValue().size():""),14,ink,false);chip.setPadding(dp(8),dp(3),dp(8),dp(3));
            GradientDrawable d=shape(surface,14);d.setStroke(dp(2),bg);chip.setBackground(d);
            chip.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(e.getKey()).setMessage(String.join("\n",e.getValue())).setPositiveButton("OK",null).show());
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMarginEnd(dp(4));r.addView(chip,lp);}
        return r;
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
            if(!e.isEmpty()){error(e);return;}drafts.remove(id);if(screen.equals("chat")&&chatId.equals(id)){composer.setText("");replyId="";replyView.setVisibility(View.GONE);}
            if(screen.equals("chat")&&chatId.equals(id))loadMessages(false);});}
    private void newMessage(){if(!PairedService.ready()){toast("Connect first, then start a chat.");return;}
        EditText number=input("+15551234567");number.setInputType(InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(this).setTitle("Start chat").setMessage("Enter the full phone number including + and country code.").setView(number)
            .setNegativeButton("Cancel",null).setPositiveButton("Open conversation",(d,w)->{
                String value=number.getText().toString().replaceAll("[\\s()\\-]","");
                PairedService.execute(c->c.createChat(value),(raw,e)->{if(!e.isEmpty()){error(e);return;}try{JSONObject chat=new JSONObject(raw);store.chat(chat);thread(chat.getString("conversationID"));}catch(Exception ex){error("The new conversation could not be opened.");}});
            }).show();}
    private void messageActions(JSONObject m){String[] options={"Reply","React","Remove my reaction","Copy text"};new AlertDialog.Builder(this).setItems(options,(d,which)->{
        if(which==0){replyId=m.optString("messageID");replyView.setText("Replying to: "+PairedProtocol.body(m)+"   ✕");replyView.setVisibility(View.VISIBLE);composer.requestFocus();return;}
        if(which==3){getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Message",PairedProtocol.body(m)));return;}
        new AlertDialog.Builder(this).setTitle(which==2?"Remove which reaction?":"React to message").setItems(REACTIONS,(dialog,index)->{
            JSONObject chat=store.chat(chatId);if(chat==null){error("Refresh this conversation first.");return;}
            PairedService.execute(c->{c.react(chat.toString(),m.optString("messageID"),REACTIONS[index],which==2);return "";},(v,e)->{if(!e.isEmpty())error(e);else loadMessages(false);});
        }).show();}).show();}
    private void settings(){remember();base("settings");header("Settings",this::inbox);connection();ScrollView sc=new ScrollView(this);LinearLayout content=vertical();sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        note(content,"Message Center "+BuildConfig.VERSION_NAME+" • experimental paired client\n\nThe connection runs on this phone and talks directly to Google. Google Messages must remain your default messaging app and have network/background access.");
        int phone=chatSettings.phoneReceipts();
        note(content,"Read receipts\n\nEach chat has its own read-receipt switch (long-press a chat, or ⋮ inside it). When it is on, opening the chat here marks it read on your phone and Google Messages sends a receipt. When it is off, the chat is not marked read, so it stays unread in Google Messages.\n\nGoogle Messages' own \"Send read receipts\" setting: "+(phone==1?"On":phone==0?"Off: no receipts are sent for any chat":"not reported yet")+".\n\nPins and chat themes are kept in this app. Google's protocol has no pin action, so pinning here does not change Google Messages.");
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
