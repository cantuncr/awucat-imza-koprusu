package app.awucat.signer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.security.auth.x500.X500Principal;

/** A signing certificate found on a token (or the mock key), plus how to reach its private key. */
final class CertInfo {
    private static final String[] KEY_USAGE = {
        "digitalSignature", "nonRepudiation", "keyEncipherment", "dataEncipherment", "keyAgreement",
        "keyCertSign", "cRLSign", "encipherOnly", "decipherOnly"
    };

    /** Stable id {@code module:slot:digest}, e.g. {@code akis:0:1a2b…}. */
    final String id;
    final X509Certificate cert;
    final byte[] der;
    /** PKCS#11 provider owning the private key; null for the mock store. */
    final Provider provider;
    /** KeyStore alias of the entry on the token; null for the mock store. */
    final String alias;

    CertInfo(String moduleKey, int slot, X509Certificate cert, Provider provider, String alias)
            throws CertificateEncodingException {
        this.cert = cert;
        this.der = cert.getEncoded();
        this.provider = provider;
        this.alias = alias;
        this.id = moduleKey + ":" + slot + ":" + digest(der).substring(0, 16);
    }

    String subject() {
        return dn(cert.getSubjectX500Principal());
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("der", Base64.getEncoder().encodeToString(der));
        m.put("subject", subject());
        m.put("issuer", dn(cert.getIssuerX500Principal()));
        String serial = cert.getSerialNumber().toString(16).toUpperCase(Locale.ROOT);
        m.put("serialHex", serial.length() % 2 == 1 ? "0" + serial : serial);
        m.put("notBefore", cert.getNotBefore().toInstant().toString());
        m.put("notAfter", cert.getNotAfter().toInstant().toString());
        m.put("keyUsage", keyUsage(cert));
        m.put("expired", new Date().after(cert.getNotAfter()));
        return m;
    }

    static List<String> keyUsage(X509Certificate cert) {
        List<String> names = new ArrayList<>();
        boolean[] bits = cert.getKeyUsage();
        if (bits != null) {
            for (int i = 0; i < bits.length && i < KEY_USAGE.length; i++) if (bits[i]) names.add(KEY_USAGE[i]);
        }
        return names;
    }

    /** "CN=AYŞE YILMAZ, SERIALNUMBER=12345678901, C=TR" style, as in the protocol samples. */
    static String dn(X500Principal p) {
        return p.toString();
    }

    static String digest(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }
}
