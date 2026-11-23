package vn.baohanh.util;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bộ tạo mã QR (chuẩn ISO/IEC 18004) tự viết, không cần thư viện ngoài.
 * Mã hóa dạng byte (UTF-8), mức sửa lỗi M (~15%), tự chọn phiên bản 1–40.
 * Dùng để in mã QR tra cứu bảo hành lên phiếu.
 */
public final class QrCode {
    // Số codeword sửa lỗi mỗi khối và số khối cho mức M, theo phiên bản (chỉ số 0 không dùng)
    private static final int[] ECC_PER_BLOCK_M = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26,
        26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28};
    private static final int[] NUM_BLOCKS_M = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14,
        16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49};
    private static final int FORMAT_BITS_M = 0;

    public final int version;
    public final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version) {
        this.version = version;
        this.size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
    }

    /** Tạo mã QR cho chuỗi văn bản. */
    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        for (; version <= 40; version++) {
            int ccBits = version <= 9 ? 8 : 16;
            if (4 + ccBits + data.length * 8 <= dataCodewords(version) * 8) break;
        }
        if (version > 40) throw new IllegalArgumentException("Dữ liệu quá dài cho mã QR");
        int ccBits = version <= 9 ? 8 : 16;
        int capacityBits = dataCodewords(version) * 8;

        // Chuỗi bit: chế độ byte (0100) + độ dài + dữ liệu + kết thúc + đệm
        BitBuffer bb = new BitBuffer();
        bb.append(0b0100, 4);
        bb.append(data.length, ccBits);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacityBits - bb.size()));
        bb.append(0, (8 - bb.size() % 8) % 8);
        for (int pad = 0xEC; bb.size() < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[bb.size() / 8];
        for (int i = 0; i < bb.size(); i++) codewords[i >>> 3] |= (byte) (bb.get(i) << (7 - (i & 7)));

        QrCode qr = new QrCode(version);
        qr.drawFunctionPatterns();
        qr.drawCodewords(qr.addEccAndInterleave(codewords));

        // Chọn mặt nạ có điểm phạt thấp nhất
        int best = 0;
        long minPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            qr.applyMask(m);
            qr.drawFormatBits(m);
            long p = qr.penalty();
            if (p < minPenalty) { minPenalty = p; best = m; }
            qr.applyMask(m); // mặt nạ là XOR nên áp lại để gỡ
        }
        qr.applyMask(best);
        qr.drawFormatBits(best);
        return qr;
    }

    public boolean get(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size && modules[y][x];
    }

    /** Ảnh SVG (vector, in sắc nét) với viền trắng border ô. */
    public String toSvg(int border) {
        StringBuilder sb = new StringBuilder();
        int dim = size + border * 2;
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(dim).append(' ').append(dim)
          .append("\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"");
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (modules[y][x]) sb.append('M').append(x + border).append(',').append(y + border).append("h1v1h-1z");
        return sb.append("\"/></svg>").toString();
    }

    /** Ảnh bitmap (dùng cho giao diện Swing). */
    public BufferedImage toImage(int scale, int border) {
        int dim = (size + border * 2) * scale;
        BufferedImage img = new BufferedImage(dim, dim, BufferedImage.TYPE_INT_RGB);
        int white = Color.WHITE.getRGB(), black = Color.BLACK.getRGB();
        for (int y = 0; y < dim; y++)
            for (int x = 0; x < dim; x++)
                img.setRGB(x, y, get(x / scale - border, y / scale - border) ? black : white);
        return img;
    }

    // ===================== Vẽ các mẫu cố định =====================

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {          // đường timing
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] pos = alignmentPositions();
        int n = pos.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) drawAlignment(pos[i], pos[j]);
        drawFormatBits(0);   // giữ chỗ, vẽ lại sau khi chọn mặt nạ
        drawVersion();
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int num = version / 7 + 2;
        int step = (version * 8 + num * 3 + 5) / (num * 4 - 4) * 2;
        int[] r = new int[num];
        r[0] = 6;
        for (int i = num - 1, p = size - 7; i >= 1; i--, p -= step) r[i] = p;
        return r;
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_BITS_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3, c = i / 3;
            set(a, c, b);
            set(c, a, b);
        }
    }

    // ===================== Dữ liệu & sửa lỗi Reed–Solomon =====================

    private static int rawDataModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int num = ver / 7 + 2;
            r -= (25 * num - 10) * num - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int ver) {
        return rawDataModules(ver) / 8 - ECC_PER_BLOCK_M[ver] * NUM_BLOCKS_M[ver];
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = NUM_BLOCKS_M[version];
        int eccLen = ECC_PER_BLOCK_M[version];
        int raw = rawDataModules(version) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;
        byte[] divisor = rsDivisor(eccLen);
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks.add(block);
        }
        byte[] result = new byte[raw];
        int idx = 0;
        for (int i = 0; i < shortLen + 1; i++)
            for (int j = 0; j < numBlocks; j++)
                if (i != shortLen - eccLen || j >= numShort) result[idx++] = blocks.get(j)[i];
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] r = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return r;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) continue;
                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                };
                modules[y][x] ^= invert;
            }
    }

    /** Điểm phạt (quy tắc 1, 2, 4 của chuẩn) để chọn mặt nạ đẹp. */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
                    boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    if (cur == prev) { run++; if (run == 5) p += 3; else if (run > 5) p++; }
                    else run = 1;
                }
            }
        }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
            }
        int dark = 0;
        for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
        int total = size * size;
        p += Math.max(0, (Math.abs(dark * 20L - total * 10L) + total - 1) / total - 1) * 10;
        return p;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class BitBuffer {
        private final List<Integer> bits = new ArrayList<>();
        void append(int val, int len) { for (int i = len - 1; i >= 0; i--) bits.add((val >>> i) & 1); }
        int size() { return bits.size(); }
        int get(int i) { return bits.get(i); }
    }
}
