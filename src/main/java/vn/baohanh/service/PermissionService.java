package vn.baohanh.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Perm;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;

/**
 * Phân quyền chi tiết: quản lý bật / tắt từng chức năng cho từng tài khoản Tiếp nhận / Kỹ thuật viên.
 * Mặc định mỗi tài khoản có quyền theo vai trò; bảng user_permission chỉ lưu phần khác mặc định.
 * Thay đổi có hiệu lực ngay (kể cả với người đang đăng nhập), không cần đăng nhập lại.
 */
public class PermissionService {
    private final Database db;
    private final AuditService audit;

    public PermissionService(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
        try { load(); } catch (SQLException e) { throw new IllegalStateException("Không nạp được phân quyền: " + e.getMessage(), e); }
    }

    /** Nạp toàn bộ quyền riêng từ CSDL vào bộ nhớ. */
    public final void load() throws SQLException {
        Perm.clearOverrides();
        Map<Long, Map<Perm, Boolean>> all = new LinkedHashMap<>();
        for (Row r : db.query("SELECT user_id, perm, allowed FROM user_permission")) {
            Perm p = parse(r.str("perm"));
            if (p != null) all.computeIfAbsent(r.lng("user_id"), k -> new EnumMap<>(Perm.class)).put(p, r.lng("allowed") == 1);
        }
        all.forEach(Perm::setOverrides);
    }

    private static Perm parse(String s) {
        try { return Perm.valueOf(s); } catch (Exception e) { return null; }
    }

    /** Danh sách quyền (theo nhóm) để dựng bảng phân quyền. */
    public static List<Row> catalog() {
        List<Row> out = new ArrayList<>();
        for (Perm p : Perm.values())
            out.add(new Row().with("key", p.name()).with("group", p.group).with("label", p.label).with("hint", p.hint)
                    .with("reception", p.defaultFor(Role.RECEPTION)).with("technician", p.defaultFor(Role.TECHNICIAN)));
        return out;
    }

    /** Bảng phân quyền: mọi tài khoản (trừ Quản lý) với quyền hiện tại của từng chức năng. */
    public Row matrix(User u) throws SQLException {
        Auth.require(u, Role.ADMIN);
        List<Row> users = new ArrayList<>();
        for (Row r : db.query("SELECT id, username, full_name, role, active FROM app_user WHERE role <> 'ADMIN' ORDER BY role, full_name"))
            users.add(userRow(r));
        return new Row().with("perms", catalog()).with("users", users);
    }

    private Row userRow(Row r) {
        User target = new User(r.lng("id"), r.str("username"), r.str("full_name"), Role.valueOf(r.str("role")));
        Map<Perm, Boolean> ov = Perm.overridesOf(target.id());
        Map<String, Object> perms = new LinkedHashMap<>();
        for (Perm p : Perm.values())
            perms.put(p.name(), new Row().with("allowed", p.allows(target)).with("custom", ov.containsKey(p)));
        return new Row().with("id", target.id()).with("username", target.username()).with("full_name", target.fullName())
                .with("role", target.role().name()).with("role_label", target.role().getLabel()).with("active", r.lng("active") == 1)
                .with("custom_count", (long) ov.size()).with("perms", perms);
    }

    /**
     * Lưu quyền của một tài khoản. {@code in} = {mã quyền: true/false}; quyền không gửi lên giữ nguyên.
     * Giá trị trùng mặc định của vai trò thì xóa dòng riêng (quay về mặc định).
     */
    public Row save(User u, long userId, Map<String, Object> in) throws SQLException {
        Auth.require(u, Role.ADMIN);
        Row r = db.one("SELECT id, username, full_name, role, active FROM app_user WHERE id = ?", userId);
        if (r == null) throw AppException.notFound("tài khoản");
        if ("ADMIN".equals(r.str("role"))) throw new AppException("Quản lý luôn có mọi quyền – không cần phân quyền");
        Role role = Role.valueOf(r.str("role"));
        List<String> changes = new ArrayList<>();
        db.tx(() -> {
            for (var e : in.entrySet()) {
                Perm p = parse(e.getKey());
                if (p == null) throw new AppException("Quyền không hợp lệ: " + e.getKey());
                boolean want = Boolean.TRUE.equals(e.getValue()) || "true".equals(String.valueOf(e.getValue())) || "1".equals(String.valueOf(e.getValue()));
                Row old = db.one("SELECT allowed FROM user_permission WHERE user_id = ? AND perm = ?", userId, p.name());
                boolean before = old != null ? old.lng("allowed") == 1 : p.defaultFor(role);
                if (want == p.defaultFor(role)) db.update("DELETE FROM user_permission WHERE user_id = ? AND perm = ?", userId, p.name());
                else db.update("INSERT INTO user_permission (user_id, perm, allowed, updated_at, updated_by) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(user_id, perm) DO UPDATE SET allowed = excluded.allowed, updated_at = excluded.updated_at, updated_by = excluded.updated_by",
                        userId, p.name(), want ? 1 : 0, Database.now(), u.id());
                if (want != before) changes.add((want ? "+ " : "− ") + p.label);
            }
            if (!changes.isEmpty()) audit.log(u, "PHÂN QUYỀN", "app_user", userId, r.str("full_name") + ": " + String.join("; ", changes));
            return null;
        });
        reloadUser(userId);
        return userRow(r);
    }

    /** Đưa tài khoản về quyền mặc định của vai trò. */
    public Row reset(User u, long userId) throws SQLException {
        Auth.require(u, Role.ADMIN);
        Row r = db.one("SELECT id, username, full_name, role, active FROM app_user WHERE id = ?", userId);
        if (r == null) throw AppException.notFound("tài khoản");
        long n = db.update("DELETE FROM user_permission WHERE user_id = ?", userId);
        if (n > 0) audit.log(u, "PHÂN QUYỀN", "app_user", userId, r.str("full_name") + ": về mặc định của vai trò");
        reloadUser(userId);
        return userRow(r);
    }

    private void reloadUser(long userId) throws SQLException {
        Map<Perm, Boolean> m = new EnumMap<>(Perm.class);
        for (Row x : db.query("SELECT perm, allowed FROM user_permission WHERE user_id = ?", userId)) {
            Perm p = parse(x.str("perm"));
            if (p != null) m.put(p, x.lng("allowed") == 1);
        }
        Perm.setOverrides(userId, m);
    }
}
