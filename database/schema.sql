-- =====================================================================
--  CƠ SỞ DỮ LIỆU: HỆ THỐNG QUẢN LÝ BẢO HÀNH ĐIỆN TỬ  (SQLite, phiên bản 4)
--  File CSDL: data/baohanh.db – tạo tự động khi chạy chương trình.
--  Ngày lưu dạng 'YYYY-MM-DD', ngày giờ dạng 'YYYY-MM-DDTHH:MM:SS'.
--  Tiền lưu số nguyên (VNĐ).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. NGƯỜI DÙNG & NHẬT KÝ
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    username        TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash   TEXT NOT NULL,                 -- PBKDF2-SHA256, dạng "số_vòng:salt:hash"
    full_name       TEXT NOT NULL,
    phone           TEXT,
    role            TEXT NOT NULL CHECK (role IN ('ADMIN','RECEPTION','TECHNICIAN')),
    active          INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    created_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    time            TEXT NOT NULL,
    user_id         INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    action          TEXT NOT NULL,                 -- vd: ĐĂNG NHẬP, THÊM, SỬA, XÓA, ĐỔI TRẠNG THÁI
    entity          TEXT,                          -- bảng / đối tượng bị tác động
    entity_id       INTEGER,
    detail          TEXT
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(time);

-- Thông tin cửa hàng và cấu hình (tên, địa chỉ, địa chỉ cổng tra cứu...)
CREATE TABLE IF NOT EXISTS setting (
    key             TEXT PRIMARY KEY,
    value           TEXT
);

-- ---------------------------------------------------------------------
-- 2. DANH MỤC
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    phone           TEXT NOT NULL UNIQUE,
    email           TEXT,
    address         TEXT,
    note            TEXT,
    created_at      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS brand (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL UNIQUE COLLATE NOCASE,
    country         TEXT,
    hotline         TEXT
);

-- Trung tâm bảo hành ủy quyền của hãng (nơi cửa hàng gửi máy)
CREATE TABLE IF NOT EXISTS service_center (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    brand_id        INTEGER NOT NULL REFERENCES brand(id) ON DELETE RESTRICT,
    name            TEXT NOT NULL,
    phone           TEXT,
    address         TEXT
);

CREATE TABLE IF NOT EXISTS category (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL UNIQUE COLLATE NOCASE
);

CREATE TABLE IF NOT EXISTS product (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    model_code      TEXT NOT NULL UNIQUE COLLATE NOCASE,   -- Mã model
    name            TEXT NOT NULL,
    brand_id        INTEGER NOT NULL REFERENCES brand(id) ON DELETE RESTRICT,
    category_id     INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    default_months  INTEGER NOT NULL DEFAULT 12 CHECK (default_months BETWEEN 1 AND 120),
    price           INTEGER NOT NULL DEFAULT 0 CHECK (price >= 0)
);

-- ---------------------------------------------------------------------
-- 3. BẢO HÀNH ĐIỆN TỬ
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS warranty (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    code            TEXT UNIQUE,                           -- BH2611-0001
    serial          TEXT NOT NULL UNIQUE COLLATE NOCASE,   -- Serial / IMEI
    product_id      INTEGER NOT NULL REFERENCES product(id) ON DELETE RESTRICT,
    customer_id     INTEGER NOT NULL REFERENCES customer(id) ON DELETE RESTRICT,
    invoice_no      TEXT,
    purchase_date   TEXT NOT NULL,
    months          INTEGER NOT NULL CHECK (months BETWEEN 1 AND 120),
    expiry_date     TEXT NOT NULL,
    note            TEXT,
    created_by      INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    created_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_warranty_customer ON warranty(customer_id);
CREATE INDEX IF NOT EXISTS idx_warranty_product  ON warranty(product_id);
CREATE INDEX IF NOT EXISTS idx_warranty_expiry   ON warranty(expiry_date);

-- ---------------------------------------------------------------------
-- 4. LINH KIỆN & KHO
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS part (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    code            TEXT NOT NULL UNIQUE COLLATE NOCASE,
    name            TEXT NOT NULL,
    brand_id        INTEGER REFERENCES brand(id) ON DELETE SET NULL,
    unit            TEXT NOT NULL DEFAULT 'cái',
    cost_price      INTEGER NOT NULL DEFAULT 0 CHECK (cost_price >= 0),   -- Giá nhập
    sell_price      INTEGER NOT NULL DEFAULT 0 CHECK (sell_price >= 0),   -- Giá bán cho khách
    stock           INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),        -- Tồn kho
    min_stock       INTEGER NOT NULL DEFAULT 0 CHECK (min_stock >= 0)     -- Mức cảnh báo
);

CREATE TABLE IF NOT EXISTS stock_movement (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    part_id         INTEGER NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    time            TEXT NOT NULL,
    qty             INTEGER NOT NULL,                       -- + nhập, - xuất
    type            TEXT NOT NULL CHECK (type IN ('IN','OUT','RETURN','ADJUST')),
    ticket_id       INTEGER REFERENCES ticket(id) ON DELETE SET NULL,
    user_id         INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    note            TEXT
);
CREATE INDEX IF NOT EXISTS idx_stock_part ON stock_movement(part_id);

-- ---------------------------------------------------------------------
-- 5. PHIẾU BẢO HÀNH / SỬA CHỮA
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    code              TEXT UNIQUE,                          -- PBH-000001
    warranty_id       INTEGER NOT NULL REFERENCES warranty(id) ON DELETE RESTRICT,
    issue             TEXT NOT NULL,
    accessories       TEXT,
    condition_note    TEXT,
    in_warranty       INTEGER NOT NULL CHECK (in_warranty IN (0,1)),
    received_at       TEXT NOT NULL,
    received_by       INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    due_date          TEXT,
    status            TEXT NOT NULL CHECK (status IN ('RECEIVED','DIAGNOSING','SENT_TO_VENDOR','REPAIRING',
                                                      'WAITING_PARTS','READY','REJECTED','RETURNED','CANCELLED')),
    technician_id     INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    service_center_id INTEGER REFERENCES service_center(id) ON DELETE SET NULL,
    diagnosis         TEXT,                                 -- Kết luận kỹ thuật
    labor_cost        INTEGER NOT NULL DEFAULT 0 CHECK (labor_cost >= 0),   -- Tiền công
    quote_status      TEXT NOT NULL DEFAULT 'NONE' CHECK (quote_status IN ('NONE','PENDING','APPROVED','DECLINED')),
    paid_amount       INTEGER NOT NULL DEFAULT 0,
    payment_method    TEXT,
    invoice_no        TEXT UNIQUE,                          -- Số hóa đơn sửa chữa (khi thu tiền)
    returned_at       TEXT
);
CREATE INDEX IF NOT EXISTS idx_ticket_warranty ON ticket(warranty_id);
CREATE INDEX IF NOT EXISTS idx_ticket_status   ON ticket(status);

-- Linh kiện dùng cho phiếu
CREATE TABLE IF NOT EXISTS ticket_part (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id       INTEGER NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    part_id         INTEGER NOT NULL REFERENCES part(id) ON DELETE RESTRICT,
    qty             INTEGER NOT NULL CHECK (qty > 0),
    unit_price      INTEGER NOT NULL CHECK (unit_price >= 0),
    charged         INTEGER NOT NULL CHECK (charged IN (0,1))   -- 0: miễn phí (trong bảo hành)
);

CREATE TABLE IF NOT EXISTS ticket_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id       INTEGER NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    time            TEXT NOT NULL,
    status          TEXT NOT NULL,
    note            TEXT,
    user_id         INTEGER REFERENCES app_user(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_history_ticket ON ticket_history(ticket_id);

-- Ảnh tình trạng máy khi nhận / trong quá trình sửa (file ảnh lưu ở data/photos/)
CREATE TABLE IF NOT EXISTS ticket_photo (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id       INTEGER NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    file_name       TEXT NOT NULL,                  -- tên file trên đĩa
    original_name   TEXT,
    content_type    TEXT NOT NULL,
    size            INTEGER NOT NULL,
    note            TEXT,
    uploaded_by     INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    uploaded_at     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_photo_ticket ON ticket_photo(ticket_id);

-- ---------------------------------------------------------------------
-- 6. KHÁCH HÀNG TRỰC TUYẾN & GÓI BẢO HÀNH MỞ RỘNG  (phiên bản 3)
-- ---------------------------------------------------------------------
-- Tài khoản đăng nhập cổng khách hàng (đăng nhập bằng SĐT)
CREATE TABLE IF NOT EXISTS customer_account (
    customer_id     INTEGER PRIMARY KEY REFERENCES customer(id) ON DELETE CASCADE,
    password_hash   TEXT NOT NULL,
    created_at      TEXT NOT NULL,
    last_login      TEXT
);

-- Yêu cầu bảo hành khách gửi online, cửa hàng hẹn lịch rồi tiếp nhận thành phiếu
CREATE TABLE IF NOT EXISTS service_request (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    code            TEXT UNIQUE,                    -- YC-000001
    customer_id     INTEGER NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    warranty_id     INTEGER NOT NULL REFERENCES warranty(id) ON DELETE CASCADE,
    issue           TEXT NOT NULL,
    preferred_date  TEXT,                           -- ngày khách muốn mang máy tới
    appointment     TEXT,                           -- ngày cửa hàng hẹn
    status          TEXT NOT NULL CHECK (status IN ('NEW','CONFIRMED','CONVERTED','REJECTED','CANCELLED')),
    staff_note      TEXT,
    ticket_id       INTEGER REFERENCES ticket(id) ON DELETE SET NULL,
    created_at      TEXT NOT NULL,
    handled_by      INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    handled_at      TEXT
);
CREATE INDEX IF NOT EXISTS idx_request_status ON service_request(status);

-- Gói bảo hành mở rộng (gia hạn có thu phí)
CREATE TABLE IF NOT EXISTS warranty_extension (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    code            TEXT UNIQUE,                    -- GBH2611-0001 (phiếu thu nội bộ)
    warranty_id     INTEGER NOT NULL REFERENCES warranty(id) ON DELETE CASCADE,
    months          INTEGER NOT NULL CHECK (months > 0),
    price           INTEGER NOT NULL CHECK (price >= 0),
    payment_method  TEXT,
    old_expiry      TEXT NOT NULL,
    new_expiry      TEXT NOT NULL,
    note            TEXT,
    created_by      INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    created_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ext_warranty ON warranty_extension(warranty_id);

-- ---------------------------------------------------------------------
-- 7. THÔNG BÁO CHO KHÁCH (email / SMS / Zalo)  (phiên bản 4)
-- ---------------------------------------------------------------------
-- Hàng đợi + nhật ký: mỗi dòng là một tin gửi qua một kênh. Gửi lỗi thì tự thử lại.
CREATE TABLE IF NOT EXISTS notification (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at      TEXT NOT NULL,
    event           TEXT NOT NULL,                  -- RECEIVED, QUOTE, READY, REQUEST_CONFIRMED, REQUEST_REJECTED, EXPIRING, TEST
    channel         TEXT NOT NULL CHECK (channel IN ('EMAIL','SMS','ZALO')),
    recipient       TEXT NOT NULL,                  -- email hoặc số điện thoại
    subject         TEXT,
    body            TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_try        TEXT NOT NULL,
    last_error      TEXT,
    provider_id     TEXT,                           -- mã tin do nhà cung cấp trả về
    sent_at         TEXT,
    customer_id     INTEGER REFERENCES customer(id) ON DELETE SET NULL,
    ticket_id       INTEGER REFERENCES ticket(id) ON DELETE SET NULL,
    warranty_id     INTEGER REFERENCES warranty(id) ON DELETE SET NULL,
    request_id      INTEGER REFERENCES service_request(id) ON DELETE SET NULL,
    dedup_key       TEXT UNIQUE                     -- chống gửi trùng cùng một sự kiện
);
CREATE INDEX IF NOT EXISTS idx_notify_status ON notification(status, next_try);

-- Khách không muốn nhận tin nhắc hạn bảo hành / ưu đãi (tin giao dịch vẫn gửi)
CREATE TABLE IF NOT EXISTS notify_optout (
    customer_id     INTEGER PRIMARY KEY REFERENCES customer(id) ON DELETE CASCADE,
    created_at      TEXT NOT NULL
);

-- ---------------------------------------------------------------------
-- 7b. ĐÁNH GIÁ SAU SỬA CHỮA & ẢNH KHÁCH GỬI KÈM YÊU CẦU  (phiên bản 5)
-- ---------------------------------------------------------------------
-- Khách chấm sao phiếu đã trả máy (mỗi phiếu một đánh giá, sửa lại được)
CREATE TABLE IF NOT EXISTS ticket_review (
    ticket_id       INTEGER PRIMARY KEY REFERENCES ticket(id) ON DELETE CASCADE,
    customer_id     INTEGER NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    stars           INTEGER NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment         TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT
);
CREATE INDEX IF NOT EXISTS idx_review_time ON ticket_review(created_at);

-- Ảnh khách tải lên khi gửi yêu cầu bảo hành online (file ở data/photos/yc-<id>/)
CREATE TABLE IF NOT EXISTS request_photo (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id      INTEGER NOT NULL REFERENCES service_request(id) ON DELETE CASCADE,
    file_name       TEXT NOT NULL,
    original_name   TEXT,
    content_type    TEXT NOT NULL,
    size            INTEGER NOT NULL,
    uploaded_at     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_req_photo ON request_photo(request_id);

-- ---------------------------------------------------------------------
-- 7c. TIN NHẮN GIỮA KHÁCH VÀ CỬA HÀNG TRONG PHIẾU  (phiên bản 6)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_message (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id         INTEGER NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    sender            TEXT NOT NULL CHECK (sender IN ('CUSTOMER','STAFF')),
    user_id           INTEGER REFERENCES app_user(id) ON DELETE SET NULL,   -- nhân viên trả lời
    body              TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    read_by_staff     INTEGER NOT NULL DEFAULT 0,
    read_by_customer  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_msg_ticket ON ticket_message(ticket_id, id);
CREATE INDEX IF NOT EXISTS idx_msg_unread ON ticket_message(sender, read_by_staff);

-- ---------------------------------------------------------------------
-- 7d. NHÀ CUNG CẤP & ĐƠN ĐẶT HÀNG LINH KIỆN  (phiên bản 7)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS supplier (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    contact         TEXT,
    phone           TEXT,
    email           TEXT,
    address         TEXT,
    note            TEXT
);

CREATE TABLE IF NOT EXISTS purchase_order (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    code            TEXT NOT NULL UNIQUE,                  -- DH-000001
    supplier_id     INTEGER NOT NULL REFERENCES supplier(id),
    status          TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ORDERED','RECEIVED','CANCELLED')),
    expected_date   TEXT,
    note            TEXT,
    created_by      INTEGER REFERENCES app_user(id) ON DELETE SET NULL,
    created_at      TEXT NOT NULL,
    ordered_at      TEXT,
    received_at     TEXT,
    received_by     INTEGER REFERENCES app_user(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_po_status ON purchase_order(status);

CREATE TABLE IF NOT EXISTS purchase_order_line (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    po_id           INTEGER NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    part_id         INTEGER NOT NULL REFERENCES part(id),
    qty             INTEGER NOT NULL CHECK (qty > 0),
    unit_cost       INTEGER NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    received_qty    INTEGER NOT NULL DEFAULT 0 CHECK (received_qty >= 0)
);
CREATE INDEX IF NOT EXISTS idx_pol_po ON purchase_order_line(po_id);

-- ---------------------------------------------------------------------
-- 8. VIEW tiện tra cứu (dùng trong báo cáo / xem nhanh bằng DB Browser)
-- ---------------------------------------------------------------------
CREATE VIEW IF NOT EXISTS v_warranty AS
SELECT w.*, p.name AS product_name, p.model_code, b.name AS brand_name, c.name AS category_name,
       cu.name AS customer_name, cu.phone AS customer_phone
FROM warranty w
JOIN product p   ON p.id = w.product_id
JOIN brand b     ON b.id = p.brand_id
JOIN category c  ON c.id = p.category_id
JOIN customer cu ON cu.id = w.customer_id;

CREATE VIEW IF NOT EXISTS v_ticket_cost AS
SELECT t.id AS ticket_id,
       t.labor_cost,
       COALESCE((SELECT SUM(tp.qty * tp.unit_price) FROM ticket_part tp WHERE tp.ticket_id = t.id AND tp.charged = 1), 0) AS parts_charged,
       COALESCE((SELECT SUM(tp.qty * tp.unit_price) FROM ticket_part tp WHERE tp.ticket_id = t.id AND tp.charged = 0), 0) AS parts_free,
       t.labor_cost + COALESCE((SELECT SUM(tp.qty * tp.unit_price) FROM ticket_part tp WHERE tp.ticket_id = t.id AND tp.charged = 1), 0) AS total
FROM ticket t;
