# Giới hạn vận hành và điều kiện mở khóa

Dự án đang ở giai đoạn bench, không được xem là flight controller đã nghiệm thu. Tháo toàn bộ cánh quạt; cố định khung; có công tắc cắt nguồn công suất độc lập; tránh người trong vùng rotor. Firmware, Wi-Fi hoặc điện thoại không thay thế ngắt nguồn vật lý.

## Hai lớp quyền

Mock mode là cấu hình mặc định. ARM trong mock chỉ chuyển state và tính toán output; không phải tín hiệu cho phép quay motor thật. Đầu ra thật cần macro build `DRONE_ENABLE_REAL_ESC=1`, các cờ nghiệm thu cấu hình đều hợp lệ và chính sách failsafe đã xác minh riêng; lệnh mạng không được tự mở khóa hardware. Không commit cờ xác nhận như bằng chứng đã đo.

ARM cần một thao tác xác nhận riêng, throttle thấp, session xác thực còn mới, cảm biến/hiệu chuẩn và pin hợp lệ, loop ổn định và không lỗi nghiêm trọng. Bất kỳ điều kiện chưa biết đều được xem là chưa đạt. Boot/reboot không tự ARM; session và lệnh cũ không được tái sử dụng. Calibration/PID/flash chỉ thực hiện disarmed. Emergency Stop có chốt độc lập với queue/session (auth/revoke hoặc DISARM không được xóa yêu cầu đã nhận), không cho heartbeat/ARM/DISARM xóa chốt; bản hiện tại cần reboot có chủ ý sau khi đã ngắt nguồn công suất và tìm nguyên nhân.

## Failsafe và liên kết

Control timeout dựa trên tuổi control packet, không chỉ tuổi heartbeat/WebSocket. Packet lỗi, trùng, lùi thứ tự, timestamp tương lai/quá cũ và session sai không làm mới thời hạn control. Heartbeat cho biết peer còn trả lời, không chứng minh joystick còn tương tác. App hiện gửi control 20 Hz khi ít nhất một joystick còn được giữ và heartbeat 5 Hz khi foreground. Throttle là cần ga có chốt: nhả joystick trái vẫn giữ mức throttle nếu joystick phải còn được giữ; yaw/roll/pitch tự về giữa theo trục được nhả. Khi nhả cả hai joystick, app chạy nền, mất route hoặc hết deadman, ngừng control để firmware timeout; heartbeat không giữ throttle sống. Đây là chính sách bench có chủ ý, cần người vận hành hiểu trước thử.

Control freshness tối đa 250 ms, control timeout 300 ms và heartbeat lease 500 ms là các giới hạn giao thức hiện tại. Trong bench, timeout/lỗi nghiêm trọng đưa state vào FAILSAFE và khóa output theo chính sách triển khai. Cutoff là phù hợp cho thử không cánh trên bàn, nhưng có thể làm rơi một drone đang bay. Không dùng chính sách bench này trên không. Chưa có estimator/controller tự hạ cánh hay RTH để thay thế; do đó chưa có chính sách mất liên kết đã xác minh cho vận hành trên không. Emergency Stop là yêu cầu dừng ngay đã được người dùng chủ ý kích hoạt, có nguy cơ rơi nếu dùng trên không.

## Giới hạn actuator/control hiện tại

PID có anti-windup theo giới hạn của mỗi controller nhưng chưa bù theo saturation của mixer hoặc slew/giới hạn công suất ở driver ESC. Collective/output slew cũng có thể thay đổi moment thực tế so với yêu cầu PID. Đây là giới hạn thuật toán đang mở, cần mô phỏng và triển khai/kiểm chứng actuator-aware anti-windup, desaturation và độ trễ trước khi đánh giá bay. Tham số 15% throttle bench không phải “mức an toàn” phổ quát với motor thật.

## Nghiệm thu trước đầu ra motor thật

Ghi ngày, phiên bản firmware, người thực hiện, thiết bị đo và kết quả cho từng mục:

1. Nguồn, ground và điện áp logic được đo; GPIO không nhận 5 V; ESC model/chuẩn xung được xác định.
2. Đã đo tín hiệu min/max/tần số/polarity và hành vi mất xung, boot, reboot, disarm, estop trên từng kênh.
3. Motor mapping/chiều quay/cánh tương ứng xác nhận từng ID; mixer đúng moment roll/pitch/yaw theo FLU.
4. IMU đúng trục, đúng dấu gyro/attitude; hiệu chuẩn lặp lại đạt; từ kế không bị trường động cơ làm hỏng heading.
5. INA219 topology, shunt/current range, pin và điện áp nguồn đã đối chiếu đồng hồ đo; bão hòa/timeout không báo pin tốt.
6. Đã đo timing dưới tải mạng và lỗi I2C; không còn deadline miss chưa giải thích hoặc dữ liệu stale được dùng để ARM.
7. Boot throttle cao, mất link, packet lỗi, IMU lỗi, pin thấp, watchdog, reboot và estop đều cho kết quả yêu cầu.
8. Có hồ sơ bench không cánh, mô phỏng/HIL và đánh giá người có chuyên môn trước mọi quyết định thử có cánh.

Không có checkbox phần mềm nào thay các bằng chứng này. Kết quả unit test của mixer không xác định dây motor thật. Build không đo phản ứng động lực học, độ rung, nguồn hay ESC.

## Bảo mật thực tế

AP có WPA2 và token phiên giúp ngăn truy cập vô ý, nhưng WebSocket `ws://` không mã hóa đầu cuối. Người có quyền vào mạng, thiết bị bị chiếm hoặc khả năng MITM vẫn là rủi ro. Không coi token là xác thực chống kẻ tấn công mạnh; không phát token vào log và không dùng mật khẩu thật trong mã. Nếu triển khai ngoài bench cần mô hình đe dọa, cơ chế key provisioning/rotation và kênh xác thực/mã hóa được thiết kế, kiểm thử riêng.
