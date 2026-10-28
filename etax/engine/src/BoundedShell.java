import java.io.*;
import java.util.concurrent.TimeUnit;

/** One bounded process group; cancellation stops children as well as the shell. */
final class BoundedShell {
    interface Cancellation { void check() throws Exception; }
    static String execute(String command, long timeoutMs, Cancellation cancellation) throws Exception {
        cancellation.check();
        Process process = new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c", command)
                .redirectErrorStream(true).start();
        int pid = processId(process);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) synchronized (output) {
                    int available = 1024 * 1024 - output.size();
                    if (available > 0) output.write(buffer, 0, Math.min(count, available));
                }
            } catch (IOException ignored) { }
        }, "vdrive-shell-output");
        drain.setDaemon(true);
        drain.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                cancellation.check();
                if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("shell deadline exceeded; outcome may be unknown");
            }
            drain.join(500);
            cancellation.check();
            synchronized (output) { return output.toString("UTF-8"); }
        } finally {
            // Never target a shared group: setsid created a session for this command only.
            try { if (pid > 0) android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL); }
            catch (Throwable ignored) { }
            process.destroyForcibly();
        }
    }
    private static int processId(Process process) {
        // Android releases differ between UNIXProcess and ProcessImpl; do not link a
        // newer JDK Process.pid() into a dex intended for Android's Java API.
        try { return ((Number) process.getClass().getMethod("pid").invoke(process)).intValue(); }
        catch (Exception ignored) { }
        for (Class<?> type = process.getClass(); type != null; type = type.getSuperclass()) try {
            java.lang.reflect.Field field = type.getDeclaredField("pid");
            field.setAccessible(true);
            return field.getInt(process);
        } catch (Exception ignored) { }
        return -1;
    }
}
