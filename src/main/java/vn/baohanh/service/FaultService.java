package vn.baohanh.service;

import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.service.ReportService.Period;

/**
 * Thống kê lỗi thường gặp trong một khoảng thời gian (theo ngày nhận máy):
 * nhóm lỗi (phân loại theo từ khóa trong "lỗi khách báo" + "kết luận kỹ thuật"),
 * model hay hỏng (kèm tỷ lệ hỏng = số phiếu / số máy đã kích hoạt),
 * linh kiện thay nhiều nhất và máy phải sửa lại nhiều lần.
 */
public class FaultService {
    private final Database db;

    public FaultService(Database db) {
        this.db = db;
    }

    /** Nhóm lỗi: mã, tên hiển thị, từ khóa (đã bỏ dấu, viết thường). Một phiếu có thể thuộc nhiều nhóm. */
    record Group(String key, String label, String... words) {}

    static final List<Group> GROUPS = List.of(
            new Group("POWER", "Không lên nguồn / tự tắt", "khong len nguon", "khong len", "mat nguon", "tat nguon", "sap nguon",
                    "tu tat", "khong khoi dong", "chet nguon", "khong mo may", "khong mo nguon", "khong bat"),
            new Group("BATTERY", "Pin / sạc", "pin", "sac", "chai pin", "phong", "hao pin", "cong sac", "adapter", "cuc sac"),
            new Group("SCREEN", "Màn hình / cảm ứng", "man hinh", "soc", "cam ung", "vo man", "diem chet", "hien thi", "toi man",
                    "chop", "nhap nhay", "den nen", "loang mau", "am man"),
            new Group("AUDIO", "Loa / micro / tai nghe", "loa", "mic", "micro", "am thanh", "tieng nho", "mat tieng", "tai nghe",
                    "tai phai", "tai trai", "re re", "khong nghe"),
            new Group("CAMERA", "Camera", "camera", "chup anh", "mo camera", "lay net"),
            new Group("NETWORK", "Mạng / WiFi / Bluetooth / sóng", "wifi", "wi fi", "bluetooth", "song", "mat song", "sim",
                    "ket noi", "ghep noi", "mat mang", "mang yeu", "khong vao mang", "khong nhan sim"),
            new Group("HEAT", "Nóng máy / quạt tản nhiệt", "nong", "qua nhiet", "quat", "tan nhiet"),
            new Group("PHYSICAL", "Vào nước / rơi vỡ (lỗi người dùng)", "vao nuoc", "ngam nuoc", "dinh nuoc", "roi vo", "bi roi", "lam roi", "roi xuong",
                    "va dap", "mop", "nut vo", "be"),
            new Group("KEYS", "Bàn phím / nút bấm / touchpad", "ban phim", "phim", "nut", "nut bam", "touchpad", "liet phim"),
            new Group("SOFTWARE", "Phần mềm / treo / tự khởi động lại", "treo", "phan mem", "khoi dong lai", "restart", "treo logo",
                    "lag", "cham", "giat lag", "cap nhat", "update", "virus", "man hinh xanh"),
            new Group("COOLING", "Không lạnh / làm mát kém", "khong lanh", "lanh yeu", "kem lanh", "gas", "dong da", "dong tuyet",
                    "ngan mat", "ngan da"),
            new Group("WASHER", "Máy giặt: vắt / xả / cấp nước", "vat", "xa nuoc", "cap nuoc", "long giat", "khong giat"),
            new Group("NOISE", "Tiếng kêu / rung lạ", "keu", "tieng on", "on ao", "rung", "lach cach", "tieng la", "tieng keu"));

    static final String OTHER = "OTHER";

    /** Bỏ dấu tiếng Việt, viết thường, chỉ giữ chữ/số, cách nhau bằng một khoảng trắng. */
    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Các nhóm lỗi mà đoạn mô tả thuộc về (rỗng → "Khác"). */
    public static List<String> classify(String text) {
        String t = " " + normalize(text) + " ";
        List<String> out = new ArrayList<>();
        for (Group g : GROUPS)
            for (String w : g.words())
                if (t.contains(" " + w + " ")) { out.add(g.key()); break; }
        if (out.isEmpty()) out.add(OTHER);
        return out;
    }

    public static String label(String key) {
        for (Group g : GROUPS) if (g.key().equals(key)) return g.label();
        return "Khác";
    }

    public Row report(User u, Period p) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.FAULTS);
        List<Row> tickets = db.query("SELECT t.id, t.code, t.issue, COALESCE(t.diagnosis, '') AS diagnosis, t.received_at, t.in_warranty, "
                + "w.serial, pr.id AS product_id, pr.name AS product, pr.model_code, b.name AS brand, c.name AS category "
                + "FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN product pr ON pr.id = w.product_id "
                + "JOIN brand b ON b.id = pr.brand_id JOIN category c ON c.id = pr.category_id "
                + "WHERE t.status <> 'CANCELLED' AND t.received_at BETWEEN ? AND ? ORDER BY t.received_at DESC", p.fromTs(), p.toTs());
        int total = tickets.size();

        // ---- Nhóm lỗi ----
        Map<String, Row> groups = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> groupModels = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> modelGroups = new LinkedHashMap<>();
        for (Row t : tickets) {
            List<String> keys = classify(t.str("issue") + " . " + t.str("diagnosis"));
            t.put("groups", keys);
            for (String k : keys) {
                Row g = groups.computeIfAbsent(k, x -> new Row().with("key", x).with("label", label(x)).with("count", 0L)
                        .with("examples", new ArrayList<String>()));
                g.put("count", g.lng("count") + 1);
                @SuppressWarnings("unchecked") List<String> ex = (List<String>) g.get("examples");
                if (ex.size() < 3 && !ex.contains(t.str("issue"))) ex.add(t.str("issue"));
                groupModels.computeIfAbsent(k, x -> new LinkedHashMap<>()).merge(t.str("product"), 1, Integer::sum);
                modelGroups.computeIfAbsent(t.lng("product_id"), x -> new LinkedHashMap<>()).merge(k, 1, Integer::sum);
            }
        }
        List<Row> groupList = new ArrayList<>(groups.values());
        groupList.sort((a, b) -> {
            if (OTHER.equals(a.str("key")) != OTHER.equals(b.str("key"))) return OTHER.equals(a.str("key")) ? 1 : -1;
            return Long.compare(b.lng("count"), a.lng("count"));
        });
        for (Row g : groupList) {
            g.put("pct", total == 0 ? 0.0 : Math.round(g.lng("count") * 1000.0 / total) / 10.0);
            g.put("top_models", topKeys(groupModels.get(g.str("key")), 3));
        }

        // ---- Model hay hỏng ----
        List<Row> models = db.query("SELECT pr.id, pr.name AS product, pr.model_code, b.name AS brand, c.name AS category, COUNT(t.id) AS tickets, "
                + "COUNT(DISTINCT w.id) AS devices, "
                + "SUM(CASE WHEN t.in_warranty = 1 THEN 1 ELSE 0 END) AS in_warranty, "
                + "(SELECT COUNT(*) FROM warranty w2 WHERE w2.product_id = pr.id) AS sold, "
                + "AVG(CASE WHEN t.status = 'RETURNED' THEN julianday(t.returned_at) - julianday(t.received_at) END) AS avg_days "
                + "FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN product pr ON pr.id = w.product_id "
                + "JOIN brand b ON b.id = pr.brand_id JOIN category c ON c.id = pr.category_id "
                + "WHERE t.status <> 'CANCELLED' AND t.received_at BETWEEN ? AND ? GROUP BY pr.id ORDER BY tickets DESC, pr.name LIMIT 15",
                p.fromTs(), p.toTs());
        for (Row m : models) {
            // tỷ lệ máy hỏng = số máy (khác nhau) phải bảo hành / số máy đã bán – không vượt 100% khi một máy sửa nhiều lần
            m.put("rate", m.lng("sold") == 0 ? 0.0 : Math.round(m.lng("devices") * 1000.0 / m.lng("sold")) / 10.0);
            Object a = m.get("avg_days");
            m.put("avg_days", a == null ? null : Math.round(((Number) a).doubleValue() * 10) / 10.0);
            List<String> top = topKeys(modelGroups.get(m.lng("id")), 2);
            m.put("top_fault", String.join(", ", top.stream().map(FaultService::label).toList()));
        }

        // ---- Linh kiện thay nhiều nhất ----
        List<Row> parts = db.query("SELECT pt.code, pt.name, pt.unit, SUM(tp.qty) AS qty, COUNT(DISTINCT tp.ticket_id) AS tickets, "
                + "SUM(tp.qty * pt.cost_price) AS cost, SUM(CASE WHEN tp.charged = 0 THEN tp.qty ELSE 0 END) AS free_qty, "
                + "GROUP_CONCAT(DISTINCT pr.name) AS models, pt.stock "
                + "FROM ticket_part tp JOIN part pt ON pt.id = tp.part_id JOIN ticket t ON t.id = tp.ticket_id "
                + "JOIN warranty w ON w.id = t.warranty_id JOIN product pr ON pr.id = w.product_id "
                + "WHERE t.received_at BETWEEN ? AND ? GROUP BY pt.id ORDER BY qty DESC, tickets DESC LIMIT 15", p.fromTs(), p.toTs());
        for (Row r : parts) r.put("models", r.str("models").replace(",", ", "));

        // ---- Máy sửa lại nhiều lần (tính mọi thời điểm, có ít nhất một lần trong kỳ) ----
        List<Row> repeats = db.query("SELECT w.serial, pr.name AS product, cu.name AS customer, cu.phone, COUNT(t.id) AS times, "
                + "MAX(t.received_at) AS last_at, GROUP_CONCAT(t.code, ', ') AS codes "
                + "FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN product pr ON pr.id = w.product_id "
                + "JOIN customer cu ON cu.id = w.customer_id WHERE t.status <> 'CANCELLED' "
                + "GROUP BY w.id HAVING COUNT(t.id) >= 2 AND MAX(t.received_at) BETWEEN ? AND ? ORDER BY times DESC, last_at DESC LIMIT 20",
                p.fromTs(), p.toTs());
        for (Row r : repeats) {
            List<Row> hist = db.query("SELECT t.issue FROM ticket t JOIN warranty w ON w.id = t.warranty_id "
                    + "WHERE w.serial = ? AND t.status <> 'CANCELLED' ORDER BY t.received_at", r.str("serial"));
            r.put("issues", String.join(" → ", hist.stream().map(h -> h.str("issue")).toList()));
        }

        // ---- Theo loại sản phẩm ----
        List<Row> byCategory = db.query("SELECT c.name AS category, COUNT(t.id) AS tickets, COUNT(DISTINCT w.id) AS devices, "
                + "(SELECT COUNT(*) FROM warranty w2 JOIN product p2 ON p2.id = w2.product_id WHERE p2.category_id = c.id) AS sold "
                + "FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN product pr ON pr.id = w.product_id "
                + "JOIN category c ON c.id = pr.category_id WHERE t.status <> 'CANCELLED' AND t.received_at BETWEEN ? AND ? "
                + "GROUP BY c.id ORDER BY tickets DESC", p.fromTs(), p.toTs());
        for (Row r : byCategory) r.put("rate", r.lng("sold") == 0 ? 0.0 : Math.round(r.lng("devices") * 1000.0 / r.lng("sold")) / 10.0);

        List<Row> recent = new ArrayList<>();
        for (Row t : tickets.subList(0, Math.min(30, total))) {
            @SuppressWarnings("unchecked") List<String> ks = (List<String>) t.get("groups");
            recent.add(new Row().with("code", t.str("code")).with("product", t.str("product")).with("issue", t.str("issue"))
                    .with("received_at", t.str("received_at")).with("groups", String.join(", ", ks.stream().map(FaultService::label).toList())));
        }

        Row out = new Row();
        out.put("from", p.from().toString());
        out.put("to", p.to().toString());
        out.put("total", (long) total);
        out.put("models_count", (long) models.size());
        out.put("repeat_count", (long) repeats.size());
        out.put("top_group", groupList.isEmpty() || OTHER.equals(groupList.get(0).str("key")) ? "" : groupList.get(0).str("label"));
        out.put("groups", groupList);
        out.put("models", models);
        out.put("parts", parts);
        out.put("repeats", repeats);
        out.put("by_category", byCategory);
        out.put("recent", recent);
        return out;
    }

    private static List<String> topKeys(Map<String, Integer> m, int n) {
        if (m == null) return List.of();
        return m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(n).map(Map.Entry::getKey).toList();
    }

    /** Thêm các trang "Lỗi thường gặp" vào file Excel báo cáo. */
    @SuppressWarnings("unchecked")
    public void addSheets(vn.baohanh.util.Xlsx x, User u, Period p) throws SQLException {
        Row r = report(u, p);
        List<List<Object>> g = new ArrayList<>();
        for (Row row : (List<Row>) r.get("groups"))
            g.add(List.of(row.str("label"), row.lng("count"), ((Number) row.get("pct")).doubleValue(),
                    String.join(", ", (List<String>) row.get("top_models")), String.join(" | ", (List<String>) row.get("examples"))));
        x.sheet("Nhóm lỗi", List.of("Nhóm lỗi", "Số phiếu", "% phiếu", "Model gặp nhiều", "Ví dụ khách báo"), g);
        List<List<Object>> m = new ArrayList<>();
        for (Row row : (List<Row>) r.get("models"))
            m.add(List.of(row.str("product"), row.str("model_code"), row.str("brand"), row.lng("tickets"), row.lng("sold"),
                    ((Number) row.get("rate")).doubleValue(), row.str("top_fault")));
        x.sheet("Model hay hỏng", List.of("Sản phẩm", "Mã model", "Hãng", "Số phiếu", "Máy đã bán", "Tỷ lệ hỏng (%)", "Lỗi hay gặp"), m);
        List<List<Object>> pt = new ArrayList<>();
        for (Row row : (List<Row>) r.get("parts"))
            pt.add(List.of(row.str("code"), row.str("name"), row.lng("qty"), row.lng("tickets"), row.lng("cost"), row.str("models")));
        x.sheet("Linh kiện thay nhiều", List.of("Mã", "Linh kiện", "Số lượng", "Số phiếu", "Giá vốn (đ)", "Dùng cho model"), pt);
        List<List<Object>> rp = new ArrayList<>();
        for (Row row : (List<Row>) r.get("repeats"))
            rp.add(List.of(row.str("serial"), row.str("product"), row.str("customer"), row.str("phone"), row.lng("times"), row.str("codes"), row.str("issues")));
        x.sheet("Máy sửa nhiều lần", List.of("Serial", "Sản phẩm", "Khách hàng", "Điện thoại", "Số lần", "Các phiếu", "Lỗi qua các lần"), rp);
    }
}
