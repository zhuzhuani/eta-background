package com.eta.shadow;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自启后台虚拟屏引擎（etax）。
 *
 * 引擎是一个需要 root 的常驻进程（uid 0 的 app_process），监听 127.0.0.1:8733。
 * 它的 dex 和启动脚本随 APK 打包在 assets 里；这个类负责：把 assets 释放到
 * /data/local/tmp/eta/，再用 su 跑 vd.sh 把引擎拉起来。装完 APK、授一次 root，
 * 就不用再手动 `sh vd.sh`。
 *
 * 启动实际工作放在一个后台 worker 里跑（{@link #kickWorker}），冷启动最坏要十几秒，
 * 调用方（{@link #ensureRunningBounded}）只做有界等待，不会把一次 screen_* 调用卡死。
 * 普通（未 root）手机上 su 会直接失败，这里静默吞掉，只把失败原因记进 {@link #reasonHint()}。
 */
public final class Engine {

    private static final String TAG = "EtaShadow";
    static final int ENGINE_PORT = 8733;

    /** {@link #ensureRunningBounded} 的返回态。 */
    public static final int UP = 0;        // 引擎已就绪
    public static final int STARTING = 1;  // 还在后台启动，稍后重试
    public static final int FAILED = 2;    // 启动失败（无 root / 没打进包 等）

    /** assets 里的路径 → 释放到 /data/local/tmp/eta/ 下的文件名。 */
    private static final String[][] PAYLOAD = {
            {"vdrive.dex", "vdrive.dex"},
            {"vdrive.version", "vdrive.version"},
            {"eta-engine/vd.sh", "vd.sh"},
            {"eta-engine/install_vdrive.sh", "install_vdrive.sh"},
            {"eta-engine/check_pair.sh", "check_pair.sh"},
    };

    private static final AtomicBoolean WORKER_BUSY = new AtomicBoolean(false);
    private static volatile long lastAttemptAt = 0L;
    private static volatile String lastReason = null;
    private static volatile String bundledVer = null;
    private static volatile String bundledHash;
    private static volatile long nextAttemptAt;
    private static int failures;

    private Engine() {}

    // ------------------------------------------------------------- public API

    /**
     * 供工具预检用：有界地确保引擎在跑。已在跑立即返回 UP；否则在后台 worker 里启动，
     * 最多等 {@code waitMs}。等不到但还在启动中返回 STARTING（让模型过几秒重试），
     * 明确失败（无 root / 没打进包）返回 FAILED。
     */
    public static int ensureRunningBounded(Context ctx, long waitMs) {
        if (isReady(ctx, 800)) return UP;
        if (ctx == null) { lastReason = "NO_CONTEXT"; return FAILED; }
        kickWorker(ctx);
        if (!WORKER_BUSY.get() && lastReason != null) return FAILED;
        long deadline = SystemClock.elapsedRealtime() + waitMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isReady(ctx, 500)) return UP;
            String r = lastReason;
            // worker 已结束且是终态失败：不用再等
            if (!WORKER_BUSY.get()
                    && ("NO_ROOT".equals(r) || "NOT_BUNDLED".equals(r) || "NO_CONTEXT".equals(r))) {
                return FAILED;
            }
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
        }
        if (isReady(ctx, 500)) return UP;
        return WORKER_BUSY.get() ? STARTING : FAILED;
    }

    /** 开机/服务连上/引擎掉线时预热：只负责把 worker 踢起来，不阻塞调用方。 */
    public static void warm(Context ctx) {
        if (ctx != null) kickWorker(ctx);
    }

    /** 失败时给工具调用返回的可读提示。 */
    public static String reasonHint() {
        String r = lastReason;
        if ("NOT_BUNDLED".equals(r)) {
            return "后台虚拟屏引擎没有打进这个 APK（assets 里没有 vdrive.dex）。"
                    + "请把引擎放进 etax/engine/ 再重新编译。";
        }
        if ("NO_ROOT".equals(r)) {
            return "后台虚拟屏需要 root：没能拿到 root 权限。"
                    + "请确认手机已 root，并在 Magisk/KernelSU 里允许 io.github.mangi.etax 使用 root，然后重试。"
                    + "（刚重启过的话临时 root 已失效，需要先重新获取 root。）";
        }
        if ("START_FAILED".equals(r) || "INSTALL_FAILED".equals(r) || "TIMEOUT".equals(r)) {
            return "后台引擎恢复尚未完成，已限制重试频率，请稍后重新打开后台屏幕；"
                    + "不要重复发送可能已执行的点击或消息。可在设置中导出诊断查看恢复结果。";
        }
        return ShadowSettings.engineFixHint();
    }

    // ------------------------------------------------------------- worker

    private static void kickWorker(final Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextAttemptAt) return;
        // 节流：最近一次尝试刚因终态原因失败（无 root / 没打进包），6s 内不再踢，避免反复弹授权框
        String r = lastReason;
        if (now - lastAttemptAt < 6000L && ("NO_ROOT".equals(r) || "NOT_BUNDLED".equals(r))) return;
        if (!WORKER_BUSY.compareAndSet(false, true)) return;   // 已有 worker 在跑
        lastAttemptAt = now;
        lastReason = null;
        EngineJournal.record(ctx, "engine_start", "requested");
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { doEnsure(ctx); }
                catch (Throwable error) { lastReason = "START_FAILED"; }
                finally {
                    EngineJournal.record(ctx, "engine_result", lastReason == null ? "ready" : lastReason);
                    if (lastReason == null) { failures = 0; nextAttemptAt = 0; }
                    else nextAttemptAt = SystemClock.elapsedRealtime() + Math.min(60000L, 5000L << Math.min(failures++, 4));
                    WORKER_BUSY.set(false);
                }
            }
        }, "eta-engine-start");
        t.setDaemon(true);
        t.start();
    }

    private static void doEnsure(Context ctx) {
        File stage = stageAssets(ctx);
        if (stage == null) { lastReason = "NOT_BUNDLED"; return; }
        if (isReady(ctx, 1500)) {
            lastReason = null;
            ShadowSettings.markEngineReady(true);
            return;
        }
        ShadowSettings.markEngineReady(false);
        runSu(buildScript(stage.getAbsolutePath()));
        boolean ready = isReady(ctx, 2000);
        ShadowSettings.markEngineReady(ready);
        if (ready) lastReason = null;
        else if (lastReason == null) lastReason = "START_FAILED";
    }

    static boolean isReady(Context ctx, int timeoutMs) {
        if (ctx == null) return false;
        try {
            org.json.JSONObject status = probeStatus(ctx, timeoutMs);
            String want = bundledVersion(ctx);
            return status.optBoolean("ok") && status.optBoolean("healthy") && want != null
                    && want.equals(status.optString("build")) && bundledHash(ctx).equals(status.optString("dexSha256"));
        } catch (Exception error) { return false; }
    }

    static org.json.JSONObject probeStatus(Context ctx, int timeoutMs) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", ENGINE_PORT), Math.min(1000, timeoutMs));
            socket.setSoTimeout(timeoutMs);
            PrintStream out = new PrintStream(socket.getOutputStream(), true, "UTF-8");
            out.println(new org.json.JSONObject().put("cmd", "status").put("auth", EngineCredential.get(ctx)));
            String line = EngineWire.readLine(new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8")), 65536);
            if (line == null) throw new java.io.EOFException("engine closed health check");
            return new org.json.JSONObject(line.startsWith("RESULT:") ? line.substring(7) : line);
        }
    }

    private static String bundledHash(Context ctx) throws Exception {
        String hash = bundledHash;
        if (hash == null) try (InputStream input = ctx.getAssets().open("vdrive.dex")) {
            bundledHash = hash = EngineWire.sha256(input);
        }
        return hash;
    }

    private static String bundledVersion(Context ctx) {
        String v = bundledVer;
        if (v != null) return v;
        try (InputStream in = ctx.getAssets().open("vdrive.version")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            v = new String(bo.toByteArray(), "UTF-8").trim();
            bundledVer = v;
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 assets 里的引擎释放到应用私有目录（无需 root，总能写）。root 脚本随后从这里
     * 复制到 /data/local/tmp/eta/。逐字节核对内容，原子替换变化的私有副本。
     *
     * @return 释放目录；assets 里缺少引擎 dex 时返回 null。
     */
    private static File stageAssets(Context ctx) {
        File directory = new File(ctx.getFilesDir(), "eta-engine-stage");
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        try {
            EngineCredential.get(ctx);
            // File locking also covers the accessibility and runtime processes.
            try (java.io.RandomAccessFile lock = new java.io.RandomAccessFile(new File(directory, ".stage.lock"), "rw");
                 java.nio.channels.FileLock held = lock.getChannel().lock()) {
                for (String[] payload : PAYLOAD) {
                    File target = new File(directory, payload[1]);
                    File temporary = new File(directory, payload[1] + ".new");
                    try {
                        copyAsset(ctx, payload[0], temporary);
                        boolean same = target.isFile() && java.util.Arrays.equals(
                                java.nio.file.Files.readAllBytes(target.toPath()), java.nio.file.Files.readAllBytes(temporary.toPath()));
                        if (!same) java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally { temporary.delete(); }
                }
            }
            return directory;
        } catch (Exception error) {
            android.util.Log.w(TAG, "stage engine failed: " + error.getClass().getSimpleName());
            return null;
        }
    }

    private static void copyAsset(Context ctx, String asset, File out) throws Exception {
        try (InputStream in = ctx.getAssets().open(asset); FileOutputStream stream = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) != -1;) stream.write(buffer, 0, count);
            stream.getFD().sync();
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    /** One installer owns stop, verification, atomic replacement and rollback. */
    private static String buildScript(String stage) {
        return "[ \"$(id -u 2>/dev/null)\" = 0 ] || { echo ETA_NO_ROOT; exit 97; }\n"
                + "sh " + quote(stage + "/install_vdrive.sh") + " " + quote(stage) + "\n";
    }

    /** 用 su 开一个 root shell，把脚本喂给它的 stdin 执行。 */
    private static void runSu(String script) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su");
            pb.redirectErrorStream(true);   // 合并 stderr，免得错误输出撑满管道把 shell 卡住
            p = pb.start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(script.getBytes("UTF-8"));
                os.write("\nexit $?\n".getBytes("UTF-8"));
                os.flush();
            }
            final Process proc = p;
            Thread reader = new Thread(new Runnable() {
                public void run() { drain(proc); }
            });
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(90, TimeUnit.SECONDS);
            if (!done) {
                lastReason = "TIMEOUT";
                p.destroyForcibly();
            } else if (p.exitValue() != 0 && lastReason == null) {
                lastReason = "INSTALL_FAILED";
            }
        } catch (java.io.IOException e) {
            lastReason = "NO_ROOT";                 // 没有 su 二进制：这台机器没 root
            android.util.Log.w(TAG, "su not available: " + e);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "engine start failed: " + t);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    private static void drain(Process p) {
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[1024];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                if (sb.length() < 4096) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            String out = sb.toString();
            if (out.contains("ETA_NO_ROOT")) lastReason = "NO_ROOT";
            if (!out.trim().isEmpty()) android.util.Log.i(TAG, "vd.sh: " + out.trim());
        } catch (Throwable ignored) { }
    }
}
