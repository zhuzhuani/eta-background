package com.eta.shadow;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, application = Application.class)
public class BootObserverPolicyTest {
    public static class Service extends AccessibilityService {
        public void onAccessibilityEvent(AccessibilityEvent event) { }
        public void onInterrupt() { }
    }
    private ServiceController<Service> controller;
    private Boot.RootQuery original;
    @Before public void setup() throws Exception {
        controller = Robolectric.buildService(Service.class).create();
        ReflectionHelpers.setStaticField(Boot.class, "service", controller.get());
        original = Boot.rootQuery;
        Boot.rootQuery = req -> new JSONObject().put("ok", true).put("geometry", new JSONObject()
            .put("displayId", 8).put("width", 400).put("height", 800).put("rotation", 0).put("density", 420)
            .put("coordinateSpace", "display").put("source", "root-display-manager"));
    }
    @After public void teardown() {
        Boot.rootQuery = original;
        ReflectionHelpers.setStaticField(Boot.class, "service", null);
        controller.destroy();
    }
    private JSONObject observe(String cmd) throws Exception {
        return ReflectionHelpers.callStaticMethod(Boot.class, "handle", ReflectionHelpers.ClassParameter.from(JSONObject.class,
            new JSONObject().put("cmd", cmd).put("displayId", 8).put("sessionId", "observer-test")));
    }
    private AccessibilityNodeInfo node(String text, boolean visible) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName("app.target"); node.setText(text); node.setVisibleToUser(visible);
        node.setBoundsInScreen(new Rect(0, 0, 100, 100));
        return node;
    }
    private AccessibilityWindowInfo window(AccessibilityNodeInfo root, int type) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ReflectionHelpers.setField(window, "mType", type); shadowOf(window).setRoot(root);
        return window;
    }
    @Test public void missingAccessibilityWindowIsPendingNotMissingRootDisplay() throws Exception {
        shadowOf(controller.get()).setWindowsOnDisplay(8, Collections.emptyList());
        shadowOf(controller.get()).setWindowsOnDisplay(0,
            Collections.singletonList(window(node("PRIVATE MAIN SCREEN", true), AccessibilityWindowInfo.TYPE_APPLICATION)));
        for (String cmd : Arrays.asList("dump", "list")) {
            JSONObject result = observe(cmd);
            assertTrue(result.getBoolean("ok"));
            assertEquals("UI_TREE_PENDING", result.getString("code"));
            assertEquals("pending", result.getString("treeStatus"));
            assertEquals(0, result.getInt("count"));
            assertEquals(0, result.getInt("nodeCount"));
            assertEquals(8, result.getJSONObject("geometry").getInt("displayId"));
            assertFalse(result.toString().contains("PRIVATE MAIN SCREEN"));
        }
    }
    @Test public void overlayAndInvisibleNodesCannotExhaustApplicationBudget() throws Exception {
        AccessibilityNodeInfo root = node("container", false);
        for (int i = 0; i < 270; i++) shadowOf(root).addChild(node("hidden", false));
        shadowOf(root).addChild(node("target control", true));
        shadowOf(controller.get()).setWindowsOnDisplay(8, Arrays.asList(
            window(node("overlay", true), AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY),
            window(root, AccessibilityWindowInfo.TYPE_APPLICATION)));
        JSONObject result = observe("dump");
        assertEquals("ready", result.getString("treeStatus"));
        assertEquals(1, result.getInt("count"));
        assertEquals("target control", result.getJSONArray("nodes").getJSONObject(0).getString("text"));
    }
    @Test public void spacedEmptyEvidenceIsScopedAndRechecked() {
        Boot.TreeObservationPolicy policy = new Boot.TreeObservationPolicy();
        assertEquals("pending", policy.observe("s:8", "window:app", 0, 0, false));
        assertEquals("pending", policy.observe("s:8", "window:app", 0, 10, false));
        assertEquals("pending", policy.observe("s:8", "window:app", 0, 500, false));
        assertEquals("unavailable", policy.observe("s:8", "window:app", 0, 1500, false));
        assertEquals("pending", policy.observe("other:8", "window:app", 0, 1600, false));
        assertEquals("pending", policy.observe("s:9", "window:app", 0, 1600, false));
        assertEquals("pending", policy.observe("s:8", "window:other-app", 0, 1600, false));
        assertEquals("ready", policy.observe("s:8", "window:other-app", 1, 1700, false));
        assertEquals("pending", policy.observe("s:8", "window:other-app", 0, 1800, false));
    }
    @Test public void missingWindowsNeverBecomePermanentUnavailableEvidence() {
        Boot.TreeObservationPolicy policy = new Boot.TreeObservationPolicy();
        assertEquals("pending", policy.observe("s:8", "", 0, 0, true));
        assertEquals("pending", policy.observe("s:8", "", 0, 750, true));
        assertEquals("pending", policy.observe("s:8", "", 0, 1500, true));
        assertEquals("ready", policy.observe("s:8", "app", 1, 1600, false));
    }
}
