package vn.baohanh.service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import vn.baohanh.db.Database;
import vn.baohanh.db.Row;
import vn.baohanh.model.EntityDef;
import vn.baohanh.model.User;
import vn.baohanh.util.SheetReader;
import vn.baohanh.util.Xlsx;

/**
 * Nhập danh sách khách hàng / sản phẩm / linh kiện từ file Excel (.xlsx) hoặc .csv.
 * Dòng đầu là tiêu đề cột (nhận theo tên, không phân biệt dấu/hoa thường, thứ tự tùy ý).
 * Bản ghi đã có (trùng SĐT / mã model / mã linh kiện) được cập nhật, ô để trống giữ giá trị cũ.
 * Bước "xem trước" chạy thử toàn bộ trong một transaction rồi hoàn tác, nên kết quả xem trước
 * giống hệt lúc nhập thật; mỗi dòng lỗi chỉ bị bỏ qua, không làm hỏng các dòng khác.
 */
public class ImportService {
    private final Database db;
    private final CatalogService catalog;
    private final AuditService audit;

    public ImportService(Database db, CatalogService catalog, AuditService audit) {
        this.db = db;
        this.catalog = catalog;
        this.audit = audit;
    }

    /** Một cột của file mẫu: trường dữ liệu, tiêu đề, các tên gọi khác được chấp nhận. */
    record Col(String field, String header, boolean required, String example1, String example2, String... aliases) {}

    static final Map<String, List<Col>> SPECS = new LinkedHashMap<>();
    static final Map<String, String> TITLES = Map.of("customer", "khách hàng", "product", "sản phẩm / model", "part", "linh kiện");

    static {
        SPECS.put("customer", List.of(
                new Col("name", "Họ tên", true, "Nguyễn Văn Mẫu", "Trần Thị Ví Dụ", "ten", "ten khach", "ten khach hang", "khach hang", "ho va ten", "name"),
                new Col("phone", "Số điện thoại", true, "0901000001", "0912000002", "dien thoai", "sdt", "so dt", "phone", "di dong"),
                new Col("email", "Email", false, "mau@example.com", "", "e mail", "thu dien tu"),
                new Col("address", "Địa chỉ", false, "12 Lê Lợi, Quận 1, TP. Hồ Chí Minh", "45 Trần Phú, Hà Đông, Hà Nội", "dia chi", "address"),
                new Col("note", "Ghi chú", false, "Khách quen", "", "ghi chu", "note")));
        SPECS.put("product", List.of(
                new Col("model_code", "Mã model", true, "SM-A556E", "MWP22", "ma", "model", "ma sp", "ma san pham"),
                new Col("name", "Tên sản phẩm", true, "Samsung Galaxy A55 5G", "AirPods Pro 2", "ten", "san pham", "ten model"),
                new Col("brand", "Hãng", true, "Samsung", "Apple", "hang san xuat", "thuong hieu", "brand", "nha san xuat"),
                new Col("category", "Loại", true, "Điện thoại", "Tai nghe", "loai san pham", "danh muc", "nhom", "category"),
                new Col("default_months", "Bảo hành (tháng)", false, "12", "12", "bao hanh", "so thang bao hanh", "thang bao hanh", "bh thang", "thoi han bao hanh"),
                new Col("price", "Giá bán", false, "9490000", "5990000", "gia", "don gia", "price")));
        SPECS.put("part", List.of(
                new Col("code", "Mã linh kiện", true, "PIN-A55", "MH-A55", "ma", "ma lk", "code"),
                new Col("name", "Tên linh kiện", true, "Pin Galaxy A55", "Màn hình Galaxy A55", "ten", "linh kien", "ten lk"),
                new Col("brand", "Hãng", false, "Samsung", "Samsung", "hang san xuat", "thuong hieu", "brand"),
                new Col("unit", "Đơn vị tính", false, "cái", "cái", "don vi", "dvt", "unit"),
                new Col("cost_price", "Giá nhập", false, "350000", "1800000", "gia von", "gia mua"),
                new Col("sell_price", "Giá bán", false, "550000", "2600000", "gia ban le", "gia"),
                new Col("stock", "Tồn kho", false, "5", "2", "ton", "so luong", "sl", "ton kho ban dau"),
                new Col("min_stock", "Tồn tối thiểu", false, "2", "1", "toi thieu", "muc toi thieu", "ton toi thieu", "canh bao")));
    }

    private static List<Col> spec(String entity) {
        List<Col> s = SPECS.get(entity);
        if (s == null) throw new AppException("Chỉ nhập được khách hàng, sản phẩm hoặc linh kiện");
        return s;
    }

    private void requireWrite(User u, String entity) {
        Auth.require(u);
        spec(entity);
        EntityDef d = catalog.def(entity);
        if (!d.canWrite(u)) throw AppException.forbidden();
    }

    /** File Excel mẫu có sẵn tiêu đề cột và 2 dòng ví dụ. */
    public byte[] template(User u, String entity) {
        requireWrite(u, entity);
        List<String> header = new ArrayList<>();
        List<Object> r1 = new ArrayList<>(), r2 = new ArrayList<>();
        for (Col c : spec(entity)) {
            header.add(c.header() + (c.required() ? " *" : ""));
            r1.add(c.example1());
            r2.add(c.example2());
        }
        return new Xlsx().sheet("Nhập " + TITLES.get(entity), header, List.of(r1, r2)).toBytes();
    }

    public static String templateName(String entity) {
        return "mau-nhap-" + switch (entity) { case "customer" -> "khach-hang"; case "product" -> "san-pham"; default -> "linh-kien"; } + ".xlsx";
    }

    /** Chạy thử, không lưu. */
    public Row preview(User u, String entity, byte[] data, String fileName) throws SQLException {
        return run(u, entity, data, fileName, false);
    }

    /** Nhập thật: các dòng hợp lệ được lưu, dòng lỗi bỏ qua. */
    public Row commit(User u, String entity, byte[] data, String fileName) throws SQLException {
        return run(u, entity, data, fileName, true);
    }

    private static final class Rollback extends RuntimeException {
        final Row result;
        Rollback(Row r) { super(null, null, false, false); result = r; }
    }

    private Row run(User u, String entity, byte[] data, String fileName, boolean save) throws SQLException {
        requireWrite(u, entity);
        List<List<String>> table;
        try {
            table = SheetReader.read(data, fileName);
        } catch (IllegalArgumentException e) {
            throw new AppException(e.getMessage());
        }
        while (!table.isEmpty() && blank(table.get(0))) table.remove(0);
        if (table.isEmpty()) throw new AppException("File không có dữ liệu");
        if (table.size() - 1 > SheetReader.MAX_ROWS) throw new AppException("Tối đa " + SheetReader.MAX_ROWS + " dòng mỗi lần nhập");

        // ---- Nhận diện cột theo tiêu đề ----
        List<Col> cols = spec(entity);
        List<String> head = table.get(0);
        Map<String, Integer> index = new LinkedHashMap<>();
        List<Row> mapping = new ArrayList<>();
        for (Col c : cols) {
            int found = -1;
            for (int pass = 0; pass < 2 && found < 0; pass++)
                for (int i = 0; i < head.size() && found < 0; i++) {
                    if (index.containsValue(i)) continue;
                    String h = FaultService.normalize(head.get(i).replace("*", ""));
                    if (h.isEmpty()) continue;
                    if (matches(h, c, pass == 1)) found = i;
                }
            if (found >= 0) index.put(c.field(), found);
            mapping.add(new Row().with("field", c.field()).with("label", c.header()).with("required", c.required())
                    .with("column", found >= 0 ? head.get(found) : ""));
        }
        List<String> missing = new ArrayList<>();
        for (Col c : cols) if (c.required() && !index.containsKey(c.field())) missing.add(c.header());
        if (!missing.isEmpty())
            throw new AppException("File thiếu cột bắt buộc: " + String.join(", ", missing)
                    + ". Hãy tải file mẫu và giữ nguyên dòng tiêu đề.");

        // ---- Xử lý từng dòng trong một transaction ----
        Row result = new Row();
        try {
            db.tx(() -> {
                List<Row> rows = new ArrayList<>();
                Set<String> newBrands = new LinkedHashSet<>(), newCats = new LinkedHashSet<>();
                long created = 0, updated = 0, errors = 0;
                for (int i = 1; i < table.size(); i++) {
                    List<String> line = table.get(i);
                    if (blank(line)) continue;
                    Map<String, String> v = new LinkedHashMap<>();
                    for (var e : index.entrySet()) v.put(e.getKey(), e.getValue() < line.size() ? line.get(e.getValue()).trim() : "");
                    Row out = new Row().with("line", (long) (i + 1)).with("values", v);
                    db.update("SAVEPOINT nhap_dong");
                    try {
                        String action = importRow(u, entity, v, newBrands, newCats, out);
                        db.update("RELEASE nhap_dong");
                        out.put("action", action);
                        if ("CREATE".equals(action)) created++; else updated++;
                    } catch (RuntimeException | SQLException ex) {
                        db.update("ROLLBACK TO nhap_dong");
                        db.update("RELEASE nhap_dong");
                        out.put("action", "ERROR");
                        out.put("message", ex.getMessage());
                        errors++;
                    }
                    rows.add(out);
                }
                result.put("entity", entity);
                result.put("title", TITLES.get(entity));
                result.put("mapping", mapping);
                result.put("rows", rows);
                result.put("created", created);
                result.put("updated", updated);
                result.put("errors", errors);
                result.put("new_brands", new ArrayList<>(newBrands));
                result.put("new_categories", new ArrayList<>(newCats));
                result.put("saved", save);
                if (save) {
                    audit.log(u, "NHẬP EXCEL", catalog.def(entity).table, null, "Nhập " + TITLES.get(entity) + " từ "
                            + (fileName == null ? "file" : fileName) + ": thêm " + created + ", cập nhật " + updated + ", lỗi " + errors);
                    return null;
                }
                throw new Rollback(result);
            });
        } catch (Rollback r) {
            return r.result;
        }
        return result;
    }

    private static boolean matches(String h, Col c, boolean loose) {
        List<String> names = new ArrayList<>(List.of(c.aliases()));
        names.add(FaultService.normalize(c.header()));
        for (String a : names) {
            String n = FaultService.normalize(a);
            if (h.equals(n)) return true;
            if (loose && n.length() >= 4 && (h.startsWith(n + " ") || h.endsWith(" " + n))) return true;
        }
        return false;
    }

    private static boolean blank(List<String> l) {
        for (String s : l) if (s != null && !s.isBlank()) return false;
        return true;
    }

    /** Lưu một dòng; trả về CREATE hoặc UPDATE. Lỗi → AppException với lời giải thích. */
    private String importRow(User u, String entity, Map<String, String> v, Set<String> newBrands, Set<String> newCats, Row out)
            throws SQLException {
        Map<String, Object> in = new LinkedHashMap<>();
        Row existing;
        switch (entity) {
            case "customer" -> {
                String phone = phone(v.get("phone"));
                if (phone.isEmpty()) throw new AppException("Thiếu số điện thoại");
                v.put("phone", phone);
                existing = db.one("SELECT * FROM customer WHERE phone = ?", phone);
                out.put("key", phone);
                out.put("name", v.getOrDefault("name", ""));
            }
            case "product" -> {
                String code = v.getOrDefault("model_code", "");
                if (code.isEmpty()) throw new AppException("Thiếu mã model");
                existing = db.one("SELECT * FROM product WHERE model_code = ? COLLATE NOCASE", code);
                out.put("key", code);
                out.put("name", v.getOrDefault("name", ""));
            }
            default -> {
                String code = v.getOrDefault("code", "");
                if (code.isEmpty()) throw new AppException("Thiếu mã linh kiện");
                existing = db.one("SELECT * FROM part WHERE code = ? COLLATE NOCASE", code);
                out.put("key", code);
                out.put("name", v.getOrDefault("name", ""));
            }
        }
        if (existing != null) in.putAll(existing);           // ô để trống: giữ giá trị cũ
        for (var e : v.entrySet()) {
            String val = e.getValue();
            if (val.isEmpty()) continue;
            switch (e.getKey()) {
                case "brand" -> in.put("brand_id", refId("brand", val, newBrands));
                case "category" -> in.put("category_id", refId("category", val, newCats));
                case "price", "cost_price", "sell_price" -> in.put(e.getKey(), money(val));
                case "default_months", "stock", "min_stock" -> in.put(e.getKey(), integer(val));
                default -> in.put(e.getKey(), val);
            }
        }
        if (existing != null && "part".equals(entity) && !v.getOrDefault("stock", "").isEmpty()
                && !String.valueOf(existing.lng("stock")).equals(v.get("stock")))
            out.put("message", "Tồn kho giữ nguyên " + existing.lng("stock") + " (dùng Nhập kho để cộng thêm)");
        catalog.save(u, entity, existing == null ? 0 : existing.lng("id"), in);
        return existing == null ? "CREATE" : "UPDATE";
    }

    /** Tìm hãng / loại theo tên (không phân biệt hoa thường); chưa có thì tạo mới. */
    private long refId(String table, String name, Set<String> created) throws SQLException {
        Row r = db.one("SELECT id FROM " + table + " WHERE lower(trim(name)) = lower(trim(?))", name);
        if (r == null)
            for (Row x : db.query("SELECT id, name FROM " + table))
                if (FaultService.normalize(x.str("name")).equals(FaultService.normalize(name))) { r = x; break; }
        if (r != null) return r.lng("id");
        long id = db.insert("INSERT INTO " + table + " (name) VALUES (?)", name.trim());
        created.add(name.trim());
        return id;
    }

    static String phone(String s) {
        String p = s == null ? "" : s.replaceAll("[\\s.\\-()]", "");
        if (p.startsWith("+84")) p = "0" + p.substring(3);
        else if (p.startsWith("84") && p.length() == 11) p = "0" + p.substring(2);
        if (p.matches("[1-9]\\d{8}")) p = "0" + p;              // Excel làm mất số 0 đầu
        return p;
    }

    static String money(String s) {
        String t = s.trim();
        if (t.matches("\\d+[.,]\\d{1,2}")) t = t.replaceAll("[.,]\\d{1,2}$", "");   // 1500000.0
        String d = t.replaceAll("[^0-9]", "");
        if (d.isEmpty()) throw new AppException("Giá \"" + s + "\" không hợp lệ");
        return d;
    }

    static String integer(String s) {
        String t = s.trim().replaceAll("[.,]0+$", "");
        if (!t.matches("-?\\d+")) throw new AppException("Số \"" + s + "\" không hợp lệ");
        return t;
    }
}
