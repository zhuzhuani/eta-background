import android.os.IBinder;
import java.lang.reflect.Method;

/** Capture this process's system-service epoch. A dead proxy is never reused. */
final class SystemServiceHealth implements java.util.function.BooleanSupplier {
    private final IBinder[] services = new IBinder[3];
    private volatile boolean dead;
    SystemServiceHealth() throws Exception {
        Method get = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        get.setAccessible(true);
        String[] names = {"activity", "display", "input"};
        for (int i = 0; i < names.length; i++) {
            IBinder service = (IBinder) get.invoke(null, names[i]);
            if (service == null) throw new IllegalStateException("system service unavailable: " + names[i]);
            services[i] = service;
            service.linkToDeath(() -> { dead = true; VDrive.systemFailed(); }, 0);
        }
    }
    public boolean getAsBoolean() {
        if (dead) return false;
        for (IBinder service : services) if (!service.isBinderAlive()) return false;
        return true;
    }
}
