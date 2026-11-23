package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import vn.baohanh.service.SettingService;
import vn.baohanh.util.Fmt;

/** Thông tin cửa hàng in trên phiếu + địa chỉ cổng tra cứu. */
public class SettingsView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final Map<String, JComponent> fields = new LinkedHashMap<>();
    private final JLabel portal = new JLabel(" ");

    public SettingsView(Ui.Ctx ctx) {
        super(ctx);
        add(Ui.header("Thông tin cửa hàng", "In trên phiếu tiếp nhận, phiếu trả máy và phiếu bảo hành điện tử."), BorderLayout.NORTH);
        Ui.Form f = new Ui.Form();
        SettingService.KEYS.forEach((k, label) -> {
            JComponent c;
            if (SettingService.MULTILINE.contains(k)) {
                int rows = "policy".equals(k) ? 4 : 3;
                JTextArea ta = new JTextArea(rows, 30);
                ta.setLineWrap(true);
                ta.setWrapStyleWord(true);
                ta.setFont(Ui.font(java.awt.Font.PLAIN, 14));
                ta.setBorder(Ui.pad(4, 6));
                c = ta;
                JScrollPane sp = new JScrollPane(ta);
                int h = rows * ta.getFontMetrics(ta.getFont()).getHeight() + 14;
                sp.setPreferredSize(new java.awt.Dimension(420, h));
                sp.setMinimumSize(new java.awt.Dimension(200, h));
                f.add(label, sp);
            } else {
                JTextField tf = new JTextField(30);
                tf.setMinimumSize(new java.awt.Dimension(200, tf.getPreferredSize().height));
                c = tf;
                f.add(label, tf);
            }
            String hint = SettingService.HINTS.get(k);
            if (hint != null) {
                JLabel hl = Ui.sub(hint);
                hl.setFont(Ui.font(java.awt.Font.PLAIN, 12));
                f.add("", hl);
            }
            fields.put(k, c);
        });
        f.full(portal);
        f.full(Ui.flow(Ui.primary("Lưu", this::save)));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(Ui.card(f), BorderLayout.NORTH);
        JScrollPane sc = new JScrollPane(wrap);
        sc.setBorder(null);
        sc.getViewport().setBackground(Ui.BG);
        sc.getVerticalScrollBar().setUnitIncrement(18);
        add(sc, BorderLayout.CENTER);
    }

    private void save() {
        Map<String, Object> in = new LinkedHashMap<>();
        fields.forEach((k, c) -> in.put(k, c instanceof JTextArea ta ? ta.getText() : ((JTextField) c).getText()));
        if (Ui.run(this, () -> ctx.app().settings.save(user(), in))) {
            refresh();
            Ui.info(this, "Đã lưu thông tin cửa hàng");
        }
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            Map<String, String> s = ctx.app().settings.all();
            fields.forEach((k, c) -> {
                if (c instanceof JTextArea ta) ta.setText(s.get(k)); else ((JTextField) c).setText(s.get(k));
            });
            portal.setText("<html>Cổng tra cứu cho khách hiện tại: <b>" + Fmt.html(ctx.app().settings.portalBase()) + "/tra-cuu</b><br>"
                    + "Mã QR trên phiếu dẫn tới địa chỉ này. Điện thoại khách phải cùng mạng Wi-Fi với máy chạy phần mềm,<br>"
                    + "hoặc nhập tên miền công khai nếu cửa hàng có.</html>");
        });
    }
}
