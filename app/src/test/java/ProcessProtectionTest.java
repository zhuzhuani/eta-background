import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessProtectionTest {
    private Path proc() throws Exception { Path root = Files.createTempDirectory("eta-protection"); Files.createDirectory(root.resolve("123")); state(root, "991", "700"); return root; }
    private void state(Path root, String start, String adjustment) throws Exception {
        String[] fields = new String[20]; java.util.Arrays.fill(fields, "1"); fields[0] = "S"; fields[19] = start;
        Files.write(root.resolve("123/stat"), ("123 (app with space) " + String.join(" ", fields)).getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("123/oom_score_adj"), adjustment.getBytes(StandardCharsets.UTF_8));
    }
    private String value(Path root) throws Exception { return new String(Files.readAllBytes(root.resolve("123/oom_score_adj")), StandardCharsets.UTF_8); }
    @Test public void restoresOriginalPriorityNotZeroAndRepeatedProtectionDoesNotOverwriteIt() throws Exception {
        Path root = proc(); ProcessProtection protection = new ProcessProtection(root);
        protection.protect(123); protection.protect(123); assertEquals("-200", value(root));
        protection.restoreAll(); assertEquals("700", value(root));
    }
    @Test public void respectsAndroidPriorityChangesAndPidReuse() throws Exception {
        Path root = proc(); ProcessProtection protection = new ProcessProtection(root);
        protection.protect(123); state(root, "991", "100"); protection.restoreAll(); assertEquals("100", value(root));
        protection.protect(123); state(root, "992", "-200"); protection.restoreAll(); assertEquals("-200", value(root));
    }
}
