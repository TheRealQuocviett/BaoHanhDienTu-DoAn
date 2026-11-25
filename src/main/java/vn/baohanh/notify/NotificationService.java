package vn.baohanh.notify;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.User;
import vn.baohanh.service.AppException;
import vn.baohanh.service.AuditService;
import vn.baohanh.service.Auth;
import vn.baohanh.service.Notifier;
import vn.baohanh.service.SettingService;
import vn.baohanh.util.Fmt;
import vn.baohanh.util.Json;

/**
 * Thông báo cho khách qua Email, SMS (eSMS.vn) và Zalo ZNS.
 *
 * - Sự kiện: đã tiếp nhận máy, có báo giá, sửa xong, hẹn lịch / từ chối yêu cầu online, sắp hết hạn bảo hành.
 * - Mỗi tin được ghi vào bảng notification (hàng đợi + nhật ký) rồi một luồng nền gửi đi;
 *   lỗi thì tự thử lại tối đa 4 lần (sau 2, 4, 8 phút…), không làm chậm thao tác của nhân viên.
 * - Chống gửi trùng bằng dedup_key; SMS / Zalo chỉ gửi trong 6h–22h.
 * - Tin nhắc hết hạn (mang tính quảng cáo) mặc định tắt và tôn trọng lựa chọn "không nhận" của khách.
 */
public class NotificationService implements Notifier {

    /** Sự kiện gửi thông báo. */
    public enum Event {
        RECEIVED("Đã tiếp nhận máy", true, "[{cua_hang}] Đã tiếp nhận máy – phiếu {ma_phieu}",
                "{cua_hang} đã tiếp nhận {san_pham} của quý khách {ten_khach}. Mã phiếu {ma_phieu}, hẹn trả {ngay_hen}. Tra cứu tiến độ: {link}"),
        QUOTE("Có báo giá sửa chữa", true, "[{cua_hang}] Báo giá sửa chữa phiếu {ma_phieu}",
                "{cua_hang}: Báo giá sửa chữa {san_pham} (phiếu {ma_phieu}) là {so_tien}. Quý khách vui lòng xác nhận tại {link} hoặc gọi {sdt_cua_hang}."),
        READY("Sửa xong – mời nhận máy", true, "[{cua_hang}] Máy đã sửa xong – phiếu {ma_phieu}",
                "{cua_hang}: {san_pham} của quý khách (phiếu {ma_phieu}) đã sửa xong. Chi phí: {so_tien}. Mời quý khách đến nhận máy. Liên hệ {sdt_cua_hang}."),
        REQUEST_CONFIRMED("Hẹn lịch yêu cầu online", true, "[{cua_hang}] Xác nhận lịch bảo hành {ma_yeu_cau}",
                "{cua_hang}: Đã xác nhận yêu cầu bảo hành {ma_yeu_cau}. Mời quý khách mang {san_pham} đến cửa hàng ngày {ngay_hen}. {loi_nhan}"),
        REQUEST_REJECTED("Từ chối yêu cầu online", true, "[{cua_hang}] Yêu cầu bảo hành {ma_yeu_cau}",
                "{cua_hang}: Rất tiếc, yêu cầu bảo hành {ma_yeu_cau} chưa được tiếp nhận. Lý do: {loi_nhan}. Liên hệ {sdt_cua_hang}."),
        MESSAGE("Cửa hàng trả lời tin nhắn", true, "[{cua_hang}] Phản hồi về phiếu {ma_phieu}",
                "{cua_hang} trả lời về phiếu {ma_phieu} ({san_pham}): \"{loi_nhan}\". Xem và nhắn lại tại {link}"),
        EXPIRING("Sắp hết hạn bảo hành (30 ngày)", false, "[{cua_hang}] Bảo hành {san_pham} sắp hết hạn",
                "{cua_hang}: Bảo hành {san_pham} của quý khách sẽ hết hạn ngày {han_bh}. Quý khách có thể mua gói bảo hành mở rộng tại cửa hàng. Liên hệ {sdt_cua_hang}."),
        OVERDUE("Xin lỗi trễ hẹn", true, "[{cua_hang}] Xin lỗi về việc trễ hẹn – phiếu {ma_phieu}",
                "{cua_hang} xin lỗi quý khách {ten_khach}: {san_pham} (phiếu {ma_phieu}) chưa kịp sửa xong đúng hẹn {ngay_hen}. "
                + "Cửa hàng đang ưu tiên xử lý và sẽ báo ngay khi máy sửa xong. Tra cứu tiến độ: {link}. Liên hệ {sdt_cua_hang}."),
        TEST("Tin thử", true, "[{cua_hang}] Tin thử từ hệ thống bảo hành",
                "Đây là tin thử từ hệ thống quản lý bảo hành của {cua_hang}. Nếu bạn nhận được tin này thì cấu hình đã đúng.");

        public final String label;
        public final boolean defaultOn;
        public final String subject;
        public final String template;

        Event(String label, boolean defaultOn, String subject, String template) {
            this.label = label;
            this.defaultOn = defaultOn;
            this.subject = subject;
            this.template = template;
        }
    }

    /** Một trường cấu hình (để giao diện web và Swing tự dựng form). */
    public record Field(String key, String label, String type, String group, String def, String help) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key); m.put("label", label); m.put("type", type); m.put("group", group);
            if (help != null) m.put("help", help);
            return m;
        }
    }

    public static final String MASK = "••••••";
    public static final List<Field> FIELDS = new ArrayList<>();
    public static final Map<String, String> CHANNEL_LABELS = Map.of("EMAIL", "Email", "SMS", "SMS", "ZALO", "Zalo");
    public static final Map<String, String> STATUS_LABELS = Map.of("PENDING", "Đang chờ gửi", "SENT", "Đã gửi", "FAILED", "Gửi lỗi");

    static {
        String g = "Chung";
        FIELDS.add(new Field("ntf_enabled", "Bật gửi thông báo cho khách", "BOOL", g, "false", null));
        g = "Email (SMTP)";
        FIELDS.add(new Field("ntf_email_on", "Gửi qua email", "BOOL", g, "false", "Chỉ gửi cho khách có email trong hồ sơ"));
        FIELDS.add(new Field("ntf_smtp_host", "Máy chủ SMTP", "TEXT", g, "smtp.gmail.com", null));
        FIELDS.add(new Field("ntf_smtp_port", "Cổng", "TEXT", g, "465", "465 (SSL) hoặc 587 (STARTTLS)"));
        FIELDS.add(new Field("ntf_smtp_security", "Bảo mật", "SELECT:SSL,STARTTLS,NONE", g, "SSL", null));
        FIELDS.add(new Field("ntf_smtp_user", "Tài khoản (email gửi)", "TEXT", g, "", null));
        FIELDS.add(new Field("ntf_smtp_password", "Mật khẩu", "PASSWORD", g, "", "Gmail: dùng Mật khẩu ứng dụng (App Password), không dùng mật khẩu đăng nhập"));
        FIELDS.add(new Field("ntf_smtp_from_name", "Tên người gửi", "TEXT", g, "", "Để trống = tên cửa hàng"));
        g = "SMS (eSMS.vn)";
        FIELDS.add(new Field("ntf_sms_on", "Gửi qua SMS", "BOOL", g, "false", "Cần tài khoản eSMS.vn và Brandname đã đăng ký"));
        FIELDS.add(new Field("ntf_esms_api_key", "ApiKey", "TEXT", g, "", null));
        FIELDS.add(new Field("ntf_esms_secret_key", "SecretKey", "PASSWORD", g, "", null));
        FIELDS.add(new Field("ntf_esms_brandname", "Brandname", "TEXT", g, "", "Nội dung tin phải khớp mẫu brandname đã đăng ký với eSMS"));
        FIELDS.add(new Field("ntf_sms_unicode", "Gửi SMS có dấu (Unicode)", "BOOL", g, "false", "Mặc định gửi không dấu: rẻ hơn, ít bị chặn"));
        g = "Zalo ZNS";
        FIELDS.add(new Field("ntf_zalo_on", "Gửi qua Zalo ZNS", "BOOL", g, "false", "Cần Zalo OA, mẫu tin đã được Zalo duyệt; chỉ gửi 6h–22h"));
        FIELDS.add(new Field("ntf_zalo_token", "Access token của OA", "PASSWORD", g, "", null));
        for (Event e : Event.values())
            if (e != Event.TEST) FIELDS.add(new Field("ntf_zalo_tpl_" + e.name(), "Mã mẫu Zalo – " + e.label, "TEXT", g, "",
                    e == Event.RECEIVED ? "Tham số mẫu: customer_name, product_name, ticket_code, amount, date, shop_name, shop_phone, note, link" : null));
        g = "Sự kiện & nội dung tin";
        for (Event e : Event.values()) {
            if (e == Event.TEST) continue;
            FIELDS.add(new Field("ntf_ev_" + e.name(), "Gửi khi: " + e.label, "BOOL", g, String.valueOf(e.defaultOn),
                    e == Event.EXPIRING ? "Tin mang tính quảng cáo – chỉ gửi cho khách không từ chối nhận" : null));
            FIELDS.add(new Field("ntf_tpl_" + e.name(), "Nội dung – " + e.label, "TEXTAREA", g, e.template,
                    e == Event.RECEIVED ? "Biến: {ten_khach} {san_pham} {serial} {ma_phieu} {ma_yeu_cau} {so_tien} {ngay_hen} {han_bh} {loi_nhan} {link} {cua_hang} {sdt_cua_hang}" : null));
        }
    }

    private final Database db;
    private final SettingService settings;
    private final AuditService audit;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService worker;

    public NotificationService(Database db, SettingService settings, AuditService audit) {
        this.db = db;
        this.settings = settings;
        this.audit = audit;
    }

    // ===================== Cấu hình =====================

    private Map<String, String> config() throws SQLException {
        Map<String, String> all = settings.all();
        Map<String, String> c = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            String v = all.get(f.key());
            boolean useDefault = v == null || (v.isBlank() && (f.type().equals("TEXTAREA") || f.type().equals("BOOL")));
            c.put(f.key(), useDefault ? f.def() : v);
        }
        return c;
    }

    private static boolean on(Map<String, String> c, String key) {
        return "true".equalsIgnoreCase(c.getOrDefault(key, "false"));
    }

    /** Cấu hình hiện tại cho giao diện (mật khẩu được che). */
    public Row getConfig(User u) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.NOTIFY_CONFIG);
        Row r = new Row();
        Map<String, String> c = config();
        for (Field f : FIELDS) {
            String v = c.get(f.key());
            r.put(f.key(), f.type().equals("PASSWORD") && v != null && !v.isEmpty() ? MASK : v);
        }
        return r;
    }

    public List<Object> fieldDefs() {
        List<Object> l = new ArrayList<>();
        for (Field f : FIELDS) l.add(f.toMap());
        return l;
    }

    public Row saveConfig(User u, Map<String, Object> in) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.NOTIFY_CONFIG);
        db.tx(() -> {
            for (Field f : FIELDS) {
                if (!in.containsKey(f.key())) continue;
                String v = Json.str(in, f.key());
                if (f.type().equals("PASSWORD") && MASK.equals(v)) continue;     // giữ mật khẩu cũ
                if (f.type().equals("BOOL")) v = String.valueOf("true".equalsIgnoreCase(v) || "1".equals(v) || "on".equals(v));
                if (f.key().equals("ntf_smtp_port") && !v.isEmpty() && !v.matches("\\d{1,5}")) throw new AppException("Cổng SMTP phải là số");
                if (f.type().equals("TEXTAREA") && v.length() > 600) throw new AppException("Nội dung tin quá dài (tối đa 600 ký tự)");
                settings.put(f.key(), v);
            }
            audit.log(u, "SỬA", "setting", null, "Cấu hình thông báo");
            return null;
        });
        return getConfig(u);
    }

    // ===================== Tạo tin =====================

    @Override
    public void onTicket(String event, long ticketId) {
        ticketEvent(event, ticketId, null, null);
    }

    @Override
    public void onMessage(long ticketId, long messageId, String body) {
        String b = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (b.length() > 160) b = b.substring(0, 157) + "…";
        ticketEvent("MESSAGE", ticketId, b, "MESSAGE:M" + messageId);
    }

    /** Phiếu quá hạn trả: gửi tin xin lỗi khách (mỗi ngày hẹn chỉ gửi một lần). */
    public void onOverdue(long ticketId, String dueDate) {
        ticketEvent("OVERDUE", ticketId, null, "OVERDUE:T" + ticketId + ":" + dueDate);
    }

    private void ticketEvent(String event, long ticketId, String note, String dedupKey) {
        try {
            Row t = db.one("SELECT t.id, t.code, t.due_date, t.warranty_id, cu.id AS customer_id, cu.name, cu.phone, cu.email, "
                    + "p.name AS product, w.serial, w.expiry_date, vc.total FROM ticket t JOIN warranty w ON w.id = t.warranty_id "
                    + "JOIN customer cu ON cu.id = w.customer_id JOIN product p ON p.id = w.product_id "
                    + "JOIN v_ticket_cost vc ON vc.ticket_id = t.id WHERE t.id = ?", ticketId);
            if (t == null) return;
            Map<String, String> v = baseVars(t);
            v.put("ma_phieu", t.str("code"));
            v.put("so_tien", Fmt.money(t.lng("total")));
            v.put("ngay_hen", Fmt.date(t.str("due_date")));
            if (note != null) v.put("loi_nhan", note);
            v.put("link", settings.portalBase() + "/tra-cuu#" + ("QUOTE".equals(event) || "MESSAGE".equals(event) ? "tai-khoan" : t.str("code")));
            String dedup = dedupKey != null ? dedupKey : event + ":T" + ticketId + ("QUOTE".equals(event) ? ":" + t.lng("total") : "");
            enqueue(Event.valueOf(event), v, t, dedup, ticketId, t.lng("warranty_id"), null);
        } catch (Exception e) {
            System.err.println("Khong tao duoc thong bao " + event + ": " + e.getMessage());
        }
    }

    @Override
    public void onRequest(String event, long requestId) {
        try {
            Row r = db.one("SELECT r.id, r.code, r.appointment, r.staff_note, r.warranty_id, cu.id AS customer_id, cu.name, cu.phone, cu.email, "
                    + "p.name AS product, w.serial, w.expiry_date FROM service_request r JOIN customer cu ON cu.id = r.customer_id "
                    + "JOIN warranty w ON w.id = r.warranty_id JOIN product p ON p.id = w.product_id WHERE r.id = ?", requestId);
            if (r == null) return;
            Map<String, String> v = baseVars(r);
            v.put("ma_yeu_cau", r.str("code"));
            v.put("ngay_hen", Fmt.date(r.str("appointment")));
            v.put("loi_nhan", r.str("staff_note"));
            v.put("link", settings.portalBase() + "/tra-cuu#tai-khoan");
            enqueue(Event.valueOf(event), v, r, event + ":R" + requestId + ":" + r.str("appointment"), null, r.lng("warranty_id"), requestId);
        } catch (Exception e) {
            System.err.println("Khong tao duoc thong bao " + event + ": " + e.getMessage());
        }
    }

    /** Quét bảo hành sắp hết hạn trong 30 ngày tới (mỗi bảo hành chỉ nhắc một lần cho mỗi ngày hết hạn). */
    public int scanExpiring() throws SQLException {
        Map<String, String> c = config();
        if (!on(c, "ntf_enabled") || !on(c, "ntf_ev_EXPIRING")) return 0;
        LocalDate t = LocalDate.now();
        int n = 0;
        for (Row r : db.query("SELECT w.id AS warranty_id, w.serial, w.expiry_date, cu.id AS customer_id, cu.name, cu.phone, cu.email, "
                + "p.name AS product FROM warranty w JOIN customer cu ON cu.id = w.customer_id JOIN product p ON p.id = w.product_id "
                + "WHERE w.expiry_date BETWEEN ? AND ?", t.plusDays(1).toString(), t.plusDays(30).toString())) {
            Map<String, String> v = baseVars(r);
            v.put("link", settings.portalBase() + "/tra-cuu#" + r.str("serial"));
            n += enqueue(Event.EXPIRING, v, r, "EXPIRING:W" + r.lng("warranty_id") + ":" + r.str("expiry_date"), null, r.lng("warranty_id"), null);
        }
        return n;
    }

    private Map<String, String> baseVars(Row r) throws SQLException {
        Map<String, String> v = new LinkedHashMap<>();
        Map<String, String> s = settings.all();
        for (String k : new String[]{"ma_phieu", "ma_yeu_cau", "so_tien", "ngay_hen", "loi_nhan", "link"}) v.put(k, "");
        v.put("ten_khach", r.str("name"));
        v.put("san_pham", r.str("product"));
        v.put("serial", r.str("serial"));
        v.put("han_bh", Fmt.date(r.str("expiry_date")));
        v.put("cua_hang", s.getOrDefault("shop_name", ""));
        v.put("sdt_cua_hang", s.getOrDefault("shop_phone", ""));
        return v;
    }

    static String fill(String tpl, Map<String, String> v) {
        String out = tpl;
        for (Map.Entry<String, String> e : v.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        return out.replaceAll("\\s{2,}", " ").trim();
    }

    /** Ghi tin vào hàng đợi cho từng kênh đang bật. Trả về số tin mới. */
    private int enqueue(Event ev, Map<String, String> v, Row who, String dedup, Long ticketId, Long warrantyId, Long requestId) throws SQLException {
        Map<String, String> c = config();
        if (!on(c, "ntf_enabled") || !on(c, "ntf_ev_" + ev.name())) return 0;
        long customerId = who.lng("customer_id");
        if (ev == Event.EXPIRING && db.scalar("SELECT COUNT(*) FROM notify_optout WHERE customer_id = ?", customerId) > 0) return 0;
        String text = fill(c.getOrDefault("ntf_tpl_" + ev.name(), ev.template), v);
        String subject = fill(ev.subject, v);
        int n = 0;
        String now = Database.now();
        if (on(c, "ntf_email_on") && EmailSender.validAddress(who.str("email")))
            n += insert(now, ev, "EMAIL", who.str("email"), subject, text, customerId, ticketId, warrantyId, requestId, dedup);
        if (on(c, "ntf_sms_on") && !who.str("phone").isEmpty())
            n += insert(now, ev, "SMS", who.str("phone"), null, on(c, "ntf_sms_unicode") ? text : HttpSenders.removeAccents(text),
                    customerId, ticketId, warrantyId, requestId, dedup);
        String tpl = c.getOrDefault("ntf_zalo_tpl_" + ev.name(), "");
        if (on(c, "ntf_zalo_on") && !who.str("phone").isEmpty() && !tpl.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("customer_name", v.get("ten_khach"));
            data.put("product_name", v.get("san_pham"));
            data.put("ticket_code", v.getOrDefault("ma_phieu", "").isEmpty() ? v.getOrDefault("ma_yeu_cau", "") : v.get("ma_phieu"));
            data.put("amount", v.get("so_tien"));
            data.put("date", ev == Event.EXPIRING ? v.get("han_bh") : v.get("ngay_hen"));
            data.put("shop_name", v.get("cua_hang"));
            data.put("shop_phone", v.get("sdt_cua_hang"));
            data.put("note", v.get("loi_nhan"));
            data.put("link", v.get("link"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("template_id", tpl.trim());
            payload.put("template_data", data);
            n += insert(now, ev, "ZALO", who.str("phone"), "Mẫu " + tpl.trim(), Json.stringify(payload), customerId, ticketId, warrantyId, requestId, dedup);
        }
        return n;
    }

    private int insert(String now, Event ev, String channel, String to, String subject, String body, long customerId,
                       Long ticketId, Long warrantyId, Long requestId, String dedup) throws SQLException {
        return db.update("INSERT OR IGNORE INTO notification (created_at, event, channel, recipient, subject, body, status, next_try, "
                + "customer_id, ticket_id, warranty_id, request_id, dedup_key) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)",
                now, ev.name(), channel, to, subject, body, now, customerId, ticketId, warrantyId, requestId, dedup + ":" + channel);
    }

    // ===================== Gửi =====================

    /** Bật luồng gửi nền: xử lý hàng đợi mỗi 15 giây, quét sắp hết hạn mỗi giờ. */
    public void start() {
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gui-thong-bao");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(() -> {
            try { processDue(); } catch (Exception e) { System.err.println("Gui thong bao loi: " + e.getMessage()); }
        }, 5, 15, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(() -> {
            try { scanExpiring(); } catch (Exception e) { System.err.println("Quet het han loi: " + e.getMessage()); }
        }, 2, 60, TimeUnit.MINUTES);
    }

    static boolean quietHours() {
        if (Boolean.getBoolean("baohanh.notify.anytime")) return false;
        int h = LocalDateTime.now().getHour();
        return h < 6 || h >= 22;
    }

    /** Gửi các tin đến hạn. Trả về số tin đã xử lý. */
    public int processDue() throws SQLException {
        if (!running.compareAndSet(false, true)) return 0;
        try {
            int n = 0;
            for (Row r : db.query("SELECT * FROM notification WHERE status = 'PENDING' AND next_try <= ? ORDER BY id LIMIT 20", Database.now())) {
                String ch = r.str("channel");
                if (!"EMAIL".equals(ch) && quietHours()) {
                    LocalDateTime six = LocalDate.now().atTime(6, 0);
                    if (LocalDateTime.now().getHour() >= 22) six = six.plusDays(1);
                    db.update("UPDATE notification SET next_try = ?, last_error = ? WHERE id = ?", Database.ts(six),
                            "Ngoài giờ cho phép gửi (6h–22h), sẽ gửi lúc 6h", r.lng("id"));
                    continue;
                }
                deliver(r);
                n++;
            }
            return n;
        } finally {
            running.set(false);
        }
    }

    private void deliver(Row r) throws SQLException {
        Map<String, String> c = config();
        try {
            String providerId = send(c, r.str("channel"), r.str("recipient"), r.str("subject"), r.str("body"), "N" + r.lng("id"));
            db.update("UPDATE notification SET status = 'SENT', sent_at = ?, attempts = attempts + 1, last_error = NULL, provider_id = ? WHERE id = ?",
                    Database.now(), providerId, r.lng("id"));
        } catch (Exception e) {
            int attempts = r.integer("attempts") + 1;
            boolean give = attempts >= 4;
            db.update("UPDATE notification SET status = ?, attempts = ?, last_error = ?, next_try = ? WHERE id = ?",
                    give ? "FAILED" : "PENDING", attempts, String.valueOf(e.getMessage()),
                    Database.ts(LocalDateTime.now().plusMinutes(1L << attempts)), r.lng("id"));
        }
    }

    @SuppressWarnings("unchecked")
    private String send(Map<String, String> c, String channel, String to, String subject, String body, String tracking) throws Exception {
        switch (channel) {
            case "EMAIL" -> {
                int port = Integer.parseInt(c.getOrDefault("ntf_smtp_port", "465").isBlank() ? "465" : c.get("ntf_smtp_port"));
                String fromName = c.getOrDefault("ntf_smtp_from_name", "");
                String user = c.getOrDefault("ntf_smtp_user", "").trim();
                String password = c.getOrDefault("ntf_smtp_password", "");
                // Mật khẩu ứng dụng Gmail hiển thị dạng "abcd efgh ijkl mnop" – bỏ dấu cách cho chắc
                if (c.getOrDefault("ntf_smtp_host", "").toLowerCase().contains("gmail")) password = password.replace(" ", "");
                if (fromName.isBlank()) fromName = settings.get("shop_name");
                EmailSender.Config cfg = new EmailSender.Config(c.get("ntf_smtp_host"), port,
                        EmailSender.Security.valueOf(c.getOrDefault("ntf_smtp_security", "SSL")), user,
                        password, user, fromName);
                String footer = "\n\n—\n" + settings.get("shop_name") + " · " + settings.get("shop_address") + " · " + settings.get("shop_phone")
                        + "\nĐây là email tự động, vui lòng không trả lời.";
                return new EmailSender(cfg).send(to, subject, body + footer);
            }
            case "SMS" -> {
                return HttpSenders.esms(c.get("ntf_esms_api_key"), c.get("ntf_esms_secret_key"), c.get("ntf_esms_brandname"), to, body,
                        on(c, "ntf_sms_unicode"));
            }
            case "ZALO" -> {
                Map<String, Object> p = Json.parseObject(body);
                return HttpSenders.zalo(c.get("ntf_zalo_token"), Json.str(p, "template_id"), to, (Map<String, Object>) p.get("template_data"), tracking);
            }
            default -> throw new IllegalArgumentException("Kênh không hỗ trợ: " + channel);
        }
    }

    /** Cửa hàng đã bật và cấu hình gửi email chưa (dùng cho OTP quên mật khẩu). */
    public boolean emailReady() throws SQLException {
        Map<String, String> c = config();
        return on(c, "ntf_email_on") && !c.getOrDefault("ntf_smtp_host", "").isBlank() && !c.getOrDefault("ntf_smtp_user", "").isBlank();
    }

    /** Gửi email ngay, không qua hàng đợi (mã OTP không được lưu trong bảng notification). */
    public String sendEmailNow(String to, String subject, String body) throws Exception {
        if (!EmailSender.validAddress(to)) throw new AppException("Email nhận không hợp lệ");
        return send(config(), "EMAIL", to, subject, body, null);
    }

    /** Gửi tin thử ngay (không qua hàng đợi) để kiểm tra cấu hình. */
    public String sendTest(User u, String channel, String to) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.NOTIFY_CONFIG);
        if (!CHANNEL_LABELS.containsKey(channel)) throw new AppException("Kênh không hợp lệ");
        String recipient = to == null ? "" : to.trim();
        if ("EMAIL".equals(channel) && !EmailSender.validAddress(recipient)) throw new AppException("Email nhận không hợp lệ");
        if (!"EMAIL".equals(channel)) {
            recipient = recipient.replaceAll("[\\s.-]", "");
            if (!vn.baohanh.service.CatalogService.PHONE.matcher(recipient).matches()) throw new AppException("Số điện thoại nhận không hợp lệ");
        }
        Map<String, String> c = config();
        Map<String, String> v = new LinkedHashMap<>();
        v.put("cua_hang", settings.get("shop_name"));
        String text = fill(Event.TEST.template, v);
        String body = text;
        if ("SMS".equals(channel) && !on(c, "ntf_sms_unicode")) body = HttpSenders.removeAccents(text);
        if ("ZALO".equals(channel)) {
            String tpl = c.getOrDefault("ntf_zalo_tpl_RECEIVED", "");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("customer_name", "Khách thử");
            data.put("product_name", "Sản phẩm thử");
            data.put("ticket_code", "PBH-TEST");
            data.put("amount", "0 đ");
            data.put("date", Fmt.date(LocalDate.now().toString()));
            data.put("shop_name", settings.get("shop_name"));
            data.put("shop_phone", settings.get("shop_phone"));
            data.put("note", "Tin thử");
            data.put("link", settings.portalBase() + "/tra-cuu");
            body = Json.stringify(Map.of("template_id", tpl, "template_data", data));
        }
        String now = Database.now();
        long id = db.insert("INSERT INTO notification (created_at, event, channel, recipient, subject, body, status, next_try) "
                + "VALUES (?, 'TEST', ?, ?, ?, ?, 'PENDING', ?)", now, channel, recipient, fill(Event.TEST.subject, v), body, now);
        try {
            String pid = send(c, channel, recipient, fill(Event.TEST.subject, v), body, "N" + id);
            db.update("UPDATE notification SET status = 'SENT', sent_at = ?, attempts = 1, provider_id = ? WHERE id = ?", Database.now(), pid, id);
            audit.log(u, "GỬI THỬ THÔNG BÁO", "notification", id, channel + " → " + recipient);
            return "Đã gửi thử qua " + CHANNEL_LABELS.get(channel) + " tới " + recipient;
        } catch (Exception e) {
            db.update("UPDATE notification SET status = 'FAILED', attempts = 1, last_error = ? WHERE id = ?", String.valueOf(e.getMessage()), id);
            throw new AppException("Gửi thử thất bại: " + e.getMessage());
        }
    }

    // ===================== Nhật ký =====================

    public List<Row> list(User u, String status, String q, Long ticketId) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.NOTIFY_VIEW);
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        List<Object> p = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT n.*, COALESCE(cu.name, '') AS customer_name, COALESCE(t.code, '') AS ticket_code "
                + "FROM notification n LEFT JOIN customer cu ON cu.id = n.customer_id LEFT JOIN ticket t ON t.id = n.ticket_id WHERE 1 = 1 ");
        if (status != null && !status.isBlank()) { sql.append("AND n.status = ? "); p.add(status); }
        if (ticketId != null && ticketId > 0) { sql.append("AND n.ticket_id = ? "); p.add(ticketId); }
        sql.append("AND (n.recipient LIKE ? OR cu.name LIKE ? OR t.code LIKE ? OR n.body LIKE ?) ORDER BY n.id DESC LIMIT 500");
        p.add(like); p.add(like); p.add(like); p.add(like);
        List<Row> rows = db.query(sql.toString(), p.toArray());
        for (Row r : rows) {
            r.put("event_label", Event.valueOf(r.str("event")).label);
            r.put("channel_label", CHANNEL_LABELS.get(r.str("channel")));
            r.put("status_label", STATUS_LABELS.get(r.str("status")));
        }
        return rows;
    }

    /** Gửi lại tin lỗi. */
    public void retry(User u, long id) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.NOTIFY_VIEW);
        int n = db.update("UPDATE notification SET status = 'PENDING', attempts = 0, next_try = ?, last_error = NULL WHERE id = ? AND status = 'FAILED'",
                Database.now(), id);
        if (n == 0) throw new AppException("Chỉ gửi lại được tin đang ở trạng thái lỗi");
    }

    public Row stats(User u) throws SQLException {
        Auth.require(u);
        Row r = new Row();
        for (String s : STATUS_LABELS.keySet()) r.put(s, db.scalar("SELECT COUNT(*) FROM notification WHERE status = ?", s));
        r.put("enabled", on(config(), "ntf_enabled"));
        return r;
    }
}
