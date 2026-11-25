package vn.baohanh.service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.User;
import vn.baohanh.util.Json;

/** Thông tin cửa hàng in trên phiếu và địa chỉ cổng tra cứu cho khách. */
public class SettingService {
    /** Khóa -> nhãn hiển thị. */
    public static final Map<String, String> KEYS = new LinkedHashMap<>();
    /** Gợi ý hiển thị dưới ô nhập. */
    public static final Map<String, String> HINTS = new LinkedHashMap<>();
    static {
        KEYS.put("shop_name", "Tên cửa hàng");
        KEYS.put("shop_address", "Địa chỉ");
        KEYS.put("shop_phone", "Điện thoại");
        KEYS.put("shop_email", "Email liên hệ");
        KEYS.put("open_hours", "Giờ mở cửa");
        KEYS.put("map_query", "Vị trí trên bản đồ");
        KEYS.put("social_link", "Trang Facebook / Zalo OA");
        KEYS.put("portal_intro", "Lời giới thiệu trên trang khách");
        KEYS.put("bank_bin", "Ngân hàng nhận chuyển khoản (VietQR)");
        KEYS.put("bank_account", "Số tài khoản");
        KEYS.put("bank_holder", "Tên chủ tài khoản");
        KEYS.put("kpi_commission_rate", "Hoa hồng kỹ thuật viên (% tiền công)");
        KEYS.put("kpi_bonus_per_ticket", "Thưởng mỗi phiếu hoàn thành (đồng)");
        KEYS.put("po_lead_days", "Thời gian chờ hàng về (ngày)");
        KEYS.put("po_cover_days", "Đặt đủ dùng trong (ngày)");
        KEYS.put("remind_due_days", "Nhắc kỹ thuật viên trước hạn trả (ngày)");
        KEYS.put("portal_url", "Địa chỉ cổng tra cứu");
        KEYS.put("policy", "Điều khoản / chính sách bảo hành");
        HINTS.put("bank_bin", "Chọn ngân hàng để in mã QR chuyển khoản trên hóa đơn và cho khách thanh toán online");
        HINTS.put("bank_holder", "Viết hoa không dấu như trên app ngân hàng, vd NGUYEN VAN A");
        HINTS.put("kpi_commission_rate", "Để trống = 10%. Tính trên tiền công các phiếu có phí đã thu");
        HINTS.put("kpi_bonus_per_ticket", "Để trống = 20.000 đ cho mỗi phiếu đã trả máy");
        HINTS.put("po_lead_days", "Để trống = 7. Dùng để dự báo: linh kiện hết trước khi hàng kịp về thì được gợi ý đặt sớm");
        HINTS.put("po_cover_days", "Để trống = 30. Số lượng gợi ý đủ dùng thêm chừng ấy ngày sau khi hàng về");
        HINTS.put("remind_due_days", "Để trống = 1 (nhắc trước 1 ngày). Phiếu quá hạn thì báo quản lý và tự gửi tin xin lỗi khách");
        HINTS.put("open_hours", "Mỗi dòng một mục, vd: Thứ 2 – Thứ 7: 8:00 – 20:00");
        HINTS.put("map_query", "Để trống = dùng địa chỉ cửa hàng. Có thể nhập tọa độ, vd 10.7626,106.6602");
        HINTS.put("social_link", "Đường dẫn bắt đầu bằng https://");
        HINTS.put("portal_url", "Để trống = tự động dùng địa chỉ mạng LAN của máy này");
        HINTS.put("policy", "In trên phiếu và hiện ở trang Cửa hàng của khách");
    }

    /** Các ô chọn từ danh sách: key → (giá trị → nhãn). */
    public static final Map<String, Map<String, String>> SELECTS = new LinkedHashMap<>();
    static {
        Map<String, String> banks = new LinkedHashMap<>();
        banks.put("", "— Không dùng VietQR —");
        vn.baohanh.util.VietQr.BANKS.forEach((k, v) -> banks.put(k, v + " (" + k + ")"));
        SELECTS.put("bank_bin", banks);
    }

    /** Các ô nhập nhiều dòng. */
    public static final java.util.Set<String> MULTILINE = java.util.Set.of("policy", "open_hours", "portal_intro");

    private final Database db;
    private final AuditService audit;
    private int port = 8080;

    public SettingService(Database db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    public void setPort(int port) { this.port = port; }

    public Map<String, String> all() throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : KEYS.keySet()) m.put(k, "");
        for (Row r : db.query("SELECT key, value FROM setting")) m.put(r.str("key"), r.str("value"));
        return m;
    }

    public String get(String key) throws SQLException {
        Row r = db.one("SELECT value FROM setting WHERE key = ?", key);
        return r == null ? "" : r.str("value");
    }

    public void put(String key, String value) throws SQLException {
        db.update("INSERT INTO setting (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
    }

    public Map<String, String> save(User u, Map<String, Object> in) throws SQLException {
        Auth.need(u, vn.baohanh.model.Perm.SETTINGS);
        db.tx(() -> {
            for (String k : KEYS.keySet()) if (in.containsKey(k)) put(k, Json.str(in, k));
            audit.log(u, "SỬA", "setting", null, "Thông tin cửa hàng");
            return null;
        });
        return all();
    }

    /** Địa chỉ cổng tra cứu in trong mã QR. Mặc định dùng IP mạng LAN để điện thoại trong cửa hàng quét được. */
    public String portalBase() throws SQLException {
        String cfg = get("portal_url").trim();
        if (!cfg.isEmpty()) return cfg.endsWith("/") ? cfg.substring(0, cfg.length() - 1) : cfg;
        return "http://" + lanAddress() + ":" + port;
    }

    public static String lanAddress() {
        // Ưu tiên card Wi-Fi / mạng dây thật; bỏ qua card ảo của VirtualBox, VMware, Hyper-V, WSL, VPN…
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
                boolean virtual = name.matches(".*(virtualbox|vmware|hyper-v|vethernet|wsl|docker|vpn|tap|tun|virtual|host-only|loopback|bluetooth).*");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (!virtual && !ip.startsWith("192.168.56.")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {
            // không lấy được thì dùng localhost
        }
        return fallback == null ? "localhost" : fallback;
    }
}
