package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import vn.baohanh.db.Row;
import vn.baohanh.service.ImportService;
import vn.baohanh.util.Fmt;

/** Nhập khách hàng / sản phẩm / linh kiện từ file Excel: tải file mẫu → chọn file → xem trước → nhập. */
public final class ImportDialog {
    private ImportDialog() {}

    public static void open(Ui.Ctx ctx, String key, String title, Runnable after) {
        JDialog d = new JDialog(ctx.frame(), "Nhập " + title.toLowerCase() + " từ Excel", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        Ui.RowModel model = new Ui.RowModel(List.of(Ui.Col.num("line", "Dòng"), Ui.Col.text("key", "customer".equals(key) ? "SĐT" : "Mã", 130),
                Ui.Col.text("name", "Tên", 220), Ui.Col.text("result", "Kết quả", 90), Ui.Col.text("message", "Ghi chú", 330)));
        JLabel summary = new JLabel("<html>Dòng đầu của file là tiêu đề cột. Bản ghi đã có (trùng "
                + ("customer".equals(key) ? "số điện thoại" : "product".equals(key) ? "mã model" : "mã linh kiện")
                + ") sẽ được cập nhật, ô trống giữ giá trị cũ." + ("product".equals(key) ? " Hãng / loại chưa có sẽ được tạo mới." : "")
                + ("part".equals(key) ? " Tồn kho chỉ áp dụng cho linh kiện mới." : "") + "</html>");
        File[] chosen = {null};
        JButton go = Ui.primary("Nhập", () -> {});
        go.setEnabled(false);

        java.util.function.Consumer<Row> show = r -> {
            @SuppressWarnings("unchecked") List<Row> rows = (List<Row>) r.get("rows");
            for (Row x : rows) {
                @SuppressWarnings("unchecked") Map<String, String> v = (Map<String, String>) x.get("values");
                if (x.str("key").isEmpty()) x.put("key", v.getOrDefault("phone", v.getOrDefault("code", v.getOrDefault("model_code", ""))));
                if (x.str("name").isEmpty()) x.put("name", v.getOrDefault("name", ""));
                x.put("result", switch (x.str("action")) { case "CREATE" -> "Thêm mới"; case "UPDATE" -> "Cập nhật"; default -> "LỖI"; });
            }
            model.setRows(rows);
            long ok = r.lng("created") + r.lng("updated");
            @SuppressWarnings("unchecked") List<String> nb = (List<String>) r.get("new_brands");
            @SuppressWarnings("unchecked") List<String> nc = (List<String>) r.get("new_categories");
            summary.setText("<html><b>" + (Boolean.TRUE.equals(r.get("saved")) ? "Đã nhập xong: " : "Xem trước (chưa lưu): ") + "</b>"
                    + "<span style='color:" + Ui.hex(Ui.OK) + "'>" + r.lng("created") + " thêm mới</span> · " + r.lng("updated") + " cập nhật · "
                    + "<span style='color:" + Ui.hex(Ui.BAD) + "'>" + r.lng("errors") + " dòng lỗi (bỏ qua)</span>"
                    + (nb.isEmpty() ? "" : "<br>Hãng mới: " + Fmt.html(String.join(", ", nb)))
                    + (nc.isEmpty() ? "" : "<br>Loại mới: " + Fmt.html(String.join(", ", nc))) + "</html>");
            go.setText(Boolean.TRUE.equals(r.get("saved")) ? "Đã nhập" : "Nhập " + ok + " dòng hợp lệ");
            go.setEnabled(!Boolean.TRUE.equals(r.get("saved")) && ok > 0);
        };
        java.util.function.BiConsumer<Boolean, File> run = (commit, f) -> {
            d.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            new SwingWorker<Row, Void>() {
                @Override protected Row doInBackground() throws Exception {
                    byte[] data = Files.readAllBytes(f.toPath());
                    return commit ? ctx.app().imports.commit(ctx.user(), key, data, f.getName()) : ctx.app().imports.preview(ctx.user(), key, data, f.getName());
                }
                @Override protected void done() {
                    d.setCursor(Cursor.getDefaultCursor());
                    Ui.run(d, () -> {
                        try {
                            show.accept(get());
                            if (commit && after != null) after.run();
                        } catch (java.util.concurrent.ExecutionException ex) {
                            if (ex.getCause() instanceof Exception e) throw e;
                            throw ex;
                        }
                    });
                }
            }.execute();
        };
        for (var l : go.getActionListeners()) go.removeActionListener(l);
        go.addActionListener(e -> { if (chosen[0] != null) run.accept(true, chosen[0]); });

        JButton template = Ui.button("Lưu file mẫu…", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(ImportService.templateName(key)));
            if (fc.showSaveDialog(d) != JFileChooser.APPROVE_OPTION) return;
            Ui.run(d, () -> {
                Files.write(fc.getSelectedFile().toPath(), ctx.app().imports.template(ctx.user(), key));
                Ui.info(d, "Đã lưu " + fc.getSelectedFile().getName() + ". Mở bằng Excel, nhập dữ liệu rồi chọn lại file này.");
            });
        });
        JButton pick = Ui.primary("Chọn file .xlsx / .csv…", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Excel / CSV (*.xlsx, *.csv)", "xlsx", "csv"));
            if (fc.showOpenDialog(d) != JFileChooser.APPROVE_OPTION) return;
            chosen[0] = fc.getSelectedFile();
            d.setTitle("Nhập " + title.toLowerCase() + " – " + chosen[0].getName());
            run.accept(false, chosen[0]);
        });

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Ui.CARD);
        root.setBorder(Ui.pad(16, 18));
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(Ui.flow(template, pick), BorderLayout.NORTH);
        top.add(summary, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);
        root.add(Ui.scroll(Ui.table(model, null)), BorderLayout.CENTER);
        root.add(Ui.flow(go, Ui.button("Đóng", d::dispose)), BorderLayout.SOUTH);
        d.setContentPane(root);
        d.setSize(900, 560);
        d.setLocationRelativeTo(ctx.frame());
        d.setVisible(true);
    }
}
