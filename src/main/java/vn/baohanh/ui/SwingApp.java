package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import vn.baohanh.model.User;
import vn.baohanh.service.AppContext;

/**
 * Điểm vào giao diện Swing: hiện cửa sổ đăng nhập, đăng nhập thành công thì mở cửa sổ chính.
 * Đóng cửa sổ đăng nhập (không đăng nhập) sẽ thoát chương trình.
 */
public final class SwingApp {
    private SwingApp() {}

    public static void launch(AppContext app, String webUrl) {
        SwingUtilities.invokeLater(() -> {
            Ui.installTheme();
            showLogin(app, webUrl);
        });
    }

    static void showLogin(AppContext app, String webUrl) {
        JDialog d = new JDialog((JFrame) null, "Đăng nhập – Quản lý bảo hành", true);
        d.setIconImages(Ui.logoImages());
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.CARD);
        root.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(5, 0, 0, 0, Ui.ACCENT), Ui.pad(24, 28)));

        String shop = "";
        try { shop = app.settings.get("shop_name"); } catch (Exception ignored) { }
        JPanel head = new JPanel(new GridLayout(0, 1));
        head.setOpaque(false);
        JLabel brand = new JLabel(shop.isEmpty() ? "Bảo hành điện tử" : shop, Ui.logo(26), JLabel.LEFT);
        brand.setIconTextGap(9);
        brand.setForeground(Ui.INK);
        brand.setFont(Ui.font(Font.BOLD, 14));
        head.add(brand);
        head.add(Ui.title("Đăng nhập"));
        head.add(Ui.sub("Hệ thống quản lý bảo hành điện tử"));
        root.add(head, BorderLayout.NORTH);

        JTextField user = new JTextField(20);
        JPasswordField pass = new JPasswordField(20);
        JLabel err = new JLabel(" ");
        err.setForeground(Ui.BAD);
        Ui.Form f = new Ui.Form();
        f.setBorder(BorderFactory.createEmptyBorder(16, 0, 4, 0));
        f.add("Tên đăng nhập", user).add("Mật khẩu", pass).full(err);
        root.add(f, BorderLayout.CENTER);

        JButton ok = Ui.primary("Đăng nhập", () -> {
            try {
                vn.baohanh.service.Auth.LoginStep st = app.auth.begin(user.getText(), new String(pass.getPassword()));
                User u = st.user();
                if (st.needCode()) {
                    u = TwoFactorDialog.askCode(d, app, st.pendingToken());
                    if (u == null) { pass.setText(""); pass.requestFocusInWindow(); return; }
                }
                d.dispose();
                MainFrame mf = new MainFrame(app, u, webUrl);
                mf.setVisible(true);
                if (u.role() == vn.baohanh.model.Role.ADMIN && !app.twoFactor.isEnabled(u.id())) {
                    User me = u;
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        if (javax.swing.JOptionPane.showConfirmDialog(mf, "<html>Tài khoản <b>Quản lý</b> chưa bật xác thực 2 lớp.<br>"
                                + "Bật để lỡ lộ mật khẩu cũng không ai đăng nhập được. Cài đặt ngay?</html>", "Bảo mật tài khoản",
                                javax.swing.JOptionPane.YES_NO_OPTION) == javax.swing.JOptionPane.YES_OPTION)
                            new TwoFactorDialog(mf, app, me).setVisible(true);
                    });
                }
            } catch (Exception ex) {
                err.setText(ex.getMessage());
                pass.setText("");
                pass.requestFocusInWindow();
            }
        });
        JLabel hint = new JLabel("<html>Tài khoản mẫu: admin/admin123 · tiepnhan/123456 · ktv1/123456<br>"
                + "Giao diện web: " + webUrl + "</html>");
        hint.setForeground(Ui.MUTE);
        hint.setFont(Ui.font(Font.PLAIN, 12));
        JPanel bottom = new JPanel(new BorderLayout(0, 10));
        bottom.setOpaque(false);
        bottom.add(ok, BorderLayout.NORTH);
        bottom.add(hint, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        d.getRootPane().setDefaultButton(ok);
        d.setContentPane(root);
        d.getContentPane().setBackground(Ui.CARD);
        d.pack();
        d.setMinimumSize(new Dimension(420, d.getHeight()));
        d.setLocationRelativeTo(null);
        d.setVisible(true);   // chặn tới khi đóng
        if (!MainFrame.isOpen()) {
            System.out.println("Da dong cua so dang nhap. Giao dien web van chay tai " + webUrl + " (nhan Ctrl+C de tat).");
        }
    }
}
