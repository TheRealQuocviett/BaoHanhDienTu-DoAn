package vn.baohanh.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import vn.baohanh.db.Row;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.service.WarrantyService;
import vn.baohanh.util.Fmt;

/**
 * Hộp thoại xử lý phiếu: thông tin, chi phí (linh kiện + tiền công), báo giá,
 * cập nhật tiến độ, phân công, thu tiền & trả máy, in phiếu, lịch sử.
 * Nút nào hiện là do tầng nghiệp vụ trả về trong "actions" (giống giao diện web).
 */
public class TicketDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final Ui.Ctx ctx;
    private final long id;
    private final JPanel body = new JPanel();

    public static void open(Ui.Ctx ctx, long id) {
        TicketDialog d = new TicketDialog(ctx, id);
        if (d.reload()) d.setVisible(true);
    }

    private TicketDialog(Ui.Ctx ctx, long id) {
        super(ctx.frame(), "Phiếu bảo hành", true);
        this.ctx = ctx;
        this.id = id;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Ui.CARD);
        body.setBorder(Ui.pad(16, 20));
        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        getContentPane().add(sp);
        setSize(940, 780);
        setLocationRelativeTo(ctx.frame());
    }

    boolean reload() {
        return Ui.run(ctx.frame(), () -> build(ctx.app().warranty.getTicket(ctx.user(), id)));
    }

    /** Thực hiện thao tác rồi nạp lại phiếu + trang đang mở. */
    private void act(Ui.Action a, String done) {
        if (Ui.run(this, a)) {
            reload();
            ctx.frame().refreshCurrent();
            if (done != null) Ui.info(this, done);
        }
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Ui.CARD);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.LINE), Ui.pad(10, 12))));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel t = Ui.bold(title);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(t);
        p.add(Box.createVerticalStrut(6));
        return p;
    }

    @SuppressWarnings("unchecked")
    private void build(Row t) throws Exception {
        body.removeAll();
        setTitle("Phiếu bảo hành " + t.str("code"));
        Map<String, Object> a = (Map<String, Object>) t.get("actions");
        Row w = (Row) t.get("warranty");
        boolean inW = Boolean.TRUE.equals(t.get("in_warranty"));

        // ---------- Tiêu đề ----------
        JLabel code = new JLabel(t.str("code"));
        code.setFont(Ui.font(Font.BOLD, 26));
        JLabel st = new JLabel(t.str("status_label") + (Boolean.TRUE.equals(t.get("overdue")) ? "  ·  QUÁ HẸN" : ""));
        st.setFont(Ui.font(Font.BOLD, 15));
        st.setForeground(switch (t.str("status")) { case "READY" -> Ui.ACCENT; case "RETURNED" -> Ui.OK;
            case "REJECTED", "CANCELLED" -> Ui.BAD; default -> Ui.INK; });
        JLabel wl = new JLabel(inW ? "Trong bảo hành" : "Ngoài bảo hành – tính phí  ·  " + t.str("quote_label"));
        wl.setForeground(inW ? Ui.OK : Ui.WARN);
        body.add(left(Ui.flow(code, st, new JLabel("  "), wl)));

        // ---------- Thông tin ----------
        JPanel info = new JPanel(new GridLayout(0, 4, 8, 4));
        info.setOpaque(false);
        info.add(Ui.kv("Sản phẩm", t.str("product_name")));
        info.add(Ui.kv("Serial / IMEI", t.str("serial")));
        info.add(Ui.kv("Khách hàng", t.str("customer_name")));
        info.add(Ui.kv("Điện thoại", t.str("customer_phone")));
        info.add(Ui.kv("Ngày nhận", Fmt.dateTime(t.str("received_at"))));
        info.add(Ui.kv("Hẹn trả", Fmt.date(t.str("due_date"))));
        info.add(Ui.kv("Hạn bảo hành", Fmt.date(w.str("expiry_date"))));
        info.add(Ui.kv("Kỹ thuật viên", t.str("technician_name")));
        info.add(Ui.kv("Nhân viên nhận", t.str("received_by_name")));
        info.add(Ui.kv("Phụ kiện", t.str("accessories")));
        info.add(Ui.kv("Ngoại quan", t.str("condition_note")));
        info.add(Ui.kv("Gửi hãng", t.str("service_center_name")));
        if (!t.str("invoice_no").isEmpty()) info.add(Ui.kv("Hóa đơn sửa chữa", t.str("invoice_no")));
        body.add(left(info));
        body.add(left(new JLabel("<html><span style='color:" + Ui.hex(Ui.MUTE) + "'>Địa chỉ khách:</span> "
                + Fmt.html(t.str("customer_address").isEmpty() ? "—" : t.str("customer_address"))
                + (t.str("customer_email").isEmpty() ? "" : " &nbsp;·&nbsp; <span style='color:" + Ui.hex(Ui.MUTE) + "'>Email:</span> " + Fmt.html(t.str("customer_email")))
                + "<br><span style='color:" + Ui.hex(Ui.MUTE) + "'>Lỗi khách báo:</span> " + Fmt.html(t.str("issue"))
                + (t.str("diagnosis").isEmpty() ? "" : "<br><span style='color:" + Ui.hex(Ui.MUTE) + "'>Kết luận kỹ thuật:</span> " + Fmt.html(t.str("diagnosis")))
                + (t.get("review") instanceof Row rv ? "<br><span style='color:" + Ui.hex(Ui.MUTE) + "'>Khách đánh giá:</span> <b style='color:" + Ui.hex(Ui.WARN) + "'>" + rv.lng("stars") + "/5 sao</b> "
                        + Fmt.html(rv.str("comment")) : "")
                + "</html>")));

        // ---------- Gợi ý chẩn đoán ----------
        if (vn.baohanh.model.Perm.REPAIR.allows(ctx.user()) && !List.of("RETURNED", "CANCELLED").contains(t.str("status"))) {
            javax.swing.JLabel dx = DiagnosisPanel.create();
            dx.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(Box.createVerticalStrut(8));
            body.add(dx);
            long tid = t.lng("id");
            new javax.swing.SwingWorker<Row, Void>() {
                @Override protected Row doInBackground() throws Exception {
                    return ctx.app().diagnosis.suggest(ctx.user(), java.util.Map.of("ticket_id", tid));
                }
                @Override protected void done() {
                    try { DiagnosisPanel.show(dx, get(), 640); body.revalidate(); } catch (Exception ignored) { }
                }
            }.execute();
        }

        // ---------- Chi phí ----------
        JPanel cost = section("Chi phí: " + Fmt.money(t.lng("total")));
        Ui.RowModel pm = new Ui.RowModel(List.of(Ui.Col.text("code", "Mã", 110), Ui.Col.text("name", "Linh kiện", 260),
                Ui.Col.num("qty", "SL"), Ui.Col.money("unit_price", "Đơn giá"), Ui.Col.money("amount", "Thành tiền"),
                new Ui.Col("charged", "Tính phí", Ui.Kind.BOOL, 70)));
        pm.setRows((List<Row>) t.get("parts"));
        JTable pt = Ui.table(pm, null);
        JScrollPane ps = Ui.scroll(pt);
        ps.setPreferredSize(new Dimension(800, 30 + 30 * Math.max(1, pm.getRowCount())));
        ps.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30 + 30 * Math.max(1, pm.getRowCount())));
        cost.add(left(ps));
        cost.add(left(Ui.sub("Tiền công: " + Fmt.money(t.lng("labor_cost")) + "   ·   Linh kiện tính phí: " + Fmt.money(t.lng("parts_charged"))
                + "   ·   Linh kiện miễn phí BH: " + Fmt.money(t.lng("parts_free")))));
        if (Boolean.TRUE.equals(a.get("edit_cost"))) {
            JComboBox<Ui.Item> partBox = Ui.combo(ctx.app().catalog.options(ctx.user(), "part"), "— Chọn linh kiện —");
            JSpinner qty = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
            cost.add(left(Ui.flow(partBox, new JLabel("SL"), qty,
                    Ui.button("Thêm linh kiện", () -> act(() -> {
                        Object pid = Ui.selectedId(partBox);
                        if (pid == null) throw new vn.baohanh.service.AppException("Vui lòng chọn linh kiện");
                        ctx.app().warranty.addPart(ctx.user(), id, ((Number) pid).longValue(), ((Number) qty.getValue()).longValue());
                    }, null)),
                    Ui.button("Bỏ linh kiện đã chọn", () -> {
                        Row sel = Ui.selected(pt, pm);
                        if (sel == null) { Ui.info(this, "Chọn một dòng linh kiện trong bảng"); return; }
                        act(() -> ctx.app().warranty.removePart(ctx.user(), id, sel.lng("id")), null);
                    }))));
            JTextField labor = new JTextField(String.valueOf(t.lng("labor_cost")), 10);
            labor.setEditable(!inW);
            JTextField diag = new JTextField(t.str("diagnosis"), 30);
            cost.add(left(Ui.flow(new JLabel("Tiền công (đ)"), labor, new JLabel("Kết luận"), diag,
                    Ui.button("Lưu chi phí", () -> act(() -> {
                        Map<String, Object> in = new LinkedHashMap<>();
                        in.put("labor_cost", labor.getText().replace(".", "").trim());
                        in.put("diagnosis", diag.getText());
                        ctx.app().warranty.updateCost(ctx.user(), id, in);
                    }, null)))));
        }
        if (Boolean.TRUE.equals(a.get("send_quote")))
            cost.add(left(Ui.flow(Ui.primary("Gửi báo giá cho khách", () -> act(() -> ctx.app().warranty.sendQuote(ctx.user(), id), "Đã gửi báo giá")))));
        if (Boolean.TRUE.equals(a.get("decide_quote"))) {
            JTextField note = new JTextField(20);
            cost.add(left(Ui.flow(new JLabel("Khách quyết định báo giá " + Fmt.money(t.lng("total")) + ":"),
                    Ui.primary("Khách đồng ý", () -> act(() -> ctx.app().warranty.decideQuote(ctx.user(), id, true, note.getText()), null)),
                    Ui.danger("Khách từ chối", () -> act(() -> ctx.app().warranty.decideQuote(ctx.user(), id, false, note.getText()), null)),
                    new JLabel("Ghi chú"), note)));
        }
        body.add(cost);

        // ---------- Cập nhật tiến độ ----------
        List<Object> next = (List<Object>) a.get("next");
        if (!next.isEmpty()) {
            JPanel sp = section("Cập nhật tiến độ");
            JComboBox<Ui.Item> to = new JComboBox<>();
            for (Object s : next) to.addItem(new Ui.Item(s, TicketStatus.valueOf((String) s).getLabel()));
            Ui.wide(to, 190);
            JComboBox<Ui.Item> center = Ui.combo(ctx.app().catalog.options(ctx.user(), "service_center"), "— Trung tâm bảo hành hãng —");
            JTextField due = new JTextField(9);
            due.setToolTipText("Đổi ngày hẹn trả (dd/MM/yyyy), để trống nếu giữ nguyên");
            JTextField note = new JTextField(34);
            Runnable sync = () -> center.setEnabled("SENT_TO_VENDOR".equals(Ui.selectedId(to)));
            to.addActionListener(e -> sync.run());
            sync.run();
            sp.add(left(Ui.flow(new JLabel("Chuyển sang"), to, center, new JLabel("Hẹn trả mới"), due)));
            sp.add(left(Ui.flow(new JLabel("Ghi chú / lý do"), note, Ui.primary("Lưu cập nhật", () -> act(() -> {
                Map<String, Object> in = new LinkedHashMap<>();
                in.put("status", Ui.selectedId(to));
                in.put("note", note.getText());
                in.put("due_date", Ui.toIso(due.getText(), "Ngày hẹn trả"));
                Object sc = Ui.selectedId(center);
                if (sc != null) in.put("service_center_id", sc);
                ctx.app().warranty.changeStatus(ctx.user(), id, in);
            }, null)))));
            body.add(sp);
        }

        // ---------- Phân công ----------
        if (Boolean.TRUE.equals(a.get("assign"))) {
            JPanel sp = section("Phân công kỹ thuật viên");
            JComboBox<Ui.Item> tech = Ui.combo(ctx.app().catalog.technicians(ctx.user()), "— Chọn —");
            Ui.select(tech, t.get("technician_id"));
            sp.add(left(Ui.flow(tech, Ui.button("Phân công", () -> act(() -> {
                Object tid = Ui.selectedId(tech);
                if (tid == null) throw new vn.baohanh.service.AppException("Vui lòng chọn kỹ thuật viên");
                ctx.app().warranty.assign(ctx.user(), id, ((Number) tid).longValue());
            }, null)))));
            body.add(sp);
        }

        // ---------- Thu tiền & trả máy ----------
        if (Boolean.TRUE.equals(a.get("pay"))) {
            JPanel sp = section("Thu tiền & trả máy – khách phải trả " + Fmt.money(t.lng("total")));
            JComboBox<Ui.Item> method = new JComboBox<>();
            method.addItem(new Ui.Item("", "— Hình thức thanh toán —"));
            WarrantyService.PAY_METHODS.forEach((k, v) -> method.addItem(new Ui.Item(k, v)));
            method.setEnabled(t.lng("total") > 0);
            if (t.get("payment_claim") instanceof Row pc) {
                for (int i = 0; i < method.getItemCount(); i++)
                    if (pc.str("method").equals(String.valueOf(method.getItemAt(i).id()))) method.setSelectedIndex(i);
            }
            Ui.wide(method, 230);
            JTextField note = new JTextField(24);
            sp.add(left(Ui.flow(method, new JLabel("Ghi chú"), note, Ui.primary(t.lng("total") > 0 ? "Thu tiền & trả máy" : "Trả máy cho khách", () -> {
                Map<String, Object> in = new LinkedHashMap<>();
                in.put("method", Ui.selectedId(method));
                in.put("note", note.getText());
                if (Ui.run(this, () -> ctx.app().warranty.pay(ctx.user(), id, in))) {
                    reload();
                    ctx.frame().refreshCurrent();
                    if (Ui.confirm(this, "Đã trả máy cho khách. In phiếu trả máy / hóa đơn?")) Ui.print(ctx, this, "tra-may", id);
                }
            }))));
            if (t.get("payment_claim") instanceof Row pc)
                sp.add(left(new JLabel("<html><span style='color:" + Ui.hex(Ui.ACCENT_TEXT) + "'>Khách báo (" + Fmt.dateTime(pc.str("time")) + "): " + Fmt.html(pc.str("text")) + "</span></html>")));
            if (t.lng("total") > 0) {
                final Row[] q = new Row[1];
                Ui.run(this, () -> q[0] = ctx.app().warranty.vietQr(id));
                if (q[0] != null && Boolean.TRUE.equals(q[0].get("available"))) {
                    JLabel qInfo = new JLabel("<html><b>Khách chuyển khoản: quét mã VietQR</b><br>" + Fmt.html(q[0].str("bank")) + " · STK <b>"
                            + Fmt.html(q[0].str("account")) + "</b>" + (q[0].str("holder").isEmpty() ? "" : " · " + Fmt.html(q[0].str("holder")))
                            + "<br>Số tiền <b>" + Fmt.money(q[0].lng("amount")) + "</b> · Nội dung <b>" + Fmt.html(q[0].str("info"))
                            + "</b><br><span style='color:" + Ui.hex(Ui.MUTE) + "'>Nhận được tiền thì chọn \"Chuyển khoản\" rồi bấm Thu tiền.</span></html>");
                    sp.add(Box.createVerticalStrut(6));
                    sp.add(left(Ui.flow(Ui.qr(q[0].str("payload"), 150), qInfo)));
                } else if (q[0] != null) {
                    sp.add(left(Ui.sub("VietQR: " + q[0].str("reason"))));
                }
            }
            body.add(sp);
        }

        // ---------- Trao đổi với khách ----------
        JPanel chat = section("Trao đổi với khách (khách nhắn từ cổng tra cứu)");
        javax.swing.JEditorPane thread = new javax.swing.JEditorPane("text/html", "");
        thread.setEditable(false);
        thread.setBackground(Ui.SOFT);
        thread.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        thread.setFont(Ui.font(java.awt.Font.PLAIN, 13));
        JScrollPane tsp = new JScrollPane(thread);
        tsp.setPreferredSize(new java.awt.Dimension(820, 200));
        tsp.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 220));
        tsp.setAlignmentX(Component.LEFT_ALIGNMENT);
        tsp.setBorder(BorderFactory.createLineBorder(Ui.LINE));
        String customer = t.str("customer_name");
        Runnable[] drawChat = new Runnable[1];
        java.util.function.Consumer<java.util.List<Row>> draw = list -> {
            StringBuilder hb = new StringBuilder("<html><body style='font-family:sans-serif;padding:4px'>");
            if (list.isEmpty()) hb.append("<p style='color:" + Ui.hex(Ui.MUTE) + "'>Chưa có tin nhắn. Bạn có thể nhắn trước cho khách.</p>");
            for (Row m : list) {
                boolean staff = "STAFF".equals(m.str("sender"));
                hb.append("<table cellpadding='0' cellspacing='0'><tr>").append(staff ? "<td width='60'></td>" : "").append("<td>")
                  .append("<table cellpadding='7' cellspacing='0' bgcolor='").append(staff ? Ui.hex(Ui.ACCENT) : Ui.hex(Ui.isDark() ? Ui.SOFT : new java.awt.Color(0xE2E8F0))).append("'><tr><td style='color:")
                  .append(staff ? "#ffffff" : Ui.hex(Ui.INK)).append("'>").append(Fmt.html(m.str("body")).replace("\n", "<br>"))
                  .append("<br><span style='font-size:9px;color:").append(staff ? "#dbe6ff" : Ui.hex(Ui.MUTE)).append("'>")
                  .append(Fmt.html(staff ? (m.str("staff_name").isEmpty() ? "Cửa hàng" : m.str("staff_name")) : customer))
                  .append(" · ").append(Fmt.dateTime(m.str("created_at"))).append("</span></td></tr></table></td></tr></table><br>");
            }
            thread.setText(hb.append("</body></html>").toString());
            SwingUtilities.invokeLater(() -> tsp.getVerticalScrollBar().setValue(tsp.getVerticalScrollBar().getMaximum()));
        };
        drawChat[0] = () -> {
            final java.util.List<Row>[] l = new java.util.List[1];
            if (Ui.run(this, () -> l[0] = ctx.app().messages.staffThread(ctx.user(), id))) { draw.accept(l[0]); ctx.frame().refreshBadges(); }
        };
        drawChat[0].run();
        chat.add(tsp);
        chat.add(Box.createVerticalStrut(6));
        javax.swing.JTextArea reply = new javax.swing.JTextArea(2, 50);
        reply.setLineWrap(true);
        reply.setWrapStyleWord(true);
        reply.setFont(Ui.font(java.awt.Font.PLAIN, 14));
        JScrollPane rsp = new JScrollPane(reply);
        rsp.setPreferredSize(new java.awt.Dimension(640, 52));
        chat.add(left(Ui.flow(rsp, Ui.primary("Gửi cho khách", () -> {
            final java.util.List<Row>[] l = new java.util.List[1];
            if (Ui.run(this, () -> l[0] = ctx.app().messages.staffSend(ctx.user(), id, reply.getText()))) {
                reply.setText("");
                draw.accept(l[0]);
            }
        }))));
        body.add(chat);

        // ---------- Ảnh tình trạng máy ----------
        body.add(PhotoPanel.build(ctx, this, id, Boolean.TRUE.equals(a.get("photos")), this::reload));

        // ---------- Hủy phiếu ----------
        if (Boolean.TRUE.equals(a.get("cancel"))) {
            JPanel cp = section("Hủy phiếu");
            JTextField reason = new JTextField(36);
            cp.add(left(Ui.flow(new JLabel("Lý do"), reason, Ui.danger("Hủy phiếu", () -> {
                if (reason.getText().isBlank()) { Ui.info(this, "Vui lòng ghi lý do hủy phiếu"); return; }
                if (!Ui.confirm(this, "Hủy phiếu " + t.str("code") + "? Không thể hoàn tác.")) return;
                act(() -> {
                    Map<String, Object> in = new LinkedHashMap<>();
                    in.put("status", "CANCELLED");
                    in.put("note", reason.getText());
                    ctx.app().warranty.changeStatus(ctx.user(), id, in);
                }, "Đã hủy phiếu");
            }))));
            body.add(cp);
        }

        // ---------- Lịch sử ----------
        JPanel hp = section("Tiến trình xử lý");
        Ui.RowModel hm = new Ui.RowModel(List.of(Ui.Col.dateTime("time", "Thời gian"), Ui.Col.text("status_label", "Trạng thái", 140),
                Ui.Col.text("user_name", "Người thực hiện", 140), Ui.Col.text("note", "Ghi chú", 400)));
        hm.setRows((List<Row>) t.get("history"));
        JScrollPane hs = Ui.scroll(Ui.table(hm, null));
        hs.setPreferredSize(new Dimension(800, 34 + 30 * hm.getRowCount()));
        hs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34 + 30 * hm.getRowCount()));
        hp.add(left(hs));
        body.add(hp);

        // ---------- In ----------
        JButton p1 = Ui.button("In phiếu tiếp nhận", () -> Ui.print(ctx, this, "tiep-nhan", id));
        JButton p2 = Ui.button("In phiếu trả máy / hóa đơn", () -> Ui.print(ctx, this, "tra-may", id));
        p2.setEnabled(List.of("READY", "REJECTED", "RETURNED").contains(t.str("status")));
        JPanel actions = Ui.flow(p1, p2, Ui.button("Đóng", this::dispose));
        actions.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        body.add(left(actions));
        body.add(Box.createVerticalGlue());
        body.revalidate();
        body.repaint();
    }
}
