package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import vn.baohanh.db.Row;
import vn.baohanh.service.ReportService;
import vn.baohanh.util.Fmt;

/** Thống kê lỗi thường gặp: nhóm lỗi, model hay hỏng, linh kiện thay nhiều, máy sửa lại nhiều lần. */
public class FaultsView extends MainFrame.View {
    private static final long serialVersionUID = 1L;

    private final Ui.RowModel groups = new Ui.RowModel(List.of(Ui.Col.text("label", "Nhóm lỗi", 230), Ui.Col.num("count", "Số phiếu"),
            Ui.Col.text("pct_text", "% phiếu", 70), Ui.Col.text("models_text", "Model gặp nhiều", 260), Ui.Col.text("examples_text", "Ví dụ khách báo", 360)));
    private final Ui.RowModel models = new Ui.RowModel(List.of(Ui.Col.text("product", "Sản phẩm", 210), Ui.Col.text("brand", "Hãng", 90),
            Ui.Col.num("tickets", "Số phiếu"), Ui.Col.num("in_warranty", "Trong BH"), Ui.Col.num("sold", "Đã bán"),
            Ui.Col.text("rate_text", "Tỷ lệ hỏng", 80), Ui.Col.text("days_text", "TG sửa TB", 80), Ui.Col.text("top_fault", "Lỗi hay gặp", 280)));
    private final Ui.RowModel parts = new Ui.RowModel(List.of(Ui.Col.text("code", "Mã", 110), Ui.Col.text("name", "Linh kiện", 210),
            Ui.Col.num("qty", "Số lượng"), Ui.Col.num("tickets", "Số phiếu"), Ui.Col.num("free_qty", "Miễn phí BH"), Ui.Col.money("cost", "Giá vốn"),
            Ui.Col.num("stock", "Tồn kho"), Ui.Col.text("models", "Dùng cho model", 280)));
    private final Ui.RowModel repeats = new Ui.RowModel(List.of(Ui.Col.text("serial", "Serial", 150), Ui.Col.text("product", "Sản phẩm", 190),
            Ui.Col.text("customer", "Khách hàng", 150), Ui.Col.text("phone", "Điện thoại", 100), Ui.Col.num("times", "Số lần"),
            Ui.Col.text("codes", "Các phiếu", 170), Ui.Col.text("issues", "Lỗi qua các lần", 360)));
    private final JPanel tiles = new JPanel(new GridLayout(1, 4, 12, 0));
    private final JLabel period = Ui.sub(" ");
    private LocalDate from = LocalDate.now().minusMonths(11).withDayOfMonth(1), to = LocalDate.now();

    public FaultsView(Ui.Ctx ctx) {
        super(ctx);
        add(Ui.header("Thống kê lỗi thường gặp", "Model nào hay hỏng, lỗi gì nhiều, linh kiện nào thay nhiều nhất – theo ngày nhận máy.",
                Ui.button("Tháng này", () -> { from = LocalDate.now().withDayOfMonth(1); to = LocalDate.now(); refresh(); }),
                Ui.button("3 tháng", () -> { from = LocalDate.now().minusMonths(2).withDayOfMonth(1); to = LocalDate.now(); refresh(); }),
                Ui.button("12 tháng", () -> { from = LocalDate.now().minusMonths(11).withDayOfMonth(1); to = LocalDate.now(); refresh(); }),
                Ui.button("Năm nay", () -> { from = LocalDate.now().withDayOfYear(1); to = LocalDate.now(); refresh(); })), BorderLayout.NORTH);
        tiles.setOpaque(false);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Nhóm lỗi", Ui.scroll(Ui.table(groups, null)));
        tabs.addTab("Model hay hỏng", Ui.scroll(Ui.table(models, null)));
        tabs.addTab("Linh kiện thay nhiều", Ui.scroll(Ui.table(parts, null)));
        tabs.addTab("Máy sửa lại nhiều lần", Ui.scroll(Ui.table(repeats, null)));
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(period, BorderLayout.NORTH);
        top.add(tiles, BorderLayout.CENTER);
        JPanel c = new JPanel(new BorderLayout(0, 12));
        c.setOpaque(false);
        c.add(top, BorderLayout.NORTH);
        c.add(tabs, BorderLayout.CENTER);
        c.add(Ui.sub("Nhóm lỗi được phân loại tự động theo từ khóa trong “lỗi khách báo” và “kết luận kỹ thuật”; một phiếu có thể thuộc nhiều nhóm. "
                + "Tỷ lệ hỏng = số máy phải bảo hành / số máy đã bán."), BorderLayout.SOUTH);
        add(c, BorderLayout.CENTER);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refresh() {
        Ui.run(this, () -> {
            Row d = ctx.app().faults.report(user(), ReportService.period(from.toString(), to.toString()));
            period.setText("Kỳ: " + Fmt.date(d.str("from")) + " – " + Fmt.date(d.str("to")));
            List<Row> g = (List<Row>) d.get("groups");
            for (Row r : g) {
                r.put("pct_text", r.get("pct") + "%");
                r.put("models_text", String.join(", ", (List<String>) r.get("top_models")));
                r.put("examples_text", String.join(" | ", (List<String>) r.get("examples")));
            }
            groups.setRows(g);
            List<Row> m = (List<Row>) d.get("models");
            for (Row r : m) {
                r.put("rate_text", r.get("rate") + "%");
                r.put("days_text", r.get("avg_days") == null ? "–" : r.get("avg_days") + " ngày");
            }
            models.setRows(m);
            parts.setRows((List<Row>) d.get("parts"));
            repeats.setRows((List<Row>) d.get("repeats"));
            tiles.removeAll();
            tiles.add(Ui.tile(String.valueOf(d.lng("total")), "Phiếu trong kỳ", false, null));
            tiles.add(Ui.tile(d.str("top_group").isEmpty() ? "–" : d.str("top_group"), "Nhóm lỗi gặp nhiều nhất", false, null));
            tiles.add(Ui.tile(m.isEmpty() ? "–" : m.get(0).str("product"), "Model nhiều phiếu nhất", false, null));
            tiles.add(Ui.tile(String.valueOf(d.lng("repeat_count")), "Máy phải sửa lại ≥ 2 lần", d.lng("repeat_count") > 0, null));
            tiles.revalidate();
            tiles.repaint();
        });
    }
}
