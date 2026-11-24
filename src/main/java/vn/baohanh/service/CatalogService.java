package vn.baohanh.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.EntityDef;
import vn.baohanh.model.EntityDef.Field;
import vn.baohanh.model.EntityDef.Type;
import vn.baohanh.model.Role;
import vn.baohanh.model.User;
import vn.baohanh.security.PasswordHasher;
import vn.baohanh.util.Json;

/**
 * Quản lý danh mục: khách hàng, hãng, trung tâm bảo hành, loại, sản phẩm, linh kiện, người dùng.
 * Mỗi danh mục được mô tả bằng một EntityDef; thêm/sửa/xóa/tìm kiếm dùng chung một bộ mã.
 */
public class CatalogService {
    public static final Pattern PHONE = Pattern.compile("^0\\d{9,10}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final Database db;
    private final AuditService audit;
    private final Auth auth;
    private final Map<String, EntityDef> defs = new LinkedHashMap<>();

    public CatalogService(Database db, AuditService audit, Auth auth) {
        this.db = db;
        this.audit = audit;
        this.auth = auth;
        define();
    }

    // ===================== ĐỊNH NGHĨA DANH MỤC =====================

    private void define() {
        EntityDef d;

        d = new EntityDef("customer", "customer", "Khách hàng");
        d.field(new Field("name", "Họ tên", Type.TEXT).req())
         .field(new Field("phone", "Số điện thoại", Type.PHONE).req())
         .field(new Field("email", "Email", Type.EMAIL))
         .field(new Field("address", "Địa chỉ", Type.TEXT))
         .field(new Field("note", "Ghi chú", Type.TEXTAREA));
        d.col("name", "Họ tên").col("phone", "Điện thoại").col("email", "Email").col("address", "Địa chỉ")
         .col("warranty_count", "Số máy", "int");
        d.listSql = "SELECT c.*, (SELECT COUNT(*) FROM warranty w WHERE w.customer_id = c.id) AS warranty_count FROM customer c";
        d.labelSql = "name || ' – ' || phone";
        d.writeRoles = new Role[]{Role.ADMIN, Role.RECEPTION};
        add(d);

        d = new EntityDef("brand", "brand", "Hãng sản xuất");
        d.field(new Field("name", "Tên hãng", Type.TEXT).req())
         .field(new Field("country", "Quốc gia", Type.TEXT))
         .field(new Field("hotline", "Tổng đài bảo hành", Type.TEXT));
        d.col("name", "Tên hãng").col("country", "Quốc gia").col("hotline", "Tổng đài").col("product_count", "Số model", "int");
        d.listSql = "SELECT b.*, (SELECT COUNT(*) FROM product p WHERE p.brand_id = b.id) AS product_count FROM brand b";
        d.labelSql = "name";
        d.orderBy = "name";
        add(d);

        d = new EntityDef("service_center", "service_center", "Trung tâm bảo hành hãng");
        d.field(new Field("brand_id", "Hãng", Type.REF).ref("brand").req())
         .field(new Field("name", "Tên trung tâm", Type.TEXT).req())
         .field(new Field("phone", "Điện thoại", Type.TEXT))
         .field(new Field("address", "Địa chỉ", Type.TEXT));
        d.col("brand_name", "Hãng").col("name", "Tên trung tâm").col("phone", "Điện thoại").col("address", "Địa chỉ");
        d.listSql = "SELECT s.*, b.name AS brand_name FROM service_center s JOIN brand b ON b.id = s.brand_id";
        d.labelSql = "name";
        d.orderBy = "brand_name, name";
        d.hint = "Nơi cửa hàng gửi máy khi lỗi cần hãng xử lý.";
        add(d);

        d = new EntityDef("supplier", "supplier", "Nhà cung cấp linh kiện");
        d.field(new Field("name", "Tên nhà cung cấp", Type.TEXT).req())
         .field(new Field("contact", "Người liên hệ", Type.TEXT))
         .field(new Field("phone", "Điện thoại", Type.TEXT))
         .field(new Field("email", "Email", Type.EMAIL))
         .field(new Field("address", "Địa chỉ", Type.TEXT))
         .field(new Field("note", "Ghi chú", Type.TEXTAREA));
        d.col("name", "Nhà cung cấp").col("contact", "Liên hệ").col("phone", "Điện thoại").col("email", "Email")
         .col("po_count", "Số đơn", "int");
        d.listSql = "SELECT s.*, (SELECT COUNT(*) FROM purchase_order o WHERE o.supplier_id = s.id) AS po_count FROM supplier s";
        d.labelSql = "name";
        d.orderBy = "name";
        d.hint = "Nơi cửa hàng đặt mua linh kiện. Dùng khi tạo đơn đặt hàng.";
        add(d);

        d = new EntityDef("category", "category", "Loại sản phẩm");
        d.field(new Field("name", "Tên loại", Type.TEXT).req());
        d.col("name", "Tên loại").col("product_count", "Số model", "int");
        d.listSql = "SELECT c.*, (SELECT COUNT(*) FROM product p WHERE p.category_id = c.id) AS product_count FROM category c";
        d.labelSql = "name";
        d.orderBy = "name";
        add(d);

        d = new EntityDef("product", "product", "Sản phẩm / Model");
        d.field(new Field("model_code", "Mã model", Type.TEXT).req())
         .field(new Field("name", "Tên sản phẩm", Type.TEXT).req())
         .field(new Field("brand_id", "Hãng", Type.REF).ref("brand").req())
         .field(new Field("category_id", "Loại", Type.REF).ref("category").req())
         .field(new Field("default_months", "Bảo hành mặc định (tháng)", Type.INT).req().range(1, 120).def("12"))
         .field(new Field("price", "Giá bán", Type.MONEY));
        d.col("model_code", "Mã model").col("name", "Tên sản phẩm").col("brand_name", "Hãng").col("category_name", "Loại")
         .col("default_months", "BH (tháng)", "int").col("price", "Giá bán", "money");
        d.listSql = "SELECT p.*, b.name AS brand_name, c.name AS category_name FROM product p "
                + "JOIN brand b ON b.id = p.brand_id JOIN category c ON c.id = p.category_id";
        d.labelSql = "name || ' (' || model_code || ')'";
        d.orderBy = "brand_name, name";
        add(d);

        d = new EntityDef("part", "part", "Linh kiện");
        d.field(new Field("code", "Mã linh kiện", Type.TEXT).req())
         .field(new Field("name", "Tên linh kiện", Type.TEXT).req())
         .field(new Field("brand_id", "Hãng", Type.REF).ref("brand"))
         .field(new Field("unit", "Đơn vị tính", Type.TEXT).def("cái"))
         .field(new Field("cost_price", "Giá nhập", Type.MONEY))
         .field(new Field("sell_price", "Giá bán", Type.MONEY))
         .field(new Field("stock", "Tồn kho ban đầu", Type.INT).range(0, 1_000_000).createOnly().def("0"))
         .field(new Field("min_stock", "Mức tồn tối thiểu", Type.INT).range(0, 1_000_000).def("0"));
        d.col("code", "Mã").col("name", "Tên linh kiện").col("brand_name", "Hãng").col("unit", "ĐVT")
         .col("sell_price", "Giá bán", "money").col("stock", "Tồn", "int").col("min_stock", "Tối thiểu", "int")
         .col("low", "Cảnh báo", "flag");
        d.listSql = "SELECT pt.*, b.name AS brand_name, CASE WHEN pt.stock <= pt.min_stock THEN 1 ELSE 0 END AS low "
                + "FROM part pt LEFT JOIN brand b ON b.id = pt.brand_id";
        d.labelSql = "code || ' – ' || name";
        d.orderBy = "code";
        d.hint = "Tồn kho chỉ thay đổi qua Nhập kho / Kiểm kê hoặc khi dùng cho phiếu.";
        add(d);

        Map<String, String> roles = new LinkedHashMap<>();
        for (Role r : Role.values()) roles.put(r.name(), r.getLabel());
        d = new EntityDef("user", "app_user", "Người dùng");
        d.field(new Field("username", "Tên đăng nhập", Type.TEXT).req())
         .field(new Field("full_name", "Họ tên", Type.TEXT).req())
         .field(new Field("phone", "Điện thoại", Type.TEXT))
         .field(new Field("role", "Vai trò", Type.ENUM).options(roles).req().def("RECEPTION"))
         .field(new Field("active", "Đang hoạt động", Type.BOOL).def("true"))
         .field(new Field("password", "Mật khẩu (để trống nếu không đổi)", Type.PASSWORD));
        d.col("username", "Tên đăng nhập").col("full_name", "Họ tên").col("role_label", "Vai trò")
         .col("phone", "Điện thoại").col("active", "Hoạt động", "bool");
        d.listSql = "SELECT id, username, full_name, phone, role, active, created_at, CASE role "
                + "WHEN 'ADMIN' THEN 'Quản lý' WHEN 'RECEPTION' THEN 'Nhân viên tiếp nhận' ELSE 'Kỹ thuật viên' END AS role_label "
                + "FROM app_user";
        d.labelSql = "full_name";
        d.orderBy = "role, full_name";
        add(d);
    }

    private void add(EntityDef d) { defs.put(d.key, d); }

    public EntityDef def(String key) {
        EntityDef d = defs.get(key);
        if (d == null) throw AppException.notFound("danh mục " + key);
        return d;
    }

    public List<EntityDef> all() { return new ArrayList<>(defs.values()); }

    // ===================== ĐỌC =====================

    public List<Row> list(User u, String key, String q) throws SQLException {
        Auth.require(u);
        EntityDef d = def(key);
        List<Row> rows = db.query("SELECT * FROM (" + d.listSql + ") ORDER BY " + d.orderBy);
        String k = norm(q);
        if (k.isEmpty()) return rows;
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            for (Object v : r.values()) {
                if (v instanceof String s && norm(s).contains(k)) { out.add(r); break; }
            }
        }
        return out;
    }

    public Row get(User u, String key, long id) throws SQLException {
        Auth.require(u);
        EntityDef d = def(key);
        Row r = db.one("SELECT * FROM " + d.table + " WHERE id = ?", id);
        if (r == null) throw AppException.notFound(d.title.toLowerCase() + " #" + id);
        r.remove("password_hash");
        return r;
    }

    /** Danh sách chọn (id, label) cho ô REF. */
    public List<Row> options(User u, String key) throws SQLException {
        Auth.require(u);
        EntityDef d = def(key);
        String where = "user".equals(key) ? " WHERE active = 1" : "";
        return db.query("SELECT id, " + d.labelSql + " AS label FROM " + d.table + where + " ORDER BY label");
    }

    /** Kỹ thuật viên đang hoạt động (để phân công). */
    public List<Row> technicians(User u) throws SQLException {
        Auth.require(u);
        return db.query("SELECT id, full_name AS label FROM app_user WHERE role = 'TECHNICIAN' AND active = 1 ORDER BY full_name");
    }

    /** Tìm khách theo SĐT (gợi ý khi kích hoạt bảo hành). */
    public Row customerByPhone(User u, String phone) throws SQLException {
        Auth.require(u);
        String p = phone == null ? "" : phone.replaceAll("[\\s.-]", "");
        return p.isEmpty() ? null : db.one("SELECT id, name, phone, email, address FROM customer WHERE phone = ?", p);
    }

    public static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.forLanguageTag("vi"));
    }

    // ===================== GHI =====================

    /** Thêm mới (id = 0) hoặc cập nhật. Trả về bản ghi sau khi lưu. */
    public Row save(User u, String key, long id, Map<String, Object> in) throws SQLException {
        EntityDef d = def(key);
        Auth.require(u);
        if (!d.canWrite(u.role())) throw AppException.forbidden();
        boolean create = id == 0;
        Row before = create ? null : get(u, key, id);

        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Field f : d.fields) {
            if (f.createOnly && !create) continue;
            Object v = parse(f, in.get(f.name), create);
            if (f.type == Type.PASSWORD) {
                if (v == null) continue;               // để trống: giữ mật khẩu cũ
                cols.add("password_hash");
                vals.add(PasswordHasher.hash((String) v));
                continue;
            }
            cols.add(f.name);
            vals.add(v);
        }
        if ("user".equals(key)) checkUserRules(u, id, in, create);

        try {
            long savedId = db.tx(() -> {
                long newId = id;
                if (create) {
                    if ("customer".equals(key) || "user".equals(key)) { cols.add("created_at"); vals.add(Database.now()); }
                    String marks = String.join(", ", java.util.Collections.nCopies(cols.size(), "?"));
                    newId = db.insert("INSERT INTO " + d.table + " (" + String.join(", ", cols) + ") VALUES (" + marks + ")",
                            vals.toArray());
                    if ("part".equals(key)) {
                        long stock = Json.num(in, "stock", 0);
                        if (stock > 0) db.update("INSERT INTO stock_movement (part_id, time, qty, type, user_id, note) "
                                + "VALUES (?, ?, ?, 'IN', ?, 'Tồn kho ban đầu')", newId, Database.now(), stock, u.id());
                    }
                } else {
                    StringBuilder set = new StringBuilder();
                    for (String c : cols) set.append(set.length() > 0 ? ", " : "").append(c).append(" = ?");
                    vals.add(id);
                    if (db.update("UPDATE " + d.table + " SET " + set + " WHERE id = ?", vals.toArray()) == 0)
                        throw AppException.notFound(d.title.toLowerCase());
                }
                audit.log(u, create ? "THÊM" : "SỬA", d.table, newId, describe(d, in, before));
                return newId;
            });
            if ("user".equals(key) && !create) auth.dropSessions(id);
            return get(u, key, savedId);
        } catch (SQLException e) {
            throw friendly(d, e);
        }
    }

    public void delete(User u, String key, long id) throws SQLException {
        EntityDef d = def(key);
        Auth.require(u);
        if (!d.canDelete(u.role())) throw AppException.forbidden();
        Row before = get(u, key, id);
        if ("user".equals(key)) {
            if (id == u.id()) throw new AppException("Không thể xóa tài khoản đang đăng nhập");
            if ("ADMIN".equals(before.str("role")) && countActiveAdmins(id) == 0)
                throw new AppException("Phải còn ít nhất một tài khoản Quản lý");
        }
        try {
            db.tx(() -> {
                db.update("DELETE FROM " + d.table + " WHERE id = ?", id);
                audit.log(u, "XÓA", d.table, id, describe(d, before, null));
                return null;
            });
            if ("user".equals(key)) auth.dropSessions(id);
        } catch (SQLException e) {
            throw friendly(d, e);
        }
    }

    private void checkUserRules(User u, long id, Map<String, Object> in, boolean create) throws SQLException {
        String pw = Json.str(in, "password");
        if (create && pw.isEmpty()) throw new AppException("Vui lòng nhập mật khẩu cho tài khoản mới");
        if (!pw.isEmpty()) Auth.checkStrength(pw);
        if (!create && id == u.id()) {
            if (!"ADMIN".equals(Json.str(in, "role"))) throw new AppException("Không thể tự hạ quyền của chính mình");
            if (!truthy(in.get("active"))) throw new AppException("Không thể tự khóa tài khoản của chính mình");
        }
        if (!create && ("ADMIN".equals(db.one("SELECT role FROM app_user WHERE id = ?", id).str("role")))
                && (!"ADMIN".equals(Json.str(in, "role")) || !truthy(in.get("active")))
                && countActiveAdmins(id) == 0)
            throw new AppException("Phải còn ít nhất một tài khoản Quản lý đang hoạt động");
    }

    private long countActiveAdmins(long excludeId) throws SQLException {
        return db.scalar("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND active = 1 AND id <> ?", excludeId);
    }

    // ===================== KIỂM TRA DỮ LIỆU =====================

    private Object parse(Field f, Object raw, boolean create) throws SQLException {
        String s = raw == null ? "" : String.valueOf(raw).trim();
        if (s.isEmpty() && f.defaultValue != null && create && raw == null) s = f.defaultValue;
        if (f.type == Type.BOOL) return truthy(s.isEmpty() ? f.defaultValue : s) ? 1 : 0;
        if (s.isEmpty()) {
            if (f.required && f.type != Type.PASSWORD) throw new AppException("Vui lòng nhập " + f.label.toLowerCase());
            return switch (f.type) {
                case INT, MONEY -> f.required ? null : 0L;
                default -> null;
            };
        }
        switch (f.type) {
            case PHONE -> {
                String p = s.replaceAll("[\\s.-]", "");
                if (!PHONE.matcher(p).matches()) throw new AppException(f.label + " không hợp lệ (10–11 số, bắt đầu bằng 0)");
                return p;
            }
            case EMAIL -> {
                if (!EMAIL.matcher(s).matches()) throw new AppException("Email không hợp lệ");
                return s;
            }
            case INT, MONEY -> {
                long n;
                try { n = Long.parseLong(s.replace(".", "").replace(",", "").replace(" ", "")); }
                catch (NumberFormatException e) { throw new AppException(f.label + " phải là số"); }
                long lo = f.type == Type.MONEY ? Math.max(0, f.min) : f.min;
                if (n < lo || n > f.max) throw new AppException(f.label + " phải từ " + lo + (f.max == Long.MAX_VALUE ? "" : " đến " + f.max));
                return n;
            }
            case REF -> {
                long rid;
                try { rid = Long.parseLong(s); } catch (NumberFormatException e) { throw new AppException(f.label + " không hợp lệ"); }
                if (db.scalar("SELECT COUNT(*) FROM " + def(f.ref).table + " WHERE id = ?", rid) == 0)
                    throw new AppException(f.label + " không tồn tại");
                return rid;
            }
            case ENUM -> {
                if (!f.options.containsKey(s)) throw new AppException(f.label + " không hợp lệ");
                return s;
            }
            default -> {
                if (s.length() > 500) throw new AppException(f.label + " quá dài");
                return "username".equals(f.name) ? s.toLowerCase() : s;
            }
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("yes");
    }

    /** Đổi lỗi SQLite thành câu dễ hiểu. */
    private AppException friendly(EntityDef d, SQLException e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("UNIQUE constraint failed")) {
            String col = m.replaceAll("(?s).*UNIQUE constraint failed: [\\w]+\\.(\\w+).*", "$1");
            Field f = d.getField(col);
            return new AppException((f == null ? col : f.label) + " đã tồn tại, vui lòng nhập giá trị khác");
        }
        if (m.contains("FOREIGN KEY constraint failed"))
            return new AppException("Không thể xóa: " + d.title.toLowerCase() + " này đang được sử dụng ở dữ liệu khác");
        if (m.contains("CHECK constraint failed")) return new AppException("Dữ liệu không hợp lệ: " + m);
        throw new RuntimeException(e);
    }

    private static String describe(EntityDef d, Map<String, Object> data, Map<String, Object> before) {
        StringBuilder sb = new StringBuilder(d.title).append(": ");
        Object name = data.get(d.fields.get(0).name);
        Object name2 = d.fields.size() > 1 ? data.get(d.fields.get(1).name) : null;
        sb.append(name == null ? "" : name);
        if (name2 != null && !(d.fields.get(1).type == Type.REF)) sb.append(" – ").append(name2);
        return sb.toString();
    }
}
