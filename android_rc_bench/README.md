# RC Bench — Android Kotlin cho ArduCopter qua TCP/MAVLink 2

Ứng dụng Android Studio độc lập để **kiểm tra RC override khi ArduPilot đang DISARMED**. Kết nối mặc định `192.168.4.1:5760` bằng TCP, gửi `RC_CHANNELS_OVERRIDE` MAVLink 2 từ GCS system ID 255, component ID 190; target được khám phá từ HEARTBEAT của autopilot ArduPilot, component 1.

**Không có lệnh ARM không đồng nghĩa không thể ARM:** RC yaw có thể kích hoạt rudder arming trong ArduPilot. Vì chưa có giá trị `ARMING_RUDDER` được xác minh, app giới hạn CH4/yaw ở ±25% quãng từ trim đến mỗi endpoint; với 1100/1500/1900, chỉ phát 1400–1600 µs, cả chế độ dual-stick và từng kênh. Không có bài test full endpoint CH4 trong phiên bản này.

Ứng dụng này không thay thế firmware ESP32 hoặc ứng dụng Flutter đã có trong kho. Package Android `dev.drone.rcbench` riêng cho phép cài cùng ứng dụng Flutter. RC override tối đa 20 Hz, GCS HEARTBEAT 1 Hz; không phát bù backlog. Chưa có quy trình ký/phát hành release; APK debug phục vụ phát triển và bench, dùng debug signing, không phải bản phát hành production.

**Không lắp cánh, ngắt nguồn ESC và bảo đảm không thể quay động cơ trước khi thử.** DISARMED không tự bảo đảm motor không thể chạy trong mọi cấu hình/test nguồn khác. Ứng dụng không gửi ARM, DISARM, motor test, đổi mode, `COMMAND_LONG` hoặc `PARAM_SET`; cũng không sửa tham số autopilot. Không dùng ứng dụng này để điều khiển khi bay.

## Khởi chạy trên Windows

1. Cài Android Studio, SDK Platform 36 và JDK 17. Dùng JDK 17 cho Gradle nếu IDE đang chọn một runtime khác; tránh tự nâng Gradle/plugin khi chưa kiểm thử tương thích.
2. Mở riêng thư mục `android_rc_bench` trong Android Studio, để IDE sync Gradle wrapper 8.12. Không mở thư mục `mobile_app` để build ứng dụng này.
3. Kết nối điện thoại Android 8.0/API 26 trở lên qua USB, bật Developer options/USB debugging, chọn thiết bị thật và Run. Dùng Android Studio SDK Manager để cài SDK/Build Tools còn thiếu.
4. Điện thoại kết nối Wi-Fi AP cung cấp TCP `192.168.4.1:5760`; nếu Android cảnh báo không có Internet, chọn giữ kết nối mạng đó.
5. Trong tab **CẤU HÌNH**, nhập IPv4 literal/port (không hỗ trợ hostname/DNS) và chọn **KẾT NỐI**. Đợi HEARTBEAT đúng autopilot, DISARMED và RC_CHANNELS còn mới. Đối chiếu min/max/trim từng kênh, xác nhận và bấm **LƯU KÊNH RCn**; thao tác này chỉ đổi cấu hình ứng dụng.
6. Về **ĐIỀU KHIỂN**, chọn chế độ từng kênh, bấm **CHO PHÉP RC BENCH**, đọc điều kiện rồi bấm **XÁC NHẬN BENCH**. Chỉ giữ nút deadman độc lập khi rig an toàn và cần phát; nhả để dừng. Tab **GIÁM SÁT** tách giá trị TX/RX, telemetry và lỗi. Chuyển tab, background hoặc mất focus dừng quyền kiểm tra; background đóng TCP và không tự reconnect.

PowerShell từ `android_rc_bench`:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Source hiện tại đã qua 49 test và build APK; lint không có error, có 2 warning được ghi trong báo cáo xác thực. APK sau build thành công nằm ở `app\build\outputs\apk\debug\app-debug.apk`. Chỉ việc có source/wrapper không chứng minh đã build APK; kết quả được ghi riêng trong [báo cáo xác thực](docs/validation.md). Có thể chạy ba task cùng một lệnh Gradle; bảo toàn exit code khi thu log.

## Quy trình bench ngắn

- Ghi nhận model/phiên bản ArduCopter thực tế, backup tham số và đọc định nghĩa tham số ứng với đúng firmware. Không thay tham số từ app để vượt khóa.
- Xác minh TCP/MAVLink và HEARTBEAT mới từ component autopilot 1; chỉ peer đúng ArduPilot mới được chọn target. Không mặc định system ID target là 1.
- RC1 có bộ giá trị đã cung cấp: min 1100, trim 1500, max 1900 µs. RC2/RC3/RC4 cùng dải chỉ là provisional, **phải xác minh riêng trước khi cho phép gửi**. Tất cả cờ xác minh, kể cả RC1, khởi tạo false và được xóa sau mỗi kết nối TCP mới; người dùng phải đối chiếu và xác nhận lại. Throttle CH3 thấp nhất 1100, không dùng 1500 làm vị trí nghỉ chỉ vì đây là giữa dải.
- Với RC_OPTIONS=33 không có receiver, RC_CHANNELS chancount 0 có thể hợp lệ và raw ban đầu bằng 0. App giữ đủ 18 raw channel, chỉ cho bootstrap override hữu hạn 750 ms khi các gate khác đạt; sau đó cần raw channel phản hồi khớp, không tự retry.
- Bắt đầu bằng kiểm tra từng kênh; các kênh khác mang giá trị ignore. Đối chiếu số đo `RC_CHANNELS` nhận từ autopilot với giá trị app đã gửi. App yêu cầu HEARTBEAT mới dưới 1500 ms và RC_CHANNELS mới dưới 1200 ms; thiếu phản hồi RC khớp trong 750 ms sẽ thu hồi quyền gửi. Không có override ACK; socket gửi thành công không chứng minh autopilot nhận/chấp nhận.
- Giữ deadman trong lúc quan sát; nhả deadman, mất focus/background hoặc mất link thì dừng phát ngay. Timeout `RC_OVERRIDE_TIME=0.5` giây do autopilot thực thi; raw RC_CHANNELS có thể giữ số cuối sau expiry, cần kiểm tra trạng thái override/failsafe thực thay vì chỉ chờ PWM hiển thị về giữa; không giả định một packet “neutral/release” đã được nhận hoặc đủ để làm hệ thống an toàn.
- Khi HEARTBEAT ARMED, không rõ trạng thái hoặc stale, app dừng toàn bộ RC. Sau khi lại DISARMED, không tự gửi lại throttle/lệnh cũ; thực hiện kiểm tra và deadman mới.

Kiến trúc và ranh giới giữa parser/controller/UI được mô tả trong [architecture](docs/architecture.md). Cần đọc [an toàn](docs/safety.md), [giao thức](docs/mavlink.md), [tham số](docs/parameters.md) và [kiểm thử](docs/testing.md) trước khi thử.

## Bàn giao

APK debug và ZIP project Android native dự kiến được đính kèm GitHub Release `rc-bench-v0.1.1-test`. Việc build cục bộ thành công chưa xác nhận upload/publication; xem kết quả workflow và release thực tế trước khi dùng link tải. Package `dev.drone.rcbench` riêng nên có thể cài cạnh Flutter.

## Log bench

Ứng dụng ghi CSV bất đồng bộ trong app-private `files/logs/`, tên file hiện ở tab GIÁM SÁT. Log gồm thời gian monotonic, TCP/ARM state, tuổi HEARTBEAT/RC, RC đã gửi/đã nhận, số override và lý do dừng. Queue log hữu hạn; xem counter dropped/error, không coi file luôn đầy đủ nếu quá tải/OS kill.

Với APK debug và USB debugging, lấy file từ Windows bằng Android SDK adb:

```powershell
adb shell run-as dev.drone.rcbench ls files/logs
adb exec-out run-as dev.drone.rcbench cat files/logs/TEN_FILE_CSV > rcbench-log.csv
```

Thay `TEN_FILE_CSV` bằng đúng tên được liệt kê. `run-as` chỉ dùng được với build debuggable được cài, không phải cơ chế export của bản release. CSV không chứa token signing vì ứng dụng không provision signing; vẫn xem log là dữ liệu thiết bị cần giữ riêng.

## Giới hạn không được bỏ qua

Ứng dụng chỉ biết trạng thái từ HEARTBEAT hợp lệ gần nhất. Autopilot có thể ARM sau heartbeat cuối nhưng trước packet RC tiếp theo; app không thể chứng minh điều chưa được quan sát. Phải có inhibit phía firmware ArduPilot phù hợp với bench, ngắt nguồn ESC và tháo cánh. Nếu không kiểm soát được các điều kiện đó, không gửi RC.

CRC MAVLink phát hiện lỗi truyền, **không xác thực nguồn**. Phiên bản này không xác minh chữ ký MAVLink 2 và từ chối frame signed chưa được xác minh; unsigned không được xem là kênh bảo mật. Không truyền hay lưu secret signing giả. Các client khác trên cùng TCP bridge, ví dụ Mission Planner, có thể ảnh hưởng tham số/ARM hoặc quyền RC; hỗ trợ nhiều TCP client phụ thuộc firmware bridge, chưa được xác minh.

Mục tiêu bàn giao là bench DISARMED có bằng chứng giao tiếp và khóa phát lệnh. Không có chế độ bay, tự giữ vị trí, ARM hay đường vòng cho phép vận hành động cơ.
