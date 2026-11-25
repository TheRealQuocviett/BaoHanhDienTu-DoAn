package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.WindowConstants;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.service.AppContext;

/**
 * Cửa sổ chính: thanh điều hướng bên trái (lọc theo vai trò), thanh trên cùng, nội dung ở giữa.
 */
public class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private static boolean open;

    private final Ui.Ctx ctx;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JLabel pageTitle = new JLabel();
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, NavItem> navItems = new LinkedHashMap<>();
    private final JPanel side = new JPanel();
    private String current;
    private final javax.swing.JButton alertsBtn = Ui.button("Nhắc việc", this::openAlerts);

    private void openAlerts() {
        AlertsDialog.open(ctx);
    }

    /** Một trang trong cửa sổ chính. */
    public abstract static class View extends JPanel {
        private static final long serialVersionUID = 1L;
        protected final Ui.Ctx ctx;

        protected View(Ui.Ctx ctx) {
            super(new BorderLayout());
            this.ctx = ctx;
            setBackground(Ui.BG);
            setBorder(Ui.pad(18, 22));
        }

        /** Nạp lại dữ liệu mỗi khi trang được mở. */
        public abstract void refresh();

        /** Danh sách dòng trong kết quả phân trang. */
        @SuppressWarnings("unchecked")
        protected static java.util.List<vn.baohanh.db.Row> rows(vn.baohanh.db.Row pageResult) {
            return (java.util.List<vn.baohanh.db.Row>) pageResult.get("rows");
        }

        /** Nhận bộ lọc từ trang khác (vd ô số liệu ở Tổng quan). */
        public void applyFilter(String filter) { }

        protected User user() { return ctx.user(); }
    }

    private record Nav(String key, String label, Role[] roles, vn.baohanh.model.Perm perm, Supplier<View> factory) {
        Nav(String key, String label, Role[] roles, Supplier<View> factory) { this(key, label, roles, null, factory); }
        Nav(String key, String label, vn.baohanh.model.Perm perm, Supplier<View> factory) { this(key, label, null, perm, factory); }
        boolean allowed(User u) { return perm != null ? perm.allows(u) : u.is(roles); }
    }


    private final class NavItem extends JLabel {
        private static final long serialVersionUID = 1L;
        final String key;
        final String base;

        NavItem(String key, String label) {
            super(label);
            this.key = key;
            this.base = label;
            setForeground(Ui.NAV_TEXT);
            setFont(Ui.font(Font.PLAIN, 14));
            setBorder(Ui.pad(9, 24));
            setOpaque(false);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { navigate(key, null); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        private boolean on, hover;

        void mark(boolean on) {
            this.on = on;
            setForeground(on ? Ui.ACCENT_TEXT : Ui.NAV_TEXT);
            setFont(Ui.font(on ? Font.BOLD : Font.PLAIN, 14));
            repaint();
        }

        @Override
        protected void paintComponent(java.awt.Graphics g0) {
            if (on || hover) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(on ? Ui.ACCENT_SOFT : Ui.SOFT);
                g.fillRoundRect(8, 1, getWidth() - 16, getHeight() - 2, 12, 12);
                if (on) { g.setColor(Ui.ACCENT); g.fillRoundRect(8, 9, 4, getHeight() - 18, 4, 4); }
                g.dispose();
            }
            super.paintComponent(g0);
        }
    }

    public MainFrame(AppContext app, User user, String webUrl) {
        super("Quản lý bảo hành điện tử – " + user.fullName() + " (" + user.role().getLabel() + ")");
        setIconImages(Ui.logoImages());
        this.ctx = new Ui.Ctx(app, user, webUrl, this);
        open = true;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { askClose(); }
        });

        Role A = Role.ADMIN, R = Role.RECEPTION, T = Role.TECHNICIAN;
        Role[] all = {A, R, T}, ar = {A, R}, at = {A, T}, a = {A};
        List<Object[]> groups = new ArrayList<>();
        groups.add(new Object[]{"", List.of(
                new Nav("tong-quan", "Tổng quan", all, () -> new DashboardView(ctx)),
                new Nav("tra-cuu", "Tra cứu", all, () -> new LookupView(ctx)),
                new Nav("phieu", "Phiếu bảo hành", all, () -> new TicketsView(ctx)),
                new Nav("kanban", "Bảng công việc", all, () -> new KanbanView(ctx)),
                new Nav("kich-hoat", "Kích hoạt bảo hành", vn.baohanh.model.Perm.ACTIVATE, () -> new ActivateView(ctx)),
                new Nav("bao-hanh", "Danh sách bảo hành", all, () -> new WarrantiesView(ctx)),
                new Nav("yeu-cau", "Yêu cầu online", all, () -> new RequestsView(ctx)),
                new Nav("lich-hen", "Lịch hẹn", all, () -> new CalendarView(ctx)),
                new Nav("tin-nhan", "Tin nhắn khách", all, () -> new MessagesView(ctx)))});
        groups.add(new Object[]{"DANH MỤC", List.of(
                new Nav("dm-customer", "Khách hàng", all, () -> new CatalogView(ctx, "customer")),
                new Nav("dm-product", "Sản phẩm / Model", all, () -> new CatalogView(ctx, "product")),
                new Nav("dm-brand", "Hãng sản xuất", all, () -> new CatalogView(ctx, "brand")),
                new Nav("dm-service_center", "Trung tâm BH hãng", all, () -> new CatalogView(ctx, "service_center")),
                new Nav("dm-category", "Loại sản phẩm", all, () -> new CatalogView(ctx, "category")))});
        groups.add(new Object[]{"KHO", List.of(
                new Nav("dm-part", "Linh kiện", all, () -> new CatalogView(ctx, "part")),
                new Nav("dat-hang", "Đặt hàng linh kiện", vn.baohanh.model.Perm.PURCHASE_VIEW, () -> new PurchaseView(ctx)),
                new Nav("dm-supplier", "Nhà cung cấp", vn.baohanh.model.Perm.PURCHASE_VIEW, () -> new CatalogView(ctx, "supplier")),
                new Nav("the-kho", "Thẻ kho", vn.baohanh.model.Perm.STOCK_VIEW, () -> new StockView(ctx)))});
        groups.add(new Object[]{"QUẢN TRỊ", List.of(
                new Nav("bao-cao", "Báo cáo thống kê", vn.baohanh.model.Perm.REPORTS, () -> new ReportsView(ctx)),
                new Nav("kpi", "KPI kỹ thuật viên", vn.baohanh.model.Perm.KPI, () -> new KpiView(ctx)),
                new Nav("loi", "Thống kê lỗi thường gặp", vn.baohanh.model.Perm.FAULTS, () -> new FaultsView(ctx)),
                new Nav("dm-user", "Người dùng", a, () -> new CatalogView(ctx, "user")),
                new Nav("phan-quyen", "Phân quyền chi tiết", a, () -> new PermissionsView(ctx)),
                new Nav("cai-dat", "Thông tin cửa hàng", vn.baohanh.model.Perm.SETTINGS, () -> new SettingsView(ctx)),
                new Nav("thong-bao", "Thông báo cho khách", vn.baohanh.model.Perm.NOTIFY_VIEW, () -> new NotificationsView(ctx)),
                new Nav("sao-luu", "Sao lưu dữ liệu", vn.baohanh.model.Perm.BACKUP, () -> new BackupView(ctx)),
                new Nav("nhat-ky", "Nhật ký hệ thống", vn.baohanh.model.Perm.AUDIT, () -> new AuditView(ctx)))});

        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(Ui.CARD);
        side.setBorder(BorderFactory.createEmptyBorder(14, 0, 14, 0));
        JLabel brand = new JLabel("Bảo hành điện tử", Ui.logo(30), JLabel.LEFT);
        brand.setIconTextGap(10);
        brand.setForeground(Ui.INK);
        brand.setFont(Ui.font(Font.BOLD, 15));
        brand.setBorder(Ui.pad(4, 16));
        side.add(brand);
        side.add(Box.createVerticalStrut(10));
        Map<String, Nav> navs = new LinkedHashMap<>();
        for (Object[] g : groups) {
            @SuppressWarnings("unchecked") List<Nav> items = (List<Nav>) g[1];
            List<Nav> allowed = items.stream().filter(n -> n.allowed(user)).toList();
            if (allowed.isEmpty()) continue;
            if (!((String) g[0]).isEmpty()) {
                JLabel gl = new JLabel((String) g[0]);
                gl.setForeground(Ui.FAINT);
                gl.setFont(Ui.font(Font.BOLD, 11));
                gl.setBorder(BorderFactory.createEmptyBorder(16, 22, 6, 16));
                side.add(gl);
            }
            for (Nav n : allowed) {
                NavItem it = new NavItem(n.key(), n.label());
                it.setAlignmentX(Component.LEFT_ALIGNMENT);
                navItems.put(n.key(), it);
                navs.put(n.key(), n);
                side.add(it);
            }
        }
        side.add(Box.createVerticalGlue());
        JScrollPane sideScroll = new JScrollPane(side);
        sideScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.LINE));
        sideScroll.setPreferredSize(new Dimension(240, 10));
        sideScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Ui.CARD);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.LINE), Ui.pad(10, 22)));
        pageTitle.setFont(Ui.font(Font.BOLD, 14));
        pageTitle.setForeground(Ui.MUTE);
        top.add(pageTitle, BorderLayout.WEST);
        JLabel who = new JLabel(user.fullName() + " · " + user.role().getLabel() + "   ");
        who.setForeground(Ui.MUTE);
        top.add(Ui.flow(who, alertsBtn,
                Ui.button(Ui.isDark() ? "Giao diện sáng" : "Giao diện tối", this::toggleTheme),
                Ui.button("Mở giao diện web", () -> Ui.browse(this, webUrl)),
                Ui.button("Bảo mật", () -> new TwoFactorDialog(this, ctx.app(), ctx.user()).setVisible(true)),
                Ui.button("Đổi mật khẩu", this::changePassword),
                Ui.button("Đăng xuất", this::logout)), BorderLayout.EAST);

        JPanel center = new JPanel(new BorderLayout());
        center.add(top, BorderLayout.NORTH);
        center.add(content, BorderLayout.CENTER);
        getContentPane().add(sideScroll, BorderLayout.WEST);
        getContentPane().add(center, BorderLayout.CENTER);

        this.navs = navs;
        setSize(1280, 820);
        setMinimumSize(new Dimension(1000, 640));
        setLocationRelativeTo(null);
        navigate("tong-quan", null);
        refreshBadges();
    }

    private final Map<String, Nav> navs;

    public static boolean isOpen() { return open; }

    /** Chuyển trang (có thể kèm bộ lọc). */
    public void navigate(String key, String filter) {
        Nav n = navs.get(key);
        if (n == null) return;
        View v = views.get(key);
        if (v == null) {
            v = n.factory().get();
            views.put(key, v);
            content.add(v, key);
        }
        current = key;
        navItems.forEach((k, it) -> it.mark(k.equals(key)));
        pageTitle.setText(n.label());
        cards.show(content, key);
        if (filter != null) v.applyFilter(filter);
        View view = v;
        Ui.run(this, view::refresh);
    }

    /** Cập nhật số đếm trên thanh điều hướng. */
    public void refreshBadges() {
        try {
            Row d = ctx.app().warranty.dashboard(ctx.user());
            badge("phieu", d.lng("open_tickets"));
            badge("dm-part", d.lng("low_stock"));
            badge("yeu-cau", d.lng("new_requests"));
            badge("tin-nhan", d.lng("unread_messages"));
            badge("dat-hang", d.lng("parts_to_order"));
            badge("lich-hen", d.lng("today_events"));
            badge("thong-bao", d.lng("notify_failed"));
            long al = d.lng("alerts");
            alertsBtn.setText(al > 0 ? "Nhắc việc (" + al + ")" : "Nhắc việc");
            alertsBtn.setForeground(al > 0 ? Ui.BAD : Ui.INK);
        } catch (Exception ignored) {
            // không quan trọng
        }
    }

    private void badge(String key, long n) {
        NavItem it = navItems.get(key);
        if (it != null) it.setText(n > 0 ? "<html>" + it.base + " &nbsp;<span style='color:" + Ui.hex(Ui.ACCENT) + "'><b>" + n + "</b></span></html>" : it.base);
    }

    /** Làm mới trang đang mở (sau khi đóng hộp thoại). */
    public void refreshCurrent() {
        View v = views.get(current);
        if (v != null) Ui.run(this, v::refresh);
        refreshBadges();
    }

    private void changePassword() {
        JPasswordField o = new JPasswordField(18), n1 = new JPasswordField(18), n2 = new JPasswordField(18);
        Ui.Form f = new Ui.Form().add("Mật khẩu hiện tại", o).add("Mật khẩu mới (≥ 6 ký tự)", n1).add("Nhập lại mật khẩu mới", n2);
        if (JOptionPane.showConfirmDialog(this, f, "Đổi mật khẩu", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (!new String(n1.getPassword()).equals(new String(n2.getPassword()))) { Ui.info(this, "Mật khẩu nhập lại không khớp"); return; }
        if (Ui.run(this, () -> ctx.app().auth.changePassword(ctx.user(), new String(o.getPassword()), new String(n1.getPassword()))))
            Ui.info(this, "Đã đổi mật khẩu");
    }

    /** Đổi sáng / tối: dựng lại cửa sổ với bảng màu mới, giữ nguyên trang đang mở. */
    private void toggleTheme() {
        Ui.setDark(!Ui.isDark());
        Ui.installTheme();
        MainFrame mf = new MainFrame(ctx.app(), ctx.user(), ctx.webUrl());
        mf.setBounds(getBounds());
        mf.setExtendedState(getExtendedState());
        if (current != null) mf.navigate(current, null);
        dispose();
        mf.setVisible(true);
    }


    private void logout() {
        Ui.run(this, () -> ctx.app().audit.log(ctx.user(), "ĐĂNG XUẤT", "app_user", ctx.user().id(), "Swing"));
        open = false;
        dispose();
        SwingApp.showLogin(ctx.app(), ctx.webUrl());
    }

    private void askClose() {
        Object[] opts = {"Thoát hẳn", "Chỉ đóng cửa sổ", "Hủy"};
        int c = JOptionPane.showOptionDialog(this,
                "Thoát hẳn sẽ tắt cả giao diện web và cổng tra cứu cho khách.\nChọn \"Chỉ đóng cửa sổ\" nếu muốn web vẫn chạy.",
                "Đóng phần mềm", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[1]);
        if (c == 0) System.exit(0);
        if (c == 1) { open = false; dispose(); }
    }
}
