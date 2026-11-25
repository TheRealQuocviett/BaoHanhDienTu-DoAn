package vn.baohanh.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.security.PasswordHasher;

/**
 * Dữ liệu mẫu cho lần chạy đầu tiên: tài khoản, danh mục, kho, bảo hành và phiếu
 * (gồm cả phiếu cũ đã trả trong 12 tháng để báo cáo có số liệu).
 * Mọi thao tác đều đi qua tầng nghiệp vụ nên dữ liệu luôn hợp lệ.
 */
public final class SeedData {
    private final AppContext app;
    private final Database db;
    private final Random rnd = new Random(42);
    private User admin;
    private final Map<String, Long> ids = new LinkedHashMap<>();

    private SeedData(AppContext app) {
        this.app = app;
        this.db = app.db;
    }

    public static void run(AppContext app) throws SQLException {
        new SeedData(app).seed();
    }

    private static final String[] STREETS = {"45 Lê Lợi, Phường Bến Nghé, Quận 1", "12 Trần Hưng Đạo, Phường 6, Quận 5",
            "88 Nguyễn Văn Cừ, Phường 2, Quận 5", "210 Lý Thường Kiệt, Phường 14, Quận 10", "7 Hoàng Văn Thụ, Phường 9, Phú Nhuận",
            "33 Cách Mạng Tháng 8, Phường 11, Quận 3", "156 Phan Xích Long, Phường 7, Phú Nhuận", "19 Võ Văn Ngân, Phường Linh Chiểu, Thủ Đức",
            "64 Nguyễn Thị Minh Khai, Phường 6, Quận 3", "301 Âu Cơ, Phường 10, Tân Bình", "27 Quang Trung, Phường 10, Gò Vấp",
            "142 Hậu Giang, Phường 6, Quận 6", "9 Nguyễn Hữu Thọ, Phường Tân Hưng, Quận 7", "75 Lê Văn Việt, Phường Hiệp Phú, Thủ Đức",
            "520 Kinh Dương Vương, Phường An Lạc, Bình Tân", "18 Phạm Văn Đồng, Phường 3, Gò Vấp", "233 Điện Biên Phủ, Phường 15, Bình Thạnh",
            "66 Trường Chinh, Phường 12, Tân Bình", "4 Tôn Đức Thắng, Phường Bến Nghé, Quận 1", "190 Lạc Long Quân, Phường 3, Quận 11"};

    /** Điền địa chỉ mẫu cho các khách còn trống. Trả về số khách được cập nhật. */
    static int fillAddresses(Database db) throws SQLException {
        int n = 0;
        for (Row cst : db.query("SELECT id FROM customer WHERE address IS NULL OR TRIM(address) = '' ORDER BY id"))
            n += db.update("UPDATE customer SET address = ? WHERE id = ?",
                    STREETS[(int) (cst.lng("id") % STREETS.length)] + ", TP. Hồ Chí Minh", cst.lng("id"));
        return n;
    }

    /**
     * CSDL dữ liệu mẫu tạo từ bản cũ (chưa có địa chỉ khách): điền bổ sung một lần.
     * Chỉ chạy với CSDL mẫu (có nhật ký "Tạo dữ liệu mẫu") và chỉ một lần, nên không đụng tới khách thật nhập sau này.
     */
    public static int upgradeSampleData(AppContext app) throws SQLException {
        Database db = app.db;
        if ("true".equals(app.settings.get("sample_addresses_done"))) return 0;
        app.settings.put("sample_addresses_done", "true");
        if (db.scalar("SELECT COUNT(*) FROM audit_log WHERE action = 'KHỞI TẠO' AND detail = 'Tạo dữ liệu mẫu'") == 0) return 0;
        return fillAddresses(db);
    }

    /** Khách mẫu có sẵn tài khoản cổng tra cứu (mật khẩu chung 123456) để demo đăng nhập. */
    public static final String[][] SAMPLE_ACCOUNTS = {
        {"0903123456", "Nguyễn Văn An", "iPhone 15 Pro Max (đang gửi hãng) + AirPods Pro 2, có tin nhắn"},
        {"0912345678", "Trần Thị Bích", "Galaxy S24 Ultra – máy sửa xong, chờ nhận"},
        {"0987654321", "Lê Hoàng Minh", "Laptop HP – hết bảo hành, đang chờ duyệt báo giá"},
        {"0934567890", "Phạm Thu Hà", "MacBook Air (đã sửa xong, có hóa đơn) + tủ lạnh (có yêu cầu bảo hành online)"},
        {"0978111222", "Võ Quốc Bảo", "Tivi Sony – chờ linh kiện, quá hẹn"},
        {"0909888777", "Đặng Mỹ Linh", "Máy giặt LG – từ chối bảo hành"},
        {"0965432100", "Bùi Thanh Tùng", "Xiaomi Redmi Note 13 – đang sửa, có phí"}};
    public static final String SAMPLE_PASSWORD = "123456";

    /** Tạo tài khoản cổng khách cho các khách mẫu chưa có tài khoản (không đổi mật khẩu khách đã tự đăng ký). */
    static int addSampleAccounts(Database db) throws SQLException {
        int n = 0;
        for (String[] a : SAMPLE_ACCOUNTS) {
            Row c = db.one("SELECT id FROM customer WHERE phone = ?", a[0]);
            if (c == null || db.scalar("SELECT COUNT(*) FROM customer_account WHERE customer_id = ?", c.lng("id")) > 0) continue;
            db.update("INSERT INTO customer_account (customer_id, password_hash, created_at) VALUES (?, ?, ?)",
                    c.lng("id"), PasswordHasher.hash(SAMPLE_PASSWORD), Database.now());
            n++;
        }
        return n;
    }

    /** CSDL mẫu từ bản cũ: thêm tài khoản khách mẫu một lần. */
    public static int upgradeSampleAccounts(AppContext app) throws SQLException {
        if ("true".equals(app.settings.get("sample_accounts_done"))) return 0;
        app.settings.put("sample_accounts_done", "true");
        if (app.db.scalar("SELECT COUNT(*) FROM audit_log WHERE action = 'KHỞI TẠO' AND detail = 'Tạo dữ liệu mẫu'") == 0) return 0;
        return addSampleAccounts(app.db);
    }


    // ===================== Lịch sử sửa chữa cho gợi ý chẩn đoán & dự báo kho =====================

    /** model, lỗi khách báo, kết luận kỹ thuật, mã linh kiện (null = không thay), tiền công, trong BH (1/0), cách đây bao nhiêu ngày, số ngày sửa */
    private static final Object[][] REPAIRS = {
        {"A3106", "Màn hình chớp nháy, có sọc xanh", "Cáp màn hình bị đứt ngầm – thay cáp màn hình", "CAP-MH-IP15PM", 300_000, 0, 5, 2},
        {"A3106", "Màn hình nhấp nháy, tối màn", "Cáp màn hình tiếp xúc kém – thay cáp màn hình", "CAP-MH-IP15PM", 300_000, 0, 15, 2},
        {"A3106", "Màn hình nháy liên tục khi mở khóa", "Cáp màn hình bị gập – thay cáp màn hình", "CAP-MH-IP15PM", 0, 1, 22, 2},
        {"A3106", "Màn hình chớp, cảm ứng lúc được lúc không", "Cáp màn hình hỏng – thay cáp màn hình", "CAP-MH-IP15PM", 300_000, 0, 32, 3},
        {"A3106", "Chớp màn hình khi để độ sáng thấp", "Cáp màn hình lỗi – thay cáp màn hình", "CAP-MH-IP15PM", 0, 1, 48, 2},
        {"A3106", "Màn hình chớp nháy", "Lỗi phần mềm iOS – cập nhật iOS, theo dõi 1 ngày", null, 100_000, 0, 60, 1},
        {"A3106", "Màn hình sọc và chớp nháy sau khi rơi", "Vỡ panel màn hình do rơi – thay màn hình", "MH-IP15PM", 400_000, 0, 95, 4},
        {"A3106", "Pin tụt nhanh, sập nguồn", "Pin chai 76% – thay pin", "PIN-IP15PM", 0, 1, 12, 1},
        {"A3106", "Máy tự tắt khi còn 30% pin", "Pin chai – thay pin", "PIN-IP15PM", 150_000, 0, 40, 1},
        {"A3106", "Pin phồng, cấn màn hình", "Pin phồng – thay pin", "PIN-IP15PM", 0, 1, 55, 2},
        {"SM-S928B", "Pin tụt nhanh, máy nóng khi sạc", "Pin chai – thay pin", "PIN-S24U", 0, 1, 18, 2},
        {"SM-S928B", "Sạc không vào", "Chân sạc bám bẩn – vệ sinh cổng sạc", null, 50_000, 0, 44, 1},
        {"SM-S928B", "Màn hình sọc xanh", "Lỗi màn hình AMOLED – thay màn hình", "MH-S24U", 0, 1, 100, 6},
        {"SM-A556E", "Sạc chập chờn, phải giữ dây mới vào", "Cụm chân sạc hỏng – thay cụm chân sạc", "CHAN-SAC-A55", 150_000, 0, 9, 1},
        {"SM-A556E", "Cắm sạc không nhận", "Cụm chân sạc hỏng – thay cụm chân sạc", "CHAN-SAC-A55", 0, 1, 27, 1},
        {"SM-A556E", "Sạc chậm, lúc được lúc không", "Cụm chân sạc lỏng – thay cụm chân sạc", "CHAN-SAC-A55", 150_000, 0, 50, 1},
        {"23129RAA4G", "Pin phồng, cấn màn hình", "Pin phồng – thay pin", "PIN-RN13", 100_000, 0, 20, 1},
        {"23129RAA4G", "Pin tụt nhanh", "Pin chai – thay pin", "PIN-RN13", 0, 1, 66, 1},
        {"15-EG3XXX", "Máy nóng, quạt kêu to", "Bụi quạt, khô keo tản nhiệt – vệ sinh, tra keo", "KEO-TN", 200_000, 0, 14, 1},
        {"15-EG3XXX", "Laptop tắt đột ngột khi chơi game", "Quá nhiệt – tra lại keo tản nhiệt", "KEO-TN", 0, 1, 38, 2},
        {"15-EG3XXX", "Không lên nguồn", "Hỏng mainboard – thay mainboard", "MB-HP15", 0, 1, 85, 7},
        {"FV1409S4W", "Không xả nước, báo lỗi OE", "Bơm xả hỏng – thay bơm xả", "BOM-XA-LG", 0, 1, 25, 2},
        {"FV1409S4W", "Máy giặt không xả, kêu ù ù", "Kẹt vật lạ, cánh bơm gãy – thay bơm xả", "BOM-XA-LG", 150_000, 0, 58, 2},
        {"INS5430", "Liệt vài phím, gõ không ăn", "Bàn phím lỗi – thay bàn phím", "BP-DELL14", 0, 1, 35, 2}};

    private static final String[] REPAIR_NAMES = {"Ngô Thanh Vân", "Trịnh Quốc Khánh", "Đỗ Hải Đăng", "Lưu Bích Ngân", "Huỳnh Tấn Phát",
            "Văn Thị Mỹ", "Mạc Đăng Khoa", "Thái Hoàng Nam", "Bạch Tuyết Mai", "Triệu Vĩnh Phúc", "Giang Thu Trang", "Âu Dương Phong"};

    /**
     * Thêm ~24 phiếu đã sửa xong có kết luận kỹ thuật và linh kiện (trong 100 ngày gần đây), để chức năng gợi ý chẩn đoán và
     * dự báo linh kiện có dữ liệu. Tồn kho hiện tại giữ nguyên (ghi thêm một lần nhập hàng trước đó bằng đúng số đã dùng).
     */
    static int addRepairHistory(AppContext app) throws SQLException {
        Database db = app.db;
        Row adminRow = db.one("SELECT id, username, full_name FROM app_user WHERE role = 'ADMIN' ORDER BY id LIMIT 1");
        Row techRow = db.one("SELECT id FROM app_user WHERE role = 'TECHNICIAN' AND active = 1 ORDER BY id LIMIT 1");
        if (adminRow == null) return 0;
        User adm = new User(adminRow.lng("id"), adminRow.str("username"), adminRow.str("full_name"), vn.baohanh.model.Role.ADMIN);
        Long techId = techRow == null ? null : techRow.lng("id");
        Row appleRow = db.one("SELECT id FROM brand WHERE name = 'Apple'");
        Long apple = appleRow == null ? null : appleRow.lng("id");
        Object[][] newParts = {{"CAP-MH-IP15PM", "Cáp màn hình iPhone 15 Pro Max", 350_000, 750_000, 1, 0},
                {"MH-IP15PM", "Màn hình iPhone 15 Pro Max", 6_200_000, 7_600_000, 1, 1}};
        for (Object[] p : newParts)
            if (db.one("SELECT id FROM part WHERE code = ?", p[0]) == null)
                app.catalog.save(adm, "part", 0, m("code", p[0], "name", p[1], "brand_id", apple, "unit", "cái",
                        "cost_price", p[2], "sell_price", p[3], "stock", p[4], "min_stock", p[5]));
        Map<Long, Long> used = new LinkedHashMap<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now().withSecond(0).withNano(0);
        java.util.Random rnd = new java.util.Random(2026);
        int n = 0;
        for (Object[] r : REPAIRS) {
            Row prod = db.one("SELECT id, default_months FROM product WHERE model_code = ?", r[0]);
            if (prod == null) continue;
            Row part = r[3] == null ? null : db.one("SELECT id, sell_price FROM part WHERE code = ?", r[3]);
            if (r[3] != null && part == null) continue;
            int daysAgo = (int) r[6], repairDays = (int) r[7];
            boolean inW = ((int) r[5]) == 1;
            java.time.LocalDateTime recv = now.minusDays(daysAgo + repairDays).withHour(9 + rnd.nextInt(8)).withMinute(rnd.nextInt(60));
            java.time.LocalDateTime ready = recv.plusDays(repairDays).withHour(10 + rnd.nextInt(7));
            java.time.LocalDateTime back = ready.plusHours(3 + rnd.nextInt(30));
            if (back.isAfter(now)) back = now.minusHours(1);
            int months = (int) prod.lng("default_months");
            LocalDate purchase = inW ? recv.toLocalDate().minusMonths(Math.max(1, months / 2)) : recv.toLocalDate().minusMonths(months).minusDays(20 + rnd.nextInt(90));
            String name = REPAIR_NAMES[n % REPAIR_NAMES.length];
            String phone;
            do phone = "097" + (1000000 + rnd.nextInt(8999999)); while (db.one("SELECT id FROM customer WHERE phone = ?", phone) != null);
            long cid = db.insert("INSERT INTO customer (name, phone, address, created_at) VALUES (?, ?, ?, ?)", name, phone,
                    STREETS[(n * 7) % STREETS.length] + ", TP. Hồ Chí Minh", Database.ts(purchase.atTime(10, 0)));
            String serial;
            do serial = ((String) r[0]).replaceAll("[^A-Z0-9]", "") + "-R" + (10000 + rnd.nextInt(89999));
            while (db.one("SELECT id FROM warranty WHERE serial = ?", serial) != null);
            long wid = db.insert("INSERT INTO warranty (code, serial, product_id, customer_id, purchase_date, months, expiry_date, created_by, created_at) "
                    + "VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?)", serial, prod.lng("id"), cid, purchase.toString(), months,
                    purchase.plusMonths(months).toString(), adm.id(), Database.ts(purchase.atTime(10, 0)));
            db.update("UPDATE warranty SET code = ? WHERE id = ?", "BH" + purchase.format(java.time.format.DateTimeFormatter.ofPattern("yyMM")) + "-" + String.format("%04d", wid), wid);
            long price = part == null ? 0 : part.lng("sell_price");
            long labor = ((Number) r[4]).longValue();
            long paid = inW ? 0 : labor + price;
            long tid = db.insert("INSERT INTO ticket (code, warranty_id, issue, accessories, in_warranty, received_at, received_by, due_date, status, technician_id, "
                    + "diagnosis, labor_cost, quote_status, paid_amount, payment_method, returned_at) VALUES (NULL, ?, ?, 'Máy', ?, ?, ?, ?, 'RETURNED', ?, ?, ?, ?, ?, ?, ?)",
                    wid, r[1], inW ? 1 : 0, Database.ts(recv), adm.id(), recv.toLocalDate().plusDays(repairDays + 1).toString(), techId, r[2], labor,
                    inW ? "NONE" : "APPROVED", paid, paid > 0 ? "CASH" : null, Database.ts(back));
            db.update("UPDATE ticket SET code = ? WHERE id = ?", String.format("PBH-%06d", tid), tid);
            String[][] steps = {{"RECEIVED", "Tiếp nhận máy"}, {"DIAGNOSING", "Kiểm tra lỗi"}, {"REPAIRING", (String) r[2]}, {"READY", "Sửa xong, đã kiểm tra"},
                    {"RETURNED", "Đã trả máy cho khách"}};
            java.time.LocalDateTime[] times = {recv, recv.plusHours(2), recv.plusHours(5), ready, back};
            for (int i = 0; i < steps.length; i++)
                db.insert("INSERT INTO ticket_history (ticket_id, time, status, note, user_id) VALUES (?, ?, ?, ?, ?)", tid, Database.ts(times[i]),
                        steps[i][0], steps[i][1], i == 0 || i == 4 ? adm.id() : techId);
            if (part != null) {
                db.insert("INSERT INTO ticket_part (ticket_id, part_id, qty, unit_price, charged) VALUES (?, ?, 1, ?, ?)", tid, part.lng("id"), price, inW ? 0 : 1);
                db.insert("INSERT INTO stock_movement (part_id, time, qty, type, ticket_id, user_id, note) VALUES (?, ?, -1, 'OUT', ?, ?, ?)",
                        part.lng("id"), Database.ts(recv.plusHours(5)), tid, techId, "Dùng cho phiếu " + String.format("PBH-%06d", tid));
                used.merge(part.lng("id"), 1L, Long::sum);
            }
            n++;
        }
        // Nhập hàng đợt trước đúng bằng số đã dùng → tồn kho hiện tại không đổi
        for (var e : used.entrySet())
            db.insert("INSERT INTO stock_movement (part_id, time, qty, type, user_id, note) VALUES (?, ?, ?, 'IN', ?, ?)",
                    e.getKey(), Database.ts(now.minusDays(110)), e.getValue(), adm.id(), "Nhập hàng đợt trước");
        return n;
    }

    /** CSDL mẫu từ bản cũ: thêm lịch sử sửa chữa một lần. */
    public static int upgradeRepairHistory(AppContext app) throws SQLException {
        if ("true".equals(app.settings.get("sample_repairs_done"))) return 0;
        app.settings.put("sample_repairs_done", "true");
        if (app.db.scalar("SELECT COUNT(*) FROM audit_log WHERE action = 'KHỞI TẠO' AND detail = 'Tạo dữ liệu mẫu'") == 0) return 0;
        try {
            return app.db.tx(() -> addRepairHistory(app));
        } catch (Exception e) {
            System.err.println("Khong them duoc phieu sua chua mau: " + e.getMessage());
            return 0;
        }
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private long save(String entity, Object... kv) throws SQLException {
        return app.catalog.save(admin, entity, 0, m(kv)).lng("id");
    }

    private void seed() throws SQLException {
        // ---------- Tài khoản ----------
        long adminId = db.insert("INSERT INTO app_user (username, password_hash, full_name, phone, role, active, created_at) "
                + "VALUES ('admin', ?, 'Nguyễn Minh Quân', '0901000001', 'ADMIN', 1, ?)", PasswordHasher.hash("admin123"), Database.now());
        admin = new User(adminId, "admin", "Nguyễn Minh Quân", Role.ADMIN);
        long rec = save("user", "username", "tiepnhan", "full_name", "Lê Thu Trang", "phone", "0901000002", "role", "RECEPTION", "active", true, "password", "123456");
        long kt1 = save("user", "username", "ktv1", "full_name", "Phạm Hoàng Long", "phone", "0901000003", "role", "TECHNICIAN", "active", true, "password", "123456");
        long kt2 = save("user", "username", "ktv2", "full_name", "Đỗ Minh Khoa", "phone", "0901000004", "role", "TECHNICIAN", "active", true, "password", "123456");
        User reception = new User(rec, "tiepnhan", "Lê Thu Trang", Role.RECEPTION);
        User tech1 = new User(kt1, "ktv1", "Phạm Hoàng Long", Role.TECHNICIAN);
        User tech2 = new User(kt2, "ktv2", "Đỗ Minh Khoa", Role.TECHNICIAN);

        // ---------- Thông tin cửa hàng ----------
        app.settings.put("shop_name", "Điện Máy Sao Việt");
        app.settings.put("shop_address", "123 Nguyễn Trãi, Phường 2, Quận 5, TP. Hồ Chí Minh");
        app.settings.put("shop_phone", "028 3838 6868");
        app.settings.put("portal_url", "");
        app.settings.put("policy", "Quý khách vui lòng mang theo phiếu này khi nhận máy. Cửa hàng không chịu trách nhiệm với dữ liệu "
                + "trong máy, vui lòng sao lưu trước khi gửi. Máy không đến nhận sau 30 ngày kể từ ngày hẹn, cửa hàng không chịu trách nhiệm bảo quản.");
        app.settings.put("shop_email", "cskh@dienmaysaoviet.vn");
        app.settings.put("open_hours", "Thứ 2 – Thứ 7: 8:00 – 20:30\nChủ nhật: 8:00 – 17:00\nNhận bảo hành đến 19:30 mỗi ngày");
        app.settings.put("map_query", "");
        app.settings.put("social_link", "");
        app.settings.put("portal_intro", "Trung tâm bảo hành chính hãng: điện thoại, laptop, tivi, điện lạnh. Kiểm tra lỗi miễn phí, "
                + "báo giá trước khi sửa, theo dõi tiến độ online.");

        // ---------- Danh mục ----------
        String[][] brands = {
            {"Apple", "Mỹ", "1800 1127"}, {"Samsung", "Hàn Quốc", "1800 588 889"}, {"Xiaomi", "Trung Quốc", "1800 400 410"},
            {"Sony", "Nhật Bản", "1800 588 885"}, {"LG", "Hàn Quốc", "1800 1503"}, {"HP", "Mỹ", "1800 588 868"}, {"Dell", "Mỹ", "1800 545 455"}};
        for (String[] b : brands) ids.put("b:" + b[0], save("brand", "name", b[0], "country", b[1], "hotline", b[2]));
        String[][] centers = {
            {"Apple", "Trung tâm bảo hành Apple ủy quyền (AASP) Quận 1", "028 3822 1122", "45 Lê Lợi, Quận 1"},
            {"Samsung", "Samsung Service Center Quận 10", "028 3862 5566", "210 Ba Tháng Hai, Quận 10"},
            {"Xiaomi", "Mi Service Center Quận 3", "028 3930 7788", "88 Cách Mạng Tháng Tám, Quận 3"},
            {"Sony", "Sony Service Center TP.HCM", "028 3929 0099", "15 Nguyễn Thị Minh Khai, Quận 1"},
            {"LG", "LG Electronics Service Tân Bình", "028 3811 4455", "302 Cộng Hòa, Tân Bình"},
            {"HP", "HP Authorized Service – Quận 3", "028 3820 3344", "12 Võ Văn Tần, Quận 3"},
            {"Dell", "Dell Service Center – Phú Nhuận", "028 3844 7788", "60 Phan Đăng Lưu, Phú Nhuận"}};
        for (String[] c : centers) ids.put("sc:" + c[0], save("service_center", "brand_id", ids.get("b:" + c[0]), "name", c[1], "phone", c[2], "address", c[3]));
        for (String c : new String[]{"Điện thoại", "Laptop", "Tivi", "Gia dụng", "Âm thanh & Phụ kiện"}) ids.put("c:" + c, save("category", "name", c));
        Object[][] products = {
            {"A3106", "iPhone 15 Pro Max 256GB", "Apple", "Điện thoại", 12, 34_990_000},
            {"A3090", "iPhone 15 128GB", "Apple", "Điện thoại", 12, 19_990_000},
            {"MBA-M3-13", "MacBook Air M3 13 inch", "Apple", "Laptop", 12, 27_990_000},
            {"A2931", "AirPods Pro 2", "Apple", "Âm thanh & Phụ kiện", 12, 5_990_000},
            {"SM-S928B", "Samsung Galaxy S24 Ultra", "Samsung", "Điện thoại", 12, 29_990_000},
            {"SM-A556E", "Samsung Galaxy A55 5G", "Samsung", "Điện thoại", 12, 9_490_000},
            {"RT38CG6584", "Tủ lạnh Samsung Inverter 380L", "Samsung", "Gia dụng", 24, 12_490_000},
            {"23129RAA4G", "Xiaomi Redmi Note 13", "Xiaomi", "Điện thoại", 18, 4_890_000},
            {"B106GL", "Robot hút bụi Xiaomi S10", "Xiaomi", "Gia dụng", 12, 6_990_000},
            {"XR-55X90L", "Tivi Sony Bravia 55 inch X90L", "Sony", "Tivi", 24, 25_900_000},
            {"WH1000XM5", "Tai nghe Sony WH-1000XM5", "Sony", "Âm thanh & Phụ kiện", 12, 7_990_000},
            {"FV1409S4W", "Máy giặt LG Inverter 9kg", "LG", "Gia dụng", 24, 9_290_000},
            {"OLED55C3", "Tivi LG OLED 55 inch C3", "LG", "Tivi", 24, 32_900_000},
            {"15-EG3XXX", "Laptop HP Pavilion 15", "HP", "Laptop", 12, 17_490_000},
            {"INS5430", "Laptop Dell Inspiron 14 5430", "Dell", "Laptop", 12, 18_990_000}};
        for (Object[] p : products)
            ids.put("p:" + p[0], save("product", "model_code", p[0], "name", p[1], "brand_id", ids.get("b:" + p[2]),
                    "category_id", ids.get("c:" + p[3]), "default_months", p[4], "price", p[5]));
        Object[][] parts = {
            {"PIN-IP15PM", "Pin iPhone 15 Pro Max", "Apple", "cái", 1_200_000, 1_650_000, 6, 2},
            {"MH-IP15", "Màn hình iPhone 15", "Apple", "cái", 4_800_000, 5_900_000, 2, 1},
            {"LOA-AP2-R", "Tai phải AirPods Pro 2", "Apple", "cái", 1_500_000, 2_100_000, 3, 1},
            {"MH-S24U", "Màn hình Galaxy S24 Ultra", "Samsung", "cái", 6_500_000, 7_900_000, 1, 1},
            {"PIN-S24U", "Pin Galaxy S24 Ultra", "Samsung", "cái", 850_000, 1_200_000, 5, 2},
            {"CHAN-SAC-A55", "Cụm chân sạc Galaxy A55", "Samsung", "cái", 250_000, 450_000, 8, 3},
            {"PIN-RN13", "Pin Redmi Note 13", "Xiaomi", "cái", 320_000, 550_000, 4, 2},
            {"BO-AT-X90L", "Bo âm thanh Sony X90L", "Sony", "cái", 1_300_000, 1_800_000, 0, 1},
            {"BOM-XA-LG", "Bơm xả máy giặt LG", "LG", "cái", 420_000, 650_000, 4, 2},
            {"MB-HP15", "Mainboard HP Pavilion 15", "HP", "cái", 2_400_000, 3_200_000, 2, 1},
            {"BP-DELL14", "Bàn phím Dell Inspiron 14", "Dell", "cái", 450_000, 750_000, 3, 1},
            {"KEO-TN", "Keo tản nhiệt", null, "tuýp", 40_000, 80_000, 20, 5},
            {"CAP-NGUON-TV", "Cáp nguồn tivi", null, "sợi", 60_000, 150_000, 1, 3}};
        for (Object[] p : parts)
            ids.put("pt:" + p[0], save("part", "code", p[0], "name", p[1], "brand_id", p[2] == null ? null : ids.get("b:" + p[2]),
                    "unit", p[3], "cost_price", p[4], "sell_price", p[5], "stock", p[6], "min_stock", p[7]));
        // Bổ sung tồn kho cho phiếu cũ (dùng nhiều linh kiện)
        for (String k : new String[]{"PIN-IP15PM", "PIN-S24U", "CHAN-SAC-A55", "PIN-RN13", "BOM-XA-LG", "KEO-TN", "BP-DELL14"})
            app.inventory.stockIn(admin, ids.get("pt:" + k), 10, 0, "Nhập hàng đầu kỳ");

        // ---------- Phiếu cũ đã xử lý (12 tháng) ----------
        String[] names = {"Trương Gia Huy", "Hoàng Ngọc Mai", "Vũ Đức Thắng", "Lý Thanh Tâm", "Phan Bảo Ngọc", "Đinh Công Danh",
            "Tạ Minh Châu", "Kiều Anh Tuấn", "Mai Phương Thảo", "La Quốc Việt", "Châu Mỹ Duyên", "Quách Thành Đạt",
            "Hồ Ngọc Hân", "Lâm Gia Bảo", "Tôn Nữ Thu Hà", "Dương Khánh Linh", "Cao Văn Phúc", "Ninh Hải Yến"};
        String[][] hist = { // model, linh kiện, lỗi
            {"A3106", "PIN-IP15PM", "Pin chai, sập nguồn khi còn 20%"}, {"SM-S928B", "PIN-S24U", "Máy nóng, pin tụt nhanh"},
            {"SM-A556E", "CHAN-SAC-A55", "Sạc chập chờn"}, {"23129RAA4G", "PIN-RN13", "Pin phồng"},
            {"FV1409S4W", "BOM-XA-LG", "Không xả nước, báo lỗi OE"}, {"INS5430", "BP-DELL14", "Liệt phím E, R"},
            {"15-EG3XXX", "KEO-TN", "Quạt kêu to, máy nóng"}, {"A3090", null, "Face ID không hoạt động"},
            {"WH1000XM5", null, "Tai trái rè"}, {"B106GL", null, "Không về dock sạc"}, {"OLED55C3", null, "Sọc màn hình"},
            {"A2931", "LOA-AP2-R", "Tai phải không nhận"}};
        List<long[]> backdates = new ArrayList<>();
        int custIdx = 0;
        for (int i = 0; i < 18; i++) {
            String[] h = hist[i % hist.length];
            int daysAgo = 20 + rnd.nextInt(340);
            int mode = i % 6; // 0..3: trong BH sửa xong, 4: hết hạn có báo giá, 5: từ chối
            boolean expired = mode == 4 || mode == 5;
            long productId = ids.get("p:" + h[0]);
            int months = (int) db.scalar("SELECT default_months FROM product WHERE id = ?", productId);
            if (!expired) daysAgo = Math.min(daysAgo, months * 30 - 60); // máy phải còn hạn tới hôm nay
            LocalDate ticketDate = LocalDate.now().minusDays(daysAgo);
            LocalDate purchase = expired ? ticketDate.minusMonths(months).minusDays(30 + rnd.nextInt(200))
                    : LocalDate.now().minusMonths(months).plusDays(5 + rnd.nextInt(Math.max(1, months * 30 - daysAgo - 40)));
            if (purchase.isAfter(ticketDate.minusDays(5))) purchase = ticketDate.minusDays(5 + rnd.nextInt(30));
            String serial = h[0].replaceAll("[^A-Z0-9]", "") + "-" + (10000 + rnd.nextInt(89999));
            String phone = "09" + (10000000 + rnd.nextInt(89999999));
            app.warranty.activate(reception, m("serial", serial, "product_id", productId, "customer_phone", phone,
                    "customer_name", names[custIdx++ % names.length], "invoice_no", "HD-" + (9000 + i), "purchase_date", purchase.toString()));
            User tech = i % 2 == 0 ? tech1 : tech2;
            long t = app.warranty.createTicket(reception, m("serial", serial, "issue", h[2], "accessories", "Máy",
                    "technician_id", tech.id())).lng("id");
            app.warranty.changeStatus(tech, t, m("status", "DIAGNOSING", "note", "Kiểm tra lỗi"));
            if (mode == 5) {
                app.warranty.updateCost(tech, t, m("labor_cost", 0, "diagnosis", "Hư hỏng do rơi vỡ / vào nước"));
                app.warranty.changeStatus(tech, t, m("status", "REJECTED", "note", "Lỗi do người dùng, khách không sửa"));
            } else if (mode == 4) {
                app.warranty.updateCost(tech, t, m("labor_cost", 200_000 + rnd.nextInt(3) * 100_000, "diagnosis", "Cần thay linh kiện"));
                if (h[1] != null) app.warranty.addPart(tech, t, ids.get("pt:" + h[1]), 1);
                app.warranty.sendQuote(tech, t);
                app.warranty.decideQuote(reception, t, true, "Khách đồng ý qua điện thoại");
                app.warranty.changeStatus(tech, t, m("status", "REPAIRING", "note", "Tiến hành sửa"));
                app.warranty.changeStatus(tech, t, m("status", "READY", "note", "Sửa xong, đã kiểm tra"));
            } else if (h[1] == null) {
                app.warranty.changeStatus(tech, t, m("status", "SENT_TO_VENDOR", "note", "Gửi hãng xử lý",
                        "service_center_id", db.scalar("SELECT s.id FROM service_center s JOIN product p ON p.brand_id = s.brand_id WHERE p.id = ?", productId)));
                app.warranty.changeStatus(tech, t, m("status", "READY", "note", "Hãng đã sửa xong, nhận máy về"));
            } else {
                app.warranty.changeStatus(tech, t, m("status", "REPAIRING", "note", "Thay linh kiện bảo hành"));
                app.warranty.addPart(tech, t, ids.get("pt:" + h[1]), 1);
                app.warranty.changeStatus(tech, t, m("status", "READY", "note", "Sửa xong"));
            }
            app.warranty.pay(reception, t, m("method", rnd.nextBoolean() ? "CASH" : "TRANSFER"));
            backdates.add(new long[]{t, daysAgo, 3 + rnd.nextInt(6)});
        }

        // ---------- Bảo hành & phiếu đang xử lý ----------
        LocalDate d = LocalDate.now();
        Object[][] ws = { // serial, model, khách, sđt, ngày mua
            {"356789104523871", "A3106", "Nguyễn Văn An", "0903123456", d.minusMonths(3)},
            {"R58T20ABCDE", "SM-S928B", "Trần Thị Bích", "0912345678", d.minusMonths(11).minusDays(10)},
            {"5CD3421XYZ", "15-EG3XXX", "Lê Hoàng Minh", "0987654321", d.minusMonths(26)},
            {"C02FK1ZQMD6M", "MBA-M3-13", "Phạm Thu Hà", "0934567890", d.minusMonths(1)},
            {"SN-TV55X90L-0042", "XR-55X90L", "Võ Quốc Bảo", "0978111222", d.minusMonths(7)},
            {"DK-WM9KG-77812", "FV1409S4W", "Đặng Mỹ Linh", "0909888777", d.minusMonths(28)},
            {"XM-RN13-551203", "23129RAA4G", "Bùi Thanh Tùng", "0965432100", d.minusMonths(20)},
            {"AIRPODS-P2-H8KQ", "A2931", "Nguyễn Văn An", "0903123456", d.minusMonths(3)},
            {"RT38-SS-880021", "RT38CG6584", "Phạm Thu Hà", "0934567890", d.minusMonths(5)}};
        for (Object[] w : ws)
            app.warranty.activate(reception, m("serial", w[0], "product_id", ids.get("p:" + w[1]), "customer_name", w[2],
                    "customer_phone", w[3], "invoice_no", "HD-" + (10200 + rnd.nextInt(300)), "purchase_date", w[4].toString()));

        // Hai máy từng sửa trước đây (để thống kê "máy phải sửa lại nhiều lần" có dữ liệu)
        Object[][] past = {{"356789104523871", "Mất sóng, không nhận SIM", 40}, {"R58T20ABCDE", "Pin chai, sạc chậm, máy nóng", 75}};
        for (Object[] pz : past) {
            long t = app.warranty.createTicket(reception, m("serial", pz[0], "issue", pz[1], "accessories", "Máy", "technician_id", kt1)).lng("id");
            app.warranty.changeStatus(tech1, t, m("status", "DIAGNOSING", "note", "Kiểm tra lỗi"));
            app.warranty.changeStatus(tech1, t, m("status", "REPAIRING", "note", "Sửa chữa bảo hành"));
            app.warranty.changeStatus(tech1, t, m("status", "READY", "note", "Sửa xong"));
            app.warranty.pay(reception, t, m("method", "CASH"));
            backdates.add(new long[]{t, (int) pz[2], 3});
        }

        long a = app.warranty.createTicket(reception, m("serial", "356789104523871", "issue", "Màn hình chớp nháy, cảm ứng loạn khi sạc",
                "accessories", "Máy, ốp lưng", "condition", "Không trầy xước", "technician_id", kt1)).lng("id");
        app.warranty.changeStatus(tech1, a, m("status", "DIAGNOSING", "note", "Kiểm tra: lỗi màn hình"));
        app.warranty.changeStatus(tech1, a, m("status", "SENT_TO_VENDOR", "note", "Gửi AASP, mã gửi AP-55821", "service_center_id", ids.get("sc:Apple")));

        long b = app.warranty.createTicket(reception, m("serial", "R58T20ABCDE", "issue", "Pin tụt nhanh, máy nóng",
                "accessories", "Máy, sạc", "condition", "Trầy nhẹ cạnh phải", "technician_id", kt1)).lng("id");
        app.warranty.changeStatus(tech1, b, m("status", "DIAGNOSING", "note", "Đo dung lượng pin còn 71%"));
        app.warranty.changeStatus(tech1, b, m("status", "REPAIRING", "note", "Thay pin chính hãng"));
        app.warranty.addPart(tech1, b, ids.get("pt:PIN-S24U"), 1);
        app.warranty.changeStatus(tech1, b, m("status", "READY", "note", "Đã thay pin, test 2 giờ ổn định"));

        long c = app.warranty.createTicket(reception, m("serial", "5CD3421XYZ", "issue", "Không lên nguồn",
                "accessories", "Máy, sạc, túi", "condition", "Bản lề lỏng", "technician_id", kt2)).lng("id");
        app.warranty.changeStatus(tech2, c, m("status", "DIAGNOSING", "note", "Chập mainboard"));
        app.warranty.updateCost(tech2, c, m("labor_cost", 300_000, "diagnosis", "Chập nguồn mainboard, cần thay mainboard"));
        app.warranty.addPart(tech2, c, ids.get("pt:MB-HP15"), 1);
        app.warranty.sendQuote(tech2, c);

        long e = app.warranty.createTicket(reception, m("serial", "SN-TV55X90L-0042", "issue", "Mất tiếng loa trái",
                "accessories", "Điều khiển", "condition", "Bình thường", "technician_id", kt2)).lng("id");
        app.warranty.changeStatus(tech2, e, m("status", "DIAGNOSING", "note", "Lỗi bo âm thanh"));
        app.warranty.changeStatus(tech2, e, m("status", "WAITING_PARTS", "note", "Hết bo âm thanh, đã đặt hàng Sony (dự kiến 5 ngày)"));

        app.warranty.createTicket(reception, m("serial", "AIRPODS-P2-H8KQ", "issue", "Tai phải không nhận", "accessories", "Hộp sạc"));

        long f = app.warranty.createTicket(reception, m("serial", "XM-RN13-551203", "issue", "Pin phồng, cấn màn hình",
                "accessories", "Máy", "condition", "Màn hình hơi hở", "technician_id", kt2)).lng("id");
        app.warranty.changeStatus(tech2, f, m("status", "DIAGNOSING", "note", "Pin phồng"));
        app.warranty.updateCost(tech2, f, m("labor_cost", 150_000, "diagnosis", "Thay pin mới"));
        app.warranty.addPart(tech2, f, ids.get("pt:PIN-RN13"), 1);
        app.warranty.sendQuote(tech2, f);
        app.warranty.decideQuote(reception, f, true, "Khách đồng ý tại quầy");
        app.warranty.changeStatus(tech2, f, m("status", "REPAIRING", "note", "Đang thay pin"));

        long g = app.warranty.createTicket(reception, m("serial", "DK-WM9KG-77812", "issue", "Kêu to khi vắt",
                "accessories", "", "condition", "Bình thường", "technician_id", kt1)).lng("id");
        app.warranty.changeStatus(tech1, g, m("status", "DIAGNOSING", "note", "Hỏng bạc đạn lồng giặt"));
        app.warranty.changeStatus(tech1, g, m("status", "REJECTED", "note", "Hết hạn bảo hành, khách không sửa"));

        backdates.add(new long[]{a, 6, 10});
        backdates.add(new long[]{b, 4, 5});
        backdates.add(new long[]{c, 3, 7});
        backdates.add(new long[]{e, 9, 7});
        backdates.add(new long[]{f, 2, 5});
        backdates.add(new long[]{g, 5, 6});
        for (long[] bd : backdates) backdate(bd[0], (int) bd[1], (int) bd[2]);

        // ---------- Cổng khách hàng, yêu cầu online, gói bảo hành mở rộng ----------
        app.customers.register("0903123456", "356789104523871", "123456");   // Nguyễn Văn An
        app.customers.register("0934567890", "C02FK1ZQMD6M", "123456");      // Phạm Thu Hà
        long ha = db.scalar("SELECT id FROM customer WHERE phone = '0934567890'");
        app.customers.createRequest(ha, m("warranty_id", db.scalar("SELECT id FROM warranty WHERE serial = 'RT38-SS-880021'"),
                "issue", "Tủ lạnh ngăn mát không lạnh, có tiếng kêu lạch cạch", "preferred_date", LocalDate.now().plusDays(2).toString()));
        app.warranty.extend(reception, db.scalar("SELECT id FROM warranty WHERE serial = 'C02FK1ZQMD6M'"),
                m("months", 12, "price", 1_490_000, "method", "CASH", "note", "Gói Bảo hành Vàng 12 tháng"));

        // Nhà cung cấp & đơn đặt hàng linh kiện
        long ncc1 = app.catalog.save(admin, "supplier", 0, m("name", "Công ty TNHH Linh Kiện Số Sài Gòn", "contact", "Anh Hùng",
                "phone", "028 3925 1122", "email", "banhang@linhkienso.vn", "address", "56 Nhật Tảo, Phường 4, Quận 10, TP. HCM")).lng("id");
        app.catalog.save(admin, "supplier", 0, m("name", "Phụ Tùng Điện Máy Toàn Phát", "contact", "Chị Lan",
                "phone", "0908 765 432", "email", "lan@toanphat.vn", "address", "112 Lý Thường Kiệt, Quận Tân Bình, TP. HCM"));
        long pinIp = db.scalar("SELECT id FROM part WHERE code = 'PIN-IP15PM'"), keo = db.scalar("SELECT id FROM part WHERE code = 'KEO-TN'");
        Row po1 = app.purchases.create(admin, m("supplier_id", ncc1, "note", "Nhập bổ sung đầu tháng",
                "lines", List.of(m("part_id", pinIp, "qty", 5), m("part_id", keo, "qty", 10))));
        app.purchases.markOrdered(admin, po1.lng("id"));
        app.purchases.receive(admin, po1.lng("id"), m());
        db.update("UPDATE purchase_order SET created_at = ?, ordered_at = ?, received_at = ? WHERE id = ?",
                Database.ts(LocalDate.now().minusDays(20).atTime(9, 15)), Database.ts(LocalDate.now().minusDays(20).atTime(9, 30)),
                Database.ts(LocalDate.now().minusDays(17).atTime(14, 0)), po1.lng("id"));
        long pinRn = db.scalar("SELECT id FROM part WHERE code = 'PIN-RN13'");
        Row po2 = app.purchases.create(admin, m("supplier_id", ncc1, "expected_date", LocalDate.now().plusDays(3).toString(),
                "note", "Pin Redmi đang thiếu cho phiếu chờ linh kiện", "lines", List.of(m("part_id", pinRn, "qty", 6))));
        app.purchases.markOrdered(admin, po2.lng("id"));

        // Lịch hẹn: hai khách đã được hẹn mang máy tới trong tuần
        int dayOff = 1;
        for (Row w : db.query("SELECT w.id, w.customer_id FROM warranty w JOIN customer cu ON cu.id = w.customer_id "
                + "WHERE cu.phone NOT IN ('0934567890', '0903123456') AND w.expiry_date >= ? "
                + "AND NOT EXISTS (SELECT 1 FROM ticket t WHERE t.warranty_id = w.id AND t.status NOT IN ('RETURNED','CANCELLED')) "
                + "ORDER BY w.id LIMIT 2", LocalDate.now())) {
            Row me = app.customers.createRequest(w.lng("customer_id"), m("warranty_id", w.lng("id"),
                    "issue", dayOff == 1 ? "Máy tự khởi động lại liên tục" : "Màn hình có sọc ngang khi bật"));
            app.requests.confirm(reception, me.lng("last_request_id"), LocalDate.now().plusDays(dayOff).toString(),
                    dayOff == 1 ? "Mang theo hộp và hóa đơn" : "Đến trước 17h");
            dayOff += 2;
        }

        // Tin nhắn mẫu: khách Nguyễn Văn An hỏi tiến độ phiếu đang sửa
        Row anT = db.one("SELECT t.id FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN customer cu ON cu.id = w.customer_id "
                + "WHERE cu.phone = '0903123456' AND t.status NOT IN ('RETURNED','CANCELLED') ORDER BY t.id LIMIT 1");
        if (anT != null) {
            java.time.LocalDateTime nowT = java.time.LocalDateTime.now();
            db.insert("INSERT INTO ticket_message (ticket_id, sender, body, created_at, read_by_staff, read_by_customer) VALUES (?, 'CUSTOMER', ?, ?, 1, 1)",
                    anT.lng("id"), "Chào shop, cho em hỏi máy em khoảng khi nào xong ạ?", Database.ts(nowT.minusHours(5)));
            db.insert("INSERT INTO ticket_message (ticket_id, sender, user_id, body, created_at, read_by_staff, read_by_customer) VALUES (?, 'STAFF', ?, ?, ?, 1, 1)",
                    anT.lng("id"), tech1.id(), "Chào anh, bên em đang chờ linh kiện từ hãng, dự kiến 2–3 ngày nữa ạ. Có linh kiện em báo anh ngay.",
                    Database.ts(nowT.minusHours(4)));
            db.insert("INSERT INTO ticket_message (ticket_id, sender, body, created_at, read_by_staff, read_by_customer) VALUES (?, 'CUSTOMER', ?, ?, 0, 1)",
                    anT.lng("id"), "Dạ vâng. Nếu thay linh kiện thì máy có mất dữ liệu không shop?", Database.ts(nowT.minusMinutes(35)));
        }

        fillAddresses(db);
        app.settings.put("sample_addresses_done", "true");
        addSampleAccounts(db);
        app.settings.put("sample_accounts_done", "true");
        addRepairHistory(app);
        app.settings.put("sample_repairs_done", "true");

        // Khách mẫu Phạm Thu Hà: một lần sửa đã xong (để thấy hóa đơn, phiếu in, chức năng đánh giá)
        long haMac = db.scalar("SELECT id FROM warranty WHERE serial = 'C02FK1ZQMD6M'");
        long tHa = app.warranty.createTicket(reception, m("serial", "C02FK1ZQMD6M", "issue", "Bàn phím liệt phím E và R",
                "accessories", "Sạc MagSafe", "technician_id", tech1.id())).lng("id");
        app.warranty.changeStatus(tech1, tHa, m("status", "DIAGNOSING", "note", "Kiểm tra bàn phím"));
        app.warranty.changeStatus(tech1, tHa, m("status", "REPAIRING", "note", "Vệ sinh, thay cụm phím bảo hành"));
        app.warranty.updateCost(tech1, tHa, m("labor_cost", 0, "diagnosis", "Bụi bẩn dưới phím, thay keycap"));
        app.warranty.changeStatus(tech1, tHa, m("status", "READY", "note", "Đã thử toàn bộ phím"));
        app.warranty.pay(reception, tHa, m("method", "CASH"));
        backdate(tHa, 9, 3);
        if (haMac == 0) throw new IllegalStateException("thiếu dữ liệu mẫu");

        // ---------- Đánh giá của khách cho các phiếu đã trả ----------
        int[] stars = {5, 5, 4, 5, 3, 4, 5, 2, 5, 4};
        String[] comments = {"Sửa nhanh, nhân viên nhiệt tình", "Rất hài lòng, sẽ quay lại", "Ổn, đúng hẹn",
                "Kỹ thuật viên giải thích rõ ràng", "Hơi lâu nhưng sửa tốt", "Báo giá minh bạch", "", "Phải chờ lâu hơn hẹn 2 ngày", "Tuyệt vời!", ""};
        int k = 0;
        for (Row t : db.query("SELECT t.id, t.returned_at, w.customer_id FROM ticket t JOIN warranty w ON w.id = t.warranty_id "
                + "WHERE t.status = 'RETURNED' ORDER BY t.id")) {
            if (t.lng("id") == tHa) continue;                 // để khách mẫu tự thử đánh giá
            if (k % 4 == 3) { k++; continue; }                 // không phải ai cũng đánh giá
            int i = k++ % stars.length;
            db.update("INSERT INTO ticket_review (ticket_id, customer_id, stars, comment, created_at) "
                    + "VALUES (?, ?, ?, ?, MIN(strftime('%Y-%m-%dT%H:%M:%S', ?, '+20 hours'), ?))",
                    t.lng("id"), t.lng("customer_id"), stars[i], comments[i].isEmpty() ? null : comments[i], t.str("returned_at"), Database.now());
        }

        // ---------- Lịch sử tin đã gửi (để hộp thư của khách có dữ liệu) ----------
        for (Row t : db.query("SELECT t.id, t.code, t.received_at, t.status, t.warranty_id, w.customer_id, cu.phone, p.name AS product, "
                + "(SELECT MIN(time) FROM ticket_history h WHERE h.ticket_id = t.id AND h.status = 'READY') AS ready_at "
                + "FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN customer cu ON cu.id = w.customer_id "
                + "JOIN product p ON p.id = w.product_id WHERE cu.phone IN ('0934567890', '0903123456', '0987654321')")) {
            String shop = "Dien May Sao Viet";
            String prod = vn.baohanh.notify.HttpSenders.removeAccents(t.str("product"));
            seedNote(t, "RECEIVED", t.str("received_at"), shop + " da tiep nhan " + prod + " cua quy khach. Ma phieu " + t.str("code")
                    + ". Tra cuu tien do tai cong tra cuu cua cua hang.");
            if (!t.isNull("ready_at"))
                seedNote(t, "READY", t.str("ready_at"), shop + ": " + prod + " cua quy khach (phieu " + t.str("code")
                        + ") da sua xong. Moi quy khach den nhan may. Lien he 028 3838 6868.");
        }

        // Thời điểm tạo bảo hành = ngày mua; nhật ký khởi tạo dọn sạch cho gọn
        db.update("UPDATE warranty SET created_at = purchase_date || 'T09:30:00'");
        db.update("UPDATE stock_movement SET time = (SELECT received_at FROM ticket WHERE id = stock_movement.ticket_id) WHERE ticket_id IS NOT NULL");
        db.update("DELETE FROM audit_log");
        app.audit.log(admin, "KHỞI TẠO", null, null, "Tạo dữ liệu mẫu");
    }

    private void seedNote(Row t, String event, String time, String body) throws SQLException {
        db.update("INSERT OR IGNORE INTO notification (created_at, event, channel, recipient, body, status, attempts, next_try, sent_at, "
                + "customer_id, ticket_id, warranty_id, dedup_key) VALUES (?, ?, 'SMS', ?, ?, 'SENT', 1, ?, ?, ?, ?, ?, ?)",
                time, event, t.str("phone"), body, time, time, t.lng("customer_id"), t.lng("id"), t.lng("warranty_id"),
                event + ":T" + t.lng("id") + ":SMS");
    }

    /** Lùi phiếu về quá khứ: các mốc lịch sử dàn đều, không vượt quá hiện tại. */
    private void backdate(long ticketId, int daysAgo, int dueDays) throws SQLException {
        List<Row> hs = db.query("SELECT id FROM ticket_history WHERE ticket_id = ? ORDER BY id", ticketId);
        int n = hs.size();
        long totalHours = Math.max(0, daysAgo * 24L - 2);
        long step = n <= 1 ? 0 : Math.min(26, totalHours / (n - 1));
        String base = "-" + daysAgo + " days";
        db.update("UPDATE ticket SET received_at = strftime('%Y-%m-%dT%H:%M:%S', received_at, ?), "
                + "due_date = date(received_at, ?, ?) WHERE id = ?", base, base, "+" + dueDays + " days", ticketId);
        for (int k = 0; k < n; k++) {
            db.update("UPDATE ticket_history SET time = strftime('%Y-%m-%dT%H:%M:%S', time, ?, ?) WHERE id = ?",
                    base, "+" + (k * step) + " hours", hs.get(k).lng("id"));
        }
        db.update("UPDATE ticket SET returned_at = (SELECT MAX(time) FROM ticket_history WHERE ticket_id = ?) "
                + "WHERE id = ? AND status = 'RETURNED'", ticketId, ticketId);
    }
}
