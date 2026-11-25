package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

import vn.baohanh.db.Row;

/** Danh sách nhắc việc của người đang đăng nhập (nhấp đúp để mở phiếu / trang đặt hàng). */
public final class AlertsDialog {
    private AlertsDialog() {}

    @SuppressWarnings("unchecked")
    public static void open(Ui.Ctx ctx) {
        JDialog d = new JDialog(ctx.frame(), "Nhắc việc", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Ui.RowModel model = new Ui.RowModel(List.of(Ui.Col.text("state", "", 40), Ui.Col.text("kind_label", "Loại", 130),
                Ui.Col.dateTime("created_at", "Lúc"), Ui.Col.text("message", "Nội dung", 620)));
        Runnable load = () -> Ui.run(d, () -> {
            List<Row> rows = (List<Row>) ctx.app().alerts.list(ctx.user(), false).get("rows");
            for (Row r : rows) r.put("state", r.get("read_at") == null ? "Mới" : "");
            model.setRows(rows);
        });
        var table = Ui.table(model, r -> {
            Ui.run(d, () -> ctx.app().alerts.markRead(ctx.user(), r.lng("id")));
            d.dispose();
            ctx.frame().refreshBadges();
            if (r.get("ticket_id") != null) TicketDialog.open(ctx, r.lng("ticket_id"));
            else if ("STOCK".equals(r.str("kind"))) ctx.frame().navigate(vn.baohanh.model.Perm.PURCHASE_VIEW.allows(ctx.user()) ? "dat-hang" : "dm-part", null);
        });
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Ui.CARD);
        root.setBorder(Ui.pad(14, 16));
        root.add(Ui.header("Nhắc việc", "Tự quét mỗi 30 phút: phiếu sắp đến hạn (nhắc kỹ thuật viên), quá hạn (báo quản lý + tự gửi tin xin lỗi khách), linh kiện sắp hết. Nhấp đúp để mở."),
                BorderLayout.NORTH);
        root.add(Ui.scroll(table), BorderLayout.CENTER);
        root.add(Ui.flow(Ui.button("Quét lại ngay", () -> Ui.run(d, () -> { ctx.app().alerts.scan(); load.run(); })),
                Ui.button("Đánh dấu đã đọc hết", () -> { Ui.run(d, () -> ctx.app().alerts.markRead(ctx.user(), 0)); load.run(); ctx.frame().refreshBadges(); }),
                Ui.button("Đóng", d::dispose)), BorderLayout.SOUTH);
        d.setContentPane(root);
        load.run();
        d.setSize(1000, 520);
        d.setLocationRelativeTo(ctx.frame());
        d.setVisible(true);
    }
}
