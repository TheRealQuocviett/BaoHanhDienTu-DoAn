package vn.baohanh.print;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import vn.baohanh.db.Row;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.service.AppException;
import vn.baohanh.service.SettingService;
import vn.baohanh.service.WarrantyService;
import vn.baohanh.util.Fmt;
import vn.baohanh.util.QrCode;

/**
 * Mẫu phiếu in (HTML khổ A5, in thẳng từ trình duyệt):
 *  - tiep-nhan : Phiếu tiếp nhận bảo hành (đưa khách khi nhận máy)
 *  - tra-may   : Phiếu trả máy / Hóa đơn sửa chữa
 *  - bao-hanh  : Phiếu bảo hành điện tử (đưa khách khi mua hàng)
 * Mỗi phiếu có mã QR dẫn tới cổng tra cứu.
 * Đường dẫn in được ký HMAC, có hạn 30 phút, nên mở được từ Swing mà không cần đăng nhập web.
 */
public class PrintService {
    private static final long LINK_TTL_MS = 30 * 60 * 1000;
    private static final User SYSTEM = new User(0, "system", "Hệ thống", Role.ADMIN);

    private final WarrantyService warranty;
    private final SettingService settings;
    private final vn.baohanh.service.PhotoService photos;
    private final byte[] secret = new byte[32];

    public PrintService(WarrantyService warranty, SettingService settings, vn.baohanh.service.PhotoService photos) {
        this.warranty = warranty;
        this.settings = settings;
        this.photos = photos;
        new SecureRandom().nextBytes(secret);
    }

    // ===================== Liên kết có chữ ký =====================

    /** Đường dẫn tương đối, vd /in/tiep-nhan/12?exp=...&sig=... */
    public String link(User u, String kind, long id) {
        if (u == null) throw new AppException(401, "Vui lòng đăng nhập");
        if (!List.of("tiep-nhan", "tra-may", "bao-hanh", "dat-hang").contains(kind)) throw new AppException("Mẫu phiếu không hợp lệ");
        if ("dat-hang".equals(kind)) vn.baohanh.service.Auth.need(u, vn.baohanh.model.Perm.PURCHASE_VIEW);
        long exp = System.currentTimeMillis() + LINK_TTL_MS;
        return "/in/" + kind + "/" + id + "?exp=" + exp + "&sig=" + sign(kind + ":" + id + ":" + exp);
    }

    private vn.baohanh.service.PurchaseService purchases;

    public void setPurchases(vn.baohanh.service.PurchaseService p) { this.purchases = p; }

    /** Phiếu đặt hàng gửi nhà cung cấp. */
    @SuppressWarnings("unchecked")
    private String purchaseOrder(Row o, Map<String, String> shop, String base) {
        StringBuilder lines = new StringBuilder();
        int i = 0;
        for (Row l : (List<Row>) o.get("lines"))
            lines.append("<tr><td>").append(++i).append("</td><td>").append(h(l.str("name"))).append("<br><small>").append(h(l.str("code")))
                 .append("</small></td><td class=\"r\">").append(l.lng("qty")).append(" ").append(h(l.str("unit")))
                 .append("</td><td class=\"r\">").append(Fmt.money(l.lng("unit_cost"))).append("</td><td class=\"r\">")
                 .append(Fmt.money(l.lng("amount"))).append("</td></tr>");
        String body = "<section class=\"grid\">" + kv("Nhà cung cấp", o.str("supplier_name")) + kv("Người liên hệ", o.str("supplier_contact"))
                + kv("Điện thoại", o.str("supplier_phone")) + kv("Email", o.str("supplier_email"))
                + kv("Ngày lập", Fmt.dateTime(o.str("created_at"))) + kv("Ngày cần hàng", o.str("expected_date").isEmpty() ? "Sớm nhất có thể" : Fmt.date(o.str("expected_date")))
                + kv("Người lập", o.str("created_by_name")) + kv("Trạng thái", o.str("status_label")) + "</section>"
                + "<table class=\"lines\"><thead><tr><th>#</th><th>Linh kiện</th><th class=\"r\">Số lượng</th><th class=\"r\">Đơn giá</th><th class=\"r\">Thành tiền</th></tr></thead>"
                + "<tbody>" + lines + "</tbody><tfoot><tr><td colspan=\"4\" class=\"r\">TỔNG CỘNG</td><td class=\"r\">" + Fmt.money(o.lng("total"))
                + "</td></tr></tfoot></table>"
                + (o.str("note").isEmpty() ? "" : "<p><b>Ghi chú:</b> " + h(o.str("note")) + "</p>")
                + "<p class=\"policy\">Đề nghị nhà cung cấp xác nhận số lượng, đơn giá và ngày giao. Hàng giao kèm hóa đơn / phiếu xuất kho ghi mã đơn "
                + h(o.str("code")) + ".</p>" + signatures("Nhà cung cấp xác nhận", "Người lập đơn");
        return page("ĐƠN ĐẶT HÀNG LINH KIỆN", shop, o.str("code"), o.str("code"), "Mã đơn", body);
    }

    /** Liên kết cho khách (quyền đã được kiểm tra ở CustomerService.checkPrint). */
    public String linkFor(String kind, long id) {
        if (!List.of("tra-may", "bao-hanh").contains(kind)) throw new AppException("Mẫu phiếu không hợp lệ");
        long exp = System.currentTimeMillis() + LINK_TTL_MS;
        return "/in/" + kind + "/" + id + "?exp=" + exp + "&sig=" + sign(kind + ":" + id + ":" + exp);
    }

    public boolean verify(String kind, long id, String exp, String sig) {
        try {
            if (Long.parseLong(exp) < System.currentTimeMillis()) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        String expected = sign(kind + ":" + id + ":" + exp);
        return sig != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ===================== Dựng HTML =====================

    public String render(String kind, long id) throws SQLException {
        Map<String, String> shop = settings.all();
        String base = settings.portalBase();
        return switch (kind) {
            case "tiep-nhan" -> receipt(warranty.getTicket(SYSTEM, id), shop, base, photoStrip(id));
            case "tra-may" -> returnSlip(warranty.getTicket(SYSTEM, id), shop, base);
            case "bao-hanh" -> certificate(warranty.getWarranty(SYSTEM, id), shop, base);
            case "dat-hang" -> purchaseOrder(purchases.get(SYSTEM, id), shop, base);
            default -> throw new AppException("Mẫu phiếu không hợp lệ");
        };
    }

    private static String h(Object o) { return Fmt.html(o); }

    /** Khối mã VietQR chuyển khoản (chỉ khi phiếu còn phải trả và cửa hàng đã cài tài khoản ngân hàng). */
    private String vietQrBlock(Row t) {
        try {
            Row q = warranty.vietQr(t.lng("id"));
            if (!Boolean.TRUE.equals(q.get("available"))) return "";
            return "<section style=\"display:flex;gap:14px;align-items:center;border:1px dashed #94a3b8;border-radius:8px;padding:8px 12px;margin:8px 0\">"
                    + "<div style=\"width:120px;flex:none\">" + q.str("svg") + "</div><div><b>Quét mã để chuyển khoản (VietQR)</b><br>"
                    + h(q.str("bank")) + " · STK <b>" + h(q.str("account")) + "</b>" + (q.str("holder").isEmpty() ? "" : " · " + h(q.str("holder")))
                    + "<br>Số tiền: <b>" + Fmt.money(q.lng("amount")) + "</b> · Nội dung: <b>" + h(q.str("info")) + "</b>"
                    + "<br><small>Mở app ngân hàng → Quét QR. Số tiền và nội dung được điền sẵn.</small></div></section>";
        } catch (Exception e) {
            return "";
        }
    }

    private String page(String title, Map<String, String> shop, String code, String qrText, String qrCaption, String body) {
        String qr = QrCode.encode(qrText).toSvg(2);
        return "<!DOCTYPE html><html lang=\"vi\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + h(title) + " " + h(code) + "</title><style>" + CSS + "</style></head><body>"
                + "<div class=\"bar\"><button onclick=\"window.print()\">In / Lưu PDF</button><span>Khổ A5 · muốn lưu file: chọn máy in \"Lưu dưới dạng PDF\"</span></div>"
                + "<main class=\"sheet\"><header><div><div class=\"shop\">" + h(shop.get("shop_name")) + "</div>"
                + "<div class=\"muted\">" + h(shop.get("shop_address")) + "</div><div class=\"muted\">ĐT: " + h(shop.get("shop_phone")) + "</div></div>"
                + "<figure class=\"qr\">" + qr + "<figcaption>" + h(qrCaption) + "</figcaption></figure></header>"
                + "<h1>" + h(title) + "</h1><div class=\"code\">Số: <b>" + h(code) + "</b></div>"
                + body + "</main><script>if(location.search.indexOf('auto=1')>=0)setTimeout(function(){window.print()},300)</script></body></html>";
    }

    private static String kv(String label, Object value) {
        return "<div class=\"kv\"><span>" + h(label) + "</span><b>" + h(value) + "</b></div>";
    }

    private static String signatures(String left, String right) {
        return "<div class=\"sign\"><div><b>" + h(left) + "</b><i>(Ký, ghi rõ họ tên)</i></div><div><b>" + h(right) + "</b><i>(Ký, ghi rõ họ tên)</i></div></div>";
    }

    /** Ảnh tình trạng máy nhúng thẳng vào phiếu (data URI), tối đa 6 ảnh. */
    private String photoStrip(long ticketId) throws SQLException {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Row p : photos.list(ticketId)) {
            if (n++ >= 6) break;
            try {
                Object[] img = photos.readInternal(p.lng("id"));
                sb.append("<img src=\"data:").append(img[0]).append(";base64,")
                  .append(Base64.getEncoder().encodeToString((byte[]) img[1])).append("\" alt=\"Ảnh tình trạng máy\">");
            } catch (Exception ignored) {
                // file ảnh bị mất thì bỏ qua
            }
        }
        return n == 0 ? "" : "<div class=\"photos\"><span>Ảnh tình trạng máy khi nhận</span><div>" + sb + "</div></div>";
    }

    /** Mã vạch Code 128 của serial: dán lên máy / phiếu, lần sau bấm "Quét" là đọc lại được. */
    private static String serialBarcode(String serial) {
        try {
            return "<div style=\"margin:10px 0 4px;text-align:center\"><div style=\"display:inline-block;width:min(100%," + (60 + serial.length() * 22)
                    + "px);height:46px\">" + vn.baohanh.util.Barcode.svg128(serial, 60).replace("<svg ", "<svg style=\"width:100%;height:100%\" ")
                    + "</div><div style=\"font:12px monospace;letter-spacing:2px\">" + h(serial) + "</div></div>";
        } catch (IllegalArgumentException e) {
            return "";                                         // serial có ký tự ngoài ASCII
        }
    }

    private String receipt(Row t, Map<String, String> shop, String base, String photoHtml) {
        Row w = (Row) t.get("warranty");
        boolean inW = Boolean.TRUE.equals(t.get("in_warranty"));
        String body = "<section class=\"grid\">"
                + kv("Ngày nhận", Fmt.dateTime(t.str("received_at"))) + kv("Hẹn trả", Fmt.date(t.str("due_date")))
                + kv("Khách hàng", t.str("customer_name")) + kv("Điện thoại", t.str("customer_phone"))
                + (t.str("customer_address").isEmpty() ? "" : "<div class=\"kv\" style=\"grid-column:1/-1\"><span>Địa chỉ</span><b>" + h(t.str("customer_address")) + "</b></div>")
                + kv("Sản phẩm", t.str("product_name")) + kv("Serial / IMEI", t.str("serial"))
                + kv("Mã bảo hành", w.str("code")) + kv("Hạn bảo hành", Fmt.date(w.str("expiry_date")))
                + "</section>"
                + serialBarcode(t.str("serial"))
                + "<div class=\"badge " + (inW ? "ok" : "warn") + "\">" + (inW ? "TRONG THỜI HẠN BẢO HÀNH" : "HẾT HẠN BẢO HÀNH – SỬA CHỮA CÓ TÍNH PHÍ") + "</div>"
                + "<table><tr><th>Lỗi khách báo</th><td>" + h(t.str("issue")) + "</td></tr>"
                + "<tr><th>Phụ kiện kèm theo</th><td>" + h(t.str("accessories").isEmpty() ? "Không" : t.str("accessories")) + "</td></tr>"
                + "<tr><th>Tình trạng ngoại quan</th><td>" + h(t.str("condition_note")) + "</td></tr>"
                + "<tr><th>Nhân viên tiếp nhận</th><td>" + h(t.str("received_by_name")) + "</td></tr></table>"
                + photoHtml
                + "<p class=\"policy\">" + h(shop.get("policy")) + "</p>"
                + "<p class=\"policy\">Tra cứu tiến độ sửa chữa: quét mã QR hoặc vào <b>" + h(base) + "/tra-cuu</b> và nhập mã phiếu.</p>"
                + signatures("Khách hàng", "Nhân viên tiếp nhận");
        return page("PHIẾU TIẾP NHẬN BẢO HÀNH", shop, t.str("code"), base + "/tra-cuu#" + t.str("code"), "Quét để tra cứu", body);
    }

    @SuppressWarnings("unchecked")
    private String returnSlip(Row t, Map<String, String> shop, String base) {
        StringBuilder parts = new StringBuilder();
        int i = 1;
        for (Row p : (List<Row>) t.get("parts")) {
            boolean charged = p.bool("charged");
            parts.append("<tr><td>").append(i++).append("</td><td>").append(h(p.str("name"))).append("</td><td class=\"r\">")
                 .append(p.lng("qty")).append("</td><td class=\"r\">").append(Fmt.money(p.lng("unit_price"))).append("</td><td class=\"r\">")
                 .append(charged ? Fmt.money(p.lng("amount")) : "Miễn phí BH").append("</td></tr>");
        }
        long labor = t.lng("labor_cost");
        parts.append("<tr><td>").append(i).append("</td><td>Tiền công sửa chữa</td><td class=\"r\">1</td><td class=\"r\">")
             .append(Fmt.money(labor)).append("</td><td class=\"r\">").append(Fmt.money(labor)).append("</td></tr>");
        boolean returned = "RETURNED".equals(t.str("status"));
        String pay = returned
                ? kv("Ngày trả máy", Fmt.dateTime(t.str("returned_at"))) + kv("Hình thức thanh toán", t.lng("paid_amount") > 0 ? t.str("payment_label") : "Không thu phí")
                  + kv("Số hóa đơn", t.str("invoice_no").isEmpty() ? "—" : t.str("invoice_no")) + kv("Đã thu", Fmt.money(t.lng("paid_amount")))
                : kv("Trạng thái", t.str("status_label"));
        boolean rejected = false;
        for (Row hrow : (List<Row>) t.get("history")) if ("REJECTED".equals(hrow.str("status"))) rejected = true;
        String result = rejected ? "Từ chối bảo hành / không sửa" : "Đã sửa xong, kiểm tra hoạt động bình thường";
        String body = "<section class=\"grid\">"
                + kv("Mã phiếu nhận", t.str("code")) + kv("Ngày nhận", Fmt.date(t.str("received_at")))
                + kv("Khách hàng", t.str("customer_name")) + kv("Điện thoại", t.str("customer_phone"))
                + (t.str("customer_address").isEmpty() ? "" : "<div class=\"kv\" style=\"grid-column:1/-1\"><span>Địa chỉ</span><b>" + h(t.str("customer_address")) + "</b></div>")
                + kv("Sản phẩm", t.str("product_name")) + kv("Serial / IMEI", t.str("serial"))
                + kv("Kỹ thuật viên", t.str("technician_name")) + kv("Kết quả", result)
                + "</section>"
                + "<table><tr><th>Lỗi khách báo</th><td>" + h(t.str("issue")) + "</td></tr>"
                + "<tr><th>Kết luận kỹ thuật</th><td>" + h(t.str("diagnosis")) + "</td></tr></table>"
                + "<table class=\"lines\"><thead><tr><th>#</th><th>Nội dung</th><th class=\"r\">SL</th><th class=\"r\">Đơn giá</th><th class=\"r\">Thành tiền</th></tr></thead>"
                + "<tbody>" + parts + "</tbody><tfoot><tr><td colspan=\"4\" class=\"r\">TỔNG THANH TOÁN</td><td class=\"r\">"
                + Fmt.money(t.lng("total")) + "</td></tr></tfoot></table>"
                + "<section class=\"grid\">" + pay + "</section>"
                + vietQrBlock(t)
                + "<p class=\"policy\">Linh kiện thay thế được bảo hành 3 tháng kể từ ngày trả máy. Vui lòng kiểm tra máy trước khi rời cửa hàng.</p>"
                + signatures("Khách hàng", "Nhân viên trả máy");
        String title = t.lng("total") > 0 ? "PHIẾU TRẢ MÁY – HÓA ĐƠN SỬA CHỮA" : "PHIẾU TRẢ MÁY BẢO HÀNH";
        return page(title, shop, t.str("code"), base + "/tra-cuu#" + t.str("code"), "Lịch sử bảo hành", body);
    }

    @SuppressWarnings("unchecked")
    private String certificate(Row w, Map<String, String> shop, String base) {
        StringBuilder ext = new StringBuilder();
        for (Row e : (List<Row>) w.get("extensions"))
            ext.append("<tr><td>").append(h(e.str("code"))).append("</td><td>").append(Fmt.date(e.str("created_at"))).append("</td><td class=\"r\">+")
               .append(e.lng("months")).append(" tháng</td><td>").append(Fmt.date(e.str("old_expiry"))).append(" → <b>")
               .append(Fmt.date(e.str("new_expiry"))).append("</b></td><td class=\"r\">").append(Fmt.money(e.lng("price"))).append("</td></tr>");
        String extHtml = ext.length() == 0 ? "" : "<table class=\"lines\"><thead><tr><th>Gói BH mở rộng</th><th>Ngày mua</th><th class=\"r\">Thêm</th>"
                + "<th>Hạn bảo hành</th><th class=\"r\">Giá</th></tr></thead><tbody>" + ext + "</tbody></table>";
        String body = "<section class=\"grid\">"
                + kv("Sản phẩm", w.str("product_name")) + kv("Mã model", w.str("model_code"))
                + kv("Hãng", w.str("brand_name")) + kv("Serial / IMEI", w.str("serial"))
                + kv("Khách hàng", w.str("customer_name")) + kv("Điện thoại", w.str("customer_phone"))
                + kv("Số hóa đơn", w.str("invoice_no").isEmpty() ? "—" : w.str("invoice_no")) + kv("Ngày mua", Fmt.date(w.str("purchase_date")))
                + kv("Thời hạn", w.lng("months") + " tháng") + kv("Bảo hành đến hết ngày", Fmt.date(w.str("expiry_date")))
                + "</section>"
                + "<div class=\"badge ok\">BẢO HÀNH ĐIỆN TỬ ĐÃ KÍCH HOẠT</div>" + extHtml
                + "<p class=\"policy\">Sản phẩm được bảo hành miễn phí các lỗi kỹ thuật do nhà sản xuất trong thời hạn trên. "
                + "Không bảo hành các trường hợp rơi vỡ, vào nước, cháy nổ, tự ý tháo sửa hoặc tem bảo hành bị rách.</p>"
                + "<p class=\"policy\">Khách không cần giữ phiếu giấy: chỉ cần đọc số Serial/IMEI hoặc số điện thoại tại quầy, "
                + "hoặc quét mã QR để xem hạn bảo hành bất cứ lúc nào.</p>"
                + signatures("Khách hàng", "Nhân viên bán hàng");
        return page("PHIẾU BẢO HÀNH ĐIỆN TỬ", shop, w.str("code"), base + "/tra-cuu#" + w.str("serial"), "Quét để xem hạn BH", body);
    }

    private static final String CSS = """
        @page{size:A5;margin:10mm}
        *{box-sizing:border-box}
        body{margin:0;background:#f3f6fb;color:#0f172a;font:13px/1.45 "Segoe UI",Roboto,Arial,sans-serif}
        .bar{display:flex;gap:12px;align-items:center;justify-content:center;padding:12px;background:#1e3a8a;color:#dbe6ff;font-size:13px}
        .bar button{font:inherit;font-weight:600;background:#2563eb;color:#fff;border:0;border-radius:6px;padding:9px 18px;cursor:pointer}
        .sheet{background:#fff;width:148mm;min-height:210mm;margin:16px auto;padding:10mm;box-shadow:0 2px 12px #0002}
        header{display:flex;justify-content:space-between;gap:12px;border-bottom:2px solid #2563eb;padding-bottom:8px}
        .shop{font-size:17px;font-weight:700}
        .muted{color:#555;font-size:12px}
        .qr{margin:0;text-align:center;width:30mm;flex:none}.qr svg{width:30mm;height:30mm;display:block}
        .qr figcaption{font-size:10px;color:#555}
        h1{text-align:center;font-size:18px;margin:12px 0 2px;letter-spacing:.02em}
        .code{text-align:center;margin-bottom:10px}
        .grid{display:grid;grid-template-columns:1fr 1fr;gap:4px 14px;margin:8px 0}
        .kv{display:flex;flex-direction:column;border-bottom:1px dotted #bbb;padding:2px 0}
        .kv span{font-size:11px;color:#555}.kv b{font-weight:600}
        .badge{text-align:center;font-weight:700;padding:6px;border:2px solid;margin:10px 0;border-radius:4px}
        .badge.ok{color:#0f7b45}.badge.warn{color:#c4320a}
        table{width:100%;border-collapse:collapse;margin:8px 0}
        th,td{border:1px solid #999;padding:5px 7px;text-align:left;vertical-align:top}
        th{background:#f1f1f1;font-weight:600;width:34%}
        .lines th{width:auto}.r{text-align:right;white-space:nowrap}
        tfoot td{font-weight:700;background:#f1f1f1}
        .policy{font-size:11px;color:#333;margin:6px 0}
        .photos span{font-size:11px;color:#555}.photos div{display:grid;grid-template-columns:repeat(3,1fr);gap:4px;margin:4px 0 8px}
        .photos img{width:100%;height:28mm;object-fit:cover;border:1px solid #999}
        .sign{display:grid;grid-template-columns:1fr 1fr;text-align:center;margin-top:14px;min-height:28mm}
        .sign b{display:block}.sign i{font-size:11px;color:#555}
        @media print{body{background:#fff}.bar{display:none}.sheet{margin:0;box-shadow:none;width:auto;min-height:0;padding:0}}
        """;
}
