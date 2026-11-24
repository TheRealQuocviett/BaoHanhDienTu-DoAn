package vn.baohanh.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mã vạch một chiều, viết thuần Java (không dùng thư viện ngoài):
 * <ul>
 *   <li>Đọc từ ảnh chụp: Code 128 (IMEI, serial điện thoại/laptop), Code 39 (serial cũ) và EAN-13 / UPC-A (mã sản phẩm trên hộp).</li>
 *   <li>Tạo mã Code 128 (in trên phiếu tiếp nhận để lần sau quét lại).</li>
 * </ul>
 * Cách đọc: lấy mẫu độ sáng dọc theo nhiều đường thẳng (ngang, dọc, nghiêng ±8°, ±16°) cắt qua ảnh,
 * phân ngưỡng cục bộ, đo độ rộng các vạch đen/trắng (nội suy dưới mức điểm ảnh) rồi so với bảng mẫu
 * của từng chuẩn. Kết quả có mã kiểm tra (Code 128, EAN) chỉ cần một đường đọc đúng; Code 39 cần ≥ 2 đường trùng nhau.
 */
public final class Barcode {
    private Barcode() {}

    public record Result(String format, String text, int hits) {
        /** Gợi ý loại mã: IMEI (15 số, đúng thuật toán Luhn), mã sản phẩm EAN, hay serial. */
        public String kind() {
            if (text.matches("\\d{15}") && luhn(text)) return "IMEI";
            if (format.startsWith("EAN") || format.startsWith("UPC")) return "Mã sản phẩm";
            return "Serial";
        }
    }

    // ===================== BẢNG MẪU =====================

    static final int[][] C128 = {
            {2,1,2,2,2,2},{2,2,2,1,2,2},{2,2,2,2,2,1},{1,2,1,2,2,3},{1,2,1,3,2,2},{1,3,1,2,2,2},{1,2,2,2,1,3},{1,2,2,3,1,2},
            {1,3,2,2,1,2},{2,2,1,2,1,3},{2,2,1,3,1,2},{2,3,1,2,1,2},{1,1,2,2,3,2},{1,2,2,1,3,2},{1,2,2,2,3,1},{1,1,3,2,2,2},
            {1,2,3,1,2,2},{1,2,3,2,2,1},{2,2,3,2,1,1},{2,2,1,1,3,2},{2,2,1,2,3,1},{2,1,3,2,1,2},{2,2,3,1,1,2},{3,1,2,1,3,1},
            {3,1,1,2,2,2},{3,2,1,1,2,2},{3,2,1,2,2,1},{3,1,2,2,1,2},{3,2,2,1,1,2},{3,2,2,2,1,1},{2,1,2,1,2,3},{2,1,2,3,2,1},
            {2,3,2,1,2,1},{1,1,1,3,2,3},{1,3,1,1,2,3},{1,3,1,3,2,1},{1,1,2,3,1,3},{1,3,2,1,1,3},{1,3,2,3,1,1},{2,1,1,3,1,3},
            {2,3,1,1,1,3},{2,3,1,3,1,1},{1,1,2,1,3,3},{1,1,2,3,3,1},{1,3,2,1,3,1},{1,1,3,1,2,3},{1,1,3,3,2,1},{1,3,3,1,2,1},
            {3,1,3,1,2,1},{2,1,1,3,3,1},{2,3,1,1,3,1},{2,1,3,1,1,3},{2,1,3,3,1,1},{2,1,3,1,3,1},{3,1,1,1,2,3},{3,1,1,3,2,1},
            {3,3,1,1,2,1},{3,1,2,1,1,3},{3,1,2,3,1,1},{3,3,2,1,1,1},{3,1,4,1,1,1},{2,2,1,4,1,1},{4,3,1,1,1,1},{1,1,1,2,2,4},
            {1,1,1,4,2,2},{1,2,1,1,2,4},{1,2,1,4,2,1},{1,4,1,1,2,2},{1,4,1,2,2,1},{1,1,2,2,1,4},{1,1,2,4,1,2},{1,2,2,1,1,4},
            {1,2,2,4,1,1},{1,4,2,1,1,2},{1,4,2,2,1,1},{2,4,1,2,1,1},{2,2,1,1,1,4},{4,1,3,1,1,1},{2,4,1,1,1,2},{1,3,4,1,1,1},
            {1,1,1,2,4,2},{1,2,1,1,4,2},{1,2,1,2,4,1},{1,1,4,2,1,2},{1,2,4,1,1,2},{1,2,4,2,1,1},{4,1,1,2,1,2},{4,2,1,1,1,2},
            {4,2,1,2,1,1},{2,1,2,1,4,1},{2,1,4,1,2,1},{4,1,2,1,2,1},{1,1,1,1,4,3},{1,1,1,3,4,1},{1,3,1,1,4,1},{1,1,4,1,1,3},
            {1,1,4,3,1,1},{4,1,1,1,1,3},{4,1,1,3,1,1},{1,1,3,1,4,1},{1,1,4,1,3,1},{3,1,1,1,4,1},{4,1,1,1,3,1},{2,1,1,4,1,2},
            {2,1,1,2,1,4},{2,1,1,2,3,2},{2,3,3,1,1,1,2}};
    static final int START_A = 103, START_B = 104, START_C = 105, STOP = 106;

    static final String C39_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%";
    static final int[] C39 = {
            0x034, 0x121, 0x061, 0x160, 0x031, 0x130, 0x070, 0x025, 0x124, 0x064,
            0x109, 0x049, 0x148, 0x019, 0x118, 0x058, 0x00D, 0x10C, 0x04C, 0x01C,
            0x103, 0x043, 0x142, 0x013, 0x112, 0x052, 0x007, 0x106, 0x046, 0x016,
            0x181, 0x0C1, 0x1C0, 0x091, 0x190, 0x0D0, 0x085, 0x184, 0x0C4, 0x0A8,
            0x0A2, 0x08A, 0x02A};
    static final int C39_STAR = 0x094;

    static final int[][] EAN_L = {{3,2,1,1},{2,2,2,1},{2,1,2,2},{1,4,1,1},{1,1,3,2},{1,2,3,1},{1,1,1,4},{1,3,1,2},{1,2,1,3},{3,1,1,2}};
    static final int[][] EAN_LG = new int[20][];
    static final int[] EAN_FIRST = {0x00, 0x0B, 0x0D, 0x0E, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A};

    static {
        for (int i = 0; i < 10; i++) {
            EAN_LG[i] = EAN_L[i];
            int[] r = EAN_L[i];
            EAN_LG[10 + i] = new int[]{r[3], r[2], r[1], r[0]};
        }
    }

    // ===================== ĐỌC TỪ ẢNH =====================

    /** Đọc mọi mã vạch thấy được trong ảnh, mã đọc được nhiều lần nhất đứng trước. */
    public static List<Result> decode(BufferedImage src) {
        BufferedImage img = scaleDown(src, 2400);
        int w = img.getWidth(), h = img.getHeight();
        int[] lum = new int[w * h];
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            lum[i] = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
        }
        Map<String, int[]> votes = new LinkedHashMap<>();   // "format|text" -> {hits}
        double[] angles = {0, 4, -4, 8, -8, 12, -12, 16, -16, 22, -22};
        outer:
        for (int orient = 0; orient < 2; orient++) {
            for (double a : angles) {
                double rad = Math.toRadians(a + (orient == 1 ? 90 : 0));
                double dx = Math.cos(rad), dy = Math.sin(rad);
                int lines = 64;
                double span = orient == 0 ? h : w;
                for (int k = 1; k <= lines; k++) {
                    double off = span * k / (lines + 1);
                    double cx = orient == 0 ? w / 2.0 : off, cy = orient == 0 ? off : h / 2.0;
                    double[] prof = sample(lum, w, h, cx, cy, dx, dy);
                    if (prof.length < 60) continue;
                    // hai cách tách vạch: phân ngưỡng cục bộ và dò cạnh theo đạo hàm (chịu ảnh mờ tốt hơn)
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (double[] runs : new double[][]{runs(prof), edgeRuns(prof)}) {
                        if (runs == null) continue;
                        for (int dir = 0; dir < 2; dir++) {
                            double[] rr = dir == 0 ? runs : reverse(runs);
                            for (Result r : decodeRuns(rr)) seen.add(r.format() + "|" + r.text());
                        }
                    }
                    for (String key : seen) votes.computeIfAbsent(key, x -> new int[1])[0]++;
                }
                // đã chắc chắn (mã có kiểm tra đọc được nhiều lần) thì dừng sớm cho nhanh
                for (var e : votes.entrySet()) if (e.getValue()[0] >= 6 && !e.getKey().startsWith("CODE_39")) break outer;
            }
        }
        List<Result> out = new ArrayList<>();
        for (var e : votes.entrySet()) {
            String[] p = e.getKey().split("\\|", 2);
            int hits = e.getValue()[0];
            if (!p[0].equals("CODE_128") && hits < 2) continue;      // Code 39 không có mã kiểm tra, EAN chỉ kiểm tra 1 chữ số
            out.add(new Result(p[0], p[1], hits));
        }
        // Một mã đọc thiếu đầu/cuối có thể trùng phần của mã dài hơn: bỏ mã ngắn ít phiếu hơn
        out.removeIf(r -> out.stream().anyMatch(o -> o != r && o.text().length() > r.text().length()
                && o.text().contains(r.text()) && o.hits() >= r.hits()));
        out.sort((a, b) -> b.hits() - a.hits());
        return out;
    }

    private static BufferedImage scaleDown(BufferedImage src, int max) {
        int w = src.getWidth(), h = src.getHeight();
        if (Math.max(w, h) <= max && src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        double s = Math.min(1.0, (double) max / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** Lấy mẫu độ sáng dọc đường thẳng qua (cx, cy) theo hướng (dx, dy); mỗi điểm lấy trung bình 3 điểm cắt ngang. */
    private static double[] sample(int[] lum, int w, int h, double cx, double cy, double dx, double dy) {
        // tìm đoạn nằm trong ảnh
        double tMin = -1e9, tMax = 1e9;
        double[][] lim = {{cx, dx, 0, w - 1}, {cy, dy, 0, h - 1}};
        for (double[] l : lim) {
            if (Math.abs(l[1]) < 1e-9) {
                if (l[0] < l[2] || l[0] > l[3]) return new double[0];
                continue;
            }
            double t1 = (l[2] - l[0]) / l[1], t2 = (l[3] - l[0]) / l[1];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (tMax - tMin < 2) return new double[0];
        int n = (int) (tMax - tMin);
        double[] out = new double[n];
        double nx = -dy, ny = dx;
        for (int i = 0; i < n; i++) {
            double x = cx + dx * (tMin + i), y = cy + dy * (tMin + i);
            double s = 0;
            for (int o = -1; o <= 1; o++) s += bilinear(lum, w, h, x + nx * o * 1.5, y + ny * o * 1.5);
            out[i] = s / 3;
        }
        return out;
    }

    private static double bilinear(int[] lum, int w, int h, double x, double y) {
        x = Math.max(0, Math.min(w - 1.001, x));
        y = Math.max(0, Math.min(h - 1.001, y));
        int x0 = (int) x, y0 = (int) y;
        double fx = x - x0, fy = y - y0;
        int i = y0 * w + x0;
        double a = lum[i] + (lum[i + 1] - lum[i]) * fx;
        double b = lum[i + w] + (lum[i + w + 1] - lum[i + w]) * fx;
        return a + (b - a) * fy;
    }

    /** Phân ngưỡng cục bộ và trả về độ rộng các đoạn trắng/đen xen kẽ, bắt đầu bằng đoạn trắng. */
    static double[] runs(double[] v) {
        int n = v.length;
        double gMin = 255, gMax = 0;
        for (double x : v) { gMin = Math.min(gMin, x); gMax = Math.max(gMax, x); }
        if (gMax - gMin < 30) return null;                      // không có tương phản → không có mã vạch
        int win = Math.max(20, Math.min(220, n / 10));
        double[] th = new double[n];
        // min/max trượt (đơn giản O(n·win/step): lấy theo khối)
        int step = Math.max(1, win / 6);
        double gMid = (gMin + gMax) / 2;
        for (int i = 0; i < n; i += step) {
            int a = Math.max(0, i - win), b = Math.min(n - 1, i + win);
            double mn = 255, mx = 0;
            for (int j = a; j <= b; j++) { mn = Math.min(mn, v[j]); mx = Math.max(mx, v[j]); }
            double t = mx - mn < (gMax - gMin) * 0.25 ? gMid : (mn + mx) / 2;
            for (int j = i; j < Math.min(n, i + step); j++) th[j] = t;
        }
        // làm mượt ngưỡng
        double[] ts = new double[n];
        int sm = Math.max(1, step);
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += th[i];
            if (i >= sm) acc -= th[i - sm];
            ts[i] = acc / Math.min(i + 1, sm);
        }
        List<Double> cross = new ArrayList<>();
        boolean black = v[0] < ts[0];
        List<Double> out = new ArrayList<>();
        double last = 0;
        if (black) out.add(0.0);                                 // luôn bắt đầu bằng đoạn trắng
        for (int i = 1; i < n; i++) {
            boolean b = v[i] < ts[i];
            if (b != black) {
                double d = v[i] - v[i - 1];
                double f = Math.abs(d) < 1e-9 ? 0.5 : (ts[i] - v[i - 1]) / d;
                f = Math.max(0, Math.min(1, f));
                double x = i - 1 + f;
                out.add(x - last);
                last = x;
                black = b;
                cross.add(x);
            }
        }
        out.add(n - last);
        if (out.size() < 20) return null;
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Tách vạch theo cạnh: cực trị của đạo hàm (đổi dấu xen kẽ), vị trí nội suy parabol. */
    static double[] edgeRuns(double[] v) {
        int n = v.length;
        if (n < 20) return null;
        double[] sm = new double[n];
        for (int i = 0; i < n; i++) sm[i] = (v[Math.max(0, i - 1)] + 2 * v[i] + v[Math.min(n - 1, i + 1)]) / 4;
        double[] d = new double[n];
        double maxD = 0;
        for (int i = 1; i < n - 1; i++) { d[i] = (sm[i + 1] - sm[i - 1]) / 2; maxD = Math.max(maxD, Math.abs(d[i])); }
        if (maxD < 6) return null;
        double th = Math.max(3, maxD * 0.10);
        List<double[]> edges = new ArrayList<>();             // {vị trí, đạo hàm}
        for (int i = 2; i < n - 2; i++) {
            double a = Math.abs(d[i]);
            if (a < th || a < Math.abs(d[i - 1]) || a < Math.abs(d[i + 1]) || Math.signum(d[i]) != Math.signum(d[i - 1]) && Math.abs(d[i - 1]) > a * 0.95) continue;
            if (a == Math.abs(d[i - 1]) && !edges.isEmpty() && edges.get(edges.size() - 1)[0] >= i - 1.5) continue;
            double y0 = Math.abs(d[i - 1]), y1 = a, y2 = Math.abs(d[i + 1]);
            double den = y0 - 2 * y1 + y2;
            double off = Math.abs(den) < 1e-9 ? 0 : 0.5 * (y0 - y2) / den;
            off = Math.max(-0.5, Math.min(0.5, off));
            double[] e = {i + off, d[i]};
            if (!edges.isEmpty() && Math.signum(edges.get(edges.size() - 1)[1]) == Math.signum(d[i])) {
                if (Math.abs(edges.get(edges.size() - 1)[1]) < a) edges.set(edges.size() - 1, e);   // cùng chiều: giữ cạnh mạnh hơn
                continue;
            }
            edges.add(e);
        }
        // bắt đầu bằng cạnh trắng→đen (đạo hàm âm)
        while (!edges.isEmpty() && edges.get(0)[1] > 0) edges.remove(0);
        if (edges.size() < 20) return null;
        double[] r = new double[edges.size() + 1];
        r[0] = edges.get(0)[0];
        for (int i = 1; i < edges.size(); i++) r[i] = edges.get(i)[0] - edges.get(i - 1)[0];
        r[edges.size()] = n - edges.get(edges.size() - 1)[0];
        return r;
    }

    private static double[] reverse(double[] r) {
        // runs bắt đầu bằng trắng; đảo ngược rồi bảo đảm vẫn bắt đầu bằng trắng
        int n = r.length;
        boolean lastIsWhite = (n - 1) % 2 == 0;
        double[] out = new double[lastIsWhite ? n : n + 1];
        int k = 0;
        if (!lastIsWhite) out[k++] = 0;
        for (int i = n - 1; i >= 0; i--) out[k++] = r[i];
        return out;
    }

    static List<Result> decodeRuns(double[] r) {
        List<Result> out = new ArrayList<>();
        for (int s = 1; s + 6 < r.length; s += 2) {             // s: chỉ số đoạn đen
            Result x = code128(r, s);
            if (x == null) x = ean13(r, s);
            if (x == null) x = code39(r, s);
            if (x != null) out.add(x);
        }
        return out;
    }

    /** Độ lệch giữa các đoạn đo được và mẫu (0 = khớp hoàn toàn); > ngưỡng → MAX. */
    static double variance(double[] r, int from, int[] pattern, int len, double maxIndividual) {
        double total = 0;
        int plen = 0;
        for (int i = 0; i < len; i++) { total += r[from + i]; plen += pattern[i]; }
        if (total <= 0) return Double.MAX_VALUE;
        double unit = total / plen;
        double maxInd = maxIndividual * unit, var = 0;
        for (int i = 0; i < len; i++) {
            double d = Math.abs(r[from + i] - pattern[i] * unit);
            if (d > maxInd) return Double.MAX_VALUE;
            var += d;
        }
        return var / total;
    }

    // ---------- Code 128 ----------

    private static int best128(double[] r, int at, int from, int to) {
        if (at + 6 > r.length) return -1;
        double best = 0.25;
        int bi = -1;
        for (int c = from; c <= to; c++) {
            double v = variance(r, at, C128[c], 6, 0.7);
            if (v < best) { best = v; bi = c; }
        }
        return bi;
    }

    static Result code128(double[] r, int s) {
        int start = best128(r, s, START_A, START_C);
        if (start < 0) return null;
        double unit = 0;
        for (int i = 0; i < 6; i++) unit += r[s + i];
        unit /= 11;
        if (r[s - 1] < unit * 3) return null;                    // cần khoảng trắng trước mã
        List<Integer> codes = new ArrayList<>();
        codes.add(start);
        int i = s + 6;
        boolean stopped = false;
        while (i + 6 <= r.length && codes.size() < 80) {
            int c = best128(r, i, 0, STOP);
            if (c < 0) return null;
            if (c == STOP) {
                if (i + 7 > r.length) return null;
                double u = 0;
                for (int k = 0; k < 6; k++) u += r[i + k];
                u /= 11;
                if (Math.abs(r[i + 6] - 2 * u) > 1.2 * u) return null;
                stopped = true;
                break;
            }
            codes.add(c);
            i += 6;
        }
        if (!stopped || codes.size() < 3) return null;
        // mã kiểm tra
        int sum = codes.get(0);
        for (int k = 1; k < codes.size() - 1; k++) sum += k * codes.get(k);
        if (sum % 103 != codes.get(codes.size() - 1)) return null;
        String text = text128(codes.subList(0, codes.size() - 1));
        return text == null || text.isBlank() ? null : new Result("CODE_128", text, 1);
    }

    private static String text128(List<Integer> codes) {
        StringBuilder sb = new StringBuilder();
        int set = codes.get(0) == START_A ? 0 : codes.get(0) == START_B ? 1 : 2;
        boolean shift = false;
        for (int k = 1; k < codes.size(); k++) {
            int c = codes.get(k);
            int cur = shift ? (set == 0 ? 1 : 0) : set;
            shift = false;
            if (cur == 2) {
                if (c < 100) { if (c < 10) sb.append('0'); sb.append(c); }
                else if (c == 100) set = 1;
                else if (c == 101) set = 0;
                // 102 = FNC1: bỏ qua
                continue;
            }
            if (c < 96) {
                char ch = cur == 0 ? (char) (c < 64 ? c + 32 : c - 64) : (char) (c + 32);
                if (ch >= 32) sb.append(ch);
            } else if (c == 98) shift = true;
            else if (c == 99) set = 2;
            else if (c == 100 && cur == 0) set = 1;
            else if (c == 101 && cur == 1) set = 0;
        }
        return sb.toString().trim();
    }

    // ---------- Code 39 ----------

    private static int c39(double[] r, int at) {
        if (at + 9 > r.length) return -1;
        double[] w = new double[9];
        System.arraycopy(r, at, w, 0, 9);
        double[] sorted = w.clone();
        java.util.Arrays.sort(sorted);
        double narrowMax = sorted[5], wideMin = sorted[6];
        if (wideMin < narrowMax * 1.6) return -1;
        double narrowMin = sorted[0], wideMax = sorted[8];
        if (wideMax > wideMin * 1.8 || narrowMax > narrowMin * 1.8) return -1;
        int bits = 0;
        for (int i = 0; i < 9; i++) bits = (bits << 1) | (w[i] >= wideMin ? 1 : 0);
        return bits;
    }

    static Result code39(double[] r, int s) {
        if (c39(r, s) != C39_STAR) return null;
        double unit = 0;
        for (int i = 0; i < 9; i++) unit += r[s + i];
        unit /= 15;                                              // 6 hẹp + 3 rộng (≈3) ≈ 15 đơn vị
        if (r[s - 1] < unit * 4) return null;
        StringBuilder sb = new StringBuilder();
        int i = s + 10;                                          // bỏ khoảng trắng giữa ký tự
        while (i + 9 <= r.length && sb.length() < 60) {
            int b = c39(r, i);
            if (b == C39_STAR) return sb.length() >= 3 ? new Result("CODE_39", sb.toString(), 1) : null;
            int idx = -1;
            for (int k = 0; k < C39.length; k++) if (C39[k] == b) { idx = k; break; }
            if (idx < 0) return null;
            if (r[i - 1] > unit * 3.5) return null;              // khoảng cách giữa ký tự quá lớn
            sb.append(C39_CHARS.charAt(idx));
            i += 10;
        }
        return null;
    }

    // ---------- EAN-13 / UPC-A ----------

    static Result ean13(double[] r, int s) {
        if (s + 3 + 24 + 5 + 24 + 3 > r.length + 1) return null;
        double u = (r[s] + r[s + 1] + r[s + 2]) / 3;
        if (u <= 0) return null;
        for (int k = 0; k < 3; k++) if (Math.abs(r[s + k] - u) > u * 0.6) return null;
        if (r[s - 1] < u * 4) return null;
        int at = s + 3;
        int[] d = new int[13];
        int lg = 0;
        for (int x = 0; x < 6; x++) {
            int bi = bestEan(r, at, true);
            if (bi < 0) return null;
            d[x + 1] = bi % 10;
            if (bi >= 10) lg |= 1 << (5 - x);
            at += 4;
        }
        // vạch giữa 01010
        if (at + 5 > r.length) return null;
        double mu = 0;
        for (int k = 0; k < 5; k++) mu += r[at + k];
        mu /= 5;
        for (int k = 0; k < 5; k++) if (Math.abs(r[at + k] - mu) > mu * 0.7) return null;
        at += 5;
        for (int x = 0; x < 6; x++) {
            int bi = bestEan(r, at, false);
            if (bi < 0) return null;
            d[x + 7] = bi;
            at += 4;
        }
        if (at + 3 > r.length) return null;
        int first = -1;
        for (int k = 0; k < 10; k++) if (EAN_FIRST[k] == lg) first = k;
        if (first < 0) return null;
        d[0] = first;
        int sum = 0;
        for (int k = 0; k < 12; k++) sum += d[k] * (k % 2 == 0 ? 1 : 3);
        if ((10 - sum % 10) % 10 != d[12]) return null;
        StringBuilder sb = new StringBuilder();
        for (int x : d) sb.append(x);
        return new Result("EAN_13", sb.toString(), 1);
    }

    private static int bestEan(double[] r, int at, boolean left) {
        if (at + 4 > r.length) return -1;
        double best = 0.48;
        int bi = -1;
        int[][] pats = left ? EAN_LG : EAN_L;
        for (int c = 0; c < pats.length; c++) {
            double v = variance(r, at, pats[c], 4, 0.7);
            if (v < best) { best = v; bi = c; }
        }
        return bi;
    }

    // ===================== TẠO MÃ CODE 128 =====================

    /** Dãy độ rộng vạch (bắt đầu bằng vạch đen) cho chuỗi ASCII in được; dùng bộ C cho chuỗi số dài. */
    public static int[] encode128(String text) {
        for (char ch : text.toCharArray()) if (ch < 32 || ch > 126) throw new IllegalArgumentException("Chỉ hỗ trợ ký tự ASCII");
        List<Integer> codes = new ArrayList<>();
        boolean allDigits = text.length() >= 4 && text.length() % 2 == 0 && text.matches("\\d+");
        if (allDigits) {
            codes.add(START_C);
            for (int i = 0; i < text.length(); i += 2) codes.add(Integer.parseInt(text.substring(i, i + 2)));
        } else {
            codes.add(START_B);
            int i = 0;
            while (i < text.length()) {
                // đoạn số dài ≥ 6 (chẵn) → chuyển bộ C cho ngắn
                int j = i;
                while (j < text.length() && Character.isDigit(text.charAt(j))) j++;
                int run = (j - i) / 2 * 2;
                if (run >= 6) {
                    codes.add(99);
                    for (int k = i; k < i + run; k += 2) codes.add(Integer.parseInt(text.substring(k, k + 2)));
                    i += run;
                    if (i < text.length()) codes.add(100);
                    else break;
                    continue;
                }
                codes.add(text.charAt(i) - 32);
                i++;
            }
        }
        int sum = codes.get(0);
        for (int k = 1; k < codes.size(); k++) sum += k * codes.get(k);
        codes.add(sum % 103);
        codes.add(STOP);
        List<Integer> w = new ArrayList<>();
        for (int c : codes) for (int x : C128[c]) w.add(x);
        int[] out = new int[w.size()];
        for (int i = 0; i < out.length; i++) out[i] = w.get(i);
        return out;
    }

    /** Ảnh SVG của mã Code 128 (dùng khi in phiếu). */
    public static String svg128(String text, int height) {
        int[] w = encode128(text);
        int total = 20;
        for (int x : w) total += x;
        StringBuilder sb = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + total + " " + height
                + "\" preserveAspectRatio=\"none\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"");
        int x = 10;
        for (int i = 0; i < w.length; i++) {
            if (i % 2 == 0) sb.append('M').append(x).append(" 0h").append(w[i]).append('v').append(height).append('h').append(-w[i]).append('z');
            x += w[i];
        }
        return sb.append("\" fill=\"#000\"/></svg>").toString();
    }

    /** Ảnh mã Code 128 (dùng cho kiểm thử và giao diện Swing). */
    public static BufferedImage image128(String text, int module, int height) {
        int[] w = encode128(text);
        int total = 20;
        for (int x : w) total += x;
        BufferedImage img = new BufferedImage(total * module, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), height);
        g.setColor(Color.BLACK);
        int x = 10 * module;
        for (int i = 0; i < w.length; i++) {
            if (i % 2 == 0) g.fillRect(x, 0, w[i] * module, height);
            x += w[i] * module;
        }
        g.dispose();
        return img;
    }

    public static boolean luhn(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
        }
        return sum % 10 == 0;
    }
}
