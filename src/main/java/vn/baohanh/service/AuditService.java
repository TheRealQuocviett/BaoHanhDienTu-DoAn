package vn.baohanh.service;

import java.sql.SQLException;
import java.util.List;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;

/** Nhật ký hệ thống: ai làm gì, lúc nào. */
public class AuditService {
    private final Database db;

    public AuditService(Database db) {
        this.db = db;
    }

    public void log(User u, String action, String entity, Long entityId, String detail) throws SQLException {
        db.update("INSERT INTO audit_log (time, user_id, action, entity, entity_id, detail) VALUES (?, ?, ?, ?, ?, ?)",
                Database.now(), u == null ? null : u.id(), action, entity, entityId, detail);
    }

    /** Xem nhật ký (chỉ quản lý). */
    public List<Row> list(User u, String q, int limit) throws SQLException {
        Auth.require(u, Role.ADMIN);
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        return db.query("SELECT a.id, a.time, COALESCE(u.full_name, '(hệ thống)') AS user_name, a.action, a.entity, "
                + "a.entity_id, a.detail FROM audit_log a LEFT JOIN app_user u ON u.id = a.user_id "
                + "WHERE (a.action LIKE ? OR a.detail LIKE ? OR a.entity LIKE ? OR u.full_name LIKE ?) "
                + "ORDER BY a.id DESC LIMIT ?", like, like, like, like, Math.max(1, Math.min(limit, 1000)));
    }
}
