package com.eta.shadow;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Shared limits and per-install authentication for the two loopback endpoints. */
public final class EngineWire {
    public static final int REQUEST_LIMIT = 256 * 1024;
    public static final int RESPONSE_LIMIT = 16 * 1024 * 1024;
    private EngineWire() { }
    public static boolean authorized(JSONObject request, String token) {
        return token != null && token.matches("[a-f0-9]{64}") && MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), request.optString("auth").getBytes(StandardCharsets.UTF_8));
    }
    public static String readLine(Reader input, int limit) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int c; (c = input.read()) != -1;) {
            if (c == '\n') return out.toString();
            if (out.length() >= limit) throw new IOException("protocol message limit exceeded");
            out.append((char)c);
        }
        return out.length() == 0 ? null : out.toString();
    }
    public static final class CredentialException extends IOException {
        public final String code;
        public CredentialException(String code) { super(code); this.code = code; }
    }
    public static String readToken(File file) throws IOException {
        if (!file.exists()) throw new CredentialException("TOKEN_MISSING");
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int value; (value = input.read()) != -1;) {
                if (bytes.size() >= 65) throw new CredentialException("TOKEN_INVALID");
                bytes.write(value);
            }
            String token = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            // App-generated tokens are exactly 64 bytes; accept one legacy LF, no extra lines.
            if (!token.matches("[a-f0-9]{64}\\n?")) throw new CredentialException("TOKEN_INVALID");
            return token.substring(0, 64);
        } catch (CredentialException error) { throw error; }
        catch (IOException error) { throw new CredentialException("TOKEN_UNREADABLE"); }
    }
    public static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
