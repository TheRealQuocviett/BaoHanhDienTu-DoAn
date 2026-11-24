package vn.baohanh.test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.model.User;
import vn.baohanh.notify.NotificationService;
import vn.baohanh.security.PasswordHasher;
import vn.baohanh.service.AppContext;
import vn.baohanh.service.AppException;
import vn.baohanh.util.Json;
import vn.baohanh.util.QrCode;
import vn.baohanh.util.Xlsx;

/**
 * BỘ KIỂM THỬ TỰ ĐỘNG cho tầng nghiệp vụ.
 *
 * Chạy: trong VS Code chọn cấu hình "Chạy kiểm thử" rồi F5, hoặc chạy file run-tests.bat.
 * Mỗi lần chạy dùng một CSDL tạm mới (không đụng tới dữ liệu thật trong thư mục data/).
 * Không dùng JUnit để không phải tải thêm thư viện; mỗi kiểm thử là một lời gọi check(...).
 */
public final class KiemThu {
    private static int pass, fail;
    private static final List<String> failures = new ArrayList<>();

    @FunctionalInterface
    interface Action { void run() throws Exception; }

    private static String unzipText(byte[] zip, String name) throws Exception {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry())
                if (e.getName().equals(name)) return new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return "";
    }

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  [ĐẠT]  " + name); }
        else { fail++; failures.add(name); System.out.println("  [LỖI]  " + name); }
    }

    /** Thao tác phải báo lỗi nghiệp vụ, và thông báo chứa đoạn chữ cho trước. */
    private static void expectError(String name, String part, Action a) {
        try {
            a.run();
            check(name + " (không báo lỗi!)", false);
        } catch (AppException e) {
            boolean ok = part == null || e.getMessage().contains(part);
            check(name + (ok ? "" : " – thông báo: " + e.getMessage()), ok);
        } catch (Exception e) {
            check(name + " – lỗi khác: " + e, false);
        }
    }

    private static void section(String s) { System.out.println("\n■ " + s); }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("kiem-thu-bao-hanh-");
        Database db = new Database(dir.resolve("test.db"), Path.of("database", "schema.sql"));
        AppContext app = new AppContext(db, 8080);
        System.out.println("CSDL kiểm thử: " + dir.resolve("test.db"));

        // ---------- Chuẩn bị dữ liệu tối thiểu ----------
        long adminId = db.insert("INSERT INTO app_user (username, password_hash, full_name, role, active, created_at) VALUES ('admin', ?, 'Quản lý', 'ADMIN', 1, ?)",
                PasswordHasher.hash("admin123"), Database.now());
        User admin = new User(adminId, "admin", "Quản lý", Role.ADMIN);
        User rec = vn.baohanh.service.Auth.toUser(app.catalog.save(admin, "user", 0, m("username", "tn", "full_name", "Tiếp Nhận", "role", "RECEPTION", "active", true, "password", "123456")).with("username", "tn"));
        Row techRow = app.catalog.save(admin, "user", 0, m("username", "ktv", "full_name", "Kỹ Thuật", "role", "TECHNICIAN", "active", true, "password", "123456"));
        User tech = new User(techRow.lng("id"), "ktv", "Kỹ Thuật", Role.TECHNICIAN);
        long brand = app.catalog.save(admin, "brand", 0, m("name", "Hãng A")).lng("id");
        long cat = app.catalog.save(admin, "category", 0, m("name", "Điện thoại")).lng("id");
        long prod = app.catalog.save(admin, "product", 0, m("model_code", "M1", "name", "Điện thoại M1", "brand_id", brand, "category_id", cat, "default_months", 12)).lng("id");
        long sc = app.catalog.save(admin, "service_center", 0, m("brand_id", brand, "name", "TTBH Hãng A")).lng("id");
        long part = app.catalog.save(admin, "part", 0, m("code", "PIN1", "name", "Pin M1", "sell_price", 500000, "cost_price", 300000, "stock", 3, "min_stock", 1)).lng("id");

        section("1. Bảo mật & đăng nhập");
        String h = PasswordHasher.hash("matkhau");
        check("Băm mật khẩu: đúng mật khẩu thì khớp", PasswordHasher.verify("matkhau", h));
        check("Băm mật khẩu: sai mật khẩu thì không khớp", !PasswordHasher.verify("matkhaU", h));
        check("Hai lần băm cùng mật khẩu cho kết quả khác nhau (có salt)", !h.equals(PasswordHasher.hash("matkhau")));
        check("Đăng nhập đúng", app.auth.login("ADMIN", "admin123").role() == Role.ADMIN);
        expectError("Đăng nhập sai mật khẩu", "Sai tên đăng nhập", () -> app.auth.login("admin", "x"));
        for (int i = 0; i < 5; i++) try { app.auth.login("tn", "sai"); } catch (AppException ignored) { }
        expectError("Sai 5 lần liên tiếp thì khóa tạm", "tạm khóa", () -> app.auth.login("tn", "123456"));
        check("Danh sách người dùng không lộ mật khẩu băm", app.catalog.list(admin, "user", "").stream().noneMatch(r -> r.containsKey("password_hash")));

        section("2. Phân quyền");
        expectError("Kỹ thuật viên không được kích hoạt bảo hành", "quyền", () -> app.warranty.activate(tech, m()));
        expectError("Tiếp nhận không được thêm hãng", "quyền", () -> app.catalog.save(rec, "brand", 0, m("name", "X")));
        expectError("Tiếp nhận không xem được báo cáo", "quyền", () -> app.reports.full(rec, vn.baohanh.service.ReportService.period("", "")));
        expectError("Tiếp nhận không sao lưu được", "quyền", () -> app.backup.create(rec, "thu-cong"));
        expectError("Không được tự hạ quyền quản lý", "hạ quyền", () -> app.catalog.save(admin, "user", adminId, m("username", "admin", "full_name", "Q", "role", "RECEPTION", "active", true)));

        section("3. Danh mục & kiểm tra dữ liệu");
        expectError("SĐT khách sai định dạng", "không hợp lệ", () -> app.catalog.save(rec, "customer", 0, m("name", "A", "phone", "12345")));
        expectError("Thiếu trường bắt buộc", "Vui lòng nhập", () -> app.catalog.save(admin, "brand", 0, m("name", "")));
        expectError("Trùng tên hãng (không phân biệt hoa thường)", "đã tồn tại", () -> app.catalog.save(admin, "brand", 0, m("name", "hãng a")));
        expectError("Không xóa được hãng đang có sản phẩm", "đang được sử dụng", () -> app.catalog.delete(admin, "brand", brand));
        check("Tìm kiếm tiếng Việt không phân biệt hoa thường", app.catalog.list(admin, "product", "ĐIỆN THOẠI").size() == 1);

        section("4. Kích hoạt bảo hành");
        Row w1 = app.warranty.activate(rec, m("serial", "sn 001", "product_id", prod, "customer_phone", "0901 111 222", "customer_name", "Khách Một"));
        check("Serial được chuẩn hóa (in hoa, bỏ khoảng trắng)", "SN001".equals(w1.str("serial")));
        check("Thời hạn mặc định theo sản phẩm", w1.lng("months") == 12);
        check("Ngày hết hạn = ngày mua + 12 tháng", LocalDate.now().plusMonths(12).toString().equals(w1.str("expiry_date")));
        check("Sinh mã bảo hành dạng BHyyMM-0001", w1.str("code").matches("BH\\d{4}-\\d{4}"));
        Row w2 = app.warranty.activate(rec, m("serial", "SN002", "product_id", prod, "customer_phone", "0901111222", "months", 24));
        check("Khách cũ được nhận lại theo SĐT", w2.lng("customer_id") == w1.lng("customer_id"));
        expectError("Trùng serial", "đã được kích hoạt", () -> app.warranty.activate(rec, m("serial", "sn001", "product_id", prod, "customer_phone", "0901111222")));
        expectError("Ngày mua ở tương lai", "tương lai", () -> app.warranty.activate(rec, m("serial", "SN003", "product_id", prod, "customer_phone", "0901111222",
                "purchase_date", LocalDate.now().plusDays(1).toString())));
        expectError("Khách mới phải có họ tên", "họ tên", () -> app.warranty.activate(rec, m("serial", "SN004", "product_id", prod, "customer_phone", "0909999999")));
        Row wOld = app.warranty.activate(rec, m("serial", "SNOLD", "product_id", prod, "customer_phone", "0902222333", "customer_name", "Khách Hai",
                "purchase_date", LocalDate.now().minusMonths(14).toString()));
        check("Máy mua 14 tháng trước (BH 12 tháng) là hết hạn", Boolean.FALSE.equals(wOld.get("active")));

        section("5. Phiếu bảo hành & quy trình");
        long t1 = app.warranty.createTicket(rec, m("serial", "SN001", "issue", "Không lên nguồn", "technician_id", tech.id())).lng("id");
        Row tk = app.warranty.getTicket(rec, t1);
        check("Phiếu mới ở trạng thái Đã tiếp nhận", "RECEIVED".equals(tk.str("status")));
        check("Máy còn hạn → phiếu trong bảo hành", Boolean.TRUE.equals(tk.get("in_warranty")));
        expectError("Một máy chỉ có một phiếu đang mở", "chưa đóng", () -> app.warranty.createTicket(rec, m("serial", "SN001", "issue", "x")));
        expectError("Không nhảy cóc trạng thái", "Không thể chuyển", () -> app.warranty.changeStatus(tech, t1, m("status", "READY")));
        expectError("Tiếp nhận không được chuyển sang Đang kiểm tra", "quyền", () -> app.warranty.changeStatus(rec, t1, m("status", "DIAGNOSING")));
        app.warranty.changeStatus(tech, t1, m("status", "DIAGNOSING", "note", "Kiểm tra"));
        expectError("Gửi hãng phải chọn trung tâm bảo hành", "trung tâm", () -> app.warranty.changeStatus(tech, t1, m("status", "SENT_TO_VENDOR")));
        app.warranty.changeStatus(tech, t1, m("status", "REPAIRING"));
        app.warranty.addPart(tech, t1, part, 1);
        check("Dùng linh kiện → trừ tồn kho (3 → 2)", db.scalar("SELECT stock FROM part WHERE id = ?", part) == 2);
        check("Trong bảo hành → linh kiện miễn phí, tổng = 0", app.warranty.getTicket(tech, t1).lng("total") == 0);
        expectError("Không đủ tồn kho", "Không đủ tồn kho", () -> app.warranty.addPart(tech, t1, part, 5));
        expectError("Trong bảo hành không được tính tiền công", "phải bằng 0", () -> app.warranty.updateCost(tech, t1, m("labor_cost", 100000)));
        app.warranty.changeStatus(tech, t1, m("status", "READY", "note", "Xong"));
        expectError("Không trả máy qua đổi trạng thái thường", "Thu tiền", () -> app.warranty.changeStatus(rec, t1, m("status", "RETURNED")));
        Row paid = app.warranty.pay(rec, t1, m());
        check("Trả máy miễn phí: không sinh hóa đơn", "RETURNED".equals(paid.str("status")) && paid.isNull("invoice_no"));
        check("Lịch sử ghi đủ các bước", ((List<?>) paid.get("history")).size() >= 5);

        section("6. Máy hết hạn: báo giá, từ chối, hoàn kho");
        long t2 = app.warranty.createTicket(rec, m("serial", "SNOLD", "issue", "Vỡ màn hình")).lng("id");
        check("Máy hết hạn → phiếu ngoài bảo hành", Boolean.FALSE.equals(app.warranty.getTicket(rec, t2).get("in_warranty")));
        app.warranty.changeStatus(tech, t2, m("status", "DIAGNOSING"));
        expectError("Chưa duyệt giá thì không được sửa", "báo giá", () -> app.warranty.changeStatus(tech, t2, m("status", "REPAIRING")));
        app.warranty.updateCost(tech, t2, m("labor_cost", 200000, "diagnosis", "Thay pin"));
        app.warranty.addPart(tech, t2, part, 1);
        check("Tổng = tiền công + linh kiện (200.000 + 500.000)", app.warranty.getTicket(tech, t2).lng("total") == 700000);
        app.warranty.sendQuote(tech, t2);
        app.warranty.decideQuote(rec, t2, true, null);
        app.warranty.updateCost(tech, t2, m("labor_cost", 250000));
        check("Đổi chi phí sau khi khách duyệt → phải báo giá lại", "NONE".equals(app.warranty.getTicket(tech, t2).str("quote_status")));
        app.warranty.sendQuote(tech, t2);
        app.warranty.decideQuote(rec, t2, false, "Khách không sửa");
        expectError("Từ chối phải ghi lý do", "lý do", () -> app.warranty.changeStatus(tech, t2, m("status", "REJECTED")));
        long before = db.scalar("SELECT stock FROM part WHERE id = ?", part);
        app.warranty.changeStatus(tech, t2, m("status", "REJECTED", "note", "Khách từ chối báo giá"));
        check("Từ chối sửa → linh kiện tự hoàn kho", db.scalar("SELECT stock FROM part WHERE id = ?", part) == before + 1);
        expectError("Thu tiền phải chọn hình thức thanh toán", "hình thức", () -> app.warranty.pay(rec, t2, m()));
        Row p2 = app.warranty.pay(rec, t2, m("method", "CASH"));
        check("Thu phí kiểm tra (tiền công) và sinh số hóa đơn", p2.lng("paid_amount") == 250000 && p2.str("invoice_no").startsWith("HDSC"));

        section("7. Hủy phiếu & ảnh");
        long t3 = app.warranty.createTicket(rec, m("serial", "SN002", "issue", "Loa rè")).lng("id");
        check("Nút Hủy phiếu chỉ hiện cho tiếp nhận ở bước đầu", Boolean.TRUE.equals(((Map<?, ?>) app.warranty.getTicket(rec, t3).get("actions")).get("cancel")));
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
        Row ph = app.photos.add(rec, t3, jpg, "a.jpg", "trầy");
        check("Tải ảnh JPEG thành công", ph.lng("id") > 0 && "image/jpeg".equals(ph.str("content_type")));
        expectError("Không nhận file không phải ảnh", "Chỉ nhận ảnh", () -> app.photos.add(rec, t3, "abc".getBytes(), "x.jpg", null));
        expectError("Chỉ người tải lên hoặc quản lý được xóa ảnh", "người tải", () -> app.photos.delete(tech, ph.lng("id")));
        app.warranty.changeStatus(rec, t3, m("status", "CANCELLED", "note", "Khách đổi ý"));
        expectError("Phiếu đã đóng không thêm ảnh được", "đã đóng", () -> app.photos.add(rec, t3, jpg, "b.jpg", null));

        section("8. Gói bảo hành mở rộng");
        Row ext = app.warranty.extend(rec, w1.lng("id"), m("months", 12, "price", 990000, "method", "TRANSFER"));
        check("Gia hạn 12 tháng → hạn mới = hạn cũ + 12 tháng", LocalDate.now().plusMonths(24).toString().equals(ext.str("expiry_date")));
        expectError("Máy hết hạn không mua gói mở rộng được", "hết hạn", () -> app.warranty.extend(rec, wOld.lng("id"), m("months", 12, "price", 0)));
        expectError("Tổng thời hạn tối đa 120 tháng", "120", () -> {
            for (int i = 0; i < 5; i++) app.warranty.extend(rec, w2.lng("id"), m("months", 24, "price", 0));
        });

        section("9. Cổng khách hàng & yêu cầu online");
        expectError("Đăng ký sai serial", "không khớp", () -> app.customers.register("0901111222", "SAI", "abcdef"));
        String tok = app.customers.register("0901111222", "SN001", "abcdef");
        long kh1 = app.customers.require(tok);
        check("Đăng ký đúng SĐT + serial → đăng nhập được", app.customers.require(app.customers.login("0901111222", "abcdef")) == kh1);
        check("Khách thấy đúng 2 máy của mình", ((List<?>) app.customers.me(kh1).get("devices")).size() == 2);
        String tok2 = app.customers.register("0902222333", "SNOLD", "abcdef");
        long kh2 = app.customers.require(tok2);
        expectError("Khách không gửi yêu cầu cho máy của người khác", "chọn máy", () -> app.customers.createRequest(kh2, m("warranty_id", w1.lng("id"), "issue", "Hỏng hết rồi")));
        app.customers.createRequest(kh1, m("warranty_id", w1.lng("id"), "issue", "Máy nóng bất thường"));
        expectError("Không gửi 2 yêu cầu cho cùng một máy", "đang chờ", () -> app.customers.createRequest(kh1, m("warranty_id", w1.lng("id"), "issue", "Lần hai nữa")));
        long rq = app.requests.list(rec, "OPEN").get(0).lng("id");
        app.requests.confirm(rec, rq, LocalDate.now().plusDays(1).toString(), "Mang theo sạc");
        Row conv = app.requests.convert(rec, rq, m());
        check("Tiếp nhận từ yêu cầu → tạo phiếu và liên kết", conv.lng("ticket_id") > 0 && "CONVERTED".equals(((Row) conv.get("request")).str("status")));
        expectError("Phiên khách không hợp lệ", "đăng nhập", () -> app.customers.require("token-gia"));

        section("10. Báo cáo, sao lưu, tiện ích");
        Row rp = app.reports.full(admin, vn.baohanh.service.ReportService.period("", ""));
        check("Báo cáo: doanh thu sửa chữa = 250.000", ((Row) rp.get("summary")).lng("revenue") == 250000);
        check("Báo cáo: doanh thu gói BH mở rộng = 990.000", ((Row) rp.get("summary")).lng("extension_revenue") == 990000);
        byte[] xlsx = app.reports.exportXlsx(admin, vn.baohanh.service.ReportService.period("", ""));
        int entries = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) { while (z.getNextEntry() != null) entries++; }
        check("File Excel là gói zip hợp lệ có 11 sheet (7 báo cáo + 4 thống kê lỗi)", entries == 5 + 11);
        Row bk = app.backup.create(admin, "thu-cong");
        app.catalog.save(admin, "brand", 0, m("name", "Hãng tạo sau sao lưu"));
        app.backup.restore(admin, bk.str("name"));
        check("Khôi phục: dữ liệu quay về lúc sao lưu", app.catalog.list(admin, "brand", "tạo sau").isEmpty());
        check("Khôi phục: ảnh vẫn còn", app.photos.list(admin, t3).size() == 1);
        expectError("Chặn tên file sao lưu nguy hiểm", "không hợp lệ", () -> app.backup.file(admin, "../../etc/passwd"));
        check("JSON: đọc/ghi giữ nguyên tiếng Việt", "Bảo hành \"điện tử\"".equals(Json.parseObject(Json.stringify(m("a", "Bảo hành \"điện tử\""))).get("a")));
        check("Mã QR: chuỗi dài chọn phiên bản lớn hơn", QrCode.encode("x".repeat(200)).version > QrCode.encode("PBH-000001").version);
        check("Trạng thái: Sửa xong chỉ chuyển được sang Đã trả khách", TicketStatus.READY.next().equals(List.of(TicketStatus.RETURNED)));
        check("Xlsx tạo được tên sheet dài/ký tự đặc biệt", new Xlsx().sheet("Tên/sheet:rất*dài?" + "x".repeat(40), List.of("A"), List.of()).toBytes().length > 0);

        section("11. Thông báo cho khách (email / SMS / Zalo – dùng máy chủ giả)");
        try (MayChuGia gia = new MayChuGia()) {
            System.setProperty("baohanh.esms.url", "http://127.0.0.1:" + gia.httpPort + "/esms");
            System.setProperty("baohanh.zalo.url", "http://127.0.0.1:" + gia.httpPort + "/zalo");
            System.setProperty("baohanh.notify.anytime", "true");
            app.settings.put("shop_name", "Cửa hàng Test");
            app.settings.put("shop_phone", "0281234567");
            app.warranty.activate(rec, m("serial", "SNN1", "product_id", prod, "customer_phone", "0905555666",
                    "customer_name", "Khách Ba", "customer_email", "khach3@example.com"));
            long tOff = app.warranty.createTicket(rec, m("serial", "SNN1", "issue", "Loa rè")).lng("id");
            check("Chưa bật thông báo → không tạo tin nào", db.scalar("SELECT COUNT(*) FROM notification") == 0);
            app.warranty.changeStatus(rec, tOff, m("status", "CANCELLED", "note", "Tạo thử"));
            expectError("Nhân viên tiếp nhận không xem được cấu hình (mật khẩu)", "quyền", () -> app.notify.getConfig(rec));
            expectError("Cổng SMTP phải là số", "Cổng", () -> app.notify.saveConfig(admin, m("ntf_smtp_port", "abc")));

            app.notify.saveConfig(admin, m("ntf_enabled", true, "ntf_email_on", true, "ntf_smtp_host", "127.0.0.1",
                    "ntf_smtp_port", String.valueOf(gia.smtpPort), "ntf_smtp_security", "NONE", "ntf_smtp_user", "shop@example.com",
                    "ntf_smtp_password", "mat-khau-ung-dung", "ntf_sms_on", true, "ntf_esms_api_key", "KEY", "ntf_esms_secret_key", "SECRET",
                    "ntf_esms_brandname", "BAOHANH", "ntf_zalo_on", true, "ntf_zalo_token", "TOKEN-Z", "ntf_zalo_tpl_READY", "12345"));
            check("Mật khẩu được che khi xem lại cấu hình", NotificationService.MASK.equals(app.notify.getConfig(admin).str("ntf_smtp_password")));
            app.notify.saveConfig(admin, m("ntf_smtp_password", NotificationService.MASK, "ntf_esms_secret_key", NotificationService.MASK));
            check("Lưu lại form có mật khẩu che → giữ mật khẩu cũ", "mat-khau-ung-dung".equals(app.settings.get("ntf_smtp_password"))
                    && "SECRET".equals(app.settings.get("ntf_esms_secret_key")));

            long tn = app.warranty.createTicket(rec, m("serial", "SNN1", "issue", "Không sạc được")).lng("id");
            String code = app.warranty.getTicket(rec, tn).str("code");
            check("Tiếp nhận máy → xếp 2 tin (email + SMS; Zalo chưa có mẫu cho sự kiện này)",
                    db.scalar("SELECT COUNT(*) FROM notification WHERE ticket_id = ? AND event = 'RECEIVED'", tn) == 2);
            app.notify.onTicket("RECEIVED", tn);
            check("Không gửi trùng cùng một sự kiện", db.scalar("SELECT COUNT(*) FROM notification WHERE ticket_id = ?", tn) == 2);
            check("Luồng gửi xử lý 2 tin", app.notify.processDue() == 2);
            check("Email tới đúng địa chỉ, có mã phiếu, tên khách, tên cửa hàng (tiếng Việt nguyên vẹn)",
                    gia.of("EMAIL").size() == 1 && gia.of("EMAIL").get(0).startsWith("EMAIL|khach3@example.com|")
                    && gia.of("EMAIL").get(0).contains(code) && gia.of("EMAIL").get(0).contains("Khách Ba") && gia.of("EMAIL").get(0).contains("Cửa hàng Test"));
            String sms = gia.of("SMS").isEmpty() ? "" : gia.of("SMS").get(0);
            check("SMS gửi eSMS đúng tham số (ApiKey, Brandname, SmsType=2, SĐT)", sms.contains("ApiKey=KEY") && sms.contains("Brandname=BAOHANH")
                    && sms.contains("SmsType=2") && sms.contains("Phone=0905555666"));
            check("SMS mặc định không dấu", sms.contains("da tiep nhan") && !sms.contains("đ") && !sms.contains("ế"));
            check("Tin đã gửi được đánh dấu Đã gửi kèm mã của nhà cung cấp",
                    db.scalar("SELECT COUNT(*) FROM notification WHERE ticket_id = ? AND status = 'SENT' AND provider_id IS NOT NULL", tn) == 2);

            for (String st : new String[]{"DIAGNOSING", "REPAIRING", "READY"}) app.warranty.changeStatus(admin, tn, m("status", st));
            app.notify.processDue();
            String zalo = gia.of("ZALO").isEmpty() ? "" : gia.of("ZALO").get(0);
            check("Sửa xong → gửi Zalo ZNS: token ở header, mẫu 12345, SĐT dạng 84…", zalo.startsWith("ZALO|TOKEN-Z|")
                    && zalo.contains("\"template_id\":\"12345\"") && zalo.contains("\"phone\":\"84905555666\"") && zalo.contains(code));
            check("Sửa xong → cũng gửi email báo nhận máy", gia.of("EMAIL").size() == 2 && gia.of("EMAIL").get(1).contains("đã sửa xong"));

            // Nhắc hết hạn: một khách đồng ý, một khách từ chối nhận
            String buy = LocalDate.now().minusMonths(12).plusDays(10).toString();
            Row wa = app.warranty.activate(rec, m("serial", "SNEXP1", "product_id", prod, "customer_phone", "0906666777", "customer_name", "Khách Bốn", "purchase_date", buy));
            Row wb = app.warranty.activate(rec, m("serial", "SNEXP2", "product_id", prod, "customer_phone", "0907777888", "customer_name", "Khách Năm", "purchase_date", buy));
            check("Tin nhắc hết hạn mặc định TẮT", app.notify.scanExpiring() == 0);
            app.notify.saveConfig(admin, m("ntf_ev_EXPIRING", true));
            app.customers.updateProfile(wb.lng("customer_id"), m("email", "", "address", "", "no_reminders", true));
            check("Khách tự tắt nhắc hạn trong cổng tra cứu", Boolean.TRUE.equals(app.customers.me(wb.lng("customer_id")).get("no_reminders")));
            app.notify.scanExpiring();
            check("Quét sắp hết hạn: nhắc khách đồng ý, bỏ qua khách đã từ chối",
                    db.scalar("SELECT COUNT(*) FROM notification WHERE event = 'EXPIRING' AND warranty_id = ?", wa.lng("id")) == 1
                    && db.scalar("SELECT COUNT(*) FROM notification WHERE event = 'EXPIRING' AND warranty_id = ?", wb.lng("id")) == 0);
            app.notify.scanExpiring();
            check("Quét lại không nhắc lần hai", db.scalar("SELECT COUNT(*) FROM notification WHERE event = 'EXPIRING'") == 1);

            // Lỗi nhà cung cấp → thử lại 4 lần rồi báo lỗi; nhân viên bấm gửi lại
            gia.esmsFail = true;
            for (int i = 0; i < 4; i++) {
                db.update("UPDATE notification SET next_try = ? WHERE status = 'PENDING'", Database.now());
                app.notify.processDue();
            }
            Row bad = db.one("SELECT * FROM notification WHERE event = 'EXPIRING'");
            check("Lỗi eSMS → thử lại tối đa 4 lần rồi chuyển Gửi lỗi, giữ thông báo lỗi",
                    "FAILED".equals(bad.str("status")) && bad.lng("attempts") == 4 && bad.str("last_error").contains("146"));
            gia.esmsFail = false;
            app.notify.retry(rec, bad.lng("id"));
            app.notify.processDue();
            check("Gửi lại tin lỗi thành công", "SENT".equals(db.one("SELECT status FROM notification WHERE id = ?", bad.lng("id")).str("status")));
            expectError("Chỉ gửi lại được tin đang lỗi", "lỗi", () -> app.notify.retry(rec, bad.lng("id")));

            // Gửi thử
            check("Gửi thử email thành công", app.notify.sendTest(admin, "EMAIL", "test@example.com").contains("test@example.com"));
            gia.smtpRejectAuth = true;
            expectError("Sai mật khẩu SMTP → gợi ý dùng Mật khẩu ứng dụng", "Mật khẩu ứng dụng", () -> app.notify.sendTest(admin, "EMAIL", "test@example.com"));
            gia.smtpRejectAuth = false;
            expectError("Gửi thử: số điện thoại sai", "không hợp lệ", () -> app.notify.sendTest(admin, "SMS", "12345"));
            check("Nhật ký thông báo tìm theo mã phiếu", !app.notify.list(rec, "", code, null).isEmpty()
                    && app.notify.list(rec, "FAILED", "", null).size() == 1);

            // Hẹn lịch yêu cầu online → báo khách
            String tk3 = app.customers.register("0905555666", "SNN1", "abcdef");
            long kh3 = app.customers.require(tk3);
            Row wn = db.one("SELECT id FROM warranty WHERE serial = 'SNN1'");
            app.warranty.pay(admin, tn, m("method", "CASH"));
            app.customers.createRequest(kh3, m("warranty_id", wn.lng("id"), "issue", "Lại hỏng sạc"));
            long rq3 = app.requests.list(rec, "OPEN").stream().filter(x -> x.lng("customer_id") == kh3).findFirst().get().lng("id");
            app.requests.confirm(rec, rq3, LocalDate.now().plusDays(2).toString(), "Mang theo hộp");
            check("Hẹn lịch yêu cầu online → email cho khách có ngày hẹn",
                    db.scalar("SELECT COUNT(*) FROM notification WHERE event = 'REQUEST_CONFIRMED' AND request_id = ?", rq3) == 2
                    && app.notify.processDue() >= 2 && gia.of("EMAIL").stream().anyMatch(x -> x.contains("Mang theo hộp")));

            section("12. Cổng khách: phiếu in, hóa đơn, hộp thư, đánh giá, ảnh yêu cầu, trang cửa hàng");
            long kh1b = kh1;
            Row me3 = app.customers.me(kh3);
            check("Khách thấy hóa đơn của phiếu đã trả máy", ((List<?>) me3.get("invoices")).stream().anyMatch(x -> ((Row) x).lng("ticket_id") == tn));
            app.customers.checkPrint(kh3, "tra-may", tn);
            app.customers.checkPrint(kh3, "bao-hanh", wn.lng("id"));
            String link = app.print.linkFor("tra-may", tn);
            java.util.regex.Matcher lm = java.util.regex.Pattern.compile("/in/tra-may/(\\d+)\\?exp=(\\d+)&sig=(.+)").matcher(link);
            check("Khách lấy được liên kết in hóa đơn / phiếu bảo hành có chữ ký hợp lệ", lm.matches() && app.print.verify("tra-may", tn, lm.group(2), lm.group(3)));
            expectError("Khách không in được hóa đơn của người khác", null, () -> app.customers.checkPrint(kh1b, "tra-may", tn));
            expectError("Khách không in được phiếu bảo hành máy người khác", null, () -> app.customers.checkPrint(kh1b, "bao-hanh", wn.lng("id")));

            List<Row> inbox = app.customers.inbox(kh3);
            check("Hộp thư: có tin đã gửi, gộp email + SMS của cùng sự kiện", inbox.stream().anyMatch(x -> "RECEIVED".equals(x.str("event"))
                    && ((List<?>) x.get("channels")).size() == 2 && x.str("body").contains("Khách Ba")));
            check("Hộp thư không lộ tin của khách khác", app.customers.inbox(kh1b).stream().noneMatch(x -> x.str("body").contains("Khách Ba")));

            expectError("Đánh giá phải từ 1–5 sao", "1 đến 5", () -> app.customers.review(kh3, tn, 0, ""));
            expectError("Không đánh giá phiếu của người khác", null, () -> app.customers.review(kh1b, tn, 5, "x"));
            app.customers.review(kh3, tn, 5, "Sửa nhanh");
            app.customers.review(kh3, tn, 4, "Sửa nhanh, hơi chờ lâu");
            check("Gửi lại đánh giá sẽ cập nhật (không tạo trùng)", db.scalar("SELECT COUNT(*) FROM ticket_review WHERE ticket_id = ?", tn) == 1
                    && db.scalar("SELECT stars FROM ticket_review WHERE ticket_id = ?", tn) == 4);
            Row rv = (Row) app.reports.full(admin, vn.baohanh.service.ReportService.period("", "")).get("reviews");
            check("Báo cáo có điểm đánh giá trung bình và nhận xét", rv.lng("count") == 1 && ((Number) rv.get("avg")).doubleValue() == 4.0
                    && ((List<?>) rv.get("recent")).size() == 1);
            check("Nhân viên thấy đánh giá trong phiếu", app.warranty.getTicket(rec, tn).get("review") != null);
            long openT = app.warranty.createTicket(rec, m("serial", "SNEXP1", "issue", "Thử đánh giá sớm")).lng("id");
            long khA = app.customers.require(app.customers.register("0906666777", "SNEXP1", "abcdef"));
            expectError("Chưa trả máy thì chưa đánh giá được", "nhận lại máy", () -> app.customers.review(khA, openT, 5, ""));

            byte[] jpg2 = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
            for (int i = 0; i < 4; i++) app.photos.addRequestPhoto(kh3, rq3, jpg2, "loi" + i + ".jpg");
            check("Khách gửi kèm ảnh cho yêu cầu", app.photos.requestPhotos(rq3).size() == 4);
            expectError("Tối đa 4 ảnh mỗi yêu cầu", "tối đa", () -> app.photos.addRequestPhoto(kh3, rq3, jpg2, "x.jpg"));
            expectError("Không gửi ảnh vào yêu cầu của người khác", null, () -> app.photos.addRequestPhoto(kh1b, rq3, jpg2, "x.jpg"));
            expectError("Chỉ nhận file ảnh", "Chỉ nhận ảnh", () -> app.photos.addRequestPhoto(kh3, rq3, "abc".getBytes(), "x.jpg"));
            long pid = app.photos.requestPhotos(rq3).get(0).lng("id");
            expectError("Khách khác không xem được ảnh", null, () -> app.photos.readRequestPhoto(kh1b, pid));
            check("Nhân viên xem được ảnh khách gửi", ((byte[]) app.photos.readRequestPhoto(0, pid)[1]).length == jpg2.length);
            Row cv = app.requests.convert(rec, rq3, m());
            check("Tiếp nhận từ yêu cầu → ảnh khách gửi được chép vào phiếu", app.photos.list(rec, cv.lng("ticket_id")).size() == 4
                    && "Ảnh khách gửi online".equals(app.photos.list(rec, cv.lng("ticket_id")).get(0).str("note")));
            expectError("Yêu cầu đã xử lý thì không thêm ảnh", "đã được xử lý", () -> app.photos.addRequestPhoto(kh3, rq3, jpg2, "y.jpg"));

            app.settings.put("open_hours", "T2–T7: 8:00 – 20:00");
            app.settings.put("social_link", "javascript:alert(1)");
            Row sp = app.portal.shopPage();
            check("Trang cửa hàng có giờ mở cửa, chặn liên kết không phải http(s)", "T2–T7: 8:00 – 20:00".equals(sp.str("open_hours")) && sp.str("social_link").isEmpty());

            section("13. Tin nhắn giữa khách và cửa hàng trong phiếu");
            app.messages.customerSend(khA, openT, "Máy em khi nào xong ạ?");
            check("Khách nhắn được về phiếu của mình", app.messages.customerThread(khA, openT).size() == 1);
            expectError("Khách khác không đọc được hội thoại", null, () -> app.messages.customerThread(kh1b, openT));
            expectError("Khách khác không nhắn vào phiếu người khác", null, () -> app.messages.customerSend(kh1b, openT, "xin chào"));
            expectError("Không gửi tin trống", "trống", () -> app.messages.customerSend(khA, openT, "   "));
            expectError("Tin quá dài bị chặn", "tối đa", () -> app.messages.customerSend(khA, openT, "x".repeat(1001)));
            check("Nhân viên thấy hội thoại chưa đọc + số trên menu", app.messages.conversations(rec, true).stream().anyMatch(x -> x.lng("ticket_id") == openT)
                    && ((Row) app.warranty.dashboard(rec)).lng("unread_messages") >= 1);
            app.messages.staffThread(tech, openT);
            check("Mở hội thoại → đánh dấu đã đọc", app.messages.conversations(rec, true).stream().noneMatch(x -> x.lng("ticket_id") == openT));
            app.messages.staffSend(tech, openT, "Dạ chiều mai anh/chị qua lấy được ạ");
            check("Khách thấy tin trả lời chưa đọc", ((Row) app.customers.me(khA)).lng("unread_messages") == 1);
            app.messages.customerThread(khA, openT);
            check("Khách mở hội thoại → hết tin chưa đọc", ((Row) app.customers.me(khA)).lng("unread_messages") == 0);
            check("Nhân viên trả lời → xếp tin SMS báo khách", db.scalar("SELECT COUNT(*) FROM notification WHERE event = 'MESSAGE' AND ticket_id = ?", openT) == 1);
            app.notify.processDue();
            check("SMS báo khách có nội dung trả lời (không dấu)", gia.of("SMS").stream().anyMatch(x -> x.contains("chieu mai")));
            expectError("Nhân viên gửi tin trống bị chặn", "trống", () -> app.messages.staffSend(tech, openT, ""));
            for (int i = 0; i < 9; i++) app.messages.customerSend(khA, openT, "Tin " + i);
            expectError("Chống spam: tối đa 10 tin / 10 phút", "quá nhanh", () -> app.messages.customerSend(khA, openT, "Tin nữa"));

            section("14. Lịch hẹn theo ngày / tuần");
            LocalDate tmr = LocalDate.now().plusDays(1);
            long apId = db.insert("INSERT INTO service_request (code, customer_id, warranty_id, issue, appointment, status, created_at) "
                    + "VALUES ('YC-TEST', ?, ?, 'Hẹn thử', ?, 'CONFIRMED', ?)", kh3, wn.lng("id"), tmr.toString(), Database.now());
            Row cal = app.calendar.events(rec, LocalDate.now().toString(), LocalDate.now().plusDays(6).toString(), 0);
            @SuppressWarnings("unchecked") List<Row> evs = (List<Row>) cal.get("events");
            check("Lịch tuần có lịch khách hẹn mang máy tới đúng ngày", evs.stream().anyMatch(e -> "APPOINTMENT".equals(e.str("kind"))
                    && e.lng("request_id") == apId && tmr.toString().equals(e.str("date"))));
            String due = db.one("SELECT due_date FROM ticket WHERE id = ?", openT).str("due_date");
            check("Lịch có hẹn trả máy của phiếu đang sửa", evs.stream().anyMatch(e -> e.get("ticket_id") != null && e.lng("ticket_id") == openT)
                    || LocalDate.parse(due).isAfter(LocalDate.now().plusDays(6)));
            @SuppressWarnings("unchecked") List<Row> techEv = (List<Row>) app.calendar.events(tech, LocalDate.now().toString(), LocalDate.now().plusDays(6).toString(), 0).get("events");
            check("Kỹ thuật viên không thấy lịch khách hẹn (chỉ phiếu)", techEv.stream().noneMatch(e -> "APPOINTMENT".equals(e.str("kind"))));
            db.update("UPDATE ticket SET due_date = ? WHERE id = ?", LocalDate.now().minusDays(3).toString(), openT);
            @SuppressWarnings("unchecked") List<Row> todayEv = (List<Row>) app.calendar.events(rec, LocalDate.now().toString(), LocalDate.now().toString(), 0).get("events");
            check("Phiếu quá hẹn hiện ở ngày hôm nay", todayEv.stream().anyMatch(e -> "OVERDUE".equals(e.str("kind")) && e.lng("ticket_id") == openT));
            expectError("Ngày sai định dạng", "không hợp lệ", () -> app.calendar.events(rec, "abc", "", 0));
            expectError("Không xem quá 2 tháng", "2 tháng", () -> app.calendar.events(rec, "2026-01-01", "2026-06-01", 0));

            section("15. Thanh toán VietQR & bảng Kanban");
            check("CRC16-CCITT đúng chuẩn (\"123456789\" → 29B1)", "29B1".equals(vn.baohanh.util.VietQr.crc16("123456789")));
            String vq = vn.baohanh.util.VietQr.payload("970436", "0011001234567", 350000, "PBH-000019 Sửa máy");
            check("Chuỗi VietQR có ngân hàng, số TK, số tiền, nội dung không dấu, CRC hợp lệ",
                    vq.startsWith("000201010212") && vq.contains("0006970436") && vq.contains("01130011001234567") && vq.contains("5406350000")
                    && vq.contains("PBH000019 Sua may") && vq.endsWith(vn.baohanh.util.VietQr.crc16(vq.substring(0, vq.length() - 4))));
            check("Mã VietQR đọc được thành mã QR", QrCode.encode(vq).size > 21);
            boolean badBin = false;
            try { vn.baohanh.util.VietQr.payload("12", "123456", 1, ""); } catch (IllegalArgumentException e) { badBin = e.getMessage().contains("ngân hàng"); }
            check("Sai mã ngân hàng bị chặn", badBin);
            db.update("UPDATE ticket SET in_warranty = 0, labor_cost = 250000, status = 'READY', quote_status = 'APPROVED' WHERE id = ?", openT);
            check("Chưa cài tài khoản ngân hàng → không hiện VietQR", !Boolean.TRUE.equals(app.warranty.vietQr(openT).get("available")));
            app.settings.put("bank_bin", "970436");
            app.settings.put("bank_account", "0011 0012 34567");
            app.settings.put("bank_holder", "CUA HANG TEST");
            Row vr = app.warranty.vietQr(openT);
            check("Phiếu sửa xong còn phải trả → có VietQR đúng số tiền", Boolean.TRUE.equals(vr.get("available")) && vr.lng("amount") >= 250000
                    && vr.str("payload").contains("54" + String.format("%02d", String.valueOf(vr.lng("amount")).length()) + vr.lng("amount"))
                    && vr.str("svg").startsWith("<svg"));
            check("Phiếu trả máy in kèm mã VietQR", app.print.render("tra-may", openT).contains("Quét mã để chuyển khoản"));
            app.warranty.pay(rec, openT, m("method", "TRANSFER"));
            check("Đã thanh toán → không còn VietQR", !Boolean.TRUE.equals(app.warranty.vietQr(openT).get("available")));

            long cvT = cv.lng("ticket_id");
            expectError("Chưa sửa xong thì chưa chọn thanh toán", "chưa đến bước", () -> app.messages.paymentNotice(kh3, cvT, "CASH"));
            db.update("UPDATE ticket SET in_warranty = 0, labor_cost = 120000, status = 'READY', quote_status = 'APPROVED' WHERE id = ?", cvT);
            app.messages.paymentNotice(kh3, cvT, "CASH");
            check("Khách chọn trả tiền mặt khi nhận máy → nhân viên thấy trong phiếu", app.warranty.getTicket(rec, cvT).get("payment_claim") instanceof Row pc1
                    && "CASH".equals(pc1.str("method")));
            app.messages.paymentNotice(kh3, cvT, "TRANSFER");
            check("Khách báo đã chuyển khoản → cập nhật lựa chọn mới nhất + tin chưa đọc cho nhân viên",
                    app.warranty.getTicket(rec, cvT).get("payment_claim") instanceof Row pc2 && "TRANSFER".equals(pc2.str("method"))
                    && app.messages.conversations(rec, true).stream().anyMatch(x -> x.lng("ticket_id") == cvT));
            expectError("Khách khác không báo thanh toán phiếu người khác", null, () -> app.messages.paymentNotice(kh1b, cvT, "CASH"));
            expectError("Cách thanh toán sai bị chặn", "không hợp lệ", () -> app.messages.paymentNotice(kh3, cvT, "BITCOIN"));

            Row bd = app.warranty.board(tech);
            @SuppressWarnings("unchecked") List<Row> bt = (List<Row>) bd.get("tickets");
            check("Bảng Kanban có 7 cột và chỉ phiếu đang mở", ((List<?>) bd.get("columns")).size() == 7
                    && bt.stream().noneMatch(x -> List.of("RETURNED", "CANCELLED").contains(x.str("status"))));
            Row recv = bt.stream().filter(x -> "RECEIVED".equals(x.str("status"))).findFirst().orElse(null);
            check("Kỹ thuật viên chỉ kéo được phiếu mới nhận sang Đang kiểm tra (không được hủy)", recv == null
                    || (((List<?>) recv.get("next")).contains("DIAGNOSING") && !((List<?>) recv.get("next")).contains("CANCELLED")));
            @SuppressWarnings("unchecked") List<Row> br = (List<Row>) app.warranty.board(rec).get("tickets");
            check("Tiếp nhận không kéo được sang trạng thái kỹ thuật", br.stream().filter(x -> "RECEIVED".equals(x.str("status")))
                    .allMatch(x -> !((List<?>) x.get("next")).contains("DIAGNOSING")));

            section("16. Đặt hàng linh kiện từ nhà cung cấp");
            long sup1 = app.catalog.save(admin, "supplier", 0, m("name", "NCC Thử", "phone", "0281234567", "email", "ncc@example.com")).lng("id");
            long pz = app.catalog.save(admin, "part", 0, m("code", "LK-PO1", "name", "Linh kiện đặt thử", "unit", "cái", "cost_price", 50000,
                    "sell_price", 90000, "stock", 1, "min_stock", 3)).lng("id");
            Row sg = app.purchases.suggestions(admin).stream().filter(x -> x.lng("id") == pz).findFirst().orElse(null);
            check("Gợi ý đặt linh kiện dưới mức tối thiểu (2×3 − 1 = 5)", sg != null && sg.lng("suggest_qty") == 5);
            expectError("Tiếp nhận không xem/đặt hàng được", "quyền", () -> app.purchases.suggestions(rec));
            expectError("Kỹ thuật viên không tạo được đơn", "quyền", () -> app.purchases.create(tech, m("supplier_id", sup1, "lines", List.of(m("part_id", pz, "qty", 5)))));
            expectError("Đơn phải có nhà cung cấp", "nhà cung cấp", () -> app.purchases.create(admin, m("lines", List.of(m("part_id", pz, "qty", 5)))));
            expectError("Đơn phải có linh kiện", "ít nhất", () -> app.purchases.create(admin, m("supplier_id", sup1, "lines", List.of())));
            Row po = app.purchases.create(admin, m("supplier_id", sup1, "lines", List.of(m("part_id", pz, "qty", 5, "unit_cost", 48000))));
            check("Tạo đơn nháp có mã DH-… và tổng tiền đúng", po.str("code").startsWith("DH-") && "DRAFT".equals(po.str("status")) && po.lng("total") == 240000);
            Row sg2 = app.purchases.suggestions(admin).stream().filter(x -> x.lng("id") == pz).findFirst().orElse(null);
            check("Đã có đơn đang về → không gợi ý đặt trùng", sg2 != null && sg2.lng("incoming") == 5 && sg2.lng("suggest_qty") == 0);
            app.purchases.markOrdered(admin, po.lng("id"));
            expectError("Không chốt đơn hai lần", "trạng thái", () -> app.purchases.markOrdered(admin, po.lng("id")));
            check("Phiếu in đơn đặt hàng có tên nhà cung cấp và tổng", app.print.render("dat-hang", po.lng("id")).contains("NCC Thử")
                    && app.print.render("dat-hang", po.lng("id")).contains("240.000"));
            long lineId = ((List<Row>) app.purchases.get(admin, po.lng("id")).get("lines")).get(0).lng("id");
            app.purchases.receive(admin, po.lng("id"), m("lines", List.of(m("id", lineId, "received_qty", 4))));
            Row pzr = db.one("SELECT stock, cost_price FROM part WHERE id = ?", pz);
            check("Nhận hàng → cộng tồn theo số thực nhận + cập nhật giá nhập", pzr.lng("stock") == 5 && pzr.lng("cost_price") == 48000);
            check("Thẻ kho ghi nhập theo đơn", db.scalar("SELECT COUNT(*) FROM stock_movement WHERE part_id = ? AND type = 'IN' AND note LIKE ?", pz, "%" + po.str("code") + "%") == 1);
            expectError("Đơn đã nhận thì không hủy được", "trạng thái", () -> app.purchases.cancel(admin, po.lng("id"), ""));
            Row po2 = app.purchases.create(admin, m("supplier_id", sup1, "lines", List.of(m("part_id", pz, "qty", 2))));
            app.purchases.cancel(admin, po2.lng("id"), "Đặt nhầm");
            check("Hủy đơn nháp", "CANCELLED".equals(app.purchases.get(admin, po2.lng("id")).str("status")));
            expectError("Không xóa nhà cung cấp đã có đơn", null, () -> app.catalog.delete(admin, "supplier", sup1));

            section("17. KPI / hoa hồng kỹ thuật viên");
            app.settings.put("kpi_commission_rate", "10");
            app.settings.put("kpi_bonus_per_ticket", "20000");
            long tkK = app.warranty.createTicket(rec, m("serial", "SNEXP2", "issue", "Thử KPI", "technician_id", tech.id())).lng("id");
            db.update("UPDATE ticket SET in_warranty = 0, labor_cost = 300000, quote_status = 'APPROVED', status = 'READY', received_at = ?, due_date = ? WHERE id = ?",
                    Database.ts(java.time.LocalDateTime.now().minusDays(2)), LocalDate.now().plusDays(1).toString(), tkK);
            db.update("INSERT INTO ticket_history (ticket_id, time, status, user_id) VALUES (?, ?, 'READY', ?)", tkK, Database.now(), tech.id());
            app.warranty.pay(rec, tkK, m("method", "CASH"));
            long khB = app.customers.require(app.customers.register("0907777888", "SNEXP2", "abcdef"));
            app.customers.review(khB, tkK, 5, "Tốt");
            Row kpi = app.reports.technicianKpi(admin, vn.baohanh.service.ReportService.period("", ""));
            @SuppressWarnings("unchecked") Row me = ((List<Row>) kpi.get("rows")).stream().filter(x -> x.lng("id") == tech.id()).findFirst().orElse(null);
            check("KPI: đếm phiếu hoàn thành, đúng hẹn, đánh giá", me != null && me.lng("done") >= 1 && me.lng("on_time_pct") > 0 && me.lng("reviews") >= 1);
            check("KPI: hoa hồng = thưởng/phiếu + 10% tiền công", me != null && me.lng("commission") >= 20000 + 30000);
            check("KPI: có thời gian sửa trung bình (tính từ lúc nhận tới lúc sửa xong)", me != null && ((Number) me.get("avg_days")).doubleValue() > 0);
            @SuppressWarnings("unchecked") List<Row> techView = (List<Row>) app.reports.technicianKpi(tech, vn.baohanh.service.ReportService.period("", "")).get("rows");
            check("Kỹ thuật viên chỉ thấy tiền của mình", techView.stream().allMatch(x -> x.lng("id") == tech.id() ? x.get("commission") != null : x.get("commission") == null));
            expectError("Tiếp nhận không xem KPI", "quyền", () -> app.reports.technicianKpi(rec, vn.baohanh.service.ReportService.period("", "")));

            section("18. Quét mã vạch / IMEI");
            {
                java.awt.image.BufferedImage bar = vn.baohanh.util.Barcode.image128("356938035643809", 3, 70);
                java.awt.image.BufferedImage photo = new java.awt.image.BufferedImage(1000, 560, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = photo.createGraphics();
                g.setColor(new java.awt.Color(190, 190, 190));
                g.fillRect(0, 0, 1000, 560);
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.rotate(Math.toRadians(5), 500, 280);
                g.drawImage(bar, 500 - bar.getWidth() / 2, 280 - bar.getHeight() / 2, null);
                g.dispose();
                java.io.ByteArrayOutputStream jpgOut = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(photo, "jpg", jpgOut);
                @SuppressWarnings("unchecked") List<Row> codes = (List<Row>) app.scan.decode(rec, jpgOut.toByteArray()).get("codes");
                check("Đọc mã Code 128 từ ảnh chụp nghiêng (JPEG)", !codes.isEmpty() && "356938035643809".equals(codes.get(0).str("text")));
                check("Nhận ra IMEI (15 số, đúng Luhn)", !codes.isEmpty() && "IMEI".equals(codes.get(0).str("kind")));
                java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(vn.baohanh.util.Barcode.image128("SN-TV55X90L-0042", 2, 60), "png", png);
                @SuppressWarnings("unchecked") List<Row> c2 = (List<Row>) app.scan.decode(rec, png.toByteArray()).get("codes");
                check("Đọc serial chữ + số (Code 128 bộ B/C)", !c2.isEmpty() && "SN-TV55X90L-0042".equals(c2.get(0).str("text")));
                java.io.ByteArrayOutputStream blank = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(300, 200, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", blank);
                check("Ảnh không có mã → danh sách rỗng", ((List<?>) app.scan.decode(rec, blank.toByteArray()).get("codes")).isEmpty());
                expectError("File không phải ảnh bị từ chối", "ảnh", () -> app.scan.decode(rec, "khong phai anh".getBytes()));
                Row ss = app.scan.start(rec);
                String scTok = ss.str("token");
                check("Phiên quét có mã QR trỏ tới trang /quet/", ss.str("url").contains("/quet/" + scTok) && ss.str("qr_svg").startsWith("<svg"));
                app.scan.submitImage(scTok, png.toByteArray());
                app.scan.submitText(scTok, "R58T20ABCDE");
                Row pl = app.scan.poll(rec, scTok, 0);
                @SuppressWarnings("unchecked") List<Row> pc = (List<Row>) pl.get("codes");
                check("Điện thoại gửi ảnh + gõ tay → máy tính nhận đủ 2 mã", pc.size() == 2 && Boolean.TRUE.equals(pl.get("phone_seen")));
                check("Hỏi lại từ vị trí cuối → không lặp mã cũ", ((List<?>) app.scan.poll(rec, scTok, 2).get("codes")).isEmpty());
                expectError("Người khác không xem được phiên quét", "hết hạn", () -> app.scan.poll(admin, scTok, 0));
                expectError("Serial gõ tay có ký tự lạ bị chặn", "không hợp lệ", () -> app.scan.submitText(scTok, "<script>"));
                app.scan.stop(rec, scTok);
                expectError("Phiên đã đóng → điện thoại không gửi được", "hết hạn", () -> app.scan.submitText(scTok, "ABC123"));
                check("Phiếu tiếp nhận in kèm mã vạch serial", app.print.render("tiep-nhan", tkK).contains("crispEdges"));
            }

            section("19. Nhập Excel khách hàng / sản phẩm / linh kiện");
            {
                byte[] cx = new Xlsx().sheet("KH", List.of("STT", "Họ và tên", "SĐT", "Địa chỉ", "Email"), List.of(
                        List.of(1, "Khách Nhập Một", "0911222333", "1 Lê Lợi", ""),
                        List.of(2, "Khách Mất Số Không", 911222444L, "", "kh2@example.com"),
                        List.of(3, "Sai Số", "12345", "", ""),
                        List.of(4, "", "0911222333", "99 Trần Hưng Đạo", ""))).toBytes();
                Row pv = app.imports.preview(rec, "customer", cx, "kh.xlsx");
                check("Xem trước: 2 thêm mới, 1 cập nhật, 1 lỗi", pv.lng("created") == 2 && pv.lng("updated") == 1 && pv.lng("errors") == 1);
                check("Xem trước không lưu gì vào CSDL", db.scalar("SELECT COUNT(*) FROM customer WHERE phone IN ('0911222333','0911222444')") == 0);
                check("Nhận cột theo tên gọi khác (Họ và tên, SĐT)", ((List<Row>) pv.get("mapping")).stream().anyMatch(x -> "phone".equals(x.str("field")) && "SĐT".equals(x.str("column"))));
                Row cm = app.imports.commit(rec, "customer", cx, "kh.xlsx");
                check("Nhập thật: lưu dòng hợp lệ, bỏ dòng lỗi", cm.lng("created") == 2 && db.scalar("SELECT COUNT(*) FROM customer WHERE phone IN ('0911222333','0911222444')") == 2);
                check("Excel mất số 0 đầu → tự thêm lại", db.one("SELECT email FROM customer WHERE phone = '0911222444'") != null);
                check("Dòng trùng SĐT cập nhật địa chỉ, ô trống giữ tên cũ",
                        "99 Trần Hưng Đạo".equals(db.one("SELECT address FROM customer WHERE phone = '0911222333'").str("address"))
                        && "Khách Nhập Một".equals(db.one("SELECT name FROM customer WHERE phone = '0911222333'").str("name")));
                check("Ghi nhật ký nhập Excel", db.scalar("SELECT COUNT(*) FROM audit_log WHERE action = 'NHẬP EXCEL'") >= 1);
                expectError("Kỹ thuật viên không nhập khách hàng", "quyền", () -> app.imports.preview(tech, "customer", cx, "kh.xlsx"));
                expectError("Thiếu cột bắt buộc → báo rõ", "thiếu cột", () -> app.imports.preview(rec, "customer",
                        new Xlsx().sheet("x", List.of("Tên"), List.of(List.of("A"))).toBytes(), "x.xlsx"));
                byte[] px = new Xlsx().sheet("SP", List.of("Mã model *", "Tên sản phẩm *", "Hãng *", "Loại *", "Bảo hành (tháng)", "Giá bán"), List.of(
                        List.of("IMP-001", "Máy nhập thử", "Hãng Nhập Mới", "Loại Nhập Mới", 18, "1.990.000"))).toBytes();
                Row pp = app.imports.commit(admin, "product", px, "sp.xlsx");
                Row impProd = db.one("SELECT p.default_months, p.price, b.name AS brand FROM product p JOIN brand b ON b.id = p.brand_id WHERE p.model_code = 'IMP-001'");
                check("Nhập sản phẩm: tự tạo hãng/loại mới, đọc giá có dấu chấm", impProd != null && impProd.lng("default_months") == 18 && impProd.lng("price") == 1_990_000
                        && ((List<?>) pp.get("new_brands")).contains("Hãng Nhập Mới"));
                byte[] csv = "Mã linh kiện;Tên linh kiện;Giá nhập;Tồn kho\nLK-CSV1;Linh kiện từ CSV;\"1.200.000\";7\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                app.imports.commit(admin, "part", csv, "lk.csv");
                Row lk = db.one("SELECT id, stock, cost_price FROM part WHERE code = 'LK-CSV1'");
                check("Nhập linh kiện từ CSV (dấu ;) kèm tồn kho ban đầu + thẻ kho", lk != null && lk.lng("stock") == 7 && lk.lng("cost_price") == 1_200_000
                        && db.scalar("SELECT COUNT(*) FROM stock_movement WHERE part_id = ?", lk.lng("id")) == 1);
                Row again = app.imports.commit(admin, "part", "Mã linh kiện;Tên linh kiện;Tồn kho\nLK-CSV1;Linh kiện từ CSV;9\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), "lk.csv");
                check("Nhập lại linh kiện đã có: không đổi tồn kho, có ghi chú", db.scalar("SELECT stock FROM part WHERE code = 'LK-CSV1'") == 7
                        && ((List<Row>) again.get("rows")).get(0).str("message").contains("giữ nguyên"));
                byte[] tpl = app.imports.template(admin, "part");
                check("File mẫu đọc lại được, có tiêu đề cột", vn.baohanh.util.SheetReader.read(tpl, "m.xlsx").get(0).contains("Mã linh kiện *"));
            }

            section("20. Thống kê lỗi thường gặp");
            {
                check("Phân loại lỗi theo từ khóa (có dấu / không dấu)", vn.baohanh.service.FaultService.classify("Không lên nguồn, pin phồng").containsAll(List.of("POWER", "BATTERY"))
                        && vn.baohanh.service.FaultService.classify("man hinh soc ngang").contains("SCREEN"));
                check("Mô tả lạ → nhóm Khác", vn.baohanh.service.FaultService.classify("abc xyz").equals(List.of("OTHER")));
                Row fr = app.faults.report(tech, vn.baohanh.service.ReportService.period("", ""));
                @SuppressWarnings("unchecked") List<Row> gr = (List<Row>) fr.get("groups");
                check("Báo cáo có nhóm lỗi, model, linh kiện", fr.lng("total") > 0 && !gr.isEmpty() && !((List<?>) fr.get("models")).isEmpty());
                long sumPct = Math.round(gr.stream().mapToDouble(x -> ((Number) x.get("pct")).doubleValue()).sum());
                check("Tỷ lệ % tính trên tổng số phiếu", sumPct >= 99);
                long rid = app.warranty.createTicket(rec, m("serial", "SNEXP2", "issue", "Lại không lên nguồn")).lng("id");
                @SuppressWarnings("unchecked") List<Row> rep = (List<Row>) app.faults.report(admin, vn.baohanh.service.ReportService.period("", "")).get("repeats");
                check("Phát hiện máy phải sửa lại nhiều lần", rep.stream().anyMatch(x -> "SNEXP2".equals(x.str("serial")) && x.lng("times") >= 2));
                app.warranty.changeStatus(rec, rid, m("status", "CANCELLED", "note", "Thử"));
                expectError("Tiếp nhận không xem thống kê lỗi", "quyền", () -> app.faults.report(rec, vn.baohanh.service.ReportService.period("", "")));
                byte[] xl = app.reports.exportXlsx(admin, vn.baohanh.service.ReportService.period("", ""));
                check("File Excel báo cáo có thêm trang Nhóm lỗi / Model hay hỏng", unzipText(xl, "xl/workbook.xml").contains("Nhóm lỗi")
                        && unzipText(xl, "xl/workbook.xml").contains("Model hay hỏng"));
            }
        }

        db.close();
        System.out.println("\n==============================================");
        System.out.printf(" KẾT QUẢ: %d đạt, %d lỗi (tổng %d kiểm thử)%n", pass, fail, pass + fail);
        for (String f : failures) System.out.println("   ✗ " + f);
        System.out.println("==============================================");
        System.exit(fail == 0 ? 0 : 1);
    }
}
