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

    private Engine() {}

    // ------------------------------------------------------------- public API

    /**
     * 供工具预检用：有界地确保引擎在跑。已在跑立即返回 UP；否则在后台 worker 里启动，
     * 最多等 {@code waitMs}。等不到但还在启动中返回 STARTING（让模型过几秒重试），
     * 明确失败（无 root / 没打进包）返回 FAILED。
     */
    public static int ensureRunningBounded(Context ctx, long waitMs) {
        if (portOpen(800)) return UP;
        if (ctx == null) { lastReason = "NO_CONTEXT"; return FAILED; }
        kickWorker(ctx);
        long deadline = SystemClock.elapsedRealtime() + waitMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (portOpen(500)) return UP;
            String r = lastReason;
            // worker 已结束且是终态失败：不用再等
            if (!WORKER_BUSY.get()
                    && ("NO_ROOT".equals(r) || "NOT_BUNDLED".equals(r) || "NO_CONTEXT".equals(r))) {
                return FAILED;
            }
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
        }
        if (portOpen(500)) return UP;
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
        return ShadowSettings.engineFixHint();
    }

    // ------------------------------------------------------------- worker

    private static void kickWorker(final Context ctx) {
        long now = SystemClock.elapsedRealtime();
        // 节流：最近一次尝试刚因终态原因失败（无 root / 没打进包），6s 内不再踢，避免反复弹授权框
        String r = lastReason;
        if (now - lastAttemptAt < 6000L && ("NO_ROOT".equals(r) || "NOT_BUNDLED".equals(r))) return;
        if (!WORKER_BUSY.compareAndSet(false, true)) return;   // 已有 worker 在跑
        lastAttemptAt = now;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { doEnsure(ctx); }
                catch (Throwable ignored) { }
                finally { WORKER_BUSY.set(false); }
            }
        }, "eta-engine-start");
        t.setDaemon(true);
        t.start();
    }

    private static void doEnsure(Context ctx) {
        File stage = stageAssets(ctx);
        if (stage == null) { lastReason = "NOT_BUNDLED"; return; }
        boolean force = false;
        if (portOpen(800)) {
            // S2：引擎在跑，但 build 和内置版本不一致 → 强制重启到内置 dex，保证 APK⇄引擎配套
            String running = probeBuild(2000);
            String want = bundledVersion(ctx);
            if (running != null && want != null && !want.isEmpty() && !want.equals(running)) {
                android.util.Log.i(TAG, "engine build " + running + " != bundled " + want + ", restarting");
                force = true;
            } else if (running != null) {
                lastReason = null;
                ShadowSettings.markEngineReady(true);
                return;                                        // 已在跑且配套，什么都不用做
            }
        }
        runSu(buildScript(stage.getAbsolutePath(), force));
        if (portOpen(1500)) {
            lastReason = null;
            ShadowSettings.markEngineReady(true);              // 立即刷新健康缓存，不等 3s 过期
        }
    }

    // ------------------------------------------------------------- internals

    private static boolean portOpen(int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", ENGINE_PORT), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 问引擎的 build 版本串（status 命令），拿不到返回 null。 */
    private static String probeBuild(int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", ENGINE_PORT), 1000);
            s.setSoTimeout(timeoutMs);
            PrintStream w = new PrintStream(s.getOutputStream(), true, "UTF-8");
            w.println("{\"cmd\":\"status\"}");
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            if (line == null) return null;
            if (line.startsWith("RESULT:")) line = line.substring(7);
            String build = new org.json.JSONObject(line).optString("build", "");
            return build.isEmpty() ? null : build;
        } catch (Throwable t) {
            return null;
        }
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
     * 复制到 /data/local/tmp/eta/。只在文件缺失或大小变化时重写，省去无谓 IO。
     *
     * @return 释放目录；assets 里缺少引擎 dex 时返回 null。
     */
    private static File stageAssets(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "eta-engine-stage");
        if (!dir.exists() && !dir.mkdirs()) return null;
        boolean haveDex = false;
        for (String[] p : PAYLOAD) {
            File out = new File(dir, p[1]);
            long assetLen = assetLength(ctx, p[0]);
            if (assetLen < 0) continue;                        // assets 里没有这个文件
            try {
                if (!(out.exists() && out.length() == assetLen)) {
                    copyAsset(ctx, p[0], out);
                }
                if ("vdrive.dex".equals(p[1])) haveDex = true;
            } catch (Throwable t) {
                android.util.Log.w(TAG, "stage " + p[0] + " failed: " + t);
            }
        }
        return haveDex ? dir : null;
    }

    private static long assetLength(Context ctx, String asset) {
        try (InputStream in = ctx.getAssets().open(asset)) {
            long n = 0; byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) > 0) n += r;
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void copyAsset(Context ctx, String asset, File out) throws Exception {
        try (InputStream in = ctx.getAssets().open(asset);
             FileOutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.getFD().sync();
        }
    }

    /**
     * root 脚本：force=true 或 dex 变了 → 重铺并强制重启（vd.sh 无参=停旧起新）；
     * 否则只在没跑时启动（vd.sh status 在跑 exit 0 / 没跑 exit 1）。
     * stage 目录路径无空格，直接内插安全。
     */
    private static String buildScript(String stage, boolean force) {
        return "set -u\n"
                + "D=/data/local/tmp/eta\n"
                + "SRC=" + stage + "\n"
                + "FORCE=" + (force ? "1" : "0") + "\n"
                + "[ \"$(id -u 2>/dev/null)\" = 0 ] || { echo ETA_NO_ROOT; exit 97; }\n"
                + "mkdir -p \"$D\" || { echo ETA_MKDIR_FAIL; exit 20; }\n"
                + "restage=0\n"
                + "if [ ! -f \"$D/vdrive.dex\" ]; then restage=1; else\n"
                + "  a=$(sha256sum \"$SRC/vdrive.dex\" 2>/dev/null | awk '{print $1}')\n"
                + "  b=$(sha256sum \"$D/vdrive.dex\" 2>/dev/null | awk '{print $1}')\n"
                + "  [ -n \"$a\" ] && [ \"$a\" = \"$b\" ] || restage=1\n"
                + "fi\n"
                + "if [ \"$restage\" = 1 ] || [ \"$FORCE\" = 1 ]; then\n"
                + "  for f in vdrive.dex vdrive.version vd.sh install_vdrive.sh check_pair.sh; do\n"
                + "    [ -f \"$SRC/$f\" ] && cp -f \"$SRC/$f\" \"$D/$f\"\n"
                + "  done\n"
                + "  chmod 700 \"$D\"/*.sh 2>/dev/null\n"
                + "  echo ETA_STAGED\n"
                + "  sh \"$D/vd.sh\"; rc=$?; echo \"ETA_VD_RC=$rc\"; exit $rc\n"
                + "fi\n"
                + "if sh \"$D/vd.sh\" status >/dev/null 2>&1; then echo ETA_ALREADY; exit 0; fi\n"
                + "sh \"$D/vd.sh\"; rc=$?; echo \"ETA_VD_RC=$rc\"; exit $rc\n";
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
            boolean done = p.waitFor(45, TimeUnit.SECONDS);
            if (!done) {
                lastReason = "TIMEOUT";
                p.destroy();
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
            while ((n = in.read(buf)) > 0 && sb.length() < 4096) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            String out = sb.toString();
            if (out.contains("ETA_NO_ROOT")) lastReason = "NO_ROOT";
            if (!out.trim().isEmpty()) android.util.Log.i(TAG, "vd.sh: " + out.trim());
        } catch (Throwable ignored) { }
    }
}
