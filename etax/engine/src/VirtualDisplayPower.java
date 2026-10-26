import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;

/** Keeps only an isolated virtual display awake. Never acquire a screen lock for display 0. */
final class VirtualDisplayPower {
    private final Context context;
    private PowerManager.WakeLock lock;
    private int boundDisplay = -1;
    private long renewedAt;
    volatile String state = "idle";

    VirtualDisplayPower(Context context) { this.context = context; }

    static boolean isIsolated(int displayId, int group, int mainGroup) {
        return displayId > 0 && group >= 0 && mainGroup >= 0 && group != mainGroup;
    }

    private static int group(Display display) {
        if (display == null) return -1;
        try {
            Class<?> type = Class.forName("android.view.DisplayInfo");
            Object info = type.getDeclaredConstructor().newInstance();
            if (!Boolean.TRUE.equals(Display.class.getMethod("getDisplayInfo", type).invoke(display, info))) return -1;
            return type.getField("displayGroupId").getInt(info);
        } catch (Exception ignored) { return -1; }
    }

    void update(int displayId, boolean running) {
        if (!running || displayId <= 0) { release(); return; }
        try {
            if (boundDisplay != displayId) release();
            if (lock == null) {
                PowerManager pm = context.getSystemService(PowerManager.class);
                DisplayManager dm = context.getSystemService(DisplayManager.class);
                if (isIsolated(displayId, group(dm.getDisplay(displayId)), group(dm.getDisplay(0)))) {
                    lock = (PowerManager.WakeLock) PowerManager.class
                        .getMethod("newWakeLock", int.class, String.class, int.class)
                        .invoke(pm, PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "etax:virtual-display", displayId);
                    state = "isolated_awake";
                } else {
                    // An OEM that ignores OWN_DISPLAY_GROUP must never cause us to wake the phone.
                    lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "etax:virtual-cpu");
                    state = "cpu_only_shared_group";
                }
                lock.setReferenceCounted(false);
                boundDisplay = displayId;
            }
            long now = SystemClock.elapsedRealtime();
            if (!lock.isHeld() || now - renewedAt >= 30000) {
                lock.acquire(90000);
                renewedAt = now;
            }
        } catch (Exception failure) {
            release();
            state = "display_power_unavailable";
            VDrive.log("virtual display power: " + failure.getClass().getSimpleName());
        }
    }

    void release() {
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
        boundDisplay = -1;
        renewedAt = 0;
        state = "idle";
    }
}
