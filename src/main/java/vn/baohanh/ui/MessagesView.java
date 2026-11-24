package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;

import vn.baohanh.db.Row;

/** Tin nhắn khách gửi từ cổng tra cứu, gom theo phiếu. Mở phiếu để đọc và trả lời. */
public class MessagesView extends MainFrame.View {
    private static final long serialVersionUID = 1L;

    private final Ui.RowModel model = new Ui.RowModel(List.of(Ui.Col.text("unread_text", "Trạng thái", 110),
            Ui.Col.text("customer_name", "Khách hàng", 150), Ui.Col.text("code", "Phiếu", 100), Ui.Col.text("product_name", "Sản phẩm", 190),
            Ui.Col.text("last_text", "Tin nhắn gần nhất", 380), Ui.Col.dateTime("last_time", "Lúc")));
    private final JTable table;
    private final JComboBox<String> filter = Ui.wide(new JComboBox<>(new String[]{"Tất cả hội thoại", "Chưa trả lời"}), 190);
    private final JLabel hint = Ui.sub("Nhấp đúp một dòng để mở phiếu và trả lời khách. Khách được báo qua email / SMS nếu đã bật thông báo.");

    public MessagesView(Ui.Ctx ctx) {
        super(ctx);
        table = Ui.table(model, r -> {
            TicketDialog.open(ctx, r.lng("ticket_id"));
            refresh();
            ctx.frame().refreshBadges();
        });
        add(Ui.header("Tin nhắn khách", "Khách hỏi về phiếu của mình từ cổng tra cứu.",
                Ui.primary("Mở phiếu & trả lời", () -> {
                    Row r = Ui.selected(table, model);
                    if (r == null) { Ui.info(this, "Chọn một hội thoại trong bảng"); return; }
                    TicketDialog.open(ctx, r.lng("ticket_id"));
                    refresh();
                    ctx.frame().refreshBadges();
                })), BorderLayout.NORTH);
        filter.addActionListener(e -> refresh());
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(Ui.flow(filter, hint), BorderLayout.NORTH);
        c.add(Ui.scroll(table), BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            List<Row> list = ctx.app().messages.conversations(user(), filter.getSelectedIndex() == 1);
            for (Row r : list) {
                r.put("unread_text", r.lng("unread") > 0 ? "● " + r.lng("unread") + " tin mới" : "Đã xem");
                r.put("last_text", ("STAFF".equals(r.str("last_sender")) ? "Bạn: " : "") + r.str("last_body").replace('\n', ' '));
            }
            model.setRows(list);
        });
    }
}
