package vn.baohanh.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.TicketStatus;

/**
 * Cổng tra cứu công khai cho khách hàng (không cần đăng nhập).
 * Chỉ tra theo Serial/IMEI hoặc mã phiếu (không tra theo SĐT để tránh lộ thông tin người khác),
 * tên và SĐT khách được che bớt, không hiện ghi chú nội bộ.
 * Giới hạn 30 lượt tra cứu / phút cho mỗi địa chỉ IP.
 */
public class PortalService {
    private static final int LIMIT_PER_MINUTE = 30;

    private final Database db;
    private final SettingService settings;
    private final Map<String, long[]> hits = new ConcurrentHashMap<>();

    public PortalService(Database db, SettingService settings) {
        this.db = db;
        this.settings = settings;
    }

    public Row lookup(String q, String clientIp) throws SQLException {
        throttle(clientIp);
        String key = q == null ? "" : q.trim().replaceAll("\\s", "");
        if (key.length() < 4) throw new AppException("Vui lòng nhập số Serial/IMEI hoặc mã phiếu");
        Row w = db.one("SELECT * FROM v_warranty WHERE serial = ? "
                + "OR id = (SELECT warranty_id FROM ticket WHERE code = ? COLLATE NOCASE)", key, key);
        Row out = new Row();
        out.put("shop", shopInfo());
        if (w == null) { out.put("found", false); return out; }
        WarrantyService.warrantyView(w, WarrantyService.today());
        Row v = new Row();
        v.put("product_name", w.str("product_name"));
        v.put("brand_name", w.str("brand_name"));
        v.put("serial", w.str("serial"));
        v.put("code", w.str("code"));
        v.put("customer", maskName(w.str("customer_name")));
        v.put("phone", maskPhone(w.str("customer_phone")));
        v.put("purchase_date", w.str("purchase_date"));
        v.put("expiry_date", w.str("expiry_date"));
        v.put("months", w.lng("months"));
        v.put("active", w.get("active"));
        v.put("days_left", w.get("days_left"));
        List<Row> tickets = new ArrayList<>();
        for (Row t : db.query("SELECT t.id, t.code, t.received_at, t.due_date, t.status, t.in_warranty, t.quote_status, "
                + "t.returned_at, vc.total FROM ticket t JOIN v_ticket_cost vc ON vc.ticket_id = t.id "
                + "WHERE t.warranty_id = ? ORDER BY t.received_at DESC", w.lng("id"))) {
            TicketStatus s = TicketStatus.valueOf(t.str("status"));
            Row tv = new Row();
            tv.put("code", t.str("code"));
            tv.put("received_at", t.str("received_at"));
            tv.put("due_date", t.str("due_date"));
            tv.put("returned_at", t.str("returned_at"));
            tv.put("status", s.name());
            tv.put("status_label", s.getLabel());
            tv.put("in_warranty", t.bool("in_warranty"));
            tv.put("quote_pending", "PENDING".equals(t.str("quote_status")));
            // Chi phí chỉ hiện khi đã báo giá hoặc sửa xong
            boolean showCost = !t.bool("in_warranty") && (!"NONE".equals(t.str("quote_status")) || s == TicketStatus.READY);
            tv.put("total", showCost ? t.lng("total") : null);
            // Các mốc trạng thái (không kèm ghi chú nội bộ)
            List<Row> steps = new ArrayList<>();
            String last = "";
            for (Row h : db.query("SELECT time, status FROM ticket_history WHERE ticket_id = ? ORDER BY time, id", t.lng("id"))) {
                if (h.str("status").equals(last)) continue;
                last = h.str("status");
                steps.add(new Row().with("time", h.str("time")).with("label", TicketStatus.valueOf(last).getLabel()));
            }
            tv.put("steps", steps);
            tickets.add(tv);
        }
        v.put("tickets", tickets);
        out.put("found", true);
        out.put("warranty", v);
        return out;
    }

    public Row shopInfo() throws SQLException {
        Map<String, String> s = settings.all();
        return new Row().with("name", s.get("shop_name")).with("address", s.get("shop_address")).with("phone", s.get("shop_phone"));
    }

    /** Thông tin đầy đủ cho trang "Cửa hàng" trên cổng khách. */
    public Row shopPage() throws SQLException {
        Map<String, String> s = settings.all();
        Row r = shopInfo();
        for (String k : new String[]{"shop_email", "open_hours", "map_query", "social_link", "portal_intro", "policy"})
            r.put(k.replace("shop_", ""), s.getOrDefault(k, ""));
        String social = s.getOrDefault("social_link", "").trim();
        if (!social.isEmpty() && !social.matches("(?i)^https?://.*")) r.put("social_link", "");   // chỉ nhận http(s)
        return r;
    }

    private void throttle(String ip) {
        long minute = System.currentTimeMillis() / 60000;
        long[] h = hits.compute(ip == null ? "?" : ip, (k, old) -> old == null || old[0] != minute ? new long[]{minute, 1} : new long[]{minute, old[1] + 1});
        if (h[1] > LIMIT_PER_MINUTE) throw new AppException(429, "Bạn tra cứu quá nhiều lần, vui lòng thử lại sau 1 phút");
        if (hits.size() > 10_000) hits.clear();
    }

    /** "Nguyễn Văn An" -> "N. V. An" */
    public static String maskName(String name) {
        String[] p = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (p[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(i == p.length - 1 ? p[i] : p[i].substring(0, 1) + ".");
        }
        return sb.toString();
    }

    /** "0903123456" -> "0903***456" */
    public static String maskPhone(String phone) {
        if (phone.length() < 7) return "***";
        return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 3);
    }
}
