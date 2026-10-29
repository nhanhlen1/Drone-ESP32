# NHIỆM VỤ: HOÀN THIỆN ỨNG DỤNG ANDROID ĐIỀU KHIỂN DRONE ESP32

Bạn là Senior Android Engineer, MAVLink Engineer, ArduPilot Engineer và QA Automation Engineer.

## 1. THAY ĐỔI QUYẾT ĐỊNH PHÁT TRIỂN

**HỦY BỎ HOÀN TOÀN YÊU CẦU TIẾP NỐI GOOGLE AI STUDIO.**

Không cần tìm, đọc, tải, giải nén hoặc tích hợp bất kỳ ZIP hay mã nguồn Google AI Studio nào nữa.

Từ bây giờ, ứng dụng Codex tự phát triển là sản phẩm chính thức của dự án.

Repository: `nhanhlen1/Drone-ESP32`

Branch cơ sở: `codex/controller-qualification-20261029`

Thư mục dự án: `android_drone_controller`

Phiên bản hiện có: `0.2.1-protocol`.

Hãy tự đọc toàn bộ source hiện tại, tài liệu, báo cáo kiểm thử và lịch sử Git, sau đó tiếp tục phát triển từ checkpoint đã lưu.

Không tạo dự án Android mới nếu không thật sự cần thiết.

## 2. MỤC TIÊU CUỐI CÙNG

Phát triển một ứng dụng Android hoàn chỉnh hoạt động như tay điều khiển RC Mode 2 cho quadcopter ESP32 chạy ArduPilot.

Điện thoại kết nối với ESP32 qua Wi-Fi và gửi dữ liệu điều khiển thông qua MAVLink2 TCP.

Ứng dụng phải có khả năng:

- Kết nối và nhận telemetry thực tế.
- Điều khiển đồng thời Roll, Pitch, Throttle, Yaw.
- ARM và DISARM.
- Chọn chế độ bay STABILIZE và các chế độ khác được FC hỗ trợ.
- Hiển thị HUD, pin, GPS, độ cao, tốc độ, trạng thái kết nối.
- Hiển thị cảnh báo PreArm, EKF và failsafe.
- Duy trì luồng RC khi ARMED trong điều kiện điều khiển hợp lệ.
- Xử lý lỗi mất kết nối và khôi phục kết nối an toàn.
- Ghi log đầy đủ phục vụ phân tích.
- Có giao diện đẹp, hiện đại, hoàn toàn bằng tiếng Việt.

## 3. CẤU HÌNH DRONE

Flight controller: ESP32 DevModule.

Firmware: ArduCopter 4.8.0-dev.

IMU: ICM-20948.

Compass: AK09916.

Barometer: BMP280.

Battery monitor: INA219 với backend tùy chỉnh.

GPS: M10 Nano.

Động cơ: 4 động cơ DJI Phantom 3 850 kV.

ESC: 4 ESC XXD HW30A 30A.

Pin: LiPo 3S 5400 mAh.

GPIO ESC: 14, 27, 26, 25.

ESC đã hiệu chuẩn dải ga 1000–2000 µs. Bốn động cơ quay ổn định từ khoảng 1036 µs.

Các giá trị đã lưu:
- MOT_PWM_MIN = 1000
- MOT_PWM_MAX = 2000
- MOT_PWM_TYPE = 0
- MOT_SPIN_ARM = 0.06
- MOT_SPIN_MIN = 0.09

Kết nối Wi-Fi:
- IP: 192.168.4.1
- TCP port: 5760
- MAVLink2

RC mapping:
- CH1: Roll
- CH2: Pitch
- CH3: Throttle
- CH4: Yaw

Thông số RC đã sử dụng trước đây: MIN=1100, TRIM=1500, MAX=1900. Phải đối chiếu với tham số FC thực tế khi kiểm thử; không tự ghi đè cấu hình.

## 4. NHIỆM VỤ KỸ THUẬT ƯU TIÊN

### A. Hoàn thiện đường điều khiển RC thực tế

Hiện tại module protocol đã được kiểm thử nhưng chưa nối hoàn chỉnh vào đường chạy live của APK.

Hãy kết nối thành công:

Joystick UI → ViewModel → ControlAuthority → RC Scheduler → MAVLink Encoder → TCP Transport → ESP32 ArduPilot.

Sử dụng một RC scheduler duy nhất, mục tiêu 20 Hz ổn định.

Kiểm thử cả bốn kênh RC, không chỉ CH1.

Phân biệt lệnh đã gửi, lệnh được ACK khi giao thức có ACK, và giá trị RC thực sự được FC báo về. Không coi TCP write hoặc HEARTBEAT là bằng chứng FC đã áp dụng RC.

### B. Thiết kế giao diện điều khiển RC Mode 2

Joystick trái:
- Trục dọc: Throttle, giữ vị trí khi thả tay.
- Trục ngang: Yaw, tự trở về giữa.

Joystick phải:
- Trục dọc: Pitch, tự trở về giữa.
- Trục ngang: Roll, tự trở về giữa.

Bắt buộc hỗ trợ cảm ứng đa điểm để thao tác hai joystick cùng lúc.

Tối ưu cho điện thoại Android, giao diện ngang, cảm ứng mượt, hiển thị trạng thái điều khiển rõ ràng.

### C. Hoàn thiện ARM/DISARM

Đọc và hoàn thiện ArmCommandService và ControlAuthority hiện có.

Bổ sung cơ chế ARM có xác nhận chủ động, hiển thị kết quả COMMAND_ACK và trạng thái HEARTBEAT mới nhất.

Không cho phép ARM nếu chưa đạt điều kiện kiểm tra của FC.

Không tự bỏ qua PreArm, Battery Failsafe, EKF hoặc các bảo vệ của ArduPilot.

Trong trạng thái ARMED hợp lệ, scheduler không được tự dừng toàn bộ RC chỉ vì nhận thấy cờ ARMED.

Tuy nhiên, phải bảo đảm mất điều khiển, giá trị RC quá hạn, lỗi session, mất Wi-Fi và khôi phục kết nối được xử lý theo state machine an toàn và failsafe đã kiểm thử.

### D. Hoàn thiện MAVLink và mạng

Tiếp tục sử dụng parser đã được kiểm thử với metadata 301 message.

Kiểm tra CRC_EXTRA, sequence gap, TCP fragmentation, stale frame và reconnect.

Bảo đảm socket truyền và nhận không ghi đè dữ liệu hoặc gây deadlock.

Không tự khôi phục lệnh throttle cũ sau khi tái kết nối.

Giữ nguyên các cải tiến protocol đã PASS trước đó.

### E. Hoàn thiện telemetry

Xây dựng màn hình hiển thị:
- Attitude/HUD.
- Trạng thái ARM.
- Chế độ bay.
- Điện áp pin, cảnh báo pin.
- GPS và vệ tinh.
- Độ cao và tốc độ.
- RC input.
- Servo output.
- Trạng thái EKF.
- STATUSTEXT.
- Trạng thái kết nối và độ mới dữ liệu.

Trường hợp cảm biến INA219 không có dữ liệu hợp lệ phải hiển thị rõ, không tự tạo giá trị pin giả.

## 5. KIỂM THỬ TỰ ĐỘNG

Tự động thực hiện:

1. Build Gradle sạch.
2. Unit tests.
3. MAVLink protocol tests.
4. TCP integration tests.
5. Kiểm thử RC CH1–CH4.
6. Kiểm thử cảm ứng đa điểm.
7. Kiểm thử ARM/DISARM và từ chối ARM trên SITL.
8. Kiểm thử điều khiển khi FC chuyển DISARMED → ARMED.
9. Kiểm thử mất Wi-Fi.
10. Kiểm thử reconnect.
11. Kiểm thử dữ liệu telemetry cũ.
12. Kiểm thử scheduler, latency và jitter.
13. Android instrumentation tests.
14. Chạy trên Android emulator.
15. Kiểm thử hồi quy toàn bộ chức năng đã PASS ở 0.2.1.

Sử dụng ArduPilot SITL thực tế để xác nhận RC_CHANNELS_OVERRIDE và phản hồi điều khiển.

Phải kiểm thử trên chính đường live của APK cuối cùng, không chỉ trên các module hoặc harness độc lập.

Mọi lỗi có thể khắc phục trong môi trường hiện tại phải được chủ động sửa và chạy lại kiểm thử.

Nếu một bài test không chạy được, phải ghi BLOCKED, không được ghi PASS.

## 6. QUYỀN TỰ ĐỘNG THỰC HIỆN

Bạn được chủ động:
- Đọc và sửa mã nguồn.
- Tạo file Kotlin mới.
- Sửa cấu trúc UI.
- Cài và cấu hình dependency cần thiết.
- Viết các bài kiểm thử.
- Chạy Gradle.
- Chạy SITL.
- Sử dụng emulator.
- Phân tích logs.
- Sửa lỗi.
- Tạo checkpoint Git.
- Build APK.
- Đóng gói bản phát hành thử nghiệm.

Không cần hỏi tôi về các quyết định kỹ thuật thông thường.

Không tự thay đổi firmware ESP32 hoặc tham số FC thực tế.

Không tự phát lệnh ARM hoặc làm động cơ chạy trên phần cứng thật.

Không tuyên bố FLIGHT READY khi chưa có kiểm chứng phần cứng và an toàn thực tế.

## 7. BÀN GIAO

Sau khi hoàn tất tối đa phần mềm, tạo:

1. APK debug cài đặt trực tiếp lên Android.
2. ZIP mã nguồn Android đầy đủ.
3. Báo cáo kiểm thử.
4. Báo cáo kết nối MAVLink và RC.
5. SHA-256.
6. Hướng dẫn cài đặt và sử dụng bằng tiếng Việt.
7. Danh sách các kiểm thử phần cứng còn cần xác nhận.

Lưu code vào nhánh phát triển riêng, giữ checkpoint cũ để rollback.

Đưa ra đường dẫn rõ ràng tới APK và từng artifact, không bắt tôi tìm trong các thư mục.

## 8. NGUYÊN TẮC QUAN TRỌNG NHẤT

Không dừng lại ở việc tạo thêm một candidate chỉ có telemetry hoặc joystick giả lập.

Mục tiêu là một APK có đường điều khiển RC hoàn chỉnh, đã vượt qua các bài kiểm thử phần mềm và SITL cần thiết, để chuẩn bị cho bước kiểm thử với ESP32 thật.

Tự thực hiện liên tục các bước có thể làm được, không dừng để hỏi tôi từng việc.

Bắt đầu ngay từ checkpoint 0.2.1-protocol.