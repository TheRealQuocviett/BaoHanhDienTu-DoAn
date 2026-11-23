package vn.baohanh.db;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/**
 * Một dòng kết quả truy vấn: tên cột -> giá trị.
 * Số nguyên luôn là Long, chuỗi là String, NULL là null.
 * Kế thừa LinkedHashMap nên có thể trả thẳng ra JSON cho giao diện web.
 */
public class Row extends LinkedHashMap<String, Object> {
    private static final long serialVersionUID = 1L;

    public String str(String k) {
        Object v = get(k);
        return v == null ? "" : String.valueOf(v);
    }

    public long lng(String k) {
        Object v = get(k);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) return Long.parseLong(s.trim());
        return 0;
    }

    public int integer(String k) {
        return (int) lng(k);
    }

    public boolean bool(String k) {
        if (get(k) instanceof Boolean b) return b;
        return lng(k) != 0;
    }

    public LocalDate date(String k) {
        String s = str(k);
        return s.isEmpty() ? null : LocalDate.parse(s.substring(0, 10));
    }

    public LocalDateTime dateTime(String k) {
        String s = str(k);
        return s.isEmpty() ? null : LocalDateTime.parse(s);
    }

    public boolean isNull(String k) {
        return get(k) == null;
    }

    /** Gán và trả về chính nó (viết gọn). */
    public Row with(String k, Object v) {
        put(k, v);
        return this;
    }
}
