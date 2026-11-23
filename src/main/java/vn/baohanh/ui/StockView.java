package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Thẻ kho: lịch sử nhập / xuất / hoàn / kiểm kê linh kiện. */
public class StockView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final Ui.RowModel model = new Ui.RowModel(List.of(
            Ui.Col.dateTime("time", "Thời gian"), Ui.Col.text("code", "Mã", 110), Ui.Col.text("name", "Linh kiện", 220),
            Ui.Col.text("type_label", "Loại", 110), Ui.Col.num("qty", "Số lượng"), Ui.Col.text("ticket_code", "Phiếu", 100),
            Ui.Col.text("user_name", "Người thực hiện", 140), Ui.Col.text("note", "Ghi chú", 240)));
    private final JComboBox<Ui.Item> part = new JComboBox<>();
    private boolean loading;

    public StockView(Ui.Ctx ctx) {
        super(ctx);
        add(Ui.header("Thẻ kho", "Lịch sử nhập, xuất cho phiếu, hoàn kho và kiểm kê (500 giao dịch gần nhất)."), BorderLayout.NORTH);
        part.addActionListener(e -> { if (!loading) load(); });
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(Ui.flow(new JLabel("Linh kiện:"), part), BorderLayout.NORTH);
        c.add(Ui.scroll(Ui.table(model, null)), BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    private void load() {
        Object id = Ui.selectedId(part);
        Ui.run(this, () -> model.setRows(ctx.app().inventory.movements(user(), id == null ? 0 : ((Number) id).longValue(), 500)));
    }

    @Override
    public void refresh() {
        loading = true;
        Ui.run(this, () -> {
            Object sel = Ui.selectedId(part);
            part.removeAllItems();
            JComboBox<Ui.Item> c = Ui.combo(ctx.app().catalog.options(user(), "part"), "Tất cả linh kiện");
            for (int i = 0; i < c.getItemCount(); i++) part.addItem(c.getItemAt(i));
            Ui.select(part, sel);
        });
        loading = false;
        load();
    }
}
