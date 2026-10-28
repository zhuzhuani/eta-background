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
    public static String readToken(File file) throws IOException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            String token = readLine(reader, 65);
            if (token == null || !token.trim().matches("[a-f0-9]{64}")) throw new IOException("invalid engine credential");
            return token.trim();
        }
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
