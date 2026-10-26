import android.graphics.Rect;

/** Clears per-task virtual-display overrides without changing global display settings or killing apps. */
final class TaskLayoutRestorer {
    static boolean restore(Object token) {
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
