# Hệ thống quản lý bảo hành điện tử (Java + SQLite)

[![Kiểm thử tự động](https://github.com/TheRealQuocviett/BaoHanhDienTu-DoAn/actions/workflows/kiem-thu.yml/badge.svg)](https://github.com/TheRealQuocviett/BaoHanhDienTu-DoAn/actions/workflows/kiem-thu.yml)

Phần mềm quản lý bảo hành cho **cửa hàng / đại lý bán lẻ thiết bị điện tử**. Phần mềm lo trọn quy trình: kích hoạt bảo hành điện tử lúc bán hàng, tiếp nhận máy hỏng, sửa chữa (linh kiện, báo giá cho máy hết hạn), thu tiền, trả máy, rồi thống kê báo cáo.

Chạy **một chương trình Java** là có cùng lúc:

| Giao diện | Dành cho | Địa chỉ |
|---|---|---|
| **Ứng dụng Java Swing** | Nhân viên (cửa sổ đăng nhập tự mở) | — |
| **Giao diện web** | Nhân viên (mở bằng trình duyệt, kể cả điện thoại) | http://localhost:8080 |
| **Cổng khách hàng** | Khách tra cứu nhanh bằng serial, hoặc đăng nhập bằng SĐT để xem mọi máy của mình, duyệt báo giá, đặt lịch bảo hành | http://localhost:8080/tra-cuu |

Swing và web **dùng chung một tầng nghiệp vụ và một CSDL**. Thao tác ở bên này thì bên kia thấy ngay, và quy tắc nghiệp vụ hay phân quyền của hai bên là một.

---

## 1. Cài đặt và chạy trong Visual Studio Code

1. Cài **JDK 17 trở lên** (khuyên dùng Eclipse Temurin 21). Kiểm tra bằng lệnh `java -version`.
2. Trong VS Code, cài extension **Extension Pack for Java** của Microsoft. Nếu đã cài extension "Java" của Oracle thì nên **Disable** nó vì hai extension có thể xung đột.
3. Vào **File → Open Folder…** và chọn thư mục `BaoHanhDienTu`, tức thư mục chứa file README này.
4. **Tải driver SQLite (chỉ cần làm một lần, máy phải có mạng):** mở **Terminal → New Terminal** rồi gõ `.\tai-thu-vien.bat`. Thư mục `lib/` sẽ có 3 file `.jar`.
5. Đợi góc dưới hiện **Java: Ready**. Sau đó mở `src/main/java/vn/baohanh/Main.java` và bấm **Run** (hoặc nhấn **F5**, chọn *Chạy hệ thống bảo hành (Swing + web)*).
6. Cửa sổ đăng nhập Swing hiện ra. Giao diện web có tại http://localhost:8080.

> Không dùng VS Code thì nhấp đúp `run.bat` (Windows) hoặc chạy `./run.sh` (macOS/Linux). Script tự tải driver nếu chưa có.
> Cấu hình *Chỉ chạy web* trong VS Code sẽ không mở Swing mà mở thẳng trình duyệt.

### Tài khoản mẫu

| Tên đăng nhập | Mật khẩu | Vai trò |
|---|---|---|
| `admin` | `admin123` | Quản lý |
| `tiepnhan` | `123456` | Nhân viên tiếp nhận |
| `ktv1`, `ktv2` | `123456` | Kỹ thuật viên |

Nên đổi mật khẩu sau khi đăng nhập (nút **Đổi mật khẩu**).

**Tài khoản khách hàng mẫu** (đăng nhập ở `/tra-cuu` → *Tài khoản của tôi*): `0903123456` / `123456` (Nguyễn Văn An), `0934567890` / `123456` (Phạm Thu Hà). Khách khác tự đăng ký bằng SĐT + số Serial của máy đã mua.

**Kiểm thử tự động:** trong VS Code chọn cấu hình *Chạy kiểm thử tự động* rồi nhấn F5 (kết quả hiện ở tab **Debug Console**), hoặc nhấp đúp `run-tests.bat`. Chương trình chạy 205 kiểm thử nghiệp vụ (kể cả gửi email/SMS/Zalo qua máy chủ giả) trên một CSDL tạm, không đụng tới dữ liệu thật.

Nếu bạn đã dùng phiên bản trước, CSDL được **tự nâng cấp và giữ nguyên dữ liệu**. Dữ liệu mẫu cho các chức năng mới chỉ có khi tạo CSDL mới.

Lần chạy đầu, phần mềm tự tạo CSDL và **dữ liệu mẫu**: 7 hãng, 15 model, 13 linh kiện, khoảng 27 bảo hành, 25 phiếu (gồm phiếu cũ trong 12 tháng để báo cáo có số liệu). Muốn làm lại từ đầu thì tắt chương trình, xóa thư mục `data`, rồi chạy lại.

---

## 2. Chức năng

### Phân quyền

| Chức năng | Quản lý | Tiếp nhận | Kỹ thuật viên |
|---|:-:|:-:|:-:|
| Tổng quan, tra cứu, xem phiếu và bảo hành | ✓ | ✓ | ✓ |
| Kích hoạt bảo hành, tiếp nhận máy, phân công KTV | ✓ | ✓ | |
| Ghi nhận khách đồng ý / từ chối báo giá, thu tiền & trả máy, hủy phiếu | ✓ | ✓ | |
| Kiểm tra, sửa chữa, gửi hãng, dùng linh kiện, tiền công, gửi báo giá | ✓ | | ✓ |
| Thêm/sửa khách hàng | ✓ | ✓ | |
| Danh mục hãng, TTBH hãng, loại, sản phẩm, linh kiện; nhập kho, kiểm kê | ✓ | | |
| Thẻ kho | ✓ | | ✓ |
| Xem yêu cầu bảo hành online | ✓ | ✓ | ✓ |
| Hẹn lịch / tiếp nhận / từ chối yêu cầu online, bán gói BH mở rộng | ✓ | ✓ | |
| Thêm ảnh tình trạng máy | ✓ | ✓ | ✓ |
| Sao lưu / khôi phục dữ liệu | ✓ | | |
| Báo cáo thống kê, xuất Excel, người dùng, thông tin cửa hàng, nhật ký hệ thống | ✓ | | |

### Nghiệp vụ chính

- **Kích hoạt bảo hành điện tử:** chọn model, nhập serial và SĐT khách. Khách cũ được tự nhận theo SĐT, khách mới được tạo luôn. Thời hạn mặc định lấy theo model. Có in **phiếu bảo hành điện tử** kèm mã QR.
- **Tiếp nhận máy:** gõ serial là hệ thống báo ngay máy còn hay hết hạn bảo hành, hoặc đang có phiếu chưa đóng. Có in **phiếu tiếp nhận** kèm QR để khách theo dõi tiến độ.
- **Quy trình phiếu:**

  | Từ trạng thái | Được chuyển sang |
  |---|---|
  | Đã tiếp nhận | Đang kiểm tra, Đã hủy |
  | Đang kiểm tra | Đang sửa chữa, Đã gửi hãng (chọn trung tâm BH hãng), Chờ linh kiện, Từ chối bảo hành |
  | Đã gửi hãng | Sửa xong – chờ trả, Từ chối bảo hành |
  | Đang sửa chữa | Sửa xong – chờ trả, Chờ linh kiện |
  | Chờ linh kiện | Đang sửa chữa |
  | Sửa xong / Từ chối | Đã trả khách (qua chức năng *Thu tiền & trả máy*) |

- **Linh kiện và chi phí:** linh kiện dùng cho phiếu sẽ **trừ tồn kho**, bỏ ra thì hoàn kho. Máy còn bảo hành được miễn phí linh kiện và tiền công.
- **Báo giá (máy hết hạn):** KTV nhập tiền công và linh kiện rồi gửi báo giá. Nhân viên ghi nhận khách đồng ý hay từ chối. Phải có đồng ý thì mới được sửa hoặc gửi hãng. Nếu chi phí thay đổi sau khi khách đã đồng ý, hệ thống buộc báo giá lại. Khi từ chối sửa, linh kiện tự hoàn kho.
- **Thu tiền & trả máy:** chọn hình thức (tiền mặt, chuyển khoản, quẹt thẻ). Hệ thống sinh **số hóa đơn sửa chữa** (`HDSC2611-00021`) và in **phiếu trả máy / hóa đơn**.
- **Kho:** nhập kho (cập nhật giá nhập), kiểm kê (bắt buộc ghi lý do), cảnh báo tồn dưới mức tối thiểu, thẻ kho ghi lại mọi giao dịch.
- **Báo cáo** (chọn khoảng thời gian): số máy kích hoạt, phiếu nhận/trả, doanh thu (tiền công + linh kiện), thời gian xử lý trung bình, biểu đồ theo tháng, tỷ lệ phải bảo hành theo hãng, model lỗi nhiều, năng suất KTV, linh kiện sử dụng. Có **xuất Excel (.xlsx)** 6 sheet.
- **Ảnh tình trạng máy:** chụp và tải lên các vết trầy, móp khi nhận máy (JPG, PNG, WebP, tối đa 8 MB, 12 ảnh mỗi phiếu). Ảnh được in kèm trên phiếu tiếp nhận để tránh tranh cãi lúc trả máy. Chỉ người tải lên hoặc quản lý mới xóa được ảnh.
- **Hủy phiếu:** nhân viên tiếp nhận có nút *Hủy phiếu* riêng (bắt buộc ghi lý do và xác nhận), tách khỏi phần cập nhật tiến độ để tránh bấm nhầm.
- **Gói bảo hành mở rộng:** bán thêm 6, 12 hoặc 24 tháng (giá tùy chỉnh) cho máy còn hạn. Hạn mới được cộng nối tiếp, tổng tối đa 120 tháng. Doanh thu gói được tính riêng trong báo cáo, và phiếu bảo hành in ra có kèm lịch sử gia hạn.
- **Yêu cầu bảo hành online:** khách gửi yêu cầu từ cổng khách hàng. Nhân viên hẹn lịch, khách mang máy tới thì bấm *Tiếp nhận máy* là thành phiếu (tự điền serial và mô tả lỗi). Mỗi máy chỉ có một yêu cầu đang chờ.
- **Cổng khách hàng có đăng nhập:** khách đăng ký bằng SĐT + số Serial của một máy đã mua (chỉ chủ máy biết, không cần SMS), dùng cùng cách đó khi quên mật khẩu. Sau khi đăng nhập, khách xem mọi máy của mình, hạn bảo hành, tiến độ sửa, **tự duyệt hoặc từ chối báo giá** (xem chi tiết linh kiện và tiền công), gửi yêu cầu bảo hành, và cập nhật email, địa chỉ, mật khẩu. Nhập sai 5 lần thì khóa 5 phút. Tài khoản khách chia 4 mục:
  - **Máy của tôi:** mở **phiếu bảo hành điện tử có mã QR** để in hoặc lưu PDF; gửi yêu cầu bảo hành **kèm tối đa 4 ảnh** chụp lỗi (khi tiếp nhận, ảnh tự chép vào phiếu); **chấm sao + nhận xét** sau khi nhận lại máy (sửa lại được).
  - **Hóa đơn:** lịch sử các lần sửa đã trả máy, xem / in lại / lưu PDF hóa đơn sửa chữa.
  - **Hộp thư:** các tin cửa hàng đã gửi qua email / SMS / Zalo (gộp theo sự kiện).
  - **Tài khoản:** email, địa chỉ, mật khẩu, tắt tin nhắc hết hạn.
- **Trang "Cửa hàng" cho khách** (`/tra-cuu#cua-hang`): giới thiệu, giờ mở cửa, nút gọi / email / nhắn tin / chỉ đường, bản đồ Google Maps (cần Internet), chính sách bảo hành. Nội dung sửa ở **Thông tin cửa hàng**.
- **Bảng công việc (Kanban)**: các cột Đã tiếp nhận → Đang kiểm tra → Chờ linh kiện → Đã gửi hãng → Đang sửa → Sửa xong → Từ chối; kéo thả phiếu để chuyển trạng thái (chỉ thả được vào cột hợp lệ với vai trò). Gửi hãng / từ chối sẽ hỏi trung tâm hoặc lý do. Có lọc "chỉ phiếu của tôi".
- **Thanh toán VietQR**: nhập ngân hàng + số tài khoản ở *Thông tin cửa hàng*; phiếu còn phải trả sẽ có mã QR chuẩn NAPAS với **đúng số tiền và nội dung (mã phiếu)** – hiện khi thu tiền và in trên hóa đơn. Trong cổng tra cứu, máy sửa xong có phí sẽ có khung **Thanh toán** để khách chọn **💵 Tiền mặt khi nhận máy** hoặc **🏦 Chuyển khoản (VietQR)** rồi bấm *Tôi đã chuyển khoản*; lựa chọn của khách hiện trong phiếu (chọn sẵn hình thức khi thu tiền) và trong tin nhắn cho nhân viên. Phần mềm không tự đối soát với ngân hàng – nhân viên kiểm tra tiền về rồi bấm Thu tiền. Mã tạo ngay trong máy, không gọi dịch vụ ngoài.
- **Đặt hàng linh kiện từ nhà cung cấp** (menu *Kho → Đặt hàng linh kiện*, *Nhà cung cấp*): linh kiện có tồn ≤ mức tối thiểu được gợi ý sẵn số lượng (đủ lên gấp đôi mức tối thiểu, trừ phần đang về). Quản lý chọn nhà cung cấp → *Tạo đơn* → in/lưu PDF gửi nhà cung cấp → *Đánh dấu đã gửi đơn* → khi hàng về bấm *Nhận hàng & nhập kho* (sửa được số thực nhận): tồn kho tự cộng, ghi thẻ kho "Nhập theo đơn DH-…", cập nhật giá nhập. Số trên menu là số linh kiện cần đặt.
- **KPI / hoa hồng kỹ thuật viên** (menu *KPI kỹ thuật viên*): bảng xếp hạng theo kỳ với số phiếu hoàn thành, đang xử lý, thời gian sửa trung bình, % đúng hẹn, điểm đánh giá của khách, doanh thu và hoa hồng. Hoa hồng = thưởng mỗi phiếu + % tiền công các phiếu có phí (đổi ở *Thông tin cửa hàng*). Kỹ thuật viên xem được bảng xếp hạng nhưng chỉ thấy tiền của chính mình.
- **Chế độ tối (dark mode)**: web nhân viên và cổng khách hàng bấm nút 🌙 / ☀️ góc trên (lần đầu tự theo cài đặt của máy); ứng dụng Swing bấm nút *Giao diện tối / Giao diện sáng* trên thanh trên cùng. Lựa chọn được ghi nhớ cho lần mở sau.
- **Quét mã vạch / IMEI khi tiếp nhận máy** (nút *Quét* cạnh ô Serial ở Tiếp nhận máy, Kích hoạt bảo hành, Tra cứu – cả web và Swing). Đọc được Code 128, Code 39 (serial, IMEI) và EAN-13; bộ giải mã viết thuần Java, không cần thư viện. Ba cách:
  - *Dùng điện thoại*: máy tính hiện mã QR, điện thoại (cùng Wi-Fi) quét QR → mở trang chụp ảnh → chụp tem mã vạch → số serial tự điền trên máy tính. Không cần cài app, chạy được qua mạng LAN thường.
  - *Camera laptop* (web, khi mở bằng `http://localhost`): bật camera, đưa mã vào khung là tự đọc.
  - *Chọn ảnh* có mã vạch từ máy.
  Phiếu tiếp nhận in kèm mã vạch serial để lần sau quét lại. 15 số đúng thuật toán Luhn được nhận là IMEI.
- **Nhập Excel** (nút *Nhập Excel* ở danh mục Khách hàng, Sản phẩm / Model, Linh kiện): tải file mẫu → điền → chọn file (.xlsx hoặc .csv) → xem trước từng dòng (thêm mới / cập nhật / lỗi kèm lý do) → bấm nhập. Nhận cột theo tên (không phân biệt dấu, thứ tự tùy ý), trùng SĐT / mã thì cập nhật, ô trống giữ giá trị cũ, tự thêm số 0 đầu SĐT bị Excel làm mất, tự tạo hãng / loại mới. Dòng lỗi được bỏ qua, không ảnh hưởng dòng khác.
- **Thống kê lỗi thường gặp** (menu *Quản trị → Thống kê lỗi thường gặp*, quản lý và kỹ thuật viên): nhóm lỗi (tự phân loại theo từ khóa trong lỗi khách báo + kết luận kỹ thuật: nguồn, pin/sạc, màn hình, loa/mic, camera, mạng, nóng máy, vào nước/rơi vỡ, bàn phím, phần mềm, điện lạnh, máy giặt, tiếng kêu…), model hay hỏng kèm tỷ lệ hỏng, linh kiện thay nhiều nhất, máy phải sửa lại nhiều lần. Có trong file Excel báo cáo (4 trang mới).
- **Kiểm thử tự động trên GitHub (GitHub Actions)**: mỗi lần đưa code lên, GitHub tải thư viện, biên dịch và chạy toàn bộ kiểm thử; huy hiệu đầu README hiện xanh (đạt) / đỏ (lỗi).
- **Lịch hẹn theo ngày / tuần** (menu *Lịch hẹn*, cả web và Swing): khách hẹn mang máy tới (yêu cầu online đã xác nhận), khách đề xuất ngày chưa xác nhận, hẹn trả máy, máy sửa xong chờ khách nhận, phiếu quá hẹn (hiện ở ngày hôm nay). Lọc theo kỹ thuật viên; kỹ thuật viên chỉ thấy phiếu của mình. Bấm vào lịch để mở phiếu / yêu cầu.
- **Tin nhắn giữa khách và cửa hàng trong phiếu:** khách bấm *💬 Nhắn tin với cửa hàng* ở từng phiếu trong cổng tra cứu; nhân viên trả lời ngay trong cửa sổ phiếu. Menu *Tin nhắn khách* gom các hội thoại, số trên menu là số phiếu có tin chưa trả lời. Khi nhân viên trả lời, khách được báo qua email / SMS / Zalo (nếu bật thông báo, sự kiện *Cửa hàng trả lời tin nhắn*). Chống spam: tối đa 10 tin / 10 phút mỗi khách.
- **Đánh giá của khách trong báo cáo:** điểm trung bình, phân bố 1–5 sao, tỷ lệ khách đánh giá, điểm theo kỹ thuật viên, nhận xét gần đây; có thêm sheet *Đánh giá* trong file Excel. Nhân viên cũng thấy đánh giá ngay trong phiếu.
- **Sao lưu & khôi phục:** tự động mỗi 24 giờ (giữ 20 bản), hoặc bấm *Sao lưu ngay*. Mỗi bản là một file `.zip` trong `data/backup/` gồm CSDL và ảnh. Trước khi khôi phục, hệ thống tự sao lưu trạng thái hiện tại để có thể quay lại.
- **Thông báo cho khách (Email / SMS / Zalo):** tự gửi khi *tiếp nhận máy*, *có báo giá*, *sửa xong*, *hẹn lịch / từ chối yêu cầu online* và *bảo hành sắp hết hạn (30 ngày)*. Tin được xếp hàng đợi và gửi ở nền nên không làm chậm thao tác; lỗi thì tự thử lại 4 lần, sau đó hiện ở trang **Thông báo cho khách** để bấm *Gửi lại*. Không gửi trùng; SMS/Zalo chỉ gửi 6h–22h. Nội dung tin sửa được. Xem cách cấu hình ở mục 6.
- **Cổng tra cứu cho khách:** tra theo serial hoặc mã phiếu (không tra theo SĐT để không lộ thông tin người khác). Tên và SĐT được che bớt, không hiện ghi chú nội bộ, giới hạn 30 lượt/phút mỗi IP.

### Bảo mật

- Mật khẩu băm bằng **PBKDF2-HMAC-SHA256** (120.000 vòng, có salt) và không bao giờ trả về giao diện.
- Nhập sai 5 lần thì tài khoản bị khóa tạm 2 phút. Không được tự hạ quyền hay tự khóa mình, và hệ thống luôn giữ ít nhất 1 Quản lý.
- Web: phiên đăng nhập lưu trong cookie `HttpOnly; SameSite=Strict`, sống 12 giờ. Mọi yêu cầu ghi phải có header `X-App` (chống CSRF).
- Liên kết in phiếu được **ký HMAC**, hết hạn sau 30 phút.
- **Nhật ký hệ thống** ghi lại mọi thao tác: đăng nhập, thêm/sửa/xóa, đổi trạng thái, nhập xuất kho…
- Mọi câu SQL đều dùng `PreparedStatement` (không ghép chuỗi dữ liệu người dùng).

---

## 3. Cơ sở dữ liệu (SQLite)

File `data/baohanh.db` được tạo tự động từ `database/schema.sql`. Nếu thư mục `data` còn CSDL của phiên bản cũ, phần mềm tự đổi tên nó thành `baohanh-cu-*.db` rồi tạo CSDL mới.

```
app_user ─┬─< audit_log
          ├─< warranty.created_by
          └─< ticket.technician_id / received_by

brand ─┬─< product >── category          customer ─< warranty >── product
       ├─< service_center                warranty ─< ticket ─┬─< ticket_history
       └─< part ─< stock_movement                            ├─< ticket_part >── part
                                                             └── service_center
customer ─┬─ customer_account                 ticket ─< ticket_photo
          └─< service_request >── warranty     warranty ─< warranty_extension
setting (thông tin cửa hàng)
```

| Bảng | Nội dung |
|---|---|
| `app_user` | Người dùng, vai trò (ADMIN/RECEPTION/TECHNICIAN), mật khẩu băm |
| `audit_log` | Nhật ký thao tác |
| `setting` | Tên, địa chỉ, SĐT cửa hàng, điều khoản in phiếu, địa chỉ cổng tra cứu |
| `customer` | Khách hàng (SĐT duy nhất) |
| `brand`, `category`, `product` | Hãng, loại, model (thời hạn BH mặc định, giá) |
| `service_center` | Trung tâm bảo hành ủy quyền của hãng |
| `warranty` | Bảo hành điện tử: serial duy nhất, ngày mua, số tháng, ngày hết hạn |
| `ticket` | Phiếu: trạng thái, KTV, tiền công, báo giá, thanh toán, số hóa đơn |
| `ticket_part` | Linh kiện dùng cho phiếu (đơn giá, có tính phí hay không) |
| `ticket_history` | Lịch sử xử lý từng phiếu |
| `part`, `stock_movement` | Linh kiện, tồn kho, thẻ kho (nhập/xuất/hoàn/kiểm kê) |
| `ticket_photo` | Ảnh tình trạng máy (file lưu ở `data/photos/`) |
| `customer_account` | Tài khoản đăng nhập cổng khách hàng (mật khẩu băm) |
| `service_request` | Yêu cầu bảo hành online: NEW → CONFIRMED → CONVERTED / REJECTED / CANCELLED |
| `warranty_extension` | Gói bảo hành mở rộng đã bán (hạn cũ, hạn mới, giá) |
| `notification` | Hàng đợi + nhật ký thông báo: kênh, người nhận, nội dung, trạng thái PENDING/SENT/FAILED, số lần thử, lỗi, `dedup_key` chống gửi trùng |
| `notify_optout` | Khách đã tắt tin nhắc hết hạn |
| `ticket_review` | Đánh giá của khách cho phiếu đã trả máy (1–5 sao, nhận xét) |
| `request_photo` | Ảnh khách gửi kèm yêu cầu online (file ở `data/photos/yc-<id>/`) |
| `ticket_message` | Tin nhắn khách ↔ cửa hàng theo phiếu, cờ đã đọc cho từng phía |
| `supplier` | Nhà cung cấp linh kiện |
| `purchase_order`, `purchase_order_line` | Đơn đặt hàng (Nháp → Đã đặt → Đã nhận / Đã hủy) và các dòng linh kiện, số thực nhận |
| View `v_warranty`, `v_ticket_cost` | Bảo hành kèm tên sản phẩm/khách; chi phí từng phiếu |

Ràng buộc: khóa ngoại (`ON DELETE RESTRICT` chặn xóa dữ liệu đang được dùng), `UNIQUE`, `CHECK` (trạng thái, số tháng 1–120, tồn kho ≥ 0…). Các thao tác nhiều bước chạy trong **transaction**.

Muốn xem hoặc sửa trực tiếp thì mở `data/baohanh.db` bằng [DB Browser for SQLite](https://sqlitebrowser.org/) hoặc extension *SQLite Viewer* (nên tắt chương trình trước khi sửa).

---

## 4. Cấu trúc mã nguồn

```
src/main/java/vn/baohanh/
├── Main.java                   Khởi động: CSDL, dữ liệu mẫu, máy chủ web, cửa sổ Swing
├── db/        Database.java    Kết nối JDBC, chạy schema, transaction, nâng cấp CSDL cũ
│              Row.java         Một dòng kết quả truy vấn
├── model/     Role, User, TicketStatus (quy trình + quyền), EntityDef (mô tả danh mục)
├── security/  PasswordHasher   Băm mật khẩu PBKDF2
├── service/   AppContext       Gom các dịch vụ – dùng chung cho Swing và web
│              Auth, AuditService, CatalogService, WarrantyService (kèm gói BH mở rộng),
│              InventoryService, ReportService, PortalService, SettingService, SeedData,
│              CustomerService (cổng khách hàng), RequestService (yêu cầu online),
│              PhotoService (ảnh), BackupService (sao lưu / khôi phục)
├── notify/    NotificationService (hàng đợi, mẫu tin, thử lại, nhắc hết hạn),
│              EmailSender (SMTP tự viết, SSL/STARTTLS), HttpSenders (eSMS, Zalo ZNS)
├── print/     PrintService     Mẫu phiếu in HTML (A5) + liên kết ký HMAC
├── api/       ApiServer        Máy chủ web + REST API (JDK HttpServer)
├── ui/        Giao diện Swing: SwingApp (đăng nhập), MainFrame, DashboardView, TicketsView,
│              TicketDialog, ReceiveDialog, ActivateView, WarrantiesView, LookupView,
│              CatalogView, StockView, ReportsView, SettingsView, AuditView, RequestsView,
│              BackupView, NotificationsView, PhotoPanel, Ui (bộ công cụ)
└── util/      Json (đọc/ghi JSON), QrCode (tạo mã QR), Xlsx (xuất Excel), Fmt
web/index.html                  Giao diện web cho nhân viên
web/portal.html                 Cổng khách hàng (tra cứu nhanh + tài khoản)
src/test/java/.../KiemThu.java  Bộ kiểm thử tự động (205 kiểm thử) + MayChuGia.java (SMTP/eSMS/Zalo giả)
database/schema.sql             Script tạo CSDL
lib/                            Driver sqlite-jdbc + slf4j (tải bằng tai-thu-vien.bat)
```

Phần mềm không dùng framework ngoài nào: bộ tạo **mã QR**, file **Excel .xlsx**, **JSON** và máy chủ web đều tự viết trên JDK. Chỉ cần driver SQLite.

### REST API chính (web)

| | Đường dẫn | |
|---|---|---|
| POST | `/api/login`, `/api/logout`, `/api/password` | Đăng nhập / đăng xuất / đổi mật khẩu |
| GET | `/api/meta`, `/api/dashboard` | Thông tin chung, tổng quan |
| GET/POST/PUT/DELETE | `/api/catalog/{customer,brand,service_center,category,product,part,user}[/{id}]` | Danh mục |
| GET/POST/DELETE | `/api/warranties[/{id}]`, `/api/lookup?q=`, `/api/serial?s=` | Bảo hành, tra cứu |
| GET/POST | `/api/tickets[/{id}]` | Phiếu |
| POST | `/api/tickets/{id}/status`, `/cost`, `/parts`, `/quote`, `/quote-decision`, `/assign`, `/pay` | Xử lý phiếu |
| GET/POST | `/api/inventory/movements`, `/in`, `/adjust` | Kho |
| GET | `/api/reports?from=&to=`, `/api/reports/export` | Báo cáo, xuất Excel |
| POST | `/api/tickets/{id}/cancel`, `/api/warranties/{id}/extend` | Hủy phiếu, bán gói BH mở rộng |
| GET/POST/DELETE | `/api/tickets/{id}/photos`, `/api/photos/{id}` | Ảnh tình trạng máy |
| GET/POST | `/api/requests`, `/api/requests/{id}/confirm`, `/reject`, `/convert` | Yêu cầu online |
| GET/POST | `/api/backups`, `/api/backups/{tên}/restore`, `/api/backups/{tên}` | Sao lưu, khôi phục, tải về |
| GET/PUT/POST | `/api/notify`, `/api/notify/config`, `/test`, `/{id}/retry`, `/run`, `/stats` | Thông báo cho khách |
| GET | `/api/public/lookup?q=` | Tra cứu cho khách (công khai) |
| POST/GET/PUT | `/api/kh/register`, `/login`, `/logout`, `/me`, `/profile`, `/password`, `/quote`, `/requests` | Cổng khách hàng (cookie riêng `ksid`) |
| GET/POST | `/api/calendar?from&to&technician_id`, `/api/messages`, `/api/tickets/{id}/messages`, `/api/kh/tickets/{id}/messages` | Lịch hẹn, tin nhắn |
| GET/POST/DELETE | `/api/kh/inbox`, `/review`, `/print-link`, `/requests/{id}/photos`, `/photos/{id}`; `/api/public/shop-page` | Hộp thư, đánh giá, phiếu in, ảnh yêu cầu, trang cửa hàng |
| GET/POST | `/api/purchase/suggest`, `/api/purchase[/{id}]`, `/api/purchase/{id}/order`, `/receive`, `/cancel` | Đặt hàng nhà cung cấp |
| GET | `/api/reports/kpi?from=&to=` | KPI / hoa hồng kỹ thuật viên |
| GET | `/api/reports/faults?from=&to=` | Thống kê lỗi thường gặp |
| POST/GET/DELETE | `/api/scan/decode`, `/api/scan/session[/{mã}]`; `/api/public/scan/{mã}[/text]`; trang `/quet/{mã}` | Quét mã vạch (ảnh, phiên quét bằng điện thoại) |
| GET/POST | `/api/import/{customer,product,part}/template`, `/preview`, `/commit` | Nhập Excel |

---

## 5. Cho khách quét mã QR bằng điện thoại

Mã QR trên phiếu dẫn tới `http://<IP máy tính>:8080/tra-cuu#<mã phiếu>`. Phần mềm tự lấy IP mạng LAN của máy, nên điện thoại khách phải **cùng mạng Wi-Fi** với máy chạy phần mềm. Lần đầu chạy, Windows có thể hỏi cho phép Java qua tường lửa: chọn **Allow**. Nếu cửa hàng có tên miền công khai thì nhập vào **Thông tin cửa hàng → Địa chỉ cổng tra cứu**.

---

## 6. Cấu hình thông báo cho khách

Vào **Quản trị → Thông báo cho khách → Cấu hình** (web hoặc Swing), tick **Bật gửi thông báo**, bật kênh cần dùng rồi bấm **Gửi thử** để kiểm tra. Chỉ quản lý xem/sửa được cấu hình; mật khẩu luôn hiện dạng `••••••`.

**Email (miễn phí, dễ nhất – nên dùng cho đồ án):**
1. Dùng một tài khoản Gmail của cửa hàng, bật *Xác minh 2 bước*.
2. Vào Tài khoản Google → *Bảo mật* → *Mật khẩu ứng dụng*, tạo một mật khẩu 16 ký tự.
3. Nhập: máy chủ `smtp.gmail.com`, cổng `465`, bảo mật `SSL`, tài khoản = địa chỉ Gmail, mật khẩu = mật khẩu ứng dụng vừa tạo.
4. Email chỉ gửi cho khách có email trong hồ sơ (nhập khi kích hoạt bảo hành, hoặc khách tự thêm trong cổng tra cứu).

**SMS qua eSMS.vn (có phí):** đăng ký tài khoản eSMS, lấy *ApiKey* và *SecretKey*, đăng ký **Brandname** và **mẫu nội dung** với eSMS. Nội dung tin trong phần mềm phải khớp mẫu đã đăng ký (sai mẫu sẽ báo lỗi 146). Mặc định gửi không dấu.

**Zalo ZNS (có phí):** cần Zalo Official Account đã xác thực, tạo mẫu tin ZNS và chờ Zalo duyệt. Nhập *access token* của OA và **mã mẫu** cho từng sự kiện; sự kiện nào để trống mã mẫu thì không gửi Zalo. Khi tạo mẫu, đặt tên tham số đúng như sau để phần mềm điền được: `customer_name`, `product_name`, `ticket_code`, `amount`, `date`, `shop_name`, `shop_phone`, `note`, `link`. Access token của Zalo hết hạn định kỳ, cần cập nhật lại.

**Biến dùng trong nội dung tin:** `{ten_khach} {san_pham} {serial} {ma_phieu} {ma_yeu_cau} {so_tien} {ngay_hen} {han_bh} {loi_nhan} {link} {cua_hang} {sdt_cua_hang}`.

**Lưu ý pháp lý:** chỉ gửi cho khách đã đồng ý nhận thông báo (Luật Bảo vệ dữ liệu cá nhân, hiệu lực 01/01/2026). Tin nhắc hết hạn mang tính quảng cáo nên **mặc định tắt**, và khách có thể tự tắt trong cổng tra cứu → *Tài khoản* → *Thông tin tài khoản*.

---

## 7. Lỗi thường gặp

- **"THIEU DRIVER SQLITE" / "No suitable driver":** thư mục `lib/` chưa có file `.jar`. Chạy `tai-thu-vien.bat` rồi chạy lại. Nếu VS Code vẫn báo lỗi, nhấn `Ctrl+Shift+P` → *Java: Clean Java Language Server Workspace* → *Restart and delete*.
- Script báo **"KHONG TAI DUOC"**: tải tay 3 file dưới đây rồi chép vào `lib/`:
  - https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar
  - https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar
  - https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar
- **"Cong 8080 dang bi chuong trinh khac su dung":** phần mềm đang chạy ở cửa sổ khác (tắt nó đi), hoặc chạy bằng cổng khác: `run.bat 9090`.
- **Trang web báo "Bạn đang mở file index.html trực tiếp":** phải chạy chương trình Java rồi vào `http://localhost:8080`, không nhấp đúp file html.
- **Lỡ thao tác sai và muốn quay lại:** vào *Sao lưu dữ liệu*, chọn bản sao lưu trước thời điểm đó rồi bấm *Khôi phục*.
- **Kiểm thử báo lỗi tiếng Việt bị "?":** xem kết quả ở tab *Debug Console* của VS Code, hoặc chạy `run-tests.bat`.
- **Gửi thử email báo "535" / "sai tài khoản/mật khẩu SMTP":** Gmail không cho dùng mật khẩu đăng nhập, phải tạo *Mật khẩu ứng dụng* (mục 6).
- **Thông báo SMS/Zalo đứng ở "Đang chờ gửi" vào ban đêm:** bình thường – SMS và Zalo chỉ gửi 6h–22h, tin sẽ tự đi lúc 6h sáng.
- **Đóng cửa sổ Swing:** chọn *Thoát hẳn* (tắt luôn web) hoặc *Chỉ đóng cửa sổ* (web vẫn chạy, nhấn Ctrl+C trong terminal để tắt).
