package vn.baohanh.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import vn.baohanh.model.EntityDef;
import vn.baohanh.model.Role;
import vn.baohanh.model.TicketStatus;
import vn.baohanh.model.User;
import vn.baohanh.service.AppContext;
import vn.baohanh.service.AppException;
import vn.baohanh.service.CustomerService;
import vn.baohanh.service.InventoryService;
import vn.baohanh.service.PhotoService;
import vn.baohanh.service.ReportService;
import vn.baohanh.service.SettingService;
import vn.baohanh.service.WarrantyService;
import vn.baohanh.util.Json;

/**
 * Máy chủ web (com.sun.net.httpserver có sẵn trong JDK).
 *
 * Trang:   /            giao diện nhân viên (cần đăng nhập)
 *          /tra-cuu     cổng tra cứu cho khách hàng (công khai)
 *          /in/...      phiếu in (liên kết có chữ ký)
 * API:     /api/...     JSON; phiên đăng nhập lưu trong cookie "sid" (HttpOnly, SameSite=Strict).
 *          Các yêu cầu ghi (POST/PUT/DELETE) phải có header "X-App: 1" để chống CSRF.
 */
public class ApiServer {
    private static final int MAX_BODY = 1_000_000;

    /** Ngữ cảnh một yêu cầu. */
    private static final class Req {
        HttpExchange ex;
        String method;
        String path;
        Map<String, String> q;
        Matcher m;
        User user;
        Map<String, Object> body;

        long id(int group) { return Long.parseLong(m.group(group)); }
        String q(String k) { return q.getOrDefault(k, ""); }
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Req r) throws Exception;
    }

    private record Route(String method, Pattern pattern, boolean auth, Handler handler, boolean raw) {
        Route(String method, Pattern pattern, boolean auth, Handler handler) { this(method, pattern, auth, handler, false); }
    }

    private final AppContext app;
    private final HttpServer server;
    private final List<Route> routes = new ArrayList<>();

    public ApiServer(AppContext app, int port) throws IOException {
        this.app = app;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        defineRoutes();
    }

    public void start() { server.start(); }

    public void stop() { server.stop(0); }

    private void get(String re, boolean auth, Handler h) { routes.add(new Route("GET", Pattern.compile(re), auth, h)); }
    private void post(String re, Handler h) { routes.add(new Route("POST", Pattern.compile(re), true, h)); }
    private void put(String re, Handler h) { routes.add(new Route("PUT", Pattern.compile(re), true, h)); }
    private void delete(String re, Handler h) { routes.add(new Route("DELETE", Pattern.compile(re), true, h)); }
    /** POST nhận dữ liệu nhị phân (tải ảnh): handler tự đọc thân yêu cầu. */
    private void postRaw(String re, Handler h) { routes.add(new Route("POST", Pattern.compile(re), true, h, true)); }
    /** Route của cổng khách hàng: tự kiểm tra phiên khách bên trong. */
    private void kh(String method, String re, Handler h) { routes.add(new Route(method, Pattern.compile(re), false, h)); }
    private void khRaw(String re, Handler h) { routes.add(new Route("POST", Pattern.compile(re), false, h, true)); }

    // =====================================================================
    //  ĐỊNH TUYẾN
    // =====================================================================

    private void defineRoutes() {
        // ---------- Công khai ----------
        get("/api/public/lookup", false, r -> app.portal.lookup(r.q("q"), r.ex.getRemoteAddress().getAddress().getHostAddress()));
        get("/api/public/shop", false, r -> app.portal.shopInfo());
        get("/api/public/shop-page", false, r -> app.portal.shopPage());
        routes.add(new Route("POST", Pattern.compile("/api/login"), false, r -> {
            User u = app.auth.login(Json.str(r.body, "username"), Json.str(r.body, "password"));
            String token = app.auth.createSession(u);
            r.ex.getResponseHeaders().add("Set-Cookie", "sid=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
            return u.toMap();
        }));

        // ---------- Phiên làm việc ----------
        get("/api/me", true, r -> r.user.toMap());
        post("/api/logout", r -> {
            app.auth.logout(cookie(r.ex, "sid"));
            r.ex.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return Map.of("ok", true);
        });
        post("/api/password", r -> {
            app.auth.changePassword(r.user, Json.str(r.body, "old_password"), Json.str(r.body, "new_password"));
            return Map.of("ok", true);
        });
        get("/api/meta", true, r -> meta(r.user));
        get("/api/dashboard", true, r -> app.warranty.dashboard(r.user));

        // ---------- Danh mục ----------
        get("/api/catalog/(\\w+)", true, r -> app.catalog.list(r.user, r.m.group(1), r.q("q")));
        get("/api/catalog/(\\w+)/(\\d+)", true, r -> app.catalog.get(r.user, r.m.group(1), r.id(2)));
        post("/api/catalog/(\\w+)", r -> app.catalog.save(r.user, r.m.group(1), 0, r.body));
        put("/api/catalog/(\\w+)/(\\d+)", r -> app.catalog.save(r.user, r.m.group(1), r.id(2), r.body));
        delete("/api/catalog/(\\w+)/(\\d+)", r -> { app.catalog.delete(r.user, r.m.group(1), r.id(2)); return Map.of("ok", true); });
        get("/api/options/(\\w+)", true, r -> app.catalog.options(r.user, r.m.group(1)));
        get("/api/technicians", true, r -> app.catalog.technicians(r.user));
        get("/api/customer-by-phone", true, r -> {
            Object c = app.catalog.customerByPhone(r.user, r.q("phone"));
            return c == null ? Map.of("found", false) : Map.of("found", true, "customer", c);
        });

        // ---------- Bảo hành ----------
        get("/api/warranties", true, r -> app.warranty.listWarranties(r.user, r.q("q"), r.q("filter")));
        get("/api/warranties/(\\d+)", true, r -> app.warranty.getWarranty(r.user, r.id(1)));
        post("/api/warranties", r -> app.warranty.activate(r.user, r.body));
        delete("/api/warranties/(\\d+)", r -> { app.warranty.deleteWarranty(r.user, r.id(1)); return Map.of("ok", true); });
        get("/api/lookup", true, r -> app.warranty.lookup(r.user, r.q("q")));
        get("/api/serial", true, r -> {
            Object w = app.warranty.bySerial(r.user, r.q("s"));
            return w == null ? Map.of("found", false) : Map.of("found", true, "warranty", w);
        });

        // ---------- Phiếu ----------
        get("/api/tickets", true, r -> app.warranty.listTickets(r.user, r.q("status"), r.q("q")));
        post("/api/tickets", r -> app.warranty.createTicket(r.user, r.body));
        get("/api/tickets/(\\d+)", true, r -> app.warranty.getTicket(r.user, r.id(1)));
        post("/api/tickets/(\\d+)/status", r -> app.warranty.changeStatus(r.user, r.id(1), r.body));
        post("/api/tickets/(\\d+)/cost", r -> app.warranty.updateCost(r.user, r.id(1), r.body));
        post("/api/tickets/(\\d+)/parts", r -> app.warranty.addPart(r.user, r.id(1), Json.num(r.body, "part_id", 0), Json.num(r.body, "qty", 1)));
        delete("/api/tickets/(\\d+)/parts/(\\d+)", r -> app.warranty.removePart(r.user, r.id(1), r.id(2)));
        post("/api/tickets/(\\d+)/quote", r -> app.warranty.sendQuote(r.user, r.id(1)));
        post("/api/tickets/(\\d+)/quote-decision", r -> app.warranty.decideQuote(r.user, r.id(1),
                Boolean.TRUE.equals(r.body.get("approve")), Json.str(r.body, "note")));
        post("/api/tickets/(\\d+)/assign", r -> app.warranty.assign(r.user, r.id(1), Json.num(r.body, "technician_id", 0)));
        post("/api/tickets/(\\d+)/pay", r -> app.warranty.pay(r.user, r.id(1), r.body));
        get("/api/print-link", true, r -> Map.of("url", app.print.link(r.user, r.q("kind"), Long.parseLong(r.q("id")))));

        post("/api/tickets/(\\d+)/cancel", r -> {
            Map<String, Object> in = new LinkedHashMap<>(r.body);
            in.put("status", "CANCELLED");
            return app.warranty.changeStatus(r.user, r.id(1), in);
        });

        // ---------- Ảnh tình trạng máy ----------
        get("/api/tickets/(\\d+)/photos", true, r -> app.photos.list(r.user, r.id(1)));
        postRaw("/api/tickets/(\\d+)/photos", r -> {
            byte[] data;
            try (InputStream in = r.ex.getRequestBody()) {
                data = in.readNBytes(PhotoService.MAX_BYTES + 1);
            }
            return app.photos.add(r.user, r.id(1), data, header(r.ex, "X-File-Name"), header(r.ex, "X-Note"));
        });
        get("/api/photos/(\\d+)", true, r -> {
            Object[] img = app.photos.read(r.user, r.id(1));
            r.ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
            return new Binary((String) img[0], (byte[]) img[1]);
        });
        delete("/api/photos/(\\d+)", r -> { app.photos.delete(r.user, r.id(1)); return Map.of("ok", true); });

        // ---------- Gói bảo hành mở rộng ----------
        post("/api/warranties/(\\d+)/extend", r -> app.warranty.extend(r.user, r.id(1), r.body));

        // ---------- Yêu cầu bảo hành online ----------
        get("/api/requests", true, r -> app.requests.list(r.user, r.q("status")));
        get("/api/request-photos/(\\d+)", true, r -> {
            Object[] img = app.photos.readRequestPhoto(0, r.id(1));
            r.ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
            return new Binary((String) img[0], (byte[]) img[1]);
        });
        post("/api/requests/(\\d+)/confirm", r -> app.requests.confirm(r.user, r.id(1), Json.str(r.body, "appointment"), Json.str(r.body, "note")));
        post("/api/requests/(\\d+)/reject", r -> app.requests.reject(r.user, r.id(1), Json.str(r.body, "note")));
        post("/api/requests/(\\d+)/convert", r -> app.requests.convert(r.user, r.id(1), r.body));

        // ---------- Sao lưu ----------
        get("/api/backups", true, r -> app.backup.list(r.user));
        post("/api/backups", r -> app.backup.create(r.user, "thu-cong"));
        post("/api/backups/([\\w.-]+)/restore", r -> Map.of("safety", app.backup.restore(r.user, r.m.group(1))));
        get("/api/backups/([\\w.-]+\\.zip)", true, r -> {
            java.nio.file.Path f = app.backup.file(r.user, r.m.group(1));
            r.ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + f.getFileName() + "\"");
            return new Binary("application/zip", Files.readAllBytes(f));
        });

        // ---------- Cổng khách hàng (đăng nhập bằng SĐT) ----------
        kh("POST", "/api/kh/register", r -> customerLogin(r, app.customers.register(Json.str(r.body, "phone"),
                Json.str(r.body, "serial"), Json.str(r.body, "password"))));
        kh("POST", "/api/kh/login", r -> customerLogin(r, app.customers.login(Json.str(r.body, "phone"), Json.str(r.body, "password"))));
        kh("POST", "/api/kh/logout", r -> {
            app.customers.logout(cookie(r.ex, "ksid"));
            r.ex.getResponseHeaders().add("Set-Cookie", "ksid=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
            return Map.of("ok", true);
        });
        kh("GET", "/api/kh/me", r -> app.customers.me(customer(r)));
        kh("PUT", "/api/kh/profile", r -> app.customers.updateProfile(customer(r), r.body));
        kh("POST", "/api/kh/password", r -> {
            app.customers.changePassword(customer(r), Json.str(r.body, "old_password"), Json.str(r.body, "new_password"));
            return Map.of("ok", true);
        });
        kh("POST", "/api/kh/quote", r -> app.customers.decideQuote(customer(r), Json.num(r.body, "ticket_id", 0),
                Boolean.TRUE.equals(r.body.get("approve")), Json.str(r.body, "note")));
        kh("POST", "/api/kh/requests", r -> app.customers.createRequest(customer(r), r.body));
        kh("POST", "/api/kh/requests/(\\d+)/cancel", r -> app.customers.cancelRequest(customer(r), r.id(1)));
        khRaw("/api/kh/requests/(\\d+)/photos", r -> {
            long me = customer(r);
            byte[] data;
            try (InputStream in = r.ex.getRequestBody()) { data = in.readNBytes(PhotoService.MAX_BYTES + 1); }
            return app.photos.addRequestPhoto(me, r.id(1), data, header(r.ex, "X-File-Name"));
        });
        kh("GET", "/api/kh/photos/(\\d+)", r -> {
            Object[] img = app.photos.readRequestPhoto(customer(r), r.id(1));
            r.ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
            return new Binary((String) img[0], (byte[]) img[1]);
        });
        kh("DELETE", "/api/kh/photos/(\\d+)", r -> { app.photos.deleteRequestPhoto(customer(r), r.id(1)); return Map.of("ok", true); });
        kh("POST", "/api/kh/review", r -> app.customers.review(customer(r), Json.num(r.body, "ticket_id", 0),
                (int) Json.num(r.body, "stars", 0), Json.str(r.body, "comment")));
        kh("GET", "/api/kh/inbox", r -> app.customers.inbox(customer(r)));
        kh("GET", "/api/kh/print-link", r -> {
            long me = customer(r);
            String kind = r.q("kind");
            long id = parseLong(r.q("id"));
            app.customers.checkPrint(me, kind, id);
            return Map.of("url", app.print.linkFor(kind, id));
        });

        // ---------- Kho ----------
        get("/api/inventory/movements", true, r -> app.inventory.movements(r.user, parseLong(r.q("part_id")), 500));
        get("/api/inventory/low", true, r -> app.inventory.lowStock(r.user));
        post("/api/inventory/in", r -> app.inventory.stockIn(r.user, Json.num(r.body, "part_id", 0), Json.num(r.body, "qty", 0),
                Json.num(r.body, "cost_price", 0), Json.str(r.body, "note")));
        post("/api/inventory/adjust", r -> app.inventory.adjust(r.user, Json.num(r.body, "part_id", 0), Json.num(r.body, "actual", -1),
                Json.str(r.body, "note")));

        // ---------- Báo cáo, cài đặt, nhật ký ----------
        get("/api/reports", true, r -> app.reports.full(r.user, ReportService.period(r.q("from"), r.q("to"))));
        get("/api/reports/export", true, r -> {
            ReportService.Period p = ReportService.period(r.q("from"), r.q("to"));
            byte[] x = app.reports.exportXlsx(r.user, p);
            r.ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + ReportService.fileName(p) + "\"");
            return new Binary("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", x);
        });
        get("/api/settings", true, r -> {
            Map<String, Object> m = new LinkedHashMap<>(app.settings.all());
            m.put("_portal_base", app.settings.portalBase());
            return m;
        });
        put("/api/settings", r -> app.settings.save(r.user, r.body));
        get("/api/audit", true, r -> app.audit.list(r.user, r.q("q"), 500));

        // ---------- Thông báo cho khách ----------
        get("/api/notify/config", true, r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fields", app.notify.fieldDefs());
            m.put("values", app.notify.getConfig(r.user));
            return m;
        });
        put("/api/notify/config", r -> app.notify.saveConfig(r.user, r.body));
        post("/api/notify/test", r -> Map.of("message", app.notify.sendTest(r.user, Json.str(r.body, "channel"), Json.str(r.body, "to"))));
        get("/api/notify", true, r -> app.notify.list(r.user, r.q("status"), r.q("q"), parseLong(r.q("ticket_id"))));
        get("/api/notify/stats", true, r -> app.notify.stats(r.user));
        post("/api/notify/(\\d+)/retry", r -> { app.notify.retry(r.user, r.id(1)); return Map.of("ok", true); });
        post("/api/notify/run", r -> {
            vn.baohanh.service.Auth.require(r.user, vn.baohanh.model.Role.ADMIN);
            int scanned = app.notify.scanExpiring();
            return Map.of("queued", scanned, "sent", app.notify.processDue());
        });
    }

    private long customer(Req r) {
        return app.customers.require(cookie(r.ex, "ksid"));
    }

    private Object customerLogin(Req r, String token) throws Exception {
        r.ex.getResponseHeaders().add("Set-Cookie", "ksid=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800");
        return app.customers.me(app.customers.require(token));
    }

    private static long parseLong(String s) {
        try { return s.isBlank() ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private record Binary(String contentType, byte[] data) {}

    private Map<String, Object> meta(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", u.toMap());
        Map<String, Object> st = new LinkedHashMap<>();
        for (TicketStatus s : TicketStatus.values()) st.put(s.name(), s.getLabel());
        m.put("statuses", st);
        Map<String, Object> roles = new LinkedHashMap<>();
        for (Role r : Role.values()) roles.put(r.name(), r.getLabel());
        m.put("roles", roles);
        m.put("pay_methods", WarrantyService.PAY_METHODS);
        m.put("quote_labels", WarrantyService.QUOTE_LABELS);
        m.put("stock_types", InventoryService.TYPE_LABELS);
        m.put("setting_keys", SettingService.KEYS);
        m.put("setting_hints", SettingService.HINTS);
        m.put("request_labels", CustomerService.REQUEST_LABELS);
        List<Object> ents = new ArrayList<>();
        for (EntityDef d : app.catalog.all()) ents.add(d.toMap(u.role()));
        m.put("entities", ents);
        return m;
    }

    // =====================================================================
    //  XỬ LÝ YÊU CẦU
    // =====================================================================

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "same-origin");
            if (path.startsWith("/in/")) { servePrint(ex, path); return; }
            if (!path.startsWith("/api/")) { serveStatic(ex, path); return; }

            Req r = new Req();
            r.ex = ex;
            r.method = method;
            r.path = path;
            r.q = parseQuery(ex.getRequestURI().getRawQuery());
            boolean pathMatched = false;
            Route route = null;
            for (Route rt : routes) {
                Matcher m = rt.pattern().matcher(path);
                if (m.matches()) {
                    pathMatched = true;
                    if (rt.method().equals(method)) { route = rt; r.m = m; break; }
                }
            }
            if (route == null) {
                sendJson(ex, pathMatched ? 405 : 404, Map.of("error", pathMatched ? "Phương thức không hỗ trợ" : "Không tìm thấy: " + path));
                return;
            }
            if (!"GET".equals(method) && !"1".equals(ex.getRequestHeaders().getFirst("X-App"))) {
                sendJson(ex, 403, Map.of("error", "Yêu cầu không hợp lệ"));
                return;
            }
            if (route.auth()) {
                r.user = app.auth.fromSession(cookie(ex, "sid"));
                if (r.user == null) { sendJson(ex, 401, Map.of("error", "Phiên đăng nhập đã hết, vui lòng đăng nhập lại")); return; }
            }
            r.body = "GET".equals(method) || "DELETE".equals(method) || route.raw() ? new HashMap<>() : readBody(ex);
            Object result = route.handler().handle(r);
            if (result instanceof Binary b) sendBytes(ex, 200, b.contentType(), b.data());
            else sendJson(ex, 200, result);
        } catch (AppException e) {
            sendJson(ex, e.getStatus(), Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (java.sql.SQLException e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Lỗi cơ sở dữ liệu: " + e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Lỗi máy chủ: " + e));
        } finally {
            ex.close();
        }
    }

    private void servePrint(HttpExchange ex, String path) throws Exception {
        Matcher m = Pattern.compile("/in/(tiep-nhan|tra-may|bao-hanh)/(\\d+)").matcher(path);
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        if (!m.matches()) { sendText(ex, 404, "Không tìm thấy"); return; }
        long id = Long.parseLong(m.group(2));
        if (!app.print.verify(m.group(1), id, q.get("exp"), q.get("sig"))) {
            sendText(ex, 403, "Liên kết in đã hết hạn. Vui lòng bấm \"In phiếu\" lại trong phần mềm.");
            return;
        }
        byte[] html = app.print.render(m.group(1), id).getBytes(StandardCharsets.UTF_8);
        sendBytes(ex, 200, "text/html; charset=utf-8", html);
    }

    private static Map<String, Object> readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) throw new AppException(413, "Dữ liệu gửi lên quá lớn");
            return Json.parseObject(new String(b, StandardCharsets.UTF_8));
        }
    }

    /** Đọc header văn bản: chấp nhận cả dạng đã mã hóa URL lẫn UTF-8 thô. */
    static String header(HttpExchange ex, String name) {
        String v = ex.getRequestHeaders().getFirst(name);
        if (v == null) return "";
        String utf8 = new String(v.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        try {
            return URLDecoder.decode(utf8.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return utf8;
        }
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> all = ex.getRequestHeaders().get("Cookie");
        if (all == null) return null;
        for (String header : all)
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        return null;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void sendJson(HttpExchange ex, int code, Object data) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        sendBytes(ex, code, "application/json; charset=utf-8", Json.stringify(data).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        sendBytes(ex, code, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Trang tĩnh: đọc từ thư mục web/ (chạy trong VS Code) hoặc classpath (file .jar). */
    private static void serveStatic(HttpExchange ex, String path) throws IOException {
        String file = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/tra-cuu", "/tra-cuu/", "/portal.html" -> "portal.html";
            default -> null;
        };
        if (file == null) { sendText(ex, 404, "Không tìm thấy"); return; }
        byte[] bytes = null;
        Path p = Path.of("web", file);
        if (Files.isRegularFile(p)) {
            bytes = Files.readAllBytes(p);
        } else {
            try (InputStream in = ApiServer.class.getResourceAsStream("/web/" + file)) {
                if (in != null) bytes = in.readAllBytes();
            }
        }
        if (bytes == null) { sendText(ex, 404, "Thiếu file web/" + file); return; }
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        sendBytes(ex, 200, "text/html; charset=utf-8", bytes);
    }
}
