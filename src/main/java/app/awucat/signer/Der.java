package app.awucat.signer;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Minimal DER encoder, just enough to build the mock mode's self-signed X.509 certificate. */
final class Der {
    private Der() {}

    static byte[] tlv(int tag, byte[]... parts) {
        byte[] content = concat(parts);
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int n = len <= 0xff ? 1 : len <= 0xffff ? 2 : len <= 0xffffff ? 3 : 4;
            out.write(0x80 | n);
            for (int i = n - 1; i >= 0; i--) out.write((len >>> (8 * i)) & 0xff);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] seq(byte[]... parts) { return tlv(0x30, parts); }

    static byte[] set(byte[]... parts) { return tlv(0x31, parts); }

    /** Context-specific [n] EXPLICIT wrapper. */
    static byte[] explicit(int n, byte[] content) { return tlv(0xA0 | n, content); }

    static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }

    static byte[] bool(boolean v) { return tlv(0x01, new byte[] {(byte) (v ? 0xff : 0x00)}); }

    static byte[] nul() { return new byte[] {0x05, 0x00}; }

    static byte[] octets(byte[] v) { return tlv(0x04, v); }

    static byte[] bits(byte[] v, int unusedBits) { return tlv(0x03, new byte[] {(byte) unusedBits}, v); }

    static byte[] utf8(String s) { return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8)); }

    static byte[] printable(String s) { return tlv(0x13, s.getBytes(StandardCharsets.US_ASCII)); }

    static byte[] oid(String dotted) {
        String[] arcs = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(arcs[0]) * 40 + Integer.parseInt(arcs[1]));
        for (int i = 2; i < arcs.length; i++) {
            long v = Long.parseLong(arcs[i]);
            int shift = 0;
            while ((v >>> (shift + 7)) != 0) shift += 7;
            for (; shift >= 0; shift -= 7) out.write((int) (((v >>> shift) & 0x7f) | (shift > 0 ? 0x80 : 0)));
        }
        return tlv(0x06, out.toByteArray());
    }

    /** UTCTime for years before 2050, GeneralizedTime afterwards, as X.509 requires. */
    static byte[] time(Instant t) {
        int year = t.atOffset(ZoneOffset.UTC).getYear();
        String pattern = year < 2050 ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'";
        String s = DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC).format(t);
        return tlv(year < 2050 ? 0x17 : 0x18, s.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}
