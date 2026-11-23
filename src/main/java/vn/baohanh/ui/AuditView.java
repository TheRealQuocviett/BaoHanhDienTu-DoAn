package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** Nhật ký hệ thống (chỉ quản lý). */
public class AuditView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final Ui.RowModel model = new Ui.RowModel(List.of(Ui.Col.dateTime("time", "Thời gian"),
            Ui.Col.text("user_name", "Người dùng", 150), Ui.Col.text("action", "Thao tác", 150), Ui.Col.text("detail", "Nội dung", 500)));
    private final JTextField q;

    public AuditView(Ui.Ctx ctx) {
        super(ctx);
        q = Ui.search("Tìm thao tác, người dùng, nội dung…", s -> refresh());
        add(Ui.header("Nhật ký hệ thống", "500 thao tác gần nhất: đăng nhập, thêm/sửa/xóa, đổi trạng thái phiếu, nhập xuất kho…"), BorderLayout.NORTH);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(Ui.flow(new JLabel("Tìm:"), q), BorderLayout.NORTH);
        c.add(Ui.scroll(Ui.table(model, null)), BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> model.setRows(ctx.app().audit.list(user(), q.getText(), 500)));
    }
}
