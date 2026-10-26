import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;

/** Probe: can uid 0 read cross-display accessibility node trees and set text (incl. CJK)? */
public final class UiaProbe {
    static long T0 = SystemClock.elapsedRealtime();
    static void log(String s) {
        System.out.println("[uia +" + (SystemClock.elapsedRealtime() - T0) + "ms] " + s);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getCtx = at.getDeclaredMethod("getSystemContext");
        getCtx.setAccessible(true);
        final Context ctx = (Context) getCtx.invoke(thread);

        HandlerThread ht = new HandlerThread("uia");
        ht.start();
        final Handler h = new Handler(ht.getLooper());
        h.post(() -> {
            try { run(ctx, h); } catch (Throwable t) { t.printStackTrace(); }
            System.out.println("PROBE_DONE");
            System.out.flush();
        });
        Thread.sleep(25000);
        System.exit(0);
    }

    static void run(Context ctx, Handler h) throws Exception {
        Class<?> uac = Class.forName("android.app.UiAutomation");
        for (java.lang.reflect.Constructor<?> c : uac.getDeclaredConstructors()) {
            log("ctor: " + c);
        }
        for (Method m : uac.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("window") || m.getName().contains("connect")) log("method: " + m);
        }
        Object ua = null;
        // preferred path: ActivityThread.getInstrumentation().getUiAutomation(flags)
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method getInstr = at.getDeclaredMethod("getInstrumentation");
            getInstr.setAccessible(true);
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object instr = getInstr.invoke(thread);
            log("instrumentation=" + instr);
            Method getUa = Class.forName("android.app.Instrumentation").getDeclaredMethod("getUiAutomation", int.class);
            getUa.setAccessible(true);
            ua = getUa.invoke(instr, 1);
            log("getUiAutomation -> " + ua);
        } catch (Throwable t) {
            log("instrumentation path failed: " + t);
        }
        if (ua == null) {
            for (java.lang.reflect.Constructor<?> c : uac.getDeclaredConstructors()) {
                try {
                    c.setAccessible(true);
                    Object[] a = new Object[c.getParameterCount()];
                    boolean ok = true;
                    Class<?>[] pt = c.getParameterTypes();
                    for (int i = 0; i < pt.length; i++) {
                        if (pt[i] == Looper.class) a[i] = h.getLooper();
                        else if (pt[i] == boolean.class) a[i] = false;
                        else if (pt[i] == int.class) a[i] = 0;
                        else ok = false;
                    }
                    if (!ok) continue;
                    ua = c.newInstance(a);
                    log("constructed via " + c);
                    break;
                } catch (Throwable t) {
                    log("ctor " + c + " failed: " + t);
                }
            }
        }
        if (ua == null) { log("NO_UIAUTOMATION"); return; }
        Method connect = uac.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        long t = SystemClock.elapsedRealtime();
        Object ok = connect.invoke(ua, 1); // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        log("UiAutomation.connect -> " + ok + " in " + (SystemClock.elapsedRealtime() - t) + "ms");

        Method all = uac.getDeclaredMethod("getWindowsOnAllDisplays");
        all.setAccessible(true);
        SparseArray<?> wins = (SparseArray<?>) all.invoke(ua);
        log("windowsOnAllDisplays displays=" + (wins == null ? -1 : wins.size()));
        if (wins != null) {
            for (int i = 0; i < wins.size(); i++) {
                int did = wins.keyAt(i);
                List<?> list = (List<?>) wins.valueAt(i);
                log("display " + did + " windows=" + (list == null ? 0 : list.size()));
                if (list == null) continue;
                for (Object o : list) {
                    AccessibilityWindowInfo w = (AccessibilityWindowInfo) o;
                    log("  win id=" + w.getId() + " type=" + w.getType() + " title=" + w.getTitle()
                            + " active=" + w.isActive() + " focused=" + w.isFocused());
                }
            }
            // dump first display that is not 0 if present, else 0
            int target = wins.size() > 0 ? wins.keyAt(0) : 0;
            for (int i = 0; i < wins.size(); i++) {
                if (wins.keyAt(i) != 0) { target = wins.keyAt(i); break; }
            }
            List<?> list = (List<?>) wins.get(target);
            log("dumping display " + target);
            if (list != null) {
                for (Object o : list) {
                    AccessibilityWindowInfo w = (AccessibilityWindowInfo) o;
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) { log("  root null for win " + w.getId()); continue; }
                    dumpTree(root, 0, 40, target);
                    trySetText(root, "中文测试 2080Ti", target);
                }
            }
        }
    }

    static void dumpTree(AccessibilityNodeInfo root, int depth, int limit, int did) {
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{root, 0});
        int n = 0;
        while (!q.isEmpty() && n < limit) {
            Object[] cur = q.poll();
            AccessibilityNodeInfo node = (AccessibilityNodeInfo) cur[0];
            int d = (Integer) cur[1];
            if (d > depth + 6) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("  ".repeat(Math.min(d, 8)));
            sb.append(node.getClassName()).append(" text=").append(node.getText())
              .append(" desc=").append(node.getContentDescription())
              .append(" edit=").append(node.isEditable())
              .append(" click=").append(node.isClickable());
            android.graphics.Rect r = new android.graphics.Rect();
            node.getBoundsInScreen(r);
            sb.append(" bounds=").append(r.left).append(',').append(r.top).append(',')
              .append(r.right).append(',').append(r.bottom);
            System.out.println("[node] " + sb);
            n++;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo ch = node.getChild(i);
                if (ch != null) q.add(new Object[]{ch, d + 1});
            }
        }
        log("dumped " + n + " nodes for display " + did);
    }

    static void trySetText(AccessibilityNodeInfo root, String text, int did) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo node = q.poll();
            if (node.isEditable()) {
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                long t = SystemClock.elapsedRealtime();
                boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                log("setText on editable(" + node.getClassName() + ") -> " + ok + " in "
                        + (SystemClock.elapsedRealtime() - t) + "ms, text now=" + node.getText());
                return;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo ch = node.getChild(i);
                if (ch != null) q.add(ch);
            }
        }
        log("no editable node found on display " + did);
    }
}
