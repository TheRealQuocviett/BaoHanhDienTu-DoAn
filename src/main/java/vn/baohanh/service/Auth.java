package vn.baohanh.service;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.security.PasswordHasher;

/**
 * Đăng nhập, phiên làm việc (web) và kiểm tra quyền.
 * Khóa tạm tài khoản 2 phút sau 5 lần nhập sai liên tiếp.
 */
public class Auth {
    private static final long SESSION_MS = 12L * 3600 * 1000;   // phiên web sống 12 giờ
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 2 * 60 * 1000;

    private record Session(User user, long expires) {}
    private record Fails(int count, long lockedUntil) {}

    private final Database db;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Fails> fails = new ConcurrentHashMap<>();

    public Auth(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Kiểm tra người dùng có một trong các vai trò; không thì báo lỗi 403. */
    public static void require(User u, Role... roles) {
        if (u == null) throw new AppException(401, "Vui lòng đăng nhập");
        if (roles.length > 0 && !u.is(roles)) throw AppException.forbidden();
    }

    /** Kiểm tra quyền chi tiết theo chức năng (quản lý có thể bật/tắt cho từng tài khoản). */
    public static void need(User u, vn.baohanh.model.Perm p) {
        if (u == null) throw new AppException(401, "Vui lòng đăng nhập");
        if (!p.allows(u)) throw new AppException(403, "Tài khoản của bạn chưa được cấp quyền: " + p.label.toLowerCase());
    }

    /** Kết quả bước 1 đăng nhập: user (đã xong) hoặc pendingToken (cần nhập mã 2 lớp). */
    public record LoginStep(User user, String pendingToken) {
        public boolean needCode() { return pendingToken != null; }
    }

    private TwoFactorService twoFactor;

    public void setTwoFactor(TwoFactorService t) { this.twoFactor = t; }

    /**
     * Đăng nhập một bước (dùng cho tài khoản chưa bật 2FA).
     * Tài khoản đã bật 2FA phải dùng begin() + TwoFactorService.finishPending().
     */
    public User login(String username, String password) throws SQLException {
        LoginStep s = begin(username, password);
        if (s.needCode()) throw new AppException("Tài khoản này đã bật xác thực 2 lớp – cần nhập mã từ ứng dụng Google Authenticator");
        return s.user();
    }

    /** Bước 1: kiểm tra mật khẩu. Nếu tài khoản bật 2FA thì trả phiếu chờ nhập mã. */
    public LoginStep begin(String username, String password) throws SQLException {
        User u = checkPassword(username, password);
        if (twoFactor != null && twoFactor.isEnabled(u.id())) return new LoginStep(null, twoFactor.startPending(u));
        audit.log(u, "ĐĂNG NHẬP", "app_user", u.id(), null);
        return new LoginStep(u, null);
    }

    private User checkPassword(String username, String password) throws SQLException {
        String key = username == null ? "" : username.trim().toLowerCase();
        if (key.isEmpty() || password == null || password.isEmpty())
            throw new AppException("Vui lòng nhập tên đăng nhập và mật khẩu");
        Fails f = fails.get(key);
        long now = System.currentTimeMillis();
        if (f != null && f.lockedUntil() > now) {
            long sec = (f.lockedUntil() - now + 999) / 1000;
            throw new AppException("Tài khoản tạm khóa do nhập sai nhiều lần. Thử lại sau " + sec + " giây");
        }
        Row r = db.one("SELECT * FROM app_user WHERE username = ?", key);
        if (r == null || !PasswordHasher.verify(password, r.str("password_hash"))) {
            int c = (f == null || f.lockedUntil() > 0 ? 0 : f.count()) + 1;
            fails.put(key, new Fails(c, c >= MAX_FAILS ? now + LOCK_MS : 0));
            throw new AppException("Sai tên đăng nhập hoặc mật khẩu");
        }
        if (!r.bool("active")) throw new AppException("Tài khoản đã bị khóa, vui lòng liên hệ quản lý");
        fails.remove(key);
        return toUser(r);
    }

    public static User toUser(Row r) {
        return new User(r.lng("id"), r.str("username"), r.str("full_name"), Role.valueOf(r.str("role")));
    }

    // ---------------- Phiên web ----------------

    public String createSession(User u) {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        sessions.put(token, new Session(u, System.currentTimeMillis() + SESSION_MS));
        return token;
    }

    public User fromSession(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expires() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return s.user();
    }

    public void logout(String token) throws SQLException {
        Session s = token == null ? null : sessions.remove(token);
        if (s != null) audit.log(s.user(), "ĐĂNG XUẤT", "app_user", s.user().id(), null);
    }

    /** Hủy mọi phiên của một người dùng (khi bị khóa / đổi quyền). */
    public void dropSessions(long userId) {
        sessions.values().removeIf(s -> s.user().id() == userId);
    }

    public void changePassword(User u, String oldPw, String newPw) throws SQLException {
        require(u);
        Row r = db.one("SELECT password_hash FROM app_user WHERE id = ?", u.id());
        if (r == null || !PasswordHasher.verify(oldPw, r.str("password_hash")))
            throw new AppException("Mật khẩu hiện tại không đúng");
        checkStrength(newPw);
        db.update("UPDATE app_user SET password_hash = ? WHERE id = ?", PasswordHasher.hash(newPw), u.id());
        audit.log(u, "ĐỔI MẬT KHẨU", "app_user", u.id(), null);
    }

    public static void checkStrength(String pw) {
        if (pw == null || pw.length() < 6) throw new AppException("Mật khẩu phải có ít nhất 6 ký tự");
    }
}
