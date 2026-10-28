import android.app.Application;
import android.content.Context;
import com.eta.shadow.Engine;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, application = Application.class)
public class EngineStagingTest {
    @Test public void equalLengthCorruptionIsReplacedFromBundledAssets() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Method stage = Engine.class.getDeclaredMethod("stageAssets", Context.class);
        stage.setAccessible(true);
        File dir = (File) stage.invoke(null, context);
        assertNotNull(dir);
        File dex = new File(dir, "vdrive.dex");
        byte[] original = Files.readAllBytes(dex.toPath());
        assertTrue(original.length > 0);
        byte[] altered = original.clone(); altered[altered.length - 1] ^= 1;
        assertFalse(Arrays.equals(original, altered));
        try {
            Files.write(dex.toPath(), altered);
            stage.invoke(null, context);
            assertArrayEquals("Corrupt bytes must be replaced even when the length matches", original, Files.readAllBytes(dex.toPath()));
        } finally { Files.write(dex.toPath(), original); }
    }
}
