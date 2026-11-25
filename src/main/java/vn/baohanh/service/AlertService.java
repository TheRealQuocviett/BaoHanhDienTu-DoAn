package vn.baohanh.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Perm;
import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.model.User;
import vn.baohanh.notify.NotificationService;
import vn.baohanh.util.Fmt;

/**
 * Nhắc việc tự động (chạy khi mở chương trình và mỗi 30 phút):
 * <ul>
 *   <li>Phiếu sắp đến hạn trả (trong số ngày cài đặt, mặc định 1): nhắc kỹ thuật viên phụ trách (chưa phân công → nhắc quản lý).</li>
 *   <li>Phiếu đã quá hạn: báo quản lý + kỹ thuật viên phụ trách, đồng thời tự gửi tin xin lỗi khách (theo cấu hình thông báo).</li>
 *   <li>Linh kiện dự báo hết trước khi kịp đặt hàng về: báo người có quyền đặt hàng (mỗi tuần nhắc lại một lần nếu chưa xử lý).</li>
 * </ul>
 * Mỗi việc chỉ nhắc một lần cho mỗi người (dedup_key), đổi ngày hẹn thì được nhắc lại theo ngày mới.
 */
public class AlertService {
    private final Database db;
    private final SettingService settings;
    private NotificationService notify;
    private ScheduledExecutorService worker;

    public static final java.util.Map<String, String> KINDS = java.util.Map.of(
            "DUE_SOON", "Sắp đến hạn trả", "OVERDUE", "Quá hạn trả", "STOCK", "Linh kiện sắp hết");

    public AlertService(Database db, SettingService settings) {
        this.db = db;
        this.settings = settings;
    }

    public void setNotify(NotificationService n) { this.notify = n; }

    private int intSetting(String key, int def) {
        try {
            String v = settings.get(key);
            return v == null || v.isBlank() ? def : Math.max(0, Integer.parseInt(v.trim()));
        } catch (Exception e) {
            return def;
        }
    }

    private int add(long userId, String kind, Long ticketId, Long partId, String message, String dedup) throws SQLException {
        return db.update("INSERT OR IGNORE INTO staff_alert (user_id, kind, ticket_id, part_id, message, created_at, dedup_key) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, kind, ticketId, partId, message, Database.now(), dedup + ":U" + userId);
    }

    private List<Long> users(String where) throws SQLException {
        return db.query("SELECT id FROM app_user WHERE active = 1 AND " + where).stream().map(r -> r.lng("id")).toList();
    }

    /** Quét và tạo nhắc việc. Trả về số nhắc việc mới. */
    public synchronized int scan() throws SQLException {
        int n = 0;
        LocalDate today = LocalDate.now();
        int before = intSetting("remind_due_days", 1);
        List<Long> admins = users("role = 'ADMIN'");
        String open = "t.status NOT IN " + TicketStatus.NOT_OVERDUE_SQL;
        String sel = "SELECT t.id, t.code, t.due_date, t.technician_id, t.status, p.name AS product, cu.name AS customer FROM ticket t "
                + "JOIN warranty w ON w.id = t.warranty_id JOIN product p ON p.id = w.product_id JOIN customer cu ON cu.id = w.customer_id WHERE " + open;

        // 1. Sắp đến hạn
        for (Row t : db.query(sel + " AND t.due_date >= ? AND t.due_date <= ?", today.toString(), today.plusDays(before).toString())) {
            String when = today.toString().equals(t.str("due_date")) ? "HÔM NAY" : "ngày " + Fmt.date(t.str("due_date"));
            String msg = "Phiếu " + t.str("code") + " (" + t.str("product") + " – " + t.str("customer") + ") hẹn trả " + when + ", trạng thái: "
                    + TicketStatus.valueOf(t.str("status")).getLabel() + ".";
            String key = "DUE:T" + t.lng("id") + ":" + t.str("due_date");
            if (t.get("technician_id") != null) n += add(t.lng("technician_id"), "DUE_SOON", t.lng("id"), null, msg, key);
            else for (long a : admins) n += add(a, "DUE_SOON", t.lng("id"), null, msg + " Chưa phân công kỹ thuật viên!", key);
        }

        // 2. Quá hạn: báo quản lý + KTV, gửi tin xin lỗi khách
        for (Row t : db.query(sel + " AND t.due_date < ?", today.toString())) {
            long late = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(t.str("due_date")), today);
            String msg = "Phiếu " + t.str("code") + " (" + t.str("product") + " – " + t.str("customer") + ") đã QUÁ HẠN " + late + " ngày (hẹn "
                    + Fmt.date(t.str("due_date")) + "), trạng thái: " + TicketStatus.valueOf(t.str("status")).getLabel() + ".";
            String key = "OVERDUE:T" + t.lng("id") + ":" + t.str("due_date");
            int fresh = 0;
            for (long a : admins) fresh += add(a, "OVERDUE", t.lng("id"), null, msg, key);
            if (t.get("technician_id") != null) fresh += add(t.lng("technician_id"), "OVERDUE", t.lng("id"), null, msg, key);
            n += fresh;
            if (notify != null) notify.onOverdue(t.lng("id"), t.str("due_date"));   // tự chống gửi trùng theo ngày hẹn
        }

        // 3. Linh kiện dự báo sắp hết
        int lead = intSetting("po_lead_days", PurchaseService.DEFAULT_LEAD_DAYS), cover = intSetting("po_cover_days", PurchaseService.DEFAULT_COVER_DAYS);
        String week = today.getYear() + "W" + today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        List<Long> buyers = db.query("SELECT id, username, full_name, role FROM app_user WHERE active = 1").stream()
                .filter(r -> Perm.PURCHASE.allows(new User(r.lng("id"), r.str("username"), r.str("full_name"), Role.valueOf(r.str("role")))))
                .map(r -> r.lng("id")).toList();
        for (Row p : PurchaseService.plan(db, lead, cover)) {
            if (!Boolean.TRUE.equals(p.get("need")) || p.lng("suggest_qty") <= 0) continue;
            String msg = p.str("name") + " (" + p.str("code") + "): " + p.str("reason") + ". Tồn " + p.lng("stock") + " " + p.str("unit")
                    + ", nên đặt thêm " + p.lng("suggest_qty") + ".";
            for (long b : buyers) n += add(b, "STOCK", null, p.lng("id"), msg, "STOCK:P" + p.lng("id") + ":" + week);
        }
        return n;
    }

    public Row list(User u, boolean unreadOnly) throws SQLException {
        Auth.require(u);
        List<Row> rows = db.query("SELECT a.*, t.code AS ticket_code FROM staff_alert a LEFT JOIN ticket t ON t.id = a.ticket_id "
                + "WHERE a.user_id = ?" + (unreadOnly ? " AND a.read_at IS NULL" : "") + " ORDER BY a.read_at IS NOT NULL, a.id DESC LIMIT 100", u.id());
        for (Row r : rows) r.put("kind_label", KINDS.get(r.str("kind")));
        return new Row().with("rows", rows).with("unread", unread(u));
    }

    public long unread(User u) throws SQLException {
        return db.scalar("SELECT COUNT(*) FROM staff_alert WHERE user_id = ? AND read_at IS NULL", u.id());
    }

    public void markRead(User u, long id) throws SQLException {
        Auth.require(u);
        if (id > 0) db.update("UPDATE staff_alert SET read_at = ? WHERE id = ? AND user_id = ? AND read_at IS NULL", Database.now(), id, u.id());
        else db.update("UPDATE staff_alert SET read_at = ? WHERE user_id = ? AND read_at IS NULL", Database.now(), u.id());
    }

    /** Phiếu đã xong / trả / hủy thì nhắc việc liên quan tự coi như đã đọc. */
    public void closeFinished() throws SQLException {
        db.update("UPDATE staff_alert SET read_at = ? WHERE read_at IS NULL AND ticket_id IN (SELECT id FROM ticket t WHERE t.status IN "
                + TicketStatus.NOT_OVERDUE_SQL + ")", Database.now());
    }

    public void start() {
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nhac-viec");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(() -> {
            try { closeFinished(); scan(); } catch (Exception e) { System.err.println("Nhac viec loi: " + e.getMessage()); }
        }, 10, 30 * 60, TimeUnit.SECONDS);
    }
}
