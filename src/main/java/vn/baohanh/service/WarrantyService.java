package vn.baohanh.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.model.User;
import vn.baohanh.util.Json;

/**
 * Nghiệp vụ chính:
 *  - Kích hoạt bảo hành điện tử khi bán hàng (tự tạo khách hàng mới nếu chưa có).
 *  - Tiếp nhận máy, phân công kỹ thuật viên, xử lý theo quy trình trạng thái.
 *  - Linh kiện dùng cho phiếu (trừ kho), tiền công, báo giá cho máy hết hạn bảo hành.
 *  - Thu tiền, xuất số hóa đơn sửa chữa và trả máy.
 */
public class WarrantyService {
    private Notifier notifier = Notifier.NONE;

    public void setNotifier(Notifier n) { this.notifier = n == null ? Notifier.NONE : n; }
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");
    public static final Map<String, String> QUOTE_LABELS = Map.of(
            "NONE", "Chưa báo giá", "PENDING", "Chờ khách duyệt", "APPROVED", "Khách đồng ý", "DECLINED", "Khách từ chối");
    public static final Map<String, String> PAY_METHODS = new LinkedHashMap<>();
    static {
        PAY_METHODS.put("CASH", "Tiền mặt");
        PAY_METHODS.put("TRANSFER", "Chuyển khoản");
        PAY_METHODS.put("CARD", "Quẹt thẻ");
    }

    private static final String TICKET_SQL = "SELECT t.*, w.serial, w.expiry_date, w.code AS warranty_code, "
            + "p.name AS product_name, b.name AS brand_name, cu.name AS customer_name, cu.phone AS customer_phone, "
            + "COALESCE(cu.address, '') AS customer_address, COALESCE(cu.email, '') AS customer_email, "
            + "tu.full_name AS technician_name, ru.full_name AS received_by_name, sc.name AS service_center_name, "
            + "vc.parts_charged, vc.parts_free, vc.total "
            + "FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN product p ON p.id = w.product_id "
            + "JOIN brand b ON b.id = p.brand_id JOIN customer cu ON cu.id = w.customer_id "
            + "LEFT JOIN app_user tu ON tu.id = t.technician_id LEFT JOIN app_user ru ON ru.id = t.received_by "
            + "LEFT JOIN service_center sc ON sc.id = t.service_center_id JOIN v_ticket_cost vc ON vc.ticket_id = t.id ";

    private final Database db;
    private final AuditService audit;

    public WarrantyService(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    static LocalDate today() { return LocalDate.now(); }

    private static String norm(String s) { return CatalogService.norm(s); }

    private static boolean matches(String key, Row r, String... cols) {
        if (key.isEmpty()) return true;
        for (String c : cols) if (norm(r.str(c)).contains(key)) return true;
        return false;
    }

    private static LocalDate parseDate(String s, LocalDate def, String label) {
        if (s == null || s.isBlank()) return def;
        try { return LocalDate.parse(s.trim()); }
        catch (DateTimeParseException e) { throw new AppException(label + " không hợp lệ"); }
    }

    // =====================================================================
    //  BẢO HÀNH
    // =====================================================================

    /** Thêm các trường tính toán: còn hạn, số ngày còn lại. */
    public static Row warrantyView(Row w, LocalDate today) {
        LocalDate exp = w.date("expiry_date");
        w.put("active", !today.isAfter(exp));
        w.put("days_left", ChronoUnit.DAYS.between(today, exp));
        return w;
    }

    public List<Row> listWarranties(User u, String q, String filter) throws SQLException {
        Auth.require(u);
        String t = today().toString();
        String where = switch (filter == null ? "" : filter) {
            case "active" -> "WHERE expiry_date >= '" + t + "'";
            case "expired" -> "WHERE expiry_date < '" + t + "'";
            case "expiring" -> "WHERE expiry_date BETWEEN '" + t + "' AND '" + today().plusDays(30) + "'";
            default -> "";
        };
        String key = norm(q);
        List<Row> out = new ArrayList<>();
        for (Row w : db.query("SELECT * FROM v_warranty " + where + " ORDER BY created_at DESC, id DESC")) {
            if (matches(key, w, "serial", "code", "product_name", "brand_name", "customer_name", "customer_phone", "invoice_no"))
                out.add(warrantyView(w, today()));
        }
        return out;
    }

    public Row getWarranty(User u, long id) throws SQLException {
        Auth.require(u);
        Row w = db.one("SELECT * FROM v_warranty WHERE id = ?", id);
        if (w == null) throw AppException.notFound("bảo hành #" + id);
        warrantyView(w, today());
        Row cu = db.one("SELECT COALESCE(address, '') AS address, COALESCE(email, '') AS email FROM customer WHERE id = ?", w.lng("customer_id"));
        w.put("customer_address", cu == null ? "" : cu.str("address"));
        w.put("customer_email", cu == null ? "" : cu.str("email"));
        w.put("tickets", db.query(TICKET_SQL + "WHERE t.warranty_id = ? ORDER BY t.received_at DESC", id)
                .stream().map(this::ticketView).toList());
        w.put("extensions", db.query("SELECT e.*, COALESCE(us.full_name, '') AS user_name FROM warranty_extension e "
                + "LEFT JOIN app_user us ON us.id = e.created_by WHERE e.warranty_id = ? ORDER BY e.id", id));
        return w;
    }

    /**
     * Kích hoạt bảo hành.
     * Đầu vào: serial, product_id, customer_phone, customer_name (khi là khách mới), customer_email,
     * customer_address, invoice_no, purchase_date, months (để trống = theo sản phẩm), note.
     */
    public Row activate(User u, Map<String, Object> in) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        String serial = Json.str(in, "serial").toUpperCase(Locale.ROOT).replaceAll("\\s", "");
        if (serial.isEmpty()) throw new AppException("Vui lòng nhập số Serial/IMEI");
        if (serial.length() > 60) throw new AppException("Serial quá dài");
        long productId = Json.num(in, "product_id", 0);
        Row product = db.one("SELECT * FROM product WHERE id = ?", productId);
        if (product == null) throw new AppException("Vui lòng chọn sản phẩm");
        String phone = Json.str(in, "customer_phone").replaceAll("[\\s.-]", "");
        if (!CatalogService.PHONE.matcher(phone).matches())
            throw new AppException("Số điện thoại khách không hợp lệ (10–11 số, bắt đầu bằng 0)");
        int months = (int) Json.num(in, "months", product.lng("default_months"));
        if (months < 1 || months > 120) throw new AppException("Thời hạn bảo hành phải từ 1 đến 120 tháng");
        LocalDate pd = parseDate(Json.str(in, "purchase_date"), today(), "Ngày mua");
        if (pd.isAfter(today())) throw new AppException("Ngày mua không được ở tương lai");

        long id = db.tx(() -> {
            if (db.scalar("SELECT COUNT(*) FROM warranty WHERE serial = ?", serial) > 0)
                throw new AppException("Serial " + serial + " đã được kích hoạt bảo hành trước đó");
            Row c = db.one("SELECT id FROM customer WHERE phone = ?", phone);
            long customerId;
            if (c != null) {
                customerId = c.lng("id");
            } else {
                String name = Json.str(in, "customer_name");
                if (name.isEmpty()) throw new AppException("Khách hàng mới: vui lòng nhập họ tên");
                customerId = db.insert("INSERT INTO customer (name, phone, email, address, created_at) VALUES (?, ?, ?, ?, ?)",
                        name, phone, emptyToNull(Json.str(in, "customer_email")), emptyToNull(Json.str(in, "customer_address")),
                        Database.now());
                audit.log(u, "THÊM", "customer", customerId, "Khách hàng: " + name + " – " + phone);
            }
            long newId = db.insert("INSERT INTO warranty (serial, product_id, customer_id, invoice_no, purchase_date, months, "
                    + "expiry_date, note, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    serial, productId, customerId, emptyToNull(Json.str(in, "invoice_no")), pd, months,
                    pd.plusMonths(months), emptyToNull(Json.str(in, "note")), u.id(), Database.now());
            String code = "BH" + pd.format(YYMM) + "-" + String.format("%04d", newId);
            db.update("UPDATE warranty SET code = ? WHERE id = ?", code, newId);
            audit.log(u, "KÍCH HOẠT BẢO HÀNH", "warranty", newId, code + " – " + product.str("name") + " – " + serial);
            return newId;
        });
        return getWarranty(u, id);
    }

    public void deleteWarranty(User u, long id) throws SQLException {
        Auth.require(u, Role.ADMIN);
        Row w = getWarranty(u, id);
        if (db.scalar("SELECT COUNT(*) FROM ticket WHERE warranty_id = ?", id) > 0)
            throw new AppException("Không thể xóa: bảo hành này đã có phiếu sửa chữa");
        db.tx(() -> {
            db.update("DELETE FROM warranty WHERE id = ?", id);
            audit.log(u, "XÓA", "warranty", id, w.str("code") + " – " + w.str("serial"));
            return null;
        });
    }

    /** Tra cứu cho nhân viên: khớp chính xác serial, SĐT, mã bảo hành hoặc mã phiếu. */
    public List<Row> lookup(User u, String q) throws SQLException {
        Auth.require(u);
        String key = q == null ? "" : q.trim().replaceAll("\\s", "");
        List<Row> out = new ArrayList<>();
        if (key.isEmpty()) return out;
        for (Row w : db.query("SELECT id FROM v_warranty WHERE serial = ? OR customer_phone = ? OR code = ? COLLATE NOCASE "
                + "OR id IN (SELECT warranty_id FROM ticket WHERE code = ? COLLATE NOCASE OR invoice_no = ? COLLATE NOCASE) "
                + "ORDER BY created_at DESC", key, key, key, key, key)) {
            out.add(getWarranty(u, w.lng("id")));
        }
        return out;
    }

    /** Thông tin nhanh theo serial (dùng khi tiếp nhận máy). */
    public Row bySerial(User u, String serial) throws SQLException {
        Auth.require(u);
        Row w = db.one("SELECT id FROM warranty WHERE serial = ?", serial == null ? "" : serial.trim());
        return w == null ? null : getWarranty(u, w.lng("id"));
    }

    // =====================================================================
    //  PHIẾU BẢO HÀNH
    // =====================================================================

    private Row ticketView(Row t) {
        TicketStatus s = TicketStatus.valueOf(t.str("status"));
        t.put("status_label", s.getLabel());
        LocalDate due = t.date("due_date");
        t.put("overdue", s.countsOverdue() && due != null && today().isAfter(due));
        t.put("quote_label", QUOTE_LABELS.get(t.str("quote_status")));
        t.put("in_warranty", t.bool("in_warranty"));
        String pm = t.str("payment_method");
        t.put("payment_label", pm.isEmpty() ? "" : PAY_METHODS.getOrDefault(pm, pm));
        return t;
    }

    /**
     * Danh sách phiếu.
     * status: "" | OPEN | OVERDUE | MINE (phiếu được giao cho tôi, chưa đóng) | QUOTE (chờ khách duyệt) | tên trạng thái
     */
    public List<Row> listTickets(User u, String status, String q) throws SQLException {
        Auth.require(u);
        String st = status == null ? "" : status;
        String where;
        List<Object> params = new ArrayList<>();
        switch (st) {
            case "" -> where = "";
            case "OPEN" -> where = "WHERE t.status NOT IN " + TicketStatus.CLOSED_SQL;
            case "OVERDUE" -> { where = "WHERE t.status NOT IN " + TicketStatus.NOT_OVERDUE_SQL + " AND t.due_date < ?"; params.add(today()); }
            case "MINE" -> { where = "WHERE t.technician_id = ? AND t.status NOT IN " + TicketStatus.CLOSED_SQL; params.add(u.id()); }
            case "QUOTE" -> where = "WHERE t.quote_status = 'PENDING' AND t.status NOT IN " + TicketStatus.CLOSED_SQL;
            default -> {
                try { TicketStatus.valueOf(st); } catch (IllegalArgumentException e) { throw new AppException("Bộ lọc không hợp lệ"); }
                where = "WHERE t.status = ?";
                params.add(st);
            }
        }
        String key = norm(q);
        List<Row> out = new ArrayList<>();
        for (Row t : db.query(TICKET_SQL + where + " ORDER BY t.received_at DESC, t.id DESC", params.toArray())) {
            if (matches(key, t, "code", "serial", "customer_phone", "customer_name", "product_name", "technician_name", "invoice_no"))
                out.add(ticketView(t));
        }
        return out;
    }

    private Row ticketRow(long id) throws SQLException {
        Row t = db.one(TICKET_SQL + "WHERE t.id = ?", id);
        if (t == null) throw AppException.notFound("phiếu #" + id);
        return t;
    }

    /** Chi tiết phiếu + lịch sử + linh kiện + các thao tác người dùng hiện tại được phép làm. */
    public Row getTicket(User u, long id) throws SQLException {
        Auth.require(u);
        Row t = ticketView(ticketRow(id));
        t.put("history", db.query("SELECT h.time, h.status, h.note, COALESCE(us.full_name, '') AS user_name "
                + "FROM ticket_history h LEFT JOIN app_user us ON us.id = h.user_id WHERE h.ticket_id = ? ORDER BY h.time, h.id", id));
        for (Object o : (List<?>) t.get("history")) {
            Row h = (Row) o;
            h.put("status_label", TicketStatus.valueOf(h.str("status")).getLabel());
        }
        t.put("parts", db.query("SELECT tp.id, tp.part_id, pt.code, pt.name, pt.unit, tp.qty, tp.unit_price, tp.charged, "
                + "tp.qty * tp.unit_price AS amount FROM ticket_part tp JOIN part pt ON pt.id = tp.part_id "
                + "WHERE tp.ticket_id = ? ORDER BY tp.id", id));
        t.put("warranty", warrantyView(db.one("SELECT * FROM v_warranty WHERE id = ?", t.lng("warranty_id")), today()));
        t.put("actions", actions(u, t));
        t.put("review", db.one("SELECT stars, comment, created_at, updated_at FROM ticket_review WHERE ticket_id = ?", id));
        return t;
    }

    /** Những gì người dùng được làm với phiếu (giao diện dựa vào đây để hiện/ẩn nút). */
    private Map<String, Object> actions(User u, Row t) {
        TicketStatus s = TicketStatus.valueOf(t.str("status"));
        boolean office = u.is(Role.ADMIN, Role.RECEPTION);
        boolean tech = u.is(Role.ADMIN, Role.TECHNICIAN);
        List<Object> next = new ArrayList<>();
        for (TicketStatus n : s.next())
            if (n != TicketStatus.RETURNED && n != TicketStatus.CANCELLED && n.allowedFor(u.role())) next.add(n.name());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("next", next);
        a.put("edit_cost", tech && s.allowsCostEdit());
        a.put("send_quote", tech && s.allowsCostEdit() && !t.bool("in_warranty")
                && List.of("NONE", "DECLINED").contains(t.str("quote_status")) && t.lng("total") > 0);
        a.put("decide_quote", office && "PENDING".equals(t.str("quote_status")) && !s.isClosed());
        a.put("assign", office && !s.isClosed());
        a.put("pay", office && (s == TicketStatus.READY || s == TicketStatus.REJECTED));
        a.put("cancel", office && s.canMoveTo(TicketStatus.CANCELLED));
        a.put("photos", !s.isClosed());
        a.put("print", true);
        return a;
    }

    public Row createTicket(User u, Map<String, Object> in) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        String serial = Json.str(in, "serial").trim();
        String issue = Json.str(in, "issue");
        if (issue.isEmpty()) throw new AppException("Vui lòng mô tả lỗi của máy");
        LocalDate due = parseDate(Json.str(in, "due_date"), today().plusDays(7), "Ngày hẹn trả");
        if (due.isBefore(today())) throw new AppException("Ngày hẹn trả không được trước hôm nay");
        long techId = Json.num(in, "technician_id", 0);

        long id = db.tx(() -> {
            Row w = db.one("SELECT * FROM warranty WHERE serial = ?", serial);
            if (w == null) throw new AppException("Serial \"" + serial + "\" chưa được kích hoạt bảo hành tại cửa hàng");
            Row open = db.one("SELECT code FROM ticket WHERE warranty_id = ? AND status NOT IN " + TicketStatus.CLOSED_SQL, w.lng("id"));
            if (open != null) throw new AppException("Máy này đang có phiếu chưa đóng: " + open.str("code"));
            if (techId > 0) checkTechnician(techId);
            boolean inW = !today().isAfter(w.date("expiry_date"));
            String now = Database.now();
            long newId = db.insert("INSERT INTO ticket (warranty_id, issue, accessories, condition_note, in_warranty, received_at, "
                    + "received_by, due_date, status, technician_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?)",
                    w.lng("id"), issue, emptyToNull(Json.str(in, "accessories")), emptyToNull(Json.str(in, "condition")),
                    inW, now, u.id(), due, techId > 0 ? techId : null);
            String code = String.format("PBH-%06d", newId);
            db.update("UPDATE ticket SET code = ? WHERE id = ?", code, newId);
            history(newId, TicketStatus.RECEIVED, inW
                    ? "Tiếp nhận máy – còn hạn bảo hành đến " + w.str("expiry_date")
                    : "Tiếp nhận máy – ĐÃ HẾT HẠN bảo hành (" + w.str("expiry_date") + "), sửa có tính phí, cần báo giá", u);
            audit.log(u, "TIẾP NHẬN MÁY", "ticket", newId, code + " – " + serial);
            return newId;
        });
        notifier.onTicket("RECEIVED", id);
        return getTicket(u, id);
    }

    private void checkTechnician(long techId) throws SQLException {
        Row r = db.one("SELECT role, active FROM app_user WHERE id = ?", techId);
        if (r == null || !r.bool("active") || !"TECHNICIAN".equals(r.str("role")))
            throw new AppException("Người được phân công phải là kỹ thuật viên đang hoạt động");
    }

    private void history(long ticketId, TicketStatus s, String note, User u) throws SQLException {
        db.update("INSERT INTO ticket_history (ticket_id, time, status, note, user_id) VALUES (?, ?, ?, ?, ?)",
                ticketId, Database.now(), s.name(), note, u == null ? null : u.id());
    }

    /** Chuyển trạng thái. Đầu vào: status, note, service_center_id, diagnosis, due_date. */
    public Row changeStatus(User u, long id, Map<String, Object> in) throws SQLException {
        Auth.require(u);
        TicketStatus target;
        try { target = TicketStatus.valueOf(Json.str(in, "status")); }
        catch (IllegalArgumentException e) { throw new AppException("Trạng thái không hợp lệ"); }
        if (target == TicketStatus.RETURNED) throw new AppException("Dùng chức năng \"Thu tiền & trả máy\" để trả máy cho khách");
        if (!target.allowedFor(u.role())) throw AppException.forbidden();
        String note = Json.str(in, "note");

        db.tx(() -> {
            Row t = ticketRow(id);
            TicketStatus cur = TicketStatus.valueOf(t.str("status"));
            if (!cur.canMoveTo(target))
                throw new AppException("Không thể chuyển từ \"" + cur.getLabel() + "\" sang \"" + target.getLabel() + "\"");
            if ((target == TicketStatus.REJECTED || target == TicketStatus.CANCELLED) && note.isEmpty())
                throw new AppException("Vui lòng ghi lý do khi chuyển sang \"" + target.getLabel() + "\"");
            boolean inW = t.bool("in_warranty");
            if (!inW && (target == TicketStatus.REPAIRING || target == TicketStatus.SENT_TO_VENDOR)
                    && !"APPROVED".equals(t.str("quote_status")))
                throw new AppException("Máy hết hạn bảo hành: cần gửi báo giá và khách đồng ý trước khi sửa");

            List<String> sets = new ArrayList<>();
            List<Object> vals = new ArrayList<>();
            sets.add("status = ?"); vals.add(target.name());
            if (target == TicketStatus.SENT_TO_VENDOR) {
                long sc = Json.num(in, "service_center_id", 0);
                if (sc <= 0 || db.scalar("SELECT COUNT(*) FROM service_center WHERE id = ?", sc) == 0)
                    throw new AppException("Vui lòng chọn trung tâm bảo hành hãng để gửi máy");
                sets.add("service_center_id = ?"); vals.add(sc);
            }
            String diag = Json.str(in, "diagnosis");
            if (!diag.isEmpty()) { sets.add("diagnosis = ?"); vals.add(diag); }
            String d = Json.str(in, "due_date");
            if (!d.isEmpty()) { sets.add("due_date = ?"); vals.add(parseDate(d, null, "Ngày hẹn trả")); }
            if (target == TicketStatus.DIAGNOSING && t.isNull("technician_id") && u.is(Role.TECHNICIAN)) {
                sets.add("technician_id = ?"); vals.add(u.id());        // KTV nhận việc
            }
            vals.add(id);
            db.update("UPDATE ticket SET " + String.join(", ", sets) + " WHERE id = ?", vals.toArray());

            if (target == TicketStatus.REJECTED) returnAllParts(u, id, t.str("code"));
            history(id, target, note, u);
            audit.log(u, "ĐỔI TRẠNG THÁI", "ticket", id, t.str("code") + ": " + cur.getLabel() + " → " + target.getLabel());
            return null;
        });
        if (target == TicketStatus.READY) notifier.onTicket("READY", id);
        return getTicket(u, id);
    }

    /** Hoàn trả toàn bộ linh kiện của phiếu về kho (khi từ chối sửa). */
    private void returnAllParts(User u, long ticketId, String code) throws SQLException {
        for (Row p : db.query("SELECT * FROM ticket_part WHERE ticket_id = ?", ticketId)) {
            db.update("UPDATE part SET stock = stock + ? WHERE id = ?", p.lng("qty"), p.lng("part_id"));
            db.update("INSERT INTO stock_movement (part_id, time, qty, type, ticket_id, user_id, note) VALUES (?, ?, ?, 'RETURN', ?, ?, ?)",
                    p.lng("part_id"), Database.now(), p.lng("qty"), ticketId, u.id(), "Hoàn kho – " + code + " không sửa");
        }
        db.update("DELETE FROM ticket_part WHERE ticket_id = ?", ticketId);
    }

    private Row requireCostEditable(User u, long id) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.TECHNICIAN);
        Row t = ticketRow(id);
        if (!TicketStatus.valueOf(t.str("status")).allowsCostEdit())
            throw new AppException("Chỉ cập nhật chi phí khi phiếu đang kiểm tra / sửa chữa / chờ linh kiện");
        return t;
    }

    /** Nếu báo giá đã được duyệt mà chi phí thay đổi thì phải báo giá lại. */
    private void resetQuoteIfChanged(User u, Row t, String reason) throws SQLException {
        if ("APPROVED".equals(t.str("quote_status")) || "DECLINED".equals(t.str("quote_status"))) {
            db.update("UPDATE ticket SET quote_status = 'NONE' WHERE id = ?", t.lng("id"));
            history(t.lng("id"), TicketStatus.valueOf(t.str("status")), "Chi phí thay đổi (" + reason + ") – cần báo giá lại", u);
        }
    }

    /** Cập nhật kết luận kỹ thuật và tiền công. */
    public Row updateCost(User u, long id, Map<String, Object> in) throws SQLException {
        db.tx(() -> {
            Row t = requireCostEditable(u, id);
            long labor = Json.num(in, "labor_cost", t.lng("labor_cost"));
            if (labor < 0) throw new AppException("Tiền công không hợp lệ");
            if (t.bool("in_warranty") && labor > 0) throw new AppException("Máy trong bảo hành: tiền công phải bằng 0");
            String diag = in.containsKey("diagnosis") ? Json.str(in, "diagnosis") : t.str("diagnosis");
            db.update("UPDATE ticket SET labor_cost = ?, diagnosis = ? WHERE id = ?", labor, emptyToNull(diag), id);
            if (labor != t.lng("labor_cost")) resetQuoteIfChanged(u, t, "tiền công");
            audit.log(u, "CẬP NHẬT CHI PHÍ", "ticket", id, t.str("code") + ": tiền công " + labor);
            return null;
        });
        return getTicket(u, id);
    }

    /** Dùng linh kiện cho phiếu: trừ tồn kho. Máy trong bảo hành thì linh kiện miễn phí. */
    public Row addPart(User u, long id, long partId, long qty) throws SQLException {
        if (qty <= 0 || qty > 1000) throw new AppException("Số lượng không hợp lệ");
        db.tx(() -> {
            Row t = requireCostEditable(u, id);
            Row p = db.one("SELECT * FROM part WHERE id = ?", partId);
            if (p == null) throw new AppException("Vui lòng chọn linh kiện");
            if (p.lng("stock") < qty)
                throw new AppException("Không đủ tồn kho: " + p.str("name") + " chỉ còn " + p.lng("stock") + " " + p.str("unit"));
            boolean charged = !t.bool("in_warranty");
            db.update("UPDATE part SET stock = stock - ? WHERE id = ?", qty, partId);
            db.update("INSERT INTO ticket_part (ticket_id, part_id, qty, unit_price, charged) VALUES (?, ?, ?, ?, ?)",
                    id, partId, qty, p.lng("sell_price"), charged);
            db.update("INSERT INTO stock_movement (part_id, time, qty, type, ticket_id, user_id, note) VALUES (?, ?, ?, 'OUT', ?, ?, ?)",
                    partId, Database.now(), -qty, id, u.id(), "Xuất cho " + t.str("code"));
            history(id, TicketStatus.valueOf(t.str("status")), "Dùng linh kiện: " + p.str("name") + " × " + qty
                    + (charged ? "" : " (miễn phí – trong bảo hành)"), u);
            if (charged) resetQuoteIfChanged(u, t, "thêm linh kiện");
            audit.log(u, "XUẤT LINH KIỆN", "ticket", id, t.str("code") + ": " + p.str("code") + " × " + qty);
            return null;
        });
        return getTicket(u, id);
    }

    /** Bỏ linh kiện khỏi phiếu: hoàn lại kho. */
    public Row removePart(User u, long id, long ticketPartId) throws SQLException {
        db.tx(() -> {
            Row t = requireCostEditable(u, id);
            Row tp = db.one("SELECT tp.*, pt.name FROM ticket_part tp JOIN part pt ON pt.id = tp.part_id "
                    + "WHERE tp.id = ? AND tp.ticket_id = ?", ticketPartId, id);
            if (tp == null) throw AppException.notFound("linh kiện trên phiếu");
            db.update("DELETE FROM ticket_part WHERE id = ?", ticketPartId);
            db.update("UPDATE part SET stock = stock + ? WHERE id = ?", tp.lng("qty"), tp.lng("part_id"));
            db.update("INSERT INTO stock_movement (part_id, time, qty, type, ticket_id, user_id, note) VALUES (?, ?, ?, 'RETURN', ?, ?, ?)",
                    tp.lng("part_id"), Database.now(), tp.lng("qty"), id, u.id(), "Hoàn kho từ " + t.str("code"));
            history(id, TicketStatus.valueOf(t.str("status")), "Bỏ linh kiện: " + tp.str("name") + " × " + tp.lng("qty"), u);
            if (tp.bool("charged")) resetQuoteIfChanged(u, t, "bỏ linh kiện");
            return null;
        });
        return getTicket(u, id);
    }

    /** Kỹ thuật viên gửi báo giá cho khách (máy hết hạn bảo hành). */
    public Row sendQuote(User u, long id) throws SQLException {
        db.tx(() -> {
            Row t = requireCostEditable(u, id);
            if (t.bool("in_warranty")) throw new AppException("Máy còn bảo hành, không cần báo giá");
            if (t.lng("total") <= 0) throw new AppException("Chưa có chi phí để báo giá (thêm tiền công hoặc linh kiện)");
            if ("PENDING".equals(t.str("quote_status"))) throw new AppException("Đã gửi báo giá, đang chờ khách duyệt");
            if ("APPROVED".equals(t.str("quote_status"))) throw new AppException("Khách đã đồng ý báo giá này");
            db.update("UPDATE ticket SET quote_status = 'PENDING' WHERE id = ?", id);
            history(id, TicketStatus.valueOf(t.str("status")), "Gửi báo giá cho khách: " + money(t.lng("total")), u);
            audit.log(u, "GỬI BÁO GIÁ", "ticket", id, t.str("code") + ": " + t.lng("total"));
            return null;
        });
        notifier.onTicket("QUOTE", id);
        return getTicket(u, id);
    }

    /** Nhân viên ghi nhận khách đồng ý / từ chối báo giá. */
    public Row decideQuote(User u, long id, boolean approve, String note) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        applyQuoteDecision(u, id, approve, note, "");
        return getTicket(u, id);
    }

    /** Khách tự duyệt báo giá trên cổng khách hàng (đã kiểm tra quyền sở hữu ở PortalService). */
    void decideQuoteOnline(long id, boolean approve, String note) throws SQLException {
        applyQuoteDecision(null, id, approve, note, " (khách duyệt online)");
    }

    private void applyQuoteDecision(User u, long id, boolean approve, String note, String source) throws SQLException {
        db.tx(() -> {
            Row t = ticketRow(id);
            if (!"PENDING".equals(t.str("quote_status"))) throw new AppException("Phiếu không có báo giá đang chờ duyệt");
            if (TicketStatus.valueOf(t.str("status")).isClosed()) throw new AppException("Phiếu đã đóng");
            db.update("UPDATE ticket SET quote_status = ? WHERE id = ?", approve ? "APPROVED" : "DECLINED", id);
            String n = (approve ? "Khách ĐỒNG Ý báo giá " : "Khách TỪ CHỐI báo giá ") + money(t.lng("total")) + source
                    + (note == null || note.isBlank() ? "" : " – " + note.trim());
            history(id, TicketStatus.valueOf(t.str("status")), n, u);
            audit.log(u, approve ? "DUYỆT BÁO GIÁ" : "TỪ CHỐI BÁO GIÁ", "ticket", id, t.str("code") + source);
            return null;
        });
    }

    /** Phân công kỹ thuật viên. */
    public Row assign(User u, long id, long technicianId) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        db.tx(() -> {
            Row t = ticketRow(id);
            if (TicketStatus.valueOf(t.str("status")).isClosed()) throw new AppException("Phiếu đã đóng");
            checkTechnician(technicianId);
            db.update("UPDATE ticket SET technician_id = ? WHERE id = ?", technicianId, id);
            String name = db.one("SELECT full_name FROM app_user WHERE id = ?", technicianId).str("full_name");
            history(id, TicketStatus.valueOf(t.str("status")), "Phân công kỹ thuật viên: " + name, u);
            audit.log(u, "PHÂN CÔNG", "ticket", id, t.str("code") + " → " + name);
            return null;
        });
        return getTicket(u, id);
    }

    /** Thu tiền (nếu có) và trả máy cho khách. Đầu vào: method (CASH/TRANSFER/CARD), note. */
    public Row pay(User u, long id, Map<String, Object> in) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        db.tx(() -> {
            Row t = ticketRow(id);
            TicketStatus s = TicketStatus.valueOf(t.str("status"));
            if (s != TicketStatus.READY && s != TicketStatus.REJECTED)
                throw new AppException("Chỉ trả máy khi phiếu ở trạng thái \"Sửa xong\" hoặc \"Từ chối bảo hành\"");
            if ("PENDING".equals(t.str("quote_status")) && s == TicketStatus.READY)
                throw new AppException("Báo giá đang chờ khách duyệt");
            long due = t.lng("total");
            String method = Json.str(in, "method");
            String invoice = null;
            if (due > 0) {
                if (!PAY_METHODS.containsKey(method)) throw new AppException("Vui lòng chọn hình thức thanh toán");
                invoice = "HDSC" + today().format(YYMM) + "-" + String.format("%05d", id);
            } else {
                method = null;
            }
            String now = Database.now();
            db.update("UPDATE ticket SET status = 'RETURNED', paid_amount = ?, payment_method = ?, invoice_no = ?, returned_at = ? WHERE id = ?",
                    due, method, invoice, now, id);
            String note = Json.str(in, "note");
            history(id, TicketStatus.RETURNED, (due > 0 ? "Thu " + money(due) + " (" + PAY_METHODS.get(method) + "), hóa đơn " + invoice
                    : "Trả máy, không thu phí") + (note.isEmpty() ? "" : " – " + note), u);
            audit.log(u, "TRẢ MÁY", "ticket", id, t.str("code") + (due > 0 ? ": thu " + due : ""));
            return null;
        });
        return getTicket(u, id);
    }

    // =====================================================================
    //  GÓI BẢO HÀNH MỞ RỘNG
    // =====================================================================

    /**
     * Bán gói bảo hành mở rộng: cộng thêm số tháng vào hạn hiện tại.
     * Chỉ áp dụng cho máy còn hạn; tổng thời hạn tối đa 120 tháng.
     * Đầu vào: months, price, method (khi price &gt; 0), note.
     */
    public Row extend(User u, long warrantyId, Map<String, Object> in) throws SQLException {
        Auth.require(u, Role.ADMIN, Role.RECEPTION);
        int months = (int) Json.num(in, "months", 0);
        long price = Json.num(in, "price", -1);
        String method = Json.str(in, "method");
        if (months < 1 || months > 36) throw new AppException("Số tháng gia hạn phải từ 1 đến 36");
        if (price < 0) throw new AppException("Giá gói không hợp lệ");
        if (price > 0 && !PAY_METHODS.containsKey(method)) throw new AppException("Vui lòng chọn hình thức thanh toán");
        db.tx(() -> {
            Row w = db.one("SELECT * FROM warranty WHERE id = ?", warrantyId);
            if (w == null) throw AppException.notFound("bảo hành #" + warrantyId);
            LocalDate exp = w.date("expiry_date");
            if (today().isAfter(exp)) throw new AppException("Bảo hành đã hết hạn, không thể mua gói mở rộng");
            if (w.lng("months") + months > 120) throw new AppException("Tổng thời hạn bảo hành tối đa 120 tháng");
            LocalDate newExp = exp.plusMonths(months);
            long id = db.insert("INSERT INTO warranty_extension (warranty_id, months, price, payment_method, old_expiry, new_expiry, note, "
                    + "created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", warrantyId, months, price,
                    price > 0 ? method : null, exp, newExp, emptyToNull(Json.str(in, "note")), u.id(), Database.now());
            String code = "GBH" + today().format(YYMM) + "-" + String.format("%04d", id);
            db.update("UPDATE warranty_extension SET code = ? WHERE id = ?", code, id);
            db.update("UPDATE warranty SET months = months + ?, expiry_date = ? WHERE id = ?", months, newExp, warrantyId);
            audit.log(u, "GIA HẠN BẢO HÀNH", "warranty", warrantyId, w.str("code") + ": +" + months + " tháng, " + money(price) + " (" + code + ")");
            return null;
        });
        return getWarranty(u, warrantyId);
    }

    // =====================================================================
    //  TỔNG QUAN
    // =====================================================================

    public Row dashboard(User u) throws SQLException {
        Auth.require(u);
        LocalDate t = today();
        Row m = new Row();
        m.put("today", t.toString());
        m.put("total_warranties", db.scalar("SELECT COUNT(*) FROM warranty"));
        m.put("active_warranties", db.scalar("SELECT COUNT(*) FROM warranty WHERE expiry_date >= ?", t));
        m.put("expiring_soon", db.scalar("SELECT COUNT(*) FROM warranty WHERE expiry_date BETWEEN ? AND ?", t, t.plusDays(30)));
        m.put("open_tickets", db.scalar("SELECT COUNT(*) FROM ticket WHERE status NOT IN " + TicketStatus.CLOSED_SQL));
        m.put("overdue_tickets", db.scalar("SELECT COUNT(*) FROM ticket WHERE status NOT IN " + TicketStatus.NOT_OVERDUE_SQL + " AND due_date < ?", t));
        m.put("ready_tickets", db.scalar("SELECT COUNT(*) FROM ticket WHERE status = 'READY'"));
        m.put("pending_quotes", db.scalar("SELECT COUNT(*) FROM ticket WHERE quote_status = 'PENDING' AND status NOT IN " + TicketStatus.CLOSED_SQL));
        m.put("my_tickets", db.scalar("SELECT COUNT(*) FROM ticket WHERE technician_id = ? AND status NOT IN " + TicketStatus.CLOSED_SQL, u.id()));
        m.put("low_stock", db.scalar("SELECT COUNT(*) FROM part WHERE stock <= min_stock"));
        m.put("new_requests", db.scalar("SELECT COUNT(*) FROM service_request WHERE status = 'NEW'"));
        m.put("notify_failed", db.scalar("SELECT COUNT(*) FROM notification WHERE status = 'FAILED'"));
        m.put("confirmed_requests", db.scalar("SELECT COUNT(*) FROM service_request WHERE status = 'CONFIRMED'"));
        String month = t.toString().substring(0, 7);
        m.put("month_revenue", db.scalar("SELECT COALESCE(SUM(paid_amount), 0) FROM ticket WHERE substr(returned_at, 1, 7) = ?", month)
                + db.scalar("SELECT COALESCE(SUM(price), 0) FROM warranty_extension WHERE substr(created_at, 1, 7) = ?", month));
        m.put("month_received", db.scalar("SELECT COUNT(*) FROM ticket WHERE substr(received_at, 1, 7) = ?", month));
        Row by = new Row();
        for (TicketStatus s : TicketStatus.values()) by.put(s.name(), 0L);
        for (Row r : db.query("SELECT status, COUNT(*) AS n FROM ticket GROUP BY status")) by.put(r.str("status"), r.lng("n"));
        m.put("by_status", by);
        // Việc cần làm: quá hẹn, chờ trả, chờ duyệt báo giá, mới nhận chưa kiểm tra
        List<Row> todo = new ArrayList<>();
        for (Row r : listTickets(u, "OPEN", "")) {
            boolean mine = u.is(Role.TECHNICIAN) ? r.lng("technician_id") == u.id() || r.isNull("technician_id") : true;
            String st = r.str("status");
            boolean need = Boolean.TRUE.equals(r.get("overdue")) || "READY".equals(st) || "REJECTED".equals(st)
                    || "RECEIVED".equals(st) || "PENDING".equals(r.str("quote_status"));
            if (mine && need) todo.add(r);
        }
        todo.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.get("overdue")), Boolean.TRUE.equals(a.get("overdue"))));
        m.put("todo", todo.size() > 12 ? todo.subList(0, 12) : todo);
        return m;
    }

    // ---------------- tiện ích ----------------

    static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static String money(long v) {
        return String.format(Locale.forLanguageTag("vi"), "%,d đ", v).replace(',', '.');
    }
}
