package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.util.Fmt;

/** Trang Tổng quan: số liệu nhanh, việc cần xử lý, phiếu theo trạng thái. */
public class DashboardView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final JPanel tiles = new JPanel(new GridLayout(0, 4, 10, 10));
    private final Ui.RowModel todo = new Ui.RowModel(List.of(
            Ui.Col.text("code", "Mã phiếu", 115), Ui.Col.text("product_name", "Sản phẩm", 170),
            new Ui.Col("status", "Trạng thái", Ui.Kind.STATUS, 200), Ui.Col.text("hint", "Việc cần làm", 240)));
    private final StatusBars bars = new StatusBars();
    private final javax.swing.JLabel sub = Ui.sub("");

    public DashboardView(Ui.Ctx ctx) {
        super(ctx);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Ui.title("Xin chào, " + ctx.user().fullName()), BorderLayout.NORTH);
        head.add(sub, BorderLayout.SOUTH);
        tiles.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        top.add(tiles, BorderLayout.CENTER);

        JTable t = Ui.table(todo, r -> TicketDialog.open(ctx, r.lng("id")));
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setOpaque(false);
        JPanel lh = new JPanel(new BorderLayout());
        lh.setOpaque(false);
        lh.add(Ui.bold("Cần xử lý (nhấp đúp để mở phiếu)"), BorderLayout.WEST);
        if (ctx.user().is(Role.ADMIN, Role.RECEPTION)) lh.add(Ui.primary("+ Tiếp nhận máy", () -> ReceiveDialog.open(ctx, null)), BorderLayout.EAST);
        left.add(lh, BorderLayout.NORTH);
        left.add(Ui.scroll(t), BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setOpaque(false);
        right.add(Ui.bold("Phiếu theo trạng thái"), BorderLayout.NORTH);
        right.add(Ui.card(bars), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new GridLayout(1, 2, 12, 0));
        bottom.setOpaque(false);
        bottom.add(left);
        bottom.add(right);

        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);
        body.add(top, BorderLayout.NORTH);
        body.add(bottom, BorderLayout.CENTER);
        add(body);
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            Row d = ctx.app().warranty.dashboard(user());
            sub.setText("Hôm nay " + Fmt.date(d.str("today")) + " · " + d.lng("month_received") + " phiếu nhận trong tháng · "
                    + d.lng("total_warranties") + " sản phẩm đã kích hoạt bảo hành");
            tiles.removeAll();
            MainFrame f = ctx.frame();
            if (user().is(Role.TECHNICIAN)) tiles.add(Ui.tile(Fmt.number(d.lng("my_tickets")), "Phiếu của tôi", true, () -> f.navigate("phieu", "MINE")));
            tiles.add(Ui.tile(Fmt.number(d.lng("open_tickets")), "Phiếu đang xử lý", false, () -> f.navigate("phieu", "OPEN")));
            tiles.add(Ui.tile(Fmt.number(d.lng("overdue_tickets")), "Quá hẹn trả khách", d.lng("overdue_tickets") > 0, () -> f.navigate("phieu", "OVERDUE")));
            tiles.add(Ui.tile(Fmt.number(d.lng("ready_tickets")), "Sửa xong – chờ khách nhận", false, () -> f.navigate("phieu", "READY")));
            tiles.add(Ui.tile(Fmt.number(d.lng("pending_quotes")), "Báo giá chờ khách duyệt", d.lng("pending_quotes") > 0, () -> f.navigate("phieu", "QUOTE")));
            tiles.add(Ui.tile(Fmt.number(d.lng("new_requests")), "Yêu cầu online mới", d.lng("new_requests") > 0, () -> f.navigate("yeu-cau", "OPEN")));
            tiles.add(Ui.tile(Fmt.number(d.lng("active_warranties")), "Bảo hành còn hạn", false, () -> f.navigate("bao-hanh", "active")));
            tiles.add(Ui.tile(Fmt.number(d.lng("expiring_soon")), "Sắp hết hạn (≤ 30 ngày)", false, () -> f.navigate("bao-hanh", "expiring")));
            tiles.add(Ui.tile(Fmt.number(d.lng("low_stock")), "Linh kiện sắp hết", d.lng("low_stock") > 0, () -> f.navigate("dm-part", null)));
            if (user().is(Role.ADMIN)) tiles.add(Ui.tile(Fmt.money(d.lng("month_revenue")), "Doanh thu dịch vụ tháng này", false, () -> f.navigate("bao-cao", null)));
            tiles.revalidate();
            tiles.repaint();
            @SuppressWarnings("unchecked") List<Row> list = (List<Row>) d.get("todo");
            for (Row r : list) r.put("hint", hint(r));
            todo.setRows(list);
            bars.data = (Row) d.get("by_status");
            bars.repaint();
        });
    }

    private static String hint(Row t) {
        if ("PENDING".equals(t.str("quote_status"))) return "Gọi khách duyệt giá: " + t.str("customer_phone") + " · " + Fmt.money(t.lng("total"));
        return switch (t.str("status")) {
            case "READY" -> "Gọi khách nhận máy: " + t.str("customer_phone");
            case "REJECTED" -> "Chờ trả máy cho khách";
            case "RECEIVED" -> t.str("technician_name").isEmpty() ? "Chưa phân công kỹ thuật viên" : "Chờ " + t.str("technician_name") + " kiểm tra";
            default -> "Hẹn trả " + Fmt.date(t.str("due_date")) + (t.str("technician_name").isEmpty() ? "" : " · " + t.str("technician_name"));
        };
    }

    /** Thanh ngang số phiếu theo trạng thái. */
    static final class StatusBars extends JComponent {
        private static final long serialVersionUID = 1L;
        Row data;

        StatusBars() { setPreferredSize(new Dimension(300, 260)); }

        @Override
        protected void paintComponent(Graphics g0) {
            if (data == null) return;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(Ui.font(java.awt.Font.PLAIN, 13));
            List<long[]> vals = new ArrayList<>();
            long max = 1;
            for (TicketStatus s : TicketStatus.values()) { long v = data.lng(s.name()); max = Math.max(max, v); vals.add(new long[]{v}); }
            int w = getWidth(), lw = 150, nw = 40, rowH = Math.max(22, Math.min(28, getHeight() / TicketStatus.values().length));
            int i = 0;
            for (TicketStatus s : TicketStatus.values()) {
                int y = i * rowH + 4;
                long v = vals.get(i)[0];
                g.setColor(Ui.INK);
                g.drawString(s.getLabel(), 0, y + 14);
                g.setColor(new java.awt.Color(0xEDF1F7));
                g.fillRoundRect(lw, y + 4, w - lw - nw, 10, 10, 10);
                g.setColor(s == TicketStatus.READY ? Ui.OK : Ui.ACCENT);
                g.fillRoundRect(lw, y + 4, (int) ((w - lw - nw) * v / (double) max), 10, 10, 10);
                g.setColor(Ui.INK);
                String n = String.valueOf(v);
                g.drawString(n, w - g.getFontMetrics().stringWidth(n) - 2, y + 14);
                i++;
            }
            g.setColor(Color.BLACK);
        }
    }
}
