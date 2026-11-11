package io.github.bags156.messagecenter;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaOutboxTest {
    static class Memory implements MediaOutbox.Storage{String value="[]";public String read(){return value;}public void write(String s){value=s;}}
    private JSONObject job()throws Exception{return new JSONObject("{id:'job',chat:'chat',name:'synthetic.jpg',mime:'image/jpeg',file:'/synthetic',caption:'Synthetic caption',reply:'original',state:'waiting',captionState:'pending',attempt:0,created:10000}");}
    @Test public void queuedAndUnsubmittedPreparationSurviveRestart()throws Exception{
        Memory disk=new Memory();MediaOutbox q=new MediaOutbox(disk);q.record(job());q.begin("job",false);MediaOutbox reopened=new MediaOutbox(disk);reopened.recover();
        assertNotNull(reopened.next());assertEquals(MediaOutbox.WAITING,reopened.get("job").optString("state"));
    }
    @Test public void bridgeInterruptionDuringSubmissionNeverAutomaticallyDuplicatesAMessage()throws Exception{
        Memory disk=new Memory();MediaOutbox q=new MediaOutbox(disk);q.record(job());long attempt=q.begin("job",false).optLong("attempt");q.bridgeStarting("job",attempt);q.progress("job",attempt,MediaOutbox.UPLOADING,-1,"");
        q.progress("job",attempt,MediaOutbox.SUBMITTING,-1,"submit-id");
        q.recover();assertNull(q.next());assertEquals(MediaOutbox.UNSURE,q.get("job").optString("state"));
        q.finish("job",attempt,new JSONObject("{mediaTmpID:'late'}"));assertEquals("",q.get("job").optString("mediaTmpID"));
    }
    @Test public void anUploadThatNeverReachedSubmissionStartsAgainOnItsOwn()throws Exception{
        Memory disk=new Memory();MediaOutbox q=new MediaOutbox(disk);q.record(job());long attempt=q.begin("job",false).optLong("attempt");q.bridgeStarting("job",attempt);q.progress("job",attempt,MediaOutbox.UPLOADING,-1,"");
        MediaOutbox reopened=new MediaOutbox(disk);reopened.recover();
        assertEquals(MediaOutbox.WAITING,reopened.get("job").optString("state"));JSONObject again=reopened.next();assertNotNull(again);assertFalse(MediaOutbox.captionNext(again,System.currentTimeMillis()));
        // The old attempt finishing late changes nothing.
        reopened.finish("job",attempt,new JSONObject("{mediaTmpID:'late'}"));assertEquals("",reopened.get("job").optString("mediaTmpID"));
    }
    @Test public void retryingOnlyTextPreservesTheAlreadyAcceptedPhoto()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job",false).optLong("attempt");q.finish("job",a,new JSONObject("{mediaTmpID:'photo'}"));
        q.reconcile(new JSONObject("{messageID:'real',tmpID:'photo',conversationID:'chat',messageStatus:{status:'OUTGOING_COMPLETE'}}"));
        JSONObject text=q.next();assertTrue(MediaOutbox.captionNext(text,System.currentTimeMillis()));
        long b=q.begin("job",true).optLong("attempt");q.failed("job",b,"Send outcome unknown. Check the conversation in Google Messages before retrying.",true);
        assertNull(q.next());assertEquals(MediaOutbox.UNSURE,q.get("job").optString("captionState"));assertEquals(MediaOutbox.SENT,q.get("job").optString("state"));
        q.retry("job",true);JSONObject retry=q.next();assertEquals("photo",retry.optString("mediaTmpID"));assertTrue(MediaOutbox.captionNext(retry,System.currentTimeMillis()));
        long c=q.begin("job",true).optLong("attempt");assertEquals(MediaOutbox.SENT,q.get("job").optString("state"));
        q.captionAccepted("job",c,"text-only");assertNull(q.next());assertEquals("photo",q.get("job").optString("mediaTmpID"));
    }
    @Test public void textWaitsUntilYourPhoneHasSentTheAttachment()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job",false).optLong("attempt");q.finish("job",a,new JSONObject("{mediaTmpID:'photo'}"));long now=System.currentTimeMillis();
        assertNull("text went before the phone sent its photo",q.next(now));assertTrue(MediaOutbox.captionWaiting(q.get("job")));
        assertTrue(q.reconcile(new JSONObject("{messageID:'real',tmpID:'photo',conversationID:'chat',messageStatus:{status:'OUTGOING_SENDING'}}")));
        assertNull(q.next(now));
        // The status update comes under the real ID only.
        assertTrue(q.reconcile(new JSONObject("{messageID:'real',conversationID:'chat',messageStatus:{status:'OUTGOING_COMPLETE'}}")));
        assertEquals(MediaOutbox.SENT,q.get("job").optString("state"));JSONObject next=q.next(now);assertNotNull(next);assertTrue(MediaOutbox.captionNext(next,now));
    }
    @Test public void textGoesAnywayWhenYourPhoneNeverReportsBack()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job",false).optLong("attempt");q.finish("job",a,new JSONObject("{mediaTmpID:'photo'}"));
        long accepted=q.get("job").optLong("acceptedAt");assertTrue(accepted>0);
        assertNull(q.next(accepted+MediaOutbox.CAPTION_UNTRACKED_MS-1000));assertNotNull(q.next(accepted+MediaOutbox.CAPTION_UNTRACKED_MS));
        assertEquals(accepted+MediaOutbox.CAPTION_UNTRACKED_MS,MediaOutbox.captionDue(q.get("job")));
        // Google shows it still sending: give your phone longer.
        q.reconcile(new JSONObject("{messageID:'real',tmpID:'photo',conversationID:'chat',messageStatus:{status:'OUTGOING_SENDING'}}"));
        assertNull(q.next(accepted+MediaOutbox.CAPTION_UNTRACKED_MS));assertNotNull(q.next(accepted+MediaOutbox.CAPTION_STILL_SENDING_MS));
    }
    @Test public void textWaitsForYouWhenItsAttachmentFailed()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job",false).optLong("attempt");q.finish("job",a,new JSONObject("{mediaTmpID:'photo'}"));
        q.reconcile(new JSONObject("{messageID:'real',tmpID:'photo',conversationID:'chat',messageStatus:{status:'OUTGOING_FAILED_GENERIC'}}"));
        assertEquals(MediaOutbox.FAILED,q.get("job").optString("state"));assertNull(q.next(System.currentTimeMillis()+MediaOutbox.CAPTION_STILL_SENDING_MS*2));
        assertTrue(MediaOutbox.captionLabel(q.get("job")).contains("Tap to send it now"));
        q.retry("job",true);JSONObject text=q.next();assertTrue(MediaOutbox.captionNext(text,System.currentTimeMillis()));
        long b=q.begin("job",true).optLong("attempt");assertEquals("the photo was resent with its text",MediaOutbox.FAILED,q.get("job").optString("state"));
        q.captionAccepted("job",b,"text");assertEquals(MediaOutbox.ACCEPTED,q.get("job").optString("captionState"));
    }
    @Test public void aRestartKeepsUntriedTextWaitingRatherThanUnsure()throws Exception{
        Memory disk=new Memory();MediaOutbox q=new MediaOutbox(disk);q.record(job());long a=q.begin("job",false).optLong("attempt");q.finish("job",a,new JSONObject("{mediaTmpID:'photo'}"));
        MediaOutbox reopened=new MediaOutbox(disk);reopened.recover();assertTrue(MediaOutbox.captionWaiting(reopened.get("job")));assertEquals(MediaOutbox.ACCEPTED,reopened.get("job").optString("state"));
    }
    @Test public void progressCannotOverwriteFinalAcceptanceAndIdsNeverMatchByText()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job",false).optLong("attempt");q.finish("job",a,new JSONObject("{mediaTmpID:'photo',captionTmpID:'caption'}"));
        q.progress("job",a,MediaOutbox.UPLOADING,-1,"");assertEquals(MediaOutbox.ACCEPTED,q.get("job").optString("state"));
        assertFalse(q.reconcile(new JSONObject("{messageID:'other',tmpID:'unrelated',conversationID:'chat',messageStatus:{status:'OUTGOING_COMPLETE'}}")));
        assertTrue(q.reconcile(new JSONObject("{messageID:'real',tmpID:'photo',conversationID:'chat',messageStatus:{status:'OUTGOING_DELIVERED'}}")));
        assertEquals("real",q.get("job").optString("mediaEcho"));assertEquals(MediaOutbox.DELIVERED,q.get("job").optString("state"));
    }
    @Test public void clearingQueueRejectsAnOldCompletion()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job",false).optLong("attempt");q.clear();q.finish("job",a,new JSONObject("{mediaTmpID:'late'}"));assertTrue(q.all().isEmpty());
    }
    @Test public void aKnownUploadFailureRemainsFailedAfterRestart()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job",false).optLong("attempt");q.bridgeStarting("job",a);q.failed("job",a,"The attachment could not be uploaded.");q.recover();
        assertEquals(MediaOutbox.FAILED,q.get("job").optString("state"));assertNull(q.next());
    }
    @Test public void aReidentifiedEchoCanUseAnExactProviderAlias()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job",false).optLong("attempt");q.progress("job",a,MediaOutbox.SUBMITTING,-1,"submit-id");
        assertTrue(q.reconcile(new JSONObject("{messageID:'real-id',conversationID:'chat',anotherMessageID:{messageID:'submit-id'},messageStatus:{status:'OUTGOING_COMPLETE'}}")));
        assertEquals("real-id",q.get("job").optString("mediaEcho"));
    }
}
