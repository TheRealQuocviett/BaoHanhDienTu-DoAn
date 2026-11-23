package vn.baohanh.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.util.Json;

/**
 * Nhân viên xử lý yêu cầu bảo hành khách gửi online:
 * NEW → CONFIRMED (hẹn ngày mang máy tới) → CONVERTED (máy tới, tạo phiếu); hoặc REJECTED.
 */
public class RequestService {
    private Notifier notifier = Notifier.NONE;

    public void setNotifier(Notifier n) { this.notifier = n == null ? Notifier.NONE : n; }
    private static final String SQL = "SELECT r.*, c.name AS customer_name, c.phone AS customer_phone, w.serial, w.code AS warranty_code, "
            + "w.expiry_date, p.name AS product_name, t.code AS ticket_code, COALESCE(us.full_name, '') AS handled_by_name "
            + "FROM service_request r JOIN customer c ON c.id = r.customer_id JOIN warranty w ON w.id = r.warranty_id "
            + "JOIN product p ON p.id = w.product_id LEFT JOIN ticket t ON t.id = r.ticket_id LEFT JOIN app_user us ON us.id = r.handled_by ";

    private final Database db;
    private final WarrantyService warranty;
    private final AuditService audit;

    public RequestService(Database db, WarrantyService warranty, AuditService audit) {
        this.db = db;
        this.warranty = warranty;
        this.audit = audit;
    }

    private PhotoService photos;

    public void setPhotos(PhotoService p) { this.photos = p; }

    private Row view(Row r) {
        r.put("status_label", CustomerService.REQUEST_LABELS.get(r.str("status")));
        r.put("active", !WarrantyService.today().isAfter(r.date("expiry_date")));
        try {
            r.put("photos", photos == null ? List.of() : photos.requestPhotos(r.lng("id")));
        } catch (SQLException e) {
            r.put("photos", List.of());
        }
        return r;
    }

    /** status: "" tất cả | OPEN (mới + đã hẹn) | tên trạng thái. */
    public List<Row> list(User u, String status) throws SQLException {
        Auth.require(u);
        String st = status == null ? "" : status;
        List<Row> rows = switch (st) {
            case "" -> db.query(SQL + "ORDER BY r.id DESC");
            case "OPEN" -> db.query(SQL + "WHERE r.status IN ('NEW','CONFIRMED') ORDER BY COALESCE(r.appointment, r.preferred_date, r.created_at), r.id");
            default -> {
                if (!CustomerService.REQUEST_LABELS.containsKey(st)) throw new AppException("Bộ lọc không hợp lệ");
                yield db.query(SQL + "WHERE r.status = ? ORDER BY r.id DESC", st);
            }
        };
        rows.forEach(this::view);
        return rows;
    }

    private Row get(long id) throws SQLException {
        Row r = db.one(SQL + "WHERE r.id = ?", id);
        if (r == null) throw AppException.notFound("yêu cầu #" + id);
        return view(r);
    }

    /** Xác nhận và hẹn ngày khách mang máy tới. */
    public Row confirm(User u, long id, String appointment, String note) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        LocalDate d;
        try { d = LocalDate.parse(appointment == null ? "" : appointment.trim()); }
        catch (Exception e) { throw new AppException("Vui lòng chọn ngày hẹn"); }
        if (d.isBefore(WarrantyService.today())) throw new AppException("Ngày hẹn không được ở quá khứ");
        Row r = get(id);
        if (!List.of("NEW", "CONFIRMED").contains(r.str("status"))) throw new AppException("Yêu cầu đã được xử lý");
        db.update("UPDATE service_request SET status = 'CONFIRMED', appointment = ?, staff_note = ?, handled_by = ?, handled_at = ? WHERE id = ?",
                d, WarrantyService.emptyToNull(note), u.id(), Database.now(), id);
        audit.log(u, "HẸN LỊCH", "service_request", id, r.str("code") + " → " + d);
        notifier.onRequest("REQUEST_CONFIRMED", id);
        return get(id);
    }

    public Row reject(User u, long id, String note) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        if (note == null || note.isBlank()) throw new AppException("Vui lòng ghi lý do từ chối (khách sẽ thấy)");
        Row r = get(id);
        if (!List.of("NEW", "CONFIRMED").contains(r.str("status"))) throw new AppException("Yêu cầu đã được xử lý");
        db.update("UPDATE service_request SET status = 'REJECTED', staff_note = ?, handled_by = ?, handled_at = ? WHERE id = ?",
                note.trim(), u.id(), Database.now(), id);
        audit.log(u, "TỪ CHỐI YÊU CẦU", "service_request", id, r.str("code"));
        notifier.onRequest("REQUEST_REJECTED", id);
        return get(id);
    }

    /** Khách đã mang máy tới: tạo phiếu bảo hành từ yêu cầu. Đầu vào giống tiếp nhận máy (trừ serial, lỗi). */
    public Row convert(User u, long id, Map<String, Object> in) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        Row r = get(id);
        if (!List.of("NEW", "CONFIRMED").contains(r.str("status"))) throw new AppException("Yêu cầu đã được xử lý");
        Map<String, Object> t = new LinkedHashMap<>(in);
        t.put("serial", r.str("serial"));
        String extra = Json.str(in, "issue");
        t.put("issue", r.str("issue") + (extra.isEmpty() ? "" : " | Ghi nhận tại quầy: " + extra) + " (yêu cầu online " + r.str("code") + ")");
        long ticketId = db.tx(() -> {
            long tid = warranty.createTicket(u, t).lng("id");
            db.update("UPDATE service_request SET status = 'CONVERTED', ticket_id = ?, handled_by = ?, handled_at = ? WHERE id = ?",
                    tid, u.id(), Database.now(), id);
            return tid;
        });
        audit.log(u, "TIẾP NHẬN TỪ YÊU CẦU", "service_request", id, r.str("code"));
        if (photos != null) {
            try { photos.copyRequestPhotosToTicket(id, ticketId, u); }
            catch (java.io.IOException e) { System.err.println("Khong chep duoc anh yeu cau: " + e.getMessage()); }
        }
        return new Row().with("request", get(id)).with("ticket_id", ticketId);
    }
}
