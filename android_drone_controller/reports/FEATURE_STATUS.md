# Tính năng và các phần còn thiếu

| Yêu cầu | Trạng thái hiện tại |
|---|---|
| Project Kotlin native, package riêng,7trang tiếng Việt,icon,splash | Đã triển khai và build |
| TCP/Wi-Fi binding, MAVLink2, CRC,partial read/write,reconnect thủ công | Triển khai; unit/TCP/SITL đạt; Android Wi-Fi thật NOT VERIFIED |
| Telemetry/stream request/ACK/tham số/firmware/log | Triển khai; dữ liệu thiếu/stale báo rõ; SYS/COMP thực từ HEARTBEAT |
| Áp suất/độ cao/pin | Có pressure/global position; không giả độ cao khí áp từ áp suất chưa tham chiếu, không giả %pin/dòng/thời gian INA219 |
| Hai joystick Mode2,multi-pointer,ga min/reset | Triển khai xem trước ngoại tuyến, host test đạt; cảm ứng Android thật NOT VERIFIED |
| Phép thử RC ban đầu | CH1±20µs/750ms,3xác nhận +xác nhận CRITICAL bổ sung nếu cần; có bằng chứng ArduPilot SITL radio_in/DataFlash |
| Giải vòng khóa RC_CHANNELS | Bỏ yêu cầu RC stream trước bootstrap; thêm stream request hữu hạn; tách ARM/health và CRITICAL consent có gate hẹp |
| RC_CHANNELS là bằng chứng áp dụng | Chỉ hiển thị tương quan; không tự gọi ACK hoặc mở khóa RC_INPUT_VERIFIED |
| CH2/CH3/CH4 live, chế độ flight joystick | **Chưa mở/triển khai đường live**; chỉ encoder/mapping mô phỏng. Cần nghiệm thu nguồnRC, timeout, failsafe và phần cứng trước cấp tiếp theo |
| Chuyển flight mode | **Chưa triển khai phát lệnh**, không gửi tự động trong bench |
| ARM/DISARM | UI khóa; command service semantic+ACK/HB/timeout13test. **Chưa có encoder hoặc nối tới TCP**, chưa được chấp thuận hardware/failsafe tier |
| Kiểm tra Android thật | NOT VERIFIED;3instrumentation test chưa chạy, không có ADB device |
| ESP32 thật và timeout receiverless | NOT VERIFIED; không truy cập192.168.4.1 hoặc nạp firmware trong phiên |
| Wi-Fi reliability30phút, reset,nguồn,lifecycle thật | NOT VERIFIED; chỉ có smokeSITL khoảng6giây và host regression |
| Dừng độc lập Wi-Fi,điều khiển dự phòng,động cơ,bay | Chưa nghiệm thu; phần mềm không thay thế phần cứng độc lập |
| APK/ZIP/tài liệu/báo cáo/log | Đã đóng gói bản ứng viên kiểm thử; không upload/push/release |

Giá trị deadzone6% và nhịp20Hz đang cố định có kiểm thử; UI chưa có bộ chỉnh mọi lựa chọn/tần số. Không tự chuyển sang MANUAL_CONTROL. Không thực hiện phép kiểm chứng30phút bằng cách đổi nhãn một bài smoke ngắn. Không tự mở chức năng bay khi bench PASS.

Để tiến tới tiêu chí nghiệm thu cuối của người dùng vẫn cần thiết bị thật, quyền thử riêng và bằng chứng FC thực, rồi mới tiếp tục cấp điều khiển cao hơn. Các dòng chưa triển khai/chưa xác minh không phải PASS.
