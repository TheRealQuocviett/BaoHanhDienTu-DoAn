package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;


/** Danh sách bảo hành đã kích hoạt, lọc theo thời hạn. */
public class WarrantiesView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final Ui.RowModel model = new Ui.RowModel(List.of(
            Ui.Col.text("code", "Mã BH", 105), Ui.Col.text("product_name", "Sản phẩm", 210), Ui.Col.text("serial", "Serial / IMEI", 150),
            Ui.Col.text("customer_name", "Khách hàng", 150), Ui.Col.text("customer_phone", "Điện thoại", 100),
            Ui.Col.date("purchase_date", "Ngày mua"), Ui.Col.date("expiry_date", "Hết hạn"), Ui.Col.num("days_left", "Còn (ngày)")));
    private final JComboBox<Ui.Item> filter = new JComboBox<>();
    private final JTextField q;
    private final JLabel count = Ui.sub(" ");
    private final Pager pager = new Pager("bảo hành", this::refresh);

    public WarrantiesView(Ui.Ctx ctx) {
        super(ctx);
        filter.addItem(new Ui.Item("", "Tất cả"));
        filter.addItem(new Ui.Item("active", "Còn hạn"));
        filter.addItem(new Ui.Item("expiring", "Sắp hết hạn (≤ 30 ngày)"));
        filter.addItem(new Ui.Item("expired", "Hết hạn"));
        filter.addActionListener(e -> { pager.reset(); refresh(); });
        q = Ui.search("Tìm serial, mã BH, tên, SĐT…", s -> { pager.reset(); refresh(); });
        JTable t = Ui.table(model, r -> WarrantyPanel.dialog(ctx, r.lng("id"), this::refresh));
        add(Ui.header("Danh sách bảo hành", "Nhấp đúp để xem chi tiết, lịch sử sửa chữa, in phiếu bảo hành.",
                vn.baohanh.model.Perm.ACTIVATE.allows(ctx.user()) ? Ui.primary("+ Kích hoạt", () -> ctx.frame().navigate("kich-hoat", null)) : new JLabel()),
                BorderLayout.NORTH);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(Ui.flow(new JLabel("Lọc:"), filter, new JLabel("  Tìm:"), q, count), BorderLayout.NORTH);
        c.add(Ui.scroll(t), BorderLayout.CENTER);
        c.add(pager, BorderLayout.SOUTH);
        add(c, BorderLayout.CENTER);
    }

    @Override
    public void applyFilter(String f) { Ui.select(filter, f); }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            vn.baohanh.db.Row res = ctx.app().warranty.pageWarranties(user(), q.getText(), String.valueOf(Ui.selectedId(filter)), pager.page(), Pager.SIZE);
            model.setRows(rows(res));
            pager.update(res);
        });
    }
}
