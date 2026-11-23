package vn.baohanh.model;

/** Vai trò người dùng và nhãn hiển thị. */
public enum Role {
    ADMIN("Quản lý"),
    RECEPTION("Nhân viên tiếp nhận"),
    TECHNICIAN("Kỹ thuật viên");

    private final String label;

    Role(String label) { this.label = label; }

    public String getLabel() { return label; }
}
