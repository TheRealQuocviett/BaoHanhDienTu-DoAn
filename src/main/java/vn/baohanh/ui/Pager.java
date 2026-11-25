package vn.baohanh.ui;

import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/** Thanh phân trang dùng chung cho các bảng nhiều dòng (phiếu, bảo hành, khách hàng). */
public class Pager extends JPanel {
    private static final long serialVersionUID = 1L;
    public static final int SIZE = 100;

    private final JLabel info = new JLabel(" ");
    private final JButton first, prev, next, last;
    private final String unit;
    private int page = 1, pages = 1;

    public Pager(String unit, Runnable reload) {
        super(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        this.unit = unit;
        setOpaque(false);
        info.setForeground(Ui.MUTE);
        first = Ui.button("«", () -> go(1, reload));
        prev = Ui.button("‹ Trước", () -> go(page - 1, reload));
        next = Ui.button("Sau ›", () -> go(page + 1, reload));
        last = Ui.button("»", () -> go(pages, reload));
        add(info);
        add(first);
        add(prev);
        add(next);
        add(last);
    }

    private void go(int p, Runnable reload) {
        page = Math.max(1, Math.min(pages, p));
        reload.run();
    }

    public int page() { return page; }

    /** Về trang 1 (khi đổi bộ lọc / ô tìm kiếm). */
    public void reset() { page = 1; }

    /** Cập nhật theo kết quả pageXxx(): total, page, pages, size. */
    public void update(Row res) {
        page = (int) res.lng("page");
        pages = (int) res.lng("pages");
        long total = res.lng("total"), size = res.lng("size");
        long from = total == 0 ? 0 : (page - 1L) * size + 1, to = Math.min(total, page * size);
        info.setText(Fmt.number(from) + "–" + Fmt.number(to) + " / " + Fmt.number(total) + " " + unit
                + (pages > 1 ? "   ·   Trang " + page + "/" + Fmt.number(pages) + "  " : "  "));
        first.setEnabled(page > 1);
        prev.setEnabled(page > 1);
        next.setEnabled(page < pages);
        last.setEnabled(page < pages);
        boolean many = pages > 1;
        first.setVisible(many);
        prev.setVisible(many);
        next.setVisible(many);
        last.setVisible(many);
    }
}
