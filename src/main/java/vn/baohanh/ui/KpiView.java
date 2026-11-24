package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.time.LocalDate;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;

import vn.baohanh.db.Row;
import vn.baohanh.service.ReportService;
import vn.baohanh.util.Fmt;

/** Bảng xếp hạng KPI / hoa hồng kỹ thuật viên. */
public class KpiView extends MainFrame.View {
    private static final long serialVersionUID = 1L;

    private final Ui.RowModel model = new Ui.RowModel(List.of(Ui.Col.text("rank_text", "Hạng", 60), Ui.Col.text("full_name", "Kỹ thuật viên", 180),
            Ui.Col.num("done", "Hoàn thành"), Ui.Col.num("in_progress", "Đang xử lý"), Ui.Col.text("avg_text", "TG sửa TB", 90),
            Ui.Col.text("on_time_text", "Đúng hẹn", 80), Ui.Col.text("rating_text", "Đánh giá", 110), Ui.Col.text("revenue_text", "Doanh thu", 110),
            Ui.Col.text("commission_text", "Hoa hồng", 110), Ui.Col.num("score", "Điểm")));
    private final JLabel period = Ui.sub(" ");
    private final JLabel note = Ui.sub(" ");
    private LocalDate from = LocalDate.now().withDayOfMonth(1), to = LocalDate.now();

    public KpiView(Ui.Ctx ctx) {
        super(ctx);
        add(Ui.header("KPI kỹ thuật viên", "Xếp hạng theo số phiếu hoàn thành, thời gian sửa, tỷ lệ đúng hẹn và điểm đánh giá của khách.",
                Ui.button("Tháng này", () -> { from = LocalDate.now().withDayOfMonth(1); to = LocalDate.now(); refresh(); }),
                Ui.button("Tháng trước", () -> { LocalDate f = LocalDate.now().minusMonths(1).withDayOfMonth(1); from = f; to = f.plusMonths(1).minusDays(1); refresh(); }),
                Ui.button("3 tháng", () -> { from = LocalDate.now().minusMonths(2).withDayOfMonth(1); to = LocalDate.now(); refresh(); }),
                Ui.button("Năm nay", () -> { from = LocalDate.now().withDayOfYear(1); to = LocalDate.now(); refresh(); })), BorderLayout.NORTH);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(period, BorderLayout.NORTH);
        c.add(Ui.scroll(Ui.table(model, null)), BorderLayout.CENTER);
        c.add(note, BorderLayout.SOUTH);
        add(c, BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            Row d = ctx.app().reports.technicianKpi(user(), ReportService.period(from.toString(), to.toString()));
            @SuppressWarnings("unchecked") List<Row> rows = (List<Row>) d.get("rows");
            for (Row r : rows) {
                long rk = r.lng("rank");
                r.put("rank_text", rk == 1 ? "Hạng 1" : rk == 2 ? "Hạng 2" : rk == 3 ? "Hạng 3" : "#" + rk);
                if (r.bool("me")) r.put("full_name", r.str("full_name") + " (bạn)");
                r.put("avg_text", r.lng("done") > 0 ? String.format("%.1f ngày", ((Number) r.get("avg_days")).doubleValue()) : "–");
                r.put("on_time_text", r.lng("done") > 0 ? r.lng("on_time_pct") + "%" : "–");
                r.put("rating_text", r.lng("reviews") > 0 ? String.format("%.1f/5 (%d)", ((Number) r.get("rating")).doubleValue(), r.lng("reviews")) : "–");
                r.put("revenue_text", r.get("revenue") == null ? "ẩn" : Fmt.money(r.lng("revenue")));
                r.put("commission_text", r.get("commission") == null ? "ẩn" : Fmt.money(r.lng("commission")));
            }
            model.setRows(rows);
            period.setText("Kỳ: " + Fmt.date(d.str("from")) + " – " + Fmt.date(d.str("to")));
            note.setText("<html>Hoa hồng = " + Fmt.money(d.lng("bonus_per_ticket")) + " × số phiếu hoàn thành + " + d.lng("rate_pct")
                    + "% tiền công các phiếu có phí. Điểm = phiếu × 10 + đánh giá × 10 + % đúng hẹn × 0,3 − ngày sửa TB × 2."
                    + (d.bool("admin") ? " Đổi mức hoa hồng ở Thông tin cửa hàng." : " Bạn chỉ xem được tiền của chính mình.") + "</html>");
        });
    }
}
