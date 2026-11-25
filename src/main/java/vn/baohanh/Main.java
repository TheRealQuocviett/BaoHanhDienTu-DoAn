package vn.baohanh;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import vn.baohanh.api.ApiServer;
import vn.baohanh.db.Database;
import vn.baohanh.service.AppContext;
import vn.baohanh.service.SeedData;
import vn.baohanh.ui.SwingApp;

/**
 * HỆ THỐNG QUẢN LÝ BẢO HÀNH ĐIỆN TỬ – cửa hàng / đại lý bán lẻ.
 *
 * Một chương trình chạy đồng thời:
 *   - Giao diện Java Swing cho nhân viên (cửa sổ đăng nhập hiện ra khi chạy)
 *   - Máy chủ web: giao diện nhân viên tại http://localhost:8080
 *                  và cổng tra cứu cho khách tại http://localhost:8080/tra-cuu
 * Cả hai dùng chung tầng nghiệp vụ và CSDL SQLite (data/baohanh.db).
 *
 * Tham số: [cổng] [--no-gui] [--browser]
 *   --no-gui   chỉ chạy máy chủ web (không mở cửa sổ Swing)
 *   --browser  mở trình duyệt vào giao diện web khi khởi động
 */
public class Main {
    public static void main(String[] args) throws Exception {
        List<String> a = Arrays.asList(args);
        int port = 8080;
        for (String s : args) if (s.matches("\\d{2,5}")) port = Integer.parseInt(s);
        if (System.getenv("PORT") != null) port = Integer.parseInt(System.getenv("PORT"));
        boolean gui = !a.contains("--no-gui") && !GraphicsEnvironment.isHeadless();

        Path schema = Path.of("database", "schema.sql");
        if (!Files.exists(schema)) {
            fail("Khong tim thay " + schema.toAbsolutePath(),
                 "Hay mo dung thu muc BaoHanhDienTu trong VS Code (thu muc chua README.md).");
        }

        Database db;
        try {
            db = new Database(Path.of("data", "baohanh.db"), schema);
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("No suitable driver")) {
                fail("THIEU DRIVER SQLITE (sqlite-jdbc)",
                     "Chay file tai-thu-vien.bat (Windows) hoac tai-thu-vien.sh de tai thu vien vao thu muc lib/, roi chay lai.",
                     "Trong VS Code: sau khi tai xong, nhan Ctrl+Shift+P -> 'Java: Clean Java Language Server Workspace' neu van bao loi.");
            }
            throw e;
        }
        if (db.getMigratedFrom() != null)
            System.out.println("Phat hien CSDL phien ban cu, da cat sang data/" + db.getMigratedFrom() + " va tao CSDL moi.");

        if (db.getUpgradedFrom() > 0)
            System.out.println("Da nang cap CSDL tu phien ban " + db.getUpgradedFrom() + " len " + Database.SCHEMA_VERSION + " (giu nguyen du lieu).");
        AppContext app = new AppContext(db, port);
        if (db.isFresh()) {
            System.out.println("CSDL moi: dang tao du lieu mau...");
            SeedData.run(app);
        } else {
            int filled = SeedData.upgradeSampleData(app);
            if (filled > 0) System.out.println("Da bo sung dia chi cho " + filled + " khach hang mau.");
            int acc = SeedData.upgradeSampleAccounts(app);
            if (acc > 0) System.out.println("Da tao " + acc + " tai khoan khach hang mau (mat khau " + SeedData.SAMPLE_PASSWORD + ").");
        }

        ApiServer server;
        try {
            server = new ApiServer(app, port);
        } catch (java.net.BindException e) {
            fail("Cong " + port + " dang bi chuong trinh khac su dung (co the ban dang chay he thong o cua so khac).",
                 "Hay tat chuong trinh do, hoac chay voi cong khac, vd: run.bat 9090");
            return;
        }
        server.start();
        app.backup.startAuto();
        app.notify.start();
        String url = "http://localhost:" + port;
        System.out.println("==============================================================");
        System.out.println(" HE THONG QUAN LY BAO HANH DIEN TU dang chay");
        System.out.println(" CSDL SQLite        : " + db.getFile().toAbsolutePath());
        System.out.println(" Giao dien web      : " + url);
        System.out.println(" Cong tra cuu khach : " + app.settings.portalBase() + "/tra-cuu");
        System.out.println(" Tai khoan mau      : admin/admin123, tiepnhan/123456, ktv1/123456");
        System.out.println(" Khach hang mau     : SDT 0903123456, 0912345678, 0987654321, 0934567890, 0978111222,");
        System.out.println("   (cong tra cuu)     0909888777, 0965432100 - mat khau " + SeedData.SAMPLE_PASSWORD);
        System.out.println(" Sao luu tu dong    : " + app.backup.dir());
        System.out.println(" Nhan Ctrl+C (hoac nut Stop) de dung");
        System.out.println("==============================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try { db.close(); } catch (SQLException ignored) { }
        }));

        if (gui) SwingApp.launch(app, url);
        if (a.contains("--browser") || !gui) openBrowser(url, !a.contains("--no-gui") || a.contains("--browser"));
    }

    private static void openBrowser(String url, boolean wanted) {
        if (!wanted) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ignored) {
            // không mở được trình duyệt thì người dùng tự mở
        }
    }

    private static void fail(String... lines) {
        System.err.println("==============================================================");
        for (String l : lines) System.err.println(" " + l);
        System.err.println("==============================================================");
        System.exit(1);
    }
}
