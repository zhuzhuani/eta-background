package com.eta.shadow;

import android.content.Context;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;

final class EngineCredential {
    private EngineCredential() { }
    static synchronized String get(Context context) throws IOException {
        if (context == null) throw new IOException("application context unavailable");
        File directory = new File(context.getFilesDir(), "eta-engine-stage");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cannot create private engine directory");
        File file = new File(directory, "engine.token");
        // Shared by the runtime and accessibility processes; never rotate on ordinary startup.
        try (RandomAccessFile lock = new RandomAccessFile(new File(directory, ".credential.lock"), "rw");
             FileLock held = lock.getChannel().lock()) {
            if (file.exists()) return EngineWire.readToken(file);
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            StringBuilder token = new StringBuilder();
            for (byte value : random) token.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            File temporary = new File(directory, ".engine.token.new");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(token.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return token.toString();
        }
    }
}
