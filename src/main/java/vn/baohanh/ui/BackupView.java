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
            Ui.Col.text("kind", "Loại", 140), Ui.Col.dateTime("time", "Thời gian"), Ui.Col.text("size_text", "Dung lượng", 100)));
    private final JTable table = Ui.table(model, null);

    public BackupView(Ui.Ctx ctx) {
        super(ctx);
        add(Ui.header("Sao lưu dữ liệu", "Tự động sao lưu mỗi 24 giờ (giữ 20 bản gần nhất). Mỗi bản gồm CSDL và ảnh.",
                Ui.button("Mở thư mục sao lưu", () -> Ui.run(this, () -> {
                    java.nio.file.Files.createDirectories(ctx.app().backup.dir());
                    Desktop.getDesktop().open(ctx.app().backup.dir().toFile());
                })),
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
        c.add(Ui.scroll(table), BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    private void restore() {
        Row r = Ui.selected(table, model);
        if (r == null) { Ui.info(this, "Chọn một bản sao lưu trong bảng"); return; }
        if (!Ui.confirm(this, "Khôi phục từ " + r.str("name") + "?\n\nToàn bộ dữ liệu sẽ quay về thời điểm đó. "
                + "Trạng thái hiện tại sẽ được tự sao lưu trước để có thể quay lại.")) return;
        final String[] safety = new String[1];
        if (Ui.run(this, () -> safety[0] = ctx.app().backup.restore(user(), r.str("name")))) {
            refresh();
            ctx.frame().refreshBadges();
            Ui.info(this, "Đã khôi phục.\nTrạng thái trước khi khôi phục được lưu ở: " + safety[0]);
        }
    }

    @Override
    public void refresh() {
        Ui.run(this, () -> {
            List<Row> list = ctx.app().backup.list(user());
            for (Row x : list) x.put("size_text", Math.max(1, x.lng("size") / 1024) + " KB");
            model.setRows(list);
        });
    }
}
