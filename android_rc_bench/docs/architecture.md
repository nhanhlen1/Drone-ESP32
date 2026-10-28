# Kiến trúc ứng dụng bench

Project Android/Kotlin nằm riêng trong `android_rc_bench`, package `dev.drone.rcbench`. Nó không dùng WebSocket/JSON của firmware ESP32 hoặc model điều khiển của Flutter. Transport của project này là TCP byte stream mang MAVLink 2.

Các lớp giao tiếp tách parser/encoder MAVLink khỏi UI Android để có thể chạy unit test host. `mavlink/Mavlink2Parser.kt` xử lý stream/CRC và frame validity; `Mavlink2Encoder.kt` mã hóa các message trong whitelist; `TelemetryDecoder.kt` chuyển HEARTBEAT/RC_CHANNELS hợp lệ thành trạng thái quan sát. Chỉ target autopilot ArduPilot component 1 được khám phá mới có thể cung cấp trạng thái cho gate.

`control/BenchEngine.kt` là state/gate thuần Kotlin, dùng đồng hồ monotonic và epoch cho control permit; `transport/BenchSession.kt` là actor TCP nonblocking một chủ sở hữu, đọc RX trước khi quyết định TX. Host chỉ nhận IPv4 literal, không chạy DNS. Final permit check và NIO write nonblocking được đồng bộ với epoch thay đổi bởi stop/deadman/background trong engine; đây là tính chất local sender, không chứng minh trạng thái FC tức thời. RC tối đa 20 Hz, heartbeat GCS 1 Hz; chỉ một frame đang ghi và không queue backlog control. Frame đang ghi dở mà permit bị thu hồi thì đóng TCP để không tiếp tục frame RC cũ. UI gửi intent/joystick/deadman, không trực tiếp bỏ qua gate và ghi byte vào socket. Ngay trước mỗi RC override, sender kiểm tra snapshot mới nhất: kết nối, target, freshness, DISARMED, endpoint đã xác minh, foreground/focus và deadman. Một lần khóa phải làm mất quyền tiếp tục queue RC cũ; reconnect cần khám phá lại, không dùng trạng thái phiên trước.

`MainActivity.kt` nối lifecycle/window focus với engine; `ui/BenchScreen.kt` dựng ba tab native và xác nhận bench hai bước; `transport/AndroidWifiBinder.kt` gắn socket vào mạng Wi-Fi đang có để AP không Internet vẫn dùng được khi mobile data bật. App không tự join AP hoặc tự reconnect. `onPause` dừng quyền và đóng TCP; trở lại app cần kết nối/kiểm tra mới.

`transport/SessionLogger.kt` ghi CSV app-private trên worker với queue hữu hạn, không giữ control gate để ghi disk. Dropped/error được hiển thị; không hứa log đầy đủ khi lỗi lưu trữ hoặc OS kill.

UI dùng Android Views native, có joystick riêng và nút deadman momentary độc lập. Joystick trái điều khiển throttle/yaw, phải điều khiển pitch/roll. Throttle chốt và dùng chuyển động tương đối để lần chạm mới không nhảy throttle; yaw/roll/pitch trở về giữa khi nhả. Deadman không phải joystick: giữ cần điều khiển không tự cấp quyền phát nếu chưa giữ deadman. Cần kiểm tra multi-touch thực trên điện thoại, kể cả tổ hợp hai joystick và deadman.

CH4 bị cap ±25% calibration span trong cả full và single mode vì rudder arming chưa xác minh; việc app không gửi MAV_CMD ARM chưa đủ ngăn RC gesture. Tất cả kênh cần xác nhận thủ công lại theo phiên TCP. Engine giám sát feedback RC và thu hồi quyền nếu không khớp trong 750 ms.

RC_CHANNELS chancount là receiver count, không phải giới hạn raw channel có thể đọc khi override. Engine giữ đủ 18 raw channel và chấp nhận count 0–18, cho bootstrap 750 ms hữu hạn kể cả raw ban đầu 0, sau đó cần feedback khớp. Count 19/255 bị từ chối. Đây là xử lý receiverless cho RC_OPTIONS=33, không thay các gate DISARMED/freshness/calibration/deadman.

Local TX/RC setpoint và giá trị RC_CHANNELS quan sát được phải tách riêng trong state/UI. Counter TX chỉ nói app đã cố gửi byte; không chứng minh autopilot chấp nhận. Thiếu RC_CHANNELS hoặc heartbeat phải hiển thị thiếu/stale, không tạo telemetry giả để làm dashboard sống.

Các tham số trong ứng dụng mô tả giới hạn bench, không đồng bộ ngầm vào autopilot. Việc người dùng xác minh endpoint chỉ cấp quyền dùng giá trị trong ứng dụng, không gửi PARAM_SET. Không lưu secret MAVLink signing hoặc giả định CRC là xác thực. Không có bề mặt gửi ARM, DISARM, thay mode hay COMMAND_LONG.

Xem `safety.md` cho khoảng trống quan sát ARMED giữa hai heartbeat: kiến trúc Android chỉ kiểm soát các frame đã quan sát, không thay inhibit phía autopilot và ngắt nguồn ESC.
