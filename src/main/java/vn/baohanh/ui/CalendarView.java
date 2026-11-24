package vn.baohanh.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.util.Fmt;

/** Lịch hẹn theo ngày / tuần: khách hẹn mang máy tới, hẹn trả máy, máy chờ khách nhận, phiếu quá hẹn. */
public class CalendarView extends MainFrame.View {
    private static final long serialVersionUID = 1L;
    private static final String[] DOW = {"T2", "T3", "T4", "T5", "T6", "T7", "CN"};
    private static Map<String, Color[]> colors() {
        boolean d = Ui.isDark();
        return Map.of(
                "APPOINTMENT", new Color[]{d ? new Color(0xA78BFA) : new Color(0x7C3AED), d ? new Color(0x261D45) : new Color(0xF4EFFF)},
                "REQUESTED", new Color[]{new Color(0xA78BFA), d ? new Color(0x1E1A33) : new Color(0xFAF8FF)},
                "DUE", new Color[]{Ui.ACCENT, d ? new Color(0x172A4D) : new Color(0xEEF4FF)},
                "READY", new Color[]{Ui.OK, d ? new Color(0x0F2E25) : new Color(0xECFAF3)},
                "OVERDUE", new Color[]{Ui.BAD, d ? new Color(0x3A1717) : new Color(0xFDEEEE)});
    }
    private final Map<String, Color[]> COLORS = colors();
    private static final Map<String, String> TITLES = Map.of("APPOINTMENT", "Khách mang máy tới", "REQUESTED", "Khách đề xuất",
            "DUE", "Hẹn trả", "READY", "Chờ khách nhận", "OVERDUE", "Quá hẹn");

    private boolean week = true;
    private LocalDate start = monday(LocalDate.now());
    private final JPanel grid = new JPanel();
    private final JLabel range = Ui.sub(" ");
    private final JComboBox<Ui.Item> tech = new JComboBox<>();
    private final JLabel bWeek = chip("Tuần"), bDay = chip("Ngày");
    private boolean loadingTech;

    private static LocalDate monday(LocalDate d) { return d.with(DayOfWeek.MONDAY); }

    private static JLabel chip(String text) {
        JLabel l = new JLabel(text);
        l.setOpaque(true);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.LINE), Ui.pad(6, 14)));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return l;
    }

    public CalendarView(Ui.Ctx ctx) {
        super(ctx);
        bWeek.addMouseListener(click(() -> { week = true; start = monday(start); refresh(); }));
        bDay.addMouseListener(click(() -> { week = false; if (start.equals(monday(LocalDate.now()))) start = LocalDate.now(); refresh(); }));
        Ui.wide(tech, 200);
        tech.addActionListener(e -> { if (!loadingTech) refresh(); });
        JComponent[] right = ctx.user().is(Role.TECHNICIAN)
                ? new JComponent[]{bDay, bWeek, Ui.button("‹", () -> move(-1)), Ui.button("Hôm nay", this::today), Ui.button("›", () -> move(1))}
                : new JComponent[]{bDay, bWeek, Ui.button("‹", () -> move(-1)), Ui.button("Hôm nay", this::today), Ui.button("›", () -> move(1)), tech};
        add(Ui.header("Lịch hẹn", "Khách hẹn mang máy tới, hẹn trả máy và phiếu quá hẹn. Bấm vào một lịch để mở.", right), BorderLayout.NORTH);
        JPanel legend = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 12, 0));
        legend.setOpaque(false);
        legend.add(range);
        for (String k : new String[]{"APPOINTMENT", "REQUESTED", "DUE", "READY", "OVERDUE"}) {
            if (ctx.user().is(Role.TECHNICIAN) && (k.equals("APPOINTMENT") || k.equals("REQUESTED"))) continue;
            JLabel l = new JLabel("■ " + TITLES.get(k));
            l.setForeground(COLORS.get(k)[0]);
            l.setFont(Ui.font(Font.PLAIN, 12));
            legend.add(l);
        }
        grid.setOpaque(false);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.setOpaque(false);
        c.add(legend, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(grid);
        sp.setBorder(null);
        sp.getViewport().setBackground(Ui.BG);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        c.add(sp, BorderLayout.CENTER);
        add(c, BorderLayout.CENTER);
    }

    private static MouseAdapter click(Runnable r) {
        return new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { r.run(); } };
    }

    private void move(int dir) { start = start.plusDays((long) dir * (week ? 7 : 1)); refresh(); }

    private void today() { start = week ? monday(LocalDate.now()) : LocalDate.now(); refresh(); }

    @Override
    public void refresh() {
        for (JLabel b : new JLabel[]{bWeek, bDay}) {
            boolean on = (b == bWeek) == week;
            b.setBackground(on ? Ui.ACCENT : Ui.CARD);
            b.setForeground(on ? Color.WHITE : Ui.INK);
        }
        int days = week ? 7 : 1;
        LocalDate end = start.plusDays(days - 1);
        final Row[] data = new Row[1];
        long techId = tech.getSelectedItem() instanceof Ui.Item it && !String.valueOf(it.id()).isEmpty() ? Long.parseLong(String.valueOf(it.id())) : 0;
        if (!Ui.run(this, () -> data[0] = ctx.app().calendar.events(user(), start.toString(), end.toString(), techId))) return;
        Row d = data[0];
        if (tech.getItemCount() == 0) {
            loadingTech = true;
            tech.addItem(new Ui.Item("", "Tất cả kỹ thuật viên"));
            for (Object o : (List<?>) d.get("technicians")) { Row t = (Row) o; tech.addItem(new Ui.Item(String.valueOf(t.lng("id")), t.str("full_name"))); }
            loadingTech = false;
        }
        @SuppressWarnings("unchecked") List<Row> events = (List<Row>) d.get("events");
        range.setText((week ? "Tuần " + Fmt.date(start.toString()) + " – " + Fmt.date(end.toString()) : DOW[start.getDayOfWeek().getValue() - 1] + ", " + Fmt.date(start.toString()))
                + " · " + events.size() + " lịch   ");
        range.setFont(Ui.font(Font.BOLD, 13));
        grid.removeAll();
        grid.setLayout(new GridLayout(1, days, 8, 0));
        String today = d.str("today");
        for (int i = 0; i < days; i++) {
            LocalDate day = start.plusDays(i);
            String k = day.toString();
            Ui.RoundPanel col = new Ui.RoundPanel(new BorderLayout(), 14);
            col.setBorder(Ui.pad(8, 8));
            if (k.equals(today)) col.setBackground(Ui.TODAY);
            JLabel head = new JLabel("<html><span style='color:" + (k.equals(today) ? Ui.hex(Ui.ACCENT) : Ui.hex(Ui.MUTE)) + "'>" + DOW[day.getDayOfWeek().getValue() - 1]
                    + (k.equals(today) ? " · Hôm nay" : "") + "</span> &nbsp;<b style='font-size:14px'>" + day.getDayOfMonth() + "/" + day.getMonthValue() + "</b></html>");
            head.setBorder(Ui.pad(0, 2));
            col.add(head, BorderLayout.NORTH);
            JPanel list = new JPanel();
            list.setOpaque(false);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            int n = 0;
            for (Row e : events) {
                if (!k.equals(e.str("date"))) continue;
                list.add(Box.createVerticalStrut(6));
                list.add(card(e, !week));
                n++;
            }
            if (n == 0) {
                JLabel none = Ui.sub("Không có lịch");
                none.setBorder(Ui.pad(8, 2));
                list.add(none);
            }
            col.add(list, BorderLayout.CENTER);
            col.setPreferredSize(new Dimension(week ? 150 : 900, Math.max(260, 60 + n * (week ? 96 : 84))));
            grid.add(col);
        }
        grid.revalidate();
        grid.repaint();
    }

    private JComponent card(Row e, boolean detail) {
        Color[] c = COLORS.get(e.str("kind"));
        String hex = String.format("#%06x", c[0].getRGB() & 0xFFFFFF);
        JLabel l = new JLabel("<html><body style='width:" + (detail ? 760 : 104) + "px'><b style='color:" + hex + "'>" + TITLES.get(e.str("kind")) + " · " + Fmt.html(e.str("code")) + "</b><br>"
                + Fmt.html(e.str("title"))
                + (detail ? "<br><span style='color:" + Ui.hex(Ui.MUTE) + "'>" + Fmt.html(e.str("customer_phone"))
                        + (e.str("technician").isEmpty() ? "" : " · KTV " + Fmt.html(e.str("technician")))
                        + (e.str("status_label").isEmpty() ? "" : " · " + Fmt.html(e.str("status_label")))
                        + "<br>" + Fmt.html(e.str("note")) + "</span>" : "") + "</body></html>");
        l.setFont(Ui.font(Font.PLAIN, 12));
        l.setOpaque(true);
        l.setBackground(c[1]);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, c[0]), Ui.pad(5, 7)));
        l.setAlignmentX(LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setToolTipText(e.str("note"));
        l.addMouseListener(click(() -> {
            if (e.get("ticket_id") != null) {
                TicketDialog.open(ctx, e.lng("ticket_id"));
                refresh();
            } else {
                ctx.frame().navigate("yeu-cau", null);
            }
        }));
        return l;
    }
}
