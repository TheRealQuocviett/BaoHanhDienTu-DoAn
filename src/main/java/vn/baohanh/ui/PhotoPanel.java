package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.service.PhotoService;

/** Khung ảnh tình trạng máy trong hộp thoại phiếu: xem ảnh thu nhỏ, phóng to, thêm, xóa. */
final class PhotoPanel {
    private PhotoPanel() {}

    static JPanel build(Ui.Ctx ctx, Component owner, long ticketId, boolean canAdd, Runnable reload) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Ui.CARD);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0),
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.LINE), Ui.pad(10, 12))));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(Ui.bold("Ảnh tình trạng máy"), BorderLayout.WEST);
        if (canAdd) head.add(Ui.button("+ Thêm ảnh", () -> { if (add(ctx, owner, ticketId)) reload.run(); }), BorderLayout.EAST);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        p.add(head);

        JPanel grid = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        List<Row> photos = new ArrayList<>();
        Ui.run(owner, () -> photos.addAll(ctx.app().photos.list(ctx.user(), ticketId)));
        if (photos.isEmpty()) grid.add(Ui.sub("Chưa có ảnh. Nên chụp mặt trước, mặt sau và các vết trầy khi nhận máy."));
        for (Row ph : photos) grid.add(thumb(ctx, owner, ph, reload));
        p.add(grid);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height + (photos.isEmpty() ? 0 : 20)));
        return p;
    }

    private static BufferedImage load(Ui.Ctx ctx, long photoId) {
        try {
            Object[] img = ctx.app().photos.read(ctx.user(), photoId);
            return ImageIO.read(new ByteArrayInputStream((byte[]) img[1]));   // WebP không đọc được → null
        } catch (Exception e) {
            return null;
        }
    }

    private static JPanel thumb(Ui.Ctx ctx, Component owner, Row ph, Runnable reload) {
        JPanel box = new JPanel(new BorderLayout());
        box.setBackground(Ui.SOFT);
        box.setBorder(BorderFactory.createLineBorder(Ui.LINE));
        BufferedImage img = load(ctx, ph.lng("id"));
        JLabel pic = new JLabel(img == null ? "(xem trên web)" : null, SwingConstants.CENTER);
        if (img != null) pic.setIcon(new ImageIcon(scale(img, 130, 98)));
        pic.setPreferredSize(new Dimension(130, 98));
        pic.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pic.setToolTipText(ph.str("original_name") + " · " + ph.str("user_name"));
        if (img != null) pic.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { show(owner, img, ph.str("original_name")); }
        });
        box.add(pic, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        String cap = ph.str("note").isEmpty() ? ph.str("original_name") : ph.str("note");
        JLabel l = new JLabel(cap.length() > 16 ? cap.substring(0, 15) + "…" : cap);
        l.setFont(Ui.font(java.awt.Font.PLAIN, 11));
        l.setForeground(Ui.MUTE);
        l.setBorder(Ui.pad(2, 4));
        bottom.add(l, BorderLayout.CENTER);
        if (ctx.user().is(Role.ADMIN) || ph.lng("uploaded_by") == ctx.user().id()) {
            JButton del = new JButton("×");
            del.setMargin(new java.awt.Insets(0, 4, 0, 4));
            del.setToolTipText("Xóa ảnh");
            del.addActionListener(e -> {
                if (Ui.confirm(owner, "Xóa ảnh này?") && Ui.run(owner, () -> ctx.app().photos.delete(ctx.user(), ph.lng("id")))) reload.run();
            });
            bottom.add(del, BorderLayout.EAST);
        }
        box.add(bottom, BorderLayout.SOUTH);
        return box;
    }

    private static Image scale(BufferedImage img, int w, int h) {
        double r = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
        return img.getScaledInstance(Math.max(1, (int) (img.getWidth() * r)), Math.max(1, (int) (img.getHeight() * r)), Image.SCALE_SMOOTH);
    }

    private static void show(Component owner, BufferedImage img, String title) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.min(img.getWidth(), screen.width - 160), h = Math.min(img.getHeight(), screen.height - 200);
        JDialog d = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(owner), title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JLabel l = new JLabel(new ImageIcon(scale(img, w, h)));
        l.setBackground(Color.BLACK);
        l.setOpaque(true);
        d.add(new JScrollPane(l));
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    /** Chọn ảnh từ máy và tải lên. */
    private static boolean add(Ui.Ctx ctx, Component owner, long ticketId) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Chọn ảnh tình trạng máy");
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("Ảnh (JPG, PNG, WebP)", "jpg", "jpeg", "png", "webp"));
        if (fc.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return false;
        String note = javax.swing.JOptionPane.showInputDialog(owner, "Ghi chú cho ảnh (không bắt buộc), vd: vết trầy góc trái", "");
        int ok = 0;
        List<String> bad = new ArrayList<>();
        for (File f : fc.getSelectedFiles()) {
            try {
                if (f.length() > PhotoService.MAX_BYTES) { bad.add(f.getName() + " (quá 8 MB)"); continue; }
                ctx.app().photos.add(ctx.user(), ticketId, Files.readAllBytes(f.toPath()), f.getName(), note);
                ok++;
            } catch (Exception e) {
                bad.add(f.getName() + " (" + e.getMessage() + ")");
            }
        }
        if (!bad.isEmpty()) Ui.info(owner, (ok > 0 ? "Đã thêm " + ok + " ảnh.\n" : "") + "Không tải được:\n" + String.join("\n", bad));
        return ok > 0;
    }
}
