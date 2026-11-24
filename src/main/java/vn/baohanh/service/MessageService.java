package vn.baohanh.service;

import java.sql.SQLException;
import java.util.List;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.User;

/**
 * Tin nhắn giữa khách và cửa hàng, gắn với từng phiếu bảo hành.
 * - Khách nhắn từ cổng tra cứu (tài khoản của tôi); nhân viên trả lời trong phiếu.
 * - Theo dõi đã đọc / chưa đọc cho từng phía; nhân viên trả lời thì khách được báo qua email/SMS (nếu bật).
 */
public class MessageService {
    public static final int MAX_LEN = 1000;

    private final Database db;
    private final AuditService audit;
    private Notifier notifier = Notifier.NONE;

    public MessageService(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    public void setNotifier(Notifier n) { this.notifier = n == null ? Notifier.NONE : n; }

    private static String clean(String body) {
        String b = body == null ? "" : body.trim().replace("\r\n", "\n");
        if (b.isEmpty()) throw new AppException("Nội dung tin nhắn trống");
        if (b.length() > MAX_LEN) throw new AppException("Tin nhắn tối đa " + MAX_LEN + " ký tự");
        return b;
    }

    private List<Row> thread(long ticketId) throws SQLException {
        List<Row> list = db.query("SELECT m.id, m.sender, m.body, m.created_at, m.read_by_staff, m.read_by_customer, "
                + "COALESCE(u.full_name, '') AS staff_name FROM ticket_message m LEFT JOIN app_user u ON u.id = m.user_id "
                + "WHERE m.ticket_id = ? ORDER BY m.id", ticketId);
        for (Row r : list) r.put("mine_staff", "STAFF".equals(r.str("sender")));
        return list;
    }

    // ===================== Phía nhân viên =====================

    /** Đọc hội thoại của phiếu (đánh dấu tin của khách là đã đọc). */
    public List<Row> staffThread(User u, long ticketId) throws SQLException {
        Auth.require(u);
        if (db.scalar("SELECT COUNT(*) FROM ticket WHERE id = ?", ticketId) == 0) throw AppException.notFound("phiếu");
        db.update("UPDATE ticket_message SET read_by_staff = 1 WHERE ticket_id = ? AND sender = 'CUSTOMER' AND read_by_staff = 0", ticketId);
        return thread(ticketId);
    }

    public List<Row> staffSend(User u, long ticketId, String body) throws SQLException {
        Auth.require(u);
        String b = clean(body);
        Row t = db.one("SELECT id, code FROM ticket WHERE id = ?", ticketId);
        if (t == null) throw AppException.notFound("phiếu");
        long id = db.tx(() -> {
            long mid = db.insert("INSERT INTO ticket_message (ticket_id, sender, user_id, body, created_at, read_by_staff) "
                    + "VALUES (?, 'STAFF', ?, ?, ?, 1)", ticketId, u.id(), b, Database.now());
            db.update("UPDATE ticket_message SET read_by_staff = 1 WHERE ticket_id = ? AND sender = 'CUSTOMER'", ticketId);
            audit.log(u, "TRẢ LỜI KHÁCH", "ticket", ticketId, t.str("code"));
            return mid;
        });
        notifier.onMessage(ticketId, id, b);
        return thread(ticketId);
    }

    /** Danh sách hội thoại (phiếu có tin nhắn), phiếu có tin chưa đọc lên đầu. */
    public List<Row> conversations(User u, boolean unreadOnly) throws SQLException {
        Auth.require(u);
        List<Row> list = db.query("SELECT t.id AS ticket_id, t.code, t.status, cu.name AS customer_name, cu.phone AS customer_phone, "
                + "p.name AS product_name, COUNT(m.id) AS total, "
                + "SUM(CASE WHEN m.sender = 'CUSTOMER' AND m.read_by_staff = 0 THEN 1 ELSE 0 END) AS unread, MAX(m.id) AS last_id "
                + "FROM ticket_message m JOIN ticket t ON t.id = m.ticket_id JOIN warranty w ON w.id = t.warranty_id "
                + "JOIN customer cu ON cu.id = w.customer_id JOIN product p ON p.id = w.product_id "
                + "GROUP BY t.id " + (unreadOnly ? "HAVING unread > 0 " : "") + "ORDER BY unread > 0 DESC, last_id DESC LIMIT 300");
        for (Row r : list) {
            Row last = db.one("SELECT sender, body, created_at FROM ticket_message WHERE id = ?", r.lng("last_id"));
            r.put("last_sender", last.str("sender"));
            r.put("last_body", last.str("body").length() > 120 ? last.str("body").substring(0, 120) + "…" : last.str("body"));
            r.put("last_time", last.str("created_at"));
            r.put("status_label", vn.baohanh.model.TicketStatus.valueOf(r.str("status")).getLabel());
        }
        return list;
    }

    public long unreadForStaff() throws SQLException {
        return db.scalar("SELECT COUNT(DISTINCT ticket_id) FROM ticket_message WHERE sender = 'CUSTOMER' AND read_by_staff = 0");
    }

    // ===================== Phía khách =====================

    private void checkOwner(long customerId, long ticketId) throws SQLException {
        if (db.scalar("SELECT COUNT(*) FROM ticket t JOIN warranty w ON w.id = t.warranty_id WHERE t.id = ? AND w.customer_id = ?",
                ticketId, customerId) == 0) throw AppException.notFound("phiếu");
    }

    public List<Row> customerThread(long customerId, long ticketId) throws SQLException {
        checkOwner(customerId, ticketId);
        db.update("UPDATE ticket_message SET read_by_customer = 1 WHERE ticket_id = ? AND sender = 'STAFF' AND read_by_customer = 0", ticketId);
        return thread(ticketId);
    }

    public List<Row> customerSend(long customerId, long ticketId, String body) throws SQLException {
        checkOwner(customerId, ticketId);
        String b = clean(body);
        // chống spam: tối đa 10 tin trong 10 phút
        if (db.scalar("SELECT COUNT(*) FROM ticket_message m JOIN ticket t ON t.id = m.ticket_id JOIN warranty w ON w.id = t.warranty_id "
                + "WHERE w.customer_id = ? AND m.sender = 'CUSTOMER' AND m.created_at >= ?",
                customerId, Database.ts(java.time.LocalDateTime.now().minusMinutes(10))) >= 10) throw new AppException("Bạn nhắn quá nhanh, vui lòng chờ vài phút");
        db.insert("INSERT INTO ticket_message (ticket_id, sender, body, created_at, read_by_customer) VALUES (?, 'CUSTOMER', ?, ?, 1)",
                ticketId, b, Database.now());
        db.update("UPDATE ticket_message SET read_by_customer = 1 WHERE ticket_id = ? AND sender = 'STAFF'", ticketId);
        return thread(ticketId);
    }

    public static final String PAY_TAG = "[Thanh toán] ";

    /**
     * Khách chọn cách thanh toán cho phiếu đã sửa xong: CASH = trả tại quầy khi nhận máy, TRANSFER = báo đã chuyển khoản.
     * Ghi thành tin nhắn của khách (nhân viên thấy ngay trong phiếu, bảng công việc, menu Tin nhắn khách).
     */
    public void paymentNotice(long customerId, long ticketId, String method) throws SQLException {
        checkOwner(customerId, ticketId);
        Row t = db.one("SELECT t.code, t.status, t.quote_status, vc.total FROM ticket t JOIN v_ticket_cost vc ON vc.ticket_id = t.id WHERE t.id = ?", ticketId);
        if (!"READY".equals(t.str("status")) || t.lng("total") <= 0 || "PENDING".equals(t.str("quote_status")))
            throw new AppException("Phiếu chưa đến bước thanh toán");
        String text = switch (method == null ? "" : method) {
            case "CASH" -> "Tôi sẽ thanh toán " + vn.baohanh.util.Fmt.money(t.lng("total")) + " tại cửa hàng khi đến nhận máy (tiền mặt / thẻ).";
            case "TRANSFER" -> "Tôi đã chuyển khoản " + vn.baohanh.util.Fmt.money(t.lng("total")) + " với nội dung \""
                    + t.str("code").replace("-", "") + " thanh toan\". Nhờ cửa hàng kiểm tra giúp.";
            default -> throw new AppException("Cách thanh toán không hợp lệ");
        };
        db.insert("INSERT INTO ticket_message (ticket_id, sender, body, created_at, read_by_customer) VALUES (?, 'CUSTOMER', ?, ?, 1)",
                ticketId, PAY_TAG + text, Database.now());
    }

    /** Lựa chọn thanh toán gần nhất của khách cho phiếu (null nếu chưa chọn). */
    public Row paymentClaim(long ticketId) throws SQLException {
        Row m = db.one("SELECT body, created_at FROM ticket_message WHERE ticket_id = ? AND sender = 'CUSTOMER' AND body LIKE ? ORDER BY id DESC LIMIT 1",
                ticketId, PAY_TAG + "%");
        if (m == null) return null;
        return new Row().with("method", m.str("body").contains("chuyển khoản") ? "TRANSFER" : "CASH")
                .with("time", m.str("created_at")).with("text", m.str("body").substring(PAY_TAG.length()));
    }

    public long unreadForCustomer(long customerId, long ticketId) throws SQLException {
        return db.scalar("SELECT COUNT(*) FROM ticket_message WHERE ticket_id = ? AND sender = 'STAFF' AND read_by_customer = 0", ticketId);
    }

    public long countFor(long ticketId) throws SQLException {
        return db.scalar("SELECT COUNT(*) FROM ticket_message WHERE ticket_id = ?", ticketId);
    }
}
