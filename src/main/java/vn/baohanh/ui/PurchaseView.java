package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.util.Fmt;

/** Đặt hàng linh kiện: gợi ý theo tồn kho, tạo đơn, đánh dấu đã đặt, nhận hàng (cộng tồn), hủy, in đơn. */
public class PurchaseView extends MainFrame.View {
    private static final long serialVersionUID = 1L;

    private final Ui.RowModel sugModel = new Ui.RowModel(List.of(Ui.Col.text("code", "Mã", 100), Ui.Col.text("name", "Linh kiện", 230),
            Ui.Col.num("stock", "Tồn"), Ui.Col.num("min_stock", "Tối thiểu"), Ui.Col.num("incoming", "Đang về"),
            Ui.Col.num("suggest_qty", "Gợi ý đặt"), Ui.Col.money("cost_price", "Giá nhập")));
    private final Ui.RowModel poModel = new Ui.RowModel(List.of(Ui.Col.text("code", "Mã đơn", 100), Ui.Col.text("supplier_name", "Nhà cung cấp", 240),
            Ui.Col.dateTime("created_at", "Ngày lập"), Ui.Col.num("lines", "Số dòng"), Ui.Col.money("total", "Tổng tiền"),
            Ui.Col.text("status_label", "Trạng thái", 150), Ui.Col.date("expected_date", "Cần hàng")));
    private final JTable sugTable = Ui.table(sugModel, null);
    private final JTable poTable;
    private final boolean admin;

    public PurchaseView(Ui.Ctx ctx) {
        super(ctx);
        admin = vn.baohanh.model.Perm.PURCHASE.allows(ctx.user());
        poTable = Ui.table(poModel, r -> showOrder(r.lng("id")));
        add(Ui.header("Đặt hàng linh kiện", "Linh kiện dưới mức tồn tối thiểu được gợi ý sẵn. Nhận hàng sẽ tự cộng tồn kho và ghi thẻ kho.",
                admin ? new java.awt.Component[]{Ui.primary("Tạo đơn từ gợi ý", this::createFromSuggestions)} : new java.awt.Component[0]),
                BorderLayout.NORTH);
        JPanel grid = new JPanel(new GridLayout(2, 1, 0, 12));
        grid.setOpaque(false);
        grid.add(Ui.card(withTitle("Cần đặt thêm (tồn ≤ mức tối thiểu)", Ui.scroll(sugTable))));
        JPanel actions = Ui.flow(
                Ui.button("Xem / in đơn", () -> selected().ifPresent(r -> showOrder(r.lng("id")))),
                Ui.button("Đánh dấu đã gửi đơn", () -> act(r -> ctx.app().purchases.markOrdered(user(), r.lng("id")), "Đã chuyển sang \"Đã đặt – chờ hàng\"")),
                Ui.primary("Nhận hàng đủ & nhập kho", () -> act(r -> ctx.app().purchases.receive(user(), r.lng("id"), Map.of()), "Đã nhận hàng – tồn kho đã được cộng")),
                Ui.danger("Hủy đơn", () -> {
                    if (selected().isPresent() && Ui.confirm(this, "Hủy đơn " + selected().get().str("code") + "?"))
                        act(r -> ctx.app().purchases.cancel(user(), r.lng("id"), ""), "Đã hủy đơn");
                }));
        JPanel orders = new JPanel(new BorderLayout(0, 6));
        orders.setOpaque(false);
        orders.add(Ui.bold("Đơn đặt hàng (nhấp đúp để xem chi tiết)"), BorderLayout.NORTH);
        orders.add(Ui.scroll(poTable), BorderLayout.CENTER);
        if (admin) orders.add(actions, BorderLayout.SOUTH);
        grid.add(Ui.card(orders));
        add(grid, BorderLayout.CENTER);
    }

    private static JPanel withTitle(String title, java.awt.Component c) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        p.add(Ui.bold(title), BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private java.util.Optional<Row> selected() {
        Row r = Ui.selected(poTable, poModel);
        if (r == null) Ui.info(this, "Chọn một đơn trong bảng");
        return java.util.Optional.ofNullable(r);
    }

    private interface PoAction { void run(Row r) throws Exception; }

    private void act(PoAction a, String ok) {
        Row r = Ui.selected(poTable, poModel);
        if (r == null) { Ui.info(this, "Chọn một đơn trong bảng"); return; }
        if (Ui.run(this, () -> a.run(r))) {
            refresh();
            ctx.frame().refreshBadges();
            Ui.info(this, ok);
        }
    }

    private void createFromSuggestions() {
        List<Map<String, Object>> lines = new ArrayList<>();
        StringBuilder sb = new StringBuilder("<html>Các linh kiện sẽ đặt:<br>");
        for (Row s : sugModel.rows()) {
            if (s.lng("suggest_qty") <= 0) continue;
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("part_id", s.lng("id"));
            l.put("qty", s.lng("suggest_qty"));
            l.put("unit_cost", s.lng("cost_price"));
            lines.add(l);
            sb.append("• ").append(Fmt.html(s.str("name"))).append(" × ").append(s.lng("suggest_qty")).append("<br>");
        }
        if (lines.isEmpty()) { Ui.info(this, "Không có linh kiện nào cần đặt (hoặc đã có đơn đang về)."); return; }
        final List<Row>[] sups = new List[1];
        if (!Ui.run(this, () -> sups[0] = ctx.app().catalog.options(user(), "supplier"))) return;
        if (sups[0].isEmpty()) { Ui.info(this, "Chưa có nhà cung cấp. Vào Kho → Nhà cung cấp để thêm."); return; }
        JComboBox<Ui.Item> sup = Ui.combo(sups[0], null);
        JTextField note = new JTextField(24);
        sb.append("</html>");
        Object[] form = {new javax.swing.JLabel(sb.toString()), "Nhà cung cấp:", sup, "Ghi chú:", note,
                Ui.sub("Muốn sửa số lượng từng dòng, dùng giao diện web (Đặt hàng linh kiện).")};
        if (JOptionPane.showConfirmDialog(this, form, "Tạo đơn đặt hàng", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("supplier_id", ((Ui.Item) sup.getSelectedItem()).id());
        in.put("note", note.getText());
        in.put("lines", lines);
        final Row[] po = new Row[1];
        if (Ui.run(this, () -> po[0] = ctx.app().purchases.create(user(), in))) {
            refresh();
            ctx.frame().refreshBadges();
            if (Ui.confirm(this, "Đã tạo đơn " + po[0].str("code") + ". In đơn để gửi nhà cung cấp?")) Ui.print(ctx, this, "dat-hang", po[0].lng("id"));
        }
    }

    @SuppressWarnings("unchecked")
    private void showOrder(long id) {
        final Row[] o = new Row[1];
        if (!Ui.run(this, () -> o[0] = ctx.app().purchases.get(user(), id))) return;
        StringBuilder sb = new StringBuilder("<html><b style='font-size:14px'>" + Fmt.html(o[0].str("code")) + "</b> · " + Fmt.html(o[0].str("status_label"))
                + "<br>Nhà cung cấp: <b>" + Fmt.html(o[0].str("supplier_name")) + "</b> · " + Fmt.html(o[0].str("supplier_phone"))
                + "<br>Ngày lập: " + Fmt.dateTime(o[0].str("created_at")) + (o[0].str("received_at").isEmpty() ? "" : " · Đã nhận: " + Fmt.dateTime(o[0].str("received_at")))
                + "<br><br><table cellpadding='3'><tr><th align='left'>Linh kiện</th><th align='right'>Đặt</th><th align='right'>Nhận</th><th align='right'>Thành tiền</th></tr>");
        for (Row l : (List<Row>) o[0].get("lines"))
            sb.append("<tr><td>").append(Fmt.html(l.str("name"))).append("</td><td align='right'>").append(l.lng("qty"))
              .append("</td><td align='right'>").append(l.lng("received_qty")).append("</td><td align='right'>").append(Fmt.money(l.lng("amount"))).append("</td></tr>");
        sb.append("<tr><td colspan='3' align='right'><b>Tổng cộng</b></td><td align='right'><b>").append(Fmt.money(o[0].lng("total"))).append("</b></td></tr></table></html>");
        int c = JOptionPane.showOptionDialog(this, sb.toString(), "Đơn đặt hàng", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                new Object[]{"In / Lưu PDF", "Đóng"}, "Đóng");
        if (c == 0) Ui.print(ctx, this, "dat-hang", id);
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            sugModel.setRows(ctx.app().purchases.suggestions(user()));
            poModel.setRows(ctx.app().purchases.list(user(), ""));
        });
    }
}
