package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/** Kích hoạt bảo hành điện tử khi bán hàng. Tự nhận khách cũ theo số điện thoại. */
public class ActivateView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final JComboBox<Ui.Item> product = new JComboBox<>();
    private final Map<Object, Long> defaultMonths = new LinkedHashMap<>();
    private final JTextField serial = new JTextField(), invoice = new JTextField(), date = new JTextField(), months = new JTextField();
    private final JTextField phone = new JTextField(), name = new JTextField(), email = new JTextField(), address = new JTextField(), note = new JTextField();
    private final JLabel custInfo = Ui.sub(" ");
    private final JLabel result = new JLabel(" ");
    private final JCheckBox print = new JCheckBox("In phiếu bảo hành điện tử sau khi kích hoạt", true);

    public ActivateView(Ui.Ctx ctx) {
        super(ctx);
        add(Ui.header("Kích hoạt bảo hành điện tử", "Thực hiện khi bán hàng – khách không cần giữ phiếu giấy, tra cứu bằng serial hoặc SĐT."), BorderLayout.NORTH);
        product.addActionListener(e -> {
            Long m = defaultMonths.get(Ui.selectedId(product));
            months.setToolTipText(m == null ? "" : "Mặc định " + m + " tháng");
            if (m != null) months.setText(String.valueOf(m));
        });
        Timer t = new Timer(350, e -> lookupCustomer());
        t.setRepeats(false);
        phone.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { t.restart(); }
            public void removeUpdate(DocumentEvent e) { t.restart(); }
            public void changedUpdate(DocumentEvent e) { t.restart(); }
        });
        print.setOpaque(false);
        Ui.Form f = new Ui.Form();
        f.full(Ui.bold("Sản phẩm"))
         .add("Sản phẩm / Model *", product).add("Số Serial / IMEI *", serial).add("Số hóa đơn bán", invoice)
         .add("Ngày mua (dd/MM/yyyy) *", date).add("Thời hạn bảo hành (tháng)", months)
         .full(Ui.bold("Khách hàng"))
         .add("Số điện thoại *", phone).full(custInfo)
         .add("Họ tên (khách mới) *", name).add("Email", email).add("Địa chỉ", address).add("Ghi chú", note)
         .full(print)
         .full(Ui.flow(Ui.primary("Kích hoạt bảo hành", this::submit), Ui.button("Nhập lại", this::clear)))
         .full(result);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        JPanel card = Ui.card(f);
        card.setMaximumSize(new Dimension(760, 10000));
        card.setPreferredSize(new Dimension(720, card.getPreferredSize().height));
        wrap.add(card, BorderLayout.WEST);
        add(new javax.swing.JScrollPane(wrap) {
            private static final long serialVersionUID = 1L;
            { setBorder(null); getViewport().setBackground(Ui.BG); }
        }, BorderLayout.CENTER);
        clear();
    }

    private void lookupCustomer() {
        String p = phone.getText().replaceAll("[\\s.-]", "");
        if (p.length() < 10) { custInfo.setText(" "); setNewCustomer(true); return; }
        try {
            Row c = ctx.app().catalog.customerByPhone(user(), p);
            setNewCustomer(c == null);
            custInfo.setText(c == null ? "Khách hàng mới – nhập họ tên bên dưới." : "Khách hàng cũ: " + c.str("name") + (c.str("address").isEmpty() ? "" : " · " + c.str("address")));
        } catch (Exception e) {
            custInfo.setText(e.getMessage());
        }
    }

    private void setNewCustomer(boolean isNew) {
        name.setEnabled(isNew);
        email.setEnabled(isNew);
        address.setEnabled(isNew);
    }

    private void clear() {
        for (JTextField f : List.of(serial, invoice, months, phone, name, email, address, note)) f.setText("");
        date.setText(Ui.vnDate(LocalDate.now()));
        custInfo.setText(" ");
        setNewCustomer(true);
    }

    private void submit() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("product_id", Ui.selectedId(product));
        in.put("serial", serial.getText());
        in.put("invoice_no", invoice.getText());
        in.put("months", months.getText().isBlank() ? null : months.getText().trim());
        in.put("customer_phone", phone.getText());
        in.put("customer_name", name.getText());
        in.put("customer_email", email.getText());
        in.put("customer_address", address.getText());
        in.put("note", note.getText());
        final Row[] w = new Row[1];
        if (Ui.run(this, () -> {
            in.put("purchase_date", Ui.toIso(date.getText(), "Ngày mua"));
            if (in.get("months") != null && !in.get("months").toString().matches("\\d+"))
                throw new vn.baohanh.service.AppException("Thời hạn bảo hành phải là số tháng");
            w[0] = ctx.app().warranty.activate(user(), in);
        })) {
            result.setText("<html><b style='color:#2563eb'>Đã kích hoạt " + w[0].str("code") + "</b> – " + Fmt.html(w[0].str("product_name"))
                    + " · Serial " + Fmt.html(w[0].str("serial")) + " · bảo hành đến hết ngày " + Fmt.date(w[0].str("expiry_date")) + "</html>");
            if (print.isSelected()) Ui.print(ctx, this, "bao-hanh", w[0].lng("id"));
            clear();
            ctx.frame().refreshBadges();
        }
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            Object sel = Ui.selectedId(product);
            product.removeAllItems();
            defaultMonths.clear();
            product.addItem(new Ui.Item(null, "— Chọn sản phẩm —"));
            for (Row p : ctx.app().catalog.list(user(), "product", "")) {
                product.addItem(new Ui.Item(p.get("id"), p.str("brand_name") + " · " + p.str("name") + " (" + p.str("model_code") + ")"));
                defaultMonths.put(p.get("id"), p.lng("default_months"));
            }
            Ui.select(product, sel);
        });
    }
}
