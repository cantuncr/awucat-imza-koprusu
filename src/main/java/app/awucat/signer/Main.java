package app.awucat.signer;

import com.sun.net.httpserver.HttpServer;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * AwuCat İmza Köprüsü — local e-signature bridge. Listens on 127.0.0.1 (first free port among
 * 34791–34793 unless {@code --port} is given) and signs CAdES attribute bytes with a smart card.
 *
 * <pre>
 * java -jar awucat-signer.jar [--mock] [--headless] [--port N] [--allow-origin URL]...
 * </pre>
 */
public final class Main {
    private static final int[] DEFAULT_PORTS = {Config.DEFAULT_PORT, 34792, 34793};

    private Main() {}

    public static void main(String[] args) throws Exception {
        boolean mock = false, headless = false;
        int port = -1;
        List<String> allow = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mock": mock = true; break;
                case "--headless": headless = true; break;
                case "--port": port = parsePort(next(args, ++i)); break;
                case "--allow-origin": allow.add(next(args, ++i)); break;
                case "-h": case "--help": usage(); return;
                default:
                    System.err.println("Bilinmeyen seçenek: " + args[i]);
                    usage();
                    System.exit(2);
            }
        }
        if (headless) System.setProperty("java.awt.headless", "true");
        System.setProperty("apple.awt.application.name", "AwuCat İmza Köprüsü");

        Log.init(Config.DIR.resolve("signer.log"));
        Log.info("AwuCat İmza Köprüsü " + HttpApi.VERSION + " başlatılıyor (Java " + System.getProperty("java.version")
                + ", " + HttpApi.platform() + (mock ? ", deneme modu" : "") + ")");
        Config config = Config.load();
        if (!Files.exists(Config.FILE)) config.save(); // give the user a file to edit
        boolean ui = !headless && !GraphicsEnvironment.isHeadless();
        if (!ui) Log.info("Görüntü yok: pencereler devre dışı (PIN istekle gönderilmeli)");

        TokenStore store = mock ? new MockStore() : new Pkcs11Store(config);
        HttpApi api = new HttpApi(store, new Origins(config, allow), ui, mock);
        HttpServer server = bind(port > 0 ? new int[] {port} : candidates(config.port));
        server.createContext("/", api);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        int bound = server.getAddress().getPort();
        Log.info("Dinleniyor: http://127.0.0.1:" + bound);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> Log.info("Durduruldu")));
        if (ui) {
            Tray.install("AwuCat İmza Köprüsü — 127.0.0.1:" + bound + (mock ? " (deneme modu)" : ""), () -> {
                server.stop(0);
                System.exit(0);
            });
        }
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p >= 1 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        System.err.println("Geçersiz port: " + s);
        System.exit(2);
        return -1;
    }

    private static int[] candidates(int configured) {
        Set<Integer> set = new LinkedHashSet<>();
        if (configured >= 1 && configured <= 65535) set.add(configured);
        for (int p : DEFAULT_PORTS) set.add(p);
        int[] out = new int[set.size()];
        int i = 0;
        for (int p : set) out[i++] = p;
        return out;
    }

    /** Binds the loopback interface only; tries the candidates in order. */
    private static HttpServer bind(int[] ports) {
        for (int p : ports) {
            try {
                return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 0);
            } catch (IOException e) {
                Log.warn("Port " + p + " kullanılamıyor: " + e.getMessage());
            }
        }
        Log.error("Hiçbir porta bağlanılamadı; köprü zaten çalışıyor olabilir.", null);
        System.exit(1);
        return null;
    }

    private static String next(String[] args, int i) {
        if (i >= args.length) {
            System.err.println("Seçenek için değer eksik: " + args[i - 1]);
            System.exit(2);
        }
        return args[i];
    }

    private static void usage() {
        System.out.println("Kullanım: java -jar awucat-signer.jar [seçenekler]\n"
                + "  --mock              Kart yerine bellekteki deneme anahtarını kullan (onay penceresi açılmaz)\n"
                + "  --headless          Hiç pencere açma (PIN istekle gönderilmeli)\n"
                + "  --port N            Yalnızca bu porta bağlan (varsayılan: 34791, 34792, 34793 sırayla)\n"
                + "  --allow-origin URL  Bu origin'e sormadan izin ver (tekrarlanabilir)\n"
                + "Yapılandırma: " + Config.FILE);
    }
}
