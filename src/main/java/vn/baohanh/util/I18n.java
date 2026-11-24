package vn.baohanh.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dịch thông báo lỗi của cổng khách hàng sang tiếng Anh (khi trình duyệt gửi header "X-Lang: en").
 * Nội dung chưa có bản dịch thì giữ nguyên tiếng Việt.
 */
public final class I18n {
    private I18n() {}

    private static final Map<String, String> EN = new LinkedHashMap<>();
    private record Rule(Pattern re, String en) {}
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("Mỗi yêu cầu tối đa (\\d+) ảnh"), "Up to $1 photos per request"),
            new Rule(Pattern.compile("Tin nhắn tối đa (\\d+) ký tự"), "Messages are limited to $1 characters"),
            new Rule(Pattern.compile("Bạn nhập sai quá nhiều lần, vui lòng thử lại sau (.+)"), "Too many failed attempts, please try again in $1"),
            new Rule(Pattern.compile("Không tìm thấy (.+)"), "Not found: $1"));

    static {
        String[][] t = {
            {"Sai số điện thoại hoặc mật khẩu. Chưa có tài khoản? Bấm \"Đăng ký\".", "Wrong phone number or password. No account yet? Click \"Register\"."},
            {"Bạn tra cứu quá nhiều lần, vui lòng thử lại sau 1 phút", "Too many lookups, please try again in 1 minute"},
            {"Vui lòng nhập số Serial/IMEI hoặc mã phiếu", "Please enter a serial/IMEI number or ticket code"},
            {"Số điện thoại và số Serial không khớp với máy nào đã mua tại cửa hàng", "This phone number and serial do not match any device bought at our store"},
            {"Số điện thoại không hợp lệ", "Invalid phone number"},
            {"Mật khẩu phải có ít nhất 6 ký tự", "Password must be at least 6 characters"},
            {"Mật khẩu hiện tại không đúng", "Current password is incorrect"},
            {"Vui lòng đăng nhập", "Please sign in"},
            {"Tài khoản không còn tồn tại", "This account no longer exists"},
            {"Email không hợp lệ", "Invalid email address"},
            {"Địa chỉ quá dài", "Address is too long"},
            {"Vui lòng mô tả lỗi của máy (ít nhất 5 ký tự)", "Please describe the problem (at least 5 characters)"},
            {"Mô tả quá dài", "Description is too long"},
            {"Ngày mong muốn không hợp lệ", "Invalid preferred date"},
            {"Ngày mong muốn không được ở quá khứ", "Preferred date cannot be in the past"},
            {"Chỉ đặt lịch trong vòng 60 ngày", "Appointments can be booked up to 60 days ahead"},
            {"Vui lòng chọn máy cần bảo hành", "Please choose a device"},
            {"Máy này đã có yêu cầu đang chờ xử lý", "This device already has a pending request"},
            {"Máy này đang được cửa hàng xử lý", "This device is already being serviced by the store"},
            {"Không thể hủy yêu cầu này", "This request can no longer be cancelled"},
            {"Yêu cầu đã được xử lý", "This request has already been processed"},
            {"Yêu cầu đã được xử lý, không thêm ảnh được", "This request has been processed, photos can no longer be added"},
            {"Vui lòng chọn từ 1 đến 5 sao", "Please choose 1 to 5 stars"},
            {"Nhận xét tối đa 500 ký tự", "Comments are limited to 500 characters"},
            {"Chỉ đánh giá được sau khi đã nhận lại máy", "You can review only after picking up your device"},
            {"Nội dung tin nhắn trống", "Message is empty"},
            {"Bạn nhắn quá nhanh, vui lòng chờ vài phút", "You are sending messages too fast, please wait a few minutes"},
            {"File ảnh trống", "The image file is empty"},
            {"Ảnh quá lớn (tối đa 8 MB)", "Image is too large (max 8 MB)"},
            {"Chỉ nhận ảnh JPG, PNG hoặc WebP", "Only JPG, PNG or WebP images are accepted"},
            {"Phiếu chưa đến bước thanh toán", "This ticket is not ready for payment yet"},
            {"Cách thanh toán không hợp lệ", "Invalid payment method"},
            {"Bạn không có quyền thực hiện chức năng này", "You are not allowed to do this"},
            {"Dữ liệu gửi lên quá lớn", "Request is too large"},
            {"Yêu cầu không hợp lệ", "Invalid request"},
            {"Cửa hàng chưa cài đặt tài khoản ngân hàng", "The store has not set up a bank account"},
            {"Phiếu đã thanh toán", "This ticket has been paid"},
            {"Phiếu không phải trả tiền", "Nothing to pay for this ticket"},
            {"Chưa đến bước thanh toán", "Not ready for payment yet"},
        };
        for (String[] p : t) EN.put(p[0], p[1]);
    }

    /** Bản tiếng Anh của một thông báo (không có thì trả lại nguyên văn). */
    public static String en(String vi) {
        if (vi == null) return null;
        String x = EN.get(vi);
        if (x != null) return x;
        for (Rule r : RULES) {
            Matcher m = r.re().matcher(vi);
            if (m.matches()) return m.replaceAll(r.en());
        }
        return vi;
    }
}
