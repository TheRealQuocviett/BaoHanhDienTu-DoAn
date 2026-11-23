package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.service.CustomerService;
import vn.baohanh.util.Fmt;

/** Yêu cầu bảo hành khách gửi từ cổng khách hàng: hẹn lịch, tiếp nhận thành phiếu, từ chối. */
public class RequestsView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final Ui.RowModel model = new Ui.RowModel(List.of(
            Ui.Col.text("code", "Mã", 100), Ui.Col.dateTime("created_at", "Gửi lúc"), Ui.Col.text("customer_name", "Khách hàng", 140),
            Ui.Col.text("customer_phone", "Điện thoại", 110), Ui.Col.text("product_name", "Sản phẩm", 170), Ui.Col.text("issue", "Lỗi khách mô tả", 240),
            Ui.Col.date("preferred_date", "Khách muốn"), Ui.Col.date("appointment", "Hẹn"), Ui.Col.text("status_label", "Trạng thái", 150),
            Ui.Col.text("ticket_code", "Phiếu", 95)));
    private final JComboBox<Ui.Item> filter = new JComboBox<>();
    private final JTable table;

    public RequestsView(Ui.Ctx ctx) {
        super(ctx);
        filter.addItem(new Ui.Item("OPEN", "Cần xử lý"));
        filter.addItem(new Ui.Item("", "Tất cả"));
        CustomerService.REQUEST_LABELS.forEach((k, v) -> filter.addItem(new Ui.Item(k, v)));
        filter.addActionListener(e -> refresh());
        table = Ui.table(model, this::handle);
        boolean office = ctx.user().is(Role.ADMIN, Role.RECEPTION);
        add(Ui.header("Yêu cầu bảo hành online", "Khách gửi từ cổng khách hàng. Hẹn lịch → khách mang máy tới thì bấm Tiếp nhận để tạo phiếu.",
                office ? Ui.primary("Xử lý yêu cầu đã chọn", () -> { Row r = Ui.selected(table, model); if (r == null) Ui.info(this, "Chọn một yêu cầu"); else handle(r); })
                       : new JLabel()), BorderLayout.NORTH);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(Ui.flow(new JLabel("Lọc:"), filter), BorderLayout.NORTH);
        c.add(Ui.scroll(table), BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    @Override
    public void applyFilter(String f) { Ui.select(filter, f); }

    @Override
    public void refresh() {
        Ui.run(this, () -> model.setRows(ctx.app().requests.list(user(), String.valueOf(Ui.selectedId(filter)))));
    }

    private void handle(Row r) {
        if (!r.isNull("ticket_id")) { TicketDialog.open(ctx, r.lng("ticket_id")); return; }
        if (!List.of("NEW", "CONFIRMED").contains(r.str("status")) || !user().is(Role.ADMIN, Role.RECEPTION)) {
            Ui.info(this, r.str("code") + ": " + r.str("status_label") + (r.str("staff_note").isEmpty() ? "" : "\n" + r.str("staff_note")));
            return;
        }
        String head = "<html><b>" + Fmt.html(r.str("code") + " – " + r.str("product_name")) + "</b><br>" + Fmt.html(r.str("customer_name") + " · "
                + r.str("customer_phone") + " · Serial " + r.str("serial")) + (Boolean.TRUE.equals(r.get("active")) ? "" : " · <b>hết bảo hành</b>")
                + "<br><i>" + Fmt.html(r.str("issue")) + "</i></html>";
        Object[] opts = {"Hẹn lịch", "Tiếp nhận máy (tạo phiếu)", "Từ chối", "Đóng"};
        Object msg = head;
        List<?> photos = (List<?>) r.get("photos");
        if (photos != null && !photos.isEmpty()) {
            JPanel strip = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
            strip.setOpaque(false);
            for (Object o : photos) {
                long pid = ((Row) o).lng("id");
                try {
                    Object[] img = ctx.app().photos.readRequestPhoto(0, pid);
                    java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream((byte[]) img[1]));
                    if (bi == null) continue;
                    double k = Math.min(120.0 / bi.getWidth(), 90.0 / bi.getHeight());
                    JLabel pic = new JLabel(new javax.swing.ImageIcon(bi.getScaledInstance((int) (bi.getWidth() * k), (int) (bi.getHeight() * k), java.awt.Image.SCALE_SMOOTH)));
                    pic.setBorder(BorderFactory.createLineBorder(Ui.LINE));
                    pic.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                    pic.setToolTipText("Bấm để xem ảnh lớn");
                    pic.addMouseListener(new java.awt.event.MouseAdapter() {
                        @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                            double z = Math.min(1.0, Math.min(900.0 / bi.getWidth(), 640.0 / bi.getHeight()));
                            JOptionPane.showMessageDialog(RequestsView.this, new JLabel(new javax.swing.ImageIcon(bi.getScaledInstance(
                                    (int) (bi.getWidth() * z), (int) (bi.getHeight() * z), java.awt.Image.SCALE_SMOOTH))), "Ảnh khách gửi", JOptionPane.PLAIN_MESSAGE);
                        }
                    });
                    strip.add(pic);
                } catch (Exception ignored) {
                    // bỏ qua ảnh lỗi
                }
            }
            msg = new Object[]{head, new JLabel("Ảnh khách gửi (" + photos.size() + ") – sẽ tự chép vào phiếu khi tiếp nhận:"), strip};
        }
        int c = JOptionPane.showOptionDialog(this, msg, "Xử lý yêu cầu", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
        if (c == 0) schedule(r);
        else if (c == 1) convert(r);
        else if (c == 2) reject(r);
    }

    private void schedule(Row r) {
        String def = !r.str("appointment").isEmpty() ? r.str("appointment") : !r.str("preferred_date").isEmpty() ? r.str("preferred_date") : LocalDate.now().plusDays(1).toString();
        JTextField date = new JTextField(Ui.vnDate(LocalDate.parse(def)), 10);
        JTextField note = new JTextField(r.str("staff_note"), 26);
        Ui.Form f = new Ui.Form().add("Ngày hẹn (dd/MM/yyyy)", date).add("Lời nhắn cho khách", note);
        if (JOptionPane.showConfirmDialog(this, f, "Hẹn lịch " + r.str("code"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (Ui.run(this, () -> ctx.app().requests.confirm(user(), r.lng("id"), Ui.toIso(date.getText(), "Ngày hẹn"), note.getText()))) {
            refresh();
            ctx.frame().refreshBadges();
        }
    }

    private void convert(Row r) {
        JTextField extra = new JTextField(26), acc = new JTextField(26), cond = new JTextField(26);
        JTextField due = new JTextField(Ui.vnDate(LocalDate.now().plusDays(7)), 10);
        JComboBox<Ui.Item> tech = new JComboBox<>();
        Ui.run(this, () -> {
            JComboBox<Ui.Item> c = Ui.combo(ctx.app().catalog.technicians(user()), "— Chưa phân công —");
            for (int i = 0; i < c.getItemCount(); i++) tech.addItem(c.getItemAt(i));
        });
        Ui.Form f = new Ui.Form().add("Ghi nhận thêm tại quầy", extra).add("Phụ kiện kèm theo", acc).add("Tình trạng ngoại quan", cond)
                .add("Ngày hẹn trả (dd/MM/yyyy)", due).add("Phân công kỹ thuật viên", tech);
        if (JOptionPane.showConfirmDialog(this, f, "Tiếp nhận máy từ " + r.str("code"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("issue", extra.getText());
        in.put("accessories", acc.getText());
        in.put("condition", cond.getText());
        Object tid = Ui.selectedId(tech);
        if (tid != null) in.put("technician_id", tid);
        final Row[] res = new Row[1];
        if (Ui.run(this, () -> {
            in.put("due_date", Ui.toIso(due.getText(), "Ngày hẹn trả"));
            res[0] = ctx.app().requests.convert(user(), r.lng("id"), in);
        })) {
            refresh();
            ctx.frame().refreshBadges();
            long id = res[0].lng("ticket_id");
            if (Ui.confirm(this, "Đã tạo phiếu. In phiếu tiếp nhận?")) Ui.print(ctx, this, "tiep-nhan", id);
            TicketDialog.open(ctx, id);
        }
    }

    private void reject(Row r) {
        String note = JOptionPane.showInputDialog(this, "Lý do từ chối (khách sẽ thấy):", "Từ chối " + r.str("code"), JOptionPane.PLAIN_MESSAGE);
        if (note == null) return;
        if (Ui.run(this, () -> ctx.app().requests.reject(user(), r.lng("id"), note))) {
            refresh();
            ctx.frame().refreshBadges();
        }
    }
}
