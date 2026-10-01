package app.awucat.signer;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code --mock}: an in-memory 2048-bit RSA key with a self-signed certificate
 * "CN=TEST İMZACI, SERIALNUMBER=11111111110, C=TR". No dialogs, no PIN. Signatures are real
 * RSA signatures but the certificate is not qualified and will be rejected by UYAP.
 */
final class MockStore implements TokenStore {
    private static final String SHA256_RSA_OID = "1.2.840.113549.1.1.11";

    private final KeyPair keyPair;
    private final CertInfo info;

    MockStore() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        info = new CertInfo("mock", 0, selfSigned(keyPair), null, null);
    }

    @Override
    public List<Map<String, Object>> moduleStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Deneme (mock)");
        m.put("path", "memory");
        m.put("present", true);
        return Collections.singletonList(m);
    }

    @Override
    public boolean cardPresent() {
        return true;
    }

    @Override
    public List<CertInfo> listCertificates(char[] pin) {
        return Collections.singletonList(info);
    }

    @Override
    public CertInfo find(String certificateId) {
        return info.id.equals(certificateId) ? info : null;
    }

    @Override
    public byte[] sign(CertInfo cert, String algorithm, byte[] data, char[] pin) throws SignerException {
        if (!"SHA256withRSA".equals(algorithm)) {
            throw new SignerException("UNSUPPORTED_ALGORITHM", "Deneme anahtarı RSA'dır; yalnızca SHA256withRSA desteklenir.");
        }
        try {
            Signature sig = Signature.getInstance(algorithm);
            sig.initSign(keyPair.getPrivate());
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new SignerException("INTERNAL", "Deneme imzası oluşturulamadı: " + e.getMessage());
        }
    }

    /** Builds a v3 certificate with keyUsage = digitalSignature + nonRepudiation (critical). */
    static X509Certificate selfSigned(KeyPair kp) throws GeneralSecurityException {
        byte[] sigAlg = Der.seq(Der.oid(SHA256_RSA_OID), Der.nul());
        byte[] name = Der.seq(
                Der.set(Der.seq(Der.oid("2.5.4.6"), Der.printable("TR"))),
                Der.set(Der.seq(Der.oid("2.5.4.5"), Der.printable("11111111110"))),
                Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8("TEST İMZACI"))));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        byte[] extensions = Der.seq(
                // keyUsage: bit 0 digitalSignature, bit 1 nonRepudiation → 0b11000000, 6 unused bits
                Der.seq(Der.oid("2.5.29.15"), Der.bool(true), Der.octets(Der.bits(new byte[] {(byte) 0xC0}, 6))),
                // basicConstraints: cA = FALSE (default, so an empty SEQUENCE)
                Der.seq(Der.oid("2.5.29.19"), Der.bool(true), Der.octets(Der.seq())));
        byte[] tbs = Der.seq(
                Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
                Der.integer(new BigInteger(1, serial)),
                sigAlg,
                name,
                Der.seq(Der.time(now.minus(1, ChronoUnit.DAYS)), Der.time(now.plus(730, ChronoUnit.DAYS))),
                name,
                kp.getPublic().getEncoded(),
                Der.explicit(3, extensions));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] cert = Der.seq(tbs, sigAlg, Der.bits(sig.sign(), 0));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert));
    }
}
