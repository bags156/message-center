package io.github.bags156.messagecenter;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaOutboxTest {
    static class Memory implements MediaOutbox.Storage{String value="[]";public String read(){return value;}public void write(String s){value=s;}}
    private JSONObject job()throws Exception{return new JSONObject("{id:'job',chat:'chat',name:'synthetic.jpg',mime:'image/jpeg',file:'/synthetic',caption:'Synthetic caption',reply:'original',state:'waiting',captionState:'pending',attempt:0,created:10000}");}
    @Test public void queuedAndUnsubmittedPreparationSurviveRestart()throws Exception{
        Memory disk=new Memory();MediaOutbox q=new MediaOutbox(disk);q.record(job());q.begin("job");MediaOutbox reopened=new MediaOutbox(disk);reopened.recover();
        assertNotNull(reopened.next());assertEquals(MediaOutbox.WAITING,reopened.get("job").optString("state"));
    }
    @Test public void bridgeInterruptionNeverAutomaticallyDuplicatesAMessage()throws Exception{
        Memory disk=new Memory();MediaOutbox q=new MediaOutbox(disk);q.record(job());long attempt=q.begin("job").optLong("attempt");q.bridgeStarting("job",attempt);q.progress("job",attempt,MediaOutbox.UPLOADING,-1,"");
        q.recover();assertNull(q.next());assertEquals(MediaOutbox.UNSURE,q.get("job").optString("state"));
        q.finish("job",attempt,new JSONObject("{mediaTmpID:'late'}"));assertEquals("",q.get("job").optString("mediaTmpID"));
    }
    @Test public void retryingOnlyTextPreservesTheAlreadyAcceptedPhoto()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job").optLong("attempt");q.finish("job",a,new JSONObject("{mediaTmpID:'photo',captionError:'Unconfirmed',captionUncertain:true}"));
        assertNull(q.next());assertEquals(MediaOutbox.UNSURE,q.get("job").optString("captionState"));q.retry("job",true);
        JSONObject retry=q.next();assertEquals("photo",retry.optString("mediaTmpID"));assertFalse(MediaOutbox.sendCaption(retry));
        long b=q.begin("job").optLong("attempt");q.captionAccepted("job",b,"text-only");assertNull(q.next());assertEquals("photo",q.get("job").optString("mediaTmpID"));
    }
    @Test public void progressCannotOverwriteFinalAcceptanceAndIdsNeverMatchByText()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job").optLong("attempt");q.finish("job",a,new JSONObject("{mediaTmpID:'photo',captionTmpID:'caption'}"));
        q.progress("job",a,MediaOutbox.UPLOADING,-1,"");assertEquals(MediaOutbox.ACCEPTED,q.get("job").optString("state"));
        assertFalse(q.reconcile(new JSONObject("{messageID:'other',tmpID:'unrelated',conversationID:'chat',messageStatus:{status:'OUTGOING_COMPLETE'}}")));
        assertTrue(q.reconcile(new JSONObject("{messageID:'real',tmpID:'photo',conversationID:'chat',messageStatus:{status:'OUTGOING_DELIVERED'}}")));
        assertEquals("real",q.get("job").optString("mediaEcho"));assertEquals(MediaOutbox.DELIVERED,q.get("job").optString("state"));
    }
    @Test public void clearingQueueRejectsAnOldCompletion()throws Exception{
        MediaOutbox q=new MediaOutbox(new Memory());q.record(job());long a=q.begin("job").optLong("attempt");q.clear();q.finish("job",a,new JSONObject("{mediaTmpID:'late'}"));assertTrue(q.all().isEmpty());
    }
}
