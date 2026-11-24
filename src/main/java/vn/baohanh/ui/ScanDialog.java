package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/**
 * Hộp thoại quét mã vạch / IMEI cho ứng dụng Swing:
 * dùng điện thoại quét mã QR rồi chụp tem (kết quả tự hiện), hoặc chọn một ảnh có mã vạch trên máy.
 */
public final class ScanDialog {
    private ScanDialog() {}

    /** Ô nhập kèm nút "Quét" bên phải. {@code after} chạy sau khi điền mã (vd: tự tra cứu). */
    public static JPanel field(Ui.Ctx ctx, JTextField target, Runnable after) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setOpaque(false);
        p.add(target, BorderLayout.CENTER);
        JButton b = Ui.button("Quét mã", () -> open(ctx, target, target, after));
        b.setToolTipText("Quét mã vạch / IMEI bằng điện thoại hoặc từ ảnh");
        p.add(b, BorderLayout.EAST);
        return p;
    }

    public static void open(Ui.Ctx ctx, Component parent, JTextField target, Runnable after) {
        Window owner = parent == null ? ctx.frame() : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "Quét mã vạch / IMEI", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        List<Row> found = new ArrayList<>();
        boolean[] picked = {false};
        JPanel codes = new JPanel();
        codes.setLayout(new BoxLayout(codes, BoxLayout.Y_AXIS));
        codes.setOpaque(false);
        JLabel status = new JLabel(" ");
        status.setForeground(Ui.MUTE);

        Runnable[] render = new Runnable[1];
        java.util.function.Consumer<String> pick = text -> {
            picked[0] = true;
            target.setText(text);
            d.dispose();
            target.requestFocusInWindow();
            if (after != null) after.run();
        };
        java.util.function.Consumer<List<Row>> add = list -> {
            int fresh = 0;
            for (Row c : list)
                if (found.stream().noneMatch(x -> x.str("text").equals(c.str("text")))) { found.add(c); fresh++; }
            render[0].run();
            if (fresh > 0 && found.size() == 1 && !picked[0]) {
                Timer t = new Timer(450, e -> { if (d.isDisplayable()) pick.accept(found.get(0).str("text")); });
                t.setRepeats(false);
                t.start();
            }
        };
        render[0] = () -> {
            codes.removeAll();
            if (!found.isEmpty()) {
                JLabel l = Ui.bold("Đã đọc được " + found.size() + " mã – bấm vào mã cần dùng:");
                l.setForeground(Ui.OK);
                l.setAlignmentX(0);
                codes.add(l);
            }
            for (Row c : found) {
                JButton b = Ui.button("<html><b style='font-family:monospace;font-size:13px'>" + Fmt.html(c.str("text")) + "</b> &nbsp;<span style='color:"
                        + Ui.hex(Ui.MUTE) + "'>" + Fmt.html(c.str("kind")) + " · " + Fmt.html(c.str("format")) + "</span></html>", () -> pick.accept(c.str("text")));
                b.setAlignmentX(0);
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
                row.setOpaque(false);
                row.setAlignmentX(0);
                row.add(b);
                codes.add(row);
            }
            codes.revalidate();
            codes.repaint();
            d.pack();
        };

        // ----- Điện thoại làm máy quét -----
        JPanel phone = box("Dùng điện thoại");
        String[] token = {null};
        Timer poll = new Timer(1500, null);
        try {
            Row s = ctx.app().scan.start(ctx.user());
            token[0] = s.str("token");
            String url = s.str("url");
            phone.add(note("Mở camera điện thoại (cùng mạng Wi-Fi), quét mã QR này rồi chụp tem mã vạch. Kết quả tự hiện ở đây."));
            JPanel qrWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 6));
            qrWrap.setOpaque(false);
            qrWrap.setAlignmentX(0);
            qrWrap.add(Ui.qr(url, 210));
            phone.add(qrWrap);
            JLabel u = new JLabel(url.contains("localhost") ? "<html><span style='color:" + Ui.hex(Ui.BAD) + "'>Máy chưa kết nối mạng LAN – điện thoại sẽ không mở được.</span></html>" : url);
            u.setFont(Ui.font(Font.PLAIN, 11));
            u.setForeground(Ui.MUTE);
            u.setAlignmentX(0);
            phone.add(u);
            int[] since = {0};
            boolean[] seen = {false};
            poll.addActionListener(e -> {
                try {
                    Row p = ctx.app().scan.poll(ctx.user(), token[0], since[0]);
                    since[0] = (int) p.lng("total");
                    if (Boolean.TRUE.equals(p.get("phone_seen")) && !seen[0]) {
                        seen[0] = true;
                        u.setText("✓ Điện thoại đã kết nối – hãy chụp mã vạch.");
                        u.setForeground(Ui.OK);
                    }
                    @SuppressWarnings("unchecked") List<Row> c = (List<Row>) p.get("codes");
                    if (!c.isEmpty()) add.accept(c);
                } catch (Exception ex) {
                    poll.stop();
                    u.setText(ex.getMessage());
                }
            });
            poll.start();
        } catch (Exception e) {
            phone.add(note("Không tạo được phiên quét: " + e.getMessage()));
        }

        // ----- Ảnh trên máy -----
        JPanel file = box("Ảnh trên máy này");
        file.add(note("Chọn ảnh chụp tem mã vạch (JPG, PNG). Đọc được Code 128, Code 39 (serial, IMEI) và EAN-13."));
        JButton choose = Ui.primary("Chọn ảnh có mã vạch…", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Ảnh (jpg, png, bmp, gif)", "jpg", "jpeg", "png", "bmp", "gif"));
            if (fc.showOpenDialog(d) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            status.setText("Đang đọc ảnh…");
            d.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            new SwingWorker<Row, Void>() {
                @Override protected Row doInBackground() throws Exception {
                    return ctx.app().scan.decode(ctx.user(), Files.readAllBytes(f.toPath()));
                }
                @Override protected void done() {
                    d.setCursor(Cursor.getDefaultCursor());
                    try {
                        @SuppressWarnings("unchecked") List<Row> c = (List<Row>) get().get("codes");
                        status.setText(c.isEmpty() ? "Không thấy mã vạch. Chụp gần hơn, đủ sáng, mã nằm ngang." : " ");
                        add.accept(c);
                    } catch (Exception ex) {
                        Throwable t = ex.getCause() == null ? ex : ex.getCause();
                        status.setText(t.getMessage());
                    }
                }
            }.execute();
        });
        choose.setAlignmentX(0);
        file.add(choose);
        status.setAlignmentX(0);
        status.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        file.add(status);
        file.add(note(" "));
        file.add(note("Mẹo: in phiếu tiếp nhận có sẵn mã vạch serial – lần sau chụp phiếu là đọc được."));

        JPanel grid = new JPanel(new java.awt.GridLayout(1, 2, 12, 0));
        grid.setOpaque(false);
        grid.add(phone);
        grid.add(file);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Ui.CARD);
        root.setBorder(Ui.pad(18, 20));
        root.add(Ui.header("Quét mã vạch / IMEI", "Để mã vạch nằm ngang, đủ sáng và chiếm phần lớn khung hình."), BorderLayout.NORTH);
        root.add(grid, BorderLayout.CENTER);
        root.add(codes, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                poll.stop();
                if (token[0] != null) ctx.app().scan.stop(ctx.user(), token[0]);
            }
        });
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    private static JPanel box(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Ui.SOFT);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.LINE), Ui.pad(12, 14)));
        JLabel t = Ui.bold(title);
        t.setAlignmentX(0);
        p.add(t);
        return p;
    }

    private static JLabel note(String text) {
        JLabel l = new JLabel("<html><body style='width:250px'>" + Fmt.html(text) + "</body></html>");
        l.setForeground(Ui.MUTE);
        l.setFont(Ui.font(Font.PLAIN, 12));
        l.setAlignmentX(0);
        l.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        return l;
    }
}
