package vn.baohanh.service;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.security.PasswordHasher;
import vn.baohanh.util.Json;

/**
 * Cổng khách hàng có đăng nhập.
 *
 * - Đăng ký / quên mật khẩu: nhập SĐT + số Serial của một máy mình đã mua (chỉ chủ máy biết)
 *   rồi đặt mật khẩu. Không cần SMS.
 * - Khách xem tất cả máy của mình, hạn bảo hành, tiến độ sửa; tự duyệt / từ chối báo giá;
 *   gửi yêu cầu bảo hành online (cửa hàng hẹn lịch rồi tiếp nhận thành phiếu).
 * Mọi thao tác đều kiểm tra máy / phiếu có đúng của khách đang đăng nhập không.
 */
public class CustomerService {
    private static final long SESSION_MS = 7L * 24 * 3600 * 1000;   // nhớ đăng nhập 7 ngày
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 5 * 60 * 1000;
    public static final Map<String, String> REQUEST_LABELS = Map.of(
            "NEW", "Chờ cửa hàng xác nhận", "CONFIRMED", "Đã hẹn lịch", "CONVERTED", "Đã tiếp nhận máy",
            "REJECTED", "Cửa hàng từ chối", "CANCELLED", "Khách đã hủy");

    private record Session(long customerId, long expires) {}
    private record Fails(int count, long lockedUntil) {}

    private final Database db;
    private final WarrantyService warranty;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Fails> fails = new ConcurrentHashMap<>();

    public CustomerService(Database db, WarrantyService warranty, AuditService audit) {
        this.db = db;
        this.warranty = warranty;
        this.audit = audit;
    }

    private static String phone(String p) {
        String s = p == null ? "" : p.replaceAll("[\\s.-]", "");
        if (!CatalogService.PHONE.matcher(s).matches()) throw new AppException("Số điện thoại không hợp lệ");
        return s;
    }

    private void throttle(String key) {
        Fails f = fails.get(key);
        long now = System.currentTimeMillis();
        if (f != null && f.lockedUntil() > now)
            throw new AppException(429, "Bạn nhập sai quá nhiều lần, vui lòng thử lại sau " + ((f.lockedUntil() - now) / 60000 + 1) + " phút");
    }

    private void fail(String key) {
        Fails f = fails.get(key);
        int c = (f == null || f.lockedUntil() > 0 ? 0 : f.count()) + 1;
        fails.put(key, new Fails(c, c >= MAX_FAILS ? System.currentTimeMillis() + LOCK_MS : 0));
    }

    // ===================== Tài khoản =====================

    /** Đăng ký hoặc đặt lại mật khẩu bằng SĐT + serial của một máy đã mua. Trả về token phiên. */
    public String register(String phoneIn, String serial, String password) throws SQLException {
        String p = phone(phoneIn);
        String key = "reg:" + p;
        throttle(key);
        Auth.checkStrength(password);
        Row c = db.one("SELECT c.id, c.name FROM customer c JOIN warranty w ON w.customer_id = c.id "
                + "WHERE c.phone = ? AND w.serial = ?", p, serial == null ? "" : serial.trim());
        if (c == null) {
            fail(key);
            throw new AppException("Số điện thoại và số Serial không khớp với máy nào đã mua tại cửa hàng");
        }
        fails.remove(key);
        long id = c.lng("id");
        boolean exists = db.scalar("SELECT COUNT(*) FROM customer_account WHERE customer_id = ?", id) > 0;
        if (exists) db.update("UPDATE customer_account SET password_hash = ? WHERE customer_id = ?", PasswordHasher.hash(password), id);
        else db.update("INSERT INTO customer_account (customer_id, password_hash, created_at) VALUES (?, ?, ?)",
                id, PasswordHasher.hash(password), Database.now());
        audit.log(null, exists ? "KHÁCH ĐẶT LẠI MẬT KHẨU" : "KHÁCH ĐĂNG KÝ", "customer", id, c.str("name") + " – " + p);
        return newSession(id);
    }

    public String login(String phoneIn, String password) throws SQLException {
        String p = phone(phoneIn);
        String key = "login:" + p;
        throttle(key);
        Row a = db.one("SELECT a.customer_id, a.password_hash FROM customer_account a JOIN customer c ON c.id = a.customer_id WHERE c.phone = ?", p);
        if (a == null || !PasswordHasher.verify(password, a.str("password_hash"))) {
            fail(key);
            throw new AppException("Sai số điện thoại hoặc mật khẩu. Chưa có tài khoản? Bấm \"Đăng ký\".");
        }
        fails.remove(key);
        db.update("UPDATE customer_account SET last_login = ? WHERE customer_id = ?", Database.now(), a.lng("customer_id"));
        return newSession(a.lng("customer_id"));
    }

    private String newSession(long customerId) {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        sessions.put(token, new Session(customerId, System.currentTimeMillis() + SESSION_MS));
        return token;
    }

    /** id khách của phiên, hoặc lỗi 401. */
    public long require(String token) {
        Session s = token == null ? null : sessions.get(token);
        if (s == null || s.expires() < System.currentTimeMillis()) {
            if (token != null) sessions.remove(token);
            throw new AppException(401, "Vui lòng đăng nhập");
        }
        return s.customerId();
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    public void changePassword(long customerId, String oldPw, String newPw) throws SQLException {
        Row a = db.one("SELECT password_hash FROM customer_account WHERE customer_id = ?", customerId);
        if (a == null || !PasswordHasher.verify(oldPw, a.str("password_hash"))) throw new AppException("Mật khẩu hiện tại không đúng");
        Auth.checkStrength(newPw);
        db.update("UPDATE customer_account SET password_hash = ? WHERE customer_id = ?", PasswordHasher.hash(newPw), customerId);
    }

    public Row updateProfile(long customerId, Map<String, Object> in) throws SQLException {
        String email = Json.str(in, "email");
        if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new AppException("Email không hợp lệ");
        String address = Json.str(in, "address");
        if (address.length() > 300) throw new AppException("Địa chỉ quá dài");
        db.tx(() -> {
            db.update("UPDATE customer SET email = ?, address = ? WHERE id = ?",
                    email.isEmpty() ? null : email, address.isEmpty() ? null : address, customerId);
            if (in.containsKey("no_reminders")) {
                Object v = in.get("no_reminders");
                boolean off = Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
                if (off) db.update("INSERT OR IGNORE INTO notify_optout (customer_id, created_at) VALUES (?, ?)", customerId, Database.now());
                else db.update("DELETE FROM notify_optout WHERE customer_id = ?", customerId);
            }
            return null;
        });
        return me(customerId);
    }

    // ===================== Dữ liệu của khách =====================

    /** Hồ sơ + tất cả máy + phiếu + yêu cầu của khách. Không lộ ghi chú nội bộ. */
    public Row me(long customerId) throws SQLException {
        Row c = db.one("SELECT id, name, phone, email, address FROM customer WHERE id = ?", customerId);
        if (c == null) throw new AppException(401, "Tài khoản không còn tồn tại");
        c.put("no_reminders", db.scalar("SELECT COUNT(*) FROM notify_optout WHERE customer_id = ?", customerId) > 0);
        LocalDate today = WarrantyService.today();
        List<Row> devices = new ArrayList<>();
        for (Row w : db.query("SELECT * FROM v_warranty WHERE customer_id = ? ORDER BY purchase_date DESC", customerId)) {
            WarrantyService.warrantyView(w, today);
            Row d = new Row();
            for (String k : new String[]{"id", "code", "serial", "product_name", "brand_name", "purchase_date", "expiry_date",
                    "months", "active", "days_left", "invoice_no"}) d.put(k, w.get(k));
            List<Row> tickets = new ArrayList<>();
            for (Row t : db.query("SELECT t.id, t.code, t.issue, t.received_at, t.due_date, t.status, t.in_warranty, t.quote_status, "
                    + "t.diagnosis, t.returned_at, t.paid_amount, t.invoice_no, t.payment_method, vc.total FROM ticket t JOIN v_ticket_cost vc ON vc.ticket_id = t.id "
                    + "WHERE t.warranty_id = ? ORDER BY t.received_at DESC", w.lng("id"))) {
                TicketStatus s = TicketStatus.valueOf(t.str("status"));
                t.put("status_label", s.getLabel());
                t.put("in_warranty", t.bool("in_warranty"));
                t.put("quote_pending", "PENDING".equals(t.str("quote_status")) && !s.isClosed());
                t.put("quote_label", WarrantyService.QUOTE_LABELS.get(t.str("quote_status")));
                t.put("parts", db.query("SELECT pt.name, tp.qty, tp.unit_price, tp.charged, tp.qty * tp.unit_price AS amount "
                        + "FROM ticket_part tp JOIN part pt ON pt.id = tp.part_id WHERE tp.ticket_id = ?", t.lng("id")));
                List<Row> steps = new ArrayList<>();
                String last = "";
                for (Row h : db.query("SELECT time, status FROM ticket_history WHERE ticket_id = ? ORDER BY time, id", t.lng("id"))) {
                    if (h.str("status").equals(last)) continue;
                    last = h.str("status");
                    steps.add(new Row().with("time", h.str("time")).with("label", TicketStatus.valueOf(last).getLabel()));
                }
                t.put("steps", steps);
                Row rv = db.one("SELECT stars, comment, created_at, updated_at FROM ticket_review WHERE ticket_id = ?", t.lng("id"));
                t.put("review", rv);
                t.put("can_review", s == TicketStatus.RETURNED);
                t.put("payment_label", WarrantyService.PAY_METHODS.getOrDefault(t.str("payment_method"), ""));
                tickets.add(t);
            }
            d.put("tickets", tickets);
            d.put("open_ticket", tickets.stream().anyMatch(t -> !TicketStatus.valueOf(t.str("status")).isClosed()));
            d.put("extensions", db.query("SELECT code, months, price, old_expiry, new_expiry, created_at FROM warranty_extension "
                    + "WHERE warranty_id = ? ORDER BY id", w.lng("id")));
            devices.add(d);
        }
        c.put("devices", devices);
        c.put("requests", requestsOf(customerId));
        // Hóa đơn sửa chữa (phiếu đã trả máy) mới nhất trước
        List<Row> invoices = new ArrayList<>();
        for (Row d : devices) for (Object o : (List<?>) d.get("tickets")) {
            Row t = (Row) o;
            if (!"RETURNED".equals(t.str("status"))) continue;
            invoices.add(new Row().with("ticket_id", t.lng("id")).with("code", t.str("code")).with("invoice_no", t.str("invoice_no"))
                    .with("product_name", d.str("product_name")).with("returned_at", t.str("returned_at"))
                    .with("paid_amount", t.lng("paid_amount")).with("payment_label", t.str("payment_label"))
                    .with("in_warranty", t.get("in_warranty")));
        }
        invoices.sort((a, b) -> b.str("returned_at").compareTo(a.str("returned_at")));
        c.put("invoices", invoices);
        c.put("unread_hint", db.scalar("SELECT COUNT(*) FROM notification WHERE customer_id = ? AND status = 'SENT' AND event <> 'TEST'", customerId));
        return c;
    }

    private List<Row> requestsOf(long customerId) throws SQLException {
        List<Row> list = db.query("SELECT r.id, r.code, r.issue, r.preferred_date, r.appointment, r.status, r.staff_note, r.created_at, "
                + "w.serial, p.name AS product_name, t.code AS ticket_code FROM service_request r JOIN warranty w ON w.id = r.warranty_id "
                + "JOIN product p ON p.id = w.product_id LEFT JOIN ticket t ON t.id = r.ticket_id "
                + "WHERE r.customer_id = ? ORDER BY r.id DESC", customerId);
        for (Row r : list) {
            r.put("status_label", REQUEST_LABELS.get(r.str("status")));
            r.put("photos", photos == null ? List.of() : photos.requestPhotos(r.lng("id")));
        }
        return list;
    }

    /** Khách duyệt / từ chối báo giá của phiếu thuộc máy mình. */
    public Row decideQuote(long customerId, long ticketId, boolean approve, String note) throws SQLException {
        Row t = db.one("SELECT t.id FROM ticket t JOIN warranty w ON w.id = t.warranty_id WHERE t.id = ? AND w.customer_id = ?",
                ticketId, customerId);
        if (t == null) throw AppException.notFound("phiếu");
        warranty.decideQuoteOnline(ticketId, approve, note);
        return me(customerId);
    }

    /** Khách gửi yêu cầu bảo hành online cho một máy của mình. */
    public Row createRequest(long customerId, Map<String, Object> in) throws SQLException {
        long wid = Json.num(in, "warranty_id", 0);
        String issue = Json.str(in, "issue");
        if (issue.length() < 5) throw new AppException("Vui lòng mô tả lỗi của máy (ít nhất 5 ký tự)");
        if (issue.length() > 1000) throw new AppException("Mô tả quá dài");
        String pd = Json.str(in, "preferred_date");
        LocalDate pref = null;
        if (!pd.isEmpty()) {
            try { pref = LocalDate.parse(pd); } catch (Exception e) { throw new AppException("Ngày mong muốn không hợp lệ"); }
            if (pref.isBefore(WarrantyService.today())) throw new AppException("Ngày mong muốn không được ở quá khứ");
            if (pref.isAfter(WarrantyService.today().plusDays(60))) throw new AppException("Chỉ đặt lịch trong vòng 60 ngày");
        }
        LocalDate prefDate = pref;
        long newId = db.tx(() -> {
            Row w = db.one("SELECT id, code FROM warranty WHERE id = ? AND customer_id = ?", wid, customerId);
            if (w == null) throw new AppException("Vui lòng chọn máy cần bảo hành");
            if (db.scalar("SELECT COUNT(*) FROM service_request WHERE warranty_id = ? AND status IN ('NEW','CONFIRMED')", wid) > 0)
                throw new AppException("Máy này đã có yêu cầu đang chờ xử lý");
            if (db.scalar("SELECT COUNT(*) FROM ticket WHERE warranty_id = ? AND status NOT IN " + TicketStatus.CLOSED_SQL, wid) > 0)
                throw new AppException("Máy này đang được cửa hàng xử lý");
            long id = db.insert("INSERT INTO service_request (customer_id, warranty_id, issue, preferred_date, status, created_at) "
                    + "VALUES (?, ?, ?, ?, 'NEW', ?)", customerId, wid, issue, prefDate, Database.now());
            String code = String.format("YC-%06d", id);
            db.update("UPDATE service_request SET code = ? WHERE id = ?", code, id);
            audit.log(null, "KHÁCH GỬI YÊU CẦU", "service_request", id, code + " – " + w.str("code"));
            return id;
        });
        return me(customerId).with("last_request_id", newId);
    }

    public Row cancelRequest(long customerId, long requestId) throws SQLException {
        int n = db.update("UPDATE service_request SET status = 'CANCELLED', handled_at = ? WHERE id = ? AND customer_id = ? "
                + "AND status IN ('NEW','CONFIRMED')", Database.now(), requestId, customerId);
        if (n == 0) throw new AppException("Không thể hủy yêu cầu này");
        return me(customerId);
    }

    // ===================== Ảnh, đánh giá, hộp thư, phiếu in =====================

    private PhotoService photos;

    public void setPhotos(PhotoService p) { this.photos = p; }

    /** Khách chấm sao (1–5) + nhận xét cho phiếu đã trả máy của mình. Gửi lại sẽ cập nhật đánh giá cũ. */
    public Row review(long customerId, long ticketId, int stars, String comment) throws SQLException {
        if (stars < 1 || stars > 5) throw new AppException("Vui lòng chọn từ 1 đến 5 sao");
        String cm = comment == null ? "" : comment.trim();
        if (cm.length() > 500) throw new AppException("Nhận xét tối đa 500 ký tự");
        Row t = db.one("SELECT t.id, t.code, t.status FROM ticket t JOIN warranty w ON w.id = t.warranty_id WHERE t.id = ? AND w.customer_id = ?",
                ticketId, customerId);
        if (t == null) throw AppException.notFound("phiếu");
        if (!"RETURNED".equals(t.str("status"))) throw new AppException("Chỉ đánh giá được sau khi đã nhận lại máy");
        String now = Database.now();
        db.update("INSERT INTO ticket_review (ticket_id, customer_id, stars, comment, created_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(ticket_id) DO UPDATE SET stars = excluded.stars, comment = excluded.comment, updated_at = ?",
                ticketId, customerId, stars, cm.isEmpty() ? null : cm, now, now);
        audit.log(null, "KHÁCH ĐÁNH GIÁ", "ticket", ticketId, t.str("code") + ": " + stars + " sao");
        return me(customerId);
    }

    /** Hộp thư: các thông báo cửa hàng đã gửi cho khách (gộp các kênh của cùng một sự kiện). */
    public List<Row> inbox(long customerId) throws SQLException {
        Map<String, Row> groups = new java.util.LinkedHashMap<>();
        for (Row n : db.query("SELECT n.id, n.created_at, n.sent_at, n.event, n.channel, n.subject, n.body, n.status, n.dedup_key, "
                + "COALESCE(t.code, '') AS ticket_code, COALESCE(r.code, '') AS request_code FROM notification n "
                + "LEFT JOIN ticket t ON t.id = n.ticket_id LEFT JOIN service_request r ON r.id = n.request_id "
                + "WHERE n.customer_id = ? AND n.event <> 'TEST' AND n.status <> 'FAILED' ORDER BY n.id DESC LIMIT 300", customerId)) {
            String key = n.str("dedup_key").isEmpty() ? "N" + n.lng("id") : n.str("dedup_key").replaceAll(":(EMAIL|SMS|ZALO)$", "");
            Row g = groups.get(key);
            if (g == null) {
                g = new Row().with("id", n.lng("id")).with("time", n.str("created_at")).with("event", n.str("event"))
                        .with("title", vn.baohanh.notify.NotificationService.Event.valueOf(n.str("event")).label)
                        .with("ref", n.str("ticket_code").isEmpty() ? n.str("request_code") : n.str("ticket_code"))
                        .with("body", "").with("channels", new ArrayList<String>()).with("pending", false);
                groups.put(key, g);
            }
            @SuppressWarnings("unchecked") List<String> ch = (List<String>) g.get("channels");
            String label = vn.baohanh.notify.NotificationService.CHANNEL_LABELS.get(n.str("channel"));
            if (!ch.contains(label)) ch.add(label);
            if ("PENDING".equals(n.str("status"))) g.put("pending", true);
            // ưu tiên nội dung email (có dấu), rồi SMS; Zalo chỉ có mã mẫu
            boolean better = "EMAIL".equals(n.str("channel")) || (g.str("body").isEmpty() && "SMS".equals(n.str("channel")));
            if (better) g.put("body", n.str("body"));
        }
        List<Row> out = new ArrayList<>(groups.values());
        for (Row g : out) if (g.str("body").isEmpty()) g.put("body", "Tin Zalo: " + g.str("title") + (g.str("ref").isEmpty() ? "" : " – " + g.str("ref")));
        return out;
    }

    /** Kiểm tra khách có quyền xem phiếu in này không (bao-hanh: máy của mình; tra-may: phiếu đã trả của mình). */
    public void checkPrint(long customerId, String kind, long id) throws SQLException {
        boolean ok = switch (kind) {
            case "bao-hanh" -> db.scalar("SELECT COUNT(*) FROM warranty WHERE id = ? AND customer_id = ?", id, customerId) > 0;
            case "tra-may" -> db.scalar("SELECT COUNT(*) FROM ticket t JOIN warranty w ON w.id = t.warranty_id "
                    + "WHERE t.id = ? AND w.customer_id = ? AND t.status = 'RETURNED'", id, customerId) > 0;
            default -> false;
        };
        if (!ok) throw AppException.notFound("phiếu");
    }
}
