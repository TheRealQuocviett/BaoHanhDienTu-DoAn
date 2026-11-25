# Kết quả thử tải – 50.000 phiếu bảo hành

Đo lúc 26/11/2026 02:05 bằng công cụ `vn.baohanh.tools.ThuTai` (chạy `thu-tai.bat`). Mỗi thao tác chạy 1 lần làm nóng rồi đo 3 lần, lấy **trung vị**.

> **Số liệu mẫu** do người viết đo trên máy thử Linux 2 nhân, qua một driver SQLite trung gian (chậm hơn driver thật khi trả nhiều dòng) – con số tuyệt đối sẽ khác trên máy bạn, nhưng mức cải thiện giữa A và B là cùng bản chất. Chạy `thu-tai.bat` để thay file này bằng số đo của chính máy bạn, hoặc xem bảng đo bằng driver thật trong tab **Actions** trên GitHub (mục *Summary* của mỗi lần chạy).

## Môi trường

| Mục | Giá trị |
|---|---|
| Hệ điều hành | Linux 6.18.44-fc-v880 |
| Java | 21.0.13.0.3 (Ubuntu) |
| Số nhân CPU | 2 |
| Bộ nhớ tối đa JVM | 1536 MB |
| SQLite | 3.53.1 |
| Dung lượng CSDL | 32.3 MB |
| Thời gian tạo dữ liệu giả | 1.0 giây |

## Dữ liệu thử

| Bảng | Số dòng |
|---|---:|
| Khách hàng (`customer`) | 25.049 |
| Bảo hành (`warranty`) | 40.051 |
| Phiếu bảo hành (`ticket`) | 50.052 |
| Linh kiện đã thay (`ticket_part`) | 22.852 |
| Lịch sử xử lý (`ticket_history`) | 99.864 |

## Thời gian phản hồi (mili giây)

A = chưa có 7 chỉ mục mới · B = đã thêm chỉ mục (phiên bản 10). Dưới 100 ms người dùng thấy như tức thì; trên 1 giây là thấy chậm.

| Nhóm | Thao tác | A: chưa chỉ mục | B: có chỉ mục | Nhanh hơn | Kết quả |
|---|---|---:|---:|---:|---|
| Danh sách phiếu | Tải TẤT CẢ phiếu một lần (cách cũ) | 95.642 | 926 | **×103** | 50052 |
| Danh sách phiếu | Trang 1 – 50 phiếu mới nhất (phân trang) | 711 | 1,2 | **×576** | 50/50052 |
| Danh sách phiếu | Trang 500 – giữa danh sách (phân trang) | 80.845 | 61,9 | **×1.307** | 50/50052 |
| Danh sách phiếu | Lọc "Đang xử lý" | 4.350 | 3,3 | **×1.299** | 50/2391 |
| Danh sách phiếu | Lọc "Quá hẹn" | 127 | 18,4 | **×6,9** | 49/49 |
| Danh sách phiếu | "Phiếu của tôi" (kỹ thuật viên) | 2.239 | 8,6 | **×260** | 50/1195 |
| Danh sách phiếu | Tìm theo SĐT khách | 512 | 421 | **×1,2** | 3/3 |
| Danh sách phiếu | Tìm theo tên khách (không dấu) | 488 | 368 | **×1,3** | 3/3 |
| Phiếu | Mở chi tiết 1 phiếu | 3,1 | 0,3 | **×10** | PBT-0025001 |
| Bảo hành | Tải TẤT CẢ bảo hành (cách cũ) | 528 | 391 | **×1,4** | 40051 |
| Bảo hành | Trang 1 – 50 bảo hành (phân trang) | 42,0 | 0,8 | **×56** | 50/40051 |
| Bảo hành | Tra cứu theo serial (quầy) | 2,5 | 0,3 | **×7,3** | 1 |
| Bảo hành | Cổng khách: tra cứu serial | 6,5 | 3,9 | **×1,7** | 1 |
| Khách hàng | Trang 1 danh sách khách (phân trang) | 0,8 | 0,7 | ≈ | 50/25049 |
| Khách hàng | Tải TẤT CẢ khách (cách cũ) | 115 | 105 | ≈ | 25049 |
| Tổng quan | Bảng điều khiển (trang chủ) | 4.662 | 71,2 | **×66** | 21 |
| Báo cáo | Báo cáo cả năm 2026 | 17.737 | 684 | **×26** | 10 |
| Báo cáo | Thống kê lỗi thường gặp (2 năm) | 1.841 | 1.127 | **×1,6** | 12 |
| Thông minh | Gợi ý chẩn đoán | 23.708 | 262 | **×90** | 16 |
| Thông minh | Dự báo linh kiện cần đặt | 0,6 | 0,4 | **×1,5** | 3 |

## Nhận xét

- **Tổng hợp**: trước khi tối ưu, mở màn hình danh sách phiếu (tải hết, chưa có chỉ mục) mất **95.642 ms**; sau khi tối ưu (chỉ mục + phân trang) còn **1,2 ms** – nhanh hơn khoảng **×77.500**.
- **Phân trang**: đã có chỉ mục, tải cả 50.000 phiếu một lần vẫn mất 926 ms; chỉ tải trang đầu 50 phiếu còn 1,2 ms (nhanh hơn ×751). Trình duyệt cũng không phải vẽ hàng chục nghìn dòng.
- **Chỉ mục**: so cột A và B. Chỉ mục `idx_ticket_part_ticket` giúp view tính chi phí phiếu không phải quét cả bảng linh kiện cho từng phiếu; `idx_ticket_received` giúp SQLite đọc thẳng 50 phiếu mới nhất theo thứ tự thay vì sắp xếp toàn bộ bảng.
- Tra cứu theo serial / mã phiếu dùng chỉ mục UNIQUE có sẵn nên luôn nhanh dù dữ liệu lớn.
- Tìm kiếm không dấu (gõ "nguyen van" ra "Nguyễn Văn") được lọc trong Java trên các cột cần tìm; chỉ các dòng của trang hiện tại mới được nạp đầy đủ.

## Kế hoạch truy vấn của SQLite (EXPLAIN QUERY PLAN) cho "50 phiếu mới nhất"

- A: `SCAN t USING INDEX idx_ticket_warranty; SEARCH w USING INTEGER PRIMARY KEY (rowid=?); USE TEMP B-TREE FOR ORDER BY`
- B: `SCAN t USING INDEX idx_ticket_received; SEARCH w USING INTEGER PRIMARY KEY (rowid=?)`

`USE TEMP B-TREE FOR ORDER BY` = phải sắp xếp toàn bộ bảng trước khi lấy 50 dòng; có chỉ mục thì SQLite đọc theo thứ tự có sẵn và dừng sau 50 dòng.

## Các chỉ mục đã thêm

| Tên | Trên | Dùng cho |
|---|---|---|
| `idx_ticket_part_ticket` | `ticket_part(ticket_id)` | Linh kiện của từng phiếu (dùng trong view tính chi phí phiếu) |
| `idx_ticket_received` | `ticket(received_at, id)` | Sắp xếp danh sách phiếu mới nhất trước → đọc đúng 1 trang |
| `idx_ticket_tech` | `ticket(technician_id, status)` | Lọc "Phiếu của tôi" cho kỹ thuật viên |
| `idx_ticket_due` | `ticket(due_date)` | Lọc phiếu quá hẹn / sắp đến hẹn |
| `idx_warranty_created` | `warranty(created_at, id)` | Sắp xếp danh sách bảo hành theo trang |
| `idx_stock_ticket` | `stock_movement(ticket_id)` | Thẻ kho theo phiếu |
| `idx_request_customer` | `service_request(customer_id)` | Yêu cầu online của từng khách |

_Số liệu phụ thuộc cấu hình máy; chạy lại `thu-tai.bat` trên máy của bạn để có số liệu của chính máy đó._
