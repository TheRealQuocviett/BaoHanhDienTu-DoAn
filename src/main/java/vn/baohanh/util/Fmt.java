package vn.baohanh.util;

/** Định dạng hiển thị dùng chung (tiền, ngày, escape HTML). */
public final class Fmt {
    private Fmt() {}

    /** 1234567 -> "1.234.567 đ" */
    public static String money(long v) {
        String s = String.format("%,d", Math.abs(v)).replace(',', '.');
        return (v < 0 ? "-" : "") + s + " đ";
    }

    public static String number(long v) {
        return String.format("%,d", v).replace(',', '.');
    }

    /** "2026-11-23" hoặc "2026-11-23T10:15:00" -> "23/11/2026" */
    public static String date(String iso) {
        if (iso == null || iso.length() < 10) return "";
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }

    /** "2026-11-23T10:15:00" -> "23/11/2026 10:15" */
    public static String dateTime(String iso) {
        if (iso == null || iso.length() < 16) return date(iso);
        return date(iso) + " " + iso.substring(11, 16);
    }

    public static String html(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
