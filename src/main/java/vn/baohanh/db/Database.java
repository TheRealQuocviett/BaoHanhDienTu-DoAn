package vn.baohanh.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Kết nối CSDL SQLite và các hàm truy vấn tiện dụng.
 *
 * - Tạo bảng từ database/schema.sql khi chạy lần đầu.
 * - CSDL phiên bản 1 (cấu trúc khác hẳn) được đổi tên thành baohanh-cu-*.db để giữ lại, rồi tạo mới.
 * - CSDL phiên bản 2, 3 được nâng cấp tại chỗ lên phiên bản mới nhất (chỉ thêm bảng mới, giữ nguyên dữ liệu).
 * - Dùng một kết nối duy nhất; mọi hàm đều synchronized nên an toàn khi
 *   giao diện web và Swing cùng truy cập.
 */
public final class Database implements AutoCloseable {

    public static final int SCHEMA_VERSION = 7;
    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Một khối công việc chạy trong transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run() throws SQLException;
    }

    private final Path dbFile;
    private Connection conn;
    private boolean fresh;          // CSDL vừa được tạo mới
    private String migratedFrom;    // tên file cũ đã được cất đi (nếu có)
    private int upgradedFrom;       // phiên bản trước khi nâng cấp tại chỗ (0 = không nâng cấp)
    private final Path schemaFile;

    public Database(Path dbFile, Path schemaFile) throws SQLException, IOException {
        this.dbFile = dbFile;
        this.schemaFile = schemaFile;
        Path dir = dbFile.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        open();
        int version = (int) scalar("PRAGMA user_version");
        long tables = scalar("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'");
        if (tables > 0 && version < 2) {
            // CSDL phiên bản cũ: cất đi rồi tạo mới
            conn.close();
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path old = dbFile.resolveSibling("baohanh-cu-" + stamp + ".db");
            Files.move(dbFile, old, StandardCopyOption.REPLACE_EXISTING);
            for (String ext : new String[]{"-wal", "-shm"}) {
                Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + ext));
            }
            migratedFrom = old.getFileName().toString();
            open();
            tables = 0;
        }
        fresh = tables == 0;
        if (tables > 0 && version < SCHEMA_VERSION) upgradedFrom = version;
        runScript(Files.readString(schemaFile, StandardCharsets.UTF_8));
        update("PRAGMA user_version = " + SCHEMA_VERSION);
    }

    private void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }
    }

    /** Chạy file SQL: bỏ dòng chú thích "--", tách lệnh theo dấu ";". */
    private void runScript(String sql) throws SQLException {
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\\R")) {
            if (!line.trim().startsWith("--")) clean.append(line).append('\n');
        }
        try (Statement st = conn.createStatement()) {
            for (String cmd : clean.toString().split(";")) {
                if (!cmd.isBlank()) st.execute(cmd);
            }
        }
    }

    // ===================== Truy vấn =====================

    public synchronized List<Row> query(String sql, Object... params) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                String[] names = new String[n];
                for (int i = 0; i < n; i++) names[i] = md.getColumnLabel(i + 1);
                while (rs.next()) {
                    Row r = new Row();
                    for (int i = 0; i < n; i++) {
                        Object v = rs.getObject(i + 1);
                        if (v instanceof Number num && !(v instanceof Double) && !(v instanceof Float)) v = num.longValue();
                        r.put(names[i], v);
                    }
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** Dòng đầu tiên hoặc null. */
    public Row one(String sql, Object... params) throws SQLException {
        List<Row> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** INSERT và trả về id vừa tạo. */
    public synchronized long insert(String sql, Object... params) throws SQLException {
        update(sql, params);
        return scalar("SELECT last_insert_rowid()");
    }

    /** UPDATE / DELETE, trả về số dòng bị ảnh hưởng. */
    public synchronized int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Câu SELECT trả về một số. */
    public synchronized long scalar(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Chạy nhiều lệnh trong một transaction: lỗi ở đâu thì hoàn tác toàn bộ. */
    public synchronized <T> T tx(Work<T> work) throws SQLException {
        if (!conn.getAutoCommit()) return work.run(); // đã ở trong transaction
        conn.setAutoCommit(false);
        try {
            T result = work.run();
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof Boolean b) p = b ? 1 : 0;
            if (p instanceof java.time.temporal.Temporal t) p = (t instanceof LocalDateTime ldt) ? ts(ldt) : t.toString();
            ps.setObject(i + 1, p);
        }
    }

    public static String ts(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }

    public static String now() {
        return ts(LocalDateTime.now());
    }

    // ===================== Sao lưu / khôi phục =====================

    /** Ghi bản sao nhất quán của CSDL ra file (lệnh VACUUM INTO của SQLite). */
    public synchronized void snapshotTo(Path target) throws SQLException {
        String path = target.toAbsolutePath().toString().replace("'", "''");
        try (Statement st = conn.createStatement()) {
            st.execute("VACUUM INTO '" + path + "'");
        }
    }

    /** Thay CSDL hiện tại bằng một file sao lưu rồi mở lại (dùng khi khôi phục). */
    public synchronized void replaceWith(Path source) throws SQLException, IOException {
        conn.close();
        try {
            Files.copy(source, dbFile, StandardCopyOption.REPLACE_EXISTING);
            for (String ext : new String[]{"-wal", "-shm"}) Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + ext));
        } finally {
            open();
        }
        runScript(Files.readString(schemaFile, StandardCharsets.UTF_8));   // bản sao lưu cũ hơn cũng được nâng cấp
        update("PRAGMA user_version = " + SCHEMA_VERSION);
    }

    public boolean isFresh() { return fresh; }
    public int getUpgradedFrom() { return upgradedFrom; }
    public String getMigratedFrom() { return migratedFrom; }
    public Path getFile() { return dbFile; }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }
}
