package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/** Sao lưu / khôi phục dữ liệu (chỉ quản lý). */
public class BackupView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private final Ui.RowModel model = new Ui.RowModel(List.of(Ui.Col.text("name", "Bản sao lưu", 360),
            Ui.Col.text("kind", "Loại", 140), Ui.Col.text("enc_text", "Mã hóa", 90), Ui.Col.dateTime("time", "Thời gian"), Ui.Col.text("size_text", "Dung lượng", 100)));
    private final JTable table = Ui.table(model, null);
    private final JLabel encState = new JLabel();

    public BackupView(Ui.Ctx ctx) {
        super(ctx);
        add(Ui.header("Sao lưu dữ liệu", "Tự động sao lưu mỗi 24 giờ (giữ 20 bản gần nhất). Mỗi bản gồm CSDL và ảnh.",
                Ui.button("Mở thư mục sao lưu", () -> Ui.run(this, () -> {
                    java.nio.file.Files.createDirectories(ctx.app().backup.dir());
                    Desktop.getDesktop().open(ctx.app().backup.dir().toFile());
                })),
                Ui.button("Mật khẩu sao lưu…", this::setPassword),
                Ui.button("Thêm file từ USB…", this::importFile),
                Ui.danger("Khôi phục bản đã chọn", this::restore),
                Ui.primary("Sao lưu ngay", () -> {
                    final Row[] r = new Row[1];
                    if (Ui.run(this, () -> r[0] = ctx.app().backup.create(user(), "thu-cong"))) {
                        refresh();
                        Ui.info(this, "Đã sao lưu: " + r[0].str("name"));
                    }
                })), BorderLayout.NORTH);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(new JLabel("<html>Thư mục: <b>" + Fmt.html(ctx.app().backup.dir().toString()) + "</b><br>"
                + "Nên chép thư mục này sang USB hoặc Google Drive định kỳ – hỏng ổ cứng thì bản sao lưu trên cùng máy cũng mất.</html>"), BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 6));
        mid.setOpaque(false);
        mid.add(encState, BorderLayout.NORTH);
        mid.add(Ui.scroll(table), BorderLayout.CENTER);
        c.add(mid, BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    private void restore() {
        Row r = Ui.selected(table, model);
        if (r == null) { Ui.info(this, "Chọn một bản sao lưu trong bảng"); return; }
        if (!Ui.confirm(this, "Khôi phục từ " + r.str("name") + "?\n\nToàn bộ dữ liệu sẽ quay về thời điểm đó. "
                + "Trạng thái hiện tại sẽ được tự sao lưu trước để có thể quay lại.")) return;
        String pw = null;
        if (r.bool("encrypted")) {
            javax.swing.JPasswordField f = new javax.swing.JPasswordField(20);
            Ui.Form form = new Ui.Form().add("Mật khẩu sao lưu", f).full(new JLabel(
                    "<html><small>Bản này đã mã hóa. Để trống nếu dùng mật khẩu đang cài trên máy này.</small></html>"));
            if (javax.swing.JOptionPane.showConfirmDialog(this, form, "Giải mã bản sao lưu", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return;
            pw = new String(f.getPassword());
        }
        final String password = pw;
        final String[] safety = new String[1];
        if (Ui.run(this, () -> safety[0] = ctx.app().backup.restore(user(), r.str("name"), password))) {
            refresh();
            ctx.frame().refreshBadges();
            Ui.info(this, "Đã khôi phục.\nTrạng thái trước khi khôi phục được lưu ở: " + safety[0]);
        }
    }

    private void setPassword() {
        javax.swing.JPasswordField p1 = new javax.swing.JPasswordField(20), p2 = new javax.swing.JPasswordField(20);
        Ui.Form f = new Ui.Form().add("Mật khẩu mới (≥ 8 ký tự)", p1).add("Nhập lại", p2).full(new JLabel(
                "<html><small>Bản sao lưu tạo từ giờ (cả bản tự động) sẽ được mã hóa AES-256.<br>Lỡ mất USB, không có mật khẩu thì không ai đọc được dữ liệu khách.<br>"
                + "<b>Quên mật khẩu này thì không khôi phục được bản đã mã hóa</b> – hãy ghi lại cất kỹ.<br>Để trống cả hai ô rồi bấm OK để tắt mã hóa.</small></html>"));
        if (javax.swing.JOptionPane.showConfirmDialog(this, f, "Mật khẩu mã hóa sao lưu", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return;
        if (Ui.run(this, () -> ctx.app().backup.setPassword(user(), new String(p1.getPassword()), new String(p2.getPassword())))) {
            refresh();
            Ui.info(this, p1.getPassword().length == 0 ? "Đã tắt mã hóa sao lưu" : "Đã lưu. Các bản sao lưu mới sẽ được mã hóa.");
        }
    }

    private void importFile() {
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        fc.setDialogTitle("Chọn file sao lưu (sao-luu-....zip / .zip.enc)");
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File file = fc.getSelectedFile();
        if (Ui.run(this, () -> {
            try (java.io.InputStream in = new java.io.FileInputStream(file)) { ctx.app().backup.importFile(user(), file.getName(), in); }
        })) {
            refresh();
            Ui.info(this, "Đã thêm " + file.getName() + ". Chọn dòng đó rồi bấm \"Khôi phục bản đã chọn\".");
        }
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            boolean on = ctx.app().backup.config(user()).bool("encrypted");
            encState.setText(on ? "<html><b style='color:" + Ui.hex(Ui.OK) + "'>Đang mã hóa</b> – bản sao lưu mới được khóa bằng mật khẩu (AES-256).</html>"
                    : "<html><b style='color:" + Ui.hex(Ui.BAD) + "'>Chưa mã hóa</b> – ai cầm được file (USB, Drive) đều mở được dữ liệu khách. Bấm \"Mật khẩu sao lưu…\" để bật.</html>");
            List<Row> list = ctx.app().backup.list(user());
            for (Row x : list) {
                x.put("size_text", Math.max(1, x.lng("size") / 1024) + " KB");
                x.put("enc_text", x.bool("encrypted") ? "Có" : "Không");
            }
            model.setRows(list);
        });
    }
}
