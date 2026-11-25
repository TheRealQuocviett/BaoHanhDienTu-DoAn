package vn.baohanh.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.util.QrCode;
import vn.baohanh.util.Totp;

/**
 * Xác thực 2 lớp bằng ứng dụng Google Authenticator (TOTP).
 * <ol>
 *   <li>setup(): tạo khóa bí mật mới (chưa bật), trả mã QR để quét.</li>
 *   <li>enable(code): người dùng nhập mã 6 số đang hiện trên điện thoại → bật, trả 8 mã khôi phục (chỉ hiện 1 lần).</li>
 *   <li>Đăng nhập: sau khi đúng mật khẩu, phải nhập mã 6 số hoặc 1 mã khôi phục.</li>
 * </ol>
 * Mã khôi phục chỉ lưu bản băm SHA-256; dùng xong là hết hiệu lực.
 */
public class TwoFactorService {
    public static final int RECOVERY_CODES = 8;
    private static final int WINDOW = 1;                  // chấp nhận lệch ±30 giây
    private static final long PENDING_MS = 5 * 60 * 1000; // hạn bước nhập mã sau khi đúng mật khẩu
    private static final int PENDING_TRIES = 5;

    private record Pending(User user, long expires, int tries) {}

    private final Database db;
    private final AuditService audit;
    private SettingService settings;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Đếm số lần nhập sai mã theo tài khoản (chống dò mã bằng cách đăng nhập lại liên tục). */
    private final Map<Long, long[]> userFails = new ConcurrentHashMap<>();
    private static final int USER_MAX_FAILS = 10;
    private static final long USER_LOCK_MS = 15 * 60 * 1000;

    public TwoFactorService(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    public void setSettings(SettingService s) { this.settings = s; }

    public boolean isEnabled(long userId) throws SQLException {
        return db.scalar("SELECT COUNT(*) FROM user_2fa WHERE user_id = ? AND enabled = 1", userId) > 0;
    }

    /** Trạng thái 2FA của chính mình. */
    public Row status(User u) throws SQLException {
        Auth.require(u);
        Row r = db.one("SELECT enabled, enabled_at, recovery FROM user_2fa WHERE user_id = ?", u.id());
        boolean on = r != null && r.bool("enabled");
        return new Row().with("enabled", on)
                .with("enabled_at", on ? r.str("enabled_at") : "")
                .with("recovery_left", on ? countCodes(r.str("recovery")) : 0)
                .with("recommended", u.role() == Role.ADMIN);
    }

    /** Bước 1: tạo khóa mới (ghi đè khóa chưa bật). */
    public Row setup(User u) throws SQLException {
        Auth.require(u);
        if (isEnabled(u.id())) throw new AppException("Xác thực 2 lớp đang bật. Muốn đổi điện thoại, hãy tắt rồi bật lại.");
        String secret = Totp.newSecret();
        db.update("INSERT INTO user_2fa (user_id, secret, enabled, created_at) VALUES (?, ?, 0, ?) "
                + "ON CONFLICT(user_id) DO UPDATE SET secret = excluded.secret, enabled = 0, created_at = excluded.created_at, "
                + "recovery = '', last_step = 0", u.id(), secret, Database.now());
        String issuer = settings == null ? "Bao hanh dien tu" : settings.get("shop_name");
        if (issuer == null || issuer.isBlank()) issuer = "Bao hanh dien tu";
        issuer = issuer.replace(":", " ");
        String url = Totp.otpauthUrl(issuer, u.username(), secret);
        return new Row().with("secret", Totp.group(secret)).with("otpauth", url)
                .with("qr_svg", QrCode.encode(url).toSvg(2)).with("issuer", issuer).with("account", u.username());
    }

    /** Bước 2: xác nhận mã đầu tiên → bật. Trả về danh sách mã khôi phục (chỉ hiện một lần). */
    public List<String> enable(User u, String code) throws SQLException {
        Auth.require(u);
        Row r = db.one("SELECT secret, enabled, last_step FROM user_2fa WHERE user_id = ?", u.id());
        if (r == null) throw new AppException("Hãy bấm \"Bắt đầu\" để tạo mã QR trước");
        if (r.bool("enabled")) throw new AppException("Xác thực 2 lớp đã bật rồi");
        long step = Totp.match(r.str("secret"), code, Totp.currentStep(), WINDOW, r.lng("last_step"));
        if (step < 0) throw new AppException("Mã không đúng. Kiểm tra giờ trên điện thoại đã đặt tự động chưa, rồi nhập mã mới.");
        List<String> codes = newRecoveryCodes();
        db.update("UPDATE user_2fa SET enabled = 1, enabled_at = ?, last_step = ?, recovery = ? WHERE user_id = ?",
                Database.now(), step, hashAll(codes), u.id());
        audit.log(u, "BẬT 2FA", "app_user", u.id(), null);
        return codes;
    }

    /** Tắt 2FA của chính mình (cần mã hiện tại hoặc mã khôi phục). */
    public void disable(User u, String code) throws SQLException {
        Auth.require(u);
        if (!isEnabled(u.id())) throw new AppException("Xác thực 2 lớp chưa bật");
        if (!verify(u.id(), code)) throw new AppException("Mã xác thực không đúng");
        db.update("DELETE FROM user_2fa WHERE user_id = ?", u.id());
        audit.log(u, "TẮT 2FA", "app_user", u.id(), null);
    }

    /** Tạo bộ mã khôi phục mới (bộ cũ hết hiệu lực). */
    public List<String> regenerate(User u, String code) throws SQLException {
        Auth.require(u);
        if (!isEnabled(u.id())) throw new AppException("Xác thực 2 lớp chưa bật");
        if (!verify(u.id(), code)) throw new AppException("Mã xác thực không đúng");
        List<String> codes = newRecoveryCodes();
        db.update("UPDATE user_2fa SET recovery = ? WHERE user_id = ?", hashAll(codes), u.id());
        audit.log(u, "TẠO LẠI MÃ KHÔI PHỤC 2FA", "app_user", u.id(), null);
        return codes;
    }

    /** Quản lý gỡ 2FA cho nhân viên mất điện thoại. */
    public void adminReset(User admin, long userId) throws SQLException {
        Auth.require(admin, Role.ADMIN);
        Row t = db.one("SELECT username FROM app_user WHERE id = ?", userId);
        if (t == null) throw AppException.notFound("tài khoản #" + userId);
        int n = db.update("DELETE FROM user_2fa WHERE user_id = ?", userId);
        userFails.remove(userId);
        if (n == 0) throw new AppException("Tài khoản này chưa bật xác thực 2 lớp");
        audit.log(admin, "GỠ 2FA", "app_user", userId, t.str("username"));
    }

    /** Danh sách tài khoản đang bật 2FA (để quản lý gỡ khi nhân viên mất điện thoại). */
    public List<Row> enabledUsers(User admin) throws SQLException {
        Auth.require(admin, Role.ADMIN);
        return db.query("SELECT u.id, u.username, u.full_name, u.role, f.enabled_at FROM user_2fa f JOIN app_user u ON u.id = f.user_id "
                + "WHERE f.enabled = 1 ORDER BY u.full_name");
    }

    /** Danh sách id tài khoản đang bật 2FA (cho màn hình quản lý người dùng). */
    public java.util.Set<Long> enabledIds() throws SQLException {
        java.util.Set<Long> s = new java.util.HashSet<>();
        for (Row r : db.query("SELECT user_id FROM user_2fa WHERE enabled = 1")) s.add(r.lng("user_id"));
        return s;
    }

    /**
     * Kiểm tra mã 6 số (TOTP) hoặc mã khôi phục dạng xxxx-xxxx.
     * Mã TOTP đúng thì ghi lại bước thời gian để không dùng lại được; mã khôi phục đúng thì bị xóa.
     */
    public synchronized boolean verify(long userId, String code) throws SQLException {
        if (code == null) return false;
        Row r = db.one("SELECT secret, recovery, last_step FROM user_2fa WHERE user_id = ? AND enabled = 1", userId);
        if (r == null) return false;
        String c = code.trim();
        if (c.replaceAll("\\s", "").matches("\\d{6}")) {
            long step = Totp.match(r.str("secret"), c, Totp.currentStep(), WINDOW, r.lng("last_step"));
            if (step < 0) return false;
            db.update("UPDATE user_2fa SET last_step = ? WHERE user_id = ?", step, userId);
            return true;
        }
        String h = sha256(normRecovery(c));
        List<String> hashes = new ArrayList<>(Arrays.asList(r.str("recovery").split(",")));
        hashes.removeIf(String::isBlank);
        boolean found = false;
        for (String x : hashes) found |= MessageDigest.isEqual(x.getBytes(StandardCharsets.US_ASCII), h.getBytes(StandardCharsets.US_ASCII));
        if (!found) return false;
        hashes.remove(h);
        db.update("UPDATE user_2fa SET recovery = ? WHERE user_id = ?", String.join(",", hashes), userId);
        audit.log(null, "DÙNG MÃ KHÔI PHỤC 2FA", "app_user", userId, "Còn " + hashes.size() + " mã");
        return true;
    }

    // ---------------- Bước đăng nhập thứ hai ----------------

    /** Đã đúng mật khẩu, tạo phiếu chờ nhập mã (5 phút, tối đa 5 lần). */
    public String startPending(User u) {
        long[] f = userFails.get(u.id());
        if (f != null && f[0] >= USER_MAX_FAILS && System.currentTimeMillis() - f[1] < USER_LOCK_MS)
            throw new AppException("Nhập sai mã xác thực quá nhiều lần. Tạm khóa 15 phút – hoặc nhờ quản lý gỡ 2FA.");
        pending.values().removeIf(p -> p.expires() < System.currentTimeMillis());
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        pending.put(token, new Pending(u, System.currentTimeMillis() + PENDING_MS, 0));
        return token;
    }

    /** Hoàn tất đăng nhập bằng mã. */
    public User finishPending(String token, String code) throws SQLException {
        Pending p = token == null ? null : pending.get(token);
        if (p == null || p.expires() < System.currentTimeMillis()) {
            if (token != null) pending.remove(token);
            throw new AppException(401, "Phiên đăng nhập đã hết hạn, vui lòng nhập lại mật khẩu");
        }
        if (verify(p.user().id(), code)) {
            pending.remove(token);
            userFails.remove(p.user().id());
            audit.log(p.user(), "ĐĂNG NHẬP", "app_user", p.user().id(), "Có xác thực 2 lớp");
            return p.user();
        }
        int tries = p.tries() + 1;
        long now = System.currentTimeMillis();
        userFails.compute(p.user().id(), (k, f) -> f == null || now - f[1] >= USER_LOCK_MS ? new long[]{1, now} : new long[]{f[0] + 1, f[1]});
        if (userFails.get(p.user().id())[0] >= USER_MAX_FAILS) {
            pending.remove(token);
            throw new AppException(401, "Nhập sai mã xác thực quá nhiều lần. Tạm khóa 15 phút.");
        }
        if (tries >= PENDING_TRIES) {
            pending.remove(token);
            audit.log(p.user(), "2FA SAI NHIỀU LẦN", "app_user", p.user().id(), null);
            throw new AppException(401, "Nhập sai mã quá nhiều lần, vui lòng đăng nhập lại");
        }
        pending.put(token, new Pending(p.user(), p.expires(), tries));
        throw new AppException("Mã xác thực không đúng (còn " + (PENDING_TRIES - tries) + " lần thử)");
    }

    // ---------------- Tiện ích ----------------

    private List<String> newRecoveryCodes() {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";   // bỏ i, l, o, 0, 1 dễ nhầm
        List<String> out = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 8; k++) {
                if (k == 4) sb.append('-');
                sb.append(abc.charAt(random.nextInt(abc.length())));
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static String normRecovery(String c) {
        String s = c.toLowerCase().replaceAll("[^a-z0-9]", "");
        return s.length() == 8 ? s.substring(0, 4) + "-" + s.substring(4) : s;
    }

    private static String hashAll(List<String> codes) {
        List<String> h = new ArrayList<>();
        for (String c : codes) h.add(sha256(c));
        return String.join(",", h);
    }

    private static int countCodes(String s) {
        return (int) Arrays.stream(s.split(",")).filter(x -> !x.isBlank()).count();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
