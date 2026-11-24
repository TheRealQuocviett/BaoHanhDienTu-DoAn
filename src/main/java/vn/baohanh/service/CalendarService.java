package vn.baohanh.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.model.User;

/**
 * Lịch hẹn theo ngày / tuần, gom từ dữ liệu sẵn có:
 * - APPOINTMENT: khách hẹn mang máy tới (yêu cầu online đã xác nhận).
 * - REQUESTED:   khách đề xuất ngày nhưng cửa hàng chưa xác nhận.
 * - DUE:         hẹn trả máy (phiếu đang xử lý, theo ngày hẹn trả).
 * - READY:       máy sửa xong chờ khách nhận (theo ngày hẹn trả).
 * - OVERDUE:     phiếu đã quá hẹn trả (hiện ở ngày hôm nay nếu hôm nay nằm trong khoảng xem).
 * Kỹ thuật viên chỉ thấy phiếu của mình (và phiếu chưa phân công), không thấy lịch khách hẹn.
 */
public class CalendarService {
    public static final String[][] KINDS = {
            {"APPOINTMENT", "Khách hẹn mang máy tới"}, {"REQUESTED", "Khách đề xuất (chưa xác nhận)"},
            {"DUE", "Hẹn trả máy"}, {"READY", "Sửa xong – chờ khách nhận"}, {"OVERDUE", "Quá hẹn trả"}};

    private final Database db;

    public CalendarService(Database db) {
        this.db = db;
    }

    /** Sự kiện trong khoảng [from, to]. technicianId &gt; 0: chỉ phiếu của kỹ thuật viên đó. */
    public Row events(User u, String fromStr, String toStr, long technicianId) throws SQLException {
        Auth.require(u);
        LocalDate from, to;
        try {
            from = fromStr == null || fromStr.isBlank() ? WarrantyService.today() : LocalDate.parse(fromStr);
            to = toStr == null || toStr.isBlank() ? from.plusDays(6) : LocalDate.parse(toStr);
        } catch (Exception e) {
            throw new AppException("Ngày không hợp lệ");
        }
        if (to.isBefore(from)) throw new AppException("Ngày kết thúc phải sau ngày bắt đầu");
        if (from.plusDays(62).isBefore(to)) throw new AppException("Chỉ xem tối đa 2 tháng một lần");
        boolean tech = u.is(Role.TECHNICIAN) && !u.is(Role.ADMIN);
        long techFilter = tech ? u.id() : technicianId;
        LocalDate today = WarrantyService.today();
        List<Row> ev = new ArrayList<>();

        if (!tech) {
            for (Row r : db.query("SELECT r.id, r.code, r.status, r.appointment, r.preferred_date, r.issue, r.staff_note, "
                    + "cu.name AS customer_name, cu.phone AS customer_phone, p.name AS product_name FROM service_request r "
                    + "JOIN customer cu ON cu.id = r.customer_id JOIN warranty w ON w.id = r.warranty_id JOIN product p ON p.id = w.product_id "
                    + "WHERE (r.status = 'CONFIRMED' AND r.appointment BETWEEN ? AND ?) "
                    + "OR (r.status = 'NEW' AND r.preferred_date BETWEEN ? AND ?) ORDER BY r.id", from, to, from, to)) {
                boolean confirmed = "CONFIRMED".equals(r.str("status"));
                ev.add(new Row().with("kind", confirmed ? "APPOINTMENT" : "REQUESTED")
                        .with("date", confirmed ? r.str("appointment") : r.str("preferred_date"))
                        .with("request_id", r.lng("id")).with("code", r.str("code"))
                        .with("title", r.str("customer_name") + " – " + r.str("product_name"))
                        .with("customer_name", r.str("customer_name")).with("customer_phone", r.str("customer_phone"))
                        .with("product_name", r.str("product_name")).with("note", r.str("issue")));
            }
        }

        String techSql = techFilter > 0 ? "AND (t.technician_id = ? OR t.technician_id IS NULL) " : "";
        List<Object> p = new ArrayList<>(List.of(from, to));
        if (techFilter > 0) p.add(techFilter);
        for (Row t : db.query("SELECT t.id, t.code, t.status, t.due_date, t.issue, cu.name AS customer_name, cu.phone AS customer_phone, "
                + "p.name AS product_name, COALESCE(us.full_name, '') AS technician FROM ticket t JOIN warranty w ON w.id = t.warranty_id "
                + "JOIN customer cu ON cu.id = w.customer_id JOIN product p ON p.id = w.product_id LEFT JOIN app_user us ON us.id = t.technician_id "
                + "WHERE t.status NOT IN ('RETURNED','CANCELLED') AND t.due_date BETWEEN ? AND ? " + techSql + "ORDER BY t.due_date, t.id",
                p.toArray())) {
            ev.add(ticketEvent(t, "READY".equals(t.str("status")) ? "READY" : "DUE", t.str("due_date")));
        }

        // Phiếu quá hẹn: hiện ở ngày hôm nay
        if (!today.isBefore(from) && !today.isAfter(to)) {
            List<Object> q = new ArrayList<>(List.of(today));
            if (techFilter > 0) q.add(techFilter);
            for (Row t : db.query("SELECT t.id, t.code, t.status, t.due_date, t.issue, cu.name AS customer_name, cu.phone AS customer_phone, "
                    + "p.name AS product_name, COALESCE(us.full_name, '') AS technician FROM ticket t JOIN warranty w ON w.id = t.warranty_id "
                    + "JOIN customer cu ON cu.id = w.customer_id JOIN product p ON p.id = w.product_id LEFT JOIN app_user us ON us.id = t.technician_id "
                    + "WHERE t.status NOT IN ('RETURNED','CANCELLED','READY') AND t.due_date < ? " + techSql + "ORDER BY t.due_date",
                    q.toArray())) {
                Row e = ticketEvent(t, "OVERDUE", today.toString());
                e.put("note", "Hẹn trả " + vn.baohanh.util.Fmt.date(t.str("due_date")) + " · " + e.str("note"));
                ev.add(e);
            }
        }

        ev.sort((a, b) -> {
            int c = a.str("date").compareTo(b.str("date"));
            return c != 0 ? c : Integer.compare(order(a.str("kind")), order(b.str("kind")));
        });
        Row out = new Row();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("today", today.toString());
        out.put("events", ev);
        out.put("technicians", db.query("SELECT id, full_name FROM app_user WHERE role = 'TECHNICIAN' AND active = 1 ORDER BY full_name"));
        List<Row> kinds = new ArrayList<>();
        for (String[] k : KINDS) kinds.add(new Row().with("key", k[0]).with("label", k[1]));
        out.put("kinds", kinds);
        return out;
    }

    private static int order(String kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i][0].equals(kind)) return i == 4 ? -1 : i;   // quá hẹn lên đầu
        return 9;
    }

    private static Row ticketEvent(Row t, String kind, String date) {
        return new Row().with("kind", kind).with("date", date).with("ticket_id", t.lng("id")).with("code", t.str("code"))
                .with("title", t.str("customer_name") + " – " + t.str("product_name"))
                .with("customer_name", t.str("customer_name")).with("customer_phone", t.str("customer_phone"))
                .with("product_name", t.str("product_name")).with("technician", t.str("technician"))
                .with("status_label", TicketStatus.valueOf(t.str("status")).getLabel()).with("note", t.str("issue"));
    }
}
