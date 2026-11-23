package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import vn.baohanh.db.Row;

/** Tra cứu theo serial, SĐT, mã bảo hành, mã phiếu hoặc số hóa đơn sửa chữa. */
public class LookupView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final JTextField q = new JTextField(30);
    private final JPanel results = new JPanel();
    private String last = "";

    public LookupView(Ui.Ctx ctx) {
        super(ctx);
        q.setFont(Ui.font(Font.PLAIN, 17));
        q.addActionListener(e -> search());
        add(Ui.header("Tra cứu bảo hành", "Nhập Serial/IMEI, số điện thoại khách, mã bảo hành, mã phiếu hoặc số hóa đơn sửa chữa."), BorderLayout.NORTH);
        results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
        results.setOpaque(false);
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(Ui.flow(q, Ui.primary("Tra cứu", this::search)), BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(results);
        sp.setBorder(null);
        sp.getViewport().setOpaque(false);
        sp.setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        center.add(sp, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
    }

    private void search() {
        last = q.getText().trim();
        refresh();
    }

    @Override
    public void refresh() {
        results.removeAll();
        if (!last.isEmpty()) {
            Ui.run(this, () -> {
                List<Row> list = ctx.app().warranty.lookup(user(), last);
                if (list.isEmpty()) {
                    JLabel l = new JLabel("Không tìm thấy bảo hành cho “" + last + "”.");
                    l.setBorder(BorderFactory.createEmptyBorder(10, 4, 0, 0));
                    results.add(l);
                }
                for (Row w : list) {
                    results.add(WarrantyPanel.build(ctx, w, this::refresh));
                    results.add(Box.createVerticalStrut(12));
                }
            });
        }
        results.revalidate();
        results.repaint();
        q.requestFocusInWindow();
    }
}
