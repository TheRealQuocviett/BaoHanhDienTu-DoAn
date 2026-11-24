package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;

/** Danh sách phiếu bảo hành: lọc theo trạng thái, tìm kiếm, nhấp đúp để xử lý. */
public class TicketsView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final Ui.RowModel model = new Ui.RowModel(List.of(
            Ui.Col.text("code", "Mã phiếu", 110), Ui.Col.text("product_name", "Sản phẩm", 190), Ui.Col.text("serial", "Serial", 130),
            Ui.Col.text("customer_name", "Khách hàng", 140), Ui.Col.text("customer_phone", "Điện thoại", 100),
            Ui.Col.date("received_at", "Ngày nhận"), Ui.Col.date("due_date", "Hẹn trả"), Ui.Col.text("technician_name", "Kỹ thuật viên", 130),
            Ui.Col.money("total", "Chi phí"), new Ui.Col("status", "Trạng thái", Ui.Kind.STATUS, 210)));
    private final JComboBox<Ui.Item> filter = new JComboBox<>();
    private final JTextField q;
    private final JLabel count = Ui.sub(" ");

    public TicketsView(Ui.Ctx ctx) {
        super(ctx);
        if (ctx.user().is(Role.TECHNICIAN)) filter.addItem(new Ui.Item("MINE", "Phiếu của tôi"));
        filter.addItem(new Ui.Item("OPEN", "Đang xử lý"));
        filter.addItem(new Ui.Item("OVERDUE", "Quá hẹn"));
        filter.addItem(new Ui.Item("QUOTE", "Chờ khách duyệt giá"));
        filter.addItem(new Ui.Item("", "Tất cả"));
        for (TicketStatus s : TicketStatus.values()) filter.addItem(new Ui.Item(s.name(), s.getLabel()));
        Ui.select(filter, ctx.user().is(Role.TECHNICIAN) ? "MINE" : "OPEN");
        filter.addActionListener(e -> refresh());
        q = Ui.search("Tìm mã phiếu, serial, tên, SĐT…", s -> refresh());

        JTable t = Ui.table(model, r -> TicketDialog.open(ctx, r.lng("id")));
        add(Ui.header("Phiếu bảo hành", "Nhấp đúp vào một phiếu để xem và xử lý.",
                vn.baohanh.model.Perm.RECEIVE.allows(ctx.user()) ? Ui.primary("+ Tiếp nhận máy", () -> ReceiveDialog.open(ctx, null)) : new JLabel()),
                BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        center.add(Ui.flow(new JLabel("Lọc:"), filter, new JLabel("  Tìm:"), q, count), BorderLayout.NORTH);
        center.add(Ui.scroll(t), BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
    }

    @Override
    public void applyFilter(String f) {
        Ui.select(filter, f);
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            model.setRows(ctx.app().warranty.listTickets(user(), String.valueOf(Ui.selectedId(filter)), q.getText()));
            count.setText("   " + model.getRowCount() + " phiếu");
        });
    }
}
