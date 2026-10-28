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
    @Test public void credentialsRejectMissingFilesAndTrailingDataWithoutDisclosingContents() throws Exception {
        File file = File.createTempFile("engine-credential", ".test");
        String token = new String(new char[64]).replace('\0', 'a');
        try {
            for (String valid : new String[]{token, token + "\n"}) {
                java.nio.file.Files.write(file.toPath(), valid.getBytes("UTF-8"));
                assertEquals(token, EngineWire.readToken(file));
            }
            for (String invalid : new String[]{"", "wrong", token + "\nx", token + "\0", token + "\n\n"}) {
                java.nio.file.Files.write(file.toPath(), invalid.getBytes("UTF-8"));
                EngineWire.CredentialException error = assertThrows(EngineWire.CredentialException.class, () -> EngineWire.readToken(file));
                assertEquals("TOKEN_INVALID", error.code);
                assertEquals("TOKEN_INVALID", error.getMessage());
            }
            assertTrue(file.delete());
            assertEquals("TOKEN_MISSING", assertThrows(EngineWire.CredentialException.class, () -> EngineWire.readToken(file)).code);
        } finally { file.delete(); }
    }
}
