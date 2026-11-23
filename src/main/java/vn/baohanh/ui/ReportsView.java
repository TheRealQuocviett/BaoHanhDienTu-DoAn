package vn.baohanh.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

import vn.baohanh.db.Row;
import vn.baohanh.service.ReportService;
import vn.baohanh.util.Fmt;

/** Báo cáo thống kê: số liệu tổng hợp, biểu đồ theo tháng, theo hãng, model, kỹ thuật viên, linh kiện; xuất Excel. */
public class ReportsView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final JTextField from = new JTextField(9), to = new JTextField(9);
    private final JPanel tiles = new JPanel(new GridLayout(0, 4, 10, 10));
    private final MonthlyChart chart = new MonthlyChart();
    private final Ui.RowModel brands = new Ui.RowModel(List.of(Ui.Col.text("brand", "Hãng", 120), Ui.Col.num("sold", "Máy đã bán"),
            Ui.Col.num("tickets", "Số phiếu"), Ui.Col.text("rate_text", "Tỷ lệ", 70)));
    private final Ui.RowModel models = new Ui.RowModel(List.of(Ui.Col.text("product", "Sản phẩm", 220), Ui.Col.text("brand", "Hãng", 90),
            Ui.Col.num("tickets", "Phiếu")));
    private final Ui.RowModel techs = new Ui.RowModel(List.of(Ui.Col.text("technician", "Kỹ thuật viên", 160), Ui.Col.num("assigned", "Được giao"),
            Ui.Col.num("completed", "Hoàn thành"), Ui.Col.num("in_progress", "Đang xử lý"), Ui.Col.text("avg", "TB ngày", 70)));
    private final Ui.RowModel parts = new Ui.RowModel(List.of(Ui.Col.text("name", "Linh kiện", 200), Ui.Col.num("qty", "SL"),
            Ui.Col.money("charged_value", "Thu khách"), Ui.Col.money("free_value", "Miễn phí BH")));
    private final Ui.RowModel reviews = new Ui.RowModel(List.of(Ui.Col.text("star_text", "Số sao", 70), Ui.Col.text("comment", "Nhận xét", 260),
            Ui.Col.text("code", "Phiếu", 95), Ui.Col.text("customer", "Khách hàng", 130), Ui.Col.date("created_at", "Ngày")));
    private final Ui.RowModel techReviews = new Ui.RowModel(List.of(Ui.Col.text("technician", "Kỹ thuật viên", 180), Ui.Col.num("n", "Đánh giá"),
            Ui.Col.text("avg_text", "Điểm TB", 90)));
    private final JLabel period = Ui.sub(" ");

    public ReportsView(Ui.Ctx ctx) {
        super(ctx);
        int y = LocalDate.now().getYear();
        add(Ui.header("Báo cáo thống kê", null,
                new JLabel("Từ"), from, new JLabel("đến"), to, Ui.button("Xem", this::refresh),
                Ui.button("12 tháng", () -> preset("", "")), Ui.button("Năm nay", () -> preset(y + "-01-01", LocalDate.now().toString())),
                Ui.primary("Xuất Excel", this::export)), BorderLayout.NORTH);
        tiles.setOpaque(false);
        JPanel body = new JPanel();
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.add(left(period));
        body.add(left(tiles));
        JPanel chartCard = Ui.card(chart);
        chartCard.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0), chartCard.getBorder()));
        body.add(left(chartCard));
        JPanel grid = new JPanel(new GridLayout(3, 2, 12, 12));
        grid.setOpaque(false);
        grid.add(block("Tỷ lệ phải bảo hành theo hãng (phiếu / máy đã bán)", brands));
        grid.add(block("Model phải bảo hành nhiều", models));
        grid.add(block("Năng suất kỹ thuật viên", techs));
        grid.add(block("Linh kiện sử dụng", parts));
        grid.add(block("Đánh giá gần đây của khách", reviews));
        grid.add(block("Điểm đánh giá theo kỹ thuật viên", techReviews));
        grid.setPreferredSize(new Dimension(900, 840));
        body.add(left(grid));
        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.getViewport().setBackground(Ui.BG);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(sp, BorderLayout.CENTER);
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel block(String title, Ui.RowModel m) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        p.add(Ui.bold(title), BorderLayout.NORTH);
        p.add(Ui.scroll(Ui.table(m, null)), BorderLayout.CENTER);
        return p;
    }

    private void preset(String f, String t) {
        from.setText(f.isEmpty() ? "" : Ui.vnDate(LocalDate.parse(f)));
        to.setText(t.isEmpty() ? "" : Ui.vnDate(LocalDate.parse(t)));
        refresh();
    }

    private ReportService.Period period() {
        return ReportService.period(Ui.toIso(from.getText(), "Ngày bắt đầu"), Ui.toIso(to.getText(), "Ngày kết thúc"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refresh() {
        Ui.run(this, () -> {
            ReportService.Period p = period();
            Row r = ctx.app().reports.full(user(), p);
            from.setText(Ui.vnDate(p.from()));
            to.setText(Ui.vnDate(p.to()));
            period.setText("Từ " + Ui.vnDate(p.from()) + " đến " + Ui.vnDate(p.to()) + " · phiếu tính theo ngày nhận, doanh thu theo ngày trả máy");
            Row s = (Row) r.get("summary");
            tiles.removeAll();
            tiles.add(Ui.tile(Fmt.number(s.lng("warranties_sold")), "Máy kích hoạt BH", false, null));
            tiles.add(Ui.tile(Fmt.number(s.lng("received")), "Phiếu tiếp nhận", false, null));
            tiles.add(Ui.tile(Fmt.number(s.lng("returned")), "Phiếu đã trả khách", false, null));
            tiles.add(Ui.tile(Fmt.money(s.lng("revenue")), "Doanh thu sửa chữa", false, null));
            tiles.add(Ui.tile(Fmt.money(s.lng("extension_revenue")), "Gói BH mở rộng (" + s.lng("extensions_sold") + " gói)", false, null));
            tiles.add(Ui.tile(Fmt.number(s.lng("requests_online")), "Yêu cầu bảo hành online", false, null));
            tiles.add(Ui.tile(s.get("avg_days") + " ngày", "Thời gian xử lý TB", false, null));
            tiles.add(Ui.tile(s.lng("received") == 0 ? "—" : (s.lng("in_warranty") * 100 / s.lng("received")) + "%", "Tỷ lệ còn bảo hành", false, null));
            Row rv = (Row) r.get("reviews");
            tiles.add(Ui.tile(rv.lng("count") == 0 ? "—" : String.format("%.1f/5 sao", ((Number) rv.get("avg")).doubleValue()),
                    "Đánh giá TB (" + rv.lng("count") + " lượt, " + rv.get("response_rate") + "% khách)", false, null));
            tiles.revalidate();
            List<Row> rr = (List<Row>) rv.get("recent");
            for (Row x : rr) x.put("star_text", x.lng("stars") + "/5 sao");
            reviews.setRows(rr);
            List<Row> tr = (List<Row>) rv.get("by_technician");
            for (Row x : tr) x.put("avg_text", String.format("%.1f/5 sao", ((Number) x.get("avg")).doubleValue()));
            techReviews.setRows(tr);
            List<Row> b = (List<Row>) r.get("by_brand");
            for (Row x : b) x.put("rate_text", x.get("rate") + "%");
            brands.setRows(b);
            models.setRows((List<Row>) r.get("top_models"));
            List<Row> t = (List<Row>) r.get("by_technician");
            for (Row x : t) x.put("avg", x.get("avg_days") == null ? "—" : String.valueOf(x.get("avg_days")));
            techs.setRows(t);
            parts.setRows((List<Row>) r.get("parts_usage"));
            chart.data = (List<Row>) r.get("monthly");
            chart.repaint();
        });
    }

    private void export() {
        Ui.run(this, () -> {
            ReportService.Period p = period();
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Lưu báo cáo Excel");
            fc.setFileFilter(new FileNameExtensionFilter("Excel (*.xlsx)", "xlsx"));
            fc.setSelectedFile(new File(ReportService.fileName(p)));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".xlsx")) f = new File(f.getParentFile(), f.getName() + ".xlsx");
            Files.write(f.toPath(), ctx.app().reports.exportXlsx(user(), p));
            if (Ui.confirm(this, "Đã lưu " + f.getName() + ". Mở file ngay?")) java.awt.Desktop.getDesktop().open(f);
        });
    }

    /** Biểu đồ cột (phiếu nhận / trả) + đường doanh thu theo tháng. */
    static final class MonthlyChart extends JComponent {
        private static final long serialVersionUID = 1L;
        List<Row> data = new ArrayList<>();

        MonthlyChart() { setPreferredSize(new Dimension(900, 260)); }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(Ui.font(Font.PLAIN, 12));
            int W = getWidth(), H = getHeight(), pl = 36, pr = 70, pt = 28, pb = 26;
            g.setColor(Ui.INK);
            g.setFont(Ui.font(Font.BOLD, 14));
            g.drawString("Phiếu và doanh thu theo tháng", 0, 14);
            g.setFont(Ui.font(Font.PLAIN, 12));
            legend(g, W - 330, 14);
            if (data.isEmpty()) return;
            long maxC = 1, maxR = 1;
            for (Row r : data) { maxC = Math.max(maxC, Math.max(r.lng("received"), r.lng("returned"))); maxR = Math.max(maxR, r.lng("revenue")); }
            int n = data.size();
            double gw = (W - pl - pr) / (double) n;
            int bw = (int) Math.max(3, Math.min(16, gw / 3));
            int ch = H - pt - pb;
            for (int i = 0; i <= 4; i++) {
                long v = Math.round(maxC * i / 4.0);
                int yy = pt + (int) (ch * (1 - v / (double) maxC));
                g.setColor(Ui.LINE);
                g.drawLine(pl, yy, W - pr, yy);
                g.setColor(Ui.MUTE);
                g.drawString(String.valueOf(v), pl - 6 - g.getFontMetrics().stringWidth(String.valueOf(v)), yy + 4);
            }
            g.setColor(Ui.WARN);
            g.drawString(String.format("%.1f tr", maxR / 1e6), W - pr + 6, pt + 4);
            g.drawString("0", W - pr + 6, H - pb);
            int[] xs = new int[n], ys = new int[n];
            for (int i = 0; i < n; i++) {
                Row r = data.get(i);
                int cx = pl + (int) (gw * i + gw / 2);
                int h1 = (int) (ch * r.lng("received") / (double) maxC), h2 = (int) (ch * r.lng("returned") / (double) maxC);
                g.setColor(Ui.ACCENT);
                g.fillRoundRect(cx - bw - 1, H - pb - h1, bw, h1 + 3, 5, 5);
                g.setColor(Ui.SKY);
                g.fillRoundRect(cx + 1, H - pb - h2, bw, h2 + 3, 5, 5);
                g.setColor(Ui.MUTE);
                String lb = r.str("label");
                if (gw < 52) lb = lb.substring(0, 2);
                g.drawString(lb, cx - g.getFontMetrics().stringWidth(lb) / 2, H - 6);
                xs[i] = cx;
                ys[i] = pt + (int) (ch * (1 - r.lng("revenue") / (double) maxR));
            }
            g.setColor(Ui.AMBER);
            g.setStroke(new BasicStroke(2.5f));
            g.drawPolyline(xs, ys, n);
            for (int i = 0; i < n; i++) g.fillOval(xs[i] - 4, ys[i] - 4, 8, 8);
        }

        private static void legend(Graphics2D g, int x, int y) {
            Object[][] items = {{Ui.ACCENT, "Phiếu nhận"}, {Ui.SKY, "Phiếu trả"}, {Ui.AMBER, "Doanh thu"}};
            for (Object[] it : items) {
                g.setColor((Color) it[0]);
                g.fillRect(x, y - 9, 10, 10);
                g.setColor(Ui.MUTE);
                g.drawString((String) it[1], x + 14, y);
                x += 110;
            }
        }
    }
}
