package app.awucat.signer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The HTTP/JSON protocol from docs/SIGNER_PROTOCOL.md: {@code GET /status}, {@code POST
 * /certificates}, {@code POST /sign}, CORS with the origin allow-list. Token operations are
 * serialised through one fair lock so at most one card operation (and one dialog) runs at a time.
 */
final class HttpApi implements HttpHandler {
    static final String VERSION = "1.0.0";
    private static final int MAX_BODY = 64 * 1024;
    private static final long STATUS_LOCK_WAIT_MS = 3000;

    private final TokenStore store;
    private final Origins origins;
    private final boolean ui;
    private final boolean mock;
    private final ReentrantLock tokenLock = new ReentrantLock(true);
    private volatile boolean lastCardPresent;

    HttpApi(TokenStore store, Origins origins, boolean ui, boolean mock) {
        this.store = store;
        this.origins = origins;
        this.ui = ui;
        this.mock = mock;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String origin = ex.getRequestHeaders().getFirst("Origin");
        try {
            // Origin gate first (also for preflights). Unknown origins are prompted only with a
            // display and outside mock mode; browsers always send Origin on cross-origin fetches.
            Origins.Prompter prompter = ui && !mock ? Dialogs::confirmOrigin : null;
            if (!origins.check(origin, prompter)) {
                throw new SignerException("ORIGIN_DENIED", "Bu sitenin (" + (origin == null ? "Origin başlığı yok" : origin)
                        + ") imza köprüsünü kullanmasına izin verilmedi.");
            }
            cors(ex, origin);
            if ("OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            Object result;
            if ("GET".equals(method) && "/status".equals(path)) result = status();
            else if ("POST".equals(method) && "/certificates".equals(path)) result = certificates(body(ex));
            else if ("POST".equals(method) && "/sign".equals(path)) result = sign(body(ex), origin);
            else throw new SignerException("NOT_FOUND", "Böyle bir uç nokta yok: " + method + " " + path);
            send(ex, 200, result);
        } catch (SignerException e) {
            Log.warn(method + " " + path + " → " + e.code + ": " + e.getMessage());
            send(ex, e.status, e.toJson());
        } catch (Throwable t) {
            Log.error("İstek işlenemedi: " + method + " " + path, t);
            send(ex, 500, new SignerException("INTERNAL", "Beklenmeyen bir hata oluştu: " + t).toJson());
        }
    }

    private Map<String, Object> status() throws InterruptedException {
        // Do not queue behind an open signing dialog: fall back to the last known card state.
        if (tokenLock.tryLock(STATUS_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
            try {
                lastCardPresent = store.cardPresent();
            } catch (RuntimeException e) {
                Log.error("Kart durumu okunamadı", e);
            } finally {
                tokenLock.unlock();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "awucat-signer");
        m.put("version", VERSION);
        m.put("platform", platform());
        m.put("java", System.getProperty("java.version"));
        m.put("mock", mock);
        m.put("modules", store.moduleStatus());
        m.put("cardPresent", lastCardPresent);
        return m;
    }

    private Map<String, Object> certificates(Map<String, Object> req) throws SignerException, InterruptedException {
        char[] pin = pinOf(req), prompted = null;
        tokenLock.lockInterruptibly();
        try {
            List<CertInfo> certs;
            try {
                certs = store.listCertificates(pin);
            } catch (SignerException e) {
                if (!"PIN_REQUIRED".equals(e.code) || pin != null || !ui) throw e;
                prompted = Dialogs.askPin("Karttaki sertifikaları okumak için kart PIN'i gerekiyor.");
                if (prompted == null) throw new SignerException("CANCELLED", "Kullanıcı PIN girişini iptal etti.");
                if (prompted.length == 0) throw e;
                certs = store.listCertificates(prompted);
            }
            List<Object> list = new ArrayList<>();
            for (CertInfo c : certs) list.add(c.toJson());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("certificates", list);
            return m;
        } finally {
            tokenLock.unlock();
            wipe(pin);
            wipe(prompted);
        }
    }

    private Map<String, Object> sign(Map<String, Object> req, String origin) throws SignerException, InterruptedException {
        String certId = required(req, "certificateId");
        String algorithm = required(req, "algorithm");
        if (!algorithm.equals("SHA256withRSA") && !algorithm.equals("SHA256withECDSA")) {
            throw new SignerException("UNSUPPORTED_ALGORITHM", "Desteklenmeyen imza algoritması: " + algorithm
                    + " (SHA256withRSA veya SHA256withECDSA kullanın).");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(required(req, "data"));
        } catch (IllegalArgumentException e) {
            throw new SignerException("BAD_REQUEST", "'data' alanı geçerli base64 değil.");
        }
        if (data.length == 0) throw new SignerException("BAD_REQUEST", "'data' alanı boş.");
        String document = Json.string(req, "documentName");
        if (document == null || document.isEmpty()) document = "(adsız belge)";
        String claimed = Json.string(req, "origin");
        if (claimed != null && !claimed.equalsIgnoreCase(origin)) {
            Log.warn("İstek gövdesindeki origin (" + claimed + ") başlıktan (" + origin + ") farklı; başlık kullanılıyor.");
        }
        char[] pin = pinOf(req), entered = null;
        tokenLock.lockInterruptibly();
        try {
            CertInfo cert = store.find(certId);
            if (cert == null) {
                throw new SignerException("CERT_NOT_FOUND", "Sertifika bulunamadı (" + certId + "); sertifika listesini yenileyin.");
            }
            if (!mock) {
                if (ui) {
                    entered = Dialogs.confirmSign(origin, document, cert.subject(), pin == null);
                    if (entered == null) throw new SignerException("CANCELLED", "Kullanıcı imzalamayı iptal etti.");
                    if (pin == null && entered.length > 0) pin = entered;
                } else if (pin == null) {
                    throw new SignerException("PIN_REQUIRED", "Görüntü yokken (headless) PIN istekle birlikte gönderilmelidir.");
                }
            }
            byte[] signature = store.sign(cert, algorithm, data, pin);
            Log.info("İmzalandı: site=" + origin + " belge=" + document + " sertifika=" + cert.id + " alg=" + algorithm
                    + " (" + data.length + " bayt veri)");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("signature", Base64.getEncoder().encodeToString(signature));
            m.put("algorithm", algorithm);
            return m;
        } finally {
            tokenLock.unlock();
            wipe(pin);
            wipe(entered);
        }
    }

    /** Reads and parses the JSON body (at most 64 KB); an empty body is an empty object. */
    private static Map<String, Object> body(HttpExchange ex) throws IOException, SignerException {
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        try {
            if (declared != null && Long.parseLong(declared.trim()) > MAX_BODY) throw tooLarge();
        } catch (NumberFormatException e) {
            throw new SignerException("BAD_REQUEST", "Geçersiz Content-Length.");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = ex.getRequestBody()) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (buf.size() + n > MAX_BODY) throw tooLarge();
                buf.write(chunk, 0, n);
            }
        }
        if (buf.size() == 0) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = Json.object(Json.parse(new String(buf.toByteArray(), StandardCharsets.UTF_8)));
            if (m == null) throw new SignerException("BAD_REQUEST", "İstek gövdesi bir JSON nesnesi olmalı.");
            return m;
        } catch (IllegalArgumentException e) {
            throw new SignerException("BAD_REQUEST", "Geçersiz JSON: " + e.getMessage());
        }
    }

    private static SignerException tooLarge() {
        return new SignerException("BAD_REQUEST", "İstek gövdesi 64 KB sınırını aşıyor.");
    }

    private static String required(Map<String, Object> req, String key) throws SignerException {
        String v = Json.string(req, key);
        if (v == null || v.isEmpty()) throw new SignerException("BAD_REQUEST", "'" + key + "' alanı gerekli.");
        return v;
    }

    /** The optional "pin" field (a string; a bare integer is tolerated), or null when absent. */
    private static char[] pinOf(Map<String, Object> req) {
        Object v = req.get("pin");
        String s = v instanceof String ? (String) v : v instanceof Long ? v.toString() : null;
        return s == null || s.isEmpty() ? null : s.toCharArray();
    }

    private static void wipe(char[] secret) {
        if (secret != null) Arrays.fill(secret, '\0');
    }

    private static void cors(HttpExchange ex, String origin) {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", origin);
        h.set("Access-Control-Allow-Private-Network", "true");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Vary", "Origin");
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** e.g. {@code mac-aarch64}, {@code windows-x86_64}, {@code linux-x86_64}. */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String name = os.contains("mac") ? "mac" : os.contains("win") ? "windows" : os.contains("linux") ? "linux"
                : os.replaceAll("[^a-z0-9]", "");
        String arch = System.getProperty("os.arch", "");
        if (arch.equals("amd64")) arch = "x86_64";
        if (arch.equals("arm64")) arch = "aarch64";
        return name + "-" + arch;
    }
}
