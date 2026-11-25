package vn.baohanh.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã hóa file sao lưu bằng mật khẩu (chỉ dùng thư viện có sẵn của JDK).
 *
 * <pre>
 * Khóa    = PBKDF2-HMAC-SHA256(mật khẩu, salt 16 byte ngẫu nhiên, 210.000 vòng) → 256 bit
 * Mã hóa  = AES-256-GCM, chia khối 1 MB (đọc/ghi từng phần, không phải nạp cả file vào RAM)
 * Cấu trúc file:
 *   "BHDTENC1" (8) | số vòng (4) | salt (16) | tiền tố nonce (8)
 *   lặp lại: độ dài khối mã hóa (4) | khối mã hóa + thẻ xác thực 16 byte
 * Nonce khối i = tiền tố (8) + i (4). Dữ liệu xác thực kèm (AAD) = phần đầu file + i + cờ "khối cuối"
 * → đổi thứ tự, cắt bớt hay sửa 1 byte đều bị phát hiện.
 * </pre>
 * Không có mật khẩu thì không đọc được gì; nhập sai mật khẩu sẽ báo lỗi chứ không ra dữ liệu rác.
 */
public final class BackupCrypto {
    public static final byte[] MAGIC = "BHDTENC1".getBytes(StandardCharsets.US_ASCII);
    public static final int ITERATIONS = 210_000;
    private static final int CHUNK = 1 << 20;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {}

    /** Mật khẩu sai hoặc file bị sửa / hỏng. */
    public static class BadPasswordException extends IOException {
        private static final long serialVersionUID = 1L;
        public BadPasswordException() { super("Sai mật khẩu sao lưu hoặc file đã bị sửa / hỏng"); }
    }

    public static boolean isEncrypted(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] h = in.readNBytes(MAGIC.length);
            return Arrays.equals(h, MAGIC);
        }
    }

    public static void encrypt(Path in, Path out, char[] password) throws IOException {
        try (InputStream is = Files.newInputStream(in); OutputStream os = Files.newOutputStream(out)) {
            encrypt(is, os, password, ITERATIONS);
        }
    }

    public static void decrypt(Path in, Path out, char[] password) throws IOException {
        try (InputStream is = Files.newInputStream(in); OutputStream os = Files.newOutputStream(out)) {
            decrypt(is, os, password);
        }
    }

    public static void encrypt(InputStream plain, OutputStream outRaw, char[] password, int iterations) throws IOException {
        byte[] salt = new byte[16], prefix = new byte[8];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(prefix);
        byte[] header = header(iterations, salt, prefix);
        SecretKey key = derive(password, salt, iterations);
        DataOutputStream out = new DataOutputStream(outRaw);
        out.write(header);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] cur = plain.readNBytes(CHUNK);
            int index = 0;
            while (true) {
                byte[] next = cur.length < CHUNK ? new byte[0] : plain.readNBytes(CHUNK);
                boolean last = next.length == 0;
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce(prefix, index)));
                c.updateAAD(aad(header, index, last));
                byte[] enc = c.doFinal(cur);
                out.writeInt(enc.length);
                out.write(enc);
                if (last) break;
                cur = next;
                index++;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Lỗi mã hóa: " + e.getMessage(), e);
        }
        out.flush();
    }

    public static void decrypt(InputStream inRaw, OutputStream out, char[] password) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(inRaw));
        byte[] magic = in.readNBytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("Không phải file sao lưu đã mã hóa");
        int iterations = in.readInt();
        if (iterations < 10_000 || iterations > 10_000_000) throw new IOException("Phần đầu file sao lưu không hợp lệ");
        byte[] salt = in.readNBytes(16), prefix = in.readNBytes(8);
        if (salt.length < 16 || prefix.length < 8) throw new IOException("File sao lưu bị cắt cụt");
        byte[] header = header(iterations, salt, prefix);
        SecretKey key = derive(password, salt, iterations);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            int index = 0;
            while (true) {
                int len;
                try { len = in.readInt(); } catch (EOFException e) { throw new BadPasswordException(); }   // thiếu khối cuối
                if (len < 16 || len > CHUNK + 16) throw new BadPasswordException();
                byte[] enc = in.readNBytes(len);
                if (enc.length < len) throw new BadPasswordException();
                boolean last = peekEnd(in);
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce(prefix, index)));
                c.updateAAD(aad(header, index, last));
                byte[] plain;
                try { plain = c.doFinal(enc); } catch (AEADBadTagException e) { throw new BadPasswordException(); }
                out.write(plain);
                if (last) break;
                index++;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Lỗi giải mã: " + e.getMessage(), e);
        }
        out.flush();
    }

    /** Hết dữ liệu chưa (đọc thử 1 byte qua mark/reset). */
    private static boolean peekEnd(DataInputStream in) throws IOException {
        in.mark(1);
        int b = in.read();
        if (b < 0) return true;
        in.reset();
        return false;
    }

    private static byte[] header(int iterations, byte[] salt, byte[] prefix) {
        return ByteBuffer.allocate(MAGIC.length + 4 + 16 + 8).put(MAGIC).putInt(iterations).put(salt).put(prefix).array();
    }

    private static byte[] nonce(byte[] prefix, int index) {
        return ByteBuffer.allocate(12).put(prefix).putInt(index).array();
    }

    private static byte[] aad(byte[] header, int index, boolean last) {
        return ByteBuffer.allocate(header.length + 5).put(header).putInt(index).put((byte) (last ? 1 : 0)).array();
    }

    private static SecretKey derive(char[] password, byte[] salt, int iterations) throws IOException {
        if (password == null || password.length == 0) throw new IOException("Chưa nhập mật khẩu sao lưu");
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IOException("Lỗi tạo khóa: " + e.getMessage(), e);
        }
    }
}
