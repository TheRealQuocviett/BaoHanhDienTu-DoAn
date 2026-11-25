package vn.baohanh.ui;

import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JLabel;

import vn.baohanh.db.Row;
import vn.baohanh.util.Fmt;

/** Khung "Gợi ý từ lịch sử sửa chữa" (dùng trong hộp tiếp nhận máy và chi tiết phiếu). */
public final class DiagnosisPanel {
    private DiagnosisPanel() {}

    public static JLabel create() {
        JLabel l = new JLabel(" ");
        l.setVerticalAlignment(JLabel.TOP);
        return l;
    }

    @SuppressWarnings("unchecked")
    public static void show(JLabel l, Row d, int width) {
        if (d == null || !Boolean.TRUE.equals(d.get("ready"))) { l.setText(" "); l.setBorder(null); l.setOpaque(false); return; }
        String mute = Ui.hex(Ui.MUTE);
        StringBuilder sb = new StringBuilder("<html><body style='width:" + width + "px'>");
        sb.append("<b>Gợi ý từ lịch sử sửa chữa</b>");
        if (d.lng("count") == 0) {
            sb.append("<br><span style='color:").append(mute).append("'>").append(Fmt.html(d.str("summary"))).append("</span>");
        } else {
            String cc = switch (d.str("confidence")) { case "Cao" -> Ui.hex(Ui.OK); case "Trung bình" -> Ui.hex(Ui.WARN); default -> mute; };
            sb.append(" &nbsp;<span style='color:").append(cc).append("'>Độ tin cậy: ").append(Fmt.html(d.str("confidence"))).append("</span>")
              .append(" <span style='color:").append(mute).append("'>· ").append(d.lng("count")).append(" phiếu tương tự, ")
              .append(d.lng("same_model")).append(" cùng model</span><br>").append(Fmt.html(d.str("summary")));
            List<Row> parts = (List<Row>) d.get("parts");
            if (!parts.isEmpty()) {
                sb.append("<br><span style='color:").append(mute).append("'>Linh kiện hay thay:</span> ");
                for (int i = 0; i < Math.min(3, parts.size()); i++) {
                    Row p = parts.get(i);
                    if (i > 0) sb.append(" · ");
                    sb.append("<b>").append(p.lng("pct")).append("%</b> ").append(Fmt.html(p.str("name")));
                    if (p.get("stock") != null) sb.append(" <span style='color:").append(p.lng("stock") <= 0 ? Ui.hex(Ui.BAD) : mute).append("'>(tồn ").append(p.lng("stock")).append(")</span>");
                }
            }
            List<Row> dg = (List<Row>) d.get("diagnoses");
            if (!dg.isEmpty()) {
                sb.append("<br><span style='color:").append(mute).append("'>Kết luận thường gặp:</span> ");
                for (int i = 0; i < dg.size(); i++) sb.append(i > 0 ? " · " : "").append(Fmt.html(dg.get(i).str("text"))).append(" (").append(dg.get(i).lng("pct")).append("%)");
            }
            if (d.get("cost_mid") != null)
                sb.append("<br><span style='color:").append(mute).append("'>Chi phí ước tính:</span> <b>").append(Fmt.money(d.lng("cost_mid"))).append("</b>")
                  .append(Boolean.TRUE.equals(d.get("in_warranty")) ? " (máy còn BH – thường miễn phí)" : "")
                  .append(" <span style='color:").append(mute).append("'>– ").append(Fmt.html(d.str("cost_basis"))).append("</span>");
            if (d.get("days_mid") != null) sb.append("<br><span style='color:").append(mute).append("'>Thường sửa mất:</span> ").append(d.lng("days_mid")).append(" ngày");
            if (d.lng("rejected_pct") >= 30) sb.append("<br><span style='color:").append(Ui.hex(Ui.BAD)).append("'>Lưu ý: ").append(d.lng("rejected_pct"))
                    .append("% ca tương tự bị từ chối bảo hành</span>");
        }
        sb.append("</body></html>");
        l.setText(sb.toString());
        l.setOpaque(true);
        l.setBackground(Ui.ACCENT_SOFT);
        l.setForeground(Ui.INK);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.BORDER), Ui.pad(8, 10)));
    }
}
