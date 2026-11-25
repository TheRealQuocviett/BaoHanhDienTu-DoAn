package vn.baohanh.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.User;
import vn.baohanh.util.Fmt;

/**
 * Gợi ý chẩn đoán từ lịch sử sửa chữa.
 * Với một máy (serial) và mô tả lỗi, tìm các phiếu đã xử lý xong có lỗi tương tự (cùng nhóm lỗi + trùng cụm từ),
 * ưu tiên cùng model, rồi cùng hãng & loại, rồi cùng loại sản phẩm. Từ đó tổng hợp:
 * linh kiện hay phải thay (kèm tỷ lệ), kết luận kỹ thuật thường gặp, chi phí ước tính (khoảng giữa 25%–75%),
 * thời gian sửa thường mất, tỷ lệ bị từ chối bảo hành và vài phiếu mẫu để kỹ thuật viên tham khảo.
 */
public class DiagnosisService {
    private final Database db;

    public DiagnosisService(Database db) {
        this.db = db;
    }

    private static final Set<String> STOP = Set.of("may", "bi", "cua", "va", "la", "thi", "nhung", "con", "rat", "hay", "luc", "khi", "nay",
            "do", "co", "cho", "voi", "duoc", "roi", "nua", "lai", "em", "anh", "chi", "shop", "minh", "toi", "hon", "qua", "mot", "cai");

    /** Các cụm 1 – 2 từ có nghĩa của mô tả lỗi (đã bỏ dấu, bỏ từ đệm). */
    static Set<String> terms(String text) {
        String[] w = FaultService.normalize(text).split(" ");
        List<String> words = new ArrayList<>();
        for (String x : w) if (x.length() >= 2 && !STOP.contains(x)) words.add(x);
        Set<String> out = new HashSet<>(words);
        for (int i = 0; i + 1 < words.size(); i++) out.add(words.get(i) + " " + words.get(i + 1));
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String x : a) if (b.contains(x)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    /**
     * Gợi ý cho một máy. in = {serial | ticket_id, issue}. Khi xem một phiếu (ticket_id) thì phiếu đó được loại khỏi dữ liệu so sánh.
     */
    public Row suggest(User u, Map<String, Object> in) throws SQLException {
        Auth.require(u);
        long ticketId = in.get("ticket_id") == null ? 0 : Long.parseLong(String.valueOf(in.get("ticket_id")));
        String serial = String.valueOf(in.getOrDefault("serial", "")).trim();
        String issue = String.valueOf(in.getOrDefault("issue", "")).trim();
        Row dev;
        if (ticketId > 0) {
            dev = db.one("SELECT t.issue, t.in_warranty, p.id AS product_id, p.name AS product, p.brand_id, p.category_id FROM ticket t "
                    + "JOIN warranty w ON w.id = t.warranty_id JOIN product p ON p.id = w.product_id WHERE t.id = ?", ticketId);
            if (dev == null) throw AppException.notFound("phiếu");
            if (issue.isEmpty()) issue = dev.str("issue");
        } else {
            dev = db.one("SELECT p.id AS product_id, p.name AS product, p.brand_id, p.category_id, "
                    + "CASE WHEN w.expiry_date >= date('now','localtime') THEN 1 ELSE 0 END AS in_warranty FROM warranty w "
                    + "JOIN product p ON p.id = w.product_id WHERE w.serial = ? COLLATE NOCASE", serial);
        }
        Row out = new Row().with("issue", issue);
        if (dev == null || issue.length() < 4) return out.with("ready", false).with("count", 0L);
        out.put("product", dev.str("product"));

        List<String> groupsIn = FaultService.classify(issue);
        boolean inOther = groupsIn.equals(List.of(FaultService.OTHER));
        Set<String> termsIn = terms(issue);
        out.put("groups", groupsIn.stream().map(FaultService::label).toList());

        List<Row> past = db.query("SELECT t.id, t.code, t.issue, COALESCE(t.diagnosis, '') AS diagnosis, t.status, t.in_warranty, t.labor_cost, "
                + "p.id AS product_id, p.name AS product, p.brand_id, p.category_id, vc.total, "
                + "t.labor_cost + COALESCE((SELECT SUM(tp.qty * tp.unit_price) FROM ticket_part tp WHERE tp.ticket_id = t.id), 0) AS value, "
                + "julianday(COALESCE((SELECT MIN(h.time) FROM ticket_history h WHERE h.ticket_id = t.id AND h.status IN ('READY','REJECTED')), t.returned_at)) "
                + "  - julianday(t.received_at) AS days "
                + "FROM ticket t JOIN warranty w ON w.id = t.warranty_id JOIN product p ON p.id = w.product_id JOIN v_ticket_cost vc ON vc.ticket_id = t.id "
                + "WHERE t.status IN ('READY','RETURNED','REJECTED') AND t.id <> ? AND p.category_id = ?", ticketId, dev.lng("category_id"));
        List<Row> similar = new ArrayList<>();
        for (Row t : past) {
            List<String> g = FaultService.classify(t.str("issue") + " . " + t.str("diagnosis"));
            double gs = 0;
            if (!inOther) {
                int inter = 0;
                for (String k : groupsIn) if (g.contains(k)) inter++;
                gs = 2.0 * inter / (groupsIn.size() + g.size());
            }
            double ts = jaccard(termsIn, terms(t.str("issue")));
            double sim = 0.75 * gs + 0.6 * ts;
            if (sim < 0.3) continue;
            double scope = t.lng("product_id") == dev.lng("product_id") ? 1.0 : t.lng("brand_id") == dev.lng("brand_id") ? 0.75 : 0.5;
            t.put("score", Math.round(Math.min(1, sim) * scope * 100) / 100.0);
            t.put("same_model", t.lng("product_id") == dev.lng("product_id"));
            similar.add(t);
        }
        similar.sort((a, b) -> Double.compare(((Number) b.get("score")).doubleValue(), ((Number) a.get("score")).doubleValue()));
        if (similar.size() > 30) similar = new ArrayList<>(similar.subList(0, 30));
        int n = similar.size();
        out.put("ready", true);
        out.put("count", (long) n);
        out.put("same_model", similar.stream().filter(x -> Boolean.TRUE.equals(x.get("same_model"))).count());
        if (n == 0) {
            out.put("summary", "Chưa có phiếu nào tương tự trong lịch sử – đây là ca mới.");
            out.put("confidence", "Chưa có dữ liệu");
            return out.with("parts", List.of()).with("diagnoses", List.of()).with("examples", List.of());
        }

        // Linh kiện: gom theo LOẠI linh kiện (Pin, Cáp màn hình, Cụm chân sạc…) để học được cả từ model khác,
        // rồi chọn đúng mã linh kiện của model đang xét nếu kho có.
        Set<String> vocab = new HashSet<>();
        for (Row x : db.query("SELECT p.name, b.name AS brand FROM product p JOIN brand b ON b.id = p.brand_id"))
            vocab.addAll(List.of(FaultService.normalize(x.str("name") + " " + x.str("brand")).split(" ")));
        List<Row> allParts = db.query("SELECT id, code, name, unit, stock, sell_price FROM part");
        Map<String, Row> kinds = new LinkedHashMap<>();
        for (Row t : similar) {
            Set<String> seen = new HashSet<>();
            for (Row p : db.query("SELECT pt.name FROM ticket_part tp JOIN part pt ON pt.id = tp.part_id WHERE tp.ticket_id = ?", t.lng("id"))) {
                String k = kind(p.str("name"), vocab);
                if (!seen.add(k)) continue;
                Row agg = kinds.computeIfAbsent(k, x -> new Row().with("kind", x).with("tickets", 0L).with("same_model", 0L).with("w", 0.0));
                agg.put("tickets", agg.lng("tickets") + 1);
                agg.put("w", ((Number) agg.get("w")).doubleValue() + ((Number) t.get("score")).doubleValue());
                if (Boolean.TRUE.equals(t.get("same_model"))) agg.put("same_model", agg.lng("same_model") + 1);
            }
        }
        double wTotal = similar.stream().mapToDouble(x -> ((Number) x.get("score")).doubleValue()).sum();
        Set<String> prodTokens = new HashSet<>(List.of(FaultService.normalize(dev.str("product")).split(" ")));
        List<Row> partList = new ArrayList<>();
        for (Row k : kinds.values()) {
            k.put("pct", Math.round(((Number) k.remove("w")).doubleValue() * 100.0 / wTotal));   // tỷ lệ có trọng số theo độ giống
            Row best = null;
            double bestScore = 0;
            for (Row p : allParts) {
                if (!kind(p.str("name"), vocab).equals(k.str("kind"))) continue;
                List<String> extra = new ArrayList<>(List.of(FaultService.normalize(p.str("name")).split(" ")));
                extra.removeAll(List.of(FaultService.normalize(k.str("kind")).split(" ")));
                double score;
                if (extra.isEmpty()) score = 0.5;                     // linh kiện dùng chung (vd keo tản nhiệt)
                else {
                    long hit = extra.stream().filter(prodTokens::contains).count();
                    score = hit == 0 ? 0 : hit - 0.4 * (extra.size() - hit);
                }
                if (score > bestScore) { bestScore = score; best = p; }
            }
            if (best != null) {
                k.put("part_id", best.lng("id"));
                k.put("code", best.str("code"));
                k.put("name", best.str("name"));
                k.put("unit", best.str("unit"));
                k.put("stock", best.lng("stock"));
                k.put("sell_price", best.lng("sell_price"));
            } else k.put("name", k.str("kind") + " (kho chưa có mã cho model này)");
            partList.add(k);
        }
        partList.sort((a, b) -> Long.compare(b.lng("pct"), a.lng("pct")));
        if (partList.size() > 5) partList = new ArrayList<>(partList.subList(0, 5));
        out.put("parts", partList);

        // Kết luận kỹ thuật hay gặp: gom theo phần "cách xử lý" (sau dấu –), có trọng số theo độ giống
        Map<String, double[]> diag = new LinkedHashMap<>();
        Map<String, String> diagText = new LinkedHashMap<>();
        for (Row t : similar) {
            String d = t.str("diagnosis").trim();
            if (d.isEmpty()) continue;
            int dash = Math.max(d.lastIndexOf('–'), d.lastIndexOf(" - "));
            String action = (dash >= 0 ? d.substring(dash + 1) : d).replaceFirst("^[-–\\s]+", "").trim();
            if (action.isEmpty()) action = d;
            action = Character.toUpperCase(action.charAt(0)) + action.substring(1);
            String key = FaultService.normalize(action);
            double[] a = diag.computeIfAbsent(key, k -> new double[2]);
            a[0] += ((Number) t.get("score")).doubleValue();
            a[1]++;
            diagText.putIfAbsent(key, action);
        }
        List<Row> diagList = new ArrayList<>();
        diag.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0])).limit(3)
                .forEach(e -> diagList.add(new Row().with("text", diagText.get(e.getKey())).with("count", (long) e.getValue()[1])
                        .with("pct", Math.round(e.getValue()[0] * 100.0 / wTotal))));
        out.put("diagnoses", diagList);

        // Chi phí: ca có tính phí dùng số tiền thực thu; nếu không có, dùng giá trị sửa (nếu tính phí)
        List<Long> paid = new ArrayList<>(), value = new ArrayList<>();
        List<Double> days = new ArrayList<>();
        long rejected = 0;
        List<Row> sameModel = similar.stream().filter(x -> Boolean.TRUE.equals(x.get("same_model"))).toList();
        List<Row> costCases = sameModel.size() >= 2 ? sameModel : similar;
        for (Row t : similar) if ("REJECTED".equals(t.str("status"))) rejected++;
        for (Row t : costCases) {
            if ("REJECTED".equals(t.str("status"))) continue;
            if (t.lng("in_warranty") == 0 && t.lng("total") > 0) paid.add(t.lng("total"));
            if (t.lng("value") > 0) value.add(t.lng("value"));
            if (t.get("days") != null) days.add(((Number) t.get("days")).doubleValue());
        }
        // Chi phí ước tính (nếu tính phí) = giá linh kiện hay thay nhất + tiền công thường gặp; không có linh kiện chính thì lấy
        // trung vị số tiền các ca có phí.
        List<Long> labors = new ArrayList<>();
        for (Row t : similar) if (!"REJECTED".equals(t.str("status")) && t.lng("labor_cost") > 0) labors.add(t.lng("labor_cost"));
        labors.sort(null);
        long labor = labors.isEmpty() ? 200_000 : pct(labors, 0.5);
        Row mainPart = partList.isEmpty() || partList.get(0).lng("pct") < 35 || partList.get(0).get("sell_price") == null ? null : partList.get(0);
        if (mainPart != null) {
            out.put("cost_mid", round(mainPart.lng("sell_price") + labor));
            out.put("cost_basis", "Giá " + mainPart.str("name") + " " + Fmt.money(mainPart.lng("sell_price")) + " + tiền công thường gặp " + Fmt.money(labor));
        } else if (!paid.isEmpty()) {
            paid.sort(null);
            out.put("cost_mid", round(pct(paid, 0.5)));
            out.put("cost_basis", "Trung vị số tiền khách đã trả ở " + paid.size() + " ca có phí" + (costCases == sameModel ? " cùng model" : " tương tự"));
        } else if (!value.isEmpty()) {
            value.sort(null);
            out.put("cost_mid", round(pct(value, 0.5)));
            out.put("cost_basis", "Giá trị tiền công + linh kiện các ca tương tự");
        }
        if (!days.isEmpty()) {
            days.sort(null);
            out.put("days_mid", Math.max(1, Math.round(days.get(days.size() / 2))));
        }
        out.put("rejected_pct", Math.round(rejected * 100.0 / n));
        out.put("in_warranty", dev.lng("in_warranty") == 1);

        // Mức tin cậy
        Row top = partList.isEmpty() ? null : partList.get(0);
        String conf = n >= 5 && top != null && top.lng("pct") >= 50 ? "Cao" : n >= 2 ? "Trung bình" : "Thấp";
        out.put("confidence", conf);

        // Câu tóm tắt
        StringBuilder sb = new StringBuilder(dev.str("product") + " báo “" + shorten(issue, 40) + "”: dựa trên " + n + " phiếu tương tự");
        if (top != null) sb.append(", ").append(top.lng("pct")).append("% đã thay ").append(top.str("name"));
        else if (!diagList.isEmpty()) sb.append(", thường gặp: ").append(shorten(diagList.get(0).str("text"), 50));
        if (out.get("cost_mid") != null) sb.append(", chi phí khoảng ").append(Fmt.money(out.lng("cost_mid")));
        if (out.get("days_mid") != null) sb.append(", sửa mất ~").append(out.lng("days_mid")).append(" ngày");
        sb.append('.');
        if (rejected * 100 >= 30L * n) sb.append(" Lưu ý: ").append(out.lng("rejected_pct")).append("% ca tương tự bị từ chối bảo hành – kiểm tra kỹ dấu hiệu vào nước / rơi vỡ.");
        out.put("summary", sb.toString());

        List<Row> ex = new ArrayList<>();
        for (Row t : similar.subList(0, Math.min(5, n))) {
            List<Row> ps = db.query("SELECT pt.name, tp.qty FROM ticket_part tp JOIN part pt ON pt.id = tp.part_id WHERE tp.ticket_id = ?", t.lng("id"));
            ex.add(new Row().with("id", t.lng("id")).with("code", t.str("code")).with("product", t.str("product")).with("issue", t.str("issue"))
                    .with("diagnosis", t.str("diagnosis")).with("status", t.str("status"))
                    .with("parts", String.join(", ", ps.stream().map(p -> p.str("name") + (p.lng("qty") > 1 ? " ×" + p.lng("qty") : "")).toList()))
                    .with("total", t.lng("total")).with("in_warranty", t.lng("in_warranty") == 1).with("score", t.get("score")));
        }
        out.put("examples", ex);
        return out;
    }

    /** Loại linh kiện = phần đầu tên, trước tên hãng / model (vd "Pin iPhone 15 Pro Max" → "Pin"). */
    static String kind(String partName, Set<String> vocab) {
        String[] words = partName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            String w = FaultService.normalize(words[i]);
            if (w.isEmpty() || vocab.contains(w) || w.matches(".*\\d.*")) break;
            sb.append(' ').append(words[i]);
        }
        return sb.toString();
    }

    private static long pct(List<Long> sorted, double q) {
        double idx = q * (sorted.size() - 1);
        int lo = (int) Math.floor(idx), hi = (int) Math.ceil(idx);
        return Math.round(sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (idx - lo));
    }

    private static long round(long v) {
        return v < 100_000 ? Math.round(v / 1000.0) * 1000 : Math.round(v / 10_000.0) * 10_000;
    }

    private static String shorten(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
