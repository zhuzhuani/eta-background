import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Restore only our own priority write, on the same process incarnation. */
final class ProcessProtection {
    private static final String PROTECTED = "-200";
    private final Path proc;
    private final Map<Integer, Saved> saved = new HashMap<>();
    private static final class Saved {
        final String started, adjustment;
        Saved(String started, String adjustment) { this.started = started; this.adjustment = adjustment; }
    }
    ProcessProtection(Path proc) { this.proc = proc; }
    private String read(int pid, String file) throws IOException {
        return new String(Files.readAllBytes(proc.resolve(Integer.toString(pid)).resolve(file)), StandardCharsets.UTF_8).trim();
    }
    private String started(int pid) throws IOException {
        String stat = read(pid, "stat");
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
        if (fields.length < 20) throw new IOException("incomplete process identity");
        return fields[19];
    }
    void protect(int pid) throws IOException {
        String started = started(pid);
        Saved existing = saved.get(pid);
        if (existing != null && existing.started.equals(started)) return;
        saved.remove(pid);
        String original = read(pid, "oom_score_adj");
        if (Integer.parseInt(original) <= -200) return;
        if (!started.equals(started(pid))) return;
        Files.write(proc.resolve(Integer.toString(pid)).resolve("oom_score_adj"), PROTECTED.getBytes(StandardCharsets.UTF_8));
        saved.put(pid, new Saved(started, original));
    }
    void restoreAll() {
        for (Map.Entry<Integer, Saved> entry : saved.entrySet()) try {
            int pid = entry.getKey(); Saved value = entry.getValue();
            if (value.started.equals(started(pid)) && PROTECTED.equals(read(pid, "oom_score_adj"))) {
                Files.write(proc.resolve(Integer.toString(pid)).resolve("oom_score_adj"), value.adjustment.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { }
        saved.clear();
    }
}
