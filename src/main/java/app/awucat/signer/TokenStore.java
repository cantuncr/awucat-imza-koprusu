package app.awucat.signer;

import java.util.List;
import java.util.Map;

/**
 * Source of signing certificates and signatures: real PKCS#11 tokens ({@link Pkcs11Store}) or the
 * in-memory test key ({@link MockStore}). Callers serialise access; implementations need not be
 * thread-safe. A {@code pin} of null means "no PIN available": implementations must throw
 * {@code PIN_REQUIRED} if the token insists on one.
 */
interface TokenStore {
    /** Module entries for {@code GET /status}. */
    List<Map<String, Object>> moduleStatus();

    /** True when at least one slot has a token inserted. */
    boolean cardPresent();

    /** Lists certificates that have a private key on a present token; refreshes the id cache. */
    List<CertInfo> listCertificates(char[] pin) throws SignerException;

    /** Looks up a certificate from the last listing, or null when the id is unknown. */
    CertInfo find(String certificateId);

    /** Signs {@code data} with the certificate's private key using {@code algorithm}. */
    byte[] sign(CertInfo cert, String algorithm, byte[] data, char[] pin) throws SignerException;
}
