package vn.baohanh.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mô tả một danh mục (bảng) để cả giao diện web và Swing tự dựng màn hình
 * thêm / sửa / xóa / tìm kiếm mà không phải viết riêng từng màn hình.
 */
public class EntityDef {

    public enum Type { TEXT, TEXTAREA, PHONE, EMAIL, INT, MONEY, REF, ENUM, BOOL, PASSWORD }

    /** Một trường dữ liệu. */
    public static class Field {
        public final String name;
        public final String label;
        public final Type type;
        public boolean required;
        public String ref;                    // REF: khóa của danh mục được tham chiếu
        public Map<String, String> options;   // ENUM: giá trị -> nhãn
        public long min = Long.MIN_VALUE, max = Long.MAX_VALUE;
        public boolean createOnly;            // chỉ nhập khi thêm mới (vd tồn kho ban đầu)
        public String defaultValue;

        public Field(String name, String label, Type type) {
            this.name = name;
            this.label = label;
            this.type = type;
        }

        public Field req() { required = true; return this; }
        public Field ref(String entity) { ref = entity; return this; }
        public Field range(long lo, long hi) { min = lo; max = hi; return this; }
        public Field createOnly() { createOnly = true; return this; }
        public Field def(String v) { defaultValue = v; return this; }
        public Field options(Map<String, String> o) { options = o; return this; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", label);
            m.put("type", type.name());
            m.put("required", required);
            if (ref != null) m.put("ref", ref);
            if (options != null) m.put("options", options);
            if (min != Long.MIN_VALUE) m.put("min", min);
            if (max != Long.MAX_VALUE) m.put("max", max);
            if (createOnly) m.put("createOnly", true);
            if (defaultValue != null) m.put("default", defaultValue);
            return m;
        }
    }

    /** Cột hiển thị trong bảng danh sách. */
    public record Column(String name, String label, String format) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("label", label);
            if (format != null) m.put("format", format);
            return m;
        }
    }

    public final String key;         // vd: customer
    public final String table;
    public final String title;       // vd: Khách hàng
    public final List<Field> fields = new ArrayList<>();
    public final List<Column> columns = new ArrayList<>();
    public String listSql;           // SELECT dùng cho danh sách (có JOIN lấy tên)
    public String labelSql;          // biểu thức hiển thị khi làm danh sách chọn
    public String orderBy = "id DESC";
    public Role[] writeRoles = { Role.ADMIN };
    public Role[] deleteRoles = { Role.ADMIN };
    public String hint;              // ghi chú hiển thị trên màn hình

    public EntityDef(String key, String table, String title) {
        this.key = key;
        this.table = table;
        this.title = title;
    }

    public EntityDef field(Field f) { fields.add(f); return this; }
    public EntityDef col(String name, String label) { columns.add(new Column(name, label, null)); return this; }
    public EntityDef col(String name, String label, String format) { columns.add(new Column(name, label, format)); return this; }

    public Field getField(String name) {
        for (Field f : fields) if (f.name.equals(name)) return f;
        return null;
    }

    /** Quyền chi tiết quyết định ai được thêm/sửa/xóa (null = theo vai trò writeRoles/deleteRoles). */
    public Perm perm;

    public boolean canWrite(User u) {
        if (perm != null) return perm.allows(u);
        for (Role x : writeRoles) if (x == u.role()) return true;
        return false;
    }
    public boolean canDelete(User u) {
        if (perm != null) return perm.allows(u);
        for (Role x : deleteRoles) if (x == u.role()) return true;
        return false;
    }

    public Map<String, Object> toMap(User viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        List<Object> fs = new ArrayList<>();
        for (Field f : fields) fs.add(f.toMap());
        m.put("fields", fs);
        List<Object> cs = new ArrayList<>();
        for (Column c : columns) cs.add(c.toMap());
        m.put("columns", cs);
        m.put("canWrite", canWrite(viewer));
        m.put("canDelete", canDelete(viewer));
        if (hint != null) m.put("hint", hint);
        return m;
    }
}
