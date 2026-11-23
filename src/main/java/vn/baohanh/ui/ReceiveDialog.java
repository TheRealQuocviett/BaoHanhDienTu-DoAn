package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/** Tiếp nhận máy: nhập serial (tự kiểm tra hạn bảo hành), lỗi, phụ kiện, hẹn trả, phân công. */
public final class ReceiveDialog {
    private ReceiveDialog() {}

    public static void open(Ui.Ctx ctx, String serial) {
        JDialog d = new JDialog(ctx.frame(), "Tiếp nhận máy bảo hành", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTextField sf = new JTextField(serial == null ? "" : serial, 26);
        JLabel check = new JLabel(" ");
        JTextArea issue = new JTextArea(3, 30);
        issue.setLineWrap(true);
        issue.setWrapStyleWord(true);
        JTextField acc = new JTextField(), cond = new JTextField();
        JTextField due = new JTextField(Ui.vnDate(LocalDate.now().plusDays(7)), 10);
        JComboBox<Ui.Item> tech = new JComboBox<>();
        Ui.run(ctx.frame(), () -> {
            JComboBox<Ui.Item> c = Ui.combo(ctx.app().catalog.technicians(ctx.user()), "— Chưa phân công —");
            for (int i = 0; i < c.getItemCount(); i++) tech.addItem(c.getItemAt(i));
        });
        JCheckBox print = new JCheckBox("In phiếu tiếp nhận sau khi tạo", true);
        print.setOpaque(false);

        Runnable doCheck = () -> {
            String s = sf.getText().trim();
            if (s.length() < 3) { check.setText(" "); return; }
            try {
                Row w = ctx.app().warranty.bySerial(ctx.user(), s);
                if (w == null) { check.setText("<html><span style='color:#c4320a'>Serial chưa được kích hoạt bảo hành tại cửa hàng.</span></html>"); return; }
                boolean active = Boolean.TRUE.equals(w.get("active"));
                @SuppressWarnings("unchecked") List<Row> ts = (List<Row>) w.get("tickets");
                String open = ts.stream().filter(t -> !List.of("RETURNED", "CANCELLED").contains(t.str("status"))).map(t -> t.str("code")).findFirst().orElse(null);
                check.setText("<html><b>" + Fmt.html(w.str("product_name")) + "</b> – " + Fmt.html(w.str("customer_name")) + " (" + w.str("customer_phone") + ")<br>"
                        + (active ? "<span style='color:#0f7b45'>Còn bảo hành</span>" : "<span style='color:#c4320a'>Hết bảo hành – sửa có phí, cần báo giá</span>")
                        + " · hết hạn " + Fmt.date(w.str("expiry_date"))
                        + (open == null ? "" : "<br><span style='color:#c4320a'>Đang có phiếu chưa đóng: " + open + "</span>") + "</html>");
            } catch (Exception e) {
                check.setText(e.getMessage());
            }
        };
        Timer timer = new Timer(350, e -> doCheck.run());
        timer.setRepeats(false);
        sf.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { timer.restart(); }
            public void removeUpdate(DocumentEvent e) { timer.restart(); }
            public void changedUpdate(DocumentEvent e) { timer.restart(); }
        });
        if (serial != null) doCheck.run();

        Ui.Form f = new Ui.Form();
        f.add("Số Serial / IMEI *", sf).full(check).add("Lỗi khách báo *", new JScrollPane(issue))
         .add("Phụ kiện kèm theo", acc).add("Tình trạng ngoại quan", cond).add("Ngày hẹn trả (dd/MM/yyyy)", due)
         .add("Phân công kỹ thuật viên", tech).full(print);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(Ui.pad(16, 20));
        root.setBackground(Ui.CARD);
        root.add(Ui.title("Tiếp nhận máy"), BorderLayout.NORTH);
        root.add(f, BorderLayout.CENTER);
        root.add(Ui.flow(Ui.primary("Tạo phiếu", () -> {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("serial", sf.getText());
            in.put("issue", issue.getText());
            in.put("accessories", acc.getText());
            in.put("condition", cond.getText());
            Object tid = Ui.selectedId(tech);
            if (tid != null) in.put("technician_id", tid);
            final Row[] created = new Row[1];
            if (Ui.run(d, () -> {
                in.put("due_date", Ui.toIso(due.getText(), "Ngày hẹn trả"));
                created[0] = ctx.app().warranty.createTicket(ctx.user(), in);
            })) {
                d.dispose();
                ctx.frame().refreshCurrent();
                long id = created[0].lng("id");
                if (print.isSelected()) Ui.print(ctx, ctx.frame(), "tiep-nhan", id);
                TicketDialog.open(ctx, id);
            }
        }), Ui.button("Hủy", d::dispose)), BorderLayout.SOUTH);
        root.setBorder(BorderFactory.createCompoundBorder(root.getBorder(), BorderFactory.createEmptyBorder()));
        d.setContentPane(root);
        d.pack();
        d.setMinimumSize(new Dimension(620, d.getHeight()));
        d.setLocationRelativeTo(ctx.frame());
        d.setVisible(true);
    }
}
