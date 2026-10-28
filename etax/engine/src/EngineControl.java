import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

/** Deployment-only probe: no Android context, system-service proxies or credentials. */
public final class EngineControl {
    public static void main(String[] args) {
        try {
            if (args.length != 3 || !"await-port".equals(args[0])) throw new IllegalArgumentException();
            int port = Integer.parseInt(args[1]);
            long timeout = Long.parseLong(args[2]);
            if (port < 1 || port > 65535 || timeout < 0 || timeout > 10000) throw new IllegalArgumentException();
            if (awaitPort(port, timeout)) System.out.println("ETA_ENGINE_PORT_FREE");
            else { System.out.println("ETA_ENGINE_ERROR=PORT_BUSY"); System.exit(98); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.out.println("ETA_ENGINE_ERROR=PORT_CHECK_INTERRUPTED"); System.exit(79);
        } catch (Exception error) {
            System.out.println("ETA_ENGINE_ERROR=PORT_CHECK_FAILED"); System.exit(79);
        }
    }

    static boolean awaitPort(int port, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            try (ServerSocket probe = new ServerSocket()) {
                // Match VDrive's actual bind, including SO_REUSEADDR and loopback address.
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress("127.0.0.1", port));
                return true;
            } catch (BindException busy) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            }
        }
    }
}
