package app.awucat.signer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A protocol-level error: carries the error {@code code} from SIGNER_PROTOCOL.md, a Turkish
 * human-readable message and the HTTP status used for the response.
 */
final class SignerException extends Exception {
    private static final long serialVersionUID = 1L;

    final String code;
    final int status;
    /** Remaining PIN tries for {@code PIN_INCORRECT}, when the token reports it; otherwise null. */
    final Integer remaining;

    SignerException(String code, String message) {
        this(code, message, null);
    }

    SignerException(String code, String message, Integer remaining) {
        super(message);
        this.code = code;
        this.status = statusFor(code);
        this.remaining = remaining;
    }

    /** Body of an error response: {@code {"error":{"code","message"[,"remaining"]}}}. */
    Map<String, Object> toJson() {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", getMessage());
        if (remaining != null) err.put("remaining", remaining);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", err);
        return root;
    }

    private static int statusFor(String code) {
        switch (code) {
            case "BAD_REQUEST":
            case "UNSUPPORTED_ALGORITHM": return 400;
            case "PIN_REQUIRED":
            case "PIN_INCORRECT": return 401;
            case "ORIGIN_DENIED": return 403;
            case "CERT_NOT_FOUND":
            case "NOT_FOUND": return 404;
            case "CANCELLED": return 409;
            case "PIN_LOCKED": return 423;
            case "MODULE_NOT_FOUND":
            case "NO_CARD": return 503;
            default: return 500; // INTERNAL
        }
    }
}
