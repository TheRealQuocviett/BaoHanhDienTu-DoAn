package vn.baohanh.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.User;
import vn.baohanh.service.AppContext;
import vn.baohanh.service.Auth;
import vn.baohanh.service.ReportService;
import vn.baohanh.service.SeedData;

/**
 * THỬ TẢI: tạo một CSDL tạm với N phiếu giả (mặc định 50.000) rồi đo thời gian các thao tác chính,
 * hai lần: (A) chưa có các chỉ mục bổ sung ở phiên bản 10, (B) đã có. Kết quả ghi ra file Markdown để đưa vào báo cáo.
 *
 * <pre>
 * Chạy:  thu-tai.bat            (Windows)   hoặc   sh thu-tai.sh
 *        thu-tai.bat 100000     (số phiếu tùy chọn)
 * </pre>
 * Không đụng tới dữ liệu thật trong thư mục data/ – mọi thứ nằm trong thư mục tạm và bị xóa khi xong.
 */
public final class ThuTai {
    /** Các chỉ mục thêm ở phiên bản 10 (trùng với database/schema.sql). */
    static final String[][] NEW_INDEXES = {
        {"idx_ticket_part_ticket", "ticket_part(ticket_id)", "Linh kiện của từng phiếu (dùng trong view tính chi phí phiếu)"},
        {"idx_ticket_received", "ticket(received_at, id)", "Sắp xếp danh sách phiếu mới nhất trước → đọc đúng 1 trang"},
        {"idx_ticket_tech", "ticket(technician_id, status)", "Lọc \"Phiếu của tôi\" cho kỹ thuật viên"},
        {"idx_ticket_due", "ticket(due_date)", "Lọc phiếu quá hẹn / sắp đến hẹn"},
        {"idx_warranty_created", "warranty(created_at, id)", "Sắp xếp danh sách bảo hành theo trang"},
        {"idx_stock_ticket", "stock_movement(ticket_id)", "Thẻ kho theo phiếu"},
        {"idx_request_customer", "service_request(customer_id)", "Yêu cầu online của từng khách"},
    };

    private record Op(String group, String name, Task task) {}
    @FunctionalInterface private interface Task { Object run() throws Exception; }
    private record Result(double medianMs, double minMs, int runs, String size) {}

    private final Database db;
    private final AppContext app;
    private final User admin, tech;
    private final int laps;
    private String sampleSerial, samplePhone, sampleName;
    private long sampleTicket;

    private ThuTai(Database db, int laps) throws SQLException {
        this.db = db;
        this.app = new AppContext(db, 0);
        this.laps = laps;
        if (db.isFresh()) SeedData.run(app);
        admin = Auth.toUser(db.one("SELECT * FROM app_user WHERE role = 'ADMIN' ORDER BY id LIMIT 1"));
        tech = Auth.toUser(db.one("SELECT * FROM app_user WHERE role = 'TECHNICIAN' ORDER BY id LIMIT 1"));
    }

    public static void main(String[] args) throws Exception {
        int n = 50_000, laps = 5;
        Path out = Path.of("docs", "thu-tai-ket-qua.md");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out") && i + 1 < args.length) out = Path.of(args[++i]);
            else if (args[i].equals("--lap") && i + 1 < args.length) laps = Integer.parseInt(args[++i]);
            else if (args[i].matches("\\d+")) n = Integer.parseInt(args[i]);
        }
        Path schema = Path.of("database", "schema.sql");
        if (!Files.exists(schema)) { System.err.println("Hay chay trong thu muc BaoHanhDienTu (co database/schema.sql)"); System.exit(1); }
        Path tmp = Files.createTempDirectory("thu-tai-");
        try (Database db = new Database(tmp.resolve("baohanh.db"), schema)) {
            ThuTai t = new ThuTai(db, laps);
            String md = t.run(n);
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, md, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println(md);
            System.out.println("==> Da ghi ket qua vao " + out.toAbsolutePath());
        } finally {
            try (Stream<Path> w = Files.walk(tmp)) {
                for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (IOException ignore) { }
        }
    }

    private String run(int n) throws Exception {
        log("Tao " + fmt(n) + " phieu gia...");
        long t0 = System.nanoTime();
        Map<String, Long> counts = generate(n);
        double genSec = (System.nanoTime() - t0) / 1e9;
        log(String.format("Xong sau %.1f giay: %s", genSec, counts));
        pickSamples();
        List<Op> ops = operations();

        dropNewIndexes();
        db.update("ANALYZE");
        log("Do lan A (chua co chi muc moi)...");
        Map<String, Result> before = measure(ops);
        String planBefore = plan();

        createNewIndexes();
        db.update("ANALYZE");
        log("Do lan B (da co chi muc moi)...");
        Map<String, Result> after = measure(ops);
        String planAfter = plan();

        return report(n, counts, genSec, ops, before, after, planBefore, planAfter);
    }

    // =====================================================================
    //  Tạo dữ liệu giả bằng câu lệnh SQL đệ quy (nhanh: vài giây cho 50.000 phiếu)
    // =====================================================================

    private Map<String, Long> generate(int n) throws SQLException {
        int customers = Math.max(10, n / 2), warranties = Math.max(10, n * 4 / 5);
        db.tx(() -> {
            long c0 = db.scalar("SELECT COALESCE(MAX(id), 0) FROM customer");
            db.update("WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < ?) "
                    + "INSERT INTO customer (name, phone, email, address, created_at) SELECT "
                    + "CASE i % 8 WHEN 0 THEN 'Nguyễn' WHEN 1 THEN 'Trần' WHEN 2 THEN 'Lê' WHEN 3 THEN 'Phạm' WHEN 4 THEN 'Hoàng' "
                    + "WHEN 5 THEN 'Võ' WHEN 6 THEN 'Đặng' ELSE 'Bùi' END || ' ' || "
                    + "CASE i % 5 WHEN 0 THEN 'Văn' WHEN 1 THEN 'Thị' WHEN 2 THEN 'Minh' WHEN 3 THEN 'Ngọc' ELSE 'Hữu' END || ' ' || "
                    + "CASE i % 9 WHEN 0 THEN 'An' WHEN 1 THEN 'Bình' WHEN 2 THEN 'Châu' WHEN 3 THEN 'Dũng' WHEN 4 THEN 'Hà' "
                    + "WHEN 5 THEN 'Khoa' WHEN 6 THEN 'Lan' WHEN 7 THEN 'Phúc' ELSE 'Tâm' END || ' ' || i, "
                    + "'07' || printf('%08d', i), CASE WHEN i % 3 = 0 THEN 'khach' || i || '@example.com' END, "
                    + "(i % 300 + 1) || ' Đường Thử Tải, TP. Hồ Chí Minh', strftime('%Y-%m-%dT%H:%M:%S', 'now', '-' || (i % 1000) || ' days') FROM s",
                    customers);
            long w0 = db.scalar("SELECT COALESCE(MAX(id), 0) FROM warranty");
            db.update("WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < ?), "
                    + "pc(c) AS (SELECT COUNT(*) FROM product), pr AS (SELECT id, ROW_NUMBER() OVER (ORDER BY id) - 1 AS rn FROM product), "
                    + "d AS (SELECT i, date('now', '-' || ((? - i) * 1095 / ?) || ' days') AS pd FROM s) "
                    + "INSERT INTO warranty (code, serial, product_id, customer_id, invoice_no, purchase_date, months, expiry_date, created_at) "
                    + "SELECT 'BHT-' || printf('%07d', i), 'TT' || printf('%09d', i * 7 + 3), "
                    + "(SELECT id FROM pr WHERE rn = (i * 7919) % (SELECT c FROM pc)), "
                    + "? + 1 + ((i * 104729) % ?), 'HDT' || i, pd, 12, date(pd, '+12 months'), pd || 'T10:00:00' FROM d",
                    warranties, warranties, warranties, c0, customers);
            db.update("WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM s WHERE i < ?), "
                    + "tc(c) AS (SELECT COUNT(*) FROM app_user WHERE role = 'TECHNICIAN'), "
                    + "tr AS (SELECT id, ROW_NUMBER() OVER (ORDER BY id) - 1 AS rn FROM app_user WHERE role = 'TECHNICIAN'), "
                    + "d AS (SELECT i, strftime('%Y-%m-%dT%H:%M:%S', 'now', '-' || ((? - i) * 730 / ?) || ' days', '-' || (i % 600) || ' minutes') AS ra, "
                    + "CASE WHEN i > ? - 400 THEN CASE i % 6 WHEN 0 THEN 'RECEIVED' WHEN 1 THEN 'DIAGNOSING' WHEN 2 THEN 'REPAIRING' "
                    + "WHEN 3 THEN 'WAITING_PARTS' WHEN 4 THEN 'READY' ELSE 'SENT_TO_VENDOR' END "
                    + "ELSE CASE i % 25 WHEN 0 THEN 'CANCELLED' WHEN 1 THEN 'REJECTED' ELSE 'RETURNED' END END AS st, "
                    + "CASE WHEN i % 3 = 0 THEN 0 ELSE 1 END AS iw FROM s) "
                    + "INSERT INTO ticket (code, warranty_id, issue, in_warranty, received_at, received_by, due_date, status, technician_id, "
                    + "diagnosis, labor_cost, paid_amount, payment_method, returned_at) "
                    + "SELECT 'PBT-' || printf('%07d', i), ? + 1 + ((i * 7919) % ?), "
                    + "CASE i % 8 WHEN 0 THEN 'Máy không lên nguồn' WHEN 1 THEN 'Màn hình bị sọc, chớp' WHEN 2 THEN 'Pin chai, sụt nhanh' "
                    + "WHEN 3 THEN 'Không sạc được' WHEN 4 THEN 'Loa rè, mất tiếng' WHEN 5 THEN 'Camera bị mờ' WHEN 6 THEN 'Máy nóng, tự tắt' "
                    + "ELSE 'Cảm ứng không nhạy' END, iw, ra, ?, date(ra, '+5 days'), st, "
                    + "(SELECT id FROM tr WHERE rn = i % (SELECT c FROM tc)), "
                    + "CASE WHEN st = 'RETURNED' THEN CASE i % 6 WHEN 0 THEN 'Thay màn hình' WHEN 1 THEN 'Thay pin' WHEN 2 THEN 'Thay IC sạc' "
                    + "WHEN 3 THEN 'Vệ sinh, hàn lại chân loa' WHEN 4 THEN 'Thay cụm camera' ELSE 'Cài lại phần mềm' END END, "
                    + "CASE iw WHEN 1 THEN 0 ELSE 150000 + (i % 5) * 50000 END, "
                    + "CASE WHEN st = 'RETURNED' AND iw = 0 THEN 150000 + (i % 5) * 50000 ELSE 0 END, "
                    + "CASE WHEN st = 'RETURNED' AND iw = 0 THEN CASE i % 2 WHEN 0 THEN 'CASH' ELSE 'TRANSFER' END END, "
                    + "CASE WHEN st IN ('RETURNED','CANCELLED','REJECTED') THEN strftime('%Y-%m-%dT%H:%M:%S', ra, '+3 days') END FROM d",
                    n, n, n, n, w0, warranties, admin.id());
            long tMin = db.scalar("SELECT MIN(id) FROM ticket WHERE code LIKE 'PBT-%'");
            // Linh kiện: nửa số phiếu đã trả máy có thay 1 linh kiện
            db.update("WITH pc(c) AS (SELECT COUNT(*) FROM part), pr AS (SELECT id, sell_price, ROW_NUMBER() OVER (ORDER BY id) - 1 AS rn FROM part) "
                    + "INSERT INTO ticket_part (ticket_id, part_id, qty, unit_price, charged) "
                    + "SELECT t.id, pr.id, 1, pr.sell_price, 1 - t.in_warranty "
                    + "FROM ticket t JOIN pr ON pr.rn = (t.id * 31) % (SELECT c FROM pc) WHERE t.id >= ? AND t.status = 'RETURNED' AND t.id % 2 = 0", tMin);
            db.update("INSERT INTO ticket_history (ticket_id, time, status, note, user_id) "
                    + "SELECT id, received_at, 'RECEIVED', 'Tiếp nhận máy', received_by FROM ticket WHERE id >= ?", tMin);
            db.update("INSERT INTO ticket_history (ticket_id, time, status, note, user_id) "
                    + "SELECT id, returned_at, status, NULL, technician_id FROM ticket WHERE id >= ? AND returned_at IS NOT NULL", tMin);
            return null;
        });
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : new String[]{"customer", "warranty", "ticket", "ticket_part", "ticket_history"})
            m.put(t, db.scalar("SELECT COUNT(*) FROM " + t));
        return m;
    }

    private void pickSamples() throws SQLException {
        Row w = db.one("SELECT w.serial, c.phone, c.name FROM warranty w JOIN customer c ON c.id = w.customer_id "
                + "WHERE w.code LIKE 'BHT-%' ORDER BY w.id LIMIT 1 OFFSET (SELECT COUNT(*) / 3 FROM warranty WHERE code LIKE 'BHT-%')");
        sampleSerial = w.str("serial");
        samplePhone = w.str("phone");
        sampleName = w.str("name");
        sampleTicket = db.scalar("SELECT id FROM ticket WHERE code LIKE 'PBT-%' ORDER BY id LIMIT 1 OFFSET (SELECT COUNT(*) / 2 FROM ticket WHERE code LIKE 'PBT-%')");
    }

    private List<Op> operations() {
        String y = String.valueOf(LocalDate.now().getYear());
        List<Op> o = new ArrayList<>();
        o.add(new Op("Danh sách phiếu", "Tải TẤT CẢ phiếu một lần (cách cũ)", () -> app.warranty.listTickets(admin, "", "").size()));
        o.add(new Op("Danh sách phiếu", "Trang 1 – 50 phiếu mới nhất (phân trang)", () -> rows(app.warranty.pageTickets(admin, "", "", 1, 50))));
        o.add(new Op("Danh sách phiếu", "Trang 500 – giữa danh sách (phân trang)", () -> rows(app.warranty.pageTickets(admin, "", "", 500, 50))));
        o.add(new Op("Danh sách phiếu", "Lọc \"Đang xử lý\"", () -> rows(app.warranty.pageTickets(admin, "OPEN", "", 1, 50))));
        o.add(new Op("Danh sách phiếu", "Lọc \"Quá hẹn\"", () -> rows(app.warranty.pageTickets(admin, "OVERDUE", "", 1, 50))));
        o.add(new Op("Danh sách phiếu", "\"Phiếu của tôi\" (kỹ thuật viên)", () -> rows(app.warranty.pageTickets(tech, "MINE", "", 1, 50))));
        o.add(new Op("Danh sách phiếu", "Tìm theo SĐT khách", () -> rows(app.warranty.pageTickets(admin, "", samplePhone, 1, 50))));
        o.add(new Op("Danh sách phiếu", "Tìm theo tên khách (không dấu)", () -> rows(app.warranty.pageTickets(admin, "", vn.baohanh.service.CatalogService.norm(sampleName), 1, 50))));
        o.add(new Op("Phiếu", "Mở chi tiết 1 phiếu", () -> app.warranty.getTicket(admin, sampleTicket).str("code")));
        o.add(new Op("Bảo hành", "Tải TẤT CẢ bảo hành (cách cũ)", () -> app.warranty.listWarranties(admin, "", "").size()));
        o.add(new Op("Bảo hành", "Trang 1 – 50 bảo hành (phân trang)", () -> rows(app.warranty.pageWarranties(admin, "", "", 1, 50))));
        o.add(new Op("Bảo hành", "Tra cứu theo serial (quầy)", () -> app.warranty.bySerial(admin, sampleSerial) == null ? 0 : 1));
        o.add(new Op("Bảo hành", "Cổng khách: tra cứu serial", () -> app.portal.lookup(sampleSerial, "127.0.0." + (int) (Math.random() * 200)).bool("found") ? 1 : 0));
        o.add(new Op("Khách hàng", "Trang 1 danh sách khách (phân trang)", () -> rows(app.catalog.page(admin, "customer", "", 1, 50))));
        o.add(new Op("Khách hàng", "Tải TẤT CẢ khách (cách cũ)", () -> app.catalog.list(admin, "customer", "").size()));
        o.add(new Op("Tổng quan", "Bảng điều khiển (trang chủ)", () -> app.warranty.dashboard(admin).size()));
        o.add(new Op("Báo cáo", "Báo cáo cả năm " + y, () -> app.reports.full(admin, ReportService.period(y + "-01-01", y + "-12-31")).size()));
        o.add(new Op("Báo cáo", "Thống kê lỗi thường gặp (2 năm)", () -> app.faults.report(admin,
                ReportService.period(LocalDate.now().minusYears(2).toString(), LocalDate.now().toString())).size()));
        o.add(new Op("Thông minh", "Gợi ý chẩn đoán", () -> app.diagnosis.suggest(admin, Map.<String, Object>of("serial", sampleSerial, "issue", "Máy không lên nguồn")).size()));
        o.add(new Op("Thông minh", "Dự báo linh kiện cần đặt", () -> app.purchases.forecast(admin).size()));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Object rows(Row page) {
        return ((List<Row>) page.get("rows")).size() + "/" + page.lng("total");
    }

    private Map<String, Result> measure(List<Op> ops) throws Exception {
        Map<String, Result> m = new LinkedHashMap<>();
        for (Op op : ops) {
            List<Double> times = new ArrayList<>();
            Object size = null;
            long deadline = System.nanoTime() + 20_000_000_000L;   // mỗi thao tác đo tối đa ~20 giây
            for (int i = 0; i <= laps; i++) {
                long t = System.nanoTime();
                size = op.task().run();
                double ms = (System.nanoTime() - t) / 1e6;
                if (i > 0 || ms > 1500) times.add(ms);              // lần đầu để "làm nóng", trừ khi đã rất chậm
                if (System.nanoTime() > deadline && !times.isEmpty()) break;
            }
            double[] a = times.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            Result r = new Result(a[a.length / 2], a[0], a.length, String.valueOf(size));
            m.put(op.name(), r);
            log(String.format("  %-48s %9.1f ms  (%s)", op.name(), r.medianMs(), r.size()));
        }
        return m;
    }

    private void dropNewIndexes() throws SQLException {
        for (String[] ix : NEW_INDEXES) db.update("DROP INDEX IF EXISTS " + ix[0]);
    }

    private void createNewIndexes() throws SQLException {
        for (String[] ix : NEW_INDEXES) db.update("CREATE INDEX IF NOT EXISTS " + ix[0] + " ON " + ix[1]);
    }

    private String plan() throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (Row r : db.query("EXPLAIN QUERY PLAN SELECT t.id FROM ticket t JOIN warranty w ON w.id = t.warranty_id "
                + "ORDER BY t.received_at DESC, t.id DESC LIMIT 50"))
            sb.append(r.str("detail")).append("; ");
        return sb.toString().replaceAll("; $", "");
    }

    // =====================================================================
    //  Báo cáo
    // =====================================================================

    private String report(int n, Map<String, Long> counts, double genSec, List<Op> ops,
                          Map<String, Result> before, Map<String, Result> after, String planBefore, String planAfter) throws Exception {
        StringBuilder s = new StringBuilder();
        s.append("# Kết quả thử tải – ").append(fmt(n)).append(" phiếu bảo hành\n\n");
        s.append("Đo lúc ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))).append(" bằng công cụ `vn.baohanh.tools.ThuTai` ")
         .append("(chạy `thu-tai.bat`). Mỗi thao tác chạy 1 lần làm nóng rồi đo ").append(laps).append(" lần, lấy **trung vị**.\n\n");
        s.append("## Môi trường\n\n| Mục | Giá trị |\n|---|---|\n");
        s.append("| Hệ điều hành | ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(" |\n");
        s.append("| Java | ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(") |\n");
        s.append("| Số nhân CPU | ").append(Runtime.getRuntime().availableProcessors()).append(" |\n");
        s.append("| Bộ nhớ tối đa JVM | ").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(" MB |\n");
        s.append("| SQLite | ").append(db.one("SELECT sqlite_version() AS v").str("v")).append(" |\n");
        s.append("| Dung lượng CSDL | ").append(String.format("%.1f MB", Files.size(db.getFile()) / 1024.0 / 1024)).append(" |\n");
        s.append("| Thời gian tạo dữ liệu giả | ").append(String.format("%.1f giây", genSec)).append(" |\n\n");
        s.append("## Dữ liệu thử\n\n| Bảng | Số dòng |\n|---|---:|\n");
        Map<String, String> label = Map.of("customer", "Khách hàng", "warranty", "Bảo hành", "ticket", "Phiếu bảo hành",
                "ticket_part", "Linh kiện đã thay", "ticket_history", "Lịch sử xử lý");
        for (var e : counts.entrySet()) s.append("| ").append(label.get(e.getKey())).append(" (`").append(e.getKey()).append("`) | ").append(fmt(e.getValue())).append(" |\n");
        s.append("\n## Thời gian phản hồi (mili giây)\n\n");
        s.append("A = chưa có 7 chỉ mục mới · B = đã thêm chỉ mục (phiên bản 10). Dưới 100 ms người dùng thấy như tức thì; trên 1 giây là thấy chậm.\n\n");
        s.append("| Nhóm | Thao tác | A: chưa chỉ mục | B: có chỉ mục | Nhanh hơn | Kết quả |\n|---|---|---:|---:|---:|---|\n");
        for (Op op : ops) {
            Result a = before.get(op.name()), b = after.get(op.name());
            double x = a.medianMs() / Math.max(0.01, b.medianMs());
            s.append("| ").append(op.group()).append(" | ").append(op.name()).append(" | ").append(ms(a.medianMs())).append(" | ")
             .append(ms(b.medianMs())).append(" | ").append(x >= 1.15 ? "**×" + times(x) + "**" : "≈").append(" | ").append(b.size()).append(" |\n");
        }
        Result allOld = after.get(ops.get(0).name()), page1 = after.get(ops.get(1).name()), worst = before.get(ops.get(0).name());
        s.append("\n## Nhận xét\n\n");
        s.append(String.format("- **Tổng hợp**: trước khi tối ưu, mở màn hình danh sách phiếu (tải hết, chưa có chỉ mục) mất **%s ms**; "
                + "sau khi tối ưu (chỉ mục + phân trang) còn **%s ms** – nhanh hơn khoảng **×%s**.\n", ms(worst.medianMs()), ms(page1.medianMs()),
                times(worst.medianMs() / Math.max(0.01, page1.medianMs()))));
        s.append(String.format("- **Phân trang**: đã có chỉ mục, tải cả %s phiếu một lần vẫn mất %s ms; chỉ tải trang đầu 50 phiếu còn %s ms (nhanh hơn ×%s). "
                + "Trình duyệt cũng không phải vẽ hàng chục nghìn dòng.\n", fmt(n), ms(allOld.medianMs()), ms(page1.medianMs()),
                times(allOld.medianMs() / Math.max(0.01, page1.medianMs()))));
        s.append("- **Chỉ mục**: so cột A và B. Chỉ mục `idx_ticket_part_ticket` giúp view tính chi phí phiếu không phải quét cả bảng linh kiện cho từng phiếu; "
                + "`idx_ticket_received` giúp SQLite đọc thẳng 50 phiếu mới nhất theo thứ tự thay vì sắp xếp toàn bộ bảng.\n");
        s.append("- Tra cứu theo serial / mã phiếu dùng chỉ mục UNIQUE có sẵn nên luôn nhanh dù dữ liệu lớn.\n");
        s.append("- Tìm kiếm không dấu (gõ \"nguyen van\" ra \"Nguyễn Văn\") được lọc trong Java trên các cột cần tìm; chỉ các dòng của trang hiện tại mới được nạp đầy đủ.\n\n");
        s.append("## Kế hoạch truy vấn của SQLite (EXPLAIN QUERY PLAN) cho \"50 phiếu mới nhất\"\n\n");
        s.append("- A: `").append(planBefore).append("`\n- B: `").append(planAfter).append("`\n\n");
        s.append("`USE TEMP B-TREE FOR ORDER BY` = phải sắp xếp toàn bộ bảng trước khi lấy 50 dòng; có chỉ mục thì SQLite đọc theo thứ tự có sẵn và dừng sau 50 dòng.\n\n");
        s.append("## Các chỉ mục đã thêm\n\n| Tên | Trên | Dùng cho |\n|---|---|---|\n");
        for (String[] ix : NEW_INDEXES) s.append("| `").append(ix[0]).append("` | `").append(ix[1]).append("` | ").append(ix[2]).append(" |\n");
        s.append("\n_Số liệu phụ thuộc cấu hình máy; chạy lại `thu-tai.bat` trên máy của bạn để có số liệu của chính máy đó._\n");
        return s.toString();
    }

    private static String ms(double v) {
        return v >= 100 ? String.format("%,.0f", v).replace(',', '.') : String.format("%.1f", v).replace('.', ',');
    }

    private static String times(double x) {
        return x >= 10 ? fmt(Math.round(x)) : String.format("%.1f", x).replace('.', ',');
    }

    private static String fmt(long v) { return String.format("%,d", v).replace(',', '.'); }

    private static void log(String s) {
        System.out.println("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + s);
    }
}
