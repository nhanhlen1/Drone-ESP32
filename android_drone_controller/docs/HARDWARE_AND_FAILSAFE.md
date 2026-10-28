# Nghiệm thu phần cứng và failsafe — chưa thực hiện

Điện thoại Android và ESP32 thật không gắn vào cloud. Toàn bộ bài đo dưới đây là **NOT VERIFIED** cho tới khi có log; không phải báo cáo PASS. Chưa có quyền phát RC trên thiết bị thật trong phiên này. Không cần các dữ liệu phần cứng chưa có để tiếp tục build/test phần mềm.

## Bài đo đầu tiên: telemetry, không RC

Mục đích: xác định đúng trạng thái Wi-Fi → TCP → MAVLink → RC stream, phân biệt không có msg65 với msg65 count0/raw0.

1. Tháo cánh, ngắt vật lý nguồn ESC, chỉ cấp nguồn flight controller theo cách đã xác minh an toàn. Không dùng Motor Test/ARM/đổi tham số. Kết nối điện thoại qua USB ADB nếu có.
2. Cài APK mới cạnh app cũ, đóng app cũ và các TCP client khác. Chọn AP `ESP32_DRONE`, giữ Wi-Fi không Internet, mở app mới rồi kết nối `192.168.4.1:5760`.
3. Trong **CHẨN ĐOÁN**, quan sát 60 giây mà không bấm cho phép RC. Kỳ vọng TX override luôn 0; có HEARTBEAT đúng SYS/COMP, DISARMED mới; msg65 hiển thị “đã quan sát” hoặc “đã tạo yêu cầu; chưa thấy”. Xem ACK511, các tham số đọc được và STATUSTEXT.
4. Xuất CSV và chụp trang chẩn đoán/telemetry/tham số. Thu `adb logcat -d > android-logcat.txt`. Ghi model điện thoại/API, thời điểm bắt đầu/kết thúc, phiên bản ArduPilot do FC thực phát.

Bằng chứng cần giữ: CSV không có override, ảnh SYS/COMP+heartbeat+RC age+raw RC, ACK stream và logcat. Nếu không có msg65, không bật thử bằng cách bỏ kiểm tra: đối chiếu ACK/firmware/stream/CRC trước. ACK ACCEPTED của511 chưa chứng minh msg65 đã phát.

## Phép thử CH1 cần phép riêng

Chỉ sau khi mô phỏng đạt, kết quả telemetry ở trên đã được đọc và **người dùng xác nhận riêng** nguồn ESC ngắt vật lý, tháo cánh, DISARMED và cho phép phát thử. Xác nhận trong app là bước chủ động của người vận hành, không được tự tích thay.

1. Đọc RC1_MIN/TRIM/MAX từ FC, đối chiếu và lưu trong app. Ghi log phía FC độc lập qua đường đã xác minh; nếu dùng Mission Planner USB đồng thời, cần kiểm tra driver thực hỗ trợ USB+Wi-Fi và chỉ một bên có quyền điều khiển. Không suy ra đa client TCP từ hằng số WIFI_MAX_CONNECTION.
2. Xác nhận ba điều kiện ở KIỂM TRA RC. Nếu FC báo CRITICAL, app vẫn khóa mặc định; chỉ quy trình chẩn đoán RC riêng có điều kiện telemetry hẹp và xác nhận thứ tư mới có thể cấp phép CH1 hữu hạn, không được hiểu là FC khỏe. Bật một phép thử, chọn +20 µs, giữ deadman. App chỉ phát CH1; CH2–18 ignore. Phiên tự hết hạn sau750ms dù telemetry trùng giá trị. Không chạy script chẩn đoán gửi RC trên phần cứng.
3. Ghi: timestamp monotonic từng TX, CH1 mong đợi, RC radio_in/nguồn override phía ArduPilot và trạng thái DISARMED. Cần phân biệt gói được tạo, socket ghi xong, FC nhận và backend áp dụng.
4. Thu log sau khi tự dừng. Chứng minh quyền override hết hạn phía FC bằng nguồn trạng thái/log phù hợp; **radio_in có thể giữ số cuối khi không receiver**, nên không dùng riêng PWM cuối để suy ra timeout còn hiệu lực. Không thay RC_OPTIONS/ARMING_CHECK/failsafe để làm phép thử trông như PASS.

Nếu chưa có nguồn quan sát trạng thái override phù hợp trên firmware ESP32, kết quả timeout phải giữ NOT VERIFIED; cần bổ sung read-only debug/log của đúng firmware trong một công việc riêng, không sửa firmware đang hoạt động ở đây.

## Ma trận phải đo

| Tình huống | Kỳ vọng phần ứng dụng | Bằng chứng cần từ FC/phần cứng | Hiện tại |
|---|---|---|---|
| Nhả deadman / STOP | Thu hồi permit, không gửi neutral bảo đảm giả | Thời điểm TX cuối, override expiry | NOT VERIFIED phần cứng |
| Mất HEARTBEAT nhưng TCP còn | Cấm RC khi1500ms, lease bench vốn ngắn hơn | Trạng thái RC/failsafe thật | NOT VERIFIED |
| Wi-Fi mất / AP reset | Đóng TCP, không nối lại/khôi phục ga | RC_OVERRIDE_TIME, GCS/radio failsafe | NOT VERIFIED |
| Background / khóa màn hình / mất focus | Dừng quyền, background đóng TCP | Timestamp TX cuối+FC timeout | NOT VERIFIED |
| App/điện thoại treo hoặc mất nguồn | Không giả định app còn chạy STOP | Failsafe FC và cơ chế độc lập Wi-Fi | NOT VERIFIED |
| Socket half-open / tắc ghi | Ghi nonblocking hữu hạn, đóng khi quá hạn | Kernel bytes muộn, FC expiry | NOT VERIFIED |
| Joystick kẹt / mất RC feedback | Joystick live bị khóa; CH1 lease không gia hạn | Tác động thực+thời hạn nguồn RC | NOT VERIFIED |
| Reconnect | Cần calibration/xác nhận/deadman mới; ga xem trước về min | Không frame cũ xuất hiện trên FC | NOT VERIFIED |
| Radio/GCS failsafe theo mode | Không sửa tham số để che lỗi | FS_THR_ENABLE,FS_GCS_ENABLE,FS_GCS_TIMEOUT,RC_FS_TIMEOUT | NOT VERIFIED |
| 30 phút telemetry / tăng tải / reconnect nhiều lần | TX RC=0, thống kê RX/CRC/gap/socket | ESP32 reset/CPU/Wi-Fi log, nguồn cấp | NOT VERIFIED |

## Đo độ bền ngoại tuyến

Script `integration/telemetry_reliability.py` không phụ thuộc Internet hay pymavlink khi chạy. Lưu bản script lên Windows trước khi chuyển AP; đọc `--help`, chọn thời lượng1800giây. Recorder chỉ nhận, không ARM/RC/stream request; có thể cần stream FC đã cấu hình sẵn. Bridge upstream được kiểm toán không chứng minh hỗ trợ hai TCP client, nên không mặc định chạy recorder và app trên cùng bridge đồng thời. Đo Android thật vẫn cần app CSV và lifecycle; Python recorder không thay thế Android Wi-Fi binding.

Không tuyên bố hạ cánh an toàn chỉ vì RC_TIMEOUT hoặc LAND failsafe có cấu hình. Dừng khẩn cấp độc lập với Wi-Fi, điều khiển dự phòng, giao thức/mapping ESC và nguồn động lực cần nghiệm thu riêng trước khi mở bất kỳ chức năng ARM/chạy motor/bay nào.
