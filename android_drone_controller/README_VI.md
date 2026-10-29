# Cài đặt và sử dụng ESP32 Drone Controller 0.3.0

Bản debug chuẩn bị kiểm tra thực tế, **CHƯA KIỂM ĐỊNH BAY**. Toàn bộ thao tác ARM trong cloud chỉ chạy trên SITL. Chưa có bằng chứng điện thoại/ESP32/động lực thật của bản này. Không lắp cánh khi thực hiện các bước bên dưới; cần người vận hành chủ động tại thiết bị.

## Cài APK

Tải `ESP32-Drone-Controller-0.3.0-rc-control-debug.apk` và đối chiếu SHA-256 trong `SHA256SUMS.json`. Cho phép cài ứng dụng từ nguồn đã chọn trên Android 8.0 trở lên, mở APK rồi cài cập nhật ứng dụng Codex. Không cần Internet khi sử dụng với ESP32. Đóng app điều khiển khác và Mission Planner có thể phát RC trước khi mở phiên mới.

Nếu dùng ADB:

```powershell
adb install -r ESP32-Drone-Controller-0.3.0-rc-control-debug.apk
```

## Kết nối và kiểm tra chỉ đọc

1. Tháo cánh; bước telemetry/bench ban đầu ngắt vật lý nguồn ESC. Chọn Wi-Fi `ESP32_DRONE` trong cài đặt Android, giữ kết nối khi điện thoại báo không có Internet.
2. Vào **KẾT NỐI**, nhập `192.168.4.1` và `5760`, bấm kết nối. Ứng dụng bind socket với Wi-Fi; dữ liệu di động có Internet không thay thế Wi-Fi này.
3. Kiểm tra SYS/COMP được khám phá từ HEARTBEAT, trạng thái DISARMED, tuổi telemetry, pin/GPS/EKF/STATUSTEXT và tham số đọc về. TCP connected chưa chứng minh FC nhận RC. CRC không xác thực thiết bị khi MAVLink không ký.
4. Đối chiếu RC1–4 MIN/TRIM/MAX/REVERSED, RCMAP và GCS/override timeout thực. Bản này hỗ trợ ArduCopter quad, mapping 1/2/3/4 và REVERSED=0; cấu hình khác được khóa để tránh điều khiển nhầm. ARMING_RUDDER phải0, FLTMODE_CH phải0 hoặc5–16 và RC1–4_OPTION phải0 để các trục không kích hoạt ARM/DISARM/mode/chức năng phụ. Ứng dụng không ghi đè tham số.

## Tay cần và thử bốn kênh DISARMED

1. Vào **TAY CẦN**, chọn profile **THỬ RC • DISARMED**. Đọc lại điều kiện RC nếu tham số bắt đầu phiên đã quá hạn. Quá trình đọc chỉ chuẩn bị dữ liệu; phải bấm cho phép riêng sau khi hoàn tất.
2. Xác nhận đã tháo cánh, cách ly nguồn ESC và cho phép thử. Bật phiên thử rồi thao tác đồng thời hai joystick. Phiên bốn kênh hữu hạn; không được dùng bench để ARM hoặc tiếp quản FC đang ARMED.
3. Cần trái: kéo dọc thay đổi ga tương đối từ điểm chạm, thả tay giữ ga; kéo ngang điều khiển yaw, thả về giữa. Cần phải: roll ngang/pitch dọc, thả về giữa. Pitch đẩy lên tạo RC thấp theo mapping đã kiểm thử; phải đối chiếu hướng thực trước bước sau.
4. Theo dõi ba giá trị riêng: kênh yêu cầu, TX ghi socket và RC_CHANNELS do FC báo. RC_CHANNELS_OVERRIDE không có COMMAND_ACK; giá trị trùng chỉ là tương quan. Dùng log FC/Mission Planner để xác minh nguồn RC và trục thực.
5. STOP hoặc chuyển trang thu hồi quyền; không tự tái phát. Không coi STOP là DISARM hay dừng động cơ.

## Phiên điều khiển và ARM/DISARM

Chỉ thực hiện trên rig tháo cánh, có dừng độc lập và người vận hành chịu trách nhiệm. Kiểm tra nguồn động lực/failsafe/timeout vẫn cần phép đo phần cứng riêng. Không dùng bản này để bay.

1. Chọn profile **ĐIỀU KHIỂN**, xác nhận điều kiện và bật phiên khi DISARMED, ga min, các trục giữa, dữ liệu RC/heartbeat và tham số phù hợp. Phiên không tự ARM.
2. Chờ RC echo bốn kênh mới khớp, SYS_STATUS xác nhận PreArm đạt, EKF attitude hợp lệ và FC báo trên đất. Khi chưa đủ, đọc lý do ARM khóa và STATUSTEXT; sửa nguyên nhân thực, không tắt bảo vệ.
3. Tích xác nhận riêng cho ARM rồi bấm **GỬI YÊU CẦU ARM**. App gửi lệnh thường, không force. Quan sát riêng “ghi đủ socket”, COMMAND_ACK và HEARTBEAT mới. ACK chấp nhận chưa chứng minh trạng thái đã ARM.
4. Khi FC ARM trong phiên hợp lệ, RC tiếp tục ở mục tiêu 20 Hz; điều khiển bốn kênh theo joystick. Mất dữ liệu/quyền/session sẽ ngừng phát, không tự gửi min throttle hoặc DISARM. Failsafe phía FC phải được kiểm chứng độc lập.
5. DISARM cần xác nhận riêng, heartbeat mới và FC báo trên mặt đất. Không có force DISARM trên không. Sau DISARM, quyền điều khiển bị thu hồi; lần tiếp theo phải xác nhận mới.
6. Chọn STABILIZE hoặc chế độ trong danh mục, tích xác nhận rồi gửi. Danh mục không bảo đảm firmware hỗ trợ mọi chế độ. Chỉ ghi “đã xác nhận” khi ACK và HEARTBEAT mới cùng xác nhận. FC từ chối thì hiển thị kết quả, không tự thử lại.

## Sau sự cố

Mất Wi-Fi, background/khóa màn hình/mất focus, STOP, dữ liệu quá hạn hoặc lỗi MAVLink sẽ thu hồi quyền. Ứng dụng không tự reconnect và không khôi phục throttle cũ. Xác minh trạng thái FC/failsafe, đưa cần về ga min rồi kết nối và cấp quyền mới. Đừng giả định đóng TCP đã xóa mọi byte còn trong kernel hoặc đã dừng động cơ.

## Log và hỗ trợ kiểm chứng

Dùng **XUẤT CSV** trong chẩn đoán/cấu hình; xuất log dừng quyền phát. Log được giữ nội bộ, không gửi đến máy chủ. Kiểm tra dropped/error để biết log có thiếu. Thu thêm logcat, model điện thoại/API, APK SHA-256 và log FC khi báo lỗi. Tham khảo [báo cáo hiện hành](reports/20261029-live/VALIDATION.md), [kiến trúc](docs/ARCHITECTURE_RC.md) và [các bài đo phần cứng](docs/HARDWARE_AND_FAILSAFE.md).
