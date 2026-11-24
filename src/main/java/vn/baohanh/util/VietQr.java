package vn.baohanh.util;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tạo chuỗi VietQR (chuẩn EMVCo QR thanh toán của NAPAS) để khách quét bằng app ngân hàng
 * và chuyển khoản đúng số tiền, đúng nội dung. Không cần Internet, không gọi API nào.
 *
 * Cấu trúc: mỗi trường = ID (2 số) + độ dài (2 số) + giá trị; cuối cùng là CRC16-CCITT (ID 63).
 */
public final class VietQr {
    private VietQr() { }

    /** Mã BIN (NAPAS) của một số ngân hàng phổ biến. */
    public static final Map<String, String> BANKS = new LinkedHashMap<>();
    static {
        BANKS.put("970436", "Vietcombank");
        BANKS.put("970415", "VietinBank");
        BANKS.put("970418", "BIDV");
        BANKS.put("970405", "Agribank");
        BANKS.put("970407", "Techcombank");
        BANKS.put("970422", "MB Bank");
        BANKS.put("970416", "ACB");
        BANKS.put("970432", "VPBank");
        BANKS.put("970423", "TPBank");
        BANKS.put("970403", "Sacombank");
        BANKS.put("970441", "VIB");
        BANKS.put("970437", "HDBank");
        BANKS.put("970443", "SHB");
        BANKS.put("970448", "OCB");
        BANKS.put("970426", "MSB");
        BANKS.put("970440", "SeABank");
        BANKS.put("970449", "LPBank");
        BANKS.put("970431", "Eximbank");
    }

    private static String f(String id, String value) {
        return id + String.format("%02d", value.length()) + value;
    }

    /** Nội dung chuyển khoản: bỏ dấu, chỉ giữ chữ, số, khoảng trắng; tối đa 25 ký tự. */
    public static String cleanInfo(String s) {
        String t = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D').replaceAll("[^A-Za-z0-9 ]", "").trim().replaceAll("\\s+", " ");
        return t.length() > 25 ? t.substring(0, 25) : t;
    }

    /**
     * @param bin     mã BIN ngân hàng (6 số)
     * @param account số tài khoản nhận
     * @param amount  số tiền (đồng); 0 = để khách tự nhập
     * @param info    nội dung chuyển khoản (vd mã phiếu)
     */
    public static String payload(String bin, String account, long amount, String info) {
        if (bin == null || !bin.matches("\\d{6}")) throw new IllegalArgumentException("Mã ngân hàng không hợp lệ");
        String acc = account == null ? "" : account.replaceAll("\\s", "");
        if (!acc.matches("[0-9A-Za-z]{4,19}")) throw new IllegalArgumentException("Số tài khoản không hợp lệ");
        String bank = f("00", bin) + f("01", acc);
        String merchant = f("00", "A000000727") + f("01", bank) + f("02", "QRIBFTTA");
        StringBuilder sb = new StringBuilder();
        sb.append(f("00", "01"));
        sb.append(f("01", amount > 0 ? "12" : "11"));                 // 12 = mã động (có số tiền), 11 = tĩnh
        sb.append(f("38", merchant));
        sb.append(f("53", "704"));                                    // VND
        if (amount > 0) sb.append(f("54", String.valueOf(amount)));
        sb.append(f("58", "VN"));
        String note = cleanInfo(info);
        if (!note.isEmpty()) sb.append(f("62", f("08", note)));
        sb.append("6304");
        return sb.append(crc16(sb.toString())).toString();
    }

    /** CRC16-CCITT (đa thức 0x1021, giá trị đầu 0xFFFF), in hoa 4 ký tự hex. */
    public static String crc16(String s) {
        int crc = 0xFFFF;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return String.format("%04X", crc);
    }

    /** Ảnh SVG của mã VietQR. */
    public static String svg(String bin, String account, long amount, String info, int scale) {
        return QrCode.encode(payload(bin, account, amount, info)).toSvg(scale);
    }
}
