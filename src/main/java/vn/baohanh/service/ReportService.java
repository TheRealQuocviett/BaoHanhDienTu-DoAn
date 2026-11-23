package vn.baohanh.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.model.User;
import vn.baohanh.util.Xlsx;

/**
 * Báo cáo thống kê theo khoảng thời gian [from, to].
 * Phiếu được tính theo ngày nhận máy; doanh thu theo ngày trả máy.
 */
public class ReportService {
    private final Database db;

    public ReportService(Database db) {
        this.db = db;
    }

    /** Khoảng thời gian: mặc định 12 tháng gần nhất. */
    public record Period(LocalDate from, LocalDate to) {
        String fromTs() { return from + "T00:00:00"; }
        String toTs() { return to + "T23:59:59"; }
    }

    public static Period period(String from, String to) {
        LocalDate t;
        LocalDate f;
        try {
            t = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to.trim());
            f = from == null || from.isBlank() ? YearMonth.from(t).minusMonths(11).atDay(1) : LocalDate.parse(from.trim());
        } catch (DateTimeParseException e) {
            throw new AppException("Khoảng thời gian không hợp lệ");
        }
        if (f.isAfter(t)) throw new AppException("Ngày bắt đầu phải trước ngày kết thúc");
        if (f.plusYears(5).isBefore(t)) throw new AppException("Khoảng thời gian tối đa 5 năm");
        return new Period(f, t);
    }

    /** Toàn bộ báo cáo trong một lần gọi (giao diện hiển thị nhiều bảng/biểu đồ). */
    public Row full(User u, Period p) throws SQLException {
        Auth.require(u, Role.ADMIN);
        Row r = new Row();
        r.put("from", p.from().toString());
        r.put("to", p.to().toString());
        r.put("summary", summary(p));
        r.put("monthly", monthly(p));
        r.put("by_brand", byBrand(p));
        r.put("top_models", topModels(p));
        r.put("by_technician", byTechnician(p));
        r.put("parts_usage", partsUsage(p));
        r.put("by_status", byStatus(p));
        r.put("reviews", reviews(p));
        return r;
    }

    /** Đánh giá của khách trong kỳ (theo ngày khách đánh giá). */
    private Row reviews(Period p) throws SQLException {
        Row out = new Row();
        Row s = db.one("SELECT COUNT(*) AS n, COALESCE(AVG(stars), 0) AS avg FROM ticket_review WHERE created_at BETWEEN ? AND ?", p.fromTs(), p.toTs());
        out.put("count", s.lng("n"));
        out.put("avg", Math.round(((Number) s.get("avg")).doubleValue() * 10) / 10.0);
        List<Long> dist = new ArrayList<>();
        for (int k = 5; k >= 1; k--)
            dist.add(db.scalar("SELECT COUNT(*) FROM ticket_review WHERE stars = ? AND created_at BETWEEN ? AND ?", k, p.fromTs(), p.toTs()));
        out.put("dist", dist);                                     // [5 sao, 4 sao, … 1 sao]
        long returned = db.scalar("SELECT COUNT(*) FROM ticket WHERE status = 'RETURNED' AND returned_at BETWEEN ? AND ?", p.fromTs(), p.toTs());
        long reviewed = db.scalar("SELECT COUNT(*) FROM ticket_review rv JOIN ticket t ON t.id = rv.ticket_id "
                + "WHERE t.returned_at BETWEEN ? AND ?", p.fromTs(), p.toTs());
        out.put("response_rate", returned == 0 ? 0 : Math.round(reviewed * 1000.0 / returned) / 10.0);
        List<Row> tech = db.query("SELECT COALESCE(us.full_name, 'Chưa phân công') AS technician, COUNT(*) AS n, ROUND(AVG(rv.stars), 1) AS avg "
                + "FROM ticket_review rv JOIN ticket t ON t.id = rv.ticket_id LEFT JOIN app_user us ON us.id = t.technician_id "
                + "WHERE rv.created_at BETWEEN ? AND ? GROUP BY t.technician_id ORDER BY avg DESC, n DESC", p.fromTs(), p.toTs());
        for (Row t : tech) t.put("avg", ((Number) t.get("avg")).doubleValue());
        out.put("by_technician", tech);
        out.put("recent", db.query("SELECT rv.stars, COALESCE(rv.comment, '') AS comment, rv.created_at, t.code, cu.name AS customer, "
                + "p.name AS product, COALESCE(us.full_name, '') AS technician FROM ticket_review rv JOIN ticket t ON t.id = rv.ticket_id "
                + "JOIN warranty w ON w.id = t.warranty_id JOIN customer cu ON cu.id = w.customer_id JOIN product p ON p.id = w.product_id "
                + "LEFT JOIN app_user us ON us.id = t.technician_id WHERE rv.created_at BETWEEN ? AND ? ORDER BY rv.created_at DESC LIMIT 50",
                p.fromTs(), p.toTs()));
        return out;
    }

    private Row summary(Period p) throws SQLException {
        Row s = db.one("SELECT COUNT(*) AS received, "
                + "SUM(CASE WHEN in_warranty = 1 THEN 1 ELSE 0 END) AS in_warranty, "
                + "SUM(CASE WHEN status = 'REJECTED' OR (status = 'RETURNED' AND id IN (SELECT ticket_id FROM ticket_history WHERE status = 'REJECTED')) THEN 1 ELSE 0 END) AS rejected "
                + "FROM ticket WHERE received_at BETWEEN ? AND ?", p.fromTs(), p.toTs());
        Row m = db.one("SELECT COUNT(*) AS returned, COALESCE(SUM(paid_amount), 0) AS revenue, "
                + "COALESCE(AVG(julianday(returned_at) - julianday(received_at)), 0) AS avg_days "
                + "FROM ticket WHERE status = 'RETURNED' AND returned_at BETWEEN ? AND ?", p.fromTs(), p.toTs());
        Row c = db.one("SELECT COALESCE(SUM(t.labor_cost), 0) AS labor, "
                + "COALESCE(SUM((SELECT SUM(tp.qty * tp.unit_price) FROM ticket_part tp WHERE tp.ticket_id = t.id AND tp.charged = 1)), 0) AS parts_revenue, "
                + "COALESCE(SUM((SELECT SUM(tp.qty * pt.cost_price) FROM ticket_part tp JOIN part pt ON pt.id = tp.part_id WHERE tp.ticket_id = t.id)), 0) AS parts_cost "
                + "FROM ticket t WHERE t.status = 'RETURNED' AND t.returned_at BETWEEN ? AND ?", p.fromTs(), p.toTs());
        Row out = new Row();
        out.putAll(s);
        out.putAll(m);
        out.putAll(c);
        out.put("avg_days", Math.round(((Number) m.get("avg_days")).doubleValue() * 10) / 10.0);
        out.put("warranties_sold", db.scalar("SELECT COUNT(*) FROM warranty WHERE purchase_date BETWEEN ? AND ?", p.from(), p.to()));
        Row ext = db.one("SELECT COUNT(*) AS n, COALESCE(SUM(price), 0) AS total FROM warranty_extension WHERE created_at BETWEEN ? AND ?",
                p.fromTs(), p.toTs());
        out.put("extensions_sold", ext.lng("n"));
        out.put("extension_revenue", ext.lng("total"));
        out.put("requests_online", db.scalar("SELECT COUNT(*) FROM service_request WHERE created_at BETWEEN ? AND ?", p.fromTs(), p.toTs()));
        for (String k : new String[]{"received", "in_warranty", "rejected"}) if (out.get(k) == null) out.put(k, 0L);
        return out;
    }

    private List<Row> monthly(Period p) throws SQLException {
        List<Row> out = new ArrayList<>();
        YearMonth end = YearMonth.from(p.to());
        for (YearMonth ym = YearMonth.from(p.from()); !ym.isAfter(end); ym = ym.plusMonths(1)) {
            String key = ym.toString();
            Row r = new Row();
            r.put("month", key);
            r.put("label", String.format("%02d/%d", ym.getMonthValue(), ym.getYear()));
            r.put("received", db.scalar("SELECT COUNT(*) FROM ticket WHERE substr(received_at, 1, 7) = ?", key));
            r.put("returned", db.scalar("SELECT COUNT(*) FROM ticket WHERE status = 'RETURNED' AND substr(returned_at, 1, 7) = ?", key));
            r.put("revenue", db.scalar("SELECT COALESCE(SUM(paid_amount), 0) FROM ticket WHERE status = 'RETURNED' AND substr(returned_at, 1, 7) = ?", key));
            r.put("activated", db.scalar("SELECT COUNT(*) FROM warranty WHERE substr(purchase_date, 1, 7) = ?", key));
            r.put("ext_revenue", db.scalar("SELECT COALESCE(SUM(price), 0) FROM warranty_extension WHERE substr(created_at, 1, 7) = ?", key));
            out.add(r);
        }
        return out;
    }

    /** Tỷ lệ máy phải bảo hành theo hãng = số phiếu / số máy đã bán (kích hoạt). */
    private List<Row> byBrand(Period p) throws SQLException {
        List<Row> rows = db.query("SELECT b.name AS brand, "
                + "(SELECT COUNT(*) FROM warranty w JOIN product pr ON pr.id = w.product_id WHERE pr.brand_id = b.id) AS sold, "
                + "(SELECT COUNT(*) FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN product pr ON pr.id = w.product_id "
                + "  WHERE pr.brand_id = b.id AND t.received_at BETWEEN ? AND ?) AS tickets "
                + "FROM brand b ORDER BY tickets DESC, b.name", p.fromTs(), p.toTs());
        rows.removeIf(r -> r.lng("sold") == 0 && r.lng("tickets") == 0);
        for (Row r : rows) r.put("rate", r.lng("sold") == 0 ? 0.0 : Math.round(r.lng("tickets") * 1000.0 / r.lng("sold")) / 10.0);
        return rows;
    }

    private List<Row> topModels(Period p) throws SQLException {
        return db.query("SELECT pr.name AS product, pr.model_code, b.name AS brand, COUNT(*) AS tickets "
                + "FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN product pr ON pr.id = w.product_id "
                + "JOIN brand b ON b.id = pr.brand_id WHERE t.received_at BETWEEN ? AND ? "
                + "GROUP BY pr.id ORDER BY tickets DESC, pr.name LIMIT 10", p.fromTs(), p.toTs());
    }

    private List<Row> byTechnician(Period p) throws SQLException {
        List<Row> rows = db.query("SELECT u.full_name AS technician, "
                + "(SELECT COUNT(*) FROM ticket t WHERE t.technician_id = u.id AND t.received_at BETWEEN ? AND ?) AS assigned, "
                + "(SELECT COUNT(*) FROM ticket t WHERE t.technician_id = u.id AND t.status = 'RETURNED' AND t.returned_at BETWEEN ? AND ?) AS completed, "
                + "(SELECT COUNT(*) FROM ticket t WHERE t.technician_id = u.id AND t.status NOT IN " + TicketStatus.CLOSED_SQL + ") AS in_progress, "
                + "(SELECT AVG(julianday(t.returned_at) - julianday(t.received_at)) FROM ticket t WHERE t.technician_id = u.id "
                + "  AND t.status = 'RETURNED' AND t.returned_at BETWEEN ? AND ?) AS avg_days "
                + "FROM app_user u WHERE u.role = 'TECHNICIAN' ORDER BY completed DESC, u.full_name",
                p.fromTs(), p.toTs(), p.fromTs(), p.toTs(), p.fromTs(), p.toTs());
        for (Row r : rows) {
            Object a = r.get("avg_days");
            r.put("avg_days", a == null ? null : Math.round(((Number) a).doubleValue() * 10) / 10.0);
        }
        return rows;
    }

    private List<Row> partsUsage(Period p) throws SQLException {
        return db.query("SELECT pt.code, pt.name, SUM(tp.qty) AS qty, "
                + "SUM(CASE WHEN tp.charged = 1 THEN tp.qty * tp.unit_price ELSE 0 END) AS charged_value, "
                + "SUM(CASE WHEN tp.charged = 0 THEN tp.qty * tp.unit_price ELSE 0 END) AS free_value "
                + "FROM ticket_part tp JOIN part pt ON pt.id = tp.part_id JOIN ticket t ON t.id = tp.ticket_id "
                + "WHERE t.received_at BETWEEN ? AND ? GROUP BY pt.id ORDER BY qty DESC LIMIT 15", p.fromTs(), p.toTs());
    }

    private List<Row> byStatus(Period p) throws SQLException {
        List<Row> rows = new ArrayList<>();
        for (TicketStatus s : TicketStatus.values()) {
            Row r = new Row();
            r.put("status", s.name());
            r.put("label", s.getLabel());
            r.put("count", db.scalar("SELECT COUNT(*) FROM ticket WHERE status = ? AND received_at BETWEEN ? AND ?", s.name(), p.fromTs(), p.toTs()));
            rows.add(r);
        }
        return rows;
    }

    // ===================== XUẤT EXCEL =====================

    @SuppressWarnings("unchecked")
    public byte[] exportXlsx(User u, Period p) throws SQLException {
        Row r = full(u, p);
        Xlsx x = new Xlsx();
        Row s = (Row) r.get("summary");
        x.sheet("Tổng hợp", List.of("Chỉ tiêu", "Giá trị"), List.of(
                List.of("Từ ngày", p.from().toString()), List.of("Đến ngày", p.to().toString()),
                List.of("Máy kích hoạt bảo hành", s.lng("warranties_sold")),
                List.of("Phiếu tiếp nhận", s.lng("received")),
                List.of("  trong đó: còn bảo hành", s.lng("in_warranty")),
                List.of("  trong đó: từ chối bảo hành", s.lng("rejected")),
                List.of("Phiếu đã trả khách", s.lng("returned")),
                List.of("Doanh thu dịch vụ (đ)", s.lng("revenue")),
                List.of("  tiền công (đ)", s.lng("labor")),
                List.of("  tiền linh kiện (đ)", s.lng("parts_revenue")),
                List.of("Giá vốn linh kiện đã dùng (đ)", s.lng("parts_cost")),
                List.of("Gói bảo hành mở rộng đã bán", s.lng("extensions_sold")),
                List.of("Doanh thu gói bảo hành mở rộng (đ)", s.lng("extension_revenue")),
                List.of("Yêu cầu bảo hành online", s.lng("requests_online")),
                List.of("Thời gian xử lý trung bình (ngày)", ((Number) s.get("avg_days")).doubleValue())));
        x.sheet("Theo tháng", List.of("Tháng", "Máy kích hoạt", "Phiếu nhận", "Phiếu trả", "Doanh thu sửa chữa (đ)", "Doanh thu gói BH (đ)"),
                rows((List<Row>) r.get("monthly"), "label", "activated", "received", "returned", "revenue", "ext_revenue"));
        x.sheet("Theo hãng", List.of("Hãng", "Máy đã bán", "Số phiếu", "Tỷ lệ (%)"),
                rows((List<Row>) r.get("by_brand"), "brand", "sold", "tickets", "rate"));
        x.sheet("Model lỗi nhiều", List.of("Sản phẩm", "Mã model", "Hãng", "Số phiếu"),
                rows((List<Row>) r.get("top_models"), "product", "model_code", "brand", "tickets"));
        x.sheet("Kỹ thuật viên", List.of("Kỹ thuật viên", "Được giao", "Hoàn thành", "Đang xử lý", "TB ngày xử lý"),
                rows((List<Row>) r.get("by_technician"), "technician", "assigned", "completed", "in_progress", "avg_days"));
        x.sheet("Linh kiện", List.of("Mã", "Tên linh kiện", "Số lượng", "Thu khách (đ)", "Miễn phí BH (đ)"),
                rows((List<Row>) r.get("parts_usage"), "code", "name", "qty", "charged_value", "free_value"));
        Row rv = (Row) r.get("reviews");
        List<List<Object>> rr = new ArrayList<>();
        rr.add(List.of("Tổng số đánh giá", rv.lng("count"), "", "", "", "", ""));
        rr.add(List.of("Điểm trung bình (sao)", ((Number) rv.get("avg")).doubleValue(), "", "", "", "", ""));
        rr.add(List.of("", "", "", "", "", "", ""));
        for (Row x1 : (List<Row>) rv.get("recent"))
            rr.add(List.of(x1.str("created_at").replace('T', ' '), x1.lng("stars"), x1.str("code"), x1.str("customer"), x1.str("product"),
                    x1.str("technician"), x1.str("comment")));
        x.sheet("Đánh giá", List.of("Thời gian", "Số sao", "Mã phiếu", "Khách hàng", "Sản phẩm", "Kỹ thuật viên", "Nhận xét"), rr);
        return x.toBytes();
    }

    private static List<List<Object>> rows(List<Row> src, String... cols) {
        List<List<Object>> out = new ArrayList<>();
        for (Row r : src) {
            List<Object> line = new ArrayList<>();
            for (String c : cols) line.add(r.get(c));
            out.add(line);
        }
        return out;
    }

    /** Dùng cho tên file xuất. */
    public static String fileName(Period p) {
        return "bao-cao-bao-hanh_" + p.from() + "_" + p.to() + ".xlsx";
    }
}
