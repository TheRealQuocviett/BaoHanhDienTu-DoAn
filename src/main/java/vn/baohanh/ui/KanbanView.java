package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.TransferHandler;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/** Bảng công việc dạng Kanban: kéo thả phiếu giữa các cột để chuyển trạng thái. */
public class KanbanView extends MainFrame.View {
    private static final long serialVersionUID = 1L;

    private final JPanel board = new JPanel();
    private final JTextField q = Ui.search("Lọc theo mã, khách, sản phẩm…", s -> draw());
    private final JCheckBox mine = new JCheckBox("Chỉ phiếu của tôi");
    private Row data;
    private Row dragging;

    public KanbanView(Ui.Ctx ctx) {
        super(ctx);
        mine.setOpaque(false);
        mine.addActionListener(e -> draw());
        add(Ui.header("Bảng công việc", "Kéo thả phiếu sang cột khác để chuyển trạng thái. Nhấp đúp để mở phiếu."), BorderLayout.NORTH);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(Ui.flow(q, mine), BorderLayout.NORTH);
        board.setOpaque(false);
        JScrollPane sp = new JScrollPane(board);
        sp.setBorder(null);
        sp.getViewport().setBackground(Ui.BG);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getHorizontalScrollBar().setUnitIncrement(18);
        c.add(sp, BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        final Row[] d = new Row[1];
        if (Ui.run(this, () -> d[0] = ctx.app().warranty.board(user()))) {
            data = d[0];
            draw();
        }
    }

    @SuppressWarnings("unchecked")
    private void draw() {
        if (data == null) return;
        List<Row> cols = (List<Row>) data.get("columns");
        List<Row> tickets = (List<Row>) data.get("tickets");
        String filter = q.getText().trim().toLowerCase();
        board.removeAll();
        board.setLayout(new BoxLayout(board, BoxLayout.X_AXIS));
        for (Row col : cols) {
            String key = col.str("key");
            List<Row> cards = tickets.stream().filter(t -> key.equals(t.str("status")))
                    .filter(t -> !mine.isSelected() || Boolean.TRUE.equals(t.get("mine")))
                    .filter(t -> filter.isEmpty() || (t.str("code") + " " + t.str("customer_name") + " " + t.str("product_name")).toLowerCase().contains(filter))
                    .toList();
            JPanel colPanel = new Ui.RoundPanel(new BorderLayout(), 14);
            colPanel.setBackground(Ui.COLUMN);
            colPanel.setBorder(Ui.pad(8, 8));
            JLabel head = new JLabel("<html><b>" + Fmt.html(col.str("label")) + "</b> <span style='color:" + Ui.hex(Ui.MUTE) + "'>(" + cards.size() + ")</span></html>");
            head.setBorder(Ui.pad(0, 2));
            colPanel.add(head, BorderLayout.NORTH);
            JPanel list = new JPanel();
            list.setOpaque(false);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            for (Row t : cards) {
                list.add(Box.createVerticalStrut(8));
                list.add(card(t));
            }
            list.add(Box.createVerticalGlue());
            colPanel.add(list, BorderLayout.CENTER);
            colPanel.setTransferHandler(new DropHandler(key, colPanel));
            int h = Math.max(420, 60 + cards.size() * 118);
            colPanel.setPreferredSize(new Dimension(215, h));
            colPanel.setMaximumSize(new Dimension(215, h));
            colPanel.setAlignmentY(TOP_ALIGNMENT);
            board.add(colPanel);
            board.add(Box.createHorizontalStrut(10));
        }
        board.revalidate();
        board.repaint();
    }

    private JComponent card(Row t) {
        boolean late = Boolean.TRUE.equals(t.get("overdue"));
        String html = "<html><body style='width:165px'><b style='color:" + Ui.hex(Ui.ACCENT_TEXT) + "'>" + Fmt.html(t.str("code")) + "</b> "
                + (t.lng("in_warranty") == 1 ? "<span style='color:" + Ui.hex(Ui.OK) + "'>· BH</span>" : "<span style='color:" + Ui.hex(Ui.WARN) + "'>· Có phí</span>")
                + "<br><b>" + Fmt.html(t.str("product_name")) + "</b><br>" + Fmt.html(t.str("customer_name"))
                + "<br><span style='color:" + Ui.hex(Ui.MUTE) + "'>" + (t.str("technician").isEmpty() ? "<i>Chưa phân công</i>" : "KTV " + Fmt.html(t.str("technician"))) + "</span>"
                + "<br><span style='color:" + (late ? Ui.hex(Ui.BAD) : Ui.hex(Ui.MUTE)) + "'>Hẹn trả " + Fmt.date(t.str("due_date")) + (late ? " · QUÁ HẸN" : "") + "</span>"
                + (t.lng("unread") > 0 ? "<br><b style='color:" + Ui.hex(Ui.BAD) + "'>" + t.lng("unread") + " tin khách chưa đọc</b>" : "")
                + ("PENDING".equals(t.str("quote_status")) ? "<br><span style='color:" + Ui.hex(Ui.WARN) + "'>Chờ khách duyệt báo giá</span>" : "")
                + "</body></html>";
        JLabel l = new JLabel(html);
        l.setFont(Ui.font(Font.PLAIN, 12));
        l.setOpaque(true);
        l.setBackground(Ui.CARD);
        l.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, late ? 3 : 0, 0, 0, Ui.BAD),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.LINE), Ui.pad(7, 8))));
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        l.setToolTipText("Kéo sang cột khác để chuyển trạng thái · nhấp đúp để mở");
        l.setTransferHandler(new TransferHandler() {
            private static final long serialVersionUID = 1L;
            @Override public int getSourceActions(JComponent c) { return MOVE; }
            @Override protected Transferable createTransferable(JComponent c) { dragging = t; return new StringSelection(String.valueOf(t.lng("id"))); }
            @Override protected void exportDone(JComponent src, Transferable data, int action) { dragging = null; }
        });
        l.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) { TicketDialog.open(ctx, t.lng("id")); refresh(); ctx.frame().refreshBadges(); }
            }
        });
        l.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                JComponent c = (JComponent) e.getSource();
                c.getTransferHandler().exportAsDrag(c, e, TransferHandler.MOVE);
            }
        });
        return l;
    }

    /** Nhận phiếu thả vào cột. */
    private final class DropHandler extends TransferHandler {
        private static final long serialVersionUID = 1L;
        private final String target;
        private final JPanel col;

        DropHandler(String target, JPanel col) { this.target = target; this.col = col; }

        @SuppressWarnings("unchecked")
        @Override public boolean canImport(TransferSupport s) {
            boolean ok = s.isDataFlavorSupported(DataFlavor.stringFlavor) && dragging != null
                    && ((List<String>) dragging.get("next")).contains(target);
            col.setBackground(ok ? Ui.COLUMN_HOT : Ui.COLUMN);
            return ok;
        }

        @Override public boolean importData(TransferSupport s) {
            if (!canImport(s)) return false;
            Row t = dragging;
            col.setBackground(Ui.COLUMN);
            javax.swing.SwingUtilities.invokeLater(() -> move(t, target));
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private void move(Row t, String target) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("status", target);
        if ("REJECTED".equals(target)) {
            String note = JOptionPane.showInputDialog(this, "Lý do từ chối bảo hành phiếu " + t.str("code") + ":", "Từ chối bảo hành", JOptionPane.QUESTION_MESSAGE);
            if (note == null) { draw(); return; }
            in.put("note", note);
        }
        if ("SENT_TO_VENDOR".equals(target)) {
            JComboBox<Ui.Item> sc = new JComboBox<>();
            for (Row r : (List<Row>) data.get("service_centers")) sc.addItem(new Ui.Item(r.lng("id"), r.str("name")));
            if (JOptionPane.showConfirmDialog(this, new Object[]{"Gửi phiếu " + t.str("code") + " tới trung tâm bảo hành hãng:", sc},
                    "Gửi hãng", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION || sc.getSelectedItem() == null) { draw(); return; }
            in.put("service_center_id", ((Ui.Item) sc.getSelectedItem()).id());
        }
        if (Ui.run(this, () -> ctx.app().warranty.changeStatus(user(), t.lng("id"), in))) {
            ctx.frame().refreshBadges();
        }
        refresh();
    }
}
