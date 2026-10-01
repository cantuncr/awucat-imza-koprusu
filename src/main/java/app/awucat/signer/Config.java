package app.awucat.signer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ~/.awucat-signer/config.json}:
 * {@code { "allowedOrigins": [], "modules": [{"name","path"}], "port": 34791 }}.
 * Only user additions are stored; the built-in defaults live in {@link Origins} and
 * {@link Pkcs11Module}.
 */
final class Config {
    /**
     * {@code ~/.awucat-signer}, overridable with the AWUCAT_SIGNER_HOME environment variable (used by
     * tests; the pre-rebrand name UDF_SIGNER_HOME is still honoured).
     */
    static final Path DIR = resolveDir();
    static final Path FILE = DIR.resolve("config.json");
    /** Pre-rebrand settings directory; {@link #load()} copies its config.json over once. */
    static final Path LEGACY_DIR = Paths.get(System.getProperty("user.home"), ".udf-studio-signer");
    static final int DEFAULT_PORT = 34791;

    final List<String> allowedOrigins = new ArrayList<>();
    final List<Pkcs11Module> modules = new ArrayList<>();
    int port = DEFAULT_PORT;

    private static Path resolveDir() {
        for (String var : new String[] {"AWUCAT_SIGNER_HOME", "UDF_SIGNER_HOME"}) {
            String v = System.getenv(var);
            if (v != null && !v.isEmpty()) return Paths.get(v);
        }
        return Paths.get(System.getProperty("user.home"), ".awucat-signer");
    }

    /**
     * One-time move of the pre-rebrand settings (allowed origins, PKCS#11 modules, port). Runs only
     * for the default location, only while the new config.json is absent. The copy goes through a
     * temporary file and an atomic rename, so an interrupted run never leaves a half-written
     * config; the legacy file is renamed to config.json.migrated only after the copy succeeded.
     */
    static void migrateLegacy() {
        if (!DIR.equals(Paths.get(System.getProperty("user.home"), ".awucat-signer"))) return;
        Path legacy = LEGACY_DIR.resolve("config.json");
        if (Files.exists(FILE) || !Files.isRegularFile(legacy)) return;
        try {
            Files.createDirectories(DIR);
            Path tmp = DIR.resolve("config.json.tmp");
            Files.copy(legacy, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, FILE, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            Log.info("Önceki yapılandırma taşındı: " + legacy + " → " + FILE);
            try {
                Files.move(legacy, legacy.resolveSibling("config.json.migrated"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Log.warn("Eski yapılandırma yeniden adlandırılamadı (" + legacy + "): " + e);
            }
        } catch (IOException e) {
            Log.warn("Önceki yapılandırma taşınamadı (" + legacy + "): " + e);
        }
    }

    /** Loads the file; a missing or malformed file yields defaults (with a warning). */
    static Config load() {
        migrateLegacy();
        Config c = new Config();
        if (!Files.isRegularFile(FILE)) return c;
        try {
            String text = new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8);
            Map<String, Object> root = Json.object(Json.parse(text));
            if (root == null) throw new IllegalArgumentException("kök öğe bir nesne değil");
            Object origins = root.get("allowedOrigins");
            if (origins instanceof List) {
                for (Object o : (List<?>) origins) if (o instanceof String) c.allowedOrigins.add((String) o);
            }
            Object mods = root.get("modules");
            if (mods instanceof List) {
                for (Object o : (List<?>) mods) {
                    Map<String, Object> m = Json.object(o);
                    String path = Json.string(m, "path");
                    if (path == null || path.isEmpty()) continue;
                    String name = Json.string(m, "name");
                    c.modules.add(new Pkcs11Module(name == null ? Paths.get(path).getFileName().toString() : name, path));
                }
            }
            Object port = root.get("port");
            if (port instanceof Number) c.port = ((Number) port).intValue();
        } catch (IOException | RuntimeException e) {
            Log.warn("Yapılandırma okunamadı, varsayılanlar kullanılıyor (" + FILE + "): " + e.getMessage());
        }
        return c;
    }

    synchronized void save() {
        List<Object> mods = new ArrayList<>();
        for (Pkcs11Module m : modules) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("name", m.name);
            mm.put("path", m.path);
            mods.add(mm);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("allowedOrigins", new ArrayList<>(allowedOrigins));
        root.put("modules", mods);
        root.put("port", port);
        try {
            Files.createDirectories(DIR);
            Files.write(FILE, (Json.write(root, 2) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("Yapılandırma yazılamadı (" + FILE + "): " + e);
        }
    }
}
