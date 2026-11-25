package vn.baohanh.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã dùng một lần theo thời gian (TOTP – RFC 6238), tương thích Google Authenticator,
 * Microsoft Authenticator, Authy...
 * <ul>
 *   <li>Khóa bí mật 20 byte, hiển thị dạng Base32 (RFC 4648).</li>
 *   <li>HMAC-SHA1, bước 30 giây, 6 chữ số.</li>
 * </ul>
 */
public final class Totp {
    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** Sinh khóa bí mật ngẫu nhiên (Base32, 32 ký tự). */
    public static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return base32Encode(b);
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : data) {
            buffer = (buffer << 8) | (x & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    /** Giải Base32; bỏ qua dấu cách, gạch ngang, dấu '=' và không phân biệt hoa thường. */
    public static byte[] base32Decode(String s) {
        String clean = s.replaceAll("[\\s=-]", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Ký tự Base32 không hợp lệ: " + c);
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        return out.array();
    }

    /** Bước thời gian hiện tại (số khoảng 30 giây kể từ 1970). */
    public static long currentStep() {
        return System.currentTimeMillis() / 1000 / STEP_SECONDS;
    }

    /** Mã 6 chữ số tại một bước thời gian (RFC 4226 – cắt động). */
    public static String code(String secretBase32, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secretBase32), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int off = h[h.length - 1] & 0x0F;
            int bin = ((h[off] & 0x7F) << 24) | ((h[off + 1] & 0xFF) << 16) | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Kiểm tra mã, cho lệch ±window bước (đồng hồ điện thoại lệch vài chục giây).
     * Trả về bước khớp, hoặc -1 nếu sai. Chỉ nhận bước lớn hơn lastUsed để chống dùng lại mã.
     */
    public static long match(String secretBase32, String code, long now, int window, long lastUsed) {
        if (code == null) return -1;
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{" + DIGITS + "}")) return -1;
        for (int d = -window; d <= window; d++) {
            long step = now + d;
            if (step <= lastUsed) continue;
            if (constantEq(code(secretBase32, step), c)) return step;
        }
        return -1;
    }

    /** Địa chỉ otpauth:// để ứng dụng xác thực quét bằng mã QR. */
    public static String otpauthUrl(String issuer, String account, String secretBase32) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean constantEq(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Hiển thị khóa theo nhóm 4 ký tự cho dễ gõ tay. */
    public static String group(String secret) {
        return secret.replaceAll("(.{4})(?=.)", "$1 ");
    }
}
