package vn.baohanh.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.User;
import vn.baohanh.util.Json;

/**
 * Đặt hàng linh kiện từ nhà cung cấp.
 * Quy trình: Gợi ý (linh kiện dưới mức tối thiểu) → Nháp → Đã đặt → Đã nhận hàng (cộng tồn kho) / Hủy.
 */
public class PurchaseService {
    public static final Map<String, String> STATUS = new LinkedHashMap<>();
    static {
        STATUS.put("DRAFT", "Nháp");
        STATUS.put("ORDERED", "Đã đặt – chờ hàng");
        STATUS.put("RECEIVED", "Đã nhận hàng");
        STATUS.put("CANCELLED", "Đã hủy");
    }

    private final Database db;
    private final AuditService audit;

    public PurchaseService(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** Số ngày chờ hàng về (mặc định) và số ngày muốn đủ dùng sau khi hàng về – chỉnh trong Thông tin cửa hàng. */
    public static final int DEFAULT_LEAD_DAYS = 7, DEFAULT_COVER_DAYS = 30, SAFETY_DAYS = 7;

    private SettingService settings;

    public void setSettings(SettingService s) { this.settings = s; }

    private int setting(String key, int def) {
        try {
            String v = settings == null ? null : settings.get(key);
            return v == null || v.isBlank() ? def : Math.max(0, Integer.parseInt(v.trim()));
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * Dự báo tồn kho cho mọi linh kiện: lượng dùng trung bình mỗi ngày (trộn 30 ngày gần nhất và 90 ngày, để bắt kịp xu hướng),
     * số ngày còn đủ dùng, ngày dự kiến hết hàng, và số lượng nên đặt.
     * Cần đặt khi: tồn ≤ mức tối thiểu, HOẶC (tồn + đang về) chỉ đủ dùng ≤ thời gian chờ hàng + 7 ngày dự phòng.
     * Số lượng gợi ý = max(2 × tối thiểu − tồn − đang về, lượng dùng trong (thời gian chờ + số ngày muốn đủ dùng) + tối thiểu − tồn − đang về).
     */
    public static List<Row> plan(Database db, int leadDays, int coverDays) throws SQLException {
        String d30 = Database.ts(java.time.LocalDateTime.now().minusDays(30)), d90 = Database.ts(java.time.LocalDateTime.now().minusDays(90));
        List<Row> list = db.query("SELECT p.id, p.code, p.name, p.unit, p.stock, p.min_stock, p.cost_price, COALESCE(b.name, '') AS brand_name, "
                + "COALESCE((SELECT SUM(l.qty - l.received_qty) FROM purchase_order_line l JOIN purchase_order o ON o.id = l.po_id "
                + "WHERE l.part_id = p.id AND o.status IN ('DRAFT','ORDERED')), 0) AS incoming, "
                + "COALESCE((SELECT SUM(-m.qty) FROM stock_movement m WHERE m.part_id = p.id AND m.type IN ('OUT','RETURN') AND m.time >= ?), 0) AS used_30, "
                + "COALESCE((SELECT SUM(-m.qty) FROM stock_movement m WHERE m.part_id = p.id AND m.type IN ('OUT','RETURN') AND m.time >= ?), 0) AS used_90, "
                + "(SELECT COUNT(*) FROM ticket t WHERE t.status = 'WAITING_PARTS') AS waiting_tickets "
                + "FROM part p LEFT JOIN brand b ON b.id = p.brand_id ORDER BY p.name", d30, d90);
        java.time.LocalDate today = java.time.LocalDate.now();
        for (Row r : list) {
            long stock = r.lng("stock"), min = r.lng("min_stock"), incoming = r.lng("incoming");
            double daily = Math.max(0, 0.5 * Math.max(0, r.lng("used_30")) / 30.0 + 0.5 * Math.max(0, r.lng("used_90")) / 90.0);
            r.put("daily_use", Math.round(daily * 100) / 100.0);
            r.put("monthly_use", Math.round(daily * 30 * 10) / 10.0);
            Long daysLeft = daily <= 0 ? null : (long) Math.floor(stock / daily);
            r.put("days_left", daysLeft);
            r.put("stockout_date", daysLeft == null ? null : today.plusDays(daysLeft).toString());
            boolean lowMin = stock <= min;
            boolean lowForecast = daily > 0 && (stock + incoming) / daily <= leadDays + SAFETY_DAYS;
            long byMin = 2 * min - stock - incoming;
            long byUse = (long) Math.ceil(daily * (leadDays + coverDays)) + min - stock - incoming;
            long qty = Math.max(0, Math.max(byMin, byUse));
            if (lowMin && qty == 0 && incoming < Math.max(1, 2 * min - stock)) qty = 1;
            r.put("suggest_qty", qty);
            r.put("need", lowMin || lowForecast);
            String reason;
            if (lowMin && stock <= 0) reason = "Đã hết hàng";
            else if (lowMin) reason = "Dưới mức tối thiểu (" + min + ")";
            else if (lowForecast) reason = "Dự báo hết sau " + daysLeft + " ngày – hàng về mất " + leadDays + " ngày";
            else reason = "";
            if (!reason.isEmpty() && incoming > 0 && qty == 0) reason += " · đã có " + incoming + " đang về";
            r.put("reason", reason);
        }
        return list;
    }

    /** Linh kiện cần đặt (theo mức tối thiểu hoặc theo dự báo hết hàng), gấp nhất đứng trước. */
    public List<Row> suggestions(User u) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.PURCHASE_VIEW);
        List<Row> out = new java.util.ArrayList<>(plan(db, setting("po_lead_days", DEFAULT_LEAD_DAYS), setting("po_cover_days", DEFAULT_COVER_DAYS))
                .stream().filter(r -> Boolean.TRUE.equals(r.get("need"))).toList());
        out.sort(java.util.Comparator.comparingLong((Row r) -> r.get("days_left") == null ? (r.lng("stock") - r.lng("min_stock")) * 1000 : r.lng("days_left"))
                .thenComparing(r -> r.str("name")));
        return out;
    }

    /** Bảng dự báo tồn kho: mọi linh kiện có phát sinh sử dụng trong 90 ngày, sắp xếp theo số ngày còn đủ dùng. */
    public Row forecast(User u) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.PURCHASE_VIEW);
        int lead = setting("po_lead_days", DEFAULT_LEAD_DAYS), cover = setting("po_cover_days", DEFAULT_COVER_DAYS);
        List<Row> rows = plan(db, lead, cover).stream().filter(r -> r.lng("used_90") > 0 || Boolean.TRUE.equals(r.get("need")))
                .sorted(java.util.Comparator.comparingLong((Row r) -> r.get("days_left") == null ? Long.MAX_VALUE : r.lng("days_left"))).toList();
        return new Row().with("rows", rows).with("lead_days", (long) lead).with("cover_days", (long) cover);
    }

    /** Số linh kiện cần đặt (cho số trên menu, bảng điều khiển). */
    public static long countToOrder(Database db, int lead, int cover) throws SQLException {
        return plan(db, lead, cover).stream().filter(r -> Boolean.TRUE.equals(r.get("need")) && r.lng("suggest_qty") > 0).count();
    }

    public List<Row> list(User u, String status) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.PURCHASE_VIEW);
        List<Row> list = db.query("SELECT o.*, s.name AS supplier_name, COALESCE(us.full_name, '') AS created_by_name, "
                + "(SELECT COUNT(*) FROM purchase_order_line l WHERE l.po_id = o.id) AS lines, "
                + "(SELECT COALESCE(SUM(l.qty * l.unit_cost), 0) FROM purchase_order_line l WHERE l.po_id = o.id) AS total "
                + "FROM purchase_order o JOIN supplier s ON s.id = o.supplier_id LEFT JOIN app_user us ON us.id = o.created_by "
                + (status == null || status.isBlank() ? "" : "WHERE o.status = ? ") + "ORDER BY o.id DESC LIMIT 300",
                status == null || status.isBlank() ? new Object[0] : new Object[]{status});
        for (Row r : list) r.put("status_label", STATUS.get(r.str("status")));
        return list;
    }

    public Row get(User u, long id) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.PURCHASE_VIEW);
        Row o = db.one("SELECT o.*, s.name AS supplier_name, s.phone AS supplier_phone, s.email AS supplier_email, s.address AS supplier_address, "
                + "s.contact AS supplier_contact, COALESCE(us.full_name, '') AS created_by_name, COALESCE(ur.full_name, '') AS received_by_name "
                + "FROM purchase_order o JOIN supplier s ON s.id = o.supplier_id LEFT JOIN app_user us ON us.id = o.created_by "
                + "LEFT JOIN app_user ur ON ur.id = o.received_by WHERE o.id = ?", id);
        if (o == null) throw AppException.notFound("đơn đặt hàng");
        List<Row> lines = db.query("SELECT l.*, p.code, p.name, p.unit, p.stock, l.qty * l.unit_cost AS amount FROM purchase_order_line l "
                + "JOIN part p ON p.id = l.part_id WHERE l.po_id = ? ORDER BY l.id", id);
        o.put("lines", lines);
        o.put("total", lines.stream().mapToLong(l -> l.lng("amount")).sum());
        o.put("status_label", STATUS.get(o.str("status")));
        return o;
    }

    /** Tạo đơn nháp. in = {supplier_id, expected_date, note, lines: [{part_id, qty, unit_cost}]} */
    @SuppressWarnings("unchecked")
    public Row create(User u, Map<String, Object> in) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.PURCHASE);
        long sup = Json.num(in, "supplier_id", 0);
        if (sup <= 0 || db.scalar("SELECT COUNT(*) FROM supplier WHERE id = ?", sup) == 0) throw new AppException("Vui lòng chọn nhà cung cấp");
        Object raw = in.get("lines");
        if (!(raw instanceof List<?> lst) || lst.isEmpty()) throw new AppException("Đơn đặt hàng phải có ít nhất một linh kiện");
        List<long[]> lines = new ArrayList<>();
        for (Object o : lst) {
            Map<String, Object> m = (Map<String, Object>) o;
            long part = Json.num(m, "part_id", 0), qty = Json.num(m, "qty", 0), cost = Json.num(m, "unit_cost", -1);
            if (qty <= 0) continue;
            if (qty > 100_000) throw new AppException("Số lượng quá lớn");
            Row p = db.one("SELECT id, cost_price FROM part WHERE id = ?", part);
            if (p == null) throw AppException.notFound("linh kiện");
            for (long[] l : lines) if (l[0] == part) throw new AppException("Một linh kiện bị lặp trong đơn");
            lines.add(new long[]{part, qty, cost < 0 ? p.lng("cost_price") : cost});
        }
        if (lines.isEmpty()) throw new AppException("Đơn đặt hàng phải có ít nhất một linh kiện với số lượng > 0");
        String exp = Json.str(in, "expected_date");
        if (!exp.isEmpty()) {
            try { LocalDate.parse(exp); } catch (Exception e) { throw new AppException("Ngày dự kiến nhận không hợp lệ"); }
        }
        long id = db.tx(() -> {
            long n = db.scalar("SELECT COALESCE(MAX(id), 0) FROM purchase_order") + 1;
            String code = String.format("DH-%06d", n);
            while (db.scalar("SELECT COUNT(*) FROM purchase_order WHERE code = ?", code) > 0) code = String.format("DH-%06d", ++n);
            long po = db.insert("INSERT INTO purchase_order (code, supplier_id, status, expected_date, note, created_by, created_at) "
                    + "VALUES (?, ?, 'DRAFT', ?, ?, ?, ?)", code, sup, exp.isEmpty() ? null : exp, Json.str(in, "note"), u.id(), Database.now());
            for (long[] l : lines)
                db.insert("INSERT INTO purchase_order_line (po_id, part_id, qty, unit_cost) VALUES (?, ?, ?, ?)", po, l[0], l[1], l[2]);
            audit.log(u, "TẠO ĐƠN ĐẶT HÀNG", "purchase_order", po, code + " (" + lines.size() + " linh kiện)");
            return po;
        });
        return get(u, id);
    }

    private Row checkStatus(long id, String... allowed) throws SQLException {
        Row o = db.one("SELECT * FROM purchase_order WHERE id = ?", id);
        if (o == null) throw AppException.notFound("đơn đặt hàng");
        if (!List.of(allowed).contains(o.str("status")))
            throw new AppException("Đơn đang ở trạng thái \"" + STATUS.get(o.str("status")) + "\", không thực hiện được");
        return o;
    }

    /** Chốt đơn: đã gửi cho nhà cung cấp. */
    public Row markOrdered(User u, long id) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.PURCHASE);
        Row o = checkStatus(id, "DRAFT");
        db.update("UPDATE purchase_order SET status = 'ORDERED', ordered_at = ? WHERE id = ?", Database.now(), id);
        audit.log(u, "ĐẶT HÀNG", "purchase_order", id, o.str("code"));
        return get(u, id);
    }

    public Row cancel(User u, long id, String note) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.PURCHASE);
        Row o = checkStatus(id, "DRAFT", "ORDERED");
        db.update("UPDATE purchase_order SET status = 'CANCELLED', note = TRIM(COALESCE(note, '') || ' ' || ?) WHERE id = ?",
                note == null || note.isBlank() ? "" : "[Hủy: " + note.trim() + "]", id);
        audit.log(u, "HỦY ĐƠN ĐẶT HÀNG", "purchase_order", id, o.str("code"));
        return get(u, id);
    }

    /**
     * Nhận hàng: cộng tồn kho theo số thực nhận (mặc định = số đặt), ghi thẻ kho, cập nhật giá nhập.
     * in = {lines: [{id, received_qty}]} (không gửi = nhận đủ).
     */
    @SuppressWarnings("unchecked")
    public Row receive(User u, long id, Map<String, Object> in) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.PURCHASE);
        Map<Long, Long> got = new LinkedHashMap<>();
        if (in != null && in.get("lines") instanceof List<?> lst)
            for (Object x : lst) {
                Map<String, Object> m = (Map<String, Object>) x;
                got.put(Json.num(m, "id", 0), Json.num(m, "received_qty", -1));
            }
        db.tx(() -> {
            Row o = checkStatus(id, "ORDERED", "DRAFT");
            long total = 0;
            for (Row l : db.query("SELECT l.*, p.code FROM purchase_order_line l JOIN part p ON p.id = l.part_id WHERE l.po_id = ?", id)) {
                long q = got.getOrDefault(l.lng("id"), -1L);
                if (q < 0) q = l.lng("qty");
                if (q > l.lng("qty") * 2) throw new AppException("Số nhận của " + l.str("code") + " vượt quá nhiều so với số đặt");
                db.update("UPDATE purchase_order_line SET received_qty = ? WHERE id = ?", q, l.lng("id"));
                if (q > 0) {
                    db.update("UPDATE part SET stock = stock + ?" + (l.lng("unit_cost") > 0 ? ", cost_price = " + l.lng("unit_cost") : "")
                            + " WHERE id = ?", q, l.lng("part_id"));
                    db.update("INSERT INTO stock_movement (part_id, time, qty, type, user_id, note) VALUES (?, ?, ?, 'IN', ?, ?)",
                            l.lng("part_id"), Database.now(), q, u.id(), "Nhập theo đơn " + o.str("code"));
                }
                total += q;
            }
            db.update("UPDATE purchase_order SET status = 'RECEIVED', received_at = ?, received_by = ?, "
                    + "ordered_at = COALESCE(ordered_at, ?) WHERE id = ?", Database.now(), u.id(), Database.now(), id);
            audit.log(u, "NHẬN HÀNG", "purchase_order", id, o.str("code") + " (" + total + " đơn vị)");
            return null;
        });
        return get(u, id);
    }
}
