import com.eta.shadow.EngineWire;
import java.io.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class EngineWireTest {
    @Test public void rejectsMissingWrongAndEmptyCredentials() throws Exception {
        String token = new String(new char[64]).replace('\0', 'a');
        assertFalse(EngineWire.authorized(new JSONObject(), token));
        assertFalse(EngineWire.authorized(new JSONObject().put("auth", "wrong"), token));
        assertFalse(EngineWire.authorized(new JSONObject().put("auth", ""), ""));
        assertTrue(EngineWire.authorized(new JSONObject().put("auth", token), token));
    }
    @Test public void requestLimitIsEnforcedEvenWithoutNewline() throws Exception {
        assertEquals("abc", EngineWire.readLine(new StringReader("abc\n"), 3));
        assertThrows(IOException.class, () -> EngineWire.readLine(new StringReader("abcd"), 3));
    }
}
