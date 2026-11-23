package vn.baohanh.notify;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import vn.baohanh.util.Json;

/**
 * Gửi SMS qua eSMS.vn và tin Zalo ZNS / ZBS qua Zalo Business API.
 *
 * eSMS: POST x-www-form-urlencoded tới .../SendMultipleMessage_V4_post/ với Phone, Content, ApiKey,
 *       SecretKey, SmsType=2 (chăm sóc khách hàng), Brandname. Thành công khi CodeResult = "100".
 *       Lưu ý: nội dung phải khớp mẫu tin brandname đã đăng ký với eSMS.
 * Zalo: POST JSON {phone, template_id, template_data, tracking_id}, header access_token.
 *       Thành công khi "error" = 0. Mẫu tin phải được Zalo duyệt trước; chỉ gửi trong 6h–22h.
 *
 * Địa chỉ API có thể đổi bằng thuộc tính hệ thống baohanh.esms.url / baohanh.zalo.url (dùng khi kiểm thử).
 */
public final class HttpSenders {
    public static final String ESMS_URL = "https://rest.esms.vn/MainService.svc/json/SendMultipleMessage_V4_post/";
    public static final String ZALO_URL = "https://business.openapi.zalo.me/message/template";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private HttpSenders() {}

    /** Gửi SMS chăm sóc khách hàng qua eSMS. Trả về SMSID. */
    public static String esms(String apiKey, String secretKey, String brandname, String phone, String content, boolean unicode)
            throws IOException, InterruptedException {
        if (blank(apiKey) || blank(secretKey) || blank(brandname)) throw new IOException("Chưa cấu hình eSMS (ApiKey, SecretKey, Brandname)");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Phone", phone);
        f.put("Content", content);
        f.put("ApiKey", apiKey.trim());
        f.put("SecretKey", secretKey.trim());
        f.put("SmsType", "2");
        f.put("Brandname", brandname.trim());
        f.put("IsUnicode", unicode ? "1" : "0");
        StringBuilder body = new StringBuilder();
        f.forEach((k, v) -> body.append(body.length() > 0 ? "&" : "").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(System.getProperty("baohanh.esms.url", ESMS_URL)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> res = call(req);
        Map<String, Object> j = parse(res);
        String code = Json.str(j, "CodeResult");
        if (!"100".equals(code)) throw new IOException("eSMS lỗi " + code + ": " + Json.str(j, "ErrorMessage"));
        return Json.str(j, "SMSID");
    }

    /** Gửi tin Zalo theo mẫu đã duyệt. Trả về msg_id. */
    public static String zalo(String accessToken, String templateId, String phone, Map<String, Object> data, String trackingId)
            throws IOException, InterruptedException {
        if (blank(accessToken)) throw new IOException("Chưa cấu hình Zalo access token");
        if (blank(templateId)) throw new IOException("Chưa nhập mã mẫu tin Zalo cho sự kiện này");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone", toZaloPhone(phone));
        body.put("template_id", templateId.trim());
        body.put("template_data", data);
        body.put("tracking_id", trackingId);
        HttpRequest req = HttpRequest.newBuilder(URI.create(System.getProperty("baohanh.zalo.url", ZALO_URL)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("access_token", accessToken.trim())
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> res = call(req);
        Map<String, Object> j = parse(res);
        long err = Json.num(j, "error", -1);
        if (err != 0) throw new IOException("Zalo lỗi " + err + ": " + Json.str(j, "message"));
        Object d = j.get("data");
        return d instanceof Map<?, ?> m && m.get("msg_id") != null ? String.valueOf(m.get("msg_id")) : "";
    }

    private static HttpResponse<String> call(HttpRequest req) throws IOException, InterruptedException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Không kết nối được " + req.uri().getHost() + " – kiểm tra mạng Internet"
                    + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(HttpResponse<String> res) throws IOException {
        if (res.statusCode() >= 500) throw new IOException("Máy chủ nhà cung cấp lỗi HTTP " + res.statusCode());
        try {
            Object o = Json.parse(res.body());
            if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (IllegalArgumentException ignored) {
            // rơi xuống báo lỗi bên dưới
        }
        throw new IOException("Phản hồi không hợp lệ (HTTP " + res.statusCode() + "): " + abbreviate(res.body()));
    }

    /** 0903123456 → 84903123456 (định dạng Zalo yêu cầu). */
    static String toZaloPhone(String phone) {
        String p = phone.replaceAll("\\D", "");
        return p.startsWith("0") ? "84" + p.substring(1) : p;
    }

    /** Bỏ dấu tiếng Việt (SMS không dấu rẻ hơn và ít bị nhà mạng chặn). */
    public static String removeAccents(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
