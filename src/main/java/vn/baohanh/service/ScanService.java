package vn.baohanh.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import vn.baohanh.db.Row;
import vn.baohanh.model.User;
import vn.baohanh.util.Barcode;
import vn.baohanh.util.QrCode;

/**
 * Quét mã vạch / IMEI khi tiếp nhận máy.
 * <ul>
 *   <li>Đọc mã từ một ảnh (camera laptop, ảnh chọn từ máy) – {@link #decode}.</li>
 *   <li>Dùng điện thoại làm máy quét: nhân viên mở một "phiên quét", máy tính hiện mã QR;
 *       điện thoại quét QR → mở trang /quet/{mã phiên} → chụp mã vạch → kết quả hiện ngay trên máy tính.
 *       Cách này chạy được qua mạng LAN thường (http), không cần HTTPS như khi mở camera trực tiếp trong trình duyệt.</li>
 * </ul>
 * Phiên quét chỉ lưu trong bộ nhớ, hết hạn sau 15 phút, mỗi phiên tối đa 60 lần gửi.
 */
public class ScanService {
    public static final long TTL_MS = 15 * 60 * 1000L;
    public static final int MAX_IMAGE = 10 * 1024 * 1024;
    private static final int MAX_SUBMITS = 60;

    private final SettingService settings;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    public ScanService(SettingService settings) {
        this.settings = settings;
    }

    private static final class Session {
        final long userId;
        final long created = System.currentTimeMillis();
        long touched = created;
        int submits;
        boolean phoneSeen;
        final List<Row> codes = new ArrayList<>();
        Session(long userId) { this.userId = userId; }
        boolean expired() { return System.currentTimeMillis() - created > TTL_MS; }
    }

    // ===================== ĐỌC ẢNH =====================

    /** Đọc mã vạch trong ảnh (JPEG/PNG). Không ném lỗi khi không thấy mã: trả danh sách rỗng. */
    public Row decode(User u, byte[] image) {
        Auth.require(u);
        return new Row().with("codes", read(image));
    }

    static List<Row> read(byte[] image) {
        if (image == null || image.length == 0) throw new AppException("Chưa có ảnh");
        if (image.length > MAX_IMAGE) throw new AppException("Ảnh quá lớn (tối đa 10 MB)");
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(image));
        } catch (Exception e) {
            img = null;
        }
        if (img == null) throw new AppException("Không đọc được ảnh – hãy dùng ảnh JPG hoặc PNG");
        if ((long) img.getWidth() * img.getHeight() > 40_000_000L) throw new AppException("Ảnh quá lớn");
        List<Row> out = new ArrayList<>();
        for (Barcode.Result r : Barcode.decode(img)) out.add(toRow(r.text(), r.format(), r.kind()));
        return out;
    }

    private static Row toRow(String text, String format, String kind) {
        return new Row().with("text", text).with("format", format.replace('_', ' ')).with("kind", kind);
    }

    // ===================== PHIÊN QUÉT BẰNG ĐIỆN THOẠI =====================

    public synchronized Row start(User u) throws SQLException {
        Auth.require(u);
        cleanup();
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        sessions.put(token, new Session(u.id()));
        String url = settings.portalBase() + "/quet/" + token;
        return new Row().with("token", token).with("url", url).with("qr_svg", QrCode.encode(url).toSvg(2))
                .with("expires_in", TTL_MS / 1000);
    }

    /** Máy tính hỏi kết quả mới (chỉ người mở phiên mới xem được). */
    public synchronized Row poll(User u, String token, int since) {
        Auth.require(u);
        Session s = sessions.get(token);
        if (s == null || s.expired() || s.userId != u.id()) throw new AppException(410, "Phiên quét đã hết hạn – hãy mở lại");
        List<Row> fresh = since >= 0 && since < s.codes.size() ? new ArrayList<>(s.codes.subList(since, s.codes.size())) : List.of();
        return new Row().with("codes", fresh).with("total", (long) s.codes.size()).with("phone_seen", s.phoneSeen);
    }

    public synchronized void stop(User u, String token) {
        Session s = sessions.get(token);
        if (s != null && s.userId == u.id()) sessions.remove(token);
    }

    private Session phone(String token) {
        Session s = token == null ? null : sessions.get(token);
        if (s == null || s.expired()) throw new AppException(410, "Mã QR đã hết hạn. Trên máy tính, bấm Quét bằng điện thoại để lấy mã mới.");
        if (++s.submits > MAX_SUBMITS) throw new AppException(429, "Đã gửi quá nhiều lần trong phiên này");
        s.touched = System.currentTimeMillis();
        s.phoneSeen = true;
        return s;
    }

    /** Điện thoại mở trang quét (để máy tính biết đã kết nối). */
    public synchronized boolean hello(String token) {
        Session s = token == null ? null : sessions.get(token);
        if (s == null || s.expired()) return false;
        s.phoneSeen = true;
        return true;
    }

    /** Điện thoại gửi ảnh chụp mã vạch. */
    public Row submitImage(String token, byte[] image) {
        synchronized (this) { phone(token); }
        List<Row> codes = read(image);                         // giải mã ngoài khóa
        synchronized (this) {
            Session s = sessions.get(token);
            if (s != null) for (Row c : codes) if (s.codes.stream().noneMatch(x -> x.str("text").equals(c.str("text")))) s.codes.add(c);
        }
        return new Row().with("codes", codes);
    }

    /** Điện thoại gửi số serial gõ tay (khi tem bị mờ, không chụp được). */
    public synchronized Row submitText(String token, String text) {
        Session s = phone(token);
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 64 || !t.matches("[\\p{L}\\p{N} ._:/+\\-]+")) throw new AppException("Số serial không hợp lệ");
        Row c = toRow(t, "Gõ tay", t.matches("\\d{15}") && Barcode.luhn(t) ? "IMEI" : "Serial");
        if (s.codes.stream().noneMatch(x -> x.str("text").equals(t))) s.codes.add(c);
        return new Row().with("codes", List.of(c));
    }

    private void cleanup() {
        sessions.values().removeIf(Session::expired);
        while (sessions.size() > 200) sessions.remove(sessions.keySet().iterator().next());
    }
}
