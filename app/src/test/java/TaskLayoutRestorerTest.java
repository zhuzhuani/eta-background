import org.junit.Test;
import static org.junit.Assert.*;

public class TaskLayoutRestorerTest {
    public static class LegacyTask {
        public int displayId;
        public boolean isVisible = true;
        public boolean topActivityInSizeCompat = true;
    }
    public static class CompatInfo {
        public boolean isTopActivityInSizeCompat() { return true; }
    }
    public static class ModernTask {
        public int displayId;
        public boolean isVisible = true;
        public CompatInfo appCompatTaskInfo = new CompatInfo();
    }
    @Test public void neverRestartsNormalHiddenOrInvisibleActivities() {
        LegacyTask task = new LegacyTask();
        assertTrue(TaskLayoutRestorer.shouldRestart(task));
        task.displayId = 12;
        assertFalse(TaskLayoutRestorer.shouldRestart(task));
        task.displayId = 0;
        task.isVisible = false;
        assertFalse(TaskLayoutRestorer.shouldRestart(task));
        task.isVisible = true;
        task.topActivityInSizeCompat = false;
        assertFalse(TaskLayoutRestorer.shouldRestart(task));
        assertFalse(TaskLayoutRestorer.shouldRestart(new Object()));
    }
    @Test public void recognizesModernTaskCompatInfo() {
        assertTrue(TaskLayoutRestorer.shouldRestart(new ModernTask()));
    }
}
