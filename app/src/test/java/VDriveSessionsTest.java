import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class VDriveSessionsTest {
    private final Map<String, FakeWorker> made = new LinkedHashMap<>();
    private final List<String> ids = new ArrayList<>();
    private VDrive.WorkerFactory original;
    private static class FakeWorker implements VDrive.Worker {
        volatile boolean acknowledgeClose = true;
        final CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        public JSONObject processLine(String request) {
            try {
            JSONObject json = new JSONObject(request);
            if (json.optString("cmd").equals("hold")) {
                entered.countDown();
                try { finish.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return new JSONObject().put("ok", true).put("displayId", 12);
            } catch (org.json.JSONException e) { throw new AssertionError(e); }
        }
        public boolean closeSession() { finish.countDown(); return acknowledgeClose; }
        public void keepAlive() { }
        public int displayId() { return 12; }
    }
    @Before public void setUp() {
        original = VDrive.factory;
        VDrive.factory = context -> {
            FakeWorker worker = new FakeWorker();
            made.put(ids.get(ids.size() - 1), worker);
            return worker;
        };
    }
    @After public void tearDown() throws Exception {
        for (FakeWorker worker : made.values()) worker.acknowledgeClose = true;
        for (String id : ids) send(id, "release_session");
        VDrive.factory = original;
    }
    private String open(String app) throws Exception {
        String id = UUID.randomUUID().toString(); ids.add(id);
        assertTrue(VDrive.processLine(new JSONObject().put("cmd", "launch").put("sessionId", id)
                .put("component", app).toString()).getBoolean("ok"));
        return id;
    }
    private JSONObject send(String id, String cmd) throws Exception {
        return VDrive.processLine(new JSONObject().put("cmd", cmd).put("sessionId", id).toString());
    }
    @Test public void lateLaunchCannotResurrectReleasedSession() throws Exception {
        String id = UUID.randomUUID().toString(); ids.add(id);
        assertTrue(send(id, "release_session").getBoolean("ok"));
        assertEquals("SESSION_CLOSED", VDrive.processLine(new JSONObject().put("cmd", "launch")
                .put("sessionId", id).put("component", "app.late").toString()).getString("code"));
        assertTrue(made.isEmpty());
    }
    @Test public void failedCleanupKeepsSlotAndRefusesFurtherActions() throws Exception {
        String first = open("app.one"); open("app.two"); open("app.three");
        made.get(first).acknowledgeClose = false;
        assertEquals("CLEANUP_PENDING", send(first, "release_session").getString("code"));
        assertEquals("SESSION_CLOSED", send(first, "tap").getString("code"));
        String fourth = UUID.randomUUID().toString(); ids.add(fourth);
        JSONObject request = new JSONObject().put("cmd", "launch").put("sessionId", fourth).put("component", "app.four");
        assertEquals("SESSION_LIMIT", VDrive.processLine(request.toString()).getString("code"));
        made.get(first).acknowledgeClose = true;
        assertTrue(send(first, "release_session").getBoolean("ok"));
        assertTrue(VDrive.processLine(request.toString()).getBoolean("ok"));
    }
    @Test public void blockedWorkerDoesNotBlockAnotherScreen() throws Exception {
        String a = open("app.a"), b = open("app.b");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<JSONObject> held = executor.submit(() -> send(a, "hold"));
            assertTrue(made.get(a).entered.await(1, TimeUnit.SECONDS));
            Future<JSONObject> other = executor.submit(() -> send(b, "tap"));
            assertTrue(other.get(1, TimeUnit.SECONDS).getBoolean("ok"));
            assertFalse(held.isDone());
            made.get(a).finish.countDown();
            assertTrue(held.get(1, TimeUnit.SECONDS).getBoolean("ok"));
        } finally { executor.shutdownNow(); }
    }
    @Test public void sameAppCannotBeOwnedByTwoSessions() throws Exception {
        open("app.shared");
        String id = UUID.randomUUID().toString(); ids.add(id);
        assertEquals("APP_BUSY", VDrive.processLine(new JSONObject().put("cmd", "launch")
                .put("sessionId", id).put("component", "app.shared").toString()).getString("code"));
    }
}
