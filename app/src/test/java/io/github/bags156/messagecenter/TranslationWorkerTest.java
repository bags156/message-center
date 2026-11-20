package io.github.bags156.messagecenter;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class TranslationWorkerTest {
    /** What you're waiting on goes ahead of everything queued; the rest keep their order. */
    @Test public void urgentGoesFirstAndTheRestKeepTheirOrder() throws Exception {
        TranslationWorker w = new TranslationWorker("test-translation");
        CountDownLatch hold = new CountDownLatch(1);
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        w.execute(() -> { try { hold.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {} ran.add("running"); });
        Thread.sleep(100);
        w.execute(() -> ran.add("received 1"));
        w.execute(() -> ran.add("received 2"));
        w.urgent(() -> ran.add("sending"));
        assertTrue("an urgent job is waiting", w.urgentWaiting());
        hold.countDown();
        w.submit(() -> null).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("running", "sending", "received 1", "received 2"), ran);
        assertFalse("nothing urgent is left", w.urgentWaiting());
        w.shutdown();
    }

    /** A job already running sees that something urgent is waiting, so it can stop for it. */
    @Test public void aRunningJobSeesSomethingUrgentWaiting() throws Exception {
        TranslationWorker w = new TranslationWorker("test-translation");
        CountDownLatch started = new CountDownLatch(1);
        boolean[] saw = {false};
        w.execute(() -> { started.countDown(); long until = System.currentTimeMillis() + 3000; while (System.currentTimeMillis() < until) { if (w.urgentWaiting()) { saw[0] = true; return; } } });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        w.urgent(() -> {});
        w.submit(() -> null).get(5, TimeUnit.SECONDS);
        assertTrue("the running job saw the urgent one waiting", saw[0]);
        w.shutdown();
    }

    /** submit and get still work, as the device checks use them. */
    @Test public void submitReturnsItsResult() throws Exception {
        TranslationWorker w = new TranslationWorker("test-translation");
        assertEquals("done", w.submit(() -> "done").get(5, TimeUnit.SECONDS));
        w.shutdown();
    }
}
