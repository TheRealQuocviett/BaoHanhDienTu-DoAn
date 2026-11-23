package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import vn.baohanh.db.Row;
import vn.baohanh.model.User;
import vn.baohanh.service.AppContext;
import vn.baohanh.service.AppException;
import vn.baohanh.util.Fmt;

/**
 * Bộ công cụ giao diện Swing dùng chung: màu sắc, phông chữ, nút, bảng dữ liệu,
 * biểu mẫu, ô nhập ngày và xử lý lỗi.
 */
public final class Ui {
    private Ui() {}

    public static final Color ACCENT = new Color(0x2563EB);
    public static final Color ACCENT_DARK = new Color(0x1D4ED8);
    public static final Color ACCENT_SOFT = new Color(0xEAF1FF);
    public static final Color ACCENT_TEXT = new Color(0x1E40AF);
    public static final Color INK = new Color(0x0F172A);
    public static final Color BG = new Color(0xF3F6FB);
    public static final Color CARD = Color.WHITE;
    public static final Color MUTE = new Color(0x64748B);
    public static final Color LINE = new Color(0xE3E8F0);
    public static final Color OK = new Color(0x059669);
    public static final Color BAD = new Color(0xDC2626);
    public static final Color WARN = new Color(0xB45309);
    public static final Color AMBER = new Color(0xF59E0B);
    public static final Color SKY = new Color(0x93C5FD);
    public static final Color SOFT = new Color(0xF6F8FC);
    /** Cặp màu (chữ, nền nhạt) cho ô số liệu. */
    public static final Color[][] TONES = {
            {ACCENT, ACCENT_SOFT}, {OK, new Color(0xE7F8F1)}, {WARN, new Color(0xFFF5E0)},
            {new Color(0x7C3AED), new Color(0xF2ECFF)}, {BAD, new Color(0xFDECEC)}};
    public static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static String fontName = "Dialog";

    /** Ngữ cảnh giao diện: dịch vụ nghiệp vụ + người đang đăng nhập. */
    public record Ctx(AppContext app, User user, String webUrl, MainFrame frame) {}

    // ===================== Giao diện chung =====================

    public static void installTheme() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win") || os.contains("mac")) {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());   // giao diện quen thuộc của hệ điều hành
            } else {
                for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels())
                    if ("Nimbus".equals(i.getName())) { UIManager.setLookAndFeel(i.getClassName()); break; }
            }
        } catch (Exception ignored) {
            // giữ giao diện mặc định
        }
        List<String> fonts = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String f : new String[]{"Segoe UI", "Inter", "Noto Sans", "DejaVu Sans", "Arial"})
            if (fonts.contains(f)) { fontName = f; break; }
        FontUIResource base = new FontUIResource(fontName, Font.PLAIN, 14);
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object k = keys.nextElement();
            if (UIManager.get(k) instanceof FontUIResource) UIManager.put(k, base);
        }
        UIManager.put("defaultFont", base);
        UIManager.put("nimbusBase", new Color(0x7189B8));
        UIManager.put("nimbusBlueGrey", new Color(0xC9D3E3));
        UIManager.put("nimbusFocus", ACCENT);
        UIManager.put("nimbusSelectionBackground", ACCENT);
        UIManager.put("nimbusSelection", ACCENT);
        UIManager.put("control", BG);
        UIManager.put("text", INK);
        UIManager.put("Table.alternateRowColor", new Color(0xFAFBFE));
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("TextField.contentMargins", new Insets(6, 8, 6, 8));
        UIManager.put("PasswordField.contentMargins", new Insets(6, 8, 6, 8));
        UIManager.put("FormattedTextField.contentMargins", new Insets(6, 8, 6, 8));
        UIManager.put("OptionPane.yesButtonText", "Đồng ý");
        UIManager.put("OptionPane.noButtonText", "Không");
        UIManager.put("OptionPane.cancelButtonText", "Hủy");
        UIManager.put("OptionPane.okButtonText", "OK");
        UIManager.put("FileChooser.saveButtonText", "Lưu");
        UIManager.put("FileChooser.cancelButtonText", "Hủy");
    }

    /** Biểu tượng logo: ô vuông bo góc xanh với hình khiên trắng. */
    public static javax.swing.Icon logo(int size) {
        return new javax.swing.Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new java.awt.GradientPaint(x, y, new Color(0x3B82F6), x + size, y + size, ACCENT_DARK));
                g.fillRoundRect(x, y, size, size, size * 3 / 10, size * 3 / 10);
                double w = size * 0.42, h = size * 0.5, cx = x + size / 2.0, top = y + size * 0.24;
                java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
                p.moveTo(cx, top);
                p.lineTo(cx + w / 2, top + h * 0.18);
                p.lineTo(cx + w / 2, top + h * 0.55);
                p.lineTo(cx, top + h);
                p.lineTo(cx - w / 2, top + h * 0.55);
                p.lineTo(cx - w / 2, top + h * 0.18);
                p.closePath();
                g.setColor(Color.WHITE);
                g.fill(p);
                g.dispose();
            }
        };
    }

    public static Font font(int style, int size) {
        return new Font(fontName, style, size);
    }

    public static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.BOLD, 24));
        l.setForeground(INK);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        return l;
    }

    public static JLabel sub(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTE);
        return l;
    }

    public static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.BOLD, 15));
        return l;
    }

    /** Nút trắng viền xám (vẽ giống nhau trên mọi hệ điều hành). */
    public static JButton button(String text, Runnable action) {
        return flat(text, CARD, INK, new Color(0xD6DEEA), action);
    }

    /** Nút chính màu xanh dương. */
    public static JButton primary(String text, Runnable action) {
        JButton b = flat(text, ACCENT, Color.WHITE, ACCENT, action);
        b.setFont(font(Font.BOLD, 14));
        return b;
    }

    public static JButton danger(String text, Runnable action) {
        return flat(text, CARD, BAD, new Color(0xF3B4B4), action);
    }

    /** Nút bo góc tự vẽ. */
    private static final class FlatButton extends JButton {
        private static final long serialVersionUID = 1L;
        private final Color base, border, hover;
        private boolean over;

        FlatButton(String text, Color bg, Color fg, Color border) {
            super(text);
            this.base = bg;
            this.border = border;
            this.hover = bg == CARD ? SOFT : bg.equals(ACCENT) ? ACCENT_DARK : bg.darker();
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setForeground(fg);
            setBorder(pad(8, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { over = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { over = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = !isEnabled() ? SOFT : over || getModel().isPressed() ? hover : base;
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g.setColor(isEnabled() ? border : LINE);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    private static JButton flat(String text, Color bg, Color fg, Color border, Runnable action) {
        JButton b = new FlatButton(text, bg, fg, border);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Panel nền trắng bo góc, viền mảnh. */
    public static class RoundPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final int arc;
        Color border = LINE;

        public RoundPanel(java.awt.LayoutManager lm, int arc) {
            super(lm);
            this.arc = arc;
            setOpaque(false);
            setBackground(CARD);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(15, 23, 42, 10));
            g.fillRoundRect(0, 2, getWidth() - 1, getHeight() - 2, arc, arc);
            g.setColor(getBackground());
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 3, arc, arc);
            g.setColor(border);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 3, arc, arc);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    public static Border pad(int v, int h) {
        return BorderFactory.createEmptyBorder(v, h, v, h);
    }

    /** Khung trắng có viền, dùng như "card". */
    public static JPanel card(JComponent inner) {
        JPanel p = new RoundPanel(new BorderLayout(), 16);
        p.setBorder(pad(14, 16));
        p.add(inner);
        return p;
    }

    public static JPanel flow(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.setOpaque(false);
        for (Component c : cs) p.add(c);
        return p;
    }

    /** Tiêu đề trang + mô tả + các nút bên phải. */
    public static JPanel header(String title, String sub, Component... right) {
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(title(title), BorderLayout.NORTH);
        if (sub != null) left.add(sub(sub), BorderLayout.SOUTH);
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        p.add(left, BorderLayout.WEST);
        if (right.length > 0) {
            JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            r.setOpaque(false);
            for (Component c : right) r.add(c);
            p.add(r, BorderLayout.EAST);
        }
        return p;
    }

    /** Ô tìm kiếm gọi lại sau mỗi lần gõ. */
    public static JTextField search(String placeholder, Consumer<String> onChange) {
        JTextField f = new JTextField(24);
        f.putClientProperty("JTextField.placeholderText", placeholder);
        f.setToolTipText(placeholder);
        f.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onChange.accept(f.getText()); }
            public void removeUpdate(DocumentEvent e) { onChange.accept(f.getText()); }
            public void changedUpdate(DocumentEvent e) { onChange.accept(f.getText()); }
        });
        return f;
    }

    // ===================== Biểu mẫu =====================

    /** Biểu mẫu 2 cột nhãn – ô nhập. */
    public static class Form extends JPanel {
        private static final long serialVersionUID = 1L;
        private int row;

        public Form() {
            super(new GridBagLayout());
            setOpaque(false);
        }

        public Form add(String label, Component field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.insets = new Insets(4, 0, 4, 10);
            c.anchor = GridBagConstraints.NORTHWEST;
            c.gridx = 0;
            JLabel l = new JLabel(label);
            l.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
            super.add(l, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(4, 0, 4, 0);
            super.add(field, c);
            return this;
        }

        public Form full(Component comp) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(6, 0, 4, 0);
            super.add(comp, c);
            return this;
        }
    }

    /** Một lựa chọn trong ô combo (id + nhãn). */
    public record Item(Object id, String label) {
        @Override public String toString() { return label; }
    }

    public static JComboBox<Item> combo(List<Row> options, String blank) {
        JComboBox<Item> cb = new JComboBox<>();
        if (blank != null) cb.addItem(new Item(null, blank));
        for (Row r : options) cb.addItem(new Item(r.get("id"), r.str("label")));
        return wide(cb, 240);
    }

    /** Đặt độ rộng tối thiểu cho ô chọn (tránh bị cắt chữ). */
    public static <T> JComboBox<T> wide(JComboBox<T> cb, int px) {
        Dimension d = cb.getPreferredSize();
        cb.setPreferredSize(new Dimension(Math.max(px, d.width), d.height));
        return cb;
    }

    public static void select(JComboBox<Item> cb, Object id) {
        for (int i = 0; i < cb.getItemCount(); i++) {
            Object v = cb.getItemAt(i).id();
            if (v != null && id != null && String.valueOf(v).equals(String.valueOf(id))) { cb.setSelectedIndex(i); return; }
        }
    }

    public static Object selectedId(JComboBox<Item> cb) {
        Item i = (Item) cb.getSelectedItem();
        return i == null ? null : i.id();
    }

    /** "23/11/2026" -> "2026-11-23"; chuỗi rỗng -> "". */
    public static String toIso(String vn, String label) {
        String s = vn == null ? "" : vn.trim();
        if (s.isEmpty()) return "";
        try {
            return LocalDate.parse(s, VN_DATE).toString();
        } catch (DateTimeParseException e) {
            try { return LocalDate.parse(s).toString(); }
            catch (DateTimeParseException e2) { throw new AppException(label + " phải có dạng ngày/tháng/năm, vd 25/12/2026"); }
        }
    }

    public static String vnDate(LocalDate d) {
        return d == null ? "" : d.format(VN_DATE);
    }

    // ===================== Bảng dữ liệu =====================

    public enum Kind { TEXT, MONEY, INT, DATE, DATETIME, BOOL, STATUS }

    public record Col(String key, String title, Kind kind, int width) {
        public static Col text(String k, String t, int w) { return new Col(k, t, Kind.TEXT, w); }
        public static Col money(String k, String t) { return new Col(k, t, Kind.MONEY, 110); }
        public static Col num(String k, String t) { return new Col(k, t, Kind.INT, 70); }
        public static Col date(String k, String t) { return new Col(k, t, Kind.DATE, 95); }
        public static Col dateTime(String k, String t) { return new Col(k, t, Kind.DATETIME, 125); }
    }

    /** Model bảng dựa trên danh sách Row. */
    public static class RowModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final List<Col> cols;
        private List<Row> rows = new ArrayList<>();

        public RowModel(List<Col> cols) { this.cols = cols; }

        public void setRows(List<Row> r) { rows = new ArrayList<>(r); fireTableDataChanged(); }
        public Row row(int i) { return rows.get(i); }
        public List<Row> rows() { return rows; }
        public Col col(int i) { return cols.get(i); }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.size(); }
        @Override public String getColumnName(int c) { return cols.get(c).title(); }
        @Override public Class<?> getColumnClass(int c) {
            return switch (cols.get(c).kind()) { case MONEY, INT -> Long.class; default -> Object.class; };
        }
        @Override public Object getValueAt(int r, int c) {
            Col col = cols.get(c);
            Row row = rows.get(r);
            Object v = row.get(col.key());
            return switch (col.kind()) {
                case MONEY, INT -> v instanceof Number n ? n.longValue() : 0L;
                case BOOL -> row.bool(col.key()) ? "Có" : "Không";
                case STATUS -> row;      // renderer tự vẽ
                default -> v == null ? "" : String.valueOf(v);
            };
        }
    }

    /** Bảng có định dạng tiền / ngày, sắp xếp được, nhấp đúp để mở. */
    public static JTable table(RowModel model, Consumer<Row> onOpen) {
        JTable t = new JTable(model);
        t.setRowHeight(36);
        t.setFillsViewportHeight(true);
        t.setAutoCreateRowSorter(true);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setGridColor(new Color(0xEEF2F7));
        t.setSelectionBackground(ACCENT_SOFT);
        t.setSelectionForeground(INK);
        t.setBackground(CARD);
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(10, 38));
        t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean s1, boolean f1, int r, int c) {
                super.getTableCellRendererComponent(tb, v == null ? "" : String.valueOf(v).toUpperCase(), false, false, r, c);
                setFont(font(Font.BOLD, 11));
                setForeground(MUTE);
                setBackground(SOFT);
                setOpaque(true);
                Col col = model.col(tb.convertColumnIndexToModel(c));
                setHorizontalAlignment(col.kind() == Kind.MONEY || col.kind() == Kind.INT ? SwingConstants.RIGHT : SwingConstants.LEFT);
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), pad(0, 8)));
                return this;
            }
        });
        t.setDefaultRenderer(Object.class, new CellRenderer(model));
        t.setDefaultRenderer(Long.class, new CellRenderer(model));
        if (t.getRowSorter() instanceof TableRowSorter<?> sorter) {
            for (int i = 0; i < model.getColumnCount(); i++)
                if (model.col(i).kind() == Kind.STATUS) sorter.setSortable(i, false);
        }
        for (int i = 0; i < model.getColumnCount(); i++) {
            int w = model.col(i).width();
            if (w > 0) t.getColumnModel().getColumn(i).setPreferredWidth(w);
        }
        if (onOpen != null) {
            t.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && t.getSelectedRow() >= 0)
                        onOpen.accept(model.row(t.convertRowIndexToModel(t.getSelectedRow())));
                }
            });
            t.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "openRow");
            t.getActionMap().put("openRow", new javax.swing.AbstractAction() {
                private static final long serialVersionUID = 1L;
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (t.getSelectedRow() >= 0) onOpen.accept(model.row(t.convertRowIndexToModel(t.getSelectedRow())));
                }
            });
        }
        return t;
    }

    public static Row selected(JTable t, RowModel m) {
        int r = t.getSelectedRow();
        return r < 0 ? null : m.row(t.convertRowIndexToModel(r));
    }

    public static JScrollPane scroll(Component c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(new javax.swing.border.LineBorder(LINE, 1, true));
        s.getViewport().setBackground(CARD);
        return s;
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final RowModel model;

        CellRenderer(RowModel model) { this.model = model; }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int column) {
            Col col = model.col(table.convertColumnIndexToModel(column));
            String text;
            int align = SwingConstants.LEFT;
            Color fg = sel ? table.getSelectionForeground() : INK;
            switch (col.kind()) {
                case MONEY -> { text = Fmt.money((Long) value); align = SwingConstants.RIGHT; }
                case INT -> { text = Fmt.number((Long) value); align = SwingConstants.RIGHT; }
                case DATE -> text = Fmt.date((String) value);
                case DATETIME -> text = Fmt.dateTime((String) value);
                case STATUS -> {
                    Row r = (Row) value;
                    text = r.str("status_label") + (Boolean.TRUE.equals(r.get("overdue")) ? "  • Quá hẹn" : "")
                            + ("PENDING".equals(r.str("quote_status")) ? "  • Chờ duyệt giá" : "");
                    if (!sel) fg = switch (r.str("status")) {
                        case "READY" -> ACCENT;
                        case "DIAGNOSING", "REPAIRING", "WAITING_PARTS", "SENT_TO_VENDOR" -> Boolean.TRUE.equals(r.get("overdue")) ? BAD : WARN;
                        case "RETURNED" -> OK;
                        case "REJECTED", "CANCELLED" -> BAD;
                        default -> Boolean.TRUE.equals(r.get("overdue")) ? BAD : INK;
                    };
                }
                default -> text = value == null ? "" : String.valueOf(value);
            }
            super.getTableCellRendererComponent(table, text, sel, focus, row, column);
            setHorizontalAlignment(align);
            setForeground(fg);
            setBorder(pad(0, 8));
            if (col.kind() == Kind.STATUS) setFont(getFont().deriveFont(Font.BOLD));
            return this;
        }
    }

    // ===================== Thông báo & lỗi =====================

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    /** Chạy một thao tác; lỗi nghiệp vụ hiện hộp thoại cảnh báo. Trả về true nếu thành công. */
    public static boolean run(Component parent, Action a) {
        try {
            a.run();
            return true;
        } catch (AppException e) {
            JOptionPane.showMessageDialog(parent, wrap(e.getMessage()), "Không thực hiện được", JOptionPane.WARNING_MESSAGE);
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(parent, wrap("Lỗi: " + e.getMessage()), "Lỗi hệ thống", JOptionPane.ERROR_MESSAGE);
        }
        return false;
    }

    /** Xuống dòng thông báo dài để hộp thoại không tràn màn hình. */
    static Object wrap(String msg) {
        if (msg == null || msg.length() < 90) return msg;
        return "<html><body style='width:460px'>" + Fmt.html(msg).replace("\n", "<br>") + "</body></html>";
    }

    public static void info(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Thông báo", JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirm(Component parent, String msg) {
        return JOptionPane.showConfirmDialog(parent, msg, "Xác nhận", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    /** Mở phiếu in trong trình duyệt (liên kết có chữ ký, không cần đăng nhập web). */
    public static void print(Ctx ctx, Component parent, String kind, long id) {
        run(parent, () -> {
            String url = ctx.webUrl() + ctx.app().print.link(ctx.user(), kind, id) + "&auto=1";
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                JTextField f = new JTextField(url);
                f.setEditable(false);
                JOptionPane.showMessageDialog(parent, f, "Mở địa chỉ này trong trình duyệt để in", JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    public static void browse(Component parent, String url) {
        run(parent, () -> Desktop.getDesktop().browse(new URI(url)));
    }

    /** Nhãn hiển thị giá trị có tiêu đề nhỏ phía trên. */
    public static JPanel kv(String label, String value) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setForeground(MUTE);
        l.setFont(font(Font.PLAIN, 12));
        JLabel v = new JLabel(value == null || value.isEmpty() ? "—" : value);
        v.setFont(font(Font.BOLD, 14));
        p.add(l, BorderLayout.NORTH);
        p.add(v, BorderLayout.CENTER);
        p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 10));
        return p;
    }

    /** Ô số liệu lớn trên trang tổng quan: biểu tượng màu + con số + nhãn. */
    public static JPanel tile(String value, String label, boolean hot, Runnable onClick) {
        String lb = label.toLowerCase();
        int k = 0;
        if (lb.contains("doanh thu") || lb.contains("gói") || lb.contains("sửa xong") || lb.contains("còn hạn")
                || lb.contains("trả khách") || lb.contains("tỷ lệ")) k = 1;
        else if (lb.contains("sắp hết") || lb.contains("báo giá") || lb.contains("thời gian") || lb.contains("quá hẹn")) k = 2;
        else if (lb.contains("yêu cầu") || lb.contains("của tôi") || lb.contains("tiếp nhận")) k = 3;
        Color[] tone = hot ? TONES[4] : TONES[k];
        final Color[] t = tone;
        RoundPanel p = new RoundPanel(new BorderLayout(14, 0), 16);
        p.setBorder(pad(14, 16));
        JComponent ic = new JComponent() {
            private static final long serialVersionUID = 1L;
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(t[1]);
                g.fillRoundRect(0, 0, 44, 44, 14, 14);
                g.setColor(t[0]);
                g.setStroke(new java.awt.BasicStroke(2.2f));
                g.drawOval(13, 13, 18, 18);
                g.fillOval(19, 19, 6, 6);
                g.dispose();
            }
        };
        ic.setPreferredSize(new Dimension(44, 44));
        JPanel icWrap = new JPanel(new java.awt.GridBagLayout());
        icWrap.setOpaque(false);
        icWrap.add(ic);
        JLabel v = new JLabel(value);
        v.setFont(font(Font.BOLD, value.length() > 8 ? 20 : 26));
        v.setForeground(hot ? BAD : INK);
        JLabel l = new JLabel("<html>" + Fmt.html(label) + "</html>");
        l.setForeground(MUTE);
        l.setFont(font(Font.PLAIN, 13));
        JPanel txt = new JPanel(new BorderLayout());
        txt.setOpaque(false);
        txt.add(v, BorderLayout.NORTH);
        txt.add(l, BorderLayout.CENTER);
        p.add(icWrap, BorderLayout.WEST);
        p.add(txt, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(210, 92));
        if (onClick != null) {
            p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            p.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { onClick.run(); }
                @Override public void mouseEntered(MouseEvent e) { p.border = new Color(0xB9C9E6); p.repaint(); }
                @Override public void mouseExited(MouseEvent e) { p.border = LINE; p.repaint(); }
            });
        }
        return p;
    }
}
