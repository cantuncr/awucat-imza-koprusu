package app.awucat.signer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.security.AuthProvider;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real smart cards through the JDK's SunPKCS11 provider. One provider instance is created per
 * (module, slot index) and kept for the lifetime of the process; the token is logged in with the
 * PIN for exactly one operation and logged out again, so the PIN is never cached anywhere.
 *
 * <p>Provider creation goes through reflection so the class compiles with {@code --release 8}:
 * {@code Provider.configure(String)} on Java 9+, {@code new SunPKCS11(InputStream)} on Java 8.
 */
final class Pkcs11Store implements TokenStore {
    private static final int MAX_SLOTS = 8;
    private static final long RETRY_FAILED_LIBRARY_MS = 5 * 60_000L;
    // CK_TOKEN_INFO.flags bits (PKCS#11 v2.20)
    private static final long CKF_USER_PIN_FINAL_TRY = 0x00020000L;
    private static final long CKF_USER_PIN_LOCKED = 0x00040000L;

    private final List<Pkcs11Module> modules = new ArrayList<>();
    /** "libraryPath#slotIndex" → provider. */
    private final Map<String, Provider> providers = new HashMap<>();
    /** Library path → time until which loading is not retried after a failure. */
    private final Map<String, Long> failedUntil = new HashMap<>();
    /** Certificate id → entry, refreshed by every successful listing. */
    private final Map<String, CertInfo> cache = new LinkedHashMap<>();

    Pkcs11Store(Config config) {
        Set<String> paths = new HashSet<>();
        for (Pkcs11Module m : config.modules) if (paths.add(m.path)) modules.add(m);
        for (Pkcs11Module m : Pkcs11Module.defaults()) if (paths.add(m.path)) modules.add(m);
    }

    @Override
    public List<Map<String, Object>> moduleStatus() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Pkcs11Module m : modules) list.add(m.toJson());
        return list;
    }

    @Override
    public boolean cardPresent() {
        for (Pkcs11Module m : modules) {
            if (!m.present()) continue;
            for (Provider p : slotProviders(m)) if (keyStore(p) != null) return true;
        }
        return false;
    }

    @Override
    public List<CertInfo> listCertificates(char[] pin) throws SignerException {
        boolean anyModule = false, anyLoaded = false, anyToken = false;
        Map<String, CertInfo> found = new LinkedHashMap<>();
        Set<String> seenDigests = new HashSet<>(); // the same card seen through two modules
        for (Pkcs11Module m : modules) {
            if (!m.present()) continue;
            anyModule = true;
            List<Provider> slots = slotProviders(m);
            if (!failedUntil.containsKey(m.path)) anyLoaded = true;
            for (int i = 0; i < slots.size(); i++) {
                Provider p = slots.get(i);
                KeyStore ks = keyStore(p);
                if (ks == null) continue; // empty reader
                anyToken = true;
                try {
                    load(ks, p, pin);
                    for (Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
                        String alias = e.nextElement();
                        if (!ks.isKeyEntry(alias)) continue; // certificate without a private key
                        Certificate c = ks.getCertificate(alias);
                        if (!(c instanceof X509Certificate)) continue;
                        CertInfo info = new CertInfo(m.key, i, (X509Certificate) c, p, alias);
                        if (seenDigests.add(CertInfo.digest(info.der))) found.put(info.id, info);
                    }
                } catch (GeneralSecurityException e) {
                    throw translate(e, p);
                } finally {
                    logout(p);
                }
            }
        }
        if (!anyModule) {
            throw new SignerException("MODULE_NOT_FOUND", "PKCS#11 kütüphanesi bulunamadı. Kart sürücüsünü (AKİS, "
                    + "eToken, OpenSC) kurun veya yapılandırma dosyasına modül ekleyin.");
        }
        if (!anyLoaded) {
            throw new SignerException("MODULE_NOT_FOUND", "PKCS#11 kütüphanesi yüklenemedi; ayrıntılar günlük dosyasında. "
                    + "Kütüphanenin Java ile aynı mimaride (64-bit) olduğundan emin olun.");
        }
        if (!anyToken) throw new SignerException("NO_CARD", "Akıllı kart takılı değil veya kart okuyucu bulunamadı.");
        cache.clear();
        cache.putAll(found);
        Log.info(found.size() + " imza sertifikası bulundu");
        return new ArrayList<>(found.values());
    }

    @Override
    public CertInfo find(String certificateId) {
        return cache.get(certificateId);
    }

    @Override
    public byte[] sign(CertInfo cert, String algorithm, byte[] data, char[] pin) throws SignerException {
        Provider p = cert.provider;
        KeyStore ks = keyStore(p);
        if (ks == null) throw new SignerException("NO_CARD", "Kart çıkarılmış görünüyor; kartı takıp yeniden deneyin.");
        try {
            load(ks, p, pin);
            Key key = ks.getKey(cert.alias, null);
            Certificate onCard = ks.getCertificate(cert.alias);
            if (!(key instanceof PrivateKey) || onCard == null || !Arrays.equals(onCard.getEncoded(), cert.der)) {
                throw new SignerException("CERT_NOT_FOUND", "Sertifika artık kartta bulunamadı; sertifika listesini yenileyin.");
            }
            String keyAlg = key.getAlgorithm();
            boolean match = algorithm.endsWith("ECDSA") ? "EC".equals(keyAlg) : "RSA".equals(keyAlg);
            if (!match) {
                throw new SignerException("UNSUPPORTED_ALGORITHM",
                        "Sertifikanın anahtarı " + keyAlg + " türünde; " + algorithm + " ile imzalanamaz.");
            }
            Signature sig = Signature.getInstance(algorithm, p);
            sig.initSign((PrivateKey) key);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw translate(e, p);
        } finally {
            logout(p);
        }
    }

    /** Providers for the module's slots (index 0..n-1); empty when the library cannot be loaded or has no reader. */
    private List<Provider> slotProviders(Pkcs11Module m) {
        List<Provider> list = new ArrayList<>();
        Long until = failedUntil.get(m.path);
        if (until != null && until > System.currentTimeMillis()) return list;
        failedUntil.remove(m.path);
        for (int i = 0; i < MAX_SLOTS; i++) {
            String key = m.path + "#" + i;
            Provider p = providers.get(key);
            if (p == null) {
                try {
                    p = createProvider(m.key + i, m.path, i);
                    providers.put(key, p);
                    Log.info("PKCS#11 sağlayıcı hazır: " + m.name + ", yuva " + i);
                } catch (Exception e) {
                    // "slotListIndex is N but token only has N slots": no more readers, not an error.
                    if (chainMessages(e).contains("slotListIndex")) break;
                    failedUntil.put(m.path, System.currentTimeMillis() + RETRY_FAILED_LIBRARY_MS);
                    Log.warn("PKCS#11 kütüphanesi yüklenemedi (" + m.path + "): " + rootMessage(e));
                    break;
                }
            }
            list.add(p);
        }
        return list;
    }

    /** SunPKCS11 for one slot. The library path is quoted and uses forward slashes (works on Windows too). */
    private static Provider createProvider(String name, String library, int slotIndex) throws Exception {
        String cfg = "name = " + name + "\nlibrary = \"" + library.replace('\\', '/').replace("\"", "\\\"")
                + "\"\nslotListIndex = " + slotIndex + "\n";
        Provider base = Security.getProvider("SunPKCS11");
        if (base != null) {
            try { // Java 9+: Provider.configure(String) with an inline ("--") configuration.
                Method configure = Provider.class.getMethod("configure", String.class);
                return (Provider) configure.invoke(base, "--" + cfg);
            } catch (NoSuchMethodException e) {
                // Java 8 with a statically configured SunPKCS11 entry: fall through to the constructor.
            }
        }
        Class<?> cls = Class.forName("sun.security.pkcs11.SunPKCS11");
        InputStream in = new ByteArrayInputStream(cfg.getBytes(Charset.defaultCharset()));
        return (Provider) cls.getConstructor(InputStream.class).newInstance(in);
    }

    /** Non-null only while a token is present: SunPKCS11 registers its services on insertion. */
    private static KeyStore keyStore(Provider p) {
        try {
            return KeyStore.getInstance("PKCS11", p);
        } catch (KeyStoreException e) {
            return null;
        }
    }

    /** Logs in (when {@code pin} is non-null) and reads the token's entries. */
    private static void load(KeyStore ks, Provider p, char[] pin) throws SignerException {
        try {
            ks.load(null, pin);
        } catch (IOException | GeneralSecurityException e) {
            throw translate(e, p);
        }
    }

    private static void logout(Provider p) {
        try {
            ((AuthProvider) p).logout();
        } catch (Exception e) {
            // not logged in or token removed: nothing to undo
        }
    }

    /** Maps SunPKCS11 exception chains (CKR_* messages) to protocol error codes. */
    private static SignerException translate(Throwable t, Provider p) {
        String chain = chainMessages(t);
        if (chain.contains("no password provided")) return new SignerException("PIN_REQUIRED", "Kart PIN'i gerekli.");
        if (chain.contains("CKR_PIN_INCORRECT") || chain.contains("FailedLoginException")) {
            Integer remaining = remainingTries(p);
            if (remaining != null && remaining == 0) return pinLocked();
            String msg = remaining == null ? "PIN hatalı." : "PIN hatalı. Kalan deneme: " + remaining;
            return new SignerException("PIN_INCORRECT", msg, remaining);
        }
        if (chain.contains("CKR_PIN_LOCKED")) return pinLocked();
        if (chain.contains("CKR_PIN_LEN_RANGE")) return new SignerException("PIN_INCORRECT", "PIN uzunluğu geçersiz.");
        if (chain.contains("CKR_TOKEN_NOT_PRESENT") || chain.contains("CKR_DEVICE_REMOVED")
                || chain.contains("No token present") || chain.contains("Token has been removed")
                || chain.contains("CKR_SLOT_ID_INVALID") || chain.contains("CKR_SESSION_HANDLE_INVALID")) {
            return new SignerException("NO_CARD", "Kart okunamadı; kartın takılı olduğundan emin olun.");
        }
        if (chain.contains("CKR_MECHANISM_INVALID") || chain.contains("NoSuchAlgorithmException")) {
            return new SignerException("UNSUPPORTED_ALGORITHM", "Kart bu imza algoritmasını desteklemiyor.");
        }
        Log.error("Kart işlemi başarısız", t);
        return new SignerException("INTERNAL", "Kart işlemi başarısız: " + rootMessage(t));
    }

    private static SignerException pinLocked() {
        return new SignerException("PIN_LOCKED", "PIN kilitlendi. Kartın kilidini PUK ile açmanız gerekir.");
    }

    /**
     * Best effort: reads CK_TOKEN_INFO.flags through reflection (needs the jar manifest's
     * Add-Opens on Java 9+) to report 0 (locked) or 1 (final try). Null when unknown.
     */
    private static Integer remainingTries(Provider p) {
        try {
            Field fp11 = p.getClass().getDeclaredField("p11");
            Field fslot = p.getClass().getDeclaredField("slotID");
            fp11.setAccessible(true);
            fslot.setAccessible(true);
            Object p11 = fp11.get(p);
            Method getInfo = p11.getClass().getMethod("C_GetTokenInfo", long.class);
            getInfo.setAccessible(true);
            Object info = getInfo.invoke(p11, fslot.getLong(p));
            long flags = info.getClass().getField("flags").getLong(info);
            if ((flags & CKF_USER_PIN_LOCKED) != 0) return 0;
            if ((flags & CKF_USER_PIN_FINAL_TRY) != 0) return 1;
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String chainMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 4000; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
