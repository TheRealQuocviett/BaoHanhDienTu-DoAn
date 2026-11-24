package vn.baohanh.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quyền chi tiết theo chức năng. Mỗi quyền có vai trò mặc định (giống phân quyền cũ);
 * quản lý có thể bật / tắt riêng cho từng tài khoản Tiếp nhận hoặc Kỹ thuật viên.
 * Quản lý (ADMIN) luôn có mọi quyền – để không ai tự khóa mình khỏi hệ thống.
 * Riêng quản lý người dùng & phân quyền luôn chỉ dành cho Quản lý.
 */
public enum Perm {
    ACTIVATE("Tiếp nhận & bảo hành", "Kích hoạt bảo hành", "Kích hoạt bảo hành điện tử khi bán máy", Role.RECEPTION),
    RECEIVE("Tiếp nhận & bảo hành", "Tiếp nhận máy, tạo phiếu", "Tạo phiếu bảo hành khi khách mang máy tới", Role.RECEPTION),
    OFFICE("Tiếp nhận & bảo hành", "Duyệt báo giá, phân công, hủy phiếu", "Ghi nhận khách đồng ý/từ chối báo giá, giao phiếu cho kỹ thuật viên, hủy phiếu", Role.RECEPTION),
    PAYMENT("Tiếp nhận & bảo hành", "Thu tiền & trả máy", "Thu tiền sửa chữa, xuất hóa đơn, trả máy cho khách", Role.RECEPTION),
    EXTEND("Tiếp nhận & bảo hành", "Bán gói bảo hành mở rộng", "Gia hạn bảo hành có thu phí", Role.RECEPTION),
    REQUESTS("Tiếp nhận & bảo hành", "Xử lý yêu cầu online", "Xác nhận / từ chối / chuyển yêu cầu của khách thành phiếu", Role.RECEPTION),
    REPAIR("Tiếp nhận & bảo hành", "Sửa chữa: chi phí, linh kiện, báo giá", "Cập nhật kết luận, tiền công, dùng linh kiện, gửi báo giá", Role.TECHNICIAN),
    DELETE_WARRANTY("Tiếp nhận & bảo hành", "Xóa bảo hành đã kích hoạt", "Chỉ xóa được bảo hành chưa có phiếu"),
    CUSTOMERS("Danh mục", "Thêm / sửa khách hàng", "Kể cả nhập khách hàng từ Excel", Role.RECEPTION),
    CATALOG("Danh mục", "Sửa sản phẩm, hãng, loại, trung tâm BH", "Kể cả nhập sản phẩm từ Excel"),
    PARTS("Kho", "Thêm / sửa linh kiện, nhà cung cấp", "Kể cả nhập linh kiện từ Excel"),
    STOCK("Kho", "Nhập kho, kiểm kê", "Cộng tồn kho, điều chỉnh theo số đếm thực tế"),
    STOCK_VIEW("Kho", "Xem thẻ kho", "Lịch sử nhập / xuất của từng linh kiện", Role.TECHNICIAN),
    PURCHASE_VIEW("Kho", "Xem đặt hàng linh kiện", "Danh sách linh kiện cần đặt, đơn đặt hàng", Role.TECHNICIAN),
    PURCHASE("Kho", "Tạo / nhận / hủy đơn đặt hàng", "Lập đơn gửi nhà cung cấp và nhập kho khi hàng về"),
    REPORTS("Báo cáo", "Báo cáo thống kê, xuất Excel", "Doanh thu, tỷ lệ bảo hành, linh kiện, đánh giá"),
    KPI("Báo cáo", "KPI kỹ thuật viên", "Bảng xếp hạng (kỹ thuật viên chỉ thấy tiền của mình)", Role.TECHNICIAN),
    FAULTS("Báo cáo", "Thống kê lỗi thường gặp", "Model hay hỏng, nhóm lỗi, linh kiện thay nhiều", Role.TECHNICIAN),
    NOTIFY_VIEW("Quản trị", "Xem & gửi lại thông báo cho khách", "Danh sách email / SMS / Zalo đã gửi", Role.RECEPTION),
    NOTIFY_CONFIG("Quản trị", "Cấu hình kênh thông báo", "Tài khoản email, SMS, Zalo và mẫu tin"),
    SETTINGS("Quản trị", "Sửa thông tin cửa hàng", "Tên, địa chỉ, tài khoản ngân hàng, mức hoa hồng…"),
    BACKUP("Quản trị", "Sao lưu / khôi phục dữ liệu", "Tạo bản sao lưu, tải về, khôi phục"),
    AUDIT("Quản trị", "Xem nhật ký hệ thống", "Ai làm gì, lúc nào");

    public final String group, label, hint;
    private final Set<Role> defaults;

    Perm(String group, String label, String hint, Role... extra) {
        this.group = group;
        this.label = label;
        this.hint = hint;
        this.defaults = EnumSet.of(Role.ADMIN, extra);
    }

    public boolean defaultFor(Role r) { return defaults.contains(r); }

    /** Quyền bật/tắt riêng: id người dùng → (quyền → có/không). Do PermissionService nạp từ CSDL. */
    private static final Map<Long, Map<Perm, Boolean>> OVERRIDES = new ConcurrentHashMap<>();

    public static void setOverrides(long userId, Map<Perm, Boolean> m) {
        if (m == null || m.isEmpty()) OVERRIDES.remove(userId);
        else OVERRIDES.put(userId, Map.copyOf(m));
    }

    public static void clearOverrides() { OVERRIDES.clear(); }

    public static Map<Perm, Boolean> overridesOf(long userId) { return OVERRIDES.getOrDefault(userId, Map.of()); }

    public boolean allows(User u) {
        if (u == null) return false;
        if (u.role() == Role.ADMIN) return true;
        Boolean o = OVERRIDES.getOrDefault(u.id(), Map.of()).get(this);
        return o != null ? o : defaultFor(u.role());
    }

    /** Danh sách mã quyền người dùng đang có (gửi cho giao diện web để ẩn/hiện menu, nút). */
    public static List<String> of(User u) {
        return java.util.Arrays.stream(values()).filter(p -> p.allows(u)).map(Enum::name).toList();
    }
}
