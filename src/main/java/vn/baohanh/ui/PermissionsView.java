package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/** Phân quyền chi tiết: chọn tài khoản bên trái, bật/tắt từng chức năng bên phải. */
public class PermissionsView extends MainFrame.View {
    private static final long serialVersionUID = 1L;

    private final DefaultListModel<Row> users = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(users);
    private final JPanel boxes = new JPanel();
    private final JLabel who = Ui.bold(" ");
    private final Map<String, JCheckBox> checks = new LinkedHashMap<>();
    private List<Row> perms = List.of();
    private Row current;

    public PermissionsView(Ui.Ctx ctx) {
        super(ctx);
        add(Ui.header("Phân quyền chi tiết", "Bật / tắt từng chức năng cho từng tài khoản. Quản lý luôn có mọi quyền. Thay đổi có hiệu lực ngay, không cần đăng nhập lại.",
                Ui.button("Về mặc định của vai trò", this::reset), Ui.primary("Lưu thay đổi", this::save)), BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(46);
        list.setCellRenderer((l, r, i, sel, focus) -> {
            JLabel x = new JLabel("<html><b>" + Fmt.html(r.str("full_name")) + "</b>" + (r.bool("active") ? "" : " <i>(đã khóa)</i>") + "<br><span style='color:"
                    + Ui.hex(sel ? Ui.CARD : Ui.MUTE) + "'>" + Fmt.html(r.str("role_label"))
                    + (r.lng("custom_count") > 0 ? " · " + r.lng("custom_count") + " quyền riêng" : "") + "</span></html>");
            x.setOpaque(true);
            x.setBorder(Ui.pad(4, 10));
            x.setBackground(sel ? Ui.ACCENT : Ui.CARD);
            x.setForeground(sel ? java.awt.Color.WHITE : Ui.INK);
            return x;
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) show(list.getSelectedValue()); });
        JScrollPane ls = Ui.scroll(list);
        ls.setPreferredSize(new Dimension(250, 10));
        boxes.setLayout(new BoxLayout(boxes, BoxLayout.Y_AXIS));
        boxes.setBackground(Ui.CARD);
        boxes.setBorder(Ui.pad(10, 14));
        JScrollPane bs = Ui.scroll(boxes);
        bs.getVerticalScrollBar().setUnitIncrement(18);
        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.setOpaque(false);
        right.add(who, BorderLayout.NORTH);
        right.add(bs, BorderLayout.CENTER);
        JPanel c = new JPanel(new BorderLayout(14, 0));
        c.setOpaque(false);
        c.add(ls, BorderLayout.WEST);
        c.add(right, BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refresh() {
        Ui.run(this, () -> {
            Row m = ctx.app().permissions.matrix(user());
            perms = (List<Row>) m.get("perms");
            long keep = current == null ? -1 : current.lng("id");
            users.clear();
            for (Row u : (List<Row>) m.get("users")) users.addElement(u);
            int idx = 0;
            for (int i = 0; i < users.size(); i++) if (users.get(i).lng("id") == keep) idx = i;
            if (!users.isEmpty()) list.setSelectedIndex(idx);
            else show(null);
        });
    }

    @SuppressWarnings("unchecked")
    private void show(Row u) {
        current = u;
        boxes.removeAll();
        checks.clear();
        if (u == null) {
            who.setText("Chưa có tài khoản Tiếp nhận / Kỹ thuật viên nào.");
            boxes.revalidate();
            boxes.repaint();
            return;
        }
        who.setText(u.str("full_name") + " – " + u.str("role_label") + " (" + u.str("username") + ")");
        Map<String, Row> p = (Map<String, Row>) u.get("perms");
        boolean tech = "TECHNICIAN".equals(u.str("role"));
        String group = "";
        for (Row d : perms) {
            if (!d.str("group").equals(group)) {
                group = d.str("group");
                JLabel g = Ui.bold(group.toUpperCase());
                g.setForeground(Ui.MUTE);
                g.setFont(Ui.font(Font.BOLD, 12));
                g.setBorder(BorderFactory.createEmptyBorder(group.isEmpty() ? 0 : 12, 0, 4, 0));
                g.setAlignmentX(0);
                boxes.add(g);
            }
            Row cur = p.get(d.str("key"));
            boolean def = d.bool(tech ? "technician" : "reception");
            JCheckBox cb = new JCheckBox("<html><b>" + Fmt.html(d.str("label")) + "</b>"
                    + (cur.bool("custom") ? " <span style='color:" + Ui.hex(Ui.WARN) + "'>● đã chỉnh riêng</span>" : "")
                    + "<br><span style='color:" + Ui.hex(Ui.MUTE) + "'>" + Fmt.html(d.str("hint")) + " · Mặc định của vai trò: " + (def ? "có" : "không") + "</span></html>",
                    cur.bool("allowed"));
            cb.setOpaque(false);
            cb.setForeground(Ui.INK);
            cb.setAlignmentX(0);
            cb.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
            checks.put(d.str("key"), cb);
            boxes.add(cb);
        }
        boxes.revalidate();
        boxes.repaint();
    }

    private void save() {
        if (current == null) return;
        Map<String, Object> in = new LinkedHashMap<>();
        checks.forEach((k, cb) -> in.put(k, cb.isSelected()));
        if (Ui.run(this, () -> ctx.app().permissions.save(user(), current.lng("id"), in))) {
            Ui.info(this, "Đã lưu quyền cho " + current.str("full_name") + ".");
            refresh();
        }
    }

    private void reset() {
        if (current == null || !Ui.confirm(this, "Đưa " + current.str("full_name") + " về quyền mặc định của vai trò " + current.str("role_label") + "?")) return;
        if (Ui.run(this, () -> ctx.app().permissions.reset(user(), current.lng("id")))) refresh();
    }

    static List<String> keys(List<Row> r) {
        List<String> out = new ArrayList<>();
        for (Row x : r) out.add(x.str("key"));
        return out;
    }
}
