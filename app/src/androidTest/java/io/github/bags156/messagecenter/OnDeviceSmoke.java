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
            check(store.message(message,0,true),"Message ID did not deduplicate");store.clear();
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
            store.message(new JSONObject("{messageID:'f',conversationID:'smoke',participantID:'me',timestamp:1794000280000000,messageStatus:{status:'OUTGOING_FAILED_GENERIC'},messageInfo:[{messageContent:{content:'See https://example.com/page'}}]}"),0,true);
            new Outbox(getTargetContext()).add("smoke","Queued while offline","");
            store.message(new JSONObject("{messageID:'v1',conversationID:'smoke',participantID:'p1',timestamp:1794000285000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{mediaContent:{mediaID:'aud1',mimeType:'audio/mp4',mediaName:'Voice message.m4a',size:42000}}]}"),0,true);
            store.message(new JSONObject("{messageID:'v2',conversationID:'smoke',participantID:'me',timestamp:1794000286000000,messageStatus:{status:'OUTGOING_DELIVERED'},messageInfo:[{mediaContent:{mediaID:'aud2',mimeType:'audio/mp4',mediaName:'Voice message.m4a',size:30000}}]}"),0,true);
            getTargetContext().getSharedPreferences("transcripts",0).edit().putString("aud1","Running ten minutes late").commit();
            JSONObject otp=new JSONObject("{messageID:'otp',conversationID:'smoke',participantID:'p1',timestamp:1794000290000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Your verification code is 482913'}}]}");
            store.message(otp,0,true);new Starred(getTargetContext()).toggle(otp);
            store.message(new JSONObject("{messageID:'emj',conversationID:'smoke',participantID:'p2',timestamp:1794000295000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'🎉'}}]}"),0,true);
            Scheduled.add(getTargetContext(),"smoke","Happy birthday!","",System.currentTimeMillis()+3_600_000L);
            check(!store.searchAll("verification",10).isEmpty(),"Search across chats did not find a message");
            store.message(new JSONObject("{messageID:'d',conversationID:'smoke',participantID:'p1',timestamp:1794000300000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Replied to a message:\\nSure'}}]}"),0,true);
        }
        // A portrait photo shown in a portrait box must be decoded at least as large as the box (never stretched).
        java.io.File photo=new java.io.File(MediaCache.dir(getTargetContext()),"smoke-portrait.jpg");
        android.graphics.Bitmap source=android.graphics.Bitmap.createBitmap(1500,2000,android.graphics.Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(photo)){source.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);}
        android.graphics.Bitmap shown=MediaCache.decodeToCover(photo,840,1260,false);photo.delete();
        check(shown!=null&&shown.getWidth()>=840&&shown.getHeight()>=1260&&shown.getWidth()<=1500,"Photo decoded smaller than its box: "+(shown==null?"none":shown.getWidth()+"x"+shown.getHeight()));
        Activity bubble=startActivitySync(new Intent(getTargetContext(),BubbleActivity.class).setAction(Intent.ACTION_VIEW).putExtra("chat","smoke").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();check(bubble!=null&&!bubble.isFinishing(),"Bubble chat did not render");
        // The message box must offer attachments (⊕) and, while empty, voice recording.
        java.util.ArrayList<android.view.View> found=new java.util.ArrayList<>();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Attach photos, videos, or files",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        check(!found.isEmpty()&&found.get(0).isShown(),"Attach button is missing from the message box");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Record voice message",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        check(!found.isEmpty(),"Voice message button is missing from the empty message box");found.clear();
        // Verification codes get a copy chip; starred and scheduled messages are marked.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Copy code 482913",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(!found.isEmpty(),"Copy code chip is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Starred",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Starred marker is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Scheduled for",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Scheduled message is missing");found.clear();
        // Tapping ⊕ opens the attach panel with Photos & videos, Camera, and Files.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Attach photos, videos, or files",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        android.view.View plus=found.get(0);runOnMainSync(plus::performClick);waitForIdleSync();
        check(bubble.hasWindowFocus()==false,"Attach panel did not open");bubble.finish();waitForIdleSync();
        try(PairedStore store=new PairedStore(getTargetContext())){store.clear();}
        Outbox box=new Outbox(getTargetContext());for(JSONObject o:box.all())box.remove(o.optString("id"));
        getTargetContext().getSharedPreferences("transcripts",0).edit().clear().commit();
        for(JSONObject o:Scheduled.forChat(getTargetContext(),"smoke"))Scheduled.remove(getTargetContext(),o.optString("id"));
        getTargetContext().getSharedPreferences("starred",0).edit().clear().commit();
        result.putString("stream","MESSAGE_CENTER_SMOKE_PASS: JNI, Keystore, history conflict, ID deduplication, activity launch, bubble chat render with attachments, failed retry, outbox, link, voice transcript, search, code chip, starred, scheduled\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","MESSAGE_CENTER_SMOKE_FAIL: "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
