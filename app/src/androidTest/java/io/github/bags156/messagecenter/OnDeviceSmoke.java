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
    /** The inbox's first row tag, or null while the list is being rebuilt (it can be empty for a frame). */
    private static Object firstRow(android.view.ViewGroup list,int tag){android.view.View v=list==null?null:list.getChildAt(0);return v==null?null:v.getTag(tag);}
    /**
     * A sent text never vanishes between Google accepting it and Google's copy arriving (the chat
     * jumped up and back down), and it's never drawn twice while both exist.
     */
    private void checkSentTextStays(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();String words="Gap check, this text must not jump";
        long now=System.currentTimeMillis();
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'gap-check',name:'Gap Check',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'gc',number:'+15550107777'},fullName:'Gap Check'}]}"));
            for(int i=0;i<12;i++)store.message(new JSONObject().put("messageID","gapold"+i).put("conversationID","gap-check").put("participantID",i%2==0?"gc":"me").put("timestamp",(now-600_000+i*1000)*1000)
                .put("messageStatus",new JSONObject().put("status",i%2==0?"INCOMING_COMPLETE":"OUTGOING_DELIVERED")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","Earlier message "+i)))),0,true);}
        Outbox box=new Outbox(ctx);JSONObject queued=box.add("gap-check",words,"");String qid=queued.optString("id");box.sent(qid,"tmp-gap-check");
        java.lang.reflect.Method openChat=PairedActivity.class.getDeclaredMethod("thread",String.class),leaveChat=PairedActivity.class.getDeclaredMethod("inbox"),redraw=PairedActivity.class.getDeclaredMethod("renderMessages",boolean.class);
        for(java.lang.reflect.Method m:new java.lang.reflect.Method[]{openChat,leaveChat,redraw})m.setAccessible(true);
        int[] shown=new int[3];
        java.util.function.IntSupplier count=()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();a.getWindow().getDecorView().findViewsWithText(found,words,android.view.View.FIND_VIEWS_WITH_TEXT);return found.size();};
        runOnMainSync(()->{try{openChat.invoke(a,"gap-check");}catch(Exception x){throw new RuntimeException(x);}});waitForIdleSync();
        runOnMainSync(()->shown[0]=count.getAsInt());
        // Google's copy is saved; the queued one hasn't been dropped yet (the order the service does it in).
        JSONObject copy=new JSONObject().put("messageID","gap-real").put("tmpID","tmp-gap-check").put("conversationID","gap-check").put("participantID","me").put("timestamp",(System.currentTimeMillis()+500)*1000)
            .put("messageStatus",new JSONObject().put("status","OUTGOING_DELIVERED")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content",words))));
        try(PairedStore store=new PairedStore(ctx)){store.message(copy,0,true);}
        runOnMainSync(()->{try{redraw.invoke(a,false);}catch(Exception x){throw new RuntimeException(x);}});waitForIdleSync();runOnMainSync(()->shown[1]=count.getAsInt());
        boolean removed=box.arrived(copy);
        runOnMainSync(()->{try{redraw.invoke(a,false);}catch(Exception x){throw new RuntimeException(x);}});waitForIdleSync();runOnMainSync(()->shown[2]=count.getAsInt());
        runOnMainSync(()->{try{leaveChat.invoke(a);}catch(Exception x){throw new RuntimeException(x);}});
        box.remove(qid);try(PairedStore store=new PairedStore(ctx)){store.deleteChat("gap-check");}
        check(shown[0]==1,"An accepted text wasn't shown while waiting for Google's copy ("+shown[0]+")");
        check(shown[1]==1,"A sent text was drawn "+shown[1]+" times while Google's copy and the queued one both existed");
        check(removed&&shown[2]==1,"Google's copy didn't replace the queued text ("+removed+", "+shown[2]+")");}
    /**
     * A chat with a number you haven't saved says who it might be (the name Google has, the groups
     * you share) until dismissed; Details lists groups in common; a pinned message sits under the
     * header, survives reopening the chat, and goes when unpinned.
     */
    private void checkPinsAndWhoIsThis(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();long now=System.currentTimeMillis();
        try(PairedStore store=new PairedStore(ctx)){
            store.chat(new JSONObject("{conversationID:'who-check',type:'RCS',lastMessageTimestamp:"+now*1000+",participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'wc',number:'+15550106666'}}]}"));
            store.chat(new JSONObject("{conversationID:'who-group',name:'Pickup crew',type:'RCS',lastMessageTimestamp:"+now*1000+",participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'wc2',number:'(555) 010-6666'},fullName:'Dana Check'},{ID:{participantID:'o',number:'+15550105555'},fullName:'Other Person'}]}"));
            store.message(new JSONObject().put("messageID","who-msg").put("conversationID","who-check").put("participantID","wc").put("timestamp",now*1000)
                .put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","The gate code is 4417")))),0,true);}
        java.lang.reflect.Method openChat=PairedActivity.class.getDeclaredMethod("thread",String.class),leaveChat=PairedActivity.class.getDeclaredMethod("inbox"),details=PairedActivity.class.getDeclaredMethod("details"),
            renderPins=PairedActivity.class.getDeclaredMethod("renderPinBar");
        for(java.lang.reflect.Method m:new java.lang.reflect.Method[]{openChat,leaveChat,details,renderPins})m.setAccessible(true);
        java.lang.reflect.Field pinsField=PairedActivity.class.getDeclaredField("pins"),barField=PairedActivity.class.getDeclaredField("pinBar");pinsField.setAccessible(true);barField.setAccessible(true);
        java.util.function.Function<String,Integer> count=words->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();a.getWindow().getDecorView().findViewsWithText(found,words,android.view.View.FIND_VIEWS_WITH_TEXT);return found.size();};
        int[] got=new int[9];
        try{runOnMainSync(()->{try{openChat.invoke(a,"who-check");got[0]=count.apply("Not in your contacts");got[1]=count.apply("Google lists them as Dana Check");got[2]=count.apply("Also in Pickup crew");
                ChatPins pins=(ChatPins)pinsField.get(a);pins.pin("who-check","who-msg","The gate code is 4417",now);renderPins.invoke(a);
                android.widget.LinearLayout bar=(android.widget.LinearLayout)barField.get(a);java.util.ArrayList<android.view.View> inBar=new java.util.ArrayList<>();bar.findViewsWithText(inBar,"gate code is 4417",android.view.View.FIND_VIEWS_WITH_TEXT);
                got[3]=bar.getVisibility()==android.view.View.VISIBLE?inBar.size():-1;
                details.invoke(a);got[4]=count.apply("Groups in common");got[5]=count.apply("Pickup crew");
                openChat.invoke(a,"who-check");bar=(android.widget.LinearLayout)barField.get(a);inBar.clear();bar.findViewsWithText(inBar,"gate code is 4417",android.view.View.FIND_VIEWS_WITH_TEXT);got[6]=inBar.size();
                pins.unpin("who-check","who-msg");renderPins.invoke(a);got[7]=bar.getVisibility()==android.view.View.GONE?1:0;
                ctx.getSharedPreferences("whois",android.content.Context.MODE_PRIVATE).edit().putBoolean("who-check",true).commit();openChat.invoke(a,"who-check");got[8]=count.apply("Not in your contacts");
                leaveChat.invoke(a);}catch(Exception x){throw new RuntimeException(x);}});}
        finally{ctx.getSharedPreferences("whois",android.content.Context.MODE_PRIVATE).edit().remove("who-check").commit();new ChatPins(ctx).clear("who-check");
            try(PairedStore store=new PairedStore(ctx)){store.deleteChat("who-check");store.deleteChat("who-group");}}
        check(got[0]==1&&got[1]==1&&got[2]==1,"A number you haven't saved didn't get its Who's this card: "+got[0]+","+got[1]+","+got[2]);
        check(got[3]==1,"The pinned message didn't show under the header ("+got[3]+")");
        check(got[4]==1&&got[5]>=1,"Details didn't list the group in common ("+got[4]+","+got[5]+")");
        check(got[6]==1,"The pin didn't survive reopening the chat");
        check(got[7]==1,"Unpinning didn't hide the pin bar");
        check(got[8]==0,"The Who's this card came back after it was dismissed");}
    /**
     * A GIF keeps playing through chat redraws: the redrawn chat gets the very same playing GIF at
     * once, instead of the still preview and a fresh decode (which flashed and restarted it).
     */
    private void checkGifSurvivesRedraw(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();long now=System.currentTimeMillis();
        byte[] gif=android.util.Base64.decode("R0lGODlhEAAQAIEAANweHgAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQADAAAACwAAAAAEAAQAAAIHQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFgQEBACH5BAEMAAEALAAAAAAQABAAgR4e3AAAAAAAAAAAAAgdAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWBAQEAIfkEAQwAAQAsAAAAABAAEACBHsg8AAAAAAAAAAAACB0AAQgcSLCgwYMIEypcyLChw4cQI0qcSLGixYEBAQA7",android.util.Base64.DEFAULT);
        JSONObject media=new JSONObject().put("mediaID","gif-check-media").put("mimeType","image/gif").put("mediaName","check.gif").put("size",gif.length).put("decryptionKey","AAAA");
        java.io.File file=MediaCache.file(ctx,media,false);file.getParentFile().mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(gif);}
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'gif-check',name:'Gif Check',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'gf',number:'+15550104444'},fullName:'Gif Check'}]}"));
            store.message(new JSONObject().put("messageID","gif-msg").put("conversationID","gif-check").put("participantID","gf").put("timestamp",now*1000).put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE"))
                .put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("actionMessageID","gif-part").put("mediaContent",media))),0,true);}
        java.lang.reflect.Method openChat=PairedActivity.class.getDeclaredMethod("thread",String.class),leaveChat=PairedActivity.class.getDeclaredMethod("inbox"),redraw=PairedActivity.class.getDeclaredMethod("renderMessages",boolean.class);
        for(java.lang.reflect.Method m:new java.lang.reflect.Method[]{openChat,leaveChat,redraw})m.setAccessible(true);
        java.util.function.Supplier<android.graphics.drawable.Drawable> playingNow=()->{java.util.ArrayDeque<android.view.View> q=new java.util.ArrayDeque<>();q.add(a.getWindow().getDecorView());
            while(!q.isEmpty()){android.view.View v=q.poll();if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++)q.add(((android.view.ViewGroup)v).getChildAt(i));
                if(v instanceof android.widget.ImageView&&((android.widget.ImageView)v).getDrawable() instanceof android.graphics.drawable.AnimatedImageDrawable)return ((android.widget.ImageView)v).getDrawable();}return null;};
        android.graphics.drawable.Drawable[] seen=new android.graphics.drawable.Drawable[3];
        try{runOnMainSync(()->{try{openChat.invoke(a,"gif-check");}catch(Exception x){throw new RuntimeException(x);}});
            for(int wait=0;wait<30&&seen[0]==null;wait++){Thread.sleep(100);runOnMainSync(()->seen[0]=playingNow.get());}
            // Redrawn: the same playing GIF is there right away, before anything could load.
            runOnMainSync(()->{try{redraw.invoke(a,false);seen[1]=playingNow.get();redraw.invoke(a,false);seen[2]=playingNow.get();}catch(Exception x){throw new RuntimeException(x);}});}
        finally{runOnMainSync(()->{try{leaveChat.invoke(a);}catch(Exception x){throw new RuntimeException(x);}});file.delete();try(PairedStore store=new PairedStore(ctx)){store.deleteChat("gif-check");}}
        check(seen[0]!=null,"The GIF didn't play in the chat");
        check(seen[1]==seen[0]&&seen[2]==seen[0],"A redraw replaced the playing GIF (it would flash and restart)");
        check(((android.graphics.drawable.AnimatedImageDrawable)seen[0]).isRunning(),"The GIF stopped playing after a redraw");}
    /**
     * A photo you sent is shown from the copy already on the phone: when Google's copy of the
     * message arrives, the sent file is kept under it before the queued one is cleared, so it
     * isn't downloaded back from Google.
     */
    private void checkSentPhotoKept()throws Exception{android.content.Context ctx=getTargetContext();MediaOutbox box=new MediaOutbox(ctx);
        android.graphics.Bitmap pic=android.graphics.Bitmap.createBitmap(64,48,android.graphics.Bitmap.Config.ARGB_8888);pic.eraseColor(0xff2266aa);
        java.io.File source=new java.io.File(ctx.getCacheDir(),"sent-check.jpg");try(java.io.FileOutputStream out=new java.io.FileOutputStream(source)){pic.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);}
        long size=source.length();JSONObject job=box.enqueue("sent-check",source,"sent-check.jpg","image/jpeg","","","standard",false,false,null);String id=job.optString("id");
        box.finish(id,job.optLong("attempt"),new JSONObject().put("mediaTmpID","tmp-sent-check"));
        JSONObject media=new JSONObject().put("mediaID","sent-check-media").put("mimeType","image/jpeg").put("mediaName","sent-check.jpg").put("size",size).put("decryptionKey","AAAA").put("thumbnailMediaID","sent-check-thumb");
        JSONObject copy=new JSONObject().put("messageID","sent-check-msg").put("tmpID","tmp-sent-check").put("conversationID","sent-check").put("participantID","me").put("timestamp",System.currentTimeMillis()*1000)
            .put("messageStatus",new JSONObject().put("status","OUTGOING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("actionMessageID","sent-check-part").put("mediaContent",media)));
        java.io.File cached=MediaCache.file(ctx,media,false);cached.delete();boolean matched,kept,same=false;
        try{matched=box.reconcile(copy);if(matched)PairedService.keepSentCopy(ctx,box,copy);box.retireCompleted();
            kept=cached.isFile()&&cached.length()==size;
            if(kept){android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(cached.getPath(),o);same=o.outWidth==64&&o.outHeight==48;}}
        finally{box.remove(id);cached.delete();source.delete();}
        check(matched,"Google's copy of the sent photo wasn't matched to it");
        check(kept&&same,"A sent photo wasn't kept on the phone; it would be downloaded back from Google");
        check(box.get(id)==null,"The sent photo stayed queued after Google's copy arrived");}
    /** Snoozed until it's quiet: each new message keeps it snoozed 30 more minutes; after a quiet half hour it's off and stays off. */
    private void checkSnoozeUntilQuiet()throws Exception{ChatSettings s=new ChatSettings(getTargetContext());String id="quiet-check";
        java.lang.reflect.Field f=ChatSettings.class.getDeclaredField("prefs");f.setAccessible(true);android.content.SharedPreferences prefs=(android.content.SharedPreferences)f.get(s);
        try{s.snoozeUntilQuiet(id);check(s.snoozed(id)&&s.untilQuiet(id),"Until it's quiet didn't snooze the chat");
            prefs.edit().putLong("snooze:"+id,System.currentTimeMillis()+1000).commit();s.activity(id);
            check(s.snoozedUntil(id)>=System.currentTimeMillis()+ChatSettings.QUIET_MS-5000,"A new message didn't keep the chat quiet");
            prefs.edit().putLong("snooze:"+id,System.currentTimeMillis()-1).commit();s.activity(id);
            check(!s.snoozed(id)&&!s.untilQuiet(id),"A message after the quiet half hour snoozed the chat again");
            s.snoozeUntilQuiet(id);s.setSnooze(id,System.currentTimeMillis()+3_600_000L);check(!s.untilQuiet(id),"Choosing a set time didn't replace until it's quiet");}
        finally{s.setSnooze(id,0);}}
    /** React with any emoji: the picker opens in the app's own theme and fills with emoji. */
    private static <T extends android.view.View> java.util.List<T> viewsOf(android.view.View root,Class<T> type){java.util.List<T> out=new java.util.ArrayList<>();
        java.util.ArrayDeque<android.view.View> q=new java.util.ArrayDeque<>();q.add(root);
        while(!q.isEmpty()){android.view.View v=q.poll();if(type.isInstance(v))out.add(type.cast(v));
            if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++)q.add(((android.view.ViewGroup)v).getChildAt(i));}
        return out;}
    /** The emoji picker's tabs (clickable labels: Recent, then the categories), in order. */
    private static java.util.List<android.widget.TextView> emojiTabs(android.app.Dialog d){java.util.List<android.widget.TextView> tabs=new java.util.ArrayList<>();
        for(android.widget.TextView t:viewsOf(d.getWindow().getDecorView(),android.widget.TextView.class))if(t.isClickable()&&t.getContentDescription()!=null&&!(t instanceof android.widget.EditText))tabs.add(t);
        return tabs;}
    /** The first emoji shown in the picker's grid, up to n of them. */
    private static String firstEmoji(android.widget.GridView grid,int n){StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.min(n,grid.getAdapter().getCount());i++)b.append(grid.getAdapter().getItem(i)).append(' ');return b.toString().trim();}
    /**
     * The emoji picker: every tab has emoji in it (they came up empty on a phone with Android's
     * picker), search finds emoji by name and keyword and takes a typed emoji, press-and-hold shows
     * skin tones, and a picked emoji is reported and comes first in Recent the next time.
     */
    private void checkEmojiPicker(PairedActivity a)throws Exception{a.getSharedPreferences("emoji",android.content.Context.MODE_PRIVATE).edit().clear().commit();
        java.lang.reflect.Method open=PairedActivity.class.getDeclaredMethod("emojiPicker",java.util.function.Consumer.class);open.setAccessible(true);
        android.app.Dialog[] d=new android.app.Dialog[1];String[] picked={null};
        Runnable show=()->runOnMainSync(()->{try{d[0]=(android.app.Dialog)open.invoke(a,(java.util.function.Consumer<String>)e->picked[0]=e);}catch(Exception x){throw new RuntimeException(x);}});
        show.run();Thread.sleep(500);
        android.widget.GridView[] grid=new android.widget.GridView[1];android.widget.EditText[] search=new android.widget.EditText[1];java.util.List<android.widget.TextView> tabs=new java.util.ArrayList<>();
        runOnMainSync(()->{android.view.View root=d[0].getWindow().getDecorView();java.util.List<android.widget.GridView> g=viewsOf(root,android.widget.GridView.class);java.util.List<android.widget.EditText> e=viewsOf(root,android.widget.EditText.class);
            grid[0]=g.isEmpty()?null:g.get(0);search[0]=e.isEmpty()?null:e.get(0);tabs.addAll(emojiTabs(d[0]));});
        check(d[0].isShowing()&&grid[0]!=null&&search[0]!=null&&tabs.size()==10,"The emoji picker is missing parts ("+d[0].isShowing()+", "+(grid[0]!=null)+", "+(search[0]!=null)+", "+tabs.size()+" tabs)");
        StringBuilder empty=new StringBuilder();
        for(int t=1;t<tabs.size();t++){int tab=t;int[] n=new int[2];runOnMainSync(()->tabs.get(tab).performClick());Thread.sleep(250);
            runOnMainSync(()->{n[0]=grid[0].getAdapter().getCount();n[1]=grid[0].getChildCount();});
            if(n[0]<40||n[1]==0)empty.append(tabs.get(tab).getContentDescription()).append(" (").append(n[0]).append('/').append(n[1]).append(") ");}
        check(empty.length()==0,"Emoji picker tabs showed no emoji: "+empty);
        String[] found=new String[1];
        java.util.function.Consumer<String> type=text->{runOnMainSync(()->{search[0].setText(text);found[0]=firstEmoji(grid[0],6);});};
        type.accept("fire");check(found[0].startsWith("🔥"),"Searching emoji for fire found "+found[0]);
        type.accept("thumbs up");check(found[0].startsWith("👍"),"Searching emoji for thumbs up found "+found[0]);
        type.accept("lol");check(found[0].contains("😂"),"Searching emoji for lol found "+found[0]);
        type.accept("zzqxv");check(found[0].isEmpty(),"Searching emoji for nonsense found "+found[0]);
        type.accept("🙂");check(found[0].equals("🙂"),"A typed emoji wasn't offered: "+found[0]);
        type.accept("");check(found[0].split(" ").length>=6,"Clearing the emoji search left the grid empty: "+found[0]);
        // Press and hold 👍: its skin tones pop up over it, and tapping one picks it.
        type.accept("thumbs up");Thread.sleep(200);
        java.lang.reflect.Method tones=PairedActivity.class.getDeclaredMethod("skinTones",android.view.View.class,EmojiIndex.Emoji.class,java.util.function.Consumer.class);tones.setAccessible(true);
        java.lang.reflect.Method index=PairedActivity.class.getDeclaredMethod("emojiIndex");index.setAccessible(true);
        android.widget.PopupWindow[] pop=new android.widget.PopupWindow[1];String[] tone={null};int[] offered={0};
        runOnMainSync(()->{try{pop[0]=(android.widget.PopupWindow)tones.invoke(a,grid[0].getChildAt(0),((EmojiIndex)index.invoke(a)).find("👍"),(java.util.function.Consumer<String>)e->tone[0]=e);}catch(Exception x){throw new RuntimeException(x);}});
        Thread.sleep(300);
        runOnMainSync(()->{java.util.List<android.widget.TextView> cells=new java.util.ArrayList<>();for(android.widget.TextView t:viewsOf(pop[0].getContentView(),android.widget.TextView.class))if(t.isClickable())cells.add(t);
            offered[0]=cells.size();if(pop[0].isShowing()&&cells.size()>2)cells.get(2).performClick();});
        check(offered[0]==6&&"👍🏼".equals(tone[0])&&!pop[0].isShowing(),"Pressing and holding 👍 didn't offer its skin tones ("+offered[0]+", "+tone[0]+")");
        type.accept("fire");Thread.sleep(150);runOnMainSync(()->grid[0].performItemClick(grid[0].getChildAt(0),0,0));Thread.sleep(300);
        check("🔥".equals(picked[0])&&!d[0].isShowing(),"Tapping 🔥 in the emoji picker didn't pick it ("+picked[0]+", "+d[0].isShowing()+")");
        show.run();Thread.sleep(400);String[] recent=new String[1];
        runOnMainSync(()->{java.util.List<android.widget.GridView> g=viewsOf(d[0].getWindow().getDecorView(),android.widget.GridView.class);recent[0]=g.isEmpty()?"":firstEmoji(g.get(0),3);d[0].dismiss();});
        check(recent[0].startsWith("🔥"),"Recent emoji didn't start with the one just picked: "+recent[0]);
        a.getSharedPreferences("emoji",android.content.Context.MODE_PRIVATE).edit().clear().commit();}
    private static java.lang.reflect.Method method(String name,Class<?>... types)throws Exception{java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
    private static java.lang.reflect.Field field(String name)throws Exception{java.lang.reflect.Field f=PairedActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void onMainCall(java.util.concurrent.Callable<?> work){runOnMainSync(()->{try{work.call();}catch(Exception x){throw new RuntimeException(x);}});}
    /** The note cards on screen, in order, by note id. */
    private java.util.List<String> noteCards(PairedActivity a){java.util.List<String> ids=new java.util.ArrayList<>();
        runOnMainSync(()->{for(android.view.View v:viewsOf(a.getWindow().getDecorView(),android.view.View.class))if(v.getTag() instanceof String&&((String)v.getTag()).startsWith("note:"))ids.add(((String)v.getTag()).substring(5));});return ids;}
    /**
     * Notes: a new note written in the editor is kept with its tags, pin and an attached picture
     * (in its own folder, readable through the app's link); notes list pinned first, then newest;
     * searching by word or #tag narrows the list; a message saved as a note says where it came
     * from; the chat shows a notes button; inbox search finds notes; deleting removes the files.
     */
    private void checkNotes(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();String number="+15557770142",key=Notes.personKey(number);
        Notes notes=new Notes(ctx);for(Notes.Note n:notes.list(key))notes.delete(key,n.id);JSONObject chat;
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'notes-check',name:'Nora Check',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'nc',number:'"+number+"'},fullName:'Nora Check'}]}"));chat=store.chat("notes-check");}
        java.lang.reflect.Method list=method("notesList",String.class,String.class,Runnable.class),thread=method("thread",String.class),inbox=method("inbox"),toNote=method("messageToNote",JSONObject.class,JSONObject.class,boolean.class),
            addTags=method("addNoteTags",String.class),pin=method("toggleNotePin"),attach=method("addNoteFiles",java.util.List.class),hits=method("renderNoteHits");
        java.lang.reflect.Field noteField=field("noteField"),editing=field("editing"),query=field("query"),items=field("items");
        Runnable back=()->{try{thread.invoke(a,"notes-check");}catch(Exception x){throw new RuntimeException(x);}};
        // A new note: words, two tags, pinned, and a picture.
        onMainCall(()->list.invoke(a,key,"Nora Check",back));waitForIdleSync();
        onMainCall(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();a.getWindow().getDecorView().findViewsWithText(found,"New note",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            if(found.isEmpty())throw new AssertionError("The notes list has no New note button");return found.get(0).performClick();});waitForIdleSync();
        java.io.File pic=new java.io.File(ctx.getCacheDir(),"note-test.png");android.graphics.Bitmap red=android.graphics.Bitmap.createBitmap(40,40,android.graphics.Bitmap.Config.ARGB_8888);red.eraseColor(0xffcc2222);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(pic)){red.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        onMainCall(()->{((android.widget.EditText)noteField.get(a)).setText("Gift idea: blue scarf");addTags.invoke(a,"Gift, birthday");pin.invoke(a);return attach.invoke(a,java.util.Collections.singletonList(android.net.Uri.fromFile(pic)));});
        int[] attached={0};for(int wait=0;wait<50&&attached[0]==0;wait++){Thread.sleep(100);onMainCall(()->{Notes.Note e=(Notes.Note)editing.get(a);attached[0]=e==null?-1:e.attachments.size();return null;});}
        check(attached[0]==1,"A picture attached to a note wasn't kept ("+attached[0]+")");
        Thread.sleep(400);shoot("note-editor");
        runOnMainSync(a::onBackPressed);waitForIdleSync();
        java.util.List<Notes.Note> saved=notes.list(key);
        check(saved.size()==1,"The new note wasn't saved ("+saved.size()+")");Notes.Note first=saved.get(0);
        check(first.text.equals("Gift idea: blue scarf")&&first.tags.equals(java.util.Arrays.asList("Gift","birthday"))&&first.pinned&&first.attachments.size()==1,
            "The note lost something: "+first.text+" "+first.tags+" pinned "+first.pinned+" "+first.attachments.size());
        java.io.File kept=notes.file(first,first.attachments.get(0));
        check(kept!=null&&kept.isFile()&&kept.length()==pic.length(),"The note's picture isn't in its folder");
        try(java.io.InputStream in=ctx.getContentResolver().openInputStream(MediaProvider.noteUri(kept,"note-test.png"))){check(in!=null&&in.read()>=0,"The note's picture can't be opened through the app's link");}
        // Two more: pinned first, then newest, then older.
        long now=System.currentTimeMillis();
        Notes.Note older=new Notes.Note("t1older","Ask how the new job is going",now-86_400_000L);older.tags=Notes.withTag(older.tags,"work");notes.save(key,"Nora Check",older);
        Notes.Note newer=new Notes.Note("t2newer","Coffee order: oat latte",now+60_000);notes.save(key,"Nora Check",newer);
        onMainCall(()->list.invoke(a,key,"Nora Check",back));waitForIdleSync();
        java.util.List<String> order=noteCards(a);
        check(order.equals(java.util.Arrays.asList(first.id,"t2newer","t1older")),"Notes aren't pinned first, then newest: "+order);
        shoot("notes-list");
        // The same screens in the black theme, pictured for the CI log.
        java.lang.reflect.Method editor=method("noteEditor",String.class,String.class,Notes.Note.class,Runnable.class);
        PairedActivity.blackForPictures=true;
        try{onMainCall(()->list.invoke(a,key,"Nora Check",back));waitForIdleSync();shoot("notes-list-black");
            onMainCall(()->editor.invoke(a,key,"Nora Check",notes.list(key).get(0),back));waitForIdleSync();
            onMainCall(()->{android.view.View f=(android.view.View)noteField.get(a);if(f!=null)a.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(f.getWindowToken(),0);return null;});shoot("note-editor-black");}
        finally{PairedActivity.blackForPictures=false;}
        onMainCall(()->list.invoke(a,key,"Nora Check",back));waitForIdleSync();
        android.widget.EditText[] search={null};
        runOnMainSync(()->{for(android.widget.EditText e:viewsOf(a.getWindow().getDecorView(),android.widget.EditText.class))if("Search notes".contentEquals(e.getContentDescription()==null?"":e.getContentDescription()))search[0]=e;});
        check(search[0]!=null,"The notes list has no search box");
        runOnMainSync(()->search[0].setText("#work"));check(noteCards(a).equals(java.util.Arrays.asList("t1older")),"Searching notes for #work showed "+noteCards(a));
        runOnMainSync(()->search[0].setText("latte"));check(noteCards(a).equals(java.util.Arrays.asList("t2newer")),"Searching notes for latte showed "+noteCards(a));
        runOnMainSync(()->search[0].setText("#gift"));check(noteCards(a).equals(java.util.Arrays.asList(first.id)),"Searching notes for #gift showed "+noteCards(a));
        runOnMainSync(()->search[0].setText(""));check(noteCards(a).size()==3,"Clearing the notes search didn't show them all: "+noteCards(a));
        // Hold a message: a new note from it, saying where it came from.
        JSONObject m=new JSONObject().put("messageID","notes-msg").put("conversationID","notes-check").put("participantID","nc").put("timestamp",now*1000)
            .put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","Starting the new job Monday"))));
        onMainCall(()->{thread.invoke(a,"notes-check");return toNote.invoke(a,m,chat,false);});
        Notes.Note fromMessage=null;for(int wait=0;wait<50&&fromMessage==null;wait++){Thread.sleep(100);for(Notes.Note n:notes.list(key))if(n.text.equals("Starting the new job Monday"))fromMessage=n;}
        check(fromMessage!=null&&fromMessage.source.startsWith("Nora Check's message, "),"A message saved as a note: "+(fromMessage==null?"missing":fromMessage.source));
        waitForIdleSync();runOnMainSync(a::onBackPressed);waitForIdleSync();
        int[] button={0};runOnMainSync(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();a.getWindow().getDecorView().findViewsWithText(found,"Your notes",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);button[0]=found.size();});
        check(button[0]>0,"The chat has no notes button although there are notes");
        // Inbox search's notes section (the unpaired test inbox has no list, so it's drawn into one here).
        int[] found={0};
        onMainCall(()->{inbox.invoke(a);android.widget.LinearLayout into=new android.widget.LinearLayout(a);into.setOrientation(android.widget.LinearLayout.VERTICAL);
            items.set(a,into);query.set(a,"latte");hits.invoke(a);java.util.ArrayList<android.view.View> got=new java.util.ArrayList<>();into.findViewsWithText(got,"oat latte",android.view.View.FIND_VIEWS_WITH_TEXT);found[0]=got.size();
            items.set(a,null);query.set(a,"");return inbox.invoke(a);});waitForIdleSync();
        check(found[0]>0,"Searching the inbox for a word in a note didn't find it");
        java.io.File folder=kept.getParentFile();for(Notes.Note n:notes.list(key))notes.delete(key,n.id);pic.delete();
        check(!folder.exists()&&!notes.has(key),"Deleting a note left its files behind");}
    /**
     * The chat's ⋮ menu is a sheet: tiles for Details, Notes, Search and Theme; switches for pin
     * and read receipts; Block and Delete at the bottom; Refresh no longer in it. The pin switch
     * pins the chat, and the Notes tile opens the notes.
     */
    private void checkChatMenu(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'menu-check',name:'Mia Check',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'mc',number:'+15557770199'},fullName:'Mia Check'}]}"));}
        java.lang.reflect.Method thread=method("thread",String.class),menu=method("chatMenu",android.view.View.class);java.lang.reflect.Field settings=field("chatSettings"),screen=field("screen");
        android.app.Dialog[] d={null};onMainCall(()->{thread.invoke(a,"menu-check");d[0]=(android.app.Dialog)menu.invoke(a,(android.view.View)null);return null;});waitForIdleSync();
        check(d[0]!=null&&d[0].isShowing(),"The chat menu didn't open");
        // A picture of it for the CI log, to review the design.
        Thread.sleep(700);android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
        if(shot!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(ctx.getCacheDir(),"chat-menu.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        StringBuilder missing=new StringBuilder();
        for(String label:new String[]{"Details","Notes","Search","Theme","Pin to top","Notifications: On","Read receipts","Open as bubble","Archive","Block\u2026","Delete conversation\u2026"}){int[] n={0};
            runOnMainSync(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();d[0].getWindow().getDecorView().findViewsWithText(found,label,android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);n[0]=found.size();});
            if(n[0]==0)missing.append(label).append(", ");}
        check(missing.length()==0,"The chat menu is missing: "+missing);
        int[] switches={0},refresh={0};runOnMainSync(()->{switches[0]=viewsOf(d[0].getWindow().getDecorView(),android.widget.Switch.class).size();
            java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();d[0].getWindow().getDecorView().findViewsWithText(found,"Refresh",android.view.View.FIND_VIEWS_WITH_TEXT|android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);refresh[0]=found.size();});
        check(switches[0]==2&&refresh[0]==0,"The chat menu has "+switches[0]+" switches and "+refresh[0]+" Refresh");
        // The pin switch pins it (and again unpins it).
        boolean[] pinned={false,true};
        onMainCall(()->{android.widget.Switch pin=null;for(android.widget.Switch sw:viewsOf(d[0].getWindow().getDecorView(),android.widget.Switch.class))if("Pin to top".contentEquals(sw.getContentDescription()))pin=sw;
            pin.toggle();JSONObject c=new PairedStore(ctx).chat("menu-check");pinned[0]=((ChatSettings)settings.get(a)).pinned(c);pin.toggle();pinned[1]=((ChatSettings)settings.get(a)).pinned(c);return null;});
        check(pinned[0]&&!pinned[1],"The pin switch didn't pin and unpin the chat ("+pinned[0]+", "+pinned[1]+")");
        // The Notes tile opens the notes.
        onMainCall(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();d[0].getWindow().getDecorView().findViewsWithText(found,"Notes",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            for(android.view.View v:found)if(v.isClickable()){v.performClick();break;}return null;});waitForIdleSync();
        String[] now={""};onMainCall(()->{now[0]=(String)screen.get(a);return null;});
        check("notes".equals(now[0])&&!d[0].isShowing(),"The Notes tile didn't open the notes ("+now[0]+")");
        runOnMainSync(a::onBackPressed);waitForIdleSync();
        // The same sheet in the black theme, pictured for the CI log (the colors are set directly; the next screen resets them).
        onMainCall(()->{thread.invoke(a,"menu-check");int[] colors=PairedActivity.palette(true,true);String[] names={"bg","card","surface","ink","muted"};
            for(int i=0;i<names.length;i++)field(names[i]).setInt(a,colors[i]);field("fab").setInt(a,0xff3b4766);field("onFab").setInt(a,0xffd8e2ff);field("danger").setInt(a,0xfff2b8b5);field("dark").setBoolean(a,true);
            d[0]=(android.app.Dialog)menu.invoke(a,(android.view.View)null);return null;});
        Thread.sleep(700);android.graphics.Bitmap darkShot=getUiAutomation().takeScreenshot();
        if(darkShot!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(ctx.getCacheDir(),"chat-menu-dark.png"))){darkShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        onMainCall(()->{d[0].dismiss();return thread.invoke(a,"menu-check");});waitForIdleSync();}
    /**
     * The inbox's ⋮ menu is a sheet: tiles for Archived, Starred, Unknown and Spam, Mark all as
     * read, Refresh and Settings; no Needs a reply (it's on the inbox already). The Starred tile
     * opens the starred messages.
     */
    private void checkInboxMenu(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        java.lang.reflect.Method inbox=method("inbox"),menu=method("inboxMenu",android.view.View.class),thread=method("thread",String.class);java.lang.reflect.Field screen=field("screen");
        android.app.Dialog[] d={null};onMainCall(()->{inbox.invoke(a);d[0]=(android.app.Dialog)menu.invoke(a,(android.view.View)null);return null;});waitForIdleSync();
        check(d[0]!=null&&d[0].isShowing(),"The inbox menu didn't open");
        Thread.sleep(700);android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
        if(shot!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(ctx.getCacheDir(),"inbox-menu.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        StringBuilder missing=new StringBuilder();
        for(String label:new String[]{"Archived","Starred","Unknown","Spam","Mark all as read","Refresh from Google","Settings"}){int[] n={0};
            runOnMainSync(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();d[0].getWindow().getDecorView().findViewsWithText(found,label,android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);n[0]=found.size();});
            if(n[0]==0)missing.append(label).append(", ");}
        int[] reply={0};runOnMainSync(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();d[0].getWindow().getDecorView().findViewsWithText(found,"Needs a reply",android.view.View.FIND_VIEWS_WITH_TEXT|android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);reply[0]=found.size();});
        check(missing.length()==0&&reply[0]==0,"The inbox menu is missing: "+missing+" (Needs a reply shown "+reply[0]+")");
        onMainCall(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();d[0].getWindow().getDecorView().findViewsWithText(found,"Starred",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
            for(android.view.View v:found)if(v.isClickable()){v.performClick();break;}return null;});waitForIdleSync();
        String[] now={""};onMainCall(()->{now[0]=(String)screen.get(a);return null;});
        check("starred".equals(now[0])&&!d[0].isShowing(),"The Starred tile didn't open starred messages ("+now[0]+")");
        // The same sheet in the black theme, pictured for the CI log.
        onMainCall(()->{inbox.invoke(a);int[] colors=PairedActivity.palette(true,true);String[] names={"bg","card","surface","ink","muted"};
            for(int i=0;i<names.length;i++)field(names[i]).setInt(a,colors[i]);field("fab").setInt(a,0xff3b4766);field("onFab").setInt(a,0xffd8e2ff);field("danger").setInt(a,0xfff2b8b5);field("dark").setBoolean(a,true);
            d[0]=(android.app.Dialog)menu.invoke(a,(android.view.View)null);return null;});
        Thread.sleep(700);android.graphics.Bitmap darkShot=getUiAutomation().takeScreenshot();
        if(darkShot!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(ctx.getCacheDir(),"inbox-menu-dark.png"))){darkShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        onMainCall(()->{d[0].dismiss();return inbox.invoke(a);});waitForIdleSync();}
    /**
     * Dialogs use the app's look: a rounded background in the app's colors and sentence-case
     * buttons in its accent color (not Android's gray box with capitals). Pictured light and
     * dark for the CI log.
     */
    private void checkDialogLook(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();String message="Off for every chat unless you turn them on for that chat. When on, opening the chat here marks it read on your phone and Google Messages sends a receipt.";
        android.app.AlertDialog[] d={null};
        onMainCall(()->{d[0]=new android.app.AlertDialog.Builder(a).setTitle("Read receipts").setMessage(message).setNegativeButton("Cancel",null).setPositiveButton("Got it",null).show();return null;});waitForIdleSync();
        int[] color={0};boolean[] caps={true};Object[] background={null};
        runOnMainSync(()->{android.widget.Button ok=d[0].getButton(android.content.DialogInterface.BUTTON_POSITIVE);color[0]=ok.getCurrentTextColor();
            caps[0]=ok.getTransformationMethod()!=null&&ok.getTransformationMethod().getClass().getSimpleName().contains("AllCaps");background[0]=d[0].getWindow().getDecorView().getBackground();});
        int accent=ctx.getColor(ctx.getResources().getIdentifier("dialog_accent","color",ctx.getPackageName()));
        Thread.sleep(500);android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
        if(shot!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(ctx.getCacheDir(),"dialog.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        runOnMainSync(()->d[0].dismiss());
        check(color[0]==accent&&!caps[0]&&background[0] instanceof android.graphics.drawable.InsetDrawable,
            "Dialogs don't have the app's look (button color "+Integer.toHexString(color[0])+" vs "+Integer.toHexString(accent)+", capitals "+caps[0]+", background "+background[0]+")");
        // Dark, for the picture.
        int style=ctx.getResources().getIdentifier("AppDialog","style",ctx.getPackageName());
        onMainCall(()->{android.content.res.Configuration night=new android.content.res.Configuration(a.getResources().getConfiguration());
            night.uiMode=(night.uiMode&~android.content.res.Configuration.UI_MODE_NIGHT_MASK)|android.content.res.Configuration.UI_MODE_NIGHT_YES;
            android.view.ContextThemeWrapper dark=new android.view.ContextThemeWrapper(a,style);dark.applyOverrideConfiguration(night);
            d[0]=new android.app.AlertDialog.Builder(dark,style).setTitle("Read receipts").setMessage(message).setNegativeButton("Cancel",null).setPositiveButton("Got it",null).show();return null;});
        Thread.sleep(600);android.graphics.Bitmap darkShot=getUiAutomation().takeScreenshot();
        if(darkShot!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(ctx.getCacheDir(),"dialog-dark.png"))){darkShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        runOnMainSync(()->d[0].dismiss());}
    private int viewsWithDescription(PairedActivity a,String label){int[] n={0};runOnMainSync(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found,label,android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);n[0]=found.size();});return n[0];}
    private void shoot(String name)throws Exception{Thread.sleep(500);android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
        try(java.io.FileWriter list=new java.io.FileWriter(new java.io.File(getTargetContext().getCacheDir(),"shots.txt"),true)){list.write(name+"\n");}catch(Exception ignored){}
        if(shot!=null)try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),name+".png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}}
    /**
     * Settings: a short main page of pages (Messages, Photos & media, Notifications, Automation,
     * Appearance, Connection, Data & about); each page opens with its settings and Back returns
     * to the main page; searching finds a setting by name or by a word people use for it.
     */
    private void checkSettingsPages(PairedActivity a)throws Exception{java.lang.reflect.Method open=method("openSettings"),page=method("openSettingsPage",String.class),inbox=method("inbox");
        java.lang.reflect.Field current=field("settingsPage"),screen=field("screen");
        onMainCall(()->open.invoke(a));waitForIdleSync();
        StringBuilder missing=new StringBuilder();
        for(String p:new String[]{"Messages","Photos & media","Notifications","Automation","Appearance","Apps","Connection","Data & about","Search settings"})if(viewsWithDescription(a,p)==0)missing.append(p).append(", ");
        check(missing.length()==0,"The settings main page is missing: "+missing);
        check(viewsWithDescription(a,"Unread reminders")==0,"The settings main page still lists every setting");
        shoot("settings-home");
        PairedActivity.blackForPictures=true;try{onMainCall(()->page.invoke(a,"notifications"));waitForIdleSync();shoot("settings-black");}finally{PairedActivity.blackForPictures=false;}
        onMainCall(()->page.invoke(a,"notifications"));waitForIdleSync();
        check(viewsWithDescription(a,"Unread reminders")>0&&viewsWithDescription(a,"Keyword alerts")>0&&viewsWithDescription(a,"Typing indicators")==0,"The Notifications page doesn't hold the notification settings");
        shoot("settings-notifications");
        // Which apps things open in have their own page, not Appearance's.
        onMainCall(()->page.invoke(a,"apps"));waitForIdleSync();
        check(viewsWithDescription(a,"Music app")>0&&viewsWithDescription(a,"Podcast app")>0&&viewsWithDescription(a,"Maps app for addresses")>0&&viewsWithDescription(a,"Calendar app for dates")>0,"The Apps page doesn't hold the music, podcast, maps and calendar apps");
        shoot("settings-apps");
        onMainCall(()->page.invoke(a,"appearance"));waitForIdleSync();
        check(viewsWithDescription(a,"Music app")==0&&viewsWithDescription(a,"Maps app for addresses")==0,"Appearance still lists the app choices");
        onMainCall(()->page.invoke(a,"notifications"));waitForIdleSync();
        runOnMainSync(a::onBackPressed);waitForIdleSync();
        String[] where={"",""};onMainCall(()->{where[0]=(String)screen.get(a);where[1]=(String)current.get(a);return null;});
        check("settings".equals(where[0])&&where[1].isEmpty(),"Back from a settings page didn't return to the main settings page ("+where[0]+", "+where[1]+")");
        android.widget.EditText[] search={null};
        runOnMainSync(()->{for(android.widget.EditText e:viewsOf(a.getWindow().getDecorView(),android.widget.EditText.class))if("Search settings".contentEquals(e.getContentDescription()==null?"":e.getContentDescription()))search[0]=e;});
        check(search[0]!=null,"Settings has no search box");
        runOnMainSync(()->search[0].setText("dark"));waitForIdleSync();
        check(viewsWithDescription(a,"Theme")>0,"Searching settings for \"dark\" didn't find Theme");
        runOnMainSync(()->search[0].setText("undo"));waitForIdleSync();
        check(viewsWithDescription(a,"Undo send")>0&&viewsWithDescription(a,"Theme")==0,"Searching settings for \"undo\" didn't narrow to Undo send");
        runOnMainSync(()->search[0].setText("spotify"));waitForIdleSync();
        check(viewsWithDescription(a,"Music app")>0,"Searching settings for \"spotify\" didn't find Music app");
        shoot("settings-search");
        runOnMainSync(()->search[0].setText(""));waitForIdleSync();
        check(viewsWithDescription(a,"Data & about")>0,"Clearing the settings search didn't bring the pages back");
        runOnMainSync(a::onBackPressed);waitForIdleSync();onMainCall(()->{where[0]=(String)screen.get(a);return null;});
        check("inbox".equals(where[0]),"Back from the main settings page didn't go to the inbox ("+where[0]+")");}
    /**
     * Send works while a load is underway (or stuck): the message goes into the outbox instead of
     * the tap doing nothing. A load stuck for over a minute stops counting as underway.
     */
    private void checkSendWhileLoading(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        java.lang.reflect.Method thread=method("thread",String.class),send=method("send"),isBusy=method("isBusy");java.lang.reflect.Field busy=field("busy"),since=field("busySince"),box=field("composer");
        onMainCall(()->{thread.invoke(a,"menu-check");busy.setBoolean(a,true);since.setLong(a,System.currentTimeMillis());((android.widget.EditText)box.get(a)).setText("Sent while loading");return send.invoke(a);});waitForIdleSync();
        Outbox out=new Outbox(ctx);JSONObject sent=null;for(JSONObject o:out.forChat("menu-check"))if("Sent while loading".equals(o.optString("text")))sent=o;
        if(sent!=null)out.remove(sent.optString("id"));
        check(sent!=null,"Tapping Send did nothing while a load was underway");
        boolean[] stale={true};onMainCall(()->{busy.setBoolean(a,true);since.setLong(a,System.currentTimeMillis()-61_000);stale[0]=(Boolean)isBusy.invoke(a);busy.setBoolean(a,false);return null;});
        check(!stale[0],"A load stuck for over a minute still counted as underway");}
    /**
     * The lock screen setting: while locked, notifications show no more than it allows (name
     * only, or nothing); unlocked, they show what's chosen for the chat. It's on the
     * Notifications settings page.
     */
    /**
     * A waiting message doesn't keep lighting a locked phone: with a stricter lock screen choice the
     * chat's notification is redrawn once as the phone locks (hiding what was said), and not again
     * each time the screen goes off (on a OnePlus, each redraw lit the screen, which then went off
     * and redrew it again, every few seconds). Unlocking redraws it in full.
     */
    private void checkLockedNoRedrawLoop()throws Exception{android.content.Context ctx=getTargetContext();
        if(field(PairedService.class,"current").get(null)==null){note("lock redraw check skipped: the service isn't running");return;}
        getUiAutomation().grantRuntimePermission(ctx.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        android.app.NotificationManager nm=ctx.getSystemService(android.app.NotificationManager.class);String before=NotificationPrivacy.mode(ctx);
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'lock-loop',name:'Lou Lock',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'ll',number:'+15557770166'},fullName:'Lou Lock'}]}"));
            store.message(new JSONObject().put("messageID","lock-loop-1").put("conversationID","lock-loop").put("participantID","ll").put("timestamp",System.currentTimeMillis()*1000)
                .put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","Are you up?")))),0,true);}
        nm.notify("chat:lock-loop",1,new android.app.Notification.Builder(ctx,"paired-messages").setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle("Lou Lock").setContentText("Are you up?").build());
        try{NotificationPrivacy.set(ctx,NotificationPrivacy.ALL);NotificationPrivacy.setLockMode(ctx,NotificationPrivacy.NAME);NotificationPrivacy.lockedForChecks=true;
            PairedService.refreshChatNotifications(ctx);Thread.sleep(1200);long locked=postTime(nm,"chat:lock-loop");
            check(locked>0,"The chat's notification wasn't redrawn as the phone locked");
            for(int i=0;i<3;i++){PairedService.refreshChatNotifications(ctx);Thread.sleep(700);}
            check(postTime(nm,"chat:lock-loop")==locked,"The chat's notification was redrawn again each time the locked screen went off (it lights the screen every few seconds)");
            NotificationPrivacy.lockedForChecks=false;PairedService.refreshChatNotifications(ctx);Thread.sleep(1200);
            check(postTime(nm,"chat:lock-loop")>locked,"Unlocking didn't redraw the chat's notification in full");}
        finally{NotificationPrivacy.lockedForChecks=null;NotificationPrivacy.setLockMode(ctx,NotificationPrivacy.SAME);NotificationPrivacy.set(ctx,before);nm.cancel("chat:lock-loop",1);
            try(PairedStore store=new PairedStore(ctx)){store.deleteChat("lock-loop");}}}
    private static long postTime(android.app.NotificationManager nm,String tag){for(android.service.notification.StatusBarNotification sbn:nm.getActiveNotifications())if(tag.equals(sbn.getTag()))return sbn.getPostTime();return -1;}
    private void checkLockScreenPreviews(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        String before=NotificationPrivacy.mode(ctx);NotificationPrivacy.set(ctx,NotificationPrivacy.ALL);
        try{NotificationPrivacy.setLockMode(ctx,NotificationPrivacy.NONE);NotificationPrivacy.lockedForChecks=true;
            check(!NotificationPrivacy.showName(ctx,"lock-check")&&!NotificationPrivacy.showText(ctx,"lock-check"),"A locked phone's notifications still showed who and what with the lock screen set to Nothing");
            NotificationPrivacy.setLockMode(ctx,NotificationPrivacy.NAME);
            check(NotificationPrivacy.showName(ctx,"lock-check")&&!NotificationPrivacy.showText(ctx,"lock-check"),"A locked phone's notifications didn't show just the name with the lock screen set to Name only");
            NotificationPrivacy.lockedForChecks=false;
            check(NotificationPrivacy.showName(ctx,"lock-check")&&NotificationPrivacy.showText(ctx,"lock-check"),"An unlocked phone's notifications were held back by the lock screen setting");
            onMainCall(()->method("openSettingsPage",String.class).invoke(a,"notifications"));waitForIdleSync();
            check(viewsWithDescription(a,"On the lock screen")>0,"The Notifications settings page has no lock screen setting");}
        finally{NotificationPrivacy.lockedForChecks=null;NotificationPrivacy.setLockMode(ctx,NotificationPrivacy.SAME);NotificationPrivacy.set(ctx,before);onMainCall(()->method("inbox").invoke(a));waitForIdleSync();}}
    /** Whether the window on top shows this text (a sheet or dialog included), waiting up to two seconds. */
    private boolean onTop(String text)throws Exception{for(int i=0;i<10;i++){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null&&!root.findAccessibilityNodeInfosByText(text).isEmpty())return true;Thread.sleep(200);}return false;}
    /** Presses and holds a point on the screen for a long press, then lifts. */
    private void hold(float x,float y)throws Exception{long t=android.os.SystemClock.uptimeMillis();
        try{sendPointerSync(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,x,y,0));}
        catch(IllegalArgumentException covered){if(dismissSystemDialogs()){Thread.sleep(800);hold(x,y);return;}StringBuilder on=new StringBuilder();for(android.view.accessibility.AccessibilityWindowInfo w:getUiAutomation().getWindows())on.append(w.getTitle()).append(" / ");
            note("something else's window was over the point held: "+on);shoot("shot-blocked");throw covered;}Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+400);
        long u=android.os.SystemClock.uptimeMillis();sendPointerSync(android.view.MotionEvent.obtain(t,u,android.view.MotionEvent.ACTION_UP,x,y,0));waitForIdleSync();}
    /**
     * A link inside a message with other words: holding the link offers it (Open, Copy, Share)
     * and doesn't open it; holding other words offers the message, with Copy link in it.
     */
    private void checkHoldLink(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'link-check',name:'Lena Check',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'lc',number:'+15557770177'},fullName:'Lena Check'}]}"));
            store.message(new JSONObject().put("messageID","link-msg").put("conversationID","link-check").put("participantID","lc").put("timestamp",System.currentTimeMillis()*1000)
                .put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","Look at https://example.com/page and tell me what you think")))),0,true);}
        onMainCall(()->method("thread",String.class).invoke(a,"link-check"));waitForIdleSync();Thread.sleep(400);
        float[] link={-1,-1},word={-1,-1};
        runOnMainSync(()->{for(android.widget.TextView t:viewsOf(a.getWindow().getDecorView(),android.widget.TextView.class)){String shown=t.getText().toString();int at=shown.indexOf("example.com/page");
            if(at<0||t.getLayout()==null)continue;int[] where=new int[2];t.getLocationOnScreen(where);android.text.Layout l=t.getLayout();
            int line=l.getLineForOffset(at+4);link[0]=where[0]+t.getTotalPaddingLeft()+l.getPrimaryHorizontal(at+4);link[1]=where[1]+t.getTotalPaddingTop()+(l.getLineTop(line)+l.getLineBottom(line))/2f;
            int w=shown.indexOf("Look");int wl=l.getLineForOffset(w+1);word[0]=where[0]+t.getTotalPaddingLeft()+l.getPrimaryHorizontal(w+1);word[1]=where[1]+t.getTotalPaddingTop()+(l.getLineTop(wl)+l.getLineBottom(wl))/2f;}});
        check(link[0]>=0,"The message with a link wasn't drawn");
        hold(link[0],link[1]);
        check(onTop("Copy link")&&onTop("Share link"),"Holding a link in a message didn't offer to copy it");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(400);
        hold(word[0],word[1]);
        check(onTop("Copy text")&&onTop("Copy link"),"Holding the words around a link didn't offer the message with Copy link");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(300);onMainCall(()->method("inbox").invoke(a));waitForIdleSync();}
    /** A message time on screen in the chat list (its left edge on the screen), or null when none is in view. */
    private Float timeOnScreen(PairedActivity a,android.widget.LinearLayout list){Float[] x={null};int id=a.getResources().getIdentifier("time_label","id",a.getPackageName());
        runOnMainSync(()->{android.graphics.Rect seen=new android.graphics.Rect();for(int i=0;i<list.getChildCount();i++){android.view.View row=list.getChildAt(i);Object label=row.getTag(id);
            if(label instanceof android.view.View&&row.getGlobalVisibleRect(seen)&&seen.height()>0){int[] at=new int[2];((android.view.View)label).getLocationOnScreen(at);x[0]=(float)at[0];return;}}});return x[0];}
    /**
     * Dragging the chat left to see times: the list slides as one and the times come in from the right;
     * an update redrawing the chat mid-drag keeps everything where the finger has it (it used to snap
     * back for a frame, a flash); letting go slides it all back with the times off the screen.
     */
    private void checkTimeSwipe(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'swipe-check',name:'Sam Check',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'p1',number:'+15557770188'},fullName:'Sam Check'}]}"));
            for(int i=0;i<16;i++)textMessage(store,"swipe-check",i);}
        onMainCall(()->method("thread",String.class).invoke(a,"swipe-check"));waitForIdleSync();Thread.sleep(600);
        android.widget.LinearLayout list=(android.widget.LinearLayout)field("items").get(a);float density=a.getResources().getDisplayMetrics().density,full=PairedActivity.TIME_REVEAL_DP*density;
        int[] size=new int[2];runOnMainSync(()->{android.view.View d=a.getWindow().getDecorView();size[0]=d.getWidth();size[1]=d.getHeight();});
        // The chat's right edge (the screen keeps a small margin): times wait past it, out of sight.
        int edge=placed(chatScroll(a)).right;
        Float resting=timeOnScreen(a,list);check(resting!=null&&resting>=edge,"A message time shows before dragging (at "+resting+", the chat ends at "+edge+")");
        float y=size[1]*0.45f,x0=size[0]*0.8f,x1=size[0]*0.35f;long t=android.os.SystemClock.uptimeMillis();
        sendPointerSync(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,x0,y,0));
        for(int i=1;i<=12;i++){Thread.sleep(16);float x=x0+(x1-x0)*i/12f;sendPointerSync(android.view.MotionEvent.obtain(t,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,x,y,0));
            if(i==3||i==12){waitForIdleSync();shoot("swipe-"+i);}}
        float held=list.getTranslationX();Float shown=timeOnScreen(a,list);
        check(held<=-full*0.99f,"The chat didn't slide over far enough to show the times ("+held+" of "+(-full)+")");
        check(shown!=null&&shown<edge-full*0.8f,"The times didn't come into view (at "+shown+")");
        // An update arriving while you hold the chat over redraws the messages: nothing may move.
        onMainCall(()->method("renderMessages",boolean.class).invoke(a,false));waitForIdleSync();shoot("swipe-redraw");
        check(list.getTranslationX()==held,"A redraw mid-drag moved the chat ("+held+" → "+list.getTranslationX()+"): it flashes");
        Float again=timeOnScreen(a,list);check(again!=null&&Math.abs(again-shown)<1,"A redraw mid-drag moved the times ("+shown+" → "+again+")");
        sendPointerSync(android.view.MotionEvent.obtain(t,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x1,y,0));
        Thread.sleep(500);waitForIdleSync();shoot("swipe-after");
        check(list.getTranslationX()==0,"The chat didn't slide back after letting go ("+list.getTranslationX()+")");
        Float gone=timeOnScreen(a,list);check(gone!=null&&gone>=edge,"A time stayed on screen after letting go");
        onMainCall(()->method("inbox").invoke(a));waitForIdleSync();
        // The inbox list isn't moved by a chat's slide (it settles after you've left).
        Object inboxList=field("items").get(a);check(inboxList==null||((android.view.View)inboxList).getTranslationX()==0,"Leaving a chat left the inbox slid over");}
    // ---- The emulator's own hiccups ----
    private volatile boolean watching;
    /**
     * A slow CI emulator sometimes puts up "Pixel Launcher isn't responding" over the app being tested,
     * which steals touches and focus. A watcher answers Wait whenever one shows.
     */
    private void watchForSystemDialogs(){android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(info);
        watching=true;Thread t=new Thread(()->{while(watching){try{Thread.sleep(600);dismissSystemDialogs();}catch(Throwable ignored){}}},"system-dialog-watch");t.setDaemon(true);t.start();}
    private boolean dismissSystemDialogs(){boolean any=false;
        for(android.view.accessibility.AccessibilityWindowInfo w:getUiAutomation().getWindows()){android.view.accessibility.AccessibilityNodeInfo r=w.getRoot();
            if(r==null||r.findAccessibilityNodeInfosByText("isn't responding").isEmpty())continue;
            for(android.view.accessibility.AccessibilityNodeInfo n:r.findAccessibilityNodeInfosByText("Wait")){if(n.getText()==null||!"Wait".contentEquals(n.getText()))continue;
                android.view.accessibility.AccessibilityNodeInfo c=n;while(c!=null&&!c.isClickable())c=c.getParent();if(c!=null){c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);any=true;}}}
        if(any)note("answered Wait on a system \"isn't responding\" dialog");return any;}
    // ---- Real touches, as a finger would make them (through the system, so status bar and back gestures apply) ----
    private final StringBuilder probe=new StringBuilder();
    private synchronized void note(String line){probe.append(line).append('\n');try{java.nio.file.Files.write(new java.io.File(getTargetContext().getCacheDir(),"probe.txt").toPath(),probe.toString().getBytes());}catch(Exception ignored){}}
    private void inject(long down,int action,float x,float y){android.view.MotionEvent e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);
        e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);getUiAutomation().injectInputEvent(e,true);e.recycle();}
    /** A finger tap at a point on the screen. */
    private void press(float x,float y)throws Exception{long t=android.os.SystemClock.uptimeMillis();inject(t,android.view.MotionEvent.ACTION_DOWN,x,y);Thread.sleep(60);
        inject(t,android.view.MotionEvent.ACTION_UP,x,y);waitForIdleSync();Thread.sleep(250);}
    /** A finger drag from one point to another, at about the speed of a careful drag. */
    private void slide(float x0,float y0,float x1,float y1)throws Exception{long t=android.os.SystemClock.uptimeMillis();inject(t,android.view.MotionEvent.ACTION_DOWN,x0,y0);Thread.sleep(80);
        for(int i=1;i<=20;i++){inject(t,android.view.MotionEvent.ACTION_MOVE,x0+(x1-x0)*i/20f,y0+(y1-y0)*i/20f);Thread.sleep(16);}
        Thread.sleep(80);inject(t,android.view.MotionEvent.ACTION_UP,x1,y1);waitForIdleSync();Thread.sleep(250);}
    /** The middle of a view on the screen. */
    private float[] middle(android.view.View v){float[] c=new float[2];runOnMainSync(()->{int[] at=new int[2];v.getLocationOnScreen(at);c[0]=at[0]+v.getWidth()/2f;c[1]=at[1]+v.getHeight()/2f;});return c;}
    /** The view in a window showing exactly this text, or null. */
    private android.widget.TextView labelled(android.view.View root,String text){android.widget.TextView[] hit=new android.widget.TextView[1];
        runOnMainSync(()->{for(android.widget.TextView t:viewsOf(root,android.widget.TextView.class))if(t.isShown()&&text.contentEquals(t.getText())){hit[0]=t;break;}});return hit[0];}
    /** Whether some window on screen shows this text right now. */
    private boolean showing(String text){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        for(android.view.accessibility.AccessibilityWindowInfo w:getUiAutomation().getWindows()){android.view.accessibility.AccessibilityNodeInfo r=w.getRoot();if(r!=null&&!r.findAccessibilityNodeInfosByText(text).isEmpty())return true;}
        return root!=null&&!root.findAccessibilityNodeInfosByText(text).isEmpty();}
    /** The crop frame's corners on the screen: left, top, right, bottom. */
    private float[] cropOnScreen(MarkupEditor editor)throws Exception{android.view.View board=(android.view.View)field(MarkupEditor.class,"board").get(editor);
        android.graphics.RectF crop=new android.graphics.RectF((android.graphics.RectF)field(MarkupEditor.class,"crop").get(editor));float[] out=new float[4];
        java.lang.reflect.Field toScreen=board.getClass().getDeclaredField("toScreen");toScreen.setAccessible(true);
        runOnMainSync(()->{try{android.graphics.Matrix m=(android.graphics.Matrix)toScreen.get(board);float[] pts={crop.left,crop.top,crop.right,crop.bottom};m.mapPoints(pts);int[] at=new int[2];board.getLocationOnScreen(at);
            out[0]=pts[0]+at[0];out[1]=pts[1]+at[1];out[2]=pts[2]+at[0];out[3]=pts[3]+at[1];}catch(Exception e){throw new RuntimeException(e);}});return out;}
    private static java.lang.reflect.Field field(Class<?> c,String name)throws Exception{java.lang.reflect.Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    /** A plain photo file of one color. */
    private java.io.File photo(java.io.File dir,String name,int w,int h,int color)throws Exception{dir.mkdirs();java.io.File f=new java.io.File(dir,name);android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);b.eraseColor(color);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(f)){b.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);}return f;}
    private boolean waitUntil(java.util.concurrent.Callable<Boolean> done,long ms)throws Exception{long until=android.os.SystemClock.uptimeMillis()+ms;
        while(android.os.SystemClock.uptimeMillis()<until){boolean[] yes={false};runOnMainSync(()->{try{yes[0]=done.call();}catch(Exception e){throw new RuntimeException(e);}});if(yes[0])return true;Thread.sleep(100);}return false;}
    /** The first view in the activity whose accessibility label contains this text. */
    private android.view.View described(PairedActivity a,String label){android.view.View[] hit=new android.view.View[1];runOnMainSync(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found,label,android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);for(android.view.View v:found)if(v.isShown()){hit[0]=v;break;}});return hit[0];}
    /** Something on screen in any window showing exactly this text (a dialog's button), or null. */
    private android.view.accessibility.AccessibilityNodeInfo onScreen(String text){java.util.List<android.view.accessibility.AccessibilityNodeInfo> roots=new java.util.ArrayList<>();
        android.view.accessibility.AccessibilityNodeInfo active=getUiAutomation().getRootInActiveWindow();if(active!=null)roots.add(active);
        for(android.view.accessibility.AccessibilityWindowInfo w:getUiAutomation().getWindows()){android.view.accessibility.AccessibilityNodeInfo r=w.getRoot();if(r!=null)roots.add(r);}
        for(android.view.accessibility.AccessibilityNodeInfo r:roots)for(android.view.accessibility.AccessibilityNodeInfo n:r.findAccessibilityNodeInfosByText(text))if(n.getText()!=null&&text.contentEquals(n.getText())&&n.isVisibleToUser())return n;return null;}
    /** Waits (off the main thread, which answers these questions) for text to be on screen, or to be gone. */
    private boolean waitForText(String text,long ms,boolean there)throws Exception{long until=android.os.SystemClock.uptimeMillis()+ms;
        do{if((onScreen(text)!=null)==there)return true;Thread.sleep(150);}while(android.os.SystemClock.uptimeMillis()<until);return false;}
    private static Object got(Object o,String name)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    /** Taps a dialog button (or any text on screen) with a finger. */
    private void pressText(String text)throws Exception{android.view.accessibility.AccessibilityNodeInfo n=null;for(int i=0;i<20&&n==null;i++){n=onScreen(text);if(n==null)Thread.sleep(150);}
        check(n!=null,"\""+text+"\" isn't on the screen to tap");android.graphics.Rect b=new android.graphics.Rect();n.getBoundsInScreen(b);press(b.exactCenterX(),b.exactCenterY());}
    private void doubleTap(float x,float y)throws Exception{long t=android.os.SystemClock.uptimeMillis();inject(t,android.view.MotionEvent.ACTION_DOWN,x,y);Thread.sleep(40);inject(t,android.view.MotionEvent.ACTION_UP,x,y);Thread.sleep(90);
        long u=android.os.SystemClock.uptimeMillis();inject(u,android.view.MotionEvent.ACTION_DOWN,x,y);Thread.sleep(40);inject(u,android.view.MotionEvent.ACTION_UP,x,y);waitForIdleSync();Thread.sleep(500);}
    private android.view.View editorWindow(MarkupEditor e)throws Exception{return ((android.app.Dialog)field(MarkupEditor.class,"dialog").get(e)).getWindow().getDecorView();}
    private android.graphics.RectF cropOf(MarkupEditor e)throws Exception{android.graphics.RectF[] r=new android.graphics.RectF[1];runOnMainSync(()->{try{r[0]=new android.graphics.RectF((android.graphics.RectF)field(MarkupEditor.class,"crop").get(e));}catch(Exception x){throw new RuntimeException(x);}});return r[0];}
    /** A view's place on the screen. */
    private android.graphics.Rect placed(android.view.View v){android.graphics.Rect r=new android.graphics.Rect();runOnMainSync(()->{int[] at=new int[2];v.getLocationOnScreen(at);r.set(at[0],at[1],at[0]+v.getWidth(),at[1]+v.getHeight());});return r;}
    /** Opens the editor by tapping a picked photo in the strip, the way a finger does (left of middle, away from its ✕). */
    private MarkupEditor openEditor(PairedActivity a,String name)throws Exception{android.view.View tile=described(a,"Mark up "+name);check(tile!=null,"The picked photo "+name+" isn't in the strip");
        android.graphics.Rect t=placed(tile);press(t.left+t.width()*0.35f,t.top+t.height()*0.65f);
        check(waitUntil(()->MarkupEditor.shown!=null,5000),"Tapping a picked photo didn't open the editor");waitForIdleSync();Thread.sleep(400);return MarkupEditor.shown;}
    /** Taps a button in the editor with a finger, after checking it's clear of the status bar, camera cutout and navigation bar. */
    private void editorButton(MarkupEditor e,String label)throws Exception{android.view.View w=editorWindow(e);android.widget.TextView b=labelled(w,label);check(b!=null,"The editor has no "+label+" button");
        android.graphics.Rect r=placed(b);android.graphics.Insets[] in=new android.graphics.Insets[1];int[] h=new int[1];
        runOnMainSync(()->{android.view.WindowInsets i=w.getRootWindowInsets();in[0]=i==null?android.graphics.Insets.NONE:i.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()|android.view.WindowInsets.Type.navigationBars()|android.view.WindowInsets.Type.displayCutout());h[0]=w.getHeight();});
        check(r.top>=in[0].top&&r.bottom<=h[0]-in[0].bottom,"The editor's "+label+" button is under a system bar ("+r+", bars "+in[0]+")");
        press(r.exactCenterX(),r.exactCenterY());}
    @SuppressWarnings("unchecked")
    private java.util.List<Object> picked(PairedActivity a)throws Exception{return (java.util.List<Object>)field("outgoing").get(a);}
    private java.io.File pickedFile(Object o)throws Exception{java.lang.reflect.Field f=o.getClass().getDeclaredField("file");f.setAccessible(true);return (java.io.File)f.get(o);}
    /**
     * Photos, start to finish, with a finger: picking two,
     * the editor's buttons clear of the system bars, cropping by every corner and side (starting right at
     * the screen's edge too), Undo, Cancel asking before throwing edits away (Keep editing / Discard),
     * Back doing the same, Done saving the crop, drawing and text, removing one, photo quality, sending,
     * and the full-screen viewer (zoom, next photo, hiding the bar, Save, Share, Close).
     */
    private void checkPhotoMechanics(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();float dp=a.getResources().getDisplayMetrics().density;
        android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(info);
        java.io.File[] made=new java.io.File[2];
        try{
            onMainCall(()->method("thread",String.class).invoke(a,"swipe-check"));waitForIdleSync();Thread.sleep(500);
            // Picking: two photos come back from Android's photo picker.
            note("photos: picking");
            made[0]=photo(MediaCache.dir(ctx),"pick-wide.jpg",2000,1500,0xff3366aa);made[1]=photo(MediaCache.dir(ctx),"pick-tall.jpg",1200,1800,0xffaa6633);
            android.content.ClipData clip=android.content.ClipData.newRawUri("photos",MediaProvider.uri(made[0],"Wide.jpg"));clip.addItem(new android.content.ClipData.Item(MediaProvider.uri(made[1],"Tall.jpg")));
            Intent result=new Intent();result.setClipData(clip);
            onMainCall(()->{java.lang.reflect.Method m=PairedActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);m.setAccessible(true);return m.invoke(a,41,Activity.RESULT_OK,result);});
            check(waitUntil(()->picked(a).size()==2,5000),"Two picked photos didn't both join the message ("+picked(a).size()+")");waitForIdleSync();Thread.sleep(400);shoot("shot-photo-strip");
            // The editor: every button clear of the system bars.
            note("photos: editor");
            MarkupEditor e=openEditor(a,"Wide.jpg");shoot("shot-photo-editor");
            editorButton(e,"Crop");check("Crop".equals(field(MarkupEditor.class,"mode").get(e)),"Tapping Crop didn't switch to cropping");
            android.graphics.RectF whole=cropOf(e);float[] r=cropOnScreen(e);int width=editorWindow(e).getWidth();
            check(r[0]>=20*dp&&r[2]<=width-20*dp,"The crop corners sit at the screen's edge, where a drag is taken as Back ("+java.util.Arrays.toString(r)+" of "+width+")");
            // With gesture navigation, a drag starting near the screen's side is Back unless the app keeps that spot:
            // the corners and side bars are kept (the emulator's injected touches skip that check, so it's checked here).
            android.view.View frame=(android.view.View)field(MarkupEditor.class,"board").get(e);java.util.List<android.graphics.Rect> keptSpots=new java.util.ArrayList<>();runOnMainSync(()->keptSpots.addAll(frame.getSystemGestureExclusionRects()));
            android.graphics.Rect fb=placed(frame);boolean cornerKept=false;for(android.graphics.Rect k:keptSpots)if(k.contains((int)(r[0]-fb.left),(int)(r[1]-fb.top))&&k.contains((int)(6*dp-fb.left),(int)(r[1]-fb.top)))cornerKept=true;
            check(keptSpots.size()>=6&&cornerKept,"The crop corners aren't kept from the Back swipe ("+keptSpots+")");
            // Every corner, with a finger starting on it; the top-left one from right at the screen's edge.
            note("photos: corners");
            slide(6*dp,r[1]+6*dp,r[0]+50*dp,r[1]+36*dp);android.graphics.RectF c=cropOf(e);
            check(MarkupEditor.shown==e,"Pulling the top-left corner from the screen's edge closed the editor (taken as Back)");
            check(c.left>whole.left+1&&c.top>whole.top+1,"The top-left corner didn't move ("+c+")");
            r=cropOnScreen(e);slide(r[2],r[1],r[2]-50*dp,r[1]+30*dp);android.graphics.RectF d=cropOf(e);check(MarkupEditor.shown==e&&d.right<c.right-1&&d.top>c.top+1,"The top-right corner didn't move ("+d+")");
            r=cropOnScreen(e);slide(r[2]-2*dp,r[3],width-4*dp,r[3]-30*dp);android.graphics.RectF g=cropOf(e);check(MarkupEditor.shown==e&&g.right>d.right+1&&g.bottom<d.bottom-1,"The bottom-right corner didn't move out toward the screen's edge ("+g+")");
            r=cropOnScreen(e);slide(r[0],r[3],r[0]+40*dp,r[3]-20*dp);android.graphics.RectF h=cropOf(e);check(MarkupEditor.shown==e&&h.left>g.left+1&&h.bottom<g.bottom-1,"The bottom-left corner didn't move ("+h+")");
            // A side moves only that side; the middle moves the whole frame.
            note("photos: side and middle");
            r=cropOnScreen(e);slide(r[2],(r[1]+r[3])/2,r[2]-30*dp,(r[1]+r[3])/2);android.graphics.RectF k=cropOf(e);
            check(k.right<h.right-1&&Math.abs(k.top-h.top)<1&&Math.abs(k.bottom-h.bottom)<1&&Math.abs(k.left-h.left)<1,"Dragging the right side didn't move just that side ("+h+" → "+k+")");
            r=cropOnScreen(e);slide((r[0]+r[2])/2,(r[1]+r[3])/2,(r[0]+r[2])/2+24*dp,(r[1]+r[3])/2);android.graphics.RectF m=cropOf(e);
            check(m.left>k.left+1&&Math.abs(m.width()-k.width())<1&&Math.abs(m.height()-k.height())<1,"Dragging the middle didn't move the frame ("+k+" → "+m+")");
            shoot("shot-photo-crop");
            editorButton(e,"Undo");check(cropOf(e).equals(k),"Undo didn't take back the last move ("+cropOf(e)+", expected "+k+")");
            // Cancel asks before throwing away edits: Keep editing stays, Discard closes and keeps the photo as picked.
            note("photos: cancel");
            java.io.File before=pickedFile(picked(a).get(0));
            editorButton(e,"Cancel");check(waitForText("Discard your changes?",3000,true),"Cancel didn't do anything (no question about discarding)");shoot("shot-photo-discard");
            pressText("Keep editing");check(waitForText("Discard your changes?",3000,false)&&MarkupEditor.shown==e,"Keep editing didn't go back to the editor");
            editorButton(e,"Cancel");pressText("Discard");check(waitUntil(()->MarkupEditor.shown==null,3000),"Discard didn't close the editor");
            check(picked(a).size()==2&&pickedFile(picked(a).get(0)).equals(before)&&before.isFile(),"Discarding changed the picked photo");
            // Done keeps the crop: the photo in the strip is replaced by the cropped copy.
            note("photos: done");
            e=openEditor(a,"Wide.jpg");editorButton(e,"Crop");r=cropOnScreen(e);slide(r[0],r[1],r[0]+60*dp,r[1]+40*dp);android.graphics.RectF kept=cropOf(e);
            editorButton(e,"Done");check(waitUntil(()->MarkupEditor.shown==null,5000),"Done didn't close the editor");
            check(waitUntil(()->!pickedFile(picked(a).get(0)).equals(before),3000),"Done didn't replace the picked photo with the cropped one");
            android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(pickedFile(picked(a).get(0)).getPath(),o);
            check(Math.abs(o.outWidth-Math.round(kept.width()))<=1&&Math.abs(o.outHeight-Math.round(kept.height()))<=1,"The saved photo isn't the cropped size ("+o.outWidth+"×"+o.outHeight+" for "+kept+")");
            // Drawing, then words: Text asks for them; Back asks before throwing both away.
            note("photos: draw and text");
            e=openEditor(a,"Wide.jpg");android.view.View board=(android.view.View)field(MarkupEditor.class,"board").get(e);android.graphics.Rect bb=placed(board);
            slide(bb.exactCenterX()-60*dp,bb.exactCenterY(),bb.exactCenterX()+60*dp,bb.exactCenterY()+20*dp);
            check(((java.util.List<?>)field(MarkupEditor.class,"undo").get(e)).size()==1,"Drawing a line wasn't recorded");
            editorButton(e,"Text");check(waitForText("Add text",3000,true),"Text didn't ask for words");
            android.view.accessibility.AccessibilityNodeInfo box=getUiAutomation().getRootInActiveWindow().findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);check(box!=null,"The words box isn't ready to type in");
            Bundle words=new Bundle();words.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Hello");box.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,words);
            pressText("Add");int labels=0;for(Object mark:(java.util.List<?>)field(MarkupEditor.class,"marks").get(e))if(mark.getClass().getSimpleName().equals("Label"))labels++;check(labels==1,"The words weren't put on the photo");
            shoot("shot-photo-text");
            waitForText("Add text",3000,false);getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            // The first Back may only put the keyboard away.
            if(!waitForText("Discard your changes?",1500,true))getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            check(waitForText("Discard your changes?",3000,true),"Back didn't ask before throwing away the drawing");pressText("Discard");check(waitUntil(()->MarkupEditor.shown==null,3000),"Discard after Back didn't close the editor");
            // No edits: Cancel just closes.
            note("photos: cancel without edits");
            e=openEditor(a,"Tall.jpg");editorButton(e,"Cancel");check(waitUntil(()->MarkupEditor.shown==null,2000)&&waitForText("Discard your changes?",500,false),"Cancel with no edits didn't simply close");
            // Removing one: its ✕, with a finger.
            note("photos: remove");
            android.view.View remove=described(a,"Remove Tall.jpg");check(remove!=null,"The tall photo has no remove button");android.graphics.Rect rr=placed(remove);
            check(rr.width()>=30*dp,"The remove button is too small for a finger ("+rr.width()/dp+"dp)");press(rr.exactCenterX(),rr.exactCenterY());
            check(waitUntil(()->picked(a).size()==1,2000),"Tapping ✕ didn't remove the photo");
            // Photo quality: tapping it offers the choices.
            note("photos: quality");
            android.view.View quality=described(a,"Photo quality");check(quality!=null,"No photo quality setting by the picked photo");android.graphics.Rect qr=placed(quality);press(qr.exactCenterX(),qr.exactCenterY());
            check(waitForText("Standard • sharp, practical size",3000,true),"Tapping photo quality didn't offer the choices");getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            check(waitForText("Standard • sharp, practical size",3000,false),"Back didn't close the photo quality choices");
            // Sending: the photo leaves the strip and is queued for this chat.
            note("photos: send");
            android.view.View send=(android.view.View)field("sendButton").get(a);check(send!=null&&send.isShown()&&"Send".contentEquals(send.getContentDescription()),"No Send button with a photo picked");android.graphics.Rect sr=placed(send);press(sr.exactCenterX(),sr.exactCenterY());
            MediaOutbox queue=new MediaOutbox(ctx);check(waitUntil(()->picked(a).isEmpty()&&!queue.forChat("swipe-check").isEmpty(),8000),"Sending didn't take the photo from the strip into the chat ("+picked(a).size()+" left, "+queue.forChat("swipe-check").size()+" queued)");
            Thread.sleep(500);shoot("shot-photo-sent");
            for(JSONObject job:queue.forChat("swipe-check"))queue.remove(job.optString("id"));
            checkPhotoViewer(a,dp);
        }finally{
            if(MarkupEditor.shown!=null){android.app.Dialog dl=(android.app.Dialog)field(MarkupEditor.class,"dialog").get(MarkupEditor.shown);runOnMainSync(dl::dismiss);}
            for(java.io.File f:made)if(f!=null)f.delete();
            onMainCall(()->method("inbox").invoke(a));waitForIdleSync();}}
    /** The full-screen viewer, opened from a photo in a chat with a finger: bar clear of the status bar, zoom, next photo, Save, Share, Close. */
    private void checkPhotoViewer(PairedActivity a,float dp)throws Exception{android.content.Context ctx=getTargetContext();long now=System.currentTimeMillis();java.util.List<java.io.File> files=new java.util.ArrayList<>();
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'photo-check',name:'Pat Photo',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'pp',number:'+15550106666'},fullName:'Pat Photo'}]}"));
            for(int i=0;i<2;i++){JSONObject media=new JSONObject().put("mediaID","viewer-media-"+i).put("mimeType","image/jpeg").put("mediaName","viewer-"+i+".jpg").put("decryptionKey","AAAA").put("dimensions",new JSONObject().put("width",1600).put("height",1200));
                java.io.File full=MediaCache.file(ctx,media,false);full.getParentFile().mkdirs();java.io.File made=photo(full.getParentFile(),full.getName(),1600,1200,i==0?0xff229955:0xff992255);files.add(made);media.put("size",made.length());
                store.message(new JSONObject().put("messageID","viewer-msg-"+i).put("conversationID","photo-check").put("participantID","pp").put("timestamp",(now-60_000+i*1000)*1000).put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE"))
                    .put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("actionMessageID","viewer-part-"+i).put("mediaContent",media))),0,true);}}
        try{note("photos: viewer");onMainCall(()->method("thread",String.class).invoke(a,"photo-check"));waitForIdleSync();Thread.sleep(800);
            java.util.ArrayList<android.view.View> photos=new java.util.ArrayList<>();runOnMainSync(()->a.getWindow().getDecorView().findViewsWithText(photos,"Photo. Tap to view.",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION));
            check(photos.size()==2,"The chat doesn't show both photos ("+photos.size()+")");android.graphics.Rect pr=placed(photos.get(1));press(pr.exactCenterX(),pr.exactCenterY());
            check(waitUntil(()->field("shownViewer").get(a)!=null,3000),"Tapping a photo didn't open it full screen");Object v=field("shownViewer").get(a);
            android.app.Dialog dialog=(android.app.Dialog)got(v,"d");android.view.View window=dialog.getWindow().getDecorView();android.view.View image=(android.view.View)got(v,"image");
            android.widget.TextView counter=(android.widget.TextView)got(v,"counter");
            check(waitUntil(()->((android.widget.ImageView)image).getDrawable()!=null,4000),"The full-screen photo didn't show");Thread.sleep(300);shoot("shot-photo-viewer");
            android.widget.TextView save=labelled(window,"Save"),share=labelled(window,"Share");check(save!=null&&share!=null,"The viewer has no Save or Share");
            int[] bar=new int[1];runOnMainSync(()->{android.view.WindowInsets in=window.getRootWindowInsets();bar[0]=in==null?0:in.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()|android.view.WindowInsets.Type.displayCutout()).top;});
            check(placed(save).top>=bar[0],"The viewer's buttons are under the status bar ("+placed(save)+", bar "+bar[0]+")");
            check("2 of 2".contentEquals(counter.getText()),"The viewer didn't open on the tapped photo ("+counter.getText()+")");
            android.graphics.Rect ir=placed(image);
            doubleTap(ir.exactCenterX(),ir.exactCenterY());check((float)got(image,"scale")>1.5f,"Double-tap didn't zoom in ("+got(image,"scale")+")");
            doubleTap(ir.exactCenterX(),ir.exactCenterY());check((float)got(image,"scale")<1.05f,"Double-tap again didn't zoom back out ("+got(image,"scale")+")");
            slide(ir.left+ir.width()*0.2f,ir.exactCenterY(),ir.left+ir.width()*0.85f,ir.exactCenterY());
            if(!"1 of 2".contentEquals(counter.getText()))slide(ir.left+ir.width()*0.85f,ir.exactCenterY(),ir.left+ir.width()*0.2f,ir.exactCenterY());
            check("1 of 2".contentEquals(counter.getText()),"Swiping sideways didn't go to the other photo ("+counter.getText()+")");
            // (A single tap counts once Android is sure it isn't a double-tap; the emulator can be slow to say so.)
            Thread.sleep(400);press(ir.exactCenterX(),ir.exactCenterY());check(waitUntil(()->!save.isShown(),2500),"A tap didn't hide the viewer's bar");
            Thread.sleep(400);press(ir.exactCenterX(),ir.exactCenterY());check(waitUntil(save::isShown,2500),"A second tap didn't bring the bar back");
            check(waitUntil(()->((android.widget.ImageView)image).getDrawable()!=null,4000),"The other photo didn't show");
            android.graphics.Rect sv=placed(save);press(sv.exactCenterX(),sv.exactCenterY());
            android.net.Uri images=android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);long savedId=-1;
            for(int i=0;i<30&&savedId<0;i++){Thread.sleep(200);try(android.database.Cursor q=ctx.getContentResolver().query(images,new String[]{android.provider.MediaStore.MediaColumns._ID},android.provider.MediaStore.MediaColumns.DISPLAY_NAME+" LIKE ?",new String[]{"viewer-0%"},null)){if(q!=null&&q.moveToFirst())savedId=q.getLong(0);}}
            check(savedId>=0,"Save didn't put the photo in Pictures");note("viewer save: ok");ctx.getContentResolver().delete(android.content.ContentUris.withAppendedId(images,savedId),null,null);
            android.graphics.Rect sh=placed(share);press(sh.exactCenterX(),sh.exactCenterY());check(waitUntil(()->!window.hasWindowFocus(),4000),"Share didn't open the share sheet");
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);check(waitUntil(window::hasWindowFocus,4000),"Closing the share sheet didn't return to the photo");
            android.view.View close=null;for(android.view.View x:viewsOf(window,android.view.View.class))if(x.getContentDescription()!=null&&"Close".contentEquals(x.getContentDescription())){close=x;break;}
            check(close!=null,"The viewer has no Close button");android.graphics.Rect cr=placed(close);press(cr.exactCenterX(),cr.exactCenterY());
            check(waitUntil(()->!dialog.isShowing(),3000),"Close didn't close the photo");}
        finally{Object v=field("shownViewer").get(a);if(v!=null){android.app.Dialog dl=(android.app.Dialog)got(v,"d");runOnMainSync(dl::dismiss);}
            for(java.io.File f:files)f.delete();try(PairedStore store=new PairedStore(ctx)){store.deleteChat("photo-check");}}}
    /** Something on screen whose accessibility label is exactly this, or null. */
    private android.view.accessibility.AccessibilityNodeInfo describedOnScreen(String label){android.view.accessibility.AccessibilityNodeInfo r=getUiAutomation().getRootInActiveWindow();if(r==null)return null;
        for(android.view.accessibility.AccessibilityNodeInfo n:r.findAccessibilityNodeInfosByText(label))if(n.getContentDescription()!=null&&label.contentEquals(n.getContentDescription()))return n;return null;}
    /** The color your bubble is drawn in, found from one of your messages' words. */
    private int bubbleColorOf(PairedActivity a,String words){int[] color={0};runOnMainSync(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found,words,android.view.View.FIND_VIEWS_WITH_TEXT);if(found.isEmpty())return;android.view.View v=found.get(0);
        for(int up=0;up<5&&v!=null;up++){if(v.getBackground() instanceof android.graphics.drawable.GradientDrawable&&((android.graphics.drawable.GradientDrawable)v.getBackground()).getColor()!=null){color[0]=((android.graphics.drawable.GradientDrawable)v.getBackground()).getColor().getDefaultColor();return;}
            v=v.getParent() instanceof android.view.View?(android.view.View)v.getParent():null;}});return color[0];}
    /**
     * A custom chat theme: Theme offers Custom…, its sliders change the color, a typed code sets it exactly,
     * Use color makes your bubbles exactly that color, and the color is offered again under Recent.
     */
    private void checkCustomTheme(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();ChatSettings settings=new ChatSettings(ctx);
        try{onMainCall(()->method("thread",String.class).invoke(a,"swipe-check"));waitForIdleSync();Thread.sleep(400);
            onMainCall(()->method("chooseTheme",String.class).invoke(a,"swipe-check"));check(waitForText("Custom…",3000,true),"The chat theme choices have no Custom option");
            pressText("Custom…");check(waitForText("Custom color",3000,true),"Custom… didn't open the color picker");Thread.sleep(300);shoot("shot-custom-theme");
            android.view.accessibility.AccessibilityNodeInfo code=describedOnScreen("Color code");check(code!=null,"The color picker has no color code box");String before=String.valueOf(code.getText());
            // Dragging the color slider changes the color.
            android.view.accessibility.AccessibilityNodeInfo hue=describedOnScreen("Color");check(hue!=null,"The color picker has no color slider");android.graphics.Rect hb=new android.graphics.Rect();hue.getBoundsInScreen(hb);
            slide(hb.left+hb.width()*0.15f,hb.exactCenterY(),hb.left+hb.width()*0.7f,hb.exactCenterY());code=describedOnScreen("Color code");
            check(code!=null&&!before.contentEquals(code.getText()),"Dragging the color slider didn't change the color ("+before+" → "+(code==null?null:code.getText())+")");
            // A typed code sets it exactly.
            Bundle typed=new Bundle();typed.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"#E91E63");code.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,typed);Thread.sleep(300);
            shoot("shot-custom-theme-pink");pressText("Use color");check(waitForText("Custom color",3000,false),"Use color didn't close the picker");waitForIdleSync();Thread.sleep(400);
            ChatTheme chosen=settings.theme("swipe-check");boolean dark=(boolean)field("dark").get(a);
            check(chosen.key.startsWith("custom:e91e63"),"The chat didn't get the custom color ("+chosen.key+")");
            int drawn=bubbleColorOf(a,"Line 0 of a long conversation");check(drawn==chosen.bubble(dark)&&(dark||drawn==0xffe91e63),"Your bubbles aren't drawn in the custom color ("+Integer.toHexString(drawn)+")");
            shoot("shot-custom-theme-chat");
            // Offered again under Recent, and marked as this chat's theme.
            onMainCall(()->method("chooseTheme",String.class).invoke(a,"swipe-check"));check(waitForText("Custom…",3000,true),"The theme choices didn't open again");pressText("Custom…");
            check(waitForText("Custom color",3000,true)&&describedOnScreen("Recent color #E91E63")!=null,"The custom color isn't offered under Recent");
            pressText("Cancel");check(waitForText("Custom color",3000,false),"Cancel didn't close the color picker");
            check(settings.theme("swipe-check").key.startsWith("custom:e91e63"),"Cancel changed the chat's theme");}
        finally{settings.setTheme("swipe-check","default");ctx.getSharedPreferences("paired",0).edit().remove("custom_colors").commit();
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);Thread.sleep(300);onMainCall(()->method("inbox").invoke(a));waitForIdleSync();}}
    /** The number beside Messages, or -1 when it isn't showing. */
    private int unreadShown(PairedActivity a)throws Exception{onMainCall(()->method("updateUnreadBadge").invoke(a));android.widget.TextView badge=(android.widget.TextView)field("unreadBadge").get(a);
        int[] n={-1};runOnMainSync(()->{if(badge!=null&&badge.getVisibility()==android.view.View.VISIBLE&&badge.getContentDescription()!=null)n[0]=Integer.parseInt(badge.getContentDescription().toString().replaceAll("\\D.*$",""));});return n[0];}
    /**
     * The unread count beside Messages: three new messages after your last reply in an unread chat add
     * three; reading the chat takes them off; the setting turns the count off.
     */
    private void checkUnreadCount(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();long now=System.currentTimeMillis();
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("paired",0);prefs.edit().putBoolean("inbox_unread_count",true).commit();
        try{try(PairedStore store=new PairedStore(ctx)){
                store.chat(new JSONObject("{conversationID:'unread-check',name:'Una Read',type:'RCS',unread:true,participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'ur',number:'+15557770111'},fullName:'Una Read'}]}").put("lastMessageTimestamp",now*1000));
                store.message(new JSONObject().put("messageID","ur-mine").put("conversationID","unread-check").put("participantID","me").put("timestamp",(now-60_000)*1000).put("messageStatus",new JSONObject().put("status","OUTGOING_COMPLETE"))
                    .put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","See you there")))),0,true);
                for(int i=0;i<3;i++)store.message(new JSONObject().put("messageID","ur-"+i).put("conversationID","unread-check").put("participantID","ur").put("timestamp",(now-30_000+i*1000)*1000).put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE"))
                    .put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","On my way "+i)))),0,true);}
            onMainCall(()->method("inbox").invoke(a));waitForIdleSync();
            int with=unreadShown(a);check(with>=3,"The unread count beside Messages doesn't include the three new messages ("+with+")");shoot("shot-unread-count");
            new ChatSettings(ctx).markSeen("unread-check",now+1000);int without=unreadShown(a);
            check(without==(with==3?-1:with-3),"Reading the chat didn't take its three messages off the count ("+with+" → "+without+")");
            new ChatSettings(ctx).setMarkedUnread("unread-check",true);check(unreadShown(a)==(with==3?1:with-2),"A chat marked unread doesn't count as one");
            prefs.edit().putBoolean("inbox_unread_count",false).commit();check(unreadShown(a)==-1,"Turning the unread count off didn't hide it");}
        finally{prefs.edit().putBoolean("inbox_unread_count",true).commit();new ChatSettings(ctx).setMarkedUnread("unread-check",false);try(PairedStore store=new PairedStore(ctx)){store.deleteChat("unread-check");}}}
    private boolean shownInChat(PairedActivity a,String words)throws Exception{boolean[] hit={false};runOnMainSync(()->{java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found,words,android.view.View.FIND_VIEWS_WITH_TEXT);for(android.view.View v:found)if(v.isShown())hit[0]=true;});return hit[0];}
    private boolean waitForChatText(PairedActivity a,String words,long ms,boolean there)throws Exception{long until=android.os.SystemClock.uptimeMillis()+ms;
        do{if(shownInChat(a,words)==there)return true;Thread.sleep(200);}while(android.os.SystemClock.uptimeMillis()<until);return false;}
    /**
     * Calls in chats: with the call log allowed, calls with the person show among their messages
     * ("Outgoing call · 4 min 12 sec", "Missed call"), a call that ends while the chat's open shows up
     * by itself, and turning the setting off hides them.
     */
    private void checkCallLines(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();long now=System.currentTimeMillis();
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("paired",0);prefs.edit().putBoolean("show_calls",true).commit();
        getUiAutomation().grantRuntimePermission(ctx.getPackageName(),android.Manifest.permission.READ_CALL_LOG);
        String out=shell("content insert --uri content://call_log/calls --bind number:s:+15557770188 --bind date:l:"+(now-60_000)+" --bind duration:l:252 --bind type:i:2 --bind new:i:0");
        shell("content insert --uri content://call_log/calls --bind number:s:5557770188 --bind date:l:"+(now-120_000)+" --bind duration:l:0 --bind type:i:3 --bind new:i:0");
        note("call log insert: "+out.trim());
        try{onMainCall(()->method("thread",String.class).invoke(a,"swipe-check"));onMainCall(()->method("watchCalls",boolean.class).invoke(a,true));
            check(waitForChatText(a,"Outgoing call · 4 min 12 sec",5000,true),"An outgoing call with them doesn't show in the chat");
            check(shownInChat(a,"Missed call"),"A missed call from them doesn't show in the chat");shoot("shot-call-lines");
            shell("content insert --uri content://call_log/calls --bind number:s:+15557770188 --bind date:l:"+(System.currentTimeMillis()-5_000)+" --bind duration:l:61 --bind type:i:1 --bind new:i:0");
            check(waitForChatText(a,"Incoming call · 1 min 1 sec",6000,true),"A call that ended with the chat open didn't show up by itself");
            prefs.edit().putBoolean("show_calls",false).commit();onMainCall(()->method("renderMessages",boolean.class).invoke(a,false));
            check(waitForChatText(a,"Outgoing call",2000,false),"Turning calls off didn't hide them");}
        finally{prefs.edit().putBoolean("show_calls",true).commit();shell("content delete --uri content://call_log/calls --where number='+15557770188'");shell("content delete --uri content://call_log/calls --where number='5557770188'");
            onMainCall(()->method("inbox").invoke(a));waitForIdleSync();}}
    /**
     * One color for all chats (Settings → Appearance → Chat color): chats without their own follow it;
     * a chat with its own is asked about (Keep theirs / Change all); any chat can still get its own
     * after, and go back with "Same as all chats".
     */
    /**
     * A song from another service opens in yours: under an Apple Music link's preview, "Open in…"
     * asks which music app the first time, then opens the same song there, and says how close the
     * match is ("Same song"). The match is saved beforehand, as if found when the message arrived,
     * so this needs no internet.
     */
    private void checkMusicLink(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        String url="https://music.apple.com/us/album/test-album/1111?i=2222",spotify="https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b";
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("paired",0);String before=prefs.getString("music_app",null);prefs.edit().remove("music_app").commit();
        java.io.File dir=new java.io.File(ctx.getCacheDir(),"links-v2");dir.mkdirs();java.io.File preview=new java.io.File(dir,MediaCache.hash(url)+".json");
        java.nio.file.Files.write(preview.toPath(),new JSONObject().put("url",url).put("title","Test Song").put("description","Test Artist · Song · 2026").put("site","Apple Music").toString().getBytes("UTF-8"));
        java.io.File found=new java.io.File(new java.io.File(ctx.getCacheDir(),"music-v1"),MediaCache.hash("SPOTIFY "+url)+".json");found.getParentFile().mkdirs();
        java.nio.file.Files.write(found.toPath(),new JSONObject().put("how","exact").put("url",spotify).put("alt","").put("kind","SONG").put("at",System.currentTimeMillis()).toString().getBytes("UTF-8"));
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'music-link',name:'Mia Music',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'mm',number:'+15557770123'},fullName:'Mia Music'}]}"));
            long now=System.currentTimeMillis()*1000;
            store.message(new JSONObject().put("messageID","music-hi").put("conversationID","music-link").put("participantID","me").put("timestamp",now-60_000_000L)
                .put("messageStatus",new JSONObject().put("status","OUTGOING_DELIVERED")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","What are you listening to?")))),0,true);
            store.message(new JSONObject().put("messageID","music-song").put("conversationID","music-link").put("participantID","mm").put("timestamp",now)
                .put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","This one "+url)))),0,true);}
        Intent[] opened=new Intent[1];ActivityMonitor watch=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){
            if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getData()!=null&&intent.getData().toString().startsWith("https://open.spotify.com/")){opened[0]=intent;return new ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};
        addMonitor(watch);
        try{runOnMainSync(()->{try{method("thread",String.class).invoke(a,"music-link");}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(800);waitForIdleSync();
            android.view.View ask=described(a,"Open in your music app");
            check(ask!=null,"An Apple Music link's preview has no \"Open in…\" for your own music app");
            android.graphics.Rect r=placed(ask);press(r.exactCenterX(),r.exactCenterY());
            check(waitForText("Open music in",3000,true),"Tapping \"Open in…\" didn't ask which music app");shoot("shot-music-choose");
            pressText("Spotify (not on this phone)");
            for(int i=0;i<30&&opened[0]==null;i++)Thread.sleep(100);
            check(opened[0]!=null&&spotify.equals(String.valueOf(opened[0].getData())),"Choosing Spotify didn't open the same song in Spotify ("+opened[0]+")");
            check("SPOTIFY".equals(prefs.getString("music_app","")),"The music app chosen wasn't remembered");
            waitForIdleSync();Thread.sleep(400);
            android.view.View open=described(a,"Open in Spotify. Same song");
            check(open!=null,"After choosing, the preview doesn't say \"Open in Spotify · Same song\"");shoot("shot-music-link");
            opened[0]=null;r=placed(open);press(r.exactCenterX(),r.exactCenterY());
            for(int i=0;i<30&&opened[0]==null;i++)Thread.sleep(100);
            check(opened[0]!=null&&spotify.equals(String.valueOf(opened[0].getData())),"Tapping \"Open in Spotify\" didn't open the song ("+opened[0]+")");
            // Holding it offers to change the music app.
            hold(r.exactCenterX(),r.exactCenterY());check(onTop("Choose music app…"),"Holding a song's preview doesn't offer to change the music app");
            getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);Thread.sleep(300);}
        finally{removeMonitor(watch);onMainCall(()->method("inbox").invoke(a));waitForIdleSync();
            if(before==null)prefs.edit().remove("music_app").commit();else prefs.edit().putString("music_app",before).commit();
            preview.delete();found.delete();try(PairedStore store=new PairedStore(ctx)){store.deleteChat("music-link");}}}
    /**
     * A song pasted into a one-to-one chat goes as the same song on the other person's service:
     * they've sent an Apple Music link before, so a Spotify link in the message box changes to the
     * Apple Music one (the match saved beforehand: no internet needed), with Undo; the chat's
     * details say which app they use.
     */
    private void checkTheirMusic(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        String spotify="https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b",apple="https://music.apple.com/us/album/blinding-lights/1499378108?i=1499378607";
        java.io.File found=new java.io.File(new java.io.File(ctx.getCacheDir(),"music-v1"),MediaCache.hash("APPLE_MUSIC "+spotify)+".json");found.getParentFile().mkdirs();
        java.nio.file.Files.write(found.toPath(),new JSONObject().put("how","exact").put("url",apple).put("alt","").put("kind","SONG").put("at",System.currentTimeMillis()).toString().getBytes("UTF-8"));
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'their-music',name:'Theo Tunes',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'tt',number:'+15557770188'},fullName:'Theo Tunes'}]}"));
            store.message(new JSONObject().put("messageID","their-song").put("conversationID","their-music").put("participantID","tt").put("timestamp",System.currentTimeMillis()*1000-3_600_000_000L)
                .put("messageStatus",new JSONObject().put("status","INCOMING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","Obsessed with this https://music.apple.com/us/album/x/1?i=2")))),0,true);}
        java.lang.reflect.Field composer=field("composer");
        try{runOnMainSync(()->{try{method("thread",String.class).invoke(a,"their-music");}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(500);waitForIdleSync();
            runOnMainSync(()->{try{((android.widget.EditText)composer.get(a)).setText("Have you heard this? "+spotify);}catch(Exception e){throw new RuntimeException(e);}});
            String[] box={""};for(int i=0;i<30;i++){Thread.sleep(100);runOnMainSync(()->{try{box[0]=((android.widget.EditText)composer.get(a)).getText().toString();}catch(Exception e){throw new RuntimeException(e);}});if(box[0].contains(apple))break;}
            check(box[0].equals("Have you heard this? "+apple),"A Spotify song in the box didn't change to the Apple Music one Theo uses ("+box[0]+")");
            check(described(a,"Changed to Apple Music for Theo")!=null,"No note saying the song changed to Apple Music for Theo");shoot("shot-their-music");
            runOnMainSync(()->{try{((android.view.View)field("musicUndo").get(a)).performClick();}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(600);waitForIdleSync();
            runOnMainSync(()->{try{box[0]=((android.widget.EditText)composer.get(a)).getText().toString();}catch(Exception e){throw new RuntimeException(e);}});
            check(box[0].equals("Have you heard this? "+spotify),"Undo didn't put the Spotify link back, or it changed again ("+box[0]+")");
            runOnMainSync(()->{try{((android.widget.EditText)composer.get(a)).setText("");}catch(Exception e){throw new RuntimeException(e);}});
            onMainCall(()->method("details").invoke(a));waitForIdleSync();
            int[] rows={0};runOnMainSync(()->{java.util.ArrayList<android.view.View> hits=new java.util.ArrayList<>();a.getWindow().getDecorView().findViewsWithText(hits,"Their music app: Apple Music (from songs they've sent)",android.view.View.FIND_VIEWS_WITH_TEXT);rows[0]=hits.size();});
            check(rows[0]>0,"The chat's details don't say Theo uses Apple Music");shoot("shot-their-music-details");}
        finally{onMainCall(()->method("inbox").invoke(a));waitForIdleSync();found.delete();
            ctx.getSharedPreferences("chat-settings",0).edit().remove("music_learned:their-music").remove("music_their:their-music").commit();
            try(PairedStore store=new PairedStore(ctx)){store.deleteChat("their-music");}}}
    private void checkThemeAll(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();ChatSettings settings=new ChatSettings(ctx);
        settings.themeAll("default",true);settings.setTheme("smoke","berry");
        try{onMainCall(()->method("chooseTheme",String.class).invoke(a,""));check(waitForText("Color for all chats",3000,true),"Chat color for all chats didn't open");shoot("shot-theme-all");
            pressText("Ocean");check(waitForText("Use Ocean for all chats?",3000,true),"It didn't ask about the chat with its own colors");pressText("Keep theirs");
            check(waitForText("Use Ocean for all chats?",3000,false),"The question didn't close");
            check("ocean".equals(settings.theme("swipe-check").key),"A chat without its own colors didn't follow all chats ("+settings.theme("swipe-check").key+")");
            check("berry".equals(settings.theme("smoke").key),"Keep theirs changed a chat's own colors");
            onMainCall(()->method("chooseTheme",String.class).invoke(a,""));check(waitForText("Color for all chats",3000,true),"Chat color didn't open again");
            pressText("Forest");pressText("Change all");check(waitForText("Use Forest for all chats?",3000,false),"The question didn't close");
            check("forest".equals(settings.theme("smoke").key)&&!settings.ownTheme("smoke"),"Change all didn't switch the chat that had its own colors");
            // One chat can still be changed after, and go back.
            onMainCall(()->method("thread",String.class).invoke(a,"swipe-check"));waitForIdleSync();
            onMainCall(()->method("chooseTheme",String.class).invoke(a,"swipe-check"));check(waitForText("Same as all chats · Forest",3000,true),"A chat's theme doesn't offer following all chats");
            pressText("Rose");waitForIdleSync();check("rose".equals(settings.theme("swipe-check").key)&&"forest".equals(settings.theme("smoke").key),"Changing one chat after didn't change just that chat");
            onMainCall(()->method("chooseTheme",String.class).invoke(a,"swipe-check"));pressText("Same as all chats · Forest");waitForIdleSync();
            check(!settings.ownTheme("swipe-check")&&"forest".equals(settings.theme("swipe-check").key),"Same as all chats didn't take the chat back to the all-chats colors");}
        finally{settings.themeAll("default",true);getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);Thread.sleep(300);onMainCall(()->method("inbox").invoke(a));waitForIdleSync();}}
    private android.view.View linkCardIn(PairedActivity a){java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found,"Link preview",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);for(android.view.View v:found)if(v.isShown())return v;return null;}
    /**
     * A link's preview never makes the chat jump (it flickered when opening a chat): opened as after a
     * restart, with nothing in memory, the card is in the very first frame at its final height, room
     * kept for its picture; a redraw (a new message, a receipt) puts the picture straight back.
     */
    private void checkLinkPreviewSteady(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();String url="https://example.com/steady-preview";
        java.io.File pic=photo(new java.io.File(ctx.getCacheDir(),"link-test"),"steady.jpg",1280,720,0xff4466cc);java.io.File dir=new java.io.File(ctx.getCacheDir(),"links-v2");dir.mkdirs();
        java.io.File saved=new java.io.File(dir,MediaCache.hash(url)+".json");
        java.nio.file.Files.write(saved.toPath(),new JSONObject().put("url",url).put("title","A steady preview").put("description","Nothing should move").put("site","Example").put("image",pic.getPath()).put("imageW",1280).put("imageH",720).toString().getBytes("UTF-8"));
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'link-steady',name:'Lee Steady',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'ls',number:'+15557770199'},fullName:'Lee Steady'}]}"));
            for(int i=0;i<10;i++)textMessage(store,"link-steady",i);
            store.message(new JSONObject().put("messageID","steady-link").put("conversationID","link-steady").put("participantID","me").put("timestamp",System.currentTimeMillis()*1000)
                .put("messageStatus",new JSONObject().put("status","OUTGOING_COMPLETE")).put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content","Look at this "+url)))),0,true);}
        // As after a restart: neither the link's details nor its picture are in memory.
        runOnMainSync(()->{try{((java.util.Map<?,?>)field(LinkPreviews.class,"memory").get(null)).clear();((android.util.LruCache<?,?>)field(MediaCache.class,"memory").get(null)).evictAll();}catch(Exception e){throw new RuntimeException(e);}});
        java.util.List<Integer> frames=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        android.view.ViewTreeObserver.OnPreDrawListener watch=()->{android.view.View card=linkCardIn(a);frames.add(card==null?-1:card.getHeight());return true;};
        try{runOnMainSync(()->{a.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(watch);try{method("thread",String.class).invoke(a,"link-steady");}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(1500);waitForIdleSync();runOnMainSync(()->a.getWindow().getDecorView().getViewTreeObserver().removeOnPreDrawListener(watch));shoot("shot-link-preview");
            java.util.List<Integer> seen;synchronized(frames){seen=new java.util.ArrayList<>(frames);}
            check(!seen.isEmpty()&&seen.get(0)>0,"The link preview wasn't in the chat's first frame (frames "+seen+")");
            boolean same=true;for(int h:seen)same&=h==seen.get(0);check(same,"The link preview changed size while the chat opened, so the chat jumped (frames "+seen+")");
            android.widget.ImageView[] hero=new android.widget.ImageView[1];
            runOnMainSync(()->{android.view.View card=linkCardIn(a);if(card!=null)for(android.widget.ImageView i:viewsOf(card,android.widget.ImageView.class)){hero[0]=i;break;}});
            check(hero[0]!=null&&hero[0].getDrawable()!=null,"The link preview's picture didn't load");
            // A redraw: the new card has its picture in the same moment, nothing to blink.
            boolean[] kept={false};runOnMainSync(()->{try{method("renderMessages",boolean.class).invoke(a,false);}catch(Exception e){throw new RuntimeException(e);}
                android.view.View card=linkCardIn(a);if(card!=null)for(android.widget.ImageView i:viewsOf(card,android.widget.ImageView.class)){kept[0]=i!=hero[0]&&i.getDrawable()!=null;break;}});
            check(kept[0],"A redraw drew the link preview without its picture for a moment (it blinks)");
            // Holding the preview offers the link's options, and Copy link copies it.
            android.view.View[] card={null};runOnMainSync(()->card[0]=linkCardIn(a));android.graphics.Rect cr=placed(card[0]);hold(cr.exactCenterX(),cr.exactCenterY());
            check(onTop("Copy link")&&onTop("Share link"),"Holding a link preview didn't offer to copy or share the link");pressText("Copy link");Thread.sleep(300);
            String[] copied={null};runOnMainSync(()->{android.content.ClipData clip=a.getSystemService(android.content.ClipboardManager.class).getPrimaryClip();if(clip!=null&&clip.getItemCount()>0)copied[0]=String.valueOf(clip.getItemAt(0).getText());});
            check(url.equals(copied[0]),"Copy link from a preview didn't copy the link ("+copied[0]+")");}
        finally{runOnMainSync(()->a.getWindow().getDecorView().getViewTreeObserver().removeOnPreDrawListener(watch));onMainCall(()->method("inbox").invoke(a));waitForIdleSync();
            saved.delete();pic.delete();try(PairedStore store=new PairedStore(ctx)){store.deleteChat("link-steady");}}}
    /** The newest raw contact the shell just added: the highest "_id=12" it lists (arguments can't hold spaces, so no "DESC"). */
    private String newestRawContact()throws Exception{java.util.regex.Matcher m=java.util.regex.Pattern.compile("_id=(\\d+)").matcher(shell("content query --uri content://com.android.contacts/raw_contacts --projection _id"));
        long best=-1;while(m.find())best=Math.max(best,Long.parseLong(m.group(1)));return best<0?"":String.valueOf(best);}
    /**
     * ⊕ → Contact lists each person once with each number once, from the phone's real contacts:
     * the same number saved twice in different formats, and the same person saved twice, were
     * listed several times by Android's picker.
     */
    private void checkContactPickerDeduplicates()throws Exception{android.content.Context ctx=getTargetContext();
        getUiAutomation().grantRuntimePermission(ctx.getPackageName(),android.Manifest.permission.READ_CONTACTS);
        java.util.List<String> made=new java.util.ArrayList<>();
        for(String[] copy:new String[][]{{"4795550134","(479)555-0177"},{"+14795550134",""}}){
            shell("content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:");String raw=newestRawContact();if(raw.isEmpty())continue;made.add(raw);
            shell("content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"+raw+" --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:DupeCheck");
            shell("content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"+raw+" --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"+copy[0]+" --bind data2:i:2");
            shell("content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"+raw+" --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:479-555-0134 --bind data2:i:2");
            if(!copy[1].isEmpty())shell("content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"+raw+" --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"+copy[1]+" --bind data2:i:3");}
        java.util.List<ContactPeople.Person> people;int rows=0;
        try{people=PhoneContacts.people(ctx);
            for(PhoneContacts.Entry e:PhoneContacts.all(ctx))if(e.name.equals("DupeCheck"))rows++;}
        finally{for(String raw:made)shell("content delete --uri content://com.android.contacts/raw_contacts?caller_is_syncadapter=true --where _id="+raw);}
        check(made.size()==2,"The emulator didn't take the test contacts ("+made.size()+")");
        int found=0;ContactPeople.Person dupe=null;for(ContactPeople.Person p:people)if(p.name.equals("DupeCheck")){found++;dupe=p;}
        check(found==1,"The same person was listed "+found+" times");
        check(dupe.numbers.size()==2,"Their numbers weren't each listed once: "+dupe.numbers.size()+" (Android has "+rows+" rows)");}
    /** ⊕ → Contact puts "Name: number" into the message box: on its own line after words, with a bare-digits number written out. */
    private void checkContactText(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'contact-check',name:'Contact Check',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'cc',number:'+15550108888'},fullName:'Contact Check'}]}"));}
        java.lang.reflect.Method openChat=PairedActivity.class.getDeclaredMethod("thread",String.class),leaveChat=PairedActivity.class.getDeclaredMethod("inbox"),
            insert=PairedActivity.class.getDeclaredMethod("insertContact",String.class,String.class,String.class),readable=PairedActivity.class.getDeclaredMethod("readableNumber",String.class);
        for(java.lang.reflect.Method m:new java.lang.reflect.Method[]{openChat,leaveChat,insert,readable})m.setAccessible(true);
        java.lang.reflect.Field box=PairedActivity.class.getDeclaredField("composer");box.setAccessible(true);String[] got=new String[2];
        runOnMainSync(()->{try{openChat.invoke(a,"contact-check");android.widget.EditText e=(android.widget.EditText)box.get(a);e.setText("Here's his number");e.setSelection(e.getText().length());
            got[1]=(String)readable.invoke(a,"4795550134");insert.invoke(a,"Ben Smith",got[1],"");got[0]=e.getText().toString();e.setText("");leaveChat.invoke(a);}catch(Exception x){throw new RuntimeException(x);}});
        try(PairedStore store=new PairedStore(ctx)){store.deleteChat("contact-check");}
        check(got[1].contains("555-0134"),"A bare-digits number wasn't written out: "+got[1]);
        check(got[0].equals("Here's his number\nBen Smith: "+got[1]),"The contact didn't go into the message box as expected: "+got[0]);}
    private String shell(String command)throws Exception{android.os.ParcelFileDescriptor out=getUiAutomation().executeShellCommand(command);
        try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(out)){return new String(in.readAllBytes());}}
    /** This app's notification for a chat, waiting up to ms for it (and for Android to make it a bubble when it's one). */
    private android.app.Notification chatNotification(android.app.NotificationManager nm,String chat,long ms)throws Exception{
        long until=System.currentTimeMillis()+ms;android.app.Notification found;
        do{found=null;for(android.service.notification.StatusBarNotification sbn:nm.getActiveNotifications())if(("chat:"+chat).equals(sbn.getTag()))found=sbn.getNotification();
            if(found!=null&&(found.flags&android.app.Notification.FLAG_BUBBLE)!=0)return found;if(System.currentTimeMillis()<until)Thread.sleep(100);}
        while(System.currentTimeMillis()<until);return found;}
    /** Android removes notifications in the background, so wait (up to ms) for this chat's to be gone. */
    private boolean gone(android.app.NotificationManager nm,String chat,long ms)throws Exception{long until=System.currentTimeMillis()+ms;
        while(true){boolean there=false;for(android.service.notification.StatusBarNotification sbn:nm.getActiveNotifications())if(("chat:"+chat).equals(sbn.getTag()))there=true;
            if(!there)return true;if(System.currentTimeMillis()>=until)return false;Thread.sleep(100);}}
    /**
     * A chat's bubble steps aside while that chat is open in the full app and comes back, quiet
     * and collapsed, once you've left it; leaving a chat that had no bubble doesn't make one.
     */
    private void checkBubbleStepsAside(PairedActivity a)throws Exception{
        android.content.Context ctx=getTargetContext();android.app.NotificationManager nm=ctx.getSystemService(android.app.NotificationManager.class);
        getUiAutomation().grantRuntimePermission(ctx.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        shell("cmd notification set_bubbles "+ctx.getPackageName()+" 1");
        nm.createNotificationChannel(new android.app.NotificationChannel("bubble-check","Bubble check",android.app.NotificationManager.IMPORTANCE_HIGH));
        android.app.Person sam=new android.app.Person.Builder().setName("Sam Example").setKey("sam").build();
        android.graphics.Bitmap face=android.graphics.Bitmap.createBitmap(108,108,android.graphics.Bitmap.Config.ARGB_8888);face.eraseColor(0xff336699);
        android.graphics.drawable.Icon icon=android.graphics.drawable.Icon.createWithAdaptiveBitmap(face);
        ctx.getSystemService(android.content.pm.ShortcutManager.class).pushDynamicShortcut(new android.content.pm.ShortcutInfo.Builder(ctx,"chat-bubble-check").setShortLabel("Sam Example").setLongLived(true)
            .setIntent(new Intent(ctx,PairedActivity.class).setAction(Intent.ACTION_VIEW).putExtra("chat","bubble-check")).setIcon(icon).setPerson(sam)
            .setCategories(java.util.Collections.singleton(android.content.pm.ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION)).build());
        android.app.PendingIntent open=android.app.PendingIntent.getActivity(ctx,9,new Intent(ctx,BubbleActivity.class).setAction(Intent.ACTION_VIEW)
            .setData(android.net.Uri.parse("messagecenter-check://bubble-check")).putExtra("chat","bubble-check"),android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_MUTABLE);
        nm.notify("chat:bubble-check",1,new android.app.Notification.Builder(ctx,"bubble-check").setSmallIcon(android.R.drawable.sym_action_chat).setShortcutId("chat-bubble-check")
            .setStyle(new android.app.Notification.MessagingStyle(new android.app.Person.Builder().setName("You").build()).addMessage("Are you on your way?",System.currentTimeMillis(),sam))
            .setBubbleMetadata(new android.app.Notification.BubbleMetadata.Builder(open,icon).setDesiredHeight(600).build()).build());
        android.app.Notification shown=chatNotification(nm,"bubble-check",4000);
        check(shown!=null&&(shown.flags&android.app.Notification.FLAG_BUBBLE)!=0,"The check notification didn't become a bubble ("+(shown==null?"not posted":"flags "+shown.flags)+")");
        try(PairedStore store=new PairedStore(ctx)){store.chat(new JSONObject("{conversationID:'bubble-check',name:'Sam Example',type:'RCS',participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'sam',number:'+15550109999'},fullName:'Sam Example'}]}"));}
        // Opened the way you open it: the chat screen also quiets that chat's notification, which used to post the bubble straight back.
        java.lang.reflect.Method openChat=PairedActivity.class.getDeclaredMethod("thread",String.class),leaveChat=PairedActivity.class.getDeclaredMethod("inbox");openChat.setAccessible(true);leaveChat.setAccessible(true);
        runOnMainSync(()->{try{openChat.invoke(a,"bubble-check");}catch(Exception e){throw new RuntimeException(e);}});
        check(gone(nm,"bubble-check",3000),"Opening a chat left its bubble on screen");
        // And it stays away: Android finishing the removal late, the chat screen coming back, Google saying it's read.
        Thread.sleep(1500);runOnMainSync(()->PairedService.clearNotification(ctx,"bubble-check"));
        Thread.sleep(ChatBubbles.RESTORE_DELAY_MS+600);check(gone(nm,"bubble-check",0),"The bubble came back while its chat was open");
        // Switching away and straight back inside the delay doesn't flash it.
        runOnMainSync(()->ChatBubbles.showing(ctx,"no-bubble"));Thread.sleep(300);runOnMainSync(()->{try{openChat.invoke(a,"bubble-check");}catch(Exception e){throw new RuntimeException(e);}});
        Thread.sleep(ChatBubbles.RESTORE_DELAY_MS+600);check(gone(nm,"bubble-check",0),"The bubble came back while its chat was still open");
        // Leaving for the inbox brings it back.
        runOnMainSync(()->{try{leaveChat.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});
        shown=chatNotification(nm,"bubble-check",ChatBubbles.RESTORE_DELAY_MS+4000);
        check(shown!=null&&(shown.flags&android.app.Notification.FLAG_BUBBLE)!=0,"Leaving the chat didn't bring its bubble back ("+(shown==null?"nothing":"flags "+shown.flags)+")");
        android.app.Notification.BubbleMetadata meta=shown.getBubbleMetadata();
        check(meta!=null&&meta.isNotificationSuppressed()&&!meta.getAutoExpandBubble(),"The bubble came back expanded, or with an unread dot");
        runOnMainSync(()->ChatBubbles.showing(ctx,""));Thread.sleep(ChatBubbles.RESTORE_DELAY_MS+600);
        check(chatNotification(nm,"no-bubble",0)==null,"Leaving a chat that had no bubble made one");
        try(PairedStore store=new PairedStore(ctx)){store.deleteChat("bubble-check");}
        nm.cancel("chat:bubble-check",1);nm.deleteNotificationChannel("bubble-check");ctx.getSystemService(android.content.pm.ShortcutManager.class).removeLongLivedShortcuts(java.util.Collections.singletonList("chat-bubble-check"));}
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
        // One chat set to "Nothing" while all chats show the name only: that chat says just "New message"-style text, the other shows who.
        NotificationPrivacy.set(ctx,NotificationPrivacy.NAME);NotificationPrivacy.setOwn(ctx,"privacy-chat",NotificationPrivacy.NONE);
        for(String chat:new String[]{"privacy-chat","privacy-other"}){
            Reminders.add(ctx,chat,"privacy-"+chat,"Daniel Example","Secret plans for Friday",System.currentTimeMillis()+3_600_000L);
            String rid=Reminders.forMessage(ctx,"privacy-"+chat).optString("id");fire.invoke(null,ctx,rid);
            android.app.Notification n=null;for(int wait=0;wait<30&&n==null;wait++){for(android.service.notification.StatusBarNotification sbn:nm.getActiveNotifications())if(("reminder:"+rid).equals(sbn.getTag()))n=sbn.getNotification();if(n==null)Thread.sleep(100);}
            check(n!=null,"Reminder notification wasn't posted ("+chat+")");
            String shown=n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)+" | "+n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)+" | "+n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
            boolean hidden=chat.equals("privacy-chat");
            check(!shown.contains("Secret plans"),"Message text shown for "+chat+": "+shown);
            check(shown.contains("Daniel")!=hidden,"Name "+(hidden?"shown for a chat set to Nothing":"missing for a chat following Name only")+": "+shown);
            nm.cancel("reminder:"+rid,2);Thread.sleep(300);}
        NotificationPrivacy.setOwn(ctx,"privacy-chat","");check(NotificationPrivacy.own(ctx,"privacy-chat").isEmpty(),"A chat's own notification setting didn't clear");
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
    /** Runs action on the main thread and waits for the frame it causes to be drawn; the time from start to that frame, in ms. */
    private long untilDrawn(Activity a,Runnable action)throws Exception{java.util.concurrent.CountDownLatch drawn=new java.util.concurrent.CountDownLatch(1);long[] at=new long[2];
        runOnMainSync(()->{android.view.View decor=a.getWindow().getDecorView();at[0]=System.nanoTime();action.run();
            decor.getViewTreeObserver().registerFrameCommitCallback(()->{at[1]=System.nanoTime();drawn.countDown();});decor.invalidate();});
        check(drawn.await(20,java.util.concurrent.TimeUnit.SECONDS),"No frame was drawn after opening or leaving a chat");waitForIdleSync();settled=(System.nanoTime()-at[0])/1_000_000;return (at[1]-at[0])/1_000_000;}
    /** For untilDrawn: when the main thread had nothing left to do afterwards (anything drawn in a second pass included). */
    private long settled;
    /**
     * Opening a busy chat from a full inbox and going back, timed to the first frame drawn, with
     * where the time went. The inbox has 120 chats (80 with full-size contact photos, 40 groups).
     */
    private String measureOpenAndBack(PairedActivity a)throws Exception{android.content.Context ctx=getTargetContext();
        SessionVault vault=new SessionVault(ctx);vault.save("synthetic session for the speed check only");
        android.graphics.Bitmap face=android.graphics.Bitmap.createBitmap(900,900,android.graphics.Bitmap.Config.ARGB_8888);android.graphics.Canvas cv=new android.graphics.Canvas(face);
        android.graphics.Paint paint=new android.graphics.Paint();for(int y=0;y<900;y+=6){paint.setColor(0xff000000|(y*37%256)<<16|(y*11%256)<<8|(y*5%256));cv.drawRect(0,y,900,y+6,paint);}
        java.io.ByteArrayOutputStream jpeg=new java.io.ByteArrayOutputStream();face.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,jpeg);
        long newest=1794300000000000L;java.util.List<java.io.File> made=new java.util.ArrayList<>();Avatars.dir(ctx).mkdirs();
        try(PairedStore store=new PairedStore(ctx)){
            for(int i=0;i<120;i++){boolean group=i%3==2;StringBuilder people=new StringBuilder("{ID:{participantID:'me',number:'+15550000000'},isMe:true}");
                for(int k=0;k<(group?4:1);k++){String pid="sp"+i+"_"+k;people.append(",{ID:{participantID:'").append(pid).append("',number:'+1555").append(String.format(java.util.Locale.ROOT,"%07d",i*10+k)).append("'},fullName:'Person ").append(i).append('-').append(k).append("'}");
                    java.io.File f=Avatars.phoneFile(ctx,pid);try(java.io.FileOutputStream out=new java.io.FileOutputStream(f)){out.write(jpeg.toByteArray());}made.add(f);}
                store.chat(new JSONObject("{conversationID:'speed"+i+"',name:'"+(group?"Group ":"Person ")+i+"',type:'RCS',isGroupChat:"+group+",lastMessageTimestamp:"+(newest-i*600_000_000L)
                    +",latestMessage:{displayContent:'See you at the game tonight, I will bring the snacks'},participants:["+people+"]}"));}
            String[] kinds={"Sounds good, see you then","Call me at (479) 555-0134 when you land, the gate code is 4417","Meet at 123 Main St, Bentonville, AR 72712 on Friday at 6",
                "Here's the link https://example.com/menu and the other one www.example.org/hours","Your package 1Z999AA10123456784 is out for delivery, flight AA 1234 lands at 7:45 PM",
                "Haha yes!! 😂😂","Can you grab milk and eggs on the way home? Also bread if they have the good kind"};
            for(int i=0;i<300;i++){boolean mine=i%3==0;store.message(new JSONObject().put("messageID","speedmsg"+i).put("conversationID","speed0").put("participantID",mine?"me":"sp0_0").put("timestamp",newest-(300-i)*300_000_000L)
                .put("messageStatus",new JSONObject().put("status",mine?"OUTGOING_DELIVERED":"INCOMING_COMPLETE"))
                .put("messageInfo",new org.json.JSONArray().put(new JSONObject().put("messageContent",new JSONObject().put("content",kinds[i%kinds.length])))),0,true);}}
        java.lang.reflect.Method open=PairedActivity.class.getDeclaredMethod("thread",String.class),back=PairedActivity.class.getDeclaredMethod("inbox");open.setAccessible(true);back.setAccessible(true);
        Runnable toChat=()->{try{open.invoke(a,"speed0");}catch(Exception e){throw new RuntimeException(e);}},toInbox=()->{try{back.invoke(a);}catch(Exception e){throw new RuntimeException(e);}};
        String result;
        try{untilDrawn(a,toInbox);
            // The first time pays for decoding photos; after that, what you'd feel opening and closing chats.
            long firstOpen=untilDrawn(a,toChat),firstBack=untilDrawn(a,toInbox);
            Perf.clear();Perf.on=true;long[] opens=new long[5],backs=new long[5];
            long[] openSettled=new long[5],backSettled=new long[5];
            for(int r=0;r<5;r++){opens[r]=untilDrawn(a,toChat);openSettled[r]=settled;backs[r]=untilDrawn(a,toInbox);backSettled[r]=settled;}
            Perf.on=false;java.util.Arrays.sort(opens);java.util.Arrays.sort(backs);java.util.Arrays.sort(openSettled);java.util.Arrays.sort(backSettled);
            result="open chat "+firstOpen+" ms first, "+opens[2]+" ms after (settled "+openSettled[2]+"); back to inbox "+firstBack+" ms first, "+backs[2]+" ms after (settled "+backSettled[2]+") [where: "+Perf.report(5)+"]";}
        finally{Perf.on=false;vault.clear();try(PairedStore store=new PairedStore(ctx)){for(int i=0;i<120;i++)store.deleteChat("speed"+i);}for(java.io.File f:made)f.delete();onMain(a,"inbox");}
        return result;}
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
        // A long message grows the message box past 5 lines, but only to about 40% of the screen.
        java.lang.reflect.Field compF=PairedActivity.class.getDeclaredField("composer"),rootF=PairedActivity.class.getDeclaredField("root");compF.setAccessible(true);rootF.setAccessible(true);
        StringBuilder longText=new StringBuilder();for(int i=0;i<40;i++)longText.append("This is line ").append(i+1).append(" of a long message\n");
        runOnMainSync(()->{try{((android.widget.EditText)compF.get(reader)).setText(longText.toString().trim());}catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleSync();runOnMainSync(()->{});waitForIdleSync();
        int[] box=new int[3];
        runOnMainSync(()->{try{android.widget.EditText e=(android.widget.EditText)compF.get(reader);box[0]=e.getHeight();box[1]=e.getLineHeight();box[2]=((android.view.View)rootF.get(reader)).getHeight();}catch(Exception e){throw new RuntimeException(e);}});
        check(box[0]>box[1]*6,"A long message didn't grow the message box ("+box[0]+" px, lines are "+box[1]+" px)");
        check(box[0]<=box[2]*0.42f,"The message box grew past 40% of the screen ("+box[0]+" of "+box[2]+" px)");
        runOnMainSync(()->{try{((android.widget.EditText)compF.get(reader)).setText("");}catch(Exception e){throw new RuntimeException(e);}});
        reader.finish();waitForIdleSync();}
    /** Photos are made to fit before sending: under 1 MB for a picture message (MMS), a sensible size for RCS; small ones left alone. */
    private void checkPhotoPrep()throws Exception{
        java.io.File dir=new java.io.File(getTargetContext().getCacheDir(),"outgoing");dir.mkdirs();
        java.io.File big=new java.io.File(dir,"smoke-big.jpg");
        {// Blocky noise, like a busy photo, so it doesn't compress to nothing (12 MP, a few MB).
            android.graphics.Bitmap noise=android.graphics.Bitmap.createBitmap(1000,750,android.graphics.Bitmap.Config.ARGB_8888);int[] px=new int[1000*750];java.util.Random r=new java.util.Random(7);
            for(int i=0;i<px.length;i++)px[i]=0xff000000|r.nextInt(0xffffff);noise.setPixels(px,0,1000,0,0,1000,750);
            android.graphics.Bitmap bm=android.graphics.Bitmap.createScaledBitmap(noise,4000,3000,false);noise.recycle();
            try(java.io.FileOutputStream fo=new java.io.FileOutputStream(big)){bm.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,fo);}bm.recycle();}
        check(big.length()>950_000,"The test photo isn't big enough to test with ("+big.length()+" bytes)");
        PhotoPrep.Ready mms=PhotoPrep.prepare(big,"IMG_1234.JPG","image/jpeg",true);
        android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(mms.file.getPath(),o);
        check(mms.file!=big&&mms.file.length()<=PhotoPrep.MMS_BYTES,"A big photo wasn't made to fit a picture message ("+mms.file.length()+" bytes)");
        check(Math.max(o.outWidth,o.outHeight)<=PhotoPrep.MMS_EDGE&&Math.abs(o.outWidth*3-o.outHeight*4)<=6,"The photo for MMS is "+o.outWidth+"×"+o.outHeight);
        check(mms.mime.equals("image/jpeg")&&mms.name.equals("IMG_1234.jpg"),"The prepared photo is named "+mms.name+" ("+mms.mime+")");
        mms.file.delete();
        PhotoPrep.Ready rcs=PhotoPrep.prepare(big,"IMG_1234.JPG","image/jpeg",false);o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(rcs.file.getPath(),o);
        check(Math.max(o.outWidth,o.outHeight)<=PhotoPrep.RCS_EDGE,"The photo for RCS is "+o.outWidth+"×"+o.outHeight);
        if(rcs.file!=big)rcs.file.delete();
        PhotoPrep.Ready fast=PhotoPrep.prepare(big,"IMG_1234.JPG","image/jpeg",false,PhotoQuality.FAST);o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeFile(fast.file.getPath(),o);
        check(Math.max(o.outWidth,o.outHeight)<=1280&&fast.file.length()<=PhotoQuality.bytes(PhotoQuality.FAST,false),"Fast quality isn't a smaller, sendable picture");if(fast.file!=big)fast.file.delete();
        check(PhotoPrep.prepare(big,"IMG_1234.JPG","image/jpeg",false,PhotoQuality.FULL).file.equals(big),"Full quality replaced a supported original unnecessarily");
        java.io.File small=new java.io.File(dir,"smoke-small.jpg");
        {android.graphics.Bitmap bm=android.graphics.Bitmap.createBitmap(800,600,android.graphics.Bitmap.Config.ARGB_8888);bm.eraseColor(0xff336699);try(java.io.FileOutputStream fo=new java.io.FileOutputStream(small)){bm.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,fo);}}
        check(PhotoPrep.prepare(small,"small.jpg","image/jpeg",true).file==small,"A small photo was re-encoded for no reason");
        big.delete();small.delete();}
    /** Real offline OCR, persistent search results, and follow-ups independent of read/unread. */
    private void checkPictureTextAndFollowUps()throws Exception{
        android.content.Context c=getTargetContext();PhotoText.clear(c);c.getSharedPreferences("paired",0).edit().putBoolean("photo_text",true).commit();
        JSONObject chat=new JSONObject("{conversationID:'picture-search',type:'RCS',participants:[{ID:{participantID:'me'},isMe:true},{ID:{participantID:'other'}}]}");
        JSONObject message=new JSONObject("{messageID:'ocr-message',conversationID:'picture-search',participantID:'other',timestamp:1800000000000000,messageStatus:{status:'INCOMING_DISPLAYED'},messageInfo:[{mediaContent:{mediaID:'ocr-fixture',mimeType:'image/png',size:10000}}]}");
        JSONObject media=PairedProtocol.media(message).get(0);java.io.File image=MediaCache.file(c,media,false);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(1200,400,android.graphics.Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);
        android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);paint.setColor(android.graphics.Color.BLACK);paint.setTextSize(76);paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,android.graphics.Typeface.BOLD));
        canvas.drawText("RIVER PARK",55,135,paint);canvas.drawText("Reservation 8246",55,265,paint);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(image)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        try(PairedStore store=new PairedStore(c)){store.chat(chat);store.message(message,0,true);}
        java.util.concurrent.CountDownLatch scanned=new java.util.concurrent.CountDownLatch(1);String[] result={"",""};PhotoText.scan(c,media,image,true,(text,error)->{result[0]=text;result[1]=error;scanned.countDown();});
        check(scanned.await(75,java.util.concurrent.TimeUnit.SECONDS),"Offline picture recognition timed out");
        check(result[1].isEmpty()&&result[0].contains("8246")&&result[0].toUpperCase(java.util.Locale.ROOT).contains("RIVER"),"Offline picture text wasn't recognized: "+result[0]+" / "+result[1]);
        try(PairedStore store=new PairedStore(c)){
            java.util.List<JSONObject> hits=PhotoText.matches(c,store,"8246","",10);check(hits.size()==1&&hits.get(0).optString("messageID").equals("ocr-message"),"Indexed picture text isn't searchable after reopening");
            ChatSettings settings=new ChatSettings(c);settings.markSeen("picture-search",PairedProtocol.time(message));
            check(NeedsReply.pending(store.replyBoundary("picture-search",chat),chat,0,0,System.currentTimeMillis()),"Reading a message removed its reply follow-up");
            settings.dismissReply("picture-search",PairedProtocol.time(message));check(!NeedsReply.pending(store.replyBoundary("picture-search",chat),chat,settings.replyDismissed("picture-search"),0,System.currentTimeMillis()),"Mark handled didn't dismiss the follow-up");
            JSONObject sent=new JSONObject("{messageID:'answer',conversationID:'picture-search',participantID:'me',timestamp:1800000001000000,messageStatus:{status:'OUTGOING_COMPLETE'},messageInfo:[{messageContent:{content:'Synthetic answer'}}]}");store.message(sent,0,true);
            check(!NeedsReply.pending(store.replyBoundary("picture-search",chat),chat,0,0,System.currentTimeMillis()),"A successfully sent answer didn't clear the follow-up");
            store.deleteMessage("ocr-message");check(PhotoText.matches(c,store,"8246","",10).isEmpty(),"Deleted messages are still returned by picture search");store.deleteChat("picture-search");
        }
        image.delete();PhotoText.clear(c);
    }
    /** A 2-second 1280×720 clip at 60 fps filmed sideways (rotation 90), with stereo sound and a little grain, like a phone's. */
    private java.io.File makeTestVideo(java.io.File out)throws Exception{
        int w=1280,h=720,frames=120;android.media.MediaCodec.BufferInfo info=new android.media.MediaCodec.BufferInfo();
        android.media.MediaFormat vf=android.media.MediaFormat.createVideoFormat("video/avc",w,h);
        vf.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        vf.setInteger(android.media.MediaFormat.KEY_BIT_RATE,12_000_000);vf.setInteger(android.media.MediaFormat.KEY_FRAME_RATE,60);vf.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL,1);
        android.media.MediaCodec ve=android.media.MediaCodec.createEncoderByType("video/avc");ve.configure(vf,null,null,android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);ve.start();
        java.util.List<byte[]> vData=new java.util.ArrayList<>();java.util.List<long[]> vMeta=new java.util.ArrayList<>();android.media.MediaFormat vOut=null;
        int frame=0;boolean inDone=false,outDone=false;
        while(!outDone){
            if(!inDone){int i=ve.dequeueInputBuffer(10_000);if(i>=0){long t=frame*1_000_000L/60;
                if(frame==frames){ve.queueInputBuffer(i,0,0,t,android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}
                else{android.media.Image img=ve.getInputImage(i);android.media.Image.Plane[] pl=img.getPlanes();
                    // A panning gradient, a moving square and fresh grain each frame: something like real footage.
                    int sq=(frame*10)%(w-200);
                    for(int c=0;c<3;c++){java.nio.ByteBuffer b=pl[c].getBuffer();int rs=pl[c].getRowStride(),ps=pl[c].getPixelStride(),pw=c==0?w:w/2,ph=c==0?h:h/2;
                        for(int y=0;y<ph;y++)for(int x=0;x<pw;x++){int v;
                            if(c==0){int g=(x+frame*6)%512;v=20+(g<256?g:511-g)*3/5+(y&255)/5;if(x>=sq&&x<sq+200&&y>=200&&y<400)v+=60;
                                int n=x*73856093^y*19349663^frame*83492791;n=(n^(n>>>13))*0x5bd1e995;v+=((n>>>24)&15)-8;v=Math.max(16,Math.min(235,v));}
                            else v=c==1?96+((x+frame)&63):96+((y*2)&63);
                            b.put(y*rs+x*ps,(byte)v);}}
                    ve.queueInputBuffer(i,0,w*h*3/2,t,0);frame++;}}}
            int o=ve.dequeueOutputBuffer(info,10_000);
            if(o==android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)vOut=ve.getOutputFormat();
            else if(o>=0){java.nio.ByteBuffer b=ve.getOutputBuffer(o);
                if((info.flags&android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&info.size>0){byte[] d=new byte[info.size];b.position(info.offset);b.get(d);vData.add(d);vMeta.add(new long[]{info.presentationTimeUs,info.flags});}
                if((info.flags&android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outDone=true;ve.releaseOutputBuffer(o,false);}}
        ve.stop();ve.release();
        android.media.MediaFormat af=android.media.MediaFormat.createAudioFormat("audio/mp4a-latm",48000,2);af.setInteger(android.media.MediaFormat.KEY_BIT_RATE,128_000);
        af.setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        android.media.MediaCodec ae=android.media.MediaCodec.createEncoderByType("audio/mp4a-latm");ae.configure(af,null,null,android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);ae.start();
        java.util.List<byte[]> aData=new java.util.ArrayList<>();java.util.List<long[]> aMeta=new java.util.ArrayList<>();android.media.MediaFormat aOut=null;
        long sample=0,total=48000L*2;inDone=false;outDone=false;
        while(!outDone){
            if(!inDone){int i=ae.dequeueInputBuffer(10_000);if(i>=0){java.nio.ByteBuffer b=ae.getInputBuffer(i);long t=sample*1_000_000L/48000;
                if(sample>=total){ae.queueInputBuffer(i,0,0,t,android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}
                else{int n=(int)Math.min(b.capacity()/4,total-sample);b.clear();b.order(java.nio.ByteOrder.nativeOrder());
                    for(int k=0;k<n;k++){short v=(short)(Math.sin(2*Math.PI*440*(sample+k)/48000.0)*8000);b.putShort(v);b.putShort(v);}
                    ae.queueInputBuffer(i,0,n*4,t,0);sample+=n;}}}
            int o=ae.dequeueOutputBuffer(info,10_000);
            if(o==android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)aOut=ae.getOutputFormat();
            else if(o>=0){java.nio.ByteBuffer b=ae.getOutputBuffer(o);
                if((info.flags&android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&info.size>0){byte[] d=new byte[info.size];b.position(info.offset);b.get(d);aData.add(d);aMeta.add(new long[]{info.presentationTimeUs,info.flags});}
                if((info.flags&android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outDone=true;ae.releaseOutputBuffer(o,false);}}
        ae.stop();ae.release();
        check(vOut!=null&&aOut!=null&&vData.size()>=frames-2,"The test video couldn't be made ("+vData.size()+" frames)");
        android.media.MediaMuxer mux=new android.media.MediaMuxer(out.getPath(),android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);mux.setOrientationHint(90);
        int vt=mux.addTrack(vOut),at=mux.addTrack(aOut);mux.start();
        java.util.function.BiConsumer<Integer,Object[]> write=(track,lists)->{@SuppressWarnings("unchecked") java.util.List<byte[]> d=(java.util.List<byte[]>)lists[0];@SuppressWarnings("unchecked") java.util.List<long[]> m=(java.util.List<long[]>)lists[1];
            for(int k=0;k<d.size();k++){android.media.MediaCodec.BufferInfo bi=new android.media.MediaCodec.BufferInfo();bi.set(0,d.get(k).length,m.get(k)[0],(int)(m.get(k)[1]&android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME));mux.writeSampleData(track,java.nio.ByteBuffer.wrap(d.get(k)),bi);}};
        write.accept(vt,new Object[]{vData,vMeta});write.accept(at,new Object[]{aData,aMeta});mux.stop();mux.release();
        return out;}
    /** Display size (rotation applied), duration and sound of a video file. */
    private int[] videoFacts(java.io.File f)throws Exception{android.media.MediaMetadataRetriever r=new android.media.MediaMetadataRetriever();
        try{r.setDataSource(f.getPath());int w=Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),h=Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot=r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);int deg=rot==null?0:Integer.parseInt(rot);if(deg==90||deg==270){int t=w;w=h;h=t;}
            int ms=Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));int channels=0;
            String fc=r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);int count=fc==null?-1:Integer.parseInt(fc);
            android.media.MediaExtractor x=new android.media.MediaExtractor();x.setDataSource(f.getPath());
            for(int i=0;i<x.getTrackCount();i++){android.media.MediaFormat fm=x.getTrackFormat(i);if(fm.getString(android.media.MediaFormat.KEY_MIME).startsWith("audio/"))channels=fm.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);}
            x.release();return new int[]{w,h,ms,channels,count};}finally{r.release();}}
    /** The journal's lines about videos, to say why a check failed. */
    private String videoJournal(){try{java.util.List<String> all=java.nio.file.Files.readAllLines(new java.io.File(getTargetContext().getFilesDir(),"diagnostics.txt").toPath());
        StringBuilder b=new StringBuilder();for(String l:all)if(l.contains("Video"))b.append(l).append(" || ");return b.toString();}catch(Exception e){return "(no journal: "+e+")";}}
    /** Videos are shrunk to send: kept upright, about the right size, with their sound; mono and under 1 MB for MMS. */
    private void checkVideoPrep()throws Exception{
        java.io.File dir=new java.io.File(getTargetContext().getCacheDir(),"outgoing");dir.mkdirs();
        java.io.File src=makeTestVideo(new java.io.File(dir,"smoke-video.mp4"));int[] in=videoFacts(src);
        android.util.Log.i("OnDeviceSmoke","Test video: "+src.length()/1024+" KB");
        check(in[0]==720&&in[1]==1280&&in[3]==2,"The test video is "+in[0]+"×"+in[1]+" with "+in[3]+" channels");
        VideoPlan.Source s=VideoPrep.read(src,"video/mp4");
        check(s!=null&&s.video.equals("video/avc")&&s.audio.equals("audio/mp4a-latm")&&!s.hdr&&Math.abs(s.durationMs-2000)<200,"The video wasn't read right ("+(s==null?"null":s.video+" "+s.audio+" "+s.durationMs+" ms")+")");
        int[] seen={-1};long t0=System.currentTimeMillis();
        PhotoPrep.Ready rcs=VideoPrep.prepare(getTargetContext(),src,"VID_2026.MP4","video/mp4",false,p->seen[0]=Math.max(seen[0],p));long rcsMs=System.currentTimeMillis()-t0;
        check(rcs.file!=src,"A heavy video wasn't re-encoded for RCS: "+videoJournal());
        check(rcs.file!=src&&rcs.file.isFile(),"A heavy video wasn't re-encoded for RCS");
        check(rcs.file.length()<src.length(),"The RCS video came out "+rcs.file.length()+" bytes, from "+src.length());
        check(rcs.mime.equals("video/mp4")&&rcs.name.equals("VID_2026.mp4"),"The prepared video is named "+rcs.name+" ("+rcs.mime+")");
        int[] r=videoFacts(rcs.file);
        check(r[0]<r[1]&&Math.abs(r[0]-720)<=16&&Math.abs(r[1]-1280)<=16,"The RCS video is "+r[0]+"×"+r[1]+" (should stay 720×1280, upright)");
        check(Math.abs(r[2]-2000)<=300&&r[3]==2,"The RCS video is "+r[2]+" ms with "+r[3]+" channels");
        check(r[4]<0||r[4]>=50&&r[4]<=70,"The RCS video has "+r[4]+" frames in 2 seconds (should be about 30 a second, from 60)");
        rcs.file.delete();
        PhotoPrep.Ready mms;try{mms=VideoPrep.prepare(getTargetContext(),src,"VID_2026.MP4","video/mp4",true,null);}
        catch(java.io.IOException e){throw new AssertionError(e.getMessage()+" | "+videoJournal());}
        check(mms.file!=src&&mms.file.length()<=VideoPlan.MMS_BYTES,"The MMS video is "+mms.file.length()+" bytes");
        int[] m=videoFacts(mms.file);
        check(m[0]<m[1]&&Math.abs(m[0]-480)<=16&&Math.abs(m[0]*16-m[1]*9)<=32*16,"The MMS video is "+m[0]+"×"+m[1]+" (should be about 480×854, upright)");
        check(Math.abs(m[2]-2000)<=300&&m[3]==1,"The MMS video is "+m[2]+" ms with "+m[3]+" channels (should be mono)");
        mms.file.delete();
        // Not a video: left alone.
        check(VideoPrep.prepare(getTargetContext(),src,"notes.pdf","application/pdf",false,null).file==src,"A file that isn't a video was touched");
        src.delete();
        android.util.Log.i("OnDeviceSmoke","Video prep: RCS took "+rcsMs+" ms, progress seen "+seen[0]);}
    /** Text on arrival: sends once on arriving (through the outbox), waits for you to leave first when you're already there, and repeats when asked. */
    private void checkArrivalTexts()throws Exception{android.content.Context c=getTargetContext();for(JSONObject a:Arrivals.all(c))Arrivals.cancel(c,a.optString("id"));
        JSONObject place=Arrivals.savePlace(c,"Home",36.37,-94.21);Outbox outbox=new Outbox(c);int before=outbox.forChat("arrive").size();
        JSONObject once=Arrivals.add(c,"arrive",place,"Made it home!",false,ArrivalPlan.ARMED);
        check(!Arrivals.arrived(c,once.optString("id"),false),"Leaving sent the arrival text");
        check(Arrivals.arrived(c,once.optString("id"),true),"Arriving didn't send the text");check(Arrivals.forChat(c,"arrive").isEmpty(),"A one-time arrival text kept watching after it sent");
        java.util.List<JSONObject> queued=outbox.forChat("arrive");check(queued.size()==before+1&&queued.get(queued.size()-1).optString("text").equals("Made it home!"),"The arrival text wasn't queued to send");
        check(!Arrivals.arrived(c,once.optString("id"),true),"A finished arrival text sent again");
        JSONObject every=Arrivals.add(c,"arrive",place,"Home again",true,ArrivalPlan.AWAY_FIRST);String id=every.optString("id");
        check(!Arrivals.arrived(c,id,true),"It texted on the spot although you were already there");
        check(!Arrivals.arrived(c,id,false)&&Arrivals.arrived(c,id,true),"Leaving and coming back didn't send");
        check(!Arrivals.arrived(c,id,true),"A second arrival without leaving sent again");
        check(!Arrivals.arrived(c,id,false)&&Arrivals.arrived(c,id,true)&&Arrivals.forChat(c,"arrive").size()==1,"Every-time arrival didn't repeat");
        Arrivals.cancel(c,id);check(Arrivals.forChat(c,"arrive").isEmpty(),"Canceling didn't stop it");
        // When I leave: arriving doesn't send; leaving does. Set up away from the place, it waits until you've been there.
        JSONObject leaving=Arrivals.add(c,"arrive",place,"Leaving home now.",false,ArrivalPlan.ARMED,true,0,"");
        check(!Arrivals.arrived(c,leaving.optString("id"),true)&&Arrivals.arrived(c,leaving.optString("id"),false),"A leaving text didn't send on leaving");
        JSONObject later=Arrivals.add(c,"arrive",place,"Off now",false,ArrivalPlan.AWAY_FIRST,true,0,"");String lid=later.optString("id");
        check(!Arrivals.arrived(c,lid,false),"A leaving text sent before you'd been there");check(!Arrivals.arrived(c,lid,true)&&Arrivals.arrived(c,lid,false),"A leaving text didn't send after you'd been there");
        // Check-in: the late text goes at the time unless you've arrived; the arrival text still goes after.
        JSONObject checkIn=Arrivals.add(c,"arrive",place,"Made it home!",false,ArrivalPlan.ARMED,false,System.currentTimeMillis()+30_000,"Not home yet, running late.");String cid=checkIn.optString("id");
        check(Arrivals.late(c,cid),"The check-in's late text didn't go");check(!Arrivals.late(c,cid),"The late text went twice");
        check(Arrivals.arrived(c,cid,true)&&Arrivals.forChat(c,"arrive").isEmpty(),"After a late text, arriving didn't send the arrival text");
        JSONObject onTime=Arrivals.add(c,"arrive",place,"Made it home!",false,ArrivalPlan.ARMED,false,System.currentTimeMillis()+30_000,"Running late");
        check(Arrivals.arrived(c,onTime.optString("id"),true)&&!Arrivals.late(c,onTime.optString("id")),"A late text went although you'd arrived");
        java.util.List<String> said=new java.util.ArrayList<>();for(JSONObject o:outbox.forChat("arrive"))said.add(o.optString("text"));
        check(said.contains("Leaving home now.")&&said.contains("Off now")&&said.contains("Not home yet, running late.")&&!said.contains("Running late"),"The leaving and check-in texts weren't queued right: "+said);
        for(JSONObject o:outbox.forChat("arrive"))outbox.remove(o.optString("id"));Arrivals.removePlace(c,"Home");check(Arrivals.places(c).isEmpty(),"The saved place wasn't forgotten");}
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
        // Google often lists people by number only: their name comes from the chat with them, and anyone still nameless goes last.
        try(PairedStore store=new PairedStore(getTargetContext())){store.chat(new JSONObject("{conversationID:'nameless',name:'Jordan Example',type:'RCS',lastMessageTimestamp:1794000000000000,participants:[{ID:{participantID:'me',number:'+15550000000'},isMe:true},{ID:{participantID:'j1',number:'+15550107777'},formattedNumber:'(555) 010-7777'}]}"));}
        java.lang.reflect.Field knownF=PairedActivity.class.getDeclaredField("knownNames"),chatPeopleF=PairedActivity.class.getDeclaredField("chatPeople");knownF.setAccessible(true);chatPeopleF.setAccessible(true);
        runOnMainSync(()->{try{googleF.set(a,new JSONObject("{top:[],all:[{name:'',number:{number:'+15550107777',formattedNumber:'(555) 010-7777'}},{name:'',number:{number:'+15550108888',formattedNumber:'(555) 010-8888'}}]}"));knownF.set(a,null);chatPeopleF.set(a,null);}catch(Exception e){throw new RuntimeException(e);}});
        check(shows.apply("jordan","Jordan Example"),"A contact Google lists by number alone isn't shown by name");
        java.util.List<String> names=new java.util.ArrayList<>();
        runOnMainSync(()->{try{java.lang.reflect.Method every=PairedActivity.class.getDeclaredMethod("everyone");every.setAccessible(true);
            for(Object person:(java.util.List<?>)every.invoke(a)){java.lang.reflect.Field n=person.getClass().getDeclaredField("name");n.setAccessible(true);names.add((String)n.get(person));}}catch(Exception e){throw new RuntimeException(e);}});
        int lastNamed=-1,firstNumber=names.size();for(int i=0;i<names.size();i++){if(ContactSearch.numberLike(names.get(i)))firstNumber=Math.min(firstNumber,i);else lastNamed=i;}
        check(names.contains("Jordan Example")&&firstNumber<names.size()&&lastNamed<firstNumber,"Number-only contacts aren't after everyone with a name ("+lastNamed+" vs "+firstNumber+" of "+names.size()+")");
        runOnMainSync(()->{try{phoneF.set(a,null);googleF.set(a,null);queryF.set(a,"");knownF.set(a,null);chatPeopleF.set(a,null);}catch(Exception e){throw new RuntimeException(e);}});
        try(PairedStore store=new PairedStore(getTargetContext())){store.deleteChat("riley");store.deleteChat("nameless");}
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
        runOnMainSync(()->{try{box[0]=new InboxScrollView(a);list[0]=new android.widget.LinearLayout(a);list[0].setOrientation(android.widget.LinearLayout.VERTICAL);box[0].addView(list[0]);
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
        // Live refreshes must not remove the touched view or reset an in-progress vertical drag.
        float density=a.getResources().getDisplayMetrics().density;
        runOnMainSync(()->{box[0].scrollTo(0,row.apply("row10").getTop());at[0]=row.apply("row10");y[0]=box[0].getScrollY();});
        long down=android.os.SystemClock.uptimeMillis();float x=box[0].getWidth()*.5f,start=box[0].getHeight()*.8f;
        inboxTouch(box[0],down,android.view.MotionEvent.ACTION_DOWN,x,start);Thread.sleep(20);
        inboxTouch(box[0],down,android.view.MotionEvent.ACTION_MOVE,x,start-40*density);Thread.sleep(20);
        inboxTouch(box[0],down,android.view.MotionEvent.ACTION_MOVE,x,start-90*density);waitForIdleSync();
        runOnMainSync(()->y[1]=box[0].getScrollY());check(y[1]>y[0],"A vertical inbox drag didn't scroll before a refresh");
        int duringDrag=y[1];
        try(PairedStore store=new PairedStore(getTargetContext())){store.chat(new JSONObject("{conversationID:'row25',name:'Row 25',type:'RCS',lastMessageTimestamp:"+(newest+240_000_000L)+","+people+"}"));}
        runOnMainSync(()->{try{for(int n=0;n<4;n++)render.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});
        // A stationary finger still owns this gesture, even after the refresh debounce expires.
        Thread.sleep(240);waitForIdleSync();
        check(row.apply("row10")==at[0],"An inbox update replaced rows while a finger was held down");
        inboxTouch(box[0],down,android.view.MotionEvent.ACTION_MOVE,x,start-170*density);waitForIdleSync();
        runOnMainSync(()->y[1]=box[0].getScrollY());check(y[1]>duringDrag,"A background inbox update cancelled the ongoing drag");
        inboxTouch(box[0],down,android.view.MotionEvent.ACTION_CANCEL,x,start-170*density);
        boolean applied=false;for(int wait=0;wait<100&&!applied;wait++){Thread.sleep(25);runOnMainSync(()->{});applied=row.apply("row10")!=at[0]&&"row25".equals(firstRow(list[0],rowTag));}
        check(applied,"Deferred inbox updates weren't applied after the finger lifted");
        // Momentum scrolling must continue too; restoring an anchor mid-fling used to stop it.
        runOnMainSync(()->{box[0].scrollTo(0,row.apply("row8").getTop());at[0]=row.apply("row10");y[0]=box[0].getScrollY();box[0].fling(Math.round(2400*density));});
        try(PairedStore store=new PairedStore(getTargetContext())){store.chat(new JSONObject("{conversationID:'row23',name:'Row 23',type:'RCS',lastMessageTimestamp:"+(newest+300_000_000L)+","+people+"}"));}
        runOnMainSync(()->{try{for(int n=0;n<4;n++)render.invoke(a);}catch(Exception e){throw new RuntimeException(e);}});
        Thread.sleep(80);runOnMainSync(()->y[1]=box[0].getScrollY());
        check(row.apply("row10")==at[0],"A live inbox refresh replaced rows during a fling");
        check(y[1]>y[0],"A live inbox refresh stopped momentum scrolling");
        applied=false;for(int wait=0;wait<240&&!applied;wait++){Thread.sleep(25);runOnMainSync(()->{});applied=row.apply("row10")!=at[0]&&"row23".equals(firstRow(list[0],rowTag));}
        check(applied,"The latest inbox update didn't appear after the fling settled");
        try(PairedStore store=new PairedStore(getTargetContext())){for(int i=0;i<30;i++)store.deleteChat("row"+i);}
        onMain(a,"inbox");}
    private void inboxTouch(android.widget.ScrollView box,long down,int action,float x,float y){runOnMainSync(()->{
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);
        try{box.dispatchTouchEvent(event);}finally{event.recycle();}});}
    @Override public void onStart(){Bundle result=new Bundle();try {
        watchForSystemDialogs();
        // Real JNI load on Android, not a mocked Java bridge.
        new MediaOutbox(getTargetContext()).clear();PhotoText.clear(getTargetContext());
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
        // Group bubbles show actual members, never your own face; the collage survives Android's adaptive mask.
        JSONObject groupIdentity=new JSONObject("{conversationID:'avatar-check',isGroupChat:true,participants:[{ID:{participantID:'owner'},isMe:true},{ID:{participantID:'c'},fullName:'Example Three'},{ID:{participantID:'a'},fullName:'Example One'},{ID:{participantID:'b'},fullName:'Example Two'},{ID:{participantID:'hidden'},isVisible:false}]}");
        check(Avatars.isGroup(groupIdentity)&&Avatars.groupMembers(groupIdentity).size()==3&&PairedProtocol.participantID(Avatars.groupMembers(groupIdentity).get(0)).equals("a"),"Group identity includes you/hidden members or changes ordering");
        java.util.List<android.graphics.Bitmap> faceColors=new java.util.ArrayList<>();for(int color:new int[]{0xffff0000,0xff00ff00,0xff0000ff}){android.graphics.Bitmap face=android.graphics.Bitmap.createBitmap(80,80,android.graphics.Bitmap.Config.ARGB_8888);face.eraseColor(color);faceColors.add(face);}
        android.graphics.Bitmap collage=Avatars.group(faceColors,java.util.Arrays.asList("A","B","C"),java.util.Arrays.asList(0xffff0000,0xff00ff00,0xff0000ff),5,0xff334766,252);
        android.graphics.Bitmap adaptiveGroup=Avatars.adaptive(collage,"",0xff334766,252);int visible=Math.round(252*.68f),inset=(252-visible)/2;
        check(adaptiveGroup.getPixel(126,inset+Math.round(visible*.28f))==0xffff0000
            &&adaptiveGroup.getPixel(inset+Math.round(visible*.28f),inset+Math.round(visible*.61f))==0xff00ff00
            &&adaptiveGroup.getPixel(inset+Math.round(visible*.72f),inset+Math.round(visible*.61f))==0xff0000ff,"A group bubble lost or stretched a participant photo inside its safe area");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"group-bubble-preview.png"))){adaptiveGroup.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        check(shown!=null&&shown.getWidth()>=840&&shown.getHeight()>=1260&&shown.getWidth()<=1500,"Photo decoded smaller than its box: "+(shown==null?"none":shown.getWidth()+"x"+shown.getHeight()));
        // A 2-second recording already on the phone for the voice message you sent, so its waveform and length are worked out.
        {short[] tone=new short[16000*2];for(int i=0;i<tone.length;i++)tone[i]=(short)Math.round(9000*Math.sin(2*Math.PI*440*i/16000.0)*(0.3+0.7*Math.abs(Math.sin(i/4000.0))));
            java.io.ByteArrayOutputStream wav=new java.io.ByteArrayOutputStream();java.nio.ByteBuffer h=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt(36+tone.length*2).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16)
             .put("data".getBytes()).putInt(tone.length*2);wav.write(h.array());java.nio.ByteBuffer body=java.nio.ByteBuffer.allocate(tone.length*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);for(short t:tone)body.putShort(t);wav.write(body.array());
            java.io.File voice=MediaCache.file(getTargetContext(),new JSONObject("{mediaID:'aud2',mimeType:'audio/mp4',mediaName:'Voice message.m4a',size:30000}"),false);voice.getParentFile().mkdirs();
            java.nio.file.Files.write(voice.toPath(),wav.toByteArray());}
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
        // Voice messages: a play button (no emoji), and once the audio is on the phone, its length from the waveform.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Play voice message",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(found.size()>=2,"Voice messages have no play button ("+found.size()+")");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"🎤",android.view.View.FIND_VIEWS_WITH_TEXT);check(found.isEmpty(),"A voice message still shows a microphone emoji");found.clear();
        boolean measured=false;for(int wait=0;wait<50&&!measured;wait++){found.clear();runOnMainSync(()->{});
            bubble.getWindow().getDecorView().findViewsWithText(found,"Play voice message, 0:02",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);measured=!found.isEmpty();if(!measured)Thread.sleep(100);}
        check(measured,"A voice message on the phone didn't get its length (0:02) from its waveform");found.clear();
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
        // A new attachment is saved outside cache even with no signed-in connection.
        java.io.File pic=new java.io.File(getTargetContext().getCacheDir(),"smoke-send.jpg");
        {android.graphics.Bitmap bm=android.graphics.Bitmap.createBitmap(64,48,android.graphics.Bitmap.Config.ARGB_8888);bm.eraseColor(0xff3366cc);
            try(java.io.FileOutputStream fo=new java.io.FileOutputStream(pic)){bm.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,fo);}}
        MediaOutbox savedMedia=new MediaOutbox(getTargetContext());savedMedia.clear();
        JSONObject smokeChat;try(PairedStore store=new PairedStore(getTargetContext())){smokeChat=store.chat("smoke");}
        java.lang.reflect.Field stripF=PairedActivity.class.getDeclaredField("attachScroll"),pendingF=PairedActivity.class.getDeclaredField("pendingMedia");stripF.setAccessible(true);pendingF.setAccessible(true);
        runOnMainSync(()->{try{Class<?> oc=Class.forName(PairedActivity.class.getName()+"$Outgoing");java.lang.reflect.Constructor<?> k=oc.getDeclaredConstructor(java.io.File.class,String.class,String.class);k.setAccessible(true);
            java.lang.reflect.Field og=PairedActivity.class.getDeclaredField("outgoing");og.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.List<Object> strip=(java.util.List<Object>)og.get(bubble);strip.add(k.newInstance(pic,"smoke-send.jpg","image/jpeg"));
            java.lang.reflect.Method ra=PairedActivity.class.getDeclaredMethod("renderAttachments");ra.setAccessible(true);ra.invoke(bubble);
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        found.clear();bubble.getWindow().getDecorView().findViewsWithText(found,"Photo quality: Standard",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(!found.isEmpty(),"Photo quality and prepared size are missing");found.clear();
        runOnMainSync(()->{try{java.lang.reflect.Method sa=PairedActivity.class.getDeclaredMethod("sendAttachments",JSONObject.class,String.class,String.class);sa.setAccessible(true);sa.invoke(bubble,smokeChat,"Synthetic attachment text","");}catch(Exception e){throw new RuntimeException(e);}});
        for(int wait=0;wait<80&&savedMedia.forChat("smoke").isEmpty();wait++){Thread.sleep(100);runOnMainSync(()->{});}waitForIdleSync();
        java.util.List<JSONObject> persisted=new MediaOutbox(getTargetContext()).forChat("smoke");check(persisted.size()==1,"The attachment wasn't saved durably");JSONObject job=persisted.get(0);String jid=job.optString("id");
        java.io.File durable=new java.io.File(job.optString("file"));check(durable.isFile()&&durable.getCanonicalPath().startsWith(getTargetContext().getFilesDir().getCanonicalPath()+"/"),"The queued attachment is still in evictable cache");
        check(!pic.exists()&&MediaOutbox.WAITING.equals(job.optString("state")),"The offline attachment wasn't queued safely");
        java.lang.reflect.Method render=PairedActivity.class.getDeclaredMethod("renderMessages",boolean.class);render.setAccessible(true);
        runOnMainSync(()->{try{render.invoke(bubble,false);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Waiting to reconnect • sends automatically",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Offline attachment doesn't say it's waiting to reconnect");found.clear();
        // The photo goes alone; its text waits until your phone has sent the photo, so it can't arrive first.
        long attempt=savedMedia.begin(jid,false).optLong("attempt");savedMedia.finish(jid,attempt,new JSONObject("{mediaTmpID:'photo-submission'}"));long nowUs=System.currentTimeMillis()*1000;
        check(!MediaOutbox.captionReady(savedMedia.get(jid),System.currentTimeMillis()),"The text would go before your phone sent the photo");
        runOnMainSync(()->{try{render.invoke(bubble,false);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Text will follow the attachment",android.view.View.FIND_VIEWS_WITH_TEXT);check(found.size()==1,"The waiting text isn't labelled ("+found.size()+")");found.clear();
        try(PairedStore store=new PairedStore(getTargetContext())){JSONObject imageEcho=new JSONObject("{messageID:'echo1',tmpID:'photo-submission',conversationID:'smoke',participantID:'me',timestamp:"+nowUs+",messageStatus:{status:'OUTGOING_DELIVERED'},messageInfo:[{mediaContent:{mediaID:'echo1-photo',mimeType:'image/jpeg',size:4000}}]}");store.message(imageEcho,0,true);savedMedia.reconcile(imageEcho);}
        check(MediaOutbox.DELIVERED.equals(savedMedia.get(jid).optString("state")),"Google's delivery state wasn't reflected");
        check(MediaOutbox.captionNext(savedMedia.next(),System.currentTimeMillis()),"The text didn't follow once the photo was sent");
        long textAttempt=savedMedia.begin(jid,true).optLong("attempt");savedMedia.captionAccepted(jid,textAttempt,"text-submission");
        try(PairedStore store=new PairedStore(getTargetContext())){JSONObject textEcho=new JSONObject("{messageID:'echo-text',tmpID:'text-submission',conversationID:'smoke',participantID:'me',timestamp:"+(nowUs+1000)+",messageStatus:{status:'OUTGOING_SENDING'},messageInfo:[{messageContent:{content:'Synthetic attachment text'}}]}");store.message(textEcho,0,true);savedMedia.reconcile(textEcho);}
        check("echo1".equals(savedMedia.get(jid).optString("mediaEcho")),"The text claimed the photo's copy");
        runOnMainSync(()->{try{render.invoke(bubble,false);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Synthetic attachment text",android.view.View.FIND_VIEWS_WITH_TEXT);check(found.size()==1,"The accompanying text is duplicated ("+found.size()+")");found.clear();
        savedMedia.retireCompleted();check(savedMedia.get(jid)==null&&!durable.exists(),"A completed attachment's saved files weren't released");
        try(PairedStore store=new PairedStore(getTargetContext())){store.deleteMessage("echo1");store.deleteMessage("echo-text");}
        runOnMainSync(()->{try{render.invoke(bubble,false);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        // Screenshots are allowed.
        check((bubble.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)==0,"Screenshots are blocked in chats");
        check((activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)==0,"Screenshots are blocked in the inbox");
        // (Last of the bubble's checks: a start the test stops still hands the bubble a result, which pauses and resumes it.)
        // The bubble's header has a button that opens this conversation in the full app.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Open in Message Center",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        check(!found.isEmpty()&&found.get(0).isShown(),"The bubble has no button to open the conversation in the app");
        Intent[] opened=new Intent[1];ActivityMonitor watchOpen=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){
            if(intent.getComponent()!=null&&intent.getComponent().getClassName().equals(PairedActivity.class.getName())){opened[0]=intent;return new ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};
        addMonitor(watchOpen);android.view.View openApp=found.get(0);runOnMainSync(openApp::performClick);waitForIdleSync();removeMonitor(watchOpen);found.clear();
        check(opened[0]!=null&&"smoke".equals(opened[0].getStringExtra("chat"))&&(opened[0].getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK)!=0,"The bubble's open button didn't open the app on this conversation ("+opened[0]+")");
        // Tapping ⊕ opens the attach panel with Photos & videos, Camera, and Files.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Attach photos, videos, or files",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        android.view.View plus=found.get(0);runOnMainSync(plus::performClick);waitForIdleSync();
        // The panel's window takes focus a moment later, from the window manager.
        for(int wait=0;wait<30&&bubble.hasWindowFocus();wait++)Thread.sleep(100);
        check(bubble.hasWindowFocus()==false,"Attach panel did not open");bubble.finish();waitForIdleSync();
        checkChatKeepsItsPlace();
        check(!PairedService.canReconnect(getTargetContext()),"Reconnect offered with no saved session to reconnect with");
        checkPinnedTap((PairedActivity)activity);
        checkInboxKeepsItsPlace((PairedActivity)activity);
        checkNewConversationSearch((PairedActivity)activity);
        checkPhotoPrep();
        checkPictureTextAndFollowUps();
        checkVideoPrep();
        checkArrivalTexts();
        checkMarkup((PairedActivity)activity);
        String openAndBack=measureOpenAndBack((PairedActivity)activity);
        String speed=measureRedraw();
        try(PairedStore store=new PairedStore(getTargetContext())){store.clear();}
        Outbox box=new Outbox(getTargetContext());for(JSONObject o:box.all())box.remove(o.optString("id"));
        new ChatSettings(getTargetContext()).setReceipts("smoke",false);new ReadReceiptQueue(getTargetContext()).cancel("smoke");
        getTargetContext().getSharedPreferences("transcripts",0).edit().clear().commit();getTargetContext().getSharedPreferences("phone_media",0).edit().clear().commit();
        for(JSONObject o:Scheduled.forChat(getTargetContext(),"smoke"))Scheduled.remove(getTargetContext(),o.optString("id"));
        getTargetContext().getSharedPreferences("starred",0).edit().clear().commit();
        new ChatSettings(getTargetContext()).setBackground("smoke","");
        checkNotificationPrivacy();
        checkContactText((PairedActivity)activity);
        checkContactPickerDeduplicates();
        checkPinsAndWhoIsThis((PairedActivity)activity);
        checkEmojiPicker((PairedActivity)activity);
        checkNotes((PairedActivity)activity);
        checkChatMenu((PairedActivity)activity);
        checkInboxMenu((PairedActivity)activity);
        checkDialogLook((PairedActivity)activity);
        checkSettingsPages((PairedActivity)activity);
        checkSendWhileLoading((PairedActivity)activity);
        checkLockScreenPreviews((PairedActivity)activity);
        checkLockedNoRedrawLoop();
        checkHoldLink((PairedActivity)activity);
        checkTimeSwipe((PairedActivity)activity);
        checkPhotoMechanics((PairedActivity)activity);
        checkLinkPreviewSteady((PairedActivity)activity);
        checkCustomTheme((PairedActivity)activity);
        checkUnreadCount((PairedActivity)activity);
        checkCallLines((PairedActivity)activity);
        checkThemeAll((PairedActivity)activity);
        checkMusicLink((PairedActivity)activity);
        checkTheirMusic((PairedActivity)activity);
        checkGifSurvivesRedraw((PairedActivity)activity);
        checkSentPhotoKept();
        checkSnoozeUntilQuiet();
        checkSentTextStays((PairedActivity)activity);
        checkBubbleStepsAside((PairedActivity)activity);
        result.putString("stream","MESSAGE_CENTER_SMOKE_PASS: SPEED "+openAndBack+" END_SPEED, sent text stays put, contact as text, contacts listed once, pins and who's this, emoji picker tabs and search, notes, chat menu sheet, inbox menu sheet, dialog look, settings pages, send while loading, lock screen previews, hold a link, gifs keep playing, sent photos kept, snooze until quiet, bubble steps aside in its open chat, notification privacy, "+speed+", JNI, Keystore, history conflict, ID deduplication, activity launch, bubble chat render with attachments, failed retry, outbox, link, voice transcript, search, code chip, starred, scheduled, preview-only media, location, group events, photo fetch state, placeholder previews, screenshots allowed, backup, addresses, chat background, smart taps, scroll anchoring (chat + inbox), message effects and invisible ink, shared photos/links/files, contact search, voice message cards, sending photos (sized to fit), sending videos (compressed, upright, mono for MMS), iPhone stickers and Genmoji (4:4:4 + monochrome, sandboxed)\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){StringBuilder where=new StringBuilder();for(StackTraceElement f:e.getStackTrace())if(f.getClassName().contains("OnDeviceSmoke")&&where.length()<200)where.append(" at ").append(f.getMethodName()).append(':').append(f.getLineNumber());
        result.putString("stream","MESSAGE_CENTER_SMOKE_FAIL: "+e.getClass().getSimpleName()+": "+e.getMessage()+where+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
