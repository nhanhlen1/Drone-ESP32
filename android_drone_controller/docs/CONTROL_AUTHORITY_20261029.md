# Tách quyền RC Bench và ứng viên điều khiển ARMED

Ngày29/10/2026. `ControlAuthority.kt` là mô-đun domain Kotlin có thể tái sử dụng, chuẩn bị để tích hợp với source Google AI Studio khi source đọc được. **Không phải bản chuyển đổi từ ZIP Google AI Studio**, không tạo app mới và không thay đường phát thật của APK hiện tại. Mã GG `com.example` chưa được đọc do tệp ZIP vượt giới hạn chuyển32MiB; giữ nguyên giới hạn này trong báo cáo.

## Quyền và trạng thái

- `READ_ONLY` không bao giờ phát RC.
- `BENCH` chỉ hoạt động DISARMED, cần props off, ngắt vật lý nguồn ESC, xác nhận riêng, cấu hình RC đã đọc/đối chiếu, target đúng và HEARTBEAT mới. Cho CH1 quanh trim ±30µs để mô hình hoá1470/1500/1530; kênh2–4 dùng ignore65535. Lease750ms tuyệt đối từ lần ghi frame hoàn chỉnh đầu, không gia hạn bởi echo. Bench không cần RC_CHANNELS ban đầu. Khi nhận ARMED, bench thu hồi quyền.
- `FLIGHT_CANDIDATE` mặc định bị từ chối vì provider production không cung cấp hồ sơ qualification. Trong test/mô phỏng, provider có thể cấp hồ sơ ràng buộc identity+connectionEpoch+thời hạn, có tham chiếu chứng cứ và đủ các mục độc lập: software, SITL, RC input phần cứng, override timeout, loss-of-link failsafe, dừng độc lập, mapping động lực và chấp thuận nghiệm thu riêng của người vận hành.
- Một phiên flight chỉ khởi tạo khi DISARMED, throttle ở min/các trục trim, foreground, telemetry/RC input mới và cấu hình tương ứng. Người vận hành phải bật phiên riêng; mô-đun không gửi ARM hoặc DISARM. Sau khi phiên đã được cấp hợp lệ, HEARTBEAT DISARMED→ARMED bình thường **không tự cắt RC**; quyết định chuyển `ARMED_CONTROL_CANDIDATE` và cho bốn kênh trong dải đã đối chiếu. Bench và flight không dùng chung quy tắc “ARMED luôn dừng”.
- Mất heartbeat, RC echo, input/deadman, foreground, qualification hoặc thay đổi tham số/target thu hồi authority epoch. Reconnect/đổi mode không tự cấp lại quyền, không phát throttle cũ. FC DISARMED sau phiên ARMED cũng thu hồi; không tự ARM lại.

CRITICAL mặc định không được quyền ở mô-đun này. `BenchEngine` đang có quy trình chẩn đoán CRITICAL riêng đã kiểm thử, với xác nhận thứ tư/mask SYS_STATUS/giới hạn CH1±20µs. Không ghép hai chính sách hoặc nới APK thật bằng một flag. Một adapter cho base GG cần chọn chính sách bench có căn cứ sau khi đọc source và kiểm thử lại.

## Hợp đồng scheduler

`decision()` trả `AuthorityDecision` chứa `canTransmit`, `connectionEpoch`, `authorityEpoch`, target, mode, stage và bản sao giá trị kênh. Scheduler một writer ở20Hz giữ latest input, không backlog, kiểm tra lại cả hai epoch/quyền trước mỗi partial write. Chỉ sau khi ghi trọn frame mới gọi `frameWritten()`; HEARTBEAT và việc tạo frame không được dùng thay cho bước này.

`input()` và `deadman(true)` là hai watchdog độc lập, cần cập nhật chủ động trong200ms. Input ở đây là ý định điều khiển hiện hành được UI/dispatcher gia hạn, không chỉ callback khi ngón tay thay vị trí; không suy việc đứng yên joystick thành mất input trong adapter. `frameWritten()` và echo được ràng buộc phiên/target/thời gian; correlation mất khi ARM state đổi. Một raw RC phù hợp trước khi app gửi không tự thành “đã xác minh lệnh app”.

`AuthorityStopEffect` chỉ là ý định/chẩn đoán, không có socket hoặc byte encoder. Nhả deadman ở bench đã thực sự phát trả `RELEASE_BENCH_CHANNELS` để adapter có thể release đúng CH1 do mình sở hữu; flight trả `STOP_RC_TRANSMISSION`, không tự release/ga-min/ARM/DISARM và không hứa động lực dừng. Release không phải motor-kill và vẫn cần override timeout/failsafe phía FC.

## Bằng chứng và giới hạn

Các test của mô-đun dùng clock fake và hồ sơ **SIMULATION-TEST-DOUBLE**; không biến các boolean trong test thành chứng nhận phần cứng hoặc quyền bay thật. Test bao gồm startup/freshness/readback, quyền bench, CH11470/1530, no-RC bootstrap, ARMED bench revoke, ARMED flight continuation, qualification thiếu/hết hạn, per-connection reset, input/heartbeat/echo/deadman watchdog và không phát lại throttle.

Đã biên dịch các source Kotlin thật bằng compiler CLI2.1.0 với JVM target17, chạy JUnit4.13.2: **27test PASS**,0failure. Log `/workspace/tooling/logs/qualification-host-tests.log`. Đây là kiểm thử JVM độc lập, không khởi chạy Gradle/Android hoặc gửi dữ liệu tới phần cứng. Lệnh Gradle để tái kiểm tra trong project: `bash scripts/verify.sh testDebugUnitTest --tests '*ControlAuthorityTest'`; root sẽ chạy hồi quy chung và báo kết quả riêng.

Mô-đun chưa nối vào `BenchSession`, UI hoặc dịch vụ ARM; source GG chưa được tích hợp. Vì vậy không được mô tả APK hiện tại là đã hỗ trợ điều khiển ARMED chỉ vì domain test đạt. Cấp phần cứng vẫn `HARDWARE NOT TESTED`, cấp bay vẫn `FLIGHT READY NOT VERIFIED`.
