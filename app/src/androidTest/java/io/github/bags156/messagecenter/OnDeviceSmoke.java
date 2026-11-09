package io.github.bags156.messagecenter;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;
import io.github.bags156.gm.bridge.Bridge;
import io.github.bags156.gm.bridge.Client;

/** Offline device checks. Never signs in, pairs, or sends a message. */
public final class OnDeviceSmoke extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    private void onMain(PairedActivity a,String method){runOnMainSync(()->{try{java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod(method);m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();}
    private android.widget.ScrollView chatScroll(PairedActivity a)throws Exception{java.lang.reflect.Field f=PairedActivity.class.getDeclaredField("scroll");f.setAccessible(true);return (android.widget.ScrollView)f.get(a);}
    private void textMessage(PairedStore store,String chat,int i)throws Exception{boolean mine=i%3==0;
        store.message(new JSONObject().put("messageID",chat+i).put("conversationID",chat).put("participantID",mine?"me":"p1").put("timestamp",1794000400000000L+i*60_000_000L)
            .put("messageStatus",new JSONObject().put("status",mine?"OUTGOING_DELIVERED":"INCOMING_COMPLETE"))
            .put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","Line "+i+" of a long conversation")))),0,true);}
    /** "What notifications show": a reminder is really posted in each mode and read back. Runs last (it grants notifications). */
    private void checkNotificationPrivacy()throws Exception{
        android.content.Context ctx=getTargetContext();
        getUiAutomation().grantRuntimePermission(ctx.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        android.app.NotificationManager nm=ctx.getSystemService(android.app.NotificationManager.class);
        java.lang.reflect.Method fire=Reminders.class.getDeclaredMethod("fire",android.content.Context.class,String.class);fire.setAccessible(true);
        for(String mode:new String[]{NotificationPrivacy.ALL,NotificationPrivacy.NAME,NotificationPrivacy.NONE}){NotificationPrivacy.set(ctx,mode);
            Reminders.add(ctx,"privacy-chat","privacy-msg","Daniel Example","Secret plans for Friday",System.currentTimeMillis()+3_600_000L);
            String rid=Reminders.forMessage(ctx,"privacy-msg").optString("id");fire.invoke(null,ctx,rid);
            // Android posts notifications in the background, so give it a moment to appear.
            android.app.Notification n=null;for(int wait=0;wait<30&&n==null;wait++){for(android.service.notification.StatusBarNotification sbn:nm.getActiveNotifications())if(("reminder:"+rid).equals(sbn.getTag()))n=sbn.getNotification();if(n==null)Thread.sleep(100);}
            check(n!=null,"Reminder notification wasn't posted ("+mode+")");
            String shown=n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)+" | "+n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)+" | "+n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
            check(shown.contains("Secret plans")==mode.equals(NotificationPrivacy.ALL),"Message text "+(mode.equals(NotificationPrivacy.ALL)?"missing":"shown")+" with '"+mode+"': "+shown);
            check(shown.contains("Daniel")!=mode.equals(NotificationPrivacy.NONE),"Name "+(mode.equals(NotificationPrivacy.NONE)?"shown":"missing")+" with '"+mode+"': "+shown);
            nm.cancel("reminder:"+rid,2);Thread.sleep(300);}
        NotificationPrivacy.set(ctx,NotificationPrivacy.ALL);}
    /**
     * Pinned chats open only from the round photo, with no highlight box; a tap on its corner
     * (outside the circle) does nothing. Not signed in here, so the inbox has no chat list: the
     * tile is built the same way and placed on the screen to be tapped.
     */
    private void checkPinnedTap(PairedActivity a)throws Exception{
        new ChatSettings(getTargetContext()).setPinned("smoke",true);onMain(a,"inbox");
        JSONObject smoke;try(PairedStore store=new PairedStore(getTargetContext())){smoke=store.chat("smoke");}check(smoke!=null,"Smoke chat missing");
        java.lang.reflect.Field rootField=PairedActivity.class.getDeclaredField("root");rootField.setAccessible(true);
        android.view.ViewGroup root=(android.view.ViewGroup)rootField.get(a);android.view.View[] cell=new android.view.View[1];
        runOnMainSync(()->{try{java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod("pinnedCell",JSONObject.class);m.setAccessible(true);
            cell[0]=(android.view.View)m.invoke(a,smoke);root.addView(cell[0],new android.widget.LinearLayout.LayoutParams(Math.round(130*a.getResources().getDisplayMetrics().density),-2));}catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleSync();
        java.util.ArrayList<android.view.View> faces=new java.util.ArrayList<>();java.util.ArrayList<String> seen=new java.util.ArrayList<>();
        runOnMainSync(()->collectDescriptions(cell[0],faces,seen));
        check(faces.size()==1,"Pinned photo not found ("+faces.size()+"; seen="+seen+")");android.view.View face=faces.get(0);
        check(cell[0].getBackground()==null,"Pinned chat still draws a highlight box");
        check(face.getWidth()>0,"Pinned photo wasn't laid out");
        java.lang.reflect.Field screen=PairedActivity.class.getDeclaredField("screen");screen.setAccessible(true);
        tap(face,2,2);check("inbox".equals(screen.get(a)),"A tap outside the round photo opened the chat");
        tap(face,face.getWidth()/2f,face.getHeight()/2f);check("chat".equals(screen.get(a)),"A tap on the photo didn't open the chat");
        new ChatSettings(getTargetContext()).setPinned("smoke",false);onMain(a,"inbox");}
    /** Mark up: draw a stroke through the middle of a plain photo, crop it, save, and check the saved copy. */
    private void checkMarkup(PairedActivity a)throws Exception{
        java.io.File photo=new java.io.File(getTargetContext().getCacheDir(),"markup-test-"+System.nanoTime()+".png");
        android.graphics.Bitmap gray=android.graphics.Bitmap.createBitmap(2000,1500,android.graphics.Bitmap.Config.ARGB_8888);gray.eraseColor(0xFF808080);
        try(java.io.FileOutputStream f=new java.io.FileOutputStream(photo)){gray.compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);}
        java.io.File[] saved=new java.io.File[1];
        runOnMainSync(()->MarkupEditor.open(a,photo,f->saved[0]=f));
        for(int i=0;i<50&&MarkupEditor.shown==null;i++)Thread.sleep(100);
        MarkupEditor editor=MarkupEditor.shown;check(editor!=null,"Markup editor didn't open");waitForIdleSync();
        java.lang.reflect.Field boardField=MarkupEditor.class.getDeclaredField("board");boardField.setAccessible(true);android.view.View board=(android.view.View)boardField.get(editor);
        check(board.getWidth()>0&&board.getHeight()>0,"Markup canvas wasn't laid out");
        // A short stroke through the middle of the photo (the canvas fits the photo in the middle).
        float cx=board.getWidth()/2f,cy=board.getHeight()/2f;
        runOnMainSync(()->{long t=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down=android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,cx-30,cy,0),move=android.view.MotionEvent.obtain(t,t+20,android.view.MotionEvent.ACTION_MOVE,cx,cy,0),
                move2=android.view.MotionEvent.obtain(t,t+40,android.view.MotionEvent.ACTION_MOVE,cx+30,cy,0),up=android.view.MotionEvent.obtain(t,t+60,android.view.MotionEvent.ACTION_UP,cx+30,cy,0);
            board.dispatchTouchEvent(down);board.dispatchTouchEvent(move);board.dispatchTouchEvent(move2);board.dispatchTouchEvent(up);down.recycle();move.recycle();move2.recycle();up.recycle();});
        java.lang.reflect.Field cropField=MarkupEditor.class.getDeclaredField("crop");cropField.setAccessible(true);
        runOnMainSync(()->{try{((android.graphics.RectF)cropField.get(editor)).set(500,375,1500,1125);
            java.lang.reflect.Method save=MarkupEditor.class.getDeclaredMethod("save");save.setAccessible(true);save.invoke(editor);}catch(Exception e){throw new RuntimeException(e);}});
        for(int i=0;i<50&&saved[0]==null;i++)Thread.sleep(100);waitForIdleSync();
        check(saved[0]!=null&&saved[0].isFile(),"Marked-up photo wasn't saved");
        android.graphics.Bitmap out=android.graphics.BitmapFactory.decodeFile(saved[0].getPath());
        check(out!=null&&out.getWidth()==1000&&out.getHeight()==750,"Crop wasn't applied: "+(out==null?"unreadable":out.getWidth()+"×"+out.getHeight()));
        int mid=out.getPixel(500,375);check(android.graphics.Color.red(mid)>170&&android.graphics.Color.green(mid)<110,"The drawing isn't in the saved photo (middle pixel "+Integer.toHexString(mid)+")");
        int corner=out.getPixel(5,5);check(Math.abs(android.graphics.Color.red(corner)-0x80)<12,"The rest of the photo changed (corner "+Integer.toHexString(corner)+")");
        check(MarkupEditor.shown==null,"Markup editor didn't close after saving");
        saved[0].delete();photo.delete();}
    /** Battery: how long a full redraw of a busy 300-message chat takes (median of five), for comparing versions. */
    private String measureRedraw()throws Exception{
        try(PairedStore store=new PairedStore(getTargetContext())){
            store.chat(new JSONObject("{conversationID:'perf',name:'Speed test',type:'RCS',lastMessageTimestamp:1794100000000000,participants:["
                +"{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'p1',number:'+15550102000'},fullName:'Daniel Example'}]}"));
            String[] kinds={"Sounds good, see you then","Call me at (479) 555-0134 when you land, the gate code is 4417","Meet at 123 Main St, Bentonville, AR 72712 on Friday at 6",
                "Here's the link https://example.com/menu and the other one www.example.org/hours","Your package 1Z999AA10123456784 is out for delivery, flight AA 1234 lands at 7:45 PM",
                "Haha yes!! 😂😂","Can you grab milk and eggs on the way home? Also bread if they have the good kind"};
            for(int i=0;i<300;i++){boolean mine=i%3==0;store.message(new JSONObject().put("messageID","perf"+i).put("conversationID","perf").put("participantID",mine?"me":"p1").put("timestamp",1794000000000000L+i*300_000_000L)
                .put("messageStatus",new JSONObject().put("status",mine?"OUTGOING_DELIVERED":"INCOMING_COMPLETE"))
                .put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content",kinds[i%kinds.length])))),0,true);}}
        PairedActivity a=(PairedActivity)startActivitySync(new Intent(getTargetContext(),BubbleActivity.class).setAction(Intent.ACTION_VIEW).putExtra("chat","perf").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        java.lang.reflect.Method render=PairedActivity.class.getDeclaredMethod("renderMessages",boolean.class);render.setAccessible(true);
        java.lang.reflect.Field limit=PairedActivity.class.getDeclaredField("limit");limit.setAccessible(true);
        // Same machine, same run: before (300 drawn, every text re-scanned) and now (120 drawn, scans reused), alternating.
        long[] before=new long[5],runs=new long[5];
        for(int r=0;r<5;r++){int at=r;
            runOnMainSync(()->{try{limit.setInt(a,300);TextScan.clear();before[at]=timedRedraw(a,render);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
            runOnMainSync(()->{try{limit.setInt(a,120);runs[at]=timedRedraw(a,render);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();}
        java.util.Arrays.sort(before);
        a.finish();waitForIdleSync();java.util.Arrays.sort(runs);
        try(PairedStore store=new PairedStore(getTargetContext())){store.deleteChat("perf");}
        return "redraw busy chat: before "+before[2]+" ms, now "+runs[2]+" ms (medians of 5, same run)";}
    private static long timedRedraw(PairedActivity a,java.lang.reflect.Method render)throws Exception{long t=System.nanoTime();render.invoke(a,false);
        android.view.View root=a.getWindow().getDecorView();root.measure(android.view.View.MeasureSpec.makeMeasureSpec(root.getWidth(),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(root.getHeight(),android.view.View.MeasureSpec.EXACTLY));
        root.layout(0,0,root.getWidth(),root.getHeight());return (System.nanoTime()-t)/1_000_000;}
    private static void collectDescriptions(android.view.View v,java.util.List<android.view.View> faces,java.util.List<String> seen){if(v==null)return;
        CharSequence d=v.getContentDescription();if(d!=null&&d.toString().contains("pinned")){seen.add(d.toString());if(d.toString().startsWith("Smoke group")&&d.toString().endsWith(", pinned"))faces.add(v);}
        if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collectDescriptions(g.getChildAt(i),faces,seen);}}
    private void tap(android.view.View v,float x,float y){runOnMainSync(()->{long t=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,x,y,0),up=android.view.MotionEvent.obtain(t,t+50,android.view.MotionEvent.ACTION_UP,x,y,0);
        v.dispatchTouchEvent(down);v.dispatchTouchEvent(up);down.recycle();up.recycle();});waitForIdleSync();}
    /** Scrolled up reading: a refresh from Google, new messages, redraws and a trip to Details must leave you where you are. */
    private void checkChatKeepsItsPlace()throws Exception{
        try(PairedStore store=new PairedStore(getTargetContext())){
            store.chat(new JSONObject("{conversationID:'scrolly',name:'Scroll test',type:'RCS',lastMessageTimestamp:1794005200000000,participants:["
                +"{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'p1',number:'+15550102000'},fullName:'Daniel Example'}]}"));
            for(int i=0;i<80;i++)textMessage(store,"scrolly",i);
            // Battery: Google repeats unchanged chats; those aren't saved (or redrawn) again, a real change is.
            String same="{conversationID:'scrolly',name:'Scroll test',type:'RCS',lastMessageTimestamp:1794005200000000,participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'p1',number:'+15550102000'},fullName:'Daniel Example'}]}";
            check(!store.chat(new JSONObject(same)),"An unchanged chat was saved again");
            check(store.chat(new JSONObject(same.replace("Scroll test","Scroll test!"))),"A changed chat wasn't saved");store.chat(new JSONObject(same));
            check(PairedService.backgroundSummary().startsWith("Background: "),"Background summary missing");
            // Catching up after a reconnect compares Google's chats with the newest message saved here.
            check(store.newestTime("scrolly")==(1794000400000000L+79*60_000_000L)/1000,"Newest saved message time is wrong: "+store.newestTime("scrolly"));
            check(store.newestTime("no-such-chat")==0,"A chat with nothing saved should have no newest time");}
        PairedActivity reader=(PairedActivity)startActivitySync(new Intent(getTargetContext(),BubbleActivity.class).setAction(Intent.ACTION_VIEW).putExtra("chat","scrolly").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();int slack=Math.round(3*reader.getResources().getDisplayMetrics().density);
        android.widget.ScrollView sv=chatScroll(reader);int[] y=new int[2];
        runOnMainSync(()->{sv.scrollTo(0,sv.getChildAt(0).getHeight()/2);});waitForIdleSync();
        runOnMainSync(()->{y[0]=sv.getScrollY();y[1]=sv.getChildAt(0).getHeight()-sv.getHeight()-sv.getScrollY();});
        check(y[0]>0&&y[1]>slack*40,"Test chat isn't long enough to scroll ("+y[0]+", "+y[1]+")");int reading=y[0];
        runOnMainSync(()->{try{java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod("messagesLoaded",boolean.class);m.setAccessible(true);m.invoke(reader,false);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        runOnMainSync(()->y[0]=sv.getScrollY());check(Math.abs(y[0]-reading)<=slack,"A refresh from Google moved the chat: "+reading+" → "+y[0]);
        boolean[] sliding=new boolean[1];String[] motionState={""};
        // The message is saved and drawn in one step on the main thread, so nothing else redraws in between.
        runOnMainSync(()->{try{try(PairedStore store=new PairedStore(getTargetContext())){textMessage(store,"scrolly",80);}
            java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod("renderMessages",boolean.class);m.setAccessible(true);m.invoke(reader,false);
            // Checked right after the first redraw: a slow emulator can take longer than the slide itself to redraw again.
            java.lang.reflect.Field running=PairedActivity.class.getDeclaredField("motionRunning");running.setAccessible(true);sliding[0]=((java.util.Map<?,?>)running.get(reader)).containsKey("m:scrolly80");
            m.invoke(reader,false);
            String[] names={"motionReady","motionChat","motionSeen","anchorOlder","motionRunning"};StringBuilder st=new StringBuilder();
            for(String f:names){java.lang.reflect.Field fl=PairedActivity.class.getDeclaredField(f);fl.setAccessible(true);Object v=fl.get(reader);st.append(f).append('=').append(v instanceof java.util.Collection?"size "+((java.util.Collection<?>)v).size():v instanceof java.util.Map?"keys "+((java.util.Map<?,?>)v).keySet():String.valueOf(v)).append("; ");}
            motionState[0]=st.toString();}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        // A message arriving in an open chat slides in when Android's animations are on (and only then).
        check(sliding[0]==android.animation.ValueAnimator.areAnimatorsEnabled(),"New message motion "+(sliding[0]?"ran with animations off":"didn't start")+" ("+motionState[0]+")");
        runOnMainSync(()->y[0]=sv.getScrollY());check(Math.abs(y[0]-reading)<=slack,"A new message (two quick redraws) moved the chat: "+reading+" → "+y[0]);
        onMain(reader,"details");runOnMainSync(reader::onBackPressed);waitForIdleSync();
        android.widget.ScrollView back=chatScroll(reader);check(back!=null&&back!=sv,"Back from Details didn't reopen the chat");
        runOnMainSync(()->y[0]=back.getScrollY());check(Math.abs(y[0]-reading)<=slack,"Coming back from Details moved the chat: "+reading+" → "+y[0]);
        // Something above the screen grows after it was drawn (a photo or link preview finishing loading): what you're reading stays still.
        java.lang.reflect.Field viewsField=PairedActivity.class.getDeclaredField("messageViews");viewsField.setAccessible(true);
        java.util.Map<?,?> views=(java.util.Map<?,?>)viewsField.get(reader);Object[] spot=new Object[2];String[] grown={""};
        runOnMainSync(()->{try{java.lang.reflect.Method spotAt=PairedActivity.class.getDeclaredMethod("spotAt",int.class);spotAt.setAccessible(true);Object sp=spotAt.invoke(reader,back.getScrollY());
            java.lang.reflect.Field idF=sp.getClass().getDeclaredField("id"),offF=sp.getClass().getDeclaredField("offset");idF.setAccessible(true);offF.setAccessible(true);spot[0]=idF.get(sp);spot[1]=offF.get(sp);
            for(java.util.Map.Entry<?,?> e:views.entrySet()){android.view.View v=(android.view.View)e.getValue();if(v.getParent()!=null&&v.getBottom()<back.getScrollY()-50){grown[0]=String.valueOf(e.getKey());
                v.setPadding(v.getPaddingLeft(),v.getPaddingTop()+400,v.getPaddingRight(),v.getPaddingBottom());break;}}}catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleSync();runOnMainSync(()->{});waitForIdleSync();
        check(!grown[0].isEmpty(),"No message above the screen to grow");
        runOnMainSync(()->{android.view.View v=(android.view.View)views.get(spot[0]);y[0]=v==null?-99999:v.getTop()-back.getScrollY();});
        check(Math.abs(y[0]-(Integer)spot[1])<=slack,"A message above the screen growing moved what you're reading: "+spot[1]+" → "+y[0]+" px from the top");
        // At the newest message, new arrivals still keep you at the bottom.
        runOnMainSync(()->back.scrollTo(0,back.getChildAt(0).getHeight()));waitForIdleSync();
        try(PairedStore store=new PairedStore(getTargetContext())){textMessage(store,"scrolly",81);}
        runOnMainSync(()->{try{java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod("messagesLoaded",boolean.class);m.setAccessible(true);m.invoke(reader,false);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        runOnMainSync(()->y[1]=back.getChildAt(0).getHeight()-back.getHeight()-back.getScrollY());check(y[1]<=slack,"A new message at the bottom didn't keep the chat at the newest ("+y[1]+" px short)");
        // An iPhone message sent with Confetti, arriving while the chat is open: the note is hidden and the confetti plays.
        try(PairedStore store=new PairedStore(getTargetContext())){store.message(new JSONObject().put("messageID","scrolly-fx").put("conversationID","scrolly").put("participantID","p1").put("timestamp",1794000400000000L+90*60_000_000L)
            .put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","We got the house!! (sent with Confetti)")))),0,true);}
        boolean[] played=new boolean[1];
        // No waitForIdleSync here: while confetti is falling the screen redraws every frame, so a slow emulator
        // isn't idle again until it's over. The effect starts in the step right after the redraw.
        runOnMainSync(()->{try{java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod("renderMessages",boolean.class);m.setAccessible(true);m.invoke(reader,false);}catch(Exception e){throw new RuntimeException(e);}});
        runOnMainSync(()->{try{java.lang.reflect.Field f=PairedActivity.class.getDeclaredField("playing");f.setAccessible(true);Object v=f.get(reader);played[0]=v!=null&&((android.view.View)v).isAttachedToWindow();}catch(Exception e){throw new RuntimeException(e);}});
        check(played[0]==android.animation.ValueAnimator.areAnimatorsEnabled(),"Confetti "+(played[0]?"played with animations off":"didn't play")+" for a message an iPhone sent with it");
        java.util.ArrayList<android.view.View> fx=new java.util.ArrayList<>();
        reader.getWindow().getDecorView().findViewsWithText(fx,"(sent with",android.view.View.FIND_VIEWS_WITH_TEXT);check(fx.isEmpty(),"The iPhone's \"(sent with Confetti)\" note is still shown");
        reader.getWindow().getDecorView().findViewsWithText(fx,"We got the house!!",android.view.View.FIND_VIEWS_WITH_TEXT);check(!fx.isEmpty(),"The message sent with Confetti is missing");fx.clear();
        reader.getWindow().getDecorView().findViewsWithText(fx,"Sent with Confetti. Tap to replay it.",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(fx.size()==1,"No \"Sent with Confetti\" line to replay it ("+fx.size()+")");
        reader.finish();waitForIdleSync();}
    /** New conversation: finds people in the phone's contacts and in your chats, not just Google's partial list. */
    private void checkNewConversationSearch(PairedActivity a)throws Exception{
        try(PairedStore store=new PairedStore(getTargetContext())){store.chat(new JSONObject("{conversationID:'riley',name:'Riley Chatfield',type:'RCS',lastMessageTimestamp:1794000000000000,participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'r1',number:'+15550104444'},fullName:'Riley Chatfield'}]}"));}
        java.util.List<PhoneContacts.Entry> phone=new java.util.ArrayList<>();
        phone.add(new PhoneContacts.Entry("Hannah Kay","+14795550123","(479) 555-0123","Mobile"));
        for(int i=0;i<500;i++)phone.add(new PhoneContacts.Entry("Person "+i,"+1479556"+String.format(java.util.Locale.ROOT,"%04d",i),"479-556-"+i,"Mobile"));
        phone.add(new PhoneContacts.Entry("Zoë Último","+14795550999","(479) 555-0999","Work"));
        java.lang.reflect.Field phoneF=PairedActivity.class.getDeclaredField("phoneContacts"),googleF=PairedActivity.class.getDeclaredField("contacts"),queryF=PairedActivity.class.getDeclaredField("contactQuery");
        phoneF.setAccessible(true);googleF.setAccessible(true);queryF.setAccessible(true);
        java.lang.reflect.Method render=PairedActivity.class.getDeclaredMethod("renderContacts");render.setAccessible(true);
        // Not signed in here, so the screen is opened directly with stand-in contacts (Google's list empty, as if it hadn't come).
        runOnMainSync(()->{try{phoneF.set(a,phone);googleF.set(a,new JSONObject("{top:[],all:[]}"));queryF.set(a,"");}catch(Exception e){throw new RuntimeException(e);}});
        onMain(a,"newChat");
        java.util.function.BiFunction<String,String,Boolean> shows=(typed,text)->{boolean[] hit=new boolean[1];
            runOnMainSync(()->{try{queryF.set(a,typed);render.invoke(a);java.util.ArrayList<android.view.View> f=new java.util.ArrayList<>();a.getWindow().getDecorView().findViewsWithText(f,text,android.view.View.FIND_VIEWS_WITH_TEXT);hit[0]=!f.isEmpty();}catch(Exception e){throw new RuntimeException(e);}});return hit[0];};
        check(shows.apply("kay","Hannah Kay"),"New conversation doesn't find a phone contact by last name");
        check(shows.apply("kay","Mobile · (479) 555-0123"),"A phone contact's number type and number aren't shown");
        check(shows.apply("zoe","Zoë Último"),"Search should ignore accents (\"zoe\" → Zoë)");
        check(shows.apply("riley","Riley Chatfield"),"Someone from a chat (not a contact) can't be found");
        check(shows.apply("Person 499","Person 499"),"The 500th contact can't be found (only part of the list is searched)");
        check(shows.apply("xqzv","No one called"),"No message when nobody matches");
        boolean asks=shows.apply("","Search all your contacts");check(asks==!PhoneContacts.allowed(a),"The \"Search all your contacts\" row "+(asks?"shows with":"is missing without")+" the contacts permission");
        runOnMainSync(()->{try{phoneF.set(a,null);googleF.set(a,null);queryF.set(a,"");}catch(Exception e){throw new RuntimeException(e);}});
        try(PairedStore store=new PairedStore(getTargetContext())){store.deleteChat("riley");}
        onMain(a,"inbox");}
    /** The inbox: a chat further down getting a message moves to the top without sliding the rows you're looking at. */
    private void checkInboxKeepsItsPlace(PairedActivity a)throws Exception{
        String people="participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'p1',number:'+15550102000'},fullName:'Daniel Example'}]";
        long newest=1794200000000000L;
        try(PairedStore store=new PairedStore(getTargetContext())){for(int i=0;i<30;i++)store.chat(new JSONObject("{conversationID:'row"+i+"',name:'Row "+i+"',type:'RCS',lastMessageTimestamp:"+(newest-i*60_000_000L)+","+people+"}"));}
        onMain(a,"inbox");
        // Not signed in here, so the inbox stops at "Connect Google Messages"; the list is drawn into a box of its own.
        java.lang.reflect.Field rootF=PairedActivity.class.getDeclaredField("root"),scrollF=PairedActivity.class.getDeclaredField("scroll"),itemsF=PairedActivity.class.getDeclaredField("items");
        rootF.setAccessible(true);scrollF.setAccessible(true);itemsF.setAccessible(true);
        java.lang.reflect.Method render=PairedActivity.class.getDeclaredMethod("renderInbox");render.setAccessible(true);
        android.widget.ScrollView[] box=new android.widget.ScrollView[1];android.widget.LinearLayout[] list=new android.widget.LinearLayout[1];
        runOnMainSync(()->{try{box[0]=new android.widget.ScrollView(a);list[0]=new android.widget.LinearLayout(a);list[0].setOrientation(android.widget.LinearLayout.VERTICAL);box[0].addView(list[0]);
            ((android.view.ViewGroup)rootF.get(a)).addView(box[0],new android.widget.LinearLayout.LayoutParams(-1,Math.round(400*a.getResources().getDisplayMetrics().density)));
            scrollF.set(a,box[0]);itemsF.set(a,list[0]);render.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        int slack=Math.round(3*a.getResources().getDisplayMetrics().density);
        int rowTag=a.getResources().getIdentifier("chat_row","id",a.getPackageName());check(rowTag!=0,"Inbox row tag id missing");
        java.util.function.Function<String,android.view.View> row=id->{for(int i=0;i<list[0].getChildCount();i++){android.view.View v=list[0].getChildAt(i);if(id.equals(v.getTag(rowTag)))return v;}return null;};
        android.view.View[] at=new android.view.View[1];int[] y=new int[2];
        runOnMainSync(()->{at[0]=row.apply("row8");if(at[0]!=null){box[0].scrollTo(0,at[0].getTop()+10);}});waitForIdleSync();
        check(at[0]!=null,"Inbox rows weren't drawn ("+list[0].getChildCount()+" rows)");
        runOnMainSync(()->{android.view.View v=row.apply("row8");y[0]=v.getTop()-box[0].getScrollY();});
        // row25 (below the screen) gets a message and moves to the top.
        try(PairedStore store=new PairedStore(getTargetContext())){store.chat(new JSONObject("{conversationID:'row25',name:'Row 25',type:'RCS',lastMessageTimestamp:"+(newest+60_000_000L)+","+people+"}"));}
        runOnMainSync(()->{try{render.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        runOnMainSync(()->{android.view.View v=row.apply("row8");y[1]=v==null?-99999:v.getTop()-box[0].getScrollY();});
        check(Math.abs(y[1]-y[0])<=slack,"A chat moving to the top slid the inbox rows you were looking at: "+y[0]+" → "+y[1]);
        // The row you're looking at itself gets a message: it moves up, and the rows around it stay put.
        runOnMainSync(()->{android.view.View v=row.apply("row9");y[0]=v.getTop()-box[0].getScrollY();});
        try(PairedStore store=new PairedStore(getTargetContext())){store.chat(new JSONObject("{conversationID:'row8',name:'Row 8',type:'RCS',lastMessageTimestamp:"+(newest+120_000_000L)+","+people+"}"));}
        runOnMainSync(()->{try{render.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        runOnMainSync(()->{android.view.View v=row.apply("row9");y[1]=v==null?-99999:v.getTop()-box[0].getScrollY();});
        check(Math.abs(y[1]-y[0])<=slack,"The chat you were looking at moving up took the inbox with it: row below went "+y[0]+" → "+y[1]);
        // At the top of the list, a chat that moves up comes into view.
        runOnMainSync(()->box[0].scrollTo(0,0));waitForIdleSync();
        try(PairedStore store=new PairedStore(getTargetContext())){store.chat(new JSONObject("{conversationID:'row20',name:'Row 20',type:'RCS',lastMessageTimestamp:"+(newest+180_000_000L)+","+people+"}"));}
        runOnMainSync(()->{try{render.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        runOnMainSync(()->{y[0]=box[0].getScrollY();android.view.View v=row.apply("row20");y[1]=v==null?-1:v.getTop();});
        check(y[0]==0&&y[1]>=0&&y[1]<Math.round(80*a.getResources().getDisplayMetrics().density),"At the top, a chat that moved up isn't in view (scroll "+y[0]+", row at "+y[1]+")");
        try(PairedStore store=new PairedStore(getTargetContext())){for(int i=0;i<30;i++)store.deleteChat("row"+i);}
        onMain(a,"inbox");}
    @Override public void onStart(){Bundle result=new Bundle();try {
        // Real JNI load on Android, not a mocked Java bridge.
        Client client=Bridge.newClient("",(kind,payload)->{});
        check(!client.exportSession().isEmpty(),"JNI session serialization failed");client.close();
        SessionVault vault=new SessionVault(getTargetContext());
        String fixture="synthetic session for offline device check only";vault.save(fixture);
        check(fixture.equals(vault.load()),"Keystore round trip failed");vault.clear();
        try(PairedStore store=new PairedStore(getTargetContext())) {
            store.clear();JSONObject message=new JSONObject().put("messageID","m1").put("conversationID","c1").put("timestamp","1794000000000000");
            long before=store.snapshot();store.message(message.put("subject","live reaction update"),0,true);
            store.message(new JSONObject(message.toString()).put("subject","stale history response"),before,false);
            check(store.message("m1").optString("subject").equals("live reaction update"),"History overwrote a live update");
            check(store.message(message,0,true),"Message ID did not deduplicate");
            JSONObject original=new JSONObject("{messageID:'origin',conversationID:'c1',timestamp:'1794000000000000',type:4,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Synthetic original'}}],anotherMessageID:{messageID:'external-original-id'}}");
            JSONObject reply=new JSONObject("{messageID:'reply',conversationID:'c1',timestamp:'1794000001000000',type:4,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Synthetic answer'}}],replyMessage:{messageID:'external-original-id'}}");
            store.message(original,0,true);store.message(reply,0,true);
            check("origin".equals(store.replyTarget(reply)),"Provider reply alias did not resolve to original");
            JSONObject byReference=store.messageByReference("c1","external-original-id");
            check(byReference!=null&&"origin".equals(byReference.optString("messageID")),"Reply alias cannot navigate after paging");
            store.message(new JSONObject("{messageID:'reply',conversationID:'c1',messageStatus:{status:'INCOMING_DISPLAYED'}}"),0,true);
            check("origin".equals(store.replyTarget(store.message("reply")))&&PairedProtocol.body(store.message("reply")).equals("Synthetic answer"),"Read update erased reply metadata or content");
            JSONObject fallback=new JSONObject("{messageID:'fallback',conversationID:'c1',timestamp:'1794000002000000',type:4,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Replied to a message: Another synthetic answer'}}]}");
            store.message(fallback,0,true);check(store.replyTarget(fallback).isEmpty(),"Reply without an ID was guessed");
            store.linkReply(fallback,"origin");check("origin".equals(store.replyTarget(fallback)),"Explicit original selection was not retained");
            ReadReceiptQueue receipts=new ReadReceiptQueue(getTargetContext());receipts.clear();ChatSettings settings=new ChatSettings(getTargetContext());settings.setReceipts("c1",true);
            // Read requests are only queued for chats the app knows, so the test chat must exist.
            store.chat(new JSONObject("{conversationID:'c1',type:2,participants:[{ID:{participantID:'p9'},isMe:true},{ID:{participantID:'p1'},fullName:'Sam Example'}]}"));
            PairedService.markReadFromNotification(getTargetContext(),"c1",()->{});
            JSONObject queued=new ReadReceiptQueue(getTargetContext()).get("c1");
            check(queued!=null&&"fallback".equals(queued.optString("messageID")),"Offline notification read was lost instead of queued");
            settings.setReceipts("c1",false);receipts.clear();store.clear();
        }
        Activity activity=startActivitySync(new Intent(getTargetContext(),PairedActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();check(activity!=null,"Activity did not launch");
        // Render a synthetic chat in the bubble window: replies, several people's reactions, iPhone fallback text.
        try(PairedStore store=new PairedStore(getTargetContext())) {
            store.chat(new JSONObject("{conversationID:'smoke',name:'Smoke group',isGroupChat:true,type:'RCS',lastMessageTimestamp:1794000300000000,participants:["
                +"{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'p1',number:'+15550102000'},fullName:'Daniel Example'},"
                +"{ID:{participantID:'p2',number:'+15550103000'},fullName:'Sam Example'}]}"));
            store.message(new JSONObject("{messageID:'a',conversationID:'smoke',participantID:'p1',timestamp:1794000000000000,messageStatus:{status:'INCOMING_COMPLETE'},"
                +"messageInfo:[{messageContent:{content:'Original'}}],reactions:[{data:{unicode:'❤️'},participantIDs:['p2','me']},{data:{type:'LIKE'},participantIDs:['p1']}]}"),0,true);
            store.message(new JSONObject("{messageID:'b',conversationID:'smoke',participantID:'me',timestamp:1794000100000000,messageStatus:{status:'OUTGOING_DELIVERED'},"
                +"messageInfo:[{messageContent:{content:'Reply'}}],replyMessage:{messageID:'a'}}"),0,true);
            store.message(new JSONObject("{messageID:'c',conversationID:'smoke',participantID:'p2',timestamp:1794000200000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Loved an image'}}]}"),0,true);
            store.message(new JSONObject("{messageID:'e',conversationID:'smoke',participantID:'p2',timestamp:1794000250000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:["
                +"{mediaContent:{mediaID:'img',mimeType:'image/jpeg',mediaName:'IMG_1.jpg',size:120000,dimensions:{width:1200,height:1600},decryptionKey:'AAECAwQ='}},"
                +"{mediaContent:{mediaID:'vid',mimeType:'video/mp4',size:9000000,thumbnailMediaID:'vthumb',thumbnailDecryptionKey:'AAECAwQ='}},"
                +"{mediaContent:{mediaID:'doc',mimeType:'application/pdf',mediaName:'Lease.pdf',size:52000}},{messageContent:{content:'Caption'}}]}"),0,true);
            // An iPhone "Laughed at an image" right after a photo: shown on the photo, not as a line.
            store.message(new JSONObject("{messageID:'tb1',conversationID:'smoke',participantID:'p1',timestamp:1794000251000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Laughed at an image'}}]}"),0,true);
            store.message(new JSONObject("{messageID:'ink',conversationID:'smoke',participantID:'p1',timestamp:1794000260000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'The surprise is Saturday (sent with Invisible Ink)'}}]}"),0,true);
            store.message(new JSONObject("{messageID:'f',conversationID:'smoke',participantID:'me',timestamp:1794000280000000,messageStatus:{status:'OUTGOING_FAILED_GENERIC'},messageInfo:[{messageContent:{content:'See https://example.com/page'}}]}"),0,true);
            new Outbox(getTargetContext()).add("smoke","Queued while offline","");
            store.message(new JSONObject("{messageID:'v1',conversationID:'smoke',participantID:'p1',timestamp:1794000285000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{mediaContent:{mediaID:'aud1',mimeType:'audio/mp4',mediaName:'Voice message.m4a',size:42000}}]}"),0,true);
            store.message(new JSONObject("{messageID:'v2',conversationID:'smoke',participantID:'me',timestamp:1794000286000000,messageStatus:{status:'OUTGOING_DELIVERED'},messageInfo:[{mediaContent:{mediaID:'aud2',mimeType:'audio/mp4',mediaName:'Voice message.m4a',size:30000}}]}"),0,true);
            getTargetContext().getSharedPreferences("transcripts",0).edit().putString("aud1","Running ten minutes late").commit();
            JSONObject otp=new JSONObject("{messageID:'otp',conversationID:'smoke',participantID:'p1',timestamp:1794000290000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Your verification code is 482913'}}]}");
            store.message(otp,0,true);new Starred(getTargetContext()).toggle(otp);
            store.message(new JSONObject("{messageID:'emj',conversationID:'smoke',participantID:'p2',timestamp:1794000295000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'🎉'}}]}"),0,true);
            Scheduled.add(getTargetContext(),"smoke","Happy birthday!","",System.currentTimeMillis()+3_600_000L);
            store.message(new JSONObject("{messageID:'trk',conversationID:'smoke',participantID:'p1',timestamp:1794000295600000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'UPS 1Z999AA10123456784 arrives Friday at 6pm'}}]}"),0,true);
            store.message(new JSONObject("{messageID:'addr',conversationID:'smoke',participantID:'p1',timestamp:1794000295500000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Meet at 805 SE J St, Bentonville, AR 72712 tonight'}}]}"),0,true);
            new ChatSettings(getTargetContext()).setBackground("smoke","stock:dusk");
            store.message(new JSONObject("{messageID:'loc',conversationID:'smoke',participantID:'p1',timestamp:1794000296000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{actionMessageID:'x1',locationContent:{messageContent:{content:'Main Street Park https://maps.google.com/?q=36.37,-94.21'}}}]}"),0,true);
            store.message(new JSONObject("{messageID:'ev',conversationID:'smoke',participantID:'p2',timestamp:1794000297000000,messageStatus:{status:'TOMBSTONE_PARTICIPANT_LEFT'}}"),0,true);
            // A photo that came with only Google's tiny in-message placeholder: shown as a preview, full photo asked for.
            android.graphics.Bitmap stamp=android.graphics.Bitmap.createBitmap(8,6,android.graphics.Bitmap.Config.ARGB_8888);stamp.eraseColor(0xff888888);
            java.io.ByteArrayOutputStream jpg=new java.io.ByteArrayOutputStream();stamp.compress(android.graphics.Bitmap.CompressFormat.JPEG,50,jpg);
            String inline=android.util.Base64.encodeToString(jpg.toByteArray(),android.util.Base64.NO_WRAP);
            store.message(new JSONObject("{messageID:'inl',conversationID:'smoke',participantID:'p1',timestamp:1794000297500000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{actionMessageID:'y1',mediaContent:{mimeType:'image/jpeg',mediaName:'IMG_3.jpg',size:900000,mediaData:'"+inline+"'}}]}"),0,true);
            check(!MediaCache.hasFull(PairedProtocol.media(store.message("inl")).get(0))&&MediaCache.previewOnly(PairedProtocol.media(store.message("inl")).get(0)),"In-message placeholder was treated as the photo");
            store.message(new JSONObject("{messageID:'nod',conversationID:'smoke',participantID:'p1',timestamp:1794000298000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{actionMessageID:'z1',mediaContent:{mimeType:'image/jpeg',mediaName:'IMG_2.jpg',size:1000}}]}"),0,true);
            // A photo the phone was asked for three times and never sent: labeled as not on the phone.
            store.message(new JSONObject("{messageID:'gone',conversationID:'smoke',participantID:'p1',timestamp:1794000298500000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{actionMessageID:'z9',mediaContent:{mimeType:'image/jpeg',mediaName:'IMG_9.jpg',size:1000}}]}"),0,true);
            getTargetContext().getSharedPreferences("phone_media",0).edit().putInt("gone:z9",PairedService.MISSING_AFTER).commit();
            // Read receipts on for this chat, with the receipt for its newest incoming message already sent.
            new ChatSettings(getTargetContext()).setReceipts("smoke",true);ReadReceiptQueue receipts=new ReadReceiptQueue(getTargetContext());
            JSONObject newest=store.latestIncoming("smoke");check(newest!=null,"No incoming message to mark read");receipts.viewed(store.chat("smoke"),newest,true);
            receipts.accepted("smoke",newest.optString("messageID"),receipts.get("smoke").optLong("revision"),System.currentTimeMillis());
            check(!store.searchAll("verification",10).isEmpty(),"Search across chats did not find a message");
            Nudges.reschedule(getTargetContext()); // unread reminders: must run against real data without crashing
            // Photo files: real photos decode and are named; a downloaded error page is recognized as not a photo.
            java.io.File okPhoto=new java.io.File(getTargetContext().getCacheDir(),"smoke-photo.jpg"),badPhoto=new java.io.File(getTargetContext().getCacheDir(),"smoke-bad.jpg");
            java.nio.file.Files.write(okPhoto.toPath(),jpg.toByteArray());java.nio.file.Files.write(badPhoto.toPath(),"<html>error</html>".getBytes());
            check(MediaCache.decode(okPhoto,2560,false)!=null&&MediaCache.describe(okPhoto).contains("JPEG"),"A real JPEG did not decode or was misnamed: "+MediaCache.describe(okPhoto));
            check(MediaCache.decode(badPhoto,2560,false)==null&&MediaCache.describe(badPhoto).contains("web page"),"An error page was not recognized: "+MediaCache.describe(badPhoto));
            okPhoto.delete();badPhoto.delete();
            // The in-app video player is registered and formats times; reminders can describe what's next.
            check(getTargetContext().getPackageManager().resolveActivity(VideoPlayerActivity.open(getTargetContext(),new java.io.File("/x.mp4"),"video/mp4","x.mp4"),0)!=null,"Video player not registered");
            check(VideoPlayerActivity.clock(83_000).equals("1:23")&&VideoPlayerActivity.clock(3_723_000).equals("1:02:03"),"Video clock wrong");
            // The Busy tile is offered to Quick Settings, and turning busy mode on and off from code is safe.
            check(!getTargetContext().getPackageManager().queryIntentServices(new Intent(android.service.quicksettings.TileService.ACTION_QS_TILE).setPackage(getTargetContext().getPackageName()),0).isEmpty(),"Busy tile isn't registered");
            PairedService.setAutoReply(getTargetContext(),Long.MAX_VALUE);check(PairedService.autoReplyUntil(getTargetContext())==Long.MAX_VALUE,"Busy mode didn't turn on");
            PairedService.setAutoReply(getTargetContext(),0);
            // Black theme: true black background, soft (not pure) white text, bubbles that still stand out.
            int[] black=PairedActivity.palette(true,true);check(black[0]==0xff000000&&black[3]!=0xffffffff&&black[2]!=black[0],"Black theme colors are off");
            // Dictation: words land with spacing and a capital at the start.
            check(ChatText.dictation("","on my way").equals("On my way")&&ChatText.dictation("Hi","there").equals(" there"),"Dictation spacing is wrong");
            Nudges.summary(getTargetContext());
            // GIFs play (animated drawable); problem reports bundle into a zip.
            java.io.File gif=new java.io.File(getTargetContext().getCacheDir(),"spin.gif");try(java.io.InputStream in=getContext().getAssets().open("spin.gif")){java.nio.file.Files.copy(in,gif.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            check(MediaCache.animated(gif,120,120) instanceof android.graphics.drawable.AnimatedImageDrawable,"GIF not animated");
            Diagnostics.init(getTargetContext());Diagnostics.note("smoke check");java.io.File report=Diagnostics.buildReport(getTargetContext(),"smoke","details",false);
            try(java.util.zip.ZipFile z=new java.util.zip.ZipFile(report)){check(z.getEntry("about.txt")!=null&&z.getEntry("journal.txt")!=null&&z.getEntry("log.txt")!=null,"Problem report incomplete");}report.delete();
            // Drafts are shared through disk, so the bubble and the full app see the same unsent text.
            new Drafts(getTargetContext()).put("smoke-draft","half a thought");check(new Drafts(getTargetContext()).getOrDefault("smoke-draft","").equals("half a thought"),"Draft not shared");
            new Drafts(getTargetContext()).put("smoke-draft","");check(new Drafts(getTargetContext()).getOrDefault("smoke-draft","none").equals("none"),"Empty draft not cleared");
            // iPhone pictures Android can't read on its own: a Genmoji (several sizes, see-through layer), tiles, crop + rotation.
            HevcSandbox.init(getTargetContext());
            java.io.File heifDir=new java.io.File(getTargetContext().getCacheDir(),"heif-smoke");heifDir.mkdirs();
            for(String n:new String[]{"genmoji","grid","turned","mono-alpha","genmoji444"})try(java.io.InputStream in=getContext().getAssets().open("heif/"+n+".heic")){java.nio.file.Files.copy(in,new java.io.File(heifDir,n+".heic").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            android.graphics.Bitmap gm=HeifDecoder.decode(new java.io.File(heifDir,"genmoji.heic"),2560);
            check(gm!=null&&gm.getWidth()==320&&gm.getHeight()==320,"Genmoji: largest size not decoded: "+(gm==null?HeifDecoder.lastProblem:gm.getWidth()+"x"+gm.getHeight()));
            int face=gm.getPixel(160,180);
            check((gm.getPixel(5,5)>>>24)<16,"Genmoji: corner not see-through ("+Integer.toHexString(gm.getPixel(5,5))+", "+HeifDecoder.lastProblem+")");
            check((face>>>24)>240&&((face>>16)&0xff)>220&&((face>>8)&0xff)>170&&(face&0xff)<70,"Genmoji: wrong colour "+Integer.toHexString(face));
            check(MediaCache.sticker(gm),"Genmoji not treated as a sticker");
            check(MediaCache.decode(new java.io.File(heifDir,"genmoji.heic"),2560,false)!=null&&MediaCache.isSticker(new java.io.File(heifDir,"genmoji.heic")),"Genmoji not shown through the normal photo path");
            android.graphics.Bitmap tiled=HeifDecoder.decode(new java.io.File(heifDir,"grid.heic"),2560);
            check(tiled!=null&&tiled.getWidth()==400&&tiled.getHeight()==300,"Tiled HEIC not decoded: "+HeifDecoder.lastProblem);
            int red=tiled.getPixel(50,50),blue=tiled.getPixel(350,250);
            check(((red>>16)&0xff)>200&&(red&0xff)<80&&(blue&0xff)>160&&((blue>>16)&0xff)<80,"Tiled HEIC colours wrong: "+Integer.toHexString(red)+" "+Integer.toHexString(blue));
            android.graphics.Bitmap turned=HeifDecoder.decode(new java.io.File(heifDir,"turned.heic"),2560);
            check(turned!=null&&turned.getWidth()==100&&turned.getHeight()==180,"Cropped/rotated HEIC wrong size: "+(turned==null?HeifDecoder.lastProblem:turned.getWidth()+"x"+turned.getHeight()));
            check((turned.getPixel(20,170)&0xff)>200&&((turned.getPixel(80,20)>>16)&0xff)<60,"HEIC rotated the wrong way");
            android.graphics.Bitmap mono=HeifDecoder.decode(new java.io.File(heifDir,"mono-alpha.heic"),2560);
            check(mono!=null&&mono.getWidth()==160,"HEIC with a monochrome see-through layer not shown: "+HeifDecoder.lastProblem);
            check((mono.getPixel(3,3)>>>24)<16&&(mono.getPixel(80,80)>>>24)>240,"Monochrome see-through layer not applied: "+Integer.toHexString(mono.getPixel(3,3))+" "+HeifDecoder.lastProblem);
            // Shaped like the Genmoji iPhones really send: 4:4:4 colour and a monochrome see-through layer, decoded in the sandbox.
            android.graphics.Bitmap g444=HeifDecoder.decode(new java.io.File(heifDir,"genmoji444.heic"),2560);
            check(g444!=null&&g444.getWidth()==354&&g444.getHeight()==316,"4:4:4 Genmoji not decoded: "+HeifDecoder.lastProblem);
            int hat=g444.getPixel(170,30),cheek=g444.getPixel(170,200);
            check((g444.getPixel(3,310)>>>24)<16&&(cheek>>>24)>240&&((cheek>>16)&0xff)>220&&(cheek&0xff)<70&&((hat>>16)&0xff)>90&&((hat>>16)&0xff)<150,"4:4:4 Genmoji colours wrong: "+Integer.toHexString(cheek)+" "+Integer.toHexString(hat));
            // Backup round trip: app-only data comes back after being cleared.
            Nicknames.set(getTargetContext(),"+15550100","Smoke Nick");String backup=Backup.export(getTargetContext());
            Nicknames.set(getTargetContext(),"+15550100","");check(Nicknames.get("+15550100").isEmpty(),"Nickname did not clear");
            check(Backup.restore(getTargetContext(),backup)>0&&Nicknames.get("+15550100").equals("Smoke Nick")&&new Starred(getTargetContext()).has("otp"),"Backup did not restore nicknames and starred messages");
            Nicknames.set(getTargetContext(),"+15550100","");
            boolean rejected=false;try{Backup.restore(getTargetContext(),"{\"app\":\"Other\"}");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"A non-backup file was accepted");
            // Preview-only attachments are recognized (so the phone can be asked for the full file) and never share a cache file.
            java.util.List<JSONObject> parts=PairedProtocol.media(store.message("e"));
            check(MediaCache.hasFull(parts.get(0))&&MediaCache.previewOnly(parts.get(1))&&!MediaCache.hasFull(parts.get(2))&&parts.get(1).optString("_message").equals("e")
                &&!MediaCache.file(getTargetContext(),parts.get(1),false).equals(MediaCache.file(getTargetContext(),parts.get(2),false)),"Attachment download details were misread");
            store.message(new JSONObject("{messageID:'d',conversationID:'smoke',participantID:'p1',timestamp:1794000300000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Replied to a message:\\nSure'}}]}"),0,true);
        }
        // A portrait photo shown in a portrait box must be decoded at least as large as the box (never stretched).
        java.io.File photo=new java.io.File(MediaCache.dir(getTargetContext()),"smoke-portrait.jpg");
        android.graphics.Bitmap source=android.graphics.Bitmap.createBitmap(1500,2000,android.graphics.Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(photo)){source.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);}
        android.graphics.Bitmap shown=MediaCache.decodeToCover(photo,840,1260,false);photo.delete();
        // Bubble icons: a wide photo is cropped, not stretched, and fills the icon.
        android.graphics.Bitmap wide=android.graphics.Bitmap.createBitmap(400,200,android.graphics.Bitmap.Config.ARGB_8888);wide.eraseColor(0xff336699);
        android.graphics.Bitmap icon=Avatars.adaptive(wide,"A",0xff000000,252),round=Avatars.circle(wide,128);
        check(icon.getWidth()==252&&icon.getHeight()==252&&icon.getPixel(126,126)==0xff336699&&round.getPixel(64,64)==0xff336699,"Bubble icon was not built from the photo");
        check(shown!=null&&shown.getWidth()>=840&&shown.getHeight()>=1260&&shown.getWidth()<=1500,"Photo decoded smaller than its box: "+(shown==null?"none":shown.getWidth()+"x"+shown.getHeight()));
        Activity bubble=startActivitySync(new Intent(getTargetContext(),BubbleActivity.class).setAction(Intent.ACTION_VIEW).putExtra("chat","smoke").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();check(bubble!=null&&!bubble.isFinishing(),"Bubble chat did not render");
        // The message box must offer attachments (⊕) and, while empty, voice recording.
        java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Attach photos, videos, or files",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        check(!found.isEmpty()&&found.get(0).isShown(),"Attach button is missing from the message box");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Press and hold to record a voice message",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        check(!found.isEmpty(),"Mic button (tap to dictate, hold to record) is missing from the empty message box");found.clear();
        // Verification codes get a copy chip; starred and scheduled messages are marked.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Copy code 482913",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(!found.isEmpty(),"Copy code chip is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Starred",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Starred marker is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Scheduled for",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Scheduled message is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Main Street Park",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Shared location is missing");found.clear();
        // Addresses in messages are tappable.
        bubble.getWindow().getDecorView().findViewsWithText(found,"805 SE J St",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Address message is missing");
        boolean tappable=false;for(android.view.View v:found)if(v instanceof android.widget.TextView&&((android.widget.TextView)v).getText() instanceof android.text.Spanned){android.text.Spanned sp=(android.text.Spanned)((android.widget.TextView)v).getText();if(sp.getSpans(0,sp.length(),android.text.style.ClickableSpan.class).length>0)tappable=true;}
        check(tappable,"Address isn't tappable");found.clear();
        // Tracking numbers and dates are tappable too.
        bubble.getWindow().getDecorView().findViewsWithText(found,"1Z999AA10123456784",android.view.View.FIND_VIEWS_WITH_TEXT);int spans=0;
        for(android.view.View v:found)if(v instanceof android.widget.TextView&&((android.widget.TextView)v).getText() instanceof android.text.Spanned){android.text.Spanned sp=(android.text.Spanned)((android.widget.TextView)v).getText();spans=Math.max(spans,sp.getSpans(0,sp.length(),android.text.style.ClickableSpan.class).length);}
        check(spans>=2,"Tracking number and date aren't both tappable ("+spans+")");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"laughed at an image",android.view.View.FIND_VIEWS_WITH_TEXT);check(found.isEmpty(),"iPhone reaction to a photo shown as a line, not on the photo");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"1 reaction. Tap to see who reacted.",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(!found.isEmpty(),"iPhone reaction didn't land on the photo");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"loved an image",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"An iPhone reaction with no photo before it disappeared");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Sam Example left",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Group event is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Find the original message for this reply",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(!found.isEmpty(),"Reply without a reference has no refresh action");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Read receipts on",android.view.View.FIND_VIEWS_WITH_TEXT);check(found.size()==1,"A chat with read receipts on doesn't say so at the top ("+found.size()+")");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,". Tap for details.",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        boolean readShown=false;for(android.view.View v:found)if(v.getContentDescription().toString().matches("Read \\d.*"))readShown=true;check(readShown,"No \"Read <time>\" under the message the receipt went out for");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Not on your phone",android.view.View.FIND_VIEWS_WITH_TEXT);check(found.size()==1,"A photo the phone never sent isn't labeled as missing ("+found.size()+")");found.clear();
        // Photos that need the phone say what they're waiting for (not a blank box). Offline here, so: the connection.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Waiting for the connection",android.view.View.FIND_VIEWS_WITH_TEXT);check(found.size()>=2,"Photos waiting on the phone don't say so offline ("+found.size()+")");found.clear();
        // Invisible ink: covered and blurred until tapped; the iPhone's note isn't shown.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Invisible ink message. Double-tap to show it.",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(found.size()==1,"Invisible ink message isn't covered ("+found.size()+")");
        android.view.View cover=found.get(0);found.clear();android.view.View inkContent=((android.view.ViewGroup)cover.getParent()).getChildAt(0);
        check(cover.getVisibility()==android.view.View.VISIBLE,"Invisible ink cover is hidden before it's tapped");
        bubble.getWindow().getDecorView().findViewsWithText(found,"Invisible Ink)",android.view.View.FIND_VIEWS_WITH_TEXT);check(found.isEmpty(),"The \"(sent with Invisible Ink)\" note is shown");found.clear();
        runOnMainSync(cover::performClick);waitForIdleSync();check(cover.getVisibility()==android.view.View.GONE,"Tapping invisible ink didn't show it");
        check(inkContent!=null,"Invisible ink message has nothing under the cover");
        // Details: what's been shared, counted and sorted into photos & videos, links (not the shared location's map link) and files.
        PairedActivity chatWindow=(PairedActivity)bubble;onMain(chatWindow,"details");
        String[] wanted={"Photos & videos, ","Links, 1","Files, 3"};
        for(String w:wanted){boolean seenIt=false;for(int wait=0;wait<40&&!seenIt;wait++){found.clear();runOnMainSync(()->{});
                bubble.getWindow().getDecorView().findViewsWithText(found,w,android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);seenIt=!found.isEmpty();if(!seenIt)Thread.sleep(100);}
            check(seenIt,"Details doesn't list \""+w+"…\" under Shared");}
        found.clear();
        runOnMainSync(()->{try{java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod("sharedScreen",int.class);m.setAccessible(true);m.invoke(chatWindow,1);}catch(Exception e){throw new RuntimeException(e);}});
        boolean linkShown=false;for(int wait=0;wait<40&&!linkShown;wait++){found.clear();runOnMainSync(()->{});bubble.getWindow().getDecorView().findViewsWithText(found,"example.com/page",android.view.View.FIND_VIEWS_WITH_TEXT);linkShown=!found.isEmpty();if(!linkShown)Thread.sleep(100);}
        check(linkShown,"The Links tab doesn't show the link that was sent");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Links, 1, selected",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(found.size()==1,"The Links tab isn't the one selected");found.clear();
        runOnMainSync(bubble::onBackPressed);waitForIdleSync();runOnMainSync(bubble::onBackPressed);waitForIdleSync();
        // Screenshots are allowed.
        check((bubble.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)==0,"Screenshots are blocked in chats");
        check((activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)==0,"Screenshots are blocked in the inbox");
        // Tapping ⊕ opens the attach panel with Photos & videos, Camera, and Files.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Attach photos, videos, or files",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        android.view.View plus=found.get(0);runOnMainSync(plus::performClick);waitForIdleSync();
        check(bubble.hasWindowFocus()==false,"Attach panel did not open");bubble.finish();waitForIdleSync();
        checkChatKeepsItsPlace();
        check(!PairedService.canReconnect(getTargetContext()),"Reconnect offered with no saved session to reconnect with");
        checkPinnedTap((PairedActivity)activity);
        checkInboxKeepsItsPlace((PairedActivity)activity);
        checkNewConversationSearch((PairedActivity)activity);
        checkMarkup((PairedActivity)activity);
        String speed=measureRedraw();
        try(PairedStore store=new PairedStore(getTargetContext())){store.clear();}
        Outbox box=new Outbox(getTargetContext());for(JSONObject o:box.all())box.remove(o.optString("id"));
        new ChatSettings(getTargetContext()).setReceipts("smoke",false);new ReadReceiptQueue(getTargetContext()).cancel("smoke");
        getTargetContext().getSharedPreferences("transcripts",0).edit().clear().commit();getTargetContext().getSharedPreferences("phone_media",0).edit().clear().commit();
        for(JSONObject o:Scheduled.forChat(getTargetContext(),"smoke"))Scheduled.remove(getTargetContext(),o.optString("id"));
        getTargetContext().getSharedPreferences("starred",0).edit().clear().commit();
        new ChatSettings(getTargetContext()).setBackground("smoke","");
        checkNotificationPrivacy();
        result.putString("stream","MESSAGE_CENTER_SMOKE_PASS: notification privacy, "+speed+", JNI, Keystore, history conflict, ID deduplication, activity launch, bubble chat render with attachments, failed retry, outbox, link, voice transcript, search, code chip, starred, scheduled, preview-only media, location, group events, photo fetch state, placeholder previews, screenshots allowed, backup, addresses, chat background, smart taps, scroll anchoring (chat + inbox), message effects and invisible ink, shared photos/links/files, contact search, iPhone stickers and Genmoji (4:4:4 + monochrome, sandboxed)\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","MESSAGE_CENTER_SMOKE_FAIL: "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
