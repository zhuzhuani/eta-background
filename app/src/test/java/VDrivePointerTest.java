import android.app.Application;
import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, application = Application.class)
public class VDrivePointerTest {
    static class Worker extends VDriveWorker {
        JSONObject observed;
        Worker(Context c) throws Exception { super(c); work.removeCallbacksAndMessages(null); displayId = 7; }
        @Override DisplayGeometry geometry(int did) { return new DisplayGeometry(3200, 1440, 0); }
        @Override boolean hasTaskOnDisplay() { return true; }
        @Override JSONObject proxyA11y(JSONObject req) { observed = req; return success(); }
        private JSONObject success() { try { return new JSONObject().put("ok", true); } catch(Exception e) { throw new AssertionError(e); } }
    }
    Worker worker;
    @Before public void setup() throws Exception { worker = new Worker(RuntimeEnvironment.getApplication()); }
    @After public void teardown() {
        if (worker != null) {
            worker.dm.unregisterDisplayListener(worker.geometryListener);
            worker.work.removeCallbacksAndMessages(null); worker.work.getLooper().quit();
        }
    }

    @Test public void normalTapNeverBecomesNodeLongClick() throws Exception {
        worker.tap(new JSONObject().put("x", 100).put("y", 100));
        assertEquals(0, worker.observed.getInt("holdMs"));
        worker.press(new JSONObject().put("x", 100).put("y", 100));
        assertEquals(800, worker.observed.getInt("holdMs"));
    }
    @Test public void screenshotCoordinatesMapToLogicalDisplayOnce() throws Exception {
        worker.lastScreenshot = new JSONObject().put("frameId", "7:frame").put("displayId", 7)
            .put("width", 1600).put("height", 720).put("geometry", worker.geometry(7).json());
        JSONObject request = new JSONObject().put("x", 1500).put("y", 650).put("coordinateSpace", "screenshot").put("frameId", "7:frame");
        assertNull(worker.preparePointerCoordinates(request));
        assertEquals(3000, request.getDouble("x"), 0.01);
        assertEquals(1300, request.getDouble("y"), 0.01);
    }
    @Test public void staleScreenshotAndRotatedNodesAreRejectedBeforeInjection() throws Exception {
        JSONObject request = new JSONObject().put("x", 100).put("y", 100).put("coordinateSpace", "screenshot").put("frameId", "old");
        assertEquals("STALE_OBSERVATION", worker.preparePointerCoordinates(request).getString("code"));
        request = new JSONObject().put("x", 100).put("y", 100).put("expectedGeometry", new DisplayGeometry(1440, 3200, 1).json());
        assertEquals("STALE_OBSERVATION", worker.preparePointerCoordinates(request).getString("code"));
        assertNull(worker.observed);
    }
    @Test public void rotationDuringNodeActionNeverFallsThroughToASecondTouch() throws Exception {
        JSONObject ambiguous = new JSONObject().put("ok", false).put("code", "GEOMETRY_CHANGED_DURING_OPERATION");
        assertSame(ambiguous, worker.stalePointerFallback(new JSONObject(), ambiguous, 7));
        JSONObject request = new JSONObject().put("expectedGeometry", new DisplayGeometry(1440, 3200, 1).json());
        assertEquals("STALE_OBSERVATION", worker.stalePointerFallback(request, null, 7).getString("code"));
        assertNull(worker.stalePointerFallback(new JSONObject(), new JSONObject().put("code", "NO_NODE"), 7));
    }
}
