package vn.baohanh.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.User;

/**
 * Sao lưu & khôi phục.
 * Mỗi bản sao lưu là một file .zip trong data/backup/ gồm CSDL (baohanh.db) và thư mục ảnh (photos/).
 * - Tự động: khi khởi động (nếu bản gần nhất cũ hơn 24 giờ) và mỗi 24 giờ; giữ lại 20 bản tự động gần nhất.
 * - Thủ công: quản lý bấm "Sao lưu ngay".
 * - Khôi phục: tự sao lưu trạng thái hiện tại trước, rồi thay CSDL + ảnh bằng bản đã chọn.
 */
public class BackupService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int KEEP_AUTO = 20;
    /** Tên file sao lưu: .zip (thường) hoặc .zip.enc (đã mã hóa bằng mật khẩu). */
    public static final String NAME_RE = "sao-luu-[\\w-]+\\.zip(\\.enc)?";
    public static final String PASSWORD_KEY = "backup_password";
    public static final int MIN_PASSWORD = 8;
    private SettingService settings;

    private final Database db;
    private final PhotoService photos;
    private final AuditService audit;
    private final Path dir;
    private ScheduledExecutorService scheduler;

    public BackupService(Database db, PhotoService photos, AuditService audit) {
        this.db = db;
        this.photos = photos;
        this.audit = audit;
        this.dir = db.getFile().toAbsolutePath().getParent().resolve("backup");
    }

    public Path dir() { return dir; }

    public void setSettings(SettingService s) { this.settings = s; }

    private String password() throws SQLException {
        return settings == null ? "" : settings.get(PASSWORD_KEY);
    }

    /** Trạng thái mã hóa (không bao giờ trả mật khẩu ra ngoài). */
    public Row config(User u) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.BACKUP);
        return new Row().with("encrypted", !password().isEmpty()).with("min_length", MIN_PASSWORD);
    }

    /** Đặt / đổi / tắt (chuỗi rỗng) mật khẩu mã hóa sao lưu. Áp dụng cho các bản sao lưu tạo từ giờ trở đi. */
    public Row setPassword(User u, String pw, String confirm) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.BACKUP);
        String p = pw == null ? "" : pw;
        if (!p.isEmpty()) {
            if (p.length() < MIN_PASSWORD) throw new AppException("Mật khẩu sao lưu phải có ít nhất " + MIN_PASSWORD + " ký tự");
            if (!p.equals(confirm)) throw new AppException("Mật khẩu nhập lại không khớp");
        }
        settings.put(PASSWORD_KEY, p);
        audit.log(u, p.isEmpty() ? "TẮT MÃ HÓA SAO LƯU" : "ĐẶT MẬT KHẨU SAO LƯU", "setting", null, null);
        return config(u);
    }

    /** Bật sao lưu tự động. */
    public void startAuto() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sao-luu-tu-dong");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (hoursSinceLastBackup() >= 24) create(null, "tu-dong");
            } catch (Exception e) {
                System.err.println("Sao luu tu dong loi: " + e.getMessage());
            }
        }, 1, 60, TimeUnit.MINUTES);
    }

    private long hoursSinceLastBackup() throws IOException {
        long newest = 0;
        for (Path p : files()) newest = Math.max(newest, Files.getLastModifiedTime(p).toMillis());
        return newest == 0 ? Long.MAX_VALUE : (System.currentTimeMillis() - newest) / 3_600_000;
    }

    /** Tạo bản sao lưu ngay. kind: "thu-cong" | "tu-dong" | "truoc-khoi-phuc". */
    public Row create(User u, String kind) throws SQLException, IOException {
        if (u != null) Auth.need(u, vn.baohanh.model.Perm.BACKUP);
        Files.createDirectories(dir);
        String name = "sao-luu-" + LocalDateTime.now().format(STAMP) + "-" + kind + ".zip";
        for (int i = 2; Files.exists(dir.resolve(name)); i++)
            name = "sao-luu-" + LocalDateTime.now().format(STAMP) + "-" + i + "-" + kind + ".zip";
        Path tmpDb = Files.createTempFile("baohanh-", ".db");
        Files.delete(tmpDb);                       // VACUUM INTO cần file chưa tồn tại
        try {
            db.snapshotTo(tmpDb);
            Path zipTmp = dir.resolve(name + ".tmp");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipTmp))) {
                zip.putNextEntry(new ZipEntry("baohanh.db"));
                Files.copy(tmpDb, zip);
                zip.closeEntry();
                if (Files.isDirectory(photos.dir())) {
                    try (Stream<Path> walk = Files.walk(photos.dir())) {
                        for (Path p : walk.filter(Files::isRegularFile).toList()) {
                            zip.putNextEntry(new ZipEntry("photos/" + photos.dir().relativize(p).toString().replace('\\', '/')));
                            Files.copy(p, zip);
                            zip.closeEntry();
                        }
                    }
                }
            }
            String pw = password();
            if (pw.isEmpty()) Files.move(zipTmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            else {
                name += ".enc";
                Path encTmp = dir.resolve(name + ".tmp");
                try {
                    vn.baohanh.util.BackupCrypto.encrypt(zipTmp, encTmp, pw.toCharArray());
                    Files.move(encTmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(zipTmp);
                    Files.deleteIfExists(encTmp);
                }
            }
        } finally {
            Files.deleteIfExists(tmpDb);
        }
        audit.log(u, "SAO LƯU", null, null, name);
        prune();
        return info(dir.resolve(name));
    }

    private void prune() throws IOException {
        List<Path> auto = new ArrayList<>(files().stream().filter(p -> p.getFileName().toString().contains("-tu-dong")).toList());
        auto.sort(Comparator.comparing(p -> p.getFileName().toString()));
        while (auto.size() > KEEP_AUTO) Files.deleteIfExists(auto.remove(0));
    }

    private List<Path> files() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches(NAME_RE)).toList();
        }
    }

    private static Row info(Path p) throws IOException {
        String n = p.getFileName().toString();
        return new Row().with("name", n).with("size", Files.size(p))
                .with("time", LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()), ZoneId.systemDefault())
                        .format(Database.TS))
                .with("kind", n.contains("-tu-dong") ? "Tự động" : n.contains("-truoc-khoi-phuc") ? "Trước khi khôi phục" : "Thủ công")
                .with("encrypted", n.endsWith(".enc"));
    }

    public List<Row> list(User u) throws IOException {
        Auth.need(u, vn.baohanh.model.Perm.BACKUP);
        List<Row> out = new ArrayList<>();
        for (Path p : files()) out.add(info(p));
        out.sort((a, b) -> b.str("name").compareTo(a.str("name")));
        return out;
    }

    /** Thêm một file sao lưu mang từ USB / máy khác vào thư mục sao lưu (để khôi phục). */
    public Row importFile(User u, String name, InputStream data) throws IOException, SQLException {
        Auth.need(u, vn.baohanh.model.Perm.BACKUP);
        String n = name == null ? "" : Path.of(name.replace('\\', '/')).getFileName().toString();
        if (!n.matches(NAME_RE)) throw new AppException("Tên file phải dạng sao-luu-....zip hoặc sao-luu-....zip.enc (giữ nguyên tên lúc tạo)");
        Files.createDirectories(dir);
        Path target = dir.resolve(n);
        if (Files.exists(target)) throw new AppException("Đã có bản sao lưu cùng tên trong thư mục");
        Path tmp = dir.resolve(n + ".tmp");
        try {
            Files.copy(data, tmp, StandardCopyOption.REPLACE_EXISTING);
            boolean enc = vn.baohanh.util.BackupCrypto.isEncrypted(tmp);
            if (enc != n.endsWith(".enc")) throw new AppException(enc ? "File đã mã hóa phải có đuôi .zip.enc" : "File này không phải bản mã hóa (.enc)");
            if (!enc) {
                byte[] head;
                try (InputStream in = Files.newInputStream(tmp)) { head = in.readNBytes(2); }
                if (head.length < 2 || head[0] != 'P' || head[1] != 'K') throw new AppException("File không phải bản sao lưu hợp lệ");
            }
            Files.move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
        audit.log(u, "THÊM FILE SAO LƯU", null, null, n);
        return info(target);
    }

    /** Đường dẫn file sao lưu (kiểm tra tên để chống truy cập file ngoài thư mục). */
    public Path file(User u, String name) throws IOException {
        Auth.need(u, vn.baohanh.model.Perm.BACKUP);
        if (name == null || !name.matches(NAME_RE)) throw new AppException("Tên bản sao lưu không hợp lệ");
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) throw AppException.notFound("bản sao lưu " + name);
        return p;
    }

    /** Khôi phục từ một bản sao lưu. Trả về tên bản sao lưu an toàn đã tạo trước khi khôi phục. */
    public String restore(User u, String name) throws SQLException, IOException {
        return restore(u, name, null);
    }

    /**
     * Khôi phục; file .enc cần mật khẩu (để trống thì thử mật khẩu sao lưu đang cài trên máy này).
     */
    public String restore(User u, String name, String password) throws SQLException, IOException {
        Path zipFile = file(u, name);
        Path work = Files.createTempDirectory("khoi-phuc-");
        try {
            if (vn.baohanh.util.BackupCrypto.isEncrypted(zipFile)) {
                String pw = password == null || password.isEmpty() ? password() : password;
                if (pw.isEmpty()) throw new AppException("Bản sao lưu này đã mã hóa – vui lòng nhập mật khẩu sao lưu");
                Path plain = work.resolve("giai-ma.zip");
                try {
                    vn.baohanh.util.BackupCrypto.decrypt(zipFile, plain, pw.toCharArray());
                } catch (vn.baohanh.util.BackupCrypto.BadPasswordException e) {
                    audit.log(u, "KHÔI PHỤC THẤT BẠI", null, null, name + ": sai mật khẩu");
                    throw new AppException("Sai mật khẩu sao lưu (hoặc file đã bị sửa / hỏng)");
                }
                zipFile = plain;
            }
            Path dbOut = null;
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String en = e.getName();
                    if (e.isDirectory() || en.contains("..") || en.startsWith("/") || en.contains("\\")) continue;
                    if (!en.equals("baohanh.db") && !en.startsWith("photos/")) continue;
                    Path out = work.resolve(en).normalize();
                    if (!out.startsWith(work)) continue;
                    Files.createDirectories(out.getParent());
                    copy(zin, out);
                    if (en.equals("baohanh.db")) dbOut = out;
                }
            }
            if (dbOut == null) throw new AppException("File sao lưu không hợp lệ (thiếu baohanh.db)");
            String safety = create(u, "truoc-khoi-phuc").str("name");
            db.replaceWith(dbOut);
            // Thay thư mục ảnh
            if (Files.isDirectory(photos.dir())) deleteTree(photos.dir());
            Path restoredPhotos = work.resolve("photos");
            if (Files.isDirectory(restoredPhotos)) copyTree(restoredPhotos, photos.dir());
            audit.log(u, "KHÔI PHỤC", null, null, "Từ " + name + " (đã sao lưu trước: " + safety + ")");
            return safety;
        } finally {
            deleteTree(work);
        }
    }

    private static void copy(InputStream in, Path out) throws IOException {
        try (OutputStream os = Files.newOutputStream(out)) {
            in.transferTo(os);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
