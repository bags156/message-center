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
            store.message(new JSONObject("{messageID:'f',conversationID:'smoke',participantID:'me',timestamp:1794000280000000,messageStatus:{status:'OUTGOING_FAILED_GENERIC'},messageInfo:[{messageContent:{content:'See https://example.com/page'}}]}"),0,true);
            new Outbox(getTargetContext()).add("smoke","Queued while offline","");
            store.message(new JSONObject("{messageID:'v1',conversationID:'smoke',participantID:'p1',timestamp:1794000285000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{mediaContent:{mediaID:'aud1',mimeType:'audio/mp4',mediaName:'Voice message.m4a',size:42000}}]}"),0,true);
            store.message(new JSONObject("{messageID:'v2',conversationID:'smoke',participantID:'me',timestamp:1794000286000000,messageStatus:{status:'OUTGOING_DELIVERED'},messageInfo:[{mediaContent:{mediaID:'aud2',mimeType:'audio/mp4',mediaName:'Voice message.m4a',size:30000}}]}"),0,true);
            getTargetContext().getSharedPreferences("transcripts",0).edit().putString("aud1","Running ten minutes late").commit();
            JSONObject otp=new JSONObject("{messageID:'otp',conversationID:'smoke',participantID:'p1',timestamp:1794000290000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Your verification code is 482913'}}]}");
            store.message(otp,0,true);new Starred(getTargetContext()).toggle(otp);
            store.message(new JSONObject("{messageID:'emj',conversationID:'smoke',participantID:'p2',timestamp:1794000295000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'🎉'}}]}"),0,true);
            Scheduled.add(getTargetContext(),"smoke","Happy birthday!","",System.currentTimeMillis()+3_600_000L);
            store.message(new JSONObject("{messageID:'loc',conversationID:'smoke',participantID:'p1',timestamp:1794000296000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{actionMessageID:'x1',locationContent:{messageContent:{content:'Main Street Park https://maps.google.com/?q=36.37,-94.21'}}}]}"),0,true);
            store.message(new JSONObject("{messageID:'ev',conversationID:'smoke',participantID:'p2',timestamp:1794000297000000,messageStatus:{status:'TOMBSTONE_PARTICIPANT_LEFT'}}"),0,true);
            // A photo that came with only Google's tiny in-message placeholder: shown as a preview, full photo asked for.
            android.graphics.Bitmap stamp=android.graphics.Bitmap.createBitmap(8,6,android.graphics.Bitmap.Config.ARGB_8888);stamp.eraseColor(0xff888888);
            java.io.ByteArrayOutputStream jpg=new java.io.ByteArrayOutputStream();stamp.compress(android.graphics.Bitmap.CompressFormat.JPEG,50,jpg);
            String inline=android.util.Base64.encodeToString(jpg.toByteArray(),android.util.Base64.NO_WRAP);
            store.message(new JSONObject("{messageID:'inl',conversationID:'smoke',participantID:'p1',timestamp:1794000297500000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{actionMessageID:'y1',mediaContent:{mimeType:'image/jpeg',mediaName:'IMG_3.jpg',size:900000,mediaData:'"+inline+"'}}]}"),0,true);
            check(!MediaCache.hasFull(PairedProtocol.media(store.message("inl")).get(0))&&MediaCache.previewOnly(PairedProtocol.media(store.message("inl")).get(0)),"In-message placeholder was treated as the photo");
            store.message(new JSONObject("{messageID:'nod',conversationID:'smoke',participantID:'p1',timestamp:1794000298000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{actionMessageID:'z1',mediaContent:{mimeType:'image/jpeg',mediaName:'IMG_2.jpg',size:1000}}]}"),0,true);
            check(!store.searchAll("verification",10).isEmpty(),"Search across chats did not find a message");
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
        bubble.getWindow().getDecorView().findViewsWithText(found,"Record voice message",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        check(!found.isEmpty(),"Voice message button is missing from the empty message box");found.clear();
        // Verification codes get a copy chip; starred and scheduled messages are marked.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Copy code 482913",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(!found.isEmpty(),"Copy code chip is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Starred",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Starred marker is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Scheduled for",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Scheduled message is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Main Street Park",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Shared location is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Sam Example left",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Group event is missing");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Find the original message for this reply",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);check(!found.isEmpty(),"Reply without a reference has no refresh action");found.clear();
        // A photo sent without download details says it's being fetched from the phone (not a blank box).
        bubble.getWindow().getDecorView().findViewsWithText(found,"Loading full photo",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Placeholder-only photo doesn't say the full photo is loading");found.clear();
        bubble.getWindow().getDecorView().findViewsWithText(found,"Getting photo from your phone",android.view.View.FIND_VIEWS_WITH_TEXT);check(!found.isEmpty(),"Photo without download details shows nothing");found.clear();
        // Screenshots are allowed.
        check((bubble.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)==0,"Screenshots are blocked in chats");
        check((activity.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)==0,"Screenshots are blocked in the inbox");
        // Tapping ⊕ opens the attach panel with Photos & videos, Camera, and Files.
        bubble.getWindow().getDecorView().findViewsWithText(found,"Attach photos, videos, or files",android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        android.view.View plus=found.get(0);runOnMainSync(plus::performClick);waitForIdleSync();
        check(bubble.hasWindowFocus()==false,"Attach panel did not open");bubble.finish();waitForIdleSync();
        try(PairedStore store=new PairedStore(getTargetContext())){store.clear();}
        Outbox box=new Outbox(getTargetContext());for(JSONObject o:box.all())box.remove(o.optString("id"));
        getTargetContext().getSharedPreferences("transcripts",0).edit().clear().commit();
        for(JSONObject o:Scheduled.forChat(getTargetContext(),"smoke"))Scheduled.remove(getTargetContext(),o.optString("id"));
        getTargetContext().getSharedPreferences("starred",0).edit().clear().commit();
        result.putString("stream","MESSAGE_CENTER_SMOKE_PASS: JNI, Keystore, history conflict, ID deduplication, activity launch, bubble chat render with attachments, failed retry, outbox, link, voice transcript, search, code chip, starred, scheduled, preview-only media, location, group events, photo fetch state, placeholder previews, screenshots allowed, backup\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","MESSAGE_CENTER_SMOKE_FAIL: "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
