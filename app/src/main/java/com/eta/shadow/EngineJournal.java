package com.eta.shadow;

import android.content.Context;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.concurrent.*;
import org.json.*;

/** Bounded lifecycle evidence, persisted across app/framework restarts. No tool payloads. */
public final class EngineJournal {
    private static final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> { Thread thread = new Thread(runnable, "eta-engine-journal"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    static { writer.allowCoreThreadTimeOut(true); }
    private EngineJournal() { }
    public static void record(Context context, String event, String state) {
        if (context == null || !event.matches("engine_start|engine_result|system_connection|application_state")
                || !state.matches("[A-Za-z0-9_]{1,64}")) return;
        final File directory = context.getFilesDir();
        final long timestamp = System.currentTimeMillis();
        writer.execute(() -> {
            try (RandomAccessFile lock = new RandomAccessFile(new File(directory, "engine-journal.lock"), "rw");
                 FileLock held = lock.getChannel().lock()) {
                File file = new File(directory, "engine-journal.jsonl");
                if (file.length() > 64 * 1024) java.nio.file.Files.move(file.toPath(), new File(directory, "engine-journal.previous.jsonl").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                String line = new JSONObject().put("timeMs", timestamp).put("event", event).put("state", state)
                        .put("pid", android.os.Process.myPid()).toString() + "\n";
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            } catch (Exception ignored) { }
        });
    }
    public static JSONArray snapshot(Context context) {
        java.util.ArrayDeque<JSONObject> entries = new java.util.ArrayDeque<>();
        for (String name : new String[]{"engine-journal.previous.jsonl", "engine-journal.jsonl"}) {
            try (BufferedReader input = new BufferedReader(new FileReader(new File(context.getFilesDir(), name)))) {
                String line;
                while ((line = EngineWire.readLine(input, 512)) != null) {
                    entries.add(new JSONObject(line));
                    while (entries.size() > 120) entries.removeFirst();
                }
            } catch (Exception ignored) { }
        }
        return new JSONArray(entries);
    }
}
