package app.awucat.signer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A PKCS#11 module (native library) known to the bridge: built-in default or user-configured. */
final class Pkcs11Module {
    /** ASCII identifier used as the first part of certificate ids, e.g. {@code akis}. */
    final String key;
    final String name;
    final String path;

    Pkcs11Module(String name, String path) {
        this.name = name;
        this.path = path;
        this.key = keyOf(name);
    }

    boolean present() {
        return Files.isRegularFile(Paths.get(path));
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("path", path);
        m.put("present", present());
        return m;
    }

    /** "AKİS" → "akis": strips diacritics and everything that is not ASCII alphanumeric. */
    static String keyOf(String name) {
        String k = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return k.isEmpty() ? "module" : k;
    }

    /** Default module locations for the current operating system (see SIGNER_PROTOCOL.md). */
    static List<Pkcs11Module> defaults() {
        List<Pkcs11Module> list = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String sys = System.getenv("SystemRoot") == null ? "C:\\Windows" : System.getenv("SystemRoot");
            String pf = System.getenv("ProgramFiles") == null ? "C:\\Program Files" : System.getenv("ProgramFiles");
            add(list, "AKİS", sys + "\\System32\\akisp11.dll", sys + "\\SysWOW64\\akisp11.dll");
            add(list, "Gemalto IDPrime", pf + "\\Gemalto\\IDGo 800 PKCS#11\\IDPrimePKCS1164.dll",
                    pf + "\\Gemalto\\Classic Client\\BIN\\gclib.dll");
            add(list, "SafeNet eToken", sys + "\\System32\\eTPKCS11.dll", sys + "\\SysWOW64\\eTPKCS11.dll");
            add(list, "OpenSC", pf + "\\OpenSC Project\\OpenSC\\pkcs11\\opensc-pkcs11.dll",
                    sys + "\\System32\\opensc-pkcs11.dll");
        } else if (os.contains("mac")) {
            add(list, "AKİS", "/usr/local/lib/libakisp11.dylib");
            add(list, "OpenSC", "/Library/OpenSC/lib/opensc-pkcs11.so", "/opt/homebrew/lib/opensc-pkcs11.so",
                    "/usr/local/lib/opensc-pkcs11.so");
            add(list, "SafeNet eToken", "/usr/local/lib/libeTPkcs11.dylib",
                    "/Library/Frameworks/eToken.framework/Versions/A/libeToken.dylib");
        } else {
            add(list, "AKİS", "/usr/lib/libakisp11.so", "/usr/lib64/libakisp11.so");
            add(list, "OpenSC", "/usr/lib/opensc-pkcs11.so", "/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so",
                    "/usr/lib/aarch64-linux-gnu/opensc-pkcs11.so", "/usr/lib64/opensc-pkcs11.so");
            add(list, "SafeNet eToken", "/usr/lib/libeTPkcs11.so", "/usr/lib/x86_64-linux-gnu/libeTPkcs11.so");
        }
        return list;
    }

    private static void add(List<Pkcs11Module> list, String name, String... paths) {
        for (String p : paths) list.add(new Pkcs11Module(name, p));
    }
}
