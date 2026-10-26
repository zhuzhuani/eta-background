import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.List;

/** Clears virtual-display overrides, then checks whether Android retained size-compat mode. */
final class TaskLayoutRestorer {
    private static Handler main;
    private static synchronized Handler handler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }
    private static final LinkedHashMap<Integer, Object> pending = new LinkedHashMap<>();
    static volatile String lastOutcome = "not_requested";
    static volatile int compatibilityRestarts;

    static Object field(Object object, String name) {
        try { return object.getClass().getField(name).get(object); }
        catch (Exception ignored) { return null; }
    }

    static boolean inSizeCompat(Object info) {
        Object compat = field(info, "appCompatTaskInfo");
        if (compat != null) try {
            return Boolean.TRUE.equals(compat.getClass().getMethod("isTopActivityInSizeCompat").invoke(compat));
        } catch (Exception ignored) { }
        return Boolean.TRUE.equals(field(info, "topActivityInSizeCompat"));
    }

    static boolean shouldRestart(Object info) {
        return Integer.valueOf(0).equals(field(info, "displayId"))
                && isUserViewing(info) && inSizeCompat(info);
    }

    static boolean isUserViewing(Object info) {
        return Boolean.TRUE.equals(field(info, "isVisible")) && Boolean.TRUE.equals(field(info, "isFocused"));
    }

    static boolean restore(int taskId, Object info) {
        Object token = field(info, "token");
        if (token == null) return false;
        handler().post(() -> {
            if (token.equals(pending.get(taskId))) return;
            pending.put(taskId, token);
            while (pending.size() > 32) pending.remove(pending.keySet().iterator().next());
            handler().postDelayed(() -> verify(taskId, token, 0), 350);
        });
        return true; // Queued; no layout mutation while a task is finishing in the background.
    }

    private static void verify(int taskId, Object expectedToken, int attempt) {
        if (pending.get(taskId) != expectedToken) return;
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            List<?> tasks = (List<?>) Class.forName("android.app.IActivityTaskManager")
                    .getMethod("getAllRootTaskInfos").invoke(atm);
            Object target = null;
            if (tasks != null) for (Object task : tasks) {
                if (Integer.valueOf(taskId).equals(field(task, "taskId"))) { target = task; break; }
            }
            if (target == null || !Integer.valueOf(0).equals(field(target, "displayId"))
                    || !expectedToken.equals(field(target, "token"))) {
                pending.remove(taskId);
                lastOutcome = "task_changed";
                return;
            }
            if (!VDrive.mainDisplayOn() || !isUserViewing(target)) {
                lastOutcome = "waiting_for_user_open";
                handler().postDelayed(() -> verify(taskId, expectedToken, 0), 5000);
                return;
            }
            // Require two focused samples so a transient ROM handover cannot trigger a relaunch.
            if (attempt == 0) {
                handler().postDelayed(() -> verify(taskId, expectedToken, 1), 750);
                return;
            }
            if (attempt == 1) {
                clearOverrides(expectedToken);
                handler().postDelayed(() -> verify(taskId, expectedToken, 2), 350);
                return;
            }
            if (shouldRestart(target)) {
                // This is Android's compat-restart-button path: clear size-compat config and
                // let ActivityManager save/recreate the visible activity. Never force-stop a package.
                Class<?> organizer = Class.forName("android.window.TaskOrganizer");
                organizer.getMethod("restartTaskTopActivityProcessIfVisible",
                        Class.forName("android.window.WindowContainerToken"))
                        .invoke(organizer.getDeclaredConstructor().newInstance(), expectedToken);
                compatibilityRestarts++;
                lastOutcome = "compatibility_restart_requested";
                pending.remove(taskId);
                return;
            }
            lastOutcome = "normal_configuration";
            pending.remove(taskId);
            return;
        } catch (Exception error) {
            lastOutcome = "compatibility_api_unavailable";
            VDrive.log("compatibility restore: " + error.getClass().getSimpleName());
        }
        if (attempt < 4) handler().postDelayed(() -> verify(taskId, expectedToken, attempt + 1), 1000);
        else pending.remove(taskId);
    }

    private static boolean clearOverrides(Object token) {
        if (token == null) return false;
        try {
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> transactionClass = Class.forName("android.window.WindowContainerTransaction");
            Object transaction = transactionClass.getDeclaredConstructor().newInstance();
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, token, new Rect());
            transactionClass.getMethod("setAppBounds", tokenClass, Rect.class)
                    .invoke(transaction, token, (Object) null);
            transactionClass.getMethod("setScreenSizeDp", tokenClass, int.class, int.class)
                    .invoke(transaction, token, 0, 0);
            transactionClass.getMethod("setDensityDpi", tokenClass, int.class)
                    .invoke(transaction, token, 0);
            transactionClass.getMethod("setWindowingMode", tokenClass, int.class)
                    .invoke(transaction, token, 1); // WINDOWING_MODE_FULLSCREEN
            transactionClass.getMethod("setActivityWindowingMode", tokenClass, int.class)
                    .invoke(transaction, token, 0); // inherit the real display's configuration
            Class<?> organizerClass = Class.forName("android.window.WindowOrganizer");
            Object organizer = organizerClass.getDeclaredConstructor().newInstance();
            organizerClass.getMethod("applyTransaction", transactionClass).invoke(organizer, transaction);
            return true;
        } catch (Throwable error) {
            VDrive.log("fullscreen configuration restore unavailable: " + error.getClass().getSimpleName());
            return false;
        }
    }
}
