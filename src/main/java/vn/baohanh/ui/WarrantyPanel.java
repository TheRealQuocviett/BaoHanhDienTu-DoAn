package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.WindowConstants;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/** Khối hiển thị một bảo hành (dùng ở Tra cứu và hộp thoại chi tiết). */
public final class WarrantyPanel {
    private WarrantyPanel() {}

    @SuppressWarnings("unchecked")
    public static JPanel build(Ui.Ctx ctx, Row w, Runnable afterChange) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Ui.CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.LINE), Ui.pad(14, 16)));
        boolean active = Boolean.TRUE.equals(w.get("active"));
        long days = w.lng("days_left");

        JLabel name = new JLabel(w.str("product_name"));
        name.setFont(Ui.font(Font.BOLD, 19));
        JLabel st = new JLabel(active ? (days <= 30 ? "Còn " + days + " ngày" : "Còn bảo hành") : "Hết hạn");
        st.setFont(Ui.font(Font.BOLD, 14));
        st.setForeground(active ? (days <= 30 ? Ui.WARN : Ui.OK) : Ui.BAD);
        p.add(left(Ui.flow(name, st)));
        p.add(left(Ui.sub("Serial " + w.str("serial") + " · Mã BH " + w.str("code") + " · " + w.str("brand_name") + " · " + w.str("category_name"))));

        // Thanh thời hạn
        LocalDate from = w.date("purchase_date"), to = w.date("expiry_date");
        double used = Math.min(1, Math.max(0, ChronoUnit.DAYS.between(from, LocalDate.now()) / (double) Math.max(1, ChronoUnit.DAYS.between(from, to))));
        JComponent meter = new JComponent() {
            private static final long serialVersionUID = 1L;
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Ui.SOFT);
                g.fillRoundRect(0, 4, getWidth(), 10, 10, 10);
                g.setColor(active ? Ui.INK : Ui.BAD);
                g.fillRoundRect(0, 4, (int) (getWidth() * used), 10, 10, 10);
            }
        };
        meter.setPreferredSize(new Dimension(600, 18));
        meter.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        p.add(left(meter));
        p.add(left(Ui.sub("Mua " + Fmt.date(w.str("purchase_date")) + "   ·   " + (active ? "còn " + days + " ngày" : "đã hết hạn " + (-days) + " ngày")
                + "   ·   hết hạn " + Fmt.date(w.str("expiry_date")))));

        JPanel info = new JPanel(new GridLayout(1, 4, 8, 0));
        info.setOpaque(false);
        info.add(Ui.kv("Khách hàng", w.str("customer_name")));
        info.add(Ui.kv("Điện thoại", w.str("customer_phone")));
        info.add(Ui.kv("Hóa đơn bán", w.str("invoice_no")));
        info.add(Ui.kv("Thời hạn", w.lng("months") + " tháng"));
        p.add(left(info));
        p.add(left(Ui.sub("<html>Địa chỉ khách: <b>" + Fmt.html(w.str("customer_address").isEmpty() ? "—" : w.str("customer_address")) + "</b>"
                + (w.str("customer_email").isEmpty() ? "" : " &nbsp;·&nbsp; Email: <b>" + Fmt.html(w.str("customer_email")) + "</b>") + "</html>")));

        List<Row> exts = (List<Row>) w.get("extensions");
        if (exts != null && !exts.isEmpty()) {
            Ui.RowModel em = new Ui.RowModel(List.of(Ui.Col.text("code", "Gói BH mở rộng", 120), Ui.Col.date("created_at", "Ngày mua"),
                    Ui.Col.num("months", "+ Tháng"), Ui.Col.date("old_expiry", "Hạn cũ"), Ui.Col.date("new_expiry", "Hạn mới"),
                    Ui.Col.money("price", "Giá"), Ui.Col.text("user_name", "Người bán", 130)));
            em.setRows(exts);
            p.add(left(Ui.bold("Gói bảo hành mở rộng")));
            JScrollPane es = Ui.scroll(Ui.table(em, null));
            int eh = 32 + 30 * exts.size();
            es.setPreferredSize(new Dimension(600, eh));
            es.setMaximumSize(new Dimension(Integer.MAX_VALUE, eh));
            p.add(left(es));
        }

        List<Row> ts = (List<Row>) w.get("tickets");
        Ui.RowModel m = new Ui.RowModel(List.of(Ui.Col.text("code", "Mã phiếu", 100), Ui.Col.text("issue", "Lỗi", 300),
                Ui.Col.date("received_at", "Ngày nhận"), new Ui.Col("status", "Trạng thái", Ui.Kind.STATUS, 200)));
        m.setRows(ts);
        p.add(left(Ui.bold("Lịch sử bảo hành (" + ts.size() + ") – nhấp đúp để mở phiếu")));
        JScrollPane sp = Ui.scroll(Ui.table(m, r -> { TicketDialog.open(ctx, r.lng("id")); if (afterChange != null) afterChange.run(); }));
        int h = 32 + 30 * Math.max(1, ts.size());
        sp.setPreferredSize(new Dimension(600, h));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        p.add(left(sp));

        boolean open = ts.stream().anyMatch(t -> !List.of("RETURNED", "CANCELLED").contains(t.str("status")));
        JPanel btns = Ui.flow();
        if (!open && vn.baohanh.model.Perm.RECEIVE.allows(ctx.user()))
            btns.add(Ui.primary("Tiếp nhận máy này", () -> { ReceiveDialog.open(ctx, w.str("serial")); if (afterChange != null) afterChange.run(); }));
        btns.add(Ui.button("In phiếu bảo hành điện tử", () -> Ui.print(ctx, p, "bao-hanh", w.lng("id"))));
        if (active && vn.baohanh.model.Perm.EXTEND.allows(ctx.user()))
            btns.add(Ui.button("Bán gói BH mở rộng", () -> { if (extend(ctx, p, w) && afterChange != null) afterChange.run(); }));
        if (vn.baohanh.model.Perm.DELETE_WARRANTY.allows(ctx.user()) && ts.isEmpty())
            btns.add(Ui.danger("Xóa bảo hành", () -> {
                if (!Ui.confirm(p, "Xóa bảo hành " + w.str("code") + "? Không thể hoàn tác.")) return;
                if (Ui.run(p, () -> ctx.app().warranty.deleteWarranty(ctx.user(), w.lng("id")))) {
                    Ui.info(p, "Đã xóa bảo hành");
                    if (afterChange != null) afterChange.run();
                }
            }));
        p.add(left(btns));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** Hộp thoại bán gói bảo hành mở rộng. Trả về true nếu đã bán. */
    static boolean extend(Ui.Ctx ctx, java.awt.Component parent, Row w) {
        javax.swing.JComboBox<Ui.Item> pack = new javax.swing.JComboBox<>();
        pack.addItem(new Ui.Item(6, "Gói Bạc – 6 tháng"));
        pack.addItem(new Ui.Item(12, "Gói Vàng – 12 tháng"));
        pack.addItem(new Ui.Item(24, "Gói Kim cương – 24 tháng"));
        pack.setSelectedIndex(1);
        javax.swing.JTextField price = new javax.swing.JTextField("0", 12);
        javax.swing.JComboBox<Ui.Item> method = new javax.swing.JComboBox<>();
        method.addItem(new Ui.Item("", "— Hình thức thanh toán —"));
        vn.baohanh.service.WarrantyService.PAY_METHODS.forEach((k, v) -> method.addItem(new Ui.Item(k, v)));
        javax.swing.JTextField note = new javax.swing.JTextField(24);
        javax.swing.JCheckBox print = new javax.swing.JCheckBox("In lại phiếu bảo hành", true);
        Ui.Form f = new Ui.Form().full(new JLabel("<html><b>" + Fmt.html(w.str("product_name")) + "</b> · Serial " + Fmt.html(w.str("serial"))
                + "<br>Hạn hiện tại: " + Fmt.date(w.str("expiry_date")) + "</html>"))
                .add("Gói", pack).add("Giá gói (đ)", price).add("Thanh toán", method).add("Ghi chú", note).full(print);
        if (javax.swing.JOptionPane.showConfirmDialog(parent, f, "Bán gói bảo hành mở rộng", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return false;
        java.util.Map<String, Object> in = new java.util.LinkedHashMap<>();
        in.put("months", Ui.selectedId(pack));
        in.put("price", price.getText().replace(".", "").replace(",", "").trim());
        in.put("method", Ui.selectedId(method));
        in.put("note", note.getText().isBlank() ? pack.getSelectedItem().toString() : note.getText());
        final Row[] res = new Row[1];
        if (!Ui.run(parent, () -> res[0] = ctx.app().warranty.extend(ctx.user(), w.lng("id"), in))) return false;
        Ui.info(parent, "Đã gia hạn bảo hành đến hết ngày " + Fmt.date(res[0].str("expiry_date")));
        if (print.isSelected()) Ui.print(ctx, parent, "bao-hanh", w.lng("id"));
        return true;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    /** Hộp thoại chi tiết bảo hành. */
    public static void dialog(Ui.Ctx ctx, long warrantyId, Runnable after) {
        JDialog d = new JDialog(ctx.frame(), "Chi tiết bảo hành", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Ui.run(ctx.frame(), () -> {
            Row w = ctx.app().warranty.getWarranty(ctx.user(), warrantyId);
            JPanel root = new JPanel(new BorderLayout());
            root.setBorder(Ui.pad(12, 12));
            root.setBackground(Ui.CARD);
            root.add(build(ctx, w, () -> { d.dispose(); if (after != null) after.run(); }), BorderLayout.NORTH);
            d.setContentPane(new JScrollPane(root));
            d.setSize(860, Math.min(720, root.getPreferredSize().height + 60));
            d.setLocationRelativeTo(ctx.frame());
            d.setVisible(true);
        });
    }
}
