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
import vn.baohanh.model.Role;
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
        if (u != null) Auth.require(u, Role.ADMIN);
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
            Files.move(zipTmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
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
            return s.filter(p -> p.getFileName().toString().matches("sao-luu-[\\w-]+\\.zip")).toList();
        }
    }

    private static Row info(Path p) throws IOException {
        String n = p.getFileName().toString();
        return new Row().with("name", n).with("size", Files.size(p))
                .with("time", LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()), ZoneId.systemDefault())
                        .format(Database.TS))
                .with("kind", n.contains("-tu-dong") ? "Tự động" : n.contains("-truoc-khoi-phuc") ? "Trước khi khôi phục" : "Thủ công");
    }

    public List<Row> list(User u) throws IOException {
        Auth.require(u, Role.ADMIN);
        List<Row> out = new ArrayList<>();
        for (Path p : files()) out.add(info(p));
        out.sort((a, b) -> b.str("name").compareTo(a.str("name")));
        return out;
    }

    /** Đường dẫn file sao lưu (kiểm tra tên để chống truy cập file ngoài thư mục). */
    public Path file(User u, String name) throws IOException {
        Auth.require(u, Role.ADMIN);
        if (name == null || !name.matches("sao-luu-[\\w-]+\\.zip")) throw new AppException("Tên bản sao lưu không hợp lệ");
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) throw AppException.notFound("bản sao lưu " + name);
        return p;
    }

    /** Khôi phục từ một bản sao lưu. Trả về tên bản sao lưu an toàn đã tạo trước khi khôi phục. */
    public String restore(User u, String name) throws SQLException, IOException {
        Path zipFile = file(u, name);
        Path work = Files.createTempDirectory("khoi-phuc-");
        try {
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
