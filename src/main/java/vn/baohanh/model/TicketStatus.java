package vn.baohanh.model;

import java.util.List;

/**
 * Trạng thái phiếu bảo hành, các bước chuyển hợp lệ và vai trò được phép chuyển.
 */
public enum TicketStatus {
    RECEIVED("Đã tiếp nhận"),
    DIAGNOSING("Đang kiểm tra"),
    SENT_TO_VENDOR("Đã gửi hãng"),
    REPAIRING("Đang sửa chữa"),
    WAITING_PARTS("Chờ linh kiện"),
    READY("Sửa xong – chờ trả"),
    REJECTED("Từ chối bảo hành"),
    RETURNED("Đã trả khách"),
    CANCELLED("Đã hủy");

    private final String label;

    TicketStatus(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Các trạng thái có thể chuyển tới từ trạng thái hiện tại. */
    public List<TicketStatus> next() {
        return switch (this) {
            case RECEIVED -> List.of(DIAGNOSING, CANCELLED);
            case DIAGNOSING -> List.of(REPAIRING, SENT_TO_VENDOR, WAITING_PARTS, REJECTED);
            case SENT_TO_VENDOR -> List.of(READY, REJECTED);
            case REPAIRING -> List.of(READY, WAITING_PARTS);
            case WAITING_PARTS -> List.of(REPAIRING);
            case READY, REJECTED -> List.of(RETURNED);
            case RETURNED, CANCELLED -> List.of();
        };
    }

    public boolean canMoveTo(TicketStatus target) { return next().contains(target); }

    /** Phiếu đã đóng. */
    public boolean isClosed() { return this == RETURNED || this == CANCELLED; }

    /** Có tính là "quá hẹn" nếu qua ngày hẹn trả hay không. */
    public boolean countsOverdue() {
        return !isClosed() && this != READY && this != REJECTED;
    }

    /** Có được thêm/bớt linh kiện, sửa tiền công ở trạng thái này không. */
    public boolean allowsCostEdit() {
        return this == DIAGNOSING || this == REPAIRING || this == WAITING_PARTS;
    }

    /** Vai trò được chuyển phiếu SANG trạng thái này. */
    public boolean allowedFor(Role r) {
        return switch (this) {
            case CANCELLED, RETURNED, RECEIVED -> r == Role.ADMIN || r == Role.RECEPTION;
            default -> r == Role.ADMIN || r == Role.TECHNICIAN;
        };
    }

    /** Chuỗi SQL liệt kê các trạng thái, vd ('RETURNED','CANCELLED'). */
    public static String sqlList(TicketStatus... list) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < list.length; i++) sb.append(i > 0 ? "," : "").append('\'').append(list[i].name()).append('\'');
        return sb.append(')').toString();
    }

    public static final String CLOSED_SQL = sqlList(RETURNED, CANCELLED);
    public static final String NOT_OVERDUE_SQL = sqlList(RETURNED, CANCELLED, READY, REJECTED);
}
