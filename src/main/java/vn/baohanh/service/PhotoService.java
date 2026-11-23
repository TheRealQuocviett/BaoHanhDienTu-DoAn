package vn.baohanh.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.model.User;

/**
 * Ảnh tình trạng máy gắn với phiếu (chụp lúc nhận máy, trong lúc sửa…).
 * File lưu ở data/photos/&lt;mã phiếu id&gt;/, CSDL chỉ lưu thông tin file.
 * Chỉ nhận JPEG / PNG / WebP (kiểm tra bằng nội dung file, không tin phần mở rộng), tối đa 8 MB, 12 ảnh mỗi phiếu.
 */
public class PhotoService {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PER_TICKET = 12;

    private final Database db;
    private final AuditService audit;
    private final Path dir;

    public PhotoService(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
        Path parent = db.getFile().toAbsolutePath().getParent();
        this.dir = parent.resolve("photos");
    }

    public Path dir() { return dir; }

    /** Nhận dạng loại ảnh theo các byte đầu file. */
    static String detect(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static String ext(String type) {
        return switch (type) { case "image/png" -> "png"; case "image/webp" -> "webp"; default -> "jpg"; };
    }

    public Row add(User u, long ticketId, byte[] data, String originalName, String note) throws SQLException, IOException {
        Auth.require(u);
        if (data == null || data.length == 0) throw new AppException("File ảnh trống");
        if (data.length > MAX_BYTES) throw new AppException("Ảnh quá lớn (tối đa 8 MB)");
        String type = detect(data);
        if (type == null) throw new AppException("Chỉ nhận ảnh JPG, PNG hoặc WebP");
        Row t = db.one("SELECT id, code, status FROM ticket WHERE id = ?", ticketId);
        if (t == null) throw AppException.notFound("phiếu #" + ticketId);
        if (TicketStatus.valueOf(t.str("status")).isClosed()) throw new AppException("Phiếu đã đóng, không thêm ảnh được");
        if (db.scalar("SELECT COUNT(*) FROM ticket_photo WHERE ticket_id = ?", ticketId) >= MAX_PER_TICKET)
            throw new AppException("Mỗi phiếu tối đa " + MAX_PER_TICKET + " ảnh");
        String file = UUID.randomUUID() + "." + ext(type);
        Path folder = dir.resolve(String.valueOf(ticketId));
        Files.createDirectories(folder);
        Files.write(folder.resolve(file), data);
        String name = originalName == null ? "" : originalName.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        long id = db.insert("INSERT INTO ticket_photo (ticket_id, file_name, original_name, content_type, size, note, uploaded_by, uploaded_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ticketId, file, name, type, data.length,
                note == null || note.isBlank() ? null : note.trim(), u.id(), Database.now());
        audit.log(u, "THÊM ẢNH", "ticket", ticketId, t.str("code") + ": " + name);
        return db.one("SELECT * FROM ticket_photo WHERE id = ?", id);
    }

    public List<Row> list(User u, long ticketId) throws SQLException {
        Auth.require(u);
        return list(ticketId);
    }

    /** Không kiểm tra quyền – dùng nội bộ (phiếu in). */
    public List<Row> list(long ticketId) throws SQLException {
        return db.query("SELECT p.id, p.ticket_id, p.original_name, p.content_type, p.size, p.note, p.uploaded_at, p.uploaded_by, "
                + "COALESCE(us.full_name, '') AS user_name FROM ticket_photo p LEFT JOIN app_user us ON us.id = p.uploaded_by "
                + "WHERE p.ticket_id = ? ORDER BY p.id", ticketId);
    }

    /** Nội dung ảnh: [0] = content type, [1] = byte[]. */
    public Object[] read(User u, long photoId) throws SQLException, IOException {
        Auth.require(u);
        return readInternal(photoId);
    }

    public Object[] readInternal(long photoId) throws SQLException, IOException {
        Row p = db.one("SELECT * FROM ticket_photo WHERE id = ?", photoId);
        if (p == null) throw AppException.notFound("ảnh");
        Path f = dir.resolve(String.valueOf(p.lng("ticket_id"))).resolve(p.str("file_name"));
        if (!Files.isRegularFile(f)) throw AppException.notFound("file ảnh (có thể đã bị xóa khỏi thư mục data/photos)");
        return new Object[]{p.str("content_type"), Files.readAllBytes(f)};
    }

    public void delete(User u, long photoId) throws SQLException, IOException {
        Auth.require(u);
        Row p = db.one("SELECT p.*, t.code FROM ticket_photo p JOIN ticket t ON t.id = p.ticket_id WHERE p.id = ?", photoId);
        if (p == null) throw AppException.notFound("ảnh");
        if (!u.is(Role.ADMIN) && p.lng("uploaded_by") != u.id()) throw new AppException(403, "Chỉ người tải ảnh lên hoặc quản lý được xóa ảnh");
        db.update("DELETE FROM ticket_photo WHERE id = ?", photoId);
        Files.deleteIfExists(dir.resolve(String.valueOf(p.lng("ticket_id"))).resolve(p.str("file_name")));
        audit.log(u, "XÓA ẢNH", "ticket", p.lng("ticket_id"), p.str("code") + ": " + p.str("original_name"));
    }

    // ===================== Ảnh khách gửi kèm yêu cầu online =====================

    public static final int MAX_PER_REQUEST = 4;

    /** Khách tải ảnh cho yêu cầu của mình (yêu cầu còn đang chờ). */
    public Row addRequestPhoto(long customerId, long requestId, byte[] data, String originalName) throws SQLException, IOException {
        if (data == null || data.length == 0) throw new AppException("File ảnh trống");
        if (data.length > MAX_BYTES) throw new AppException("Ảnh quá lớn (tối đa 8 MB)");
        String type = detect(data);
        if (type == null) throw new AppException("Chỉ nhận ảnh JPG, PNG hoặc WebP");
        Row r = db.one("SELECT id, status FROM service_request WHERE id = ? AND customer_id = ?", requestId, customerId);
        if (r == null) throw AppException.notFound("yêu cầu");
        if (!List.of("NEW", "CONFIRMED").contains(r.str("status"))) throw new AppException("Yêu cầu đã được xử lý, không thêm ảnh được");
        if (db.scalar("SELECT COUNT(*) FROM request_photo WHERE request_id = ?", requestId) >= MAX_PER_REQUEST)
            throw new AppException("Mỗi yêu cầu tối đa " + MAX_PER_REQUEST + " ảnh");
        String file = UUID.randomUUID() + "." + ext(type);
        Path folder = dir.resolve("yc-" + requestId);
        Files.createDirectories(folder);
        Files.write(folder.resolve(file), data);
        String name = originalName == null ? "" : originalName.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        long id = db.insert("INSERT INTO request_photo (request_id, file_name, original_name, content_type, size, uploaded_at) VALUES (?, ?, ?, ?, ?, ?)",
                requestId, file, name, type, data.length, Database.now());
        return db.one("SELECT id, request_id, original_name, content_type, size, uploaded_at FROM request_photo WHERE id = ?", id);
    }

    public List<Row> requestPhotos(long requestId) throws SQLException {
        return db.query("SELECT id, request_id, original_name, content_type, size, uploaded_at FROM request_photo WHERE request_id = ? ORDER BY id", requestId);
    }

    /** Đọc ảnh yêu cầu. customerId &gt; 0: chỉ cho chủ yêu cầu; customerId = 0: nhân viên (đã kiểm tra đăng nhập). */
    public Object[] readRequestPhoto(long customerId, long photoId) throws SQLException, IOException {
        Row p = db.one("SELECT rp.*, r.customer_id FROM request_photo rp JOIN service_request r ON r.id = rp.request_id WHERE rp.id = ?", photoId);
        if (p == null || (customerId > 0 && p.lng("customer_id") != customerId)) throw AppException.notFound("ảnh");
        Path f = dir.resolve("yc-" + p.lng("request_id")).resolve(p.str("file_name"));
        if (!Files.isRegularFile(f)) throw AppException.notFound("file ảnh");
        return new Object[]{p.str("content_type"), Files.readAllBytes(f)};
    }

    public void deleteRequestPhoto(long customerId, long photoId) throws SQLException, IOException {
        Row p = db.one("SELECT rp.*, r.customer_id, r.status FROM request_photo rp JOIN service_request r ON r.id = rp.request_id WHERE rp.id = ?", photoId);
        if (p == null || p.lng("customer_id") != customerId) throw AppException.notFound("ảnh");
        if (!List.of("NEW", "CONFIRMED").contains(p.str("status"))) throw new AppException("Yêu cầu đã được xử lý");
        db.update("DELETE FROM request_photo WHERE id = ?", photoId);
        Files.deleteIfExists(dir.resolve("yc-" + p.lng("request_id")).resolve(p.str("file_name")));
    }

    /** Khi tiếp nhận máy từ yêu cầu: chép ảnh khách gửi sang phiếu (ghi chú "Ảnh khách gửi online"). */
    public int copyRequestPhotosToTicket(long requestId, long ticketId, User u) throws SQLException, IOException {
        int n = 0;
        for (Row p : db.query("SELECT * FROM request_photo WHERE request_id = ? ORDER BY id", requestId)) {
            Path src = dir.resolve("yc-" + requestId).resolve(p.str("file_name"));
            if (!Files.isRegularFile(src)) continue;
            Path folder = dir.resolve(String.valueOf(ticketId));
            Files.createDirectories(folder);
            String file = UUID.randomUUID() + "." + ext(p.str("content_type"));
            Files.copy(src, folder.resolve(file));
            db.insert("INSERT INTO ticket_photo (ticket_id, file_name, original_name, content_type, size, note, uploaded_by, uploaded_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ticketId, file, p.str("original_name"), p.str("content_type"), p.lng("size"),
                    "Ảnh khách gửi online", u.id(), Database.now());
            n++;
        }
        return n;
    }
}
