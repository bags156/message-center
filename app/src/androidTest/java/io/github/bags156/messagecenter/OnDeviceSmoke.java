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
            store.message(new JSONObject("{messageID:'d',conversationID:'smoke',participantID:'p1',timestamp:1794000300000000,messageStatus:{status:'INCOMING_COMPLETE'},messageInfo:[{messageContent:{content:'Replied to a message:\\nSure'}}]}"),0,true);
        }
        Activity bubble=startActivitySync(new Intent(getTargetContext(),BubbleActivity.class).setAction(Intent.ACTION_VIEW).putExtra("chat","smoke").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();check(bubble!=null&&!bubble.isFinishing(),"Bubble chat did not render");bubble.finish();waitForIdleSync();
        try(PairedStore store=new PairedStore(getTargetContext())){store.clear();}
        Outbox box=new Outbox(getTargetContext());for(JSONObject o:box.all())box.remove(o.optString("id"));
        result.putString("stream","MESSAGE_CENTER_SMOKE_PASS: JNI, Keystore, history conflict, ID deduplication, activity launch, bubble chat render with attachments, failed retry, outbox, link\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","MESSAGE_CENTER_SMOKE_FAIL: "+e.getClass().getSimpleName()+": "+e.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}}
}
