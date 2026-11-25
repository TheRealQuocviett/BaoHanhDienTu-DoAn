package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.service.AppContext;
import vn.baohanh.service.AppException;
import vn.baohanh.util.Fmt;
import vn.baohanh.util.QrCode;

/** Cài đặt xác thực 2 lớp (Google Authenticator) cho tài khoản đang đăng nhập + quản lý gỡ cho nhân viên. */
public class TwoFactorDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final AppContext app;
    private final User user;
    private final JPanel body = new JPanel();

    public TwoFactorDialog(Window owner, AppContext app, User user) {
        super(owner, "Bảo mật tài khoản – xác thực 2 lớp", ModalityType.APPLICATION_MODAL);
        this.app = app;
        this.user = user;
        setIconImages(Ui.logoImages());
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(Ui.pad(16, 18));
        body.setBackground(Ui.CARD);
        setContentPane(body);
        render();
        setMinimumSize(new Dimension(540, 260));
        setLocationRelativeTo(owner);
    }

    private void render() {
        body.removeAll();
        Row st;
        try { st = app.twoFactor.status(user); } catch (Exception e) { Ui.info(this, e.getMessage()); return; }
        put(Ui.title("Xác thực 2 lớp (2FA)"));
        put(Ui.sub("<html><div style='width:470px'>Ngoài mật khẩu, mỗi lần đăng nhập phải nhập mã 6 số đổi mỗi 30 giây trên điện thoại "
                + "(ứng dụng Google Authenticator). Lộ mật khẩu kẻ gian vẫn không vào được.</div></html>"));
        if (st.bool("enabled")) {
            put(label("<html><b style='color:" + Ui.hex(Ui.OK) + "'>● Đang bật</b> từ " + Fmt.html(Fmt.dateTime(st.str("enabled_at")))
                    + " · còn <b>" + st.lng("recovery_left") + "</b>/8 mã khôi phục</html>"));
            JTextField code = new JTextField(14);
            put(Ui.flow(new JLabel("Mã 6 số hoặc mã khôi phục:"), code,
                    Ui.button("Tạo lại mã khôi phục", () -> {
                        java.util.concurrent.atomic.AtomicReference<List<String>> r = new java.util.concurrent.atomic.AtomicReference<>();
                        if (Ui.run(this, () -> r.set(app.twoFactor.regenerate(user, code.getText())))) showCodes(r.get());
                        code.setText("");
                    }),
                    Ui.button("Tắt 2 lớp", () -> {
                        if (!Ui.confirm(this, "Tắt xác thực 2 lớp cho tài khoản này?")) return;
                        if (Ui.run(this, () -> app.twoFactor.disable(user, code.getText()))) render();
                    })));
        } else {
            if (user.role() == Role.ADMIN)
                put(label("<html><span style='color:" + Ui.hex(Ui.BAD) + "'>Tài khoản Quản lý có toàn quyền – nên bật 2 lớp.</span></html>"));
            put(Ui.flow(Ui.primary("Bắt đầu cài đặt", this::setup)));
        }
        if (user.role() == Role.ADMIN) addAdminList();
        body.revalidate();
        body.repaint();
        pack();
    }

    private void setup() {
        Row s;
        try { s = app.twoFactor.setup(user); } catch (Exception e) { Ui.info(this, e.getMessage()); return; }
        JPanel p = new JPanel(new BorderLayout(14, 8));
        p.add(new JLabel(new ImageIcon(QrCode.encode(s.str("otpauth")).toImage(5, 2))), BorderLayout.WEST);
        JTextArea info = new JTextArea("1. Cài Google Authenticator trên điện thoại.\n"
                + "2. Bấm + → Quét mã QR, quét mã bên trái.\n"
                + "   (Hoặc chọn Nhập khóa thiết lập và gõ:\n   " + s.str("secret") + ")\n"
                + "3. Nhập mã 6 số ứng dụng hiện ra:");
        info.setEditable(false);
        info.setOpaque(false);
        info.setFont(Ui.font(Font.PLAIN, 13));
        JTextField code = new JTextField(8);
        code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.add(info, BorderLayout.CENTER);
        right.add(code, BorderLayout.SOUTH);
        p.add(right, BorderLayout.CENTER);
        while (true) {
            if (JOptionPane.showConfirmDialog(this, p, "Quét mã QR bằng Google Authenticator", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            try {
                List<String> codes = app.twoFactor.enable(user, code.getText());
                showCodes(codes);
                render();
                return;
            } catch (Exception e) {
                Ui.info(this, e.getMessage());
                code.setText("");
            }
        }
    }

    private void showCodes(List<String> codes) {
        String txt = String.join("\n", codes);
        JTextArea a = new JTextArea(4, 24);
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        a.setText(codes.get(0) + "    " + codes.get(1) + "\n" + codes.get(2) + "    " + codes.get(3) + "\n"
                + codes.get(4) + "    " + codes.get(5) + "\n" + codes.get(6) + "    " + codes.get(7));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html><b>Mã khôi phục – chỉ hiện một lần.</b><br>In ra hoặc chép cất nơi an toàn. Mất điện thoại thì dùng<br>"
                + "một mã này thay cho mã 6 số (mỗi mã dùng được 1 lần).</html>"), BorderLayout.NORTH);
        p.add(a, BorderLayout.CENTER);
        JButton copy = Ui.button("Chép vào bộ nhớ tạm", () ->
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txt), null));
        p.add(Ui.flow(copy), BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, p, "Mã khôi phục", JOptionPane.PLAIN_MESSAGE);
    }

    private void addAdminList() {
        put(javax.swing.Box.createVerticalStrut(14));
        put(label("<html><div style='width:470px'><b>Nhân viên đã bật 2 lớp</b> – gỡ khi nhân viên mất điện thoại và hết mã khôi phục.</div></html>"));
        try {
            List<Row> list = app.twoFactor.enabledUsers(user);
            if (list.isEmpty()) put(label("Chưa có ai bật."));
            for (Row r : list) {
                long id = r.lng("id");
                JLabel l = new JLabel(r.str("full_name") + " (" + r.str("username") + ")   ");
                if (id == user.id()) put(Ui.flow(l, new JLabel("(bạn)")));
                else put(Ui.flow(l, Ui.button("Gỡ 2 lớp", () -> {
                    if (Ui.confirm(this, "Gỡ xác thực 2 lớp của " + r.str("full_name") + "?")
                            && Ui.run(this, () -> app.twoFactor.adminReset(user, id))) render();
                })));
            }
        } catch (Exception e) {
            put(label(e.getMessage()));
        }
    }

    private JLabel label(String html) {
        JLabel l = new JLabel(html);
        l.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        return l;
    }

    private void put(java.awt.Component c) {
        if (c instanceof javax.swing.JComponent j) j.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(c);
    }

    /** Bước hỏi mã khi đăng nhập. Trả về User nếu đúng; null nếu người dùng bấm Hủy hoặc phải nhập lại mật khẩu. */
    public static User askCode(Component parent, AppContext app, String token) {
        JTextField code = new JTextField(10);
        code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
        JLabel err = new JLabel(" ");
        err.setForeground(Ui.BAD);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html>Tài khoản đã bật <b>xác thực 2 lớp</b>.<br>Mở Google Authenticator và nhập mã 6 số<br>"
                + "(hoặc một mã khôi phục dạng abcd-efgh).</html>"), BorderLayout.NORTH);
        p.add(code, BorderLayout.CENTER);
        p.add(err, BorderLayout.SOUTH);
        while (true) {
            javax.swing.SwingUtilities.invokeLater(code::requestFocusInWindow);
            if (JOptionPane.showConfirmDialog(parent, p, "Nhập mã xác thực", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
            try {
                return app.twoFactor.finishPending(token, code.getText());
            } catch (AppException e) {
                if (e.getStatus() == 401) { Ui.info(parent, e.getMessage()); return null; }
                err.setText(e.getMessage());
                code.setText("");
            } catch (Exception e) {
                Ui.info(parent, e.getMessage());
                return null;
            }
        }
    }

}
