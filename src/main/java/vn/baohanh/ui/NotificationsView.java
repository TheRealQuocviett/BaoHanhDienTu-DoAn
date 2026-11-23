package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;

/** Nhật ký thông báo gửi khách (email / SMS / Zalo) và cấu hình gửi. */
public class NotificationsView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private static final String[] STATUS_KEYS = {"", "PENDING", "SENT", "FAILED"};

    private final Ui.RowModel model = new Ui.RowModel(List.of(Ui.Col.dateTime("created_at", "Thời gian"),
            Ui.Col.text("event_label", "Sự kiện", 170), Ui.Col.text("channel_label", "Kênh", 60),
            Ui.Col.text("customer_name", "Khách", 150), Ui.Col.text("recipient", "Gửi tới", 170),
            Ui.Col.text("content", "Nội dung", 380), Ui.Col.text("status_label", "Trạng thái", 100),
            Ui.Col.text("last_error", "Lỗi gần nhất", 240)));
    private final JTable table = Ui.table(model, null);
    private final JTextField q = Ui.search("Tìm theo khách, SĐT/email, mã phiếu…", s -> refresh());
    private final JComboBox<String> status = Ui.wide(new JComboBox<>(new String[]{"Tất cả trạng thái", "Đang chờ gửi", "Đã gửi", "Gửi lỗi"}), 170);
    private final JLabel summary = new JLabel(" ");

    public NotificationsView(Ui.Ctx ctx) {
        super(ctx);
        boolean admin = ctx.user().is(Role.ADMIN);
        List<Component> right = new java.util.ArrayList<>();
        right.add(Ui.button("Gửi lại tin lỗi đã chọn", this::retry));
        if (admin) {
            right.add(Ui.button("Gửi ngay các tin đang chờ", () -> {
                final int[] n = new int[2];
                if (Ui.run(this, () -> { n[0] = ctx.app().notify.scanExpiring(); n[1] = ctx.app().notify.processDue(); })) {
                    refresh();
                    Ui.info(this, "Đã xử lý " + n[1] + " tin" + (n[0] > 0 ? ", thêm " + n[0] + " tin nhắc hết hạn" : ""));
                }
            }));
            right.add(Ui.primary("Cấu hình", this::openConfig));
        }
        add(Ui.header("Thông báo cho khách", "Email / SMS / Zalo tự gửi khi tiếp nhận máy, có báo giá, sửa xong, hẹn lịch và sắp hết hạn bảo hành.",
                right.toArray(new Component[0])), BorderLayout.NORTH);
        status.addActionListener(e -> refresh());
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(Ui.flow(q, status), BorderLayout.NORTH);
        top.add(summary, BorderLayout.SOUTH);
        c.add(top, BorderLayout.NORTH);
        c.add(Ui.scroll(table), BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    private void retry() {
        Row r = Ui.selected(table, model);
        if (r == null) { Ui.info(this, "Chọn một tin trong bảng"); return; }
        if (Ui.run(this, () -> ctx.app().notify.retry(user(), r.lng("id")))) {
            refresh();
            ctx.frame().refreshBadges();
            Ui.info(this, "Đã đưa vào hàng đợi, tin sẽ được gửi trong ít giây.");
        }
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            List<Row> list = ctx.app().notify.list(user(), STATUS_KEYS[Math.max(0, status.getSelectedIndex())], q.getText(), null);
            for (Row r : list) r.put("content", "ZALO".equals(r.str("channel")) ? r.str("subject") : r.str("body"));
            model.setRows(list);
            Row s = ctx.app().notify.stats(user());
            summary.setText(s.bool("enabled")
                    ? "Đang bật · " + s.lng("PENDING") + " tin chờ gửi · " + s.lng("SENT") + " đã gửi · " + s.lng("FAILED") + " lỗi"
                    : "Chức năng gửi thông báo đang TẮT – quản trị viên bấm \"Cấu hình\" để bật và nhập tài khoản email / SMS / Zalo.");
        });
    }

    // ===================== Hộp thoại cấu hình =====================

    @SuppressWarnings("unchecked")
    private void openConfig() {
        final Row[] values = new Row[1];
        if (!Ui.run(this, () -> values[0] = ctx.app().notify.getConfig(user()))) return;
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog d = new JDialog(owner, "Cấu hình thông báo cho khách", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        Map<String, JComponent> inputs = new LinkedHashMap<>();
        Ui.Form form = new Ui.Form();
        form.setBorder(Ui.pad(14, 16));
        String group = null;
        for (Object o : ctx.app().notify.fieldDefs()) {
            Map<String, Object> f = (Map<String, Object>) o;
            String key = (String) f.get("key"), label = (String) f.get("label"), type = (String) f.get("type");
            if (!f.get("group").equals(group)) {
                group = (String) f.get("group");
                JLabel g = Ui.bold(group);
                g.setBorder(BorderFactory.createEmptyBorder(12, 0, 2, 0));
                form.full(g);
            }
            String v = values[0].str(key);
            JComponent input;
            if (type.equals("BOOL")) {
                JCheckBox cb = new JCheckBox(label, "true".equals(v));
                cb.setOpaque(false);
                input = cb;
                form.full(cb);
            } else {
                if (type.equals("PASSWORD")) input = new JPasswordField(v, 28);
                else if (type.equals("TEXTAREA")) {
                    JTextArea a = new JTextArea(v, 3, 40);
                    a.setLineWrap(true);
                    a.setWrapStyleWord(true);
                    input = a;
                } else if (type.startsWith("SELECT:")) {
                    JComboBox<String> cb = new JComboBox<>(type.substring(7).split(","));
                    cb.setSelectedItem(v);
                    input = cb;
                } else input = new JTextField(v, 28);
                form.add(label, type.equals("TEXTAREA") ? new JScrollPane(input) : input);
            }
            if (f.get("help") != null) {
                JLabel help = Ui.sub((String) f.get("help"));
                form.full(help);
            }
            inputs.put(key, input);
        }
        form.full(Ui.sub("<html>Chỉ gửi cho khách đã đồng ý nhận thông báo khi mua hàng (Luật Bảo vệ dữ liệu cá nhân). "
                + "SMS và Zalo chỉ gửi 6h–22h; tin ngoài giờ sẽ chờ đến 6h sáng.</html>"));

        JComboBox<String> testCh = Ui.wide(new JComboBox<>(new String[]{"EMAIL", "SMS", "ZALO"}), 110);
        JTextField testTo = new JTextField(22);
        JScrollPane sp = new JScrollPane(form);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(Ui.pad(10, 16));
        java.util.function.Supplier<Map<String, Object>> collect = () -> {
            Map<String, Object> in = new LinkedHashMap<>();
            for (Map.Entry<String, JComponent> e : inputs.entrySet()) {
                JComponent c = e.getValue();
                Object val;
                if (c instanceof JCheckBox cb) val = cb.isSelected();
                else if (c instanceof JPasswordField pf) val = new String(pf.getPassword()).trim();
                else if (c instanceof JTextArea ta) val = ta.getText();
                else if (c instanceof JComboBox<?> cb) val = String.valueOf(cb.getSelectedItem());
                else val = ((JTextField) c).getText().trim();
                in.put(e.getKey(), val);
            }
            return in;
        };
        bottom.add(Ui.flow(new JLabel("Gửi thử qua"), testCh, new JLabel("tới"), testTo, Ui.button("Gửi thử", () -> {
            final String[] msg = new String[1];
            // Tự lưu những gì đang nhập trước khi gửi thử
            if (Ui.run(d, () -> {
                ctx.app().notify.saveConfig(user(), collect.get());
                msg[0] = ctx.app().notify.sendTest(user(), (String) testCh.getSelectedItem(), testTo.getText());
            })) Ui.info(d, msg[0] + "\n(Cấu hình đã được lưu.)");
            refresh();
        })), BorderLayout.WEST);
        bottom.add(Ui.flow(Ui.button("Đóng", d::dispose), Ui.primary("Lưu cấu hình", () -> {
            if (Ui.run(d, () -> ctx.app().notify.saveConfig(user(), collect.get()))) {
                refresh();
                Ui.info(d, "Đã lưu cấu hình thông báo");
            }
        })), BorderLayout.EAST);
        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(sp, BorderLayout.CENTER);
        d.getContentPane().add(bottom, BorderLayout.SOUTH);
        d.setSize(new Dimension(920, Math.min(760, owner == null ? 760 : owner.getHeight() - 40)));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
