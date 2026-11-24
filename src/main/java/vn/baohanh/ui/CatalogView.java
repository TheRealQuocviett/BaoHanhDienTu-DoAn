package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import javax.swing.WindowConstants;

import vn.baohanh.db.Row;
import vn.baohanh.model.EntityDef;
import vn.baohanh.model.EntityDef.Field;
import vn.baohanh.model.Role;
import vn.baohanh.util.Fmt;

/**
 * Màn hình danh mục dùng chung (khách hàng, hãng, TTBH, loại, sản phẩm, linh kiện, người dùng).
 * Cột và ô nhập được dựng tự động từ EntityDef – giống hệt giao diện web.
 */
public class CatalogView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final EntityDef def;
    private final Ui.RowModel model;
    private final JTable table;
    private final JTextField q;
    private final JLabel count = Ui.sub(" ");

    public CatalogView(Ui.Ctx ctx, String key) {
        super(ctx);
        this.def = ctx.app().catalog.def(key);
        List<Ui.Col> cols = new ArrayList<>();
        for (EntityDef.Column c : def.columns) {
            Ui.Kind k = c.format() == null ? Ui.Kind.TEXT : switch (c.format()) {
                case "money" -> Ui.Kind.MONEY; case "int" -> Ui.Kind.INT; case "bool", "flag" -> Ui.Kind.BOOL; default -> Ui.Kind.TEXT; };
            String title = "flag".equals(c.format()) ? "Sắp hết" : c.label();
            cols.add(new Ui.Col(c.name(), title, k, k == Ui.Kind.TEXT ? 160 : 90));
        }
        model = new Ui.RowModel(cols);
        boolean canWrite = def.canWrite(ctx.user().role());
        table = Ui.table(model, canWrite ? r -> edit(r.lng("id")) : null);
        q = Ui.search("Tìm kiếm…", s -> refresh());

        List<Component> btns = new ArrayList<>();
        if ("part".equals(key)) {
            if (ctx.user().is(Role.ADMIN, Role.TECHNICIAN)) btns.add(Ui.button("Thẻ kho", () -> ctx.frame().navigate("the-kho", null)));
            if (ctx.user().is(Role.ADMIN)) {
                btns.add(Ui.button("Nhập kho", () -> stock(true)));
                btns.add(Ui.button("Kiểm kê", () -> stock(false)));
            }
        }
        if (canWrite && List.of("customer", "product", "part").contains(key))
            btns.add(Ui.button("Nhập Excel", () -> ImportDialog.open(ctx, key, def.title, this::refresh)));
        if (canWrite) {
            btns.add(Ui.button("Sửa", () -> { Row r = Ui.selected(table, model); if (r == null) Ui.info(this, "Chọn một dòng"); else edit(r.lng("id")); }));
            btns.add(Ui.primary("+ Thêm mới", () -> edit(0)));
        }
        add(Ui.header(def.title, def.hint != null ? def.hint : (canWrite ? "Nhấp đúp vào một dòng để sửa hoặc xóa." : "Chỉ xem."),
                btns.toArray(new Component[0])), BorderLayout.NORTH);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(Ui.flow(new JLabel("Tìm:"), q, count), BorderLayout.NORTH);
        c.add(Ui.scroll(table), BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            model.setRows(ctx.app().catalog.list(user(), def.key, q.getText()));
            count.setText("   " + model.getRowCount() + " dòng");
        });
    }

    /** Hộp thoại thêm (id = 0) / sửa. */
    private void edit(long id) {
        JDialog d = new JDialog(ctx.frame(), (id == 0 ? "Thêm " : "Sửa ") + def.title.toLowerCase(), true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Map<String, JComponent> inputs = new LinkedHashMap<>();
        Ui.Form f = new Ui.Form();
        boolean ok = Ui.run(ctx.frame(), () -> {
            Row rec = id == 0 ? new Row() : ctx.app().catalog.get(user(), def.key, id);
            for (Field fd : def.fields) {
                if (fd.createOnly && id != 0) continue;
                Object v = id == 0 ? fd.defaultValue : rec.get(fd.name);
                String sv = v == null ? "" : String.valueOf(v);
                JComponent in;
                switch (fd.type) {
                    case REF -> {
                        JComboBox<Ui.Item> cb = Ui.combo(ctx.app().catalog.options(user(), fd.ref), fd.required ? "— Chọn —" : "— Không —");
                        Ui.select(cb, v);
                        in = cb;
                    }
                    case ENUM -> {
                        JComboBox<Ui.Item> cb = new JComboBox<>();
                        fd.options.forEach((k, l) -> cb.addItem(new Ui.Item(k, l)));
                        Ui.select(cb, v);
                        in = cb;
                    }
                    case BOOL -> {
                        JCheckBox cb = new JCheckBox(fd.label, id == 0 ? "true".equals(fd.defaultValue) : rec.bool(fd.name));
                        cb.setOpaque(false);
                        in = cb;
                    }
                    case PASSWORD -> in = new JPasswordField(22);
                    case TEXTAREA -> {
                        JTextArea ta = new JTextArea(sv, 3, 28);
                        ta.setLineWrap(true);
                        ta.setWrapStyleWord(true);
                        in = new JScrollPane(ta);
                    }
                    default -> in = new JTextField(sv, 28);
                }
                inputs.put(fd.name, in);
                String label = fd.type == EntityDef.Type.PASSWORD && id == 0 ? "Mật khẩu *" : fd.label + (fd.required ? " *" : "");
                if (fd.type == EntityDef.Type.BOOL) f.full(in); else f.add(label, in);
            }
        });
        if (!ok) return;

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Ui.CARD);
        root.setBorder(Ui.pad(16, 20));
        root.add(Ui.title((id == 0 ? "Thêm " : "Sửa ") + def.title.toLowerCase()), BorderLayout.NORTH);
        root.add(f, BorderLayout.CENTER);
        JPanel btns = Ui.flow(Ui.primary("Lưu", () -> {
            Map<String, Object> in = new LinkedHashMap<>();
            inputs.forEach((k, c) -> in.put(k, value(c)));
            if (Ui.run(d, () -> ctx.app().catalog.save(user(), def.key, id, in))) {
                d.dispose();
                refresh();
                ctx.frame().refreshBadges();
            }
        }), Ui.button("Hủy", d::dispose));
        if (id != 0 && def.canDelete(user().role()))
            btns.add(Ui.danger("Xóa", () -> {
                if (!Ui.confirm(d, "Xóa bản ghi này? Không thể hoàn tác.")) return;
                if (Ui.run(d, () -> ctx.app().catalog.delete(user(), def.key, id))) { d.dispose(); refresh(); }
            }));
        root.add(btns, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(ctx.frame());
        d.setVisible(true);
    }

    private static Object value(JComponent c) {
        if (c instanceof JComboBox<?> cb) { Object it = cb.getSelectedItem(); return it instanceof Ui.Item i ? i.id() : null; }
        if (c instanceof JCheckBox cb) return cb.isSelected();
        if (c instanceof JPasswordField pf) return new String(pf.getPassword());
        if (c instanceof JScrollPane sp && sp.getViewport().getView() instanceof JTextArea ta) return ta.getText();
        if (c instanceof JTextField tf) return tf.getText();
        return null;
    }

    /** Nhập kho / kiểm kê linh kiện đang chọn. */
    private void stock(boolean in) {
        Row p = Ui.selected(table, model);
        if (p == null) { Ui.info(this, "Chọn một linh kiện trong bảng trước"); return; }
        JTextField a = new JTextField(10), b = new JTextField(10), note = new JTextField(24);
        Ui.Form f = new Ui.Form();
        f.full(new JLabel("<html><b>" + Fmt.html(p.str("code") + " – " + p.str("name")) + "</b><br>Tồn hiện tại: " + p.lng("stock") + " " + Fmt.html(p.str("unit")) + "</html>"));
        if (in) f.add("Số lượng nhập *", a).add("Giá nhập mới (để trống = giữ)", b).add("Ghi chú / nhà cung cấp", note);
        else f.add("Số tồn thực tế *", a).add("Lý do chênh lệch *", note);
        if (javax.swing.JOptionPane.showConfirmDialog(this, f, in ? "Nhập kho" : "Kiểm kê", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return;
        if (Ui.run(this, () -> {
            long n = parse(a.getText(), "Số lượng");
            if (in) ctx.app().inventory.stockIn(user(), p.lng("id"), n, b.getText().isBlank() ? 0 : parse(b.getText(), "Giá nhập"), note.getText());
            else ctx.app().inventory.adjust(user(), p.lng("id"), n, note.getText());
        })) {
            refresh();
            ctx.frame().refreshBadges();
        }
    }

    private static long parse(String s, String label) {
        try { return Long.parseLong(s.replace(".", "").replace(",", "").trim()); }
        catch (NumberFormatException e) { throw new vn.baohanh.service.AppException(label + " phải là số"); }
    }
}
