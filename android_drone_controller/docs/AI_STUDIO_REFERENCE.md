# App tham khảo Google AI Studio — chờ mã nguồn có thể đọc

Cập nhật 29/10/2026 (UTC+7). Người dùng gửi `esp32-drone-controller-oct-28-2026-7-45-49-pm.zip` và xác nhận app này thực sự làm thay đổi CH1 input trong Mission Planner. Đây là bằng chứng thực nghiệm **do người dùng báo cáo**, hữu ích cho việc xác nhận có một đường truyền/định dạng RC hoạt động trên cấu hình của họ. Chưa có capture, log hoặc mã nguồn được đọc để quy kết phương thức cụ thể.

## Giới hạn truy cập tệp

Lệnh tải tệp đính kèm trả `file exceeds the executor transfer limit of 32 MiB`. Đã kiểm tra các thư mục attachment/shared trong workspace và công cụ hiện có; chưa có bản ZIP cục bộ hoặc công cụ tải từng phần. Đây là giới hạn chuyển tệp, không phải lỗi checksum, credential hay quyền GitHub. Không đổi mạng hoặc xin token để xử lý giới hạn này.

Chưa giải nén, chạy app, đọc mã hay sao chép logic từ ZIP. Không suy ra app dùng Kotlin, Flutter, WebView, MAVLink1/2, msg69/70 hoặc cách cấu hình kênh từ tên tệp.

## Đối chiếu cần làm khi có source

Gói source dưới32MiB, bỏ build/.gradle/node_modules/APK, hoặc các tệp tạo/gửi MAVLink và xử lý thử CH1, đủ để bắt đầu:

1. Xác định phương thức, sender/target SYSID/COMPID, phiên bản MAVLink, CRC_EXTRA, payload wire order và extension fields.
2. Đối chiếu giá trị CH1, CH2–CH18 ignore/release, rate, điều kiện bắt đầu, heartbeat GCS và stream request.
3. Xác định gói đầu tiên có được gửi trước RC_CHANNELS hay không; app xử lý cờ DISARMED và sức khỏe CRITICAL thế nào.
4. Kiểm tra socket/network binding, partial reads/writes, reconnect, background, deadman, STOP và throttle reset.
5. Lấy vector byte từ implementation thực, so với pymavlink và encoder Kotlin hiện tại; chạy cùng test trên SITL upstream base afeb9c49. Giữ riêng phần đã chứng minh trên phần cứng và trên mô phỏng.
6. Chỉ đưa thay đổi có căn cứ vào project mới; không sao chép quyền phát vô hạn, ARM/motor/mode hoặc bỏ gate an toàn chỉ vì CH1 đã thay đổi trong Mission Planner.

Chưa thể kết luận khác biệt nào giữa hai app là nguyên nhân thành công. Việc CH1 input thay đổi không tự chứng minh override timeout, điều khiển bốn kênh, failsafe, động cơ hoặc điều kiện bay đã đạt.

## Kết quả phiên trước được giữ nguyên

Project mới đã qua121test trên từng bản SITL4.6.3 và upstream base afeb9c49,5test Python, lint0error/1warning; có DataFlash RCIN cho CH1 và APK debug. Các kết quả đó thuộc mã hiện tại, **không phải** kiểm thử hoặc đối chiếu ZIP tham khảo. Đọc `reports/VALIDATION.md` và `reports/FEATURE_STATUS.md` trước khi dùng bản ứng viên bench.
