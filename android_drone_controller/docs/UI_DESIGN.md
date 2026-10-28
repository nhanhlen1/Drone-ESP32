# Giao diện Android và trạng thái kiểm định

Ứng dụng `dev.drone.controller` dùng Kotlin native, `ComponentActivity`, `ViewModel` với factory và runtime được inject, `StateFlow`, coroutine thu trạng thái theo lifecycle và custom View cho hai joystick. Không dùng WebView hoặc Flutter. Package riêng cài song song app RC Bench cũ. Min Android 8/API26; compile/target36; Gradle8.12, AGP8.9.1, Kotlin2.1.0, bytecode Java17. Có icon vector riêng và splash hệ thống Android12+; theme tối, điều khiển tối thiểu48dp.

## Các trang

- **Kết nối**: mở Wi-Fi settings, nhập IPv4/port, kết nối/ngắt thủ công; phân biệt TCP, HEARTBEAT, telemetry, RC input và quyền ARM. Wi-Fi không cần Internet; socket được gắn vào mạng Wi-Fi hiện có.
- **Tay cần**: Mode2 xem trước ngoại tuyến, hai ngón có pointer ID độc lập. Trái ga/yaw, phải pitch/roll. Ga thay đổi tương đối tránh nhảy tới điểm chạm, giữ khi nhấc tay; reset khi STOP, đổi trang, mất focus hoặc phiên mới. Không phát RC từ joystick.
- **Telemetry**: dữ liệu giải mã thực, đơn vị rõ; mẫu hết thời hạn hiển thị chưa có dữ liệu và tuổi mẫu. Không suy ra dòng, phần trăm pin, thời gian bay, độ cao khí áp từ cảm biến chưa được xác minh. Mode lạ giữ mã thô.
- **Kiểm tra RC**: riêng ba xác nhận tháo cánh/ngắt vật lý ESC/cho phép phép thử, CH1 đã đối chiếu, HEARTBEAT DISARMED mới và deadman. Giới hạn CH1 ±20µs,750ms; kênh còn lại ignore. ARM flag được tách khỏi system health: DISARMED không đồng nghĩa sức khỏe FC tốt. CRITICAL hiển thị cảnh báo đỏ và mặc định cấm phát. Chỉ khi engine xác định tình huống RC/prearm hẹp bằng SYS_STATUS mới, chưa có RC ban đầu và không có lỗi cảm biến khác, mới hiện checkbox xác nhận thứ tư để cho phép riêng chẩn đoán CH1. Checkbox không tự tích và bị xóa khi stop/mất focus/đổi phiên; CRITICAL không được gọi là an toàn và phép thử không mở khóa điều khiển tổng quát. Không mở khóa joystick/ARM sau test. Phản hồi PWM khớp được ghi là tương quan; chưa là bằng chứng FC áp dụng.
- **Cấu hình**: MIN/TRIM/MAX và cờ đối chiếu từng kênh; chỉ lưu ứng dụng. Xem tham số thực đọc từ FC. Không có PARAM_SET. Xuất CSV qua Android Storage Access Framework không cần quyền bộ nhớ chung.
- **Chẩn đoán**: byte RX/TX, gói được tạo/ghi đủ socket, nhịp RC, CRC/parser, sequence gaps, tuổi HEARTBEAT/RC, request/ACK, RC TX/RX riêng và nhật ký.
- **Hướng dẫn**: quy trình ban đầu, lý do khóa, cách lấy log bằng ADB và giới hạn dừng qua Wi-Fi.

Nút STOP luôn ở đáy màn hình. Toàn bộ trang cuộn được, thanh điều hướng cuộn ngang; áp dụng inset system bars, cutout và bàn phím. Mỗi lần chuyển tab thu hồi quyền thử. Background/rotation/khóa màn hình ngắt TCP và thu hồi quyền; không tự reconnect hoặc khôi phục ga.

## Kiểm thử UI

`JoystickModelTest` kiểm tra mapping Mode2/dấu pitch, độc lập pointer ID, ngăn ngón mới cướp cần, ga không nhảy tại điểm chạm, giữ/đặt lại ga, hồi tâm, deadzone/clamp và đầu vào không hữu hạn. Kết quả chạy thực nằm trong báo cáo unit test chung.

`ControllerViewModelTest` chạy host với engine/session thật và clock giả: background thu hồi permit và đóng link; mất rồi lấy focus không khôi phục ga/quyền; STOP giữ telemetry nhưng hủy permit; xem trước không tạo quyền; thiếu ngắt ESC không cấp phép; clear ViewModelStore đóng runtime và chặn reconnect. Kết quả chạy thực nằm trong báo cáo unit test chung.

`ControllerActivityTest` là instrumentation test cho recreation, lifecycle background/foreground, trạng thái ViewModel và permission manifest. Cần điện thoại hoặc emulator để chạy; compile thành công không được ghi PASS cho execution.

Chưa xác minh khả năng hiển thị/tương tác trên điện thoại thật, font hệ thống lớn, xoay màn hình khi đang TCP, đa chạm Android thực, Wi-Fi ESP32 thực và cử chỉ deadman thực. Các mục này **NOT VERIFIED** nếu không có log instrumentation/device tương ứng. Layout/code review không thay thế kiểm thử vật lý.

## Lint và tương thích API26

Thuộc tính `windowLightNavigationBar` chỉ có từ API27 được đặt trong `values-v27/`; API26 dùng theme nền tối mà không tham chiếu thuộc tính mới. Splash API31 nằm riêng `values-v31/`. Các chuỗi trạng thái động, chẩn đoán, firmware và cảnh báo CRITICAL sử dụng string resource với placeholder để giữ đơn vị và nội dung nhất quán.

`ControllerScreen` được khởi tạo bằng code với `Activity` và `UiActions` được inject; không được inflate từ XML hoặc Layout Editor. Vì vậy cảnh báo `ViewConstructor` còn lại đối với lớp này là giới hạn thiết kế đã biết, không phải constructor lỗi khi chạy. `StickView` cung cấp constructor `(Context)` bằng `@JvmOverloads`. Không tắt lint rule toàn dự án và không hạ minSdk để che lỗi API.
