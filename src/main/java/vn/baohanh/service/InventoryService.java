package vn.baohanh.service;

import java.sql.SQLException;
import java.util.List;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;

/** Kho linh kiện: nhập kho, kiểm kê (điều chỉnh), xem thẻ kho. */
public class InventoryService {
    public static final java.util.Map<String, String> TYPE_LABELS = java.util.Map.of(
            "IN", "Nhập kho", "OUT", "Xuất cho phiếu", "RETURN", "Hoàn kho", "ADJUST", "Kiểm kê");

    private final Database db;
    private final AuditService audit;

    public InventoryService(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    private Row part(long partId) throws SQLException {
        Row p = db.one("SELECT * FROM part WHERE id = ?", partId);
        if (p == null) throw AppException.notFound("linh kiện");
        return p;
    }

    /** Nhập thêm hàng; có thể cập nhật giá nhập mới. */
    public Row stockIn(User u, long partId, long qty, long costPrice, String note) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.STOCK);
        if (qty <= 0 || qty > 1_000_000) throw new AppException("Số lượng nhập phải lớn hơn 0");
        if (costPrice < 0) throw new AppException("Giá nhập không hợp lệ");
        return db.tx(() -> {
            Row p = part(partId);
            db.update("UPDATE part SET stock = stock + ?" + (costPrice > 0 ? ", cost_price = " + costPrice : "") + " WHERE id = ?", qty, partId);
            db.update("INSERT INTO stock_movement (part_id, time, qty, type, user_id, note) VALUES (?, ?, ?, 'IN', ?, ?)",
                    partId, Database.now(), qty, u.id(), blank(note) ? "Nhập kho" : note.trim());
            audit.log(u, "NHẬP KHO", "part", partId, p.str("code") + " +" + qty);
            return part(partId);
        });
    }

    /** Kiểm kê: đặt lại số tồn thực tế, ghi chênh lệch. */
    public Row adjust(User u, long partId, long actual, String note) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.STOCK);
        if (actual < 0) throw new AppException("Số tồn thực tế không được âm");
        if (blank(note)) throw new AppException("Vui lòng ghi lý do điều chỉnh");
        return db.tx(() -> {
            Row p = part(partId);
            long diff = actual - p.lng("stock");
            if (diff == 0) throw new AppException("Số tồn không thay đổi");
            db.update("UPDATE part SET stock = ? WHERE id = ?", actual, partId);
            db.update("INSERT INTO stock_movement (part_id, time, qty, type, user_id, note) VALUES (?, ?, ?, 'ADJUST', ?, ?)",
                    partId, Database.now(), diff, u.id(), note.trim());
            audit.log(u, "KIỂM KÊ", "part", partId, p.str("code") + ": " + p.lng("stock") + " → " + actual);
            return part(partId);
        });
    }

    /** Thẻ kho: lịch sử nhập/xuất (partId = 0: tất cả). */
    public List<Row> movements(User u, long partId, int limit) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.STOCK_VIEW);
        List<Row> rows = db.query("SELECT m.id, m.time, pt.code, pt.name, m.qty, m.type, t.code AS ticket_code, "
                + "COALESCE(us.full_name, '') AS user_name, m.note FROM stock_movement m JOIN part pt ON pt.id = m.part_id "
                + "LEFT JOIN ticket t ON t.id = m.ticket_id LEFT JOIN app_user us ON us.id = m.user_id "
                + (partId > 0 ? "WHERE m.part_id = " + partId + " " : "") + "ORDER BY m.id DESC LIMIT ?", Math.max(1, Math.min(limit, 2000)));
        for (Row r : rows) r.put("type_label", TYPE_LABELS.get(r.str("type")));
        return rows;
    }

    public List<Row> lowStock(User u) throws SQLException {
        Auth.require(u);
        return db.query("SELECT id, code, name, unit, stock, min_stock FROM part WHERE stock <= min_stock ORDER BY stock - min_stock, code");
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
