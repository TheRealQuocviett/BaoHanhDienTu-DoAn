package vn.baohanh.service;

/**
 * Lỗi nghiệp vụ hiển thị được cho người dùng.
 * status: mã HTTP tương ứng (400 dữ liệu sai, 401 chưa đăng nhập, 403 không có quyền, 404 không tìm thấy).
 */
public class AppException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public AppException(String message) {
        this(400, message);
    }

    public AppException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static AppException notFound(String what) {
        return new AppException(404, "Không tìm thấy " + what);
    }

    public static AppException forbidden() {
        return new AppException(403, "Bạn không có quyền thực hiện chức năng này");
    }
}
