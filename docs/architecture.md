# Kiến trúc và quy ước

INA219 dùng driver thanh ghi trực tiếp với shunt thực tế; không phụ thuộc thư viện Adafruit INA219 và không dùng calibration cố định theo module giả định. Firmware gồm các lớp: cấu hình phần cứng; driver/manager cảm biến; hiệu chuẩn; AHRS/attitude; PID; mixer; ESC; pin; GPS; AP/WebSocket; validator lệnh; state machine; failsafe; telemetry; lưu cấu hình và diagnostics. Nếu khởi tạo queue/task thất bại, `runtimeReady` chặn loop tiếp tục và đầu ra vẫn khóa. `src/main.cpp` chỉ giữ setup/loop và tích hợp vòng network/GPS. `src/Runtime.h` định nghĩa các POD snapshot/queue dùng chung; `src/control/FlightRuntime.cpp` sở hữu tác vụ flight, state/PID/mixer; `src/sensors/SensorTask.cpp` sở hữu manager I2C và giao dịch calibration; `src/telemetry/Telemetry.cpp` mã hóa snapshot ngoài vòng control. `include/drone_core.h` là umbrella cho chín header `include/core/`: math, command, ahrs, pid, attitude_controller, mixer, safety, session và timing. Thuật toán độc lập Arduino được test trên host.

## Hệ tọa độ và đơn vị

Thân dùng FLU thuận tay phải: +X trước, +Y trái, +Z lên. Euler theo ZYX; góc dương theo quy tắc bàn tay phải. Gia tốc kế đứng yên, đúng hướng trả specific force gần (0,0,+1) g. Gyro dùng rad/s trong thuật toán; góc AHRS radian. Telemetry attitude dùng độ để hiển thị. Từ kế dùng µT và phải được hiệu chuẩn/hướng trục nhất quán. Hướng board có thể khác thân: áp dụng phép quay đúng định thức +1 cho cả accel/gyro/mag; không tùy tiện đổi một dấu riêng để làm màn hình trông đúng.

AHRS tích phân quaternion từ gyro; gia tốc chỉ hiệu chỉnh khi biên độ/độ tin cậy đạt điều kiện. Từ kế chỉ đóng góp heading khi trường đo hợp lệ; nhiễu dòng motor có thể làm yaw sai dù gyro và accel vẫn tốt. Không có magnetometer hợp lệ thì yaw có thể trôi và không phải heading tuyệt đối. BMP280 chỉ ước lượng cao tương đối theo áp suất tham chiếu, chịu nhiễu luồng cánh, nhiệt độ và thời tiết.

Outer loop điều khiển roll/pitch attitude tạo rate setpoint; inner loop điều khiển tốc độ góc tạo torque normalized. Chế độ rate bỏ outer loop. Lệnh yaw là yaw rate, chưa có giữ heading tuyệt đối. PID có giới hạn, lọc đạo hàm, anti-windup cục bộ và reset lúc disarm. Anti-windup hiện dựa trên saturation riêng của PID; chưa back-calculate từ saturation mixer/giới hạn hoặc slew của ESC. Các giới hạn output/motor có thể làm tích phân không phản ánh torque thực sự; cần phân tích và triển khai actuator-aware anti-windup trước khi dùng trên không. Các giới hạn PID ban đầu là cấu hình bench, chưa phải tuning thực tế.

## Thời gian thực

Scheduler hiện đặt sensor/control 250 Hz (chu kỳ 4 ms); BMP280 25 Hz, INA219 10 Hz và telemetry 20 Hz. GPS được poll với budget đọc UART hữu hạn. Mục tiêu IMU/AHRS/control 200–500 Hz phải được đo; tần số thực tế phụ thuộc I2C, DLPF và tốc độ output của IMU. Tác vụ sensor priority 4 và control priority 5 nằm trên core 1; vòng network/GPS/telemetry chạy trong Arduino loop riêng. Slow I2C vẫn dùng task sensor để chỉ một chủ sở hữu Wire; task control không chờ Wire nhưng có thể nhận sample muộn. Đọc slow sensor và UART/JSON/Wi-Fi phải tách khỏi tính toán control; không ghi flash hay log Serial liên tục trong vòng nhanh. Dữ liệu đi qua snapshot có khóa ngắn hoặc queue; không giữ mutex trong I2C, JSON hay phát mạng. Tác vụ cao tần không cấp phát heap. FreeRTOS scheduler có thể nhường bằng tick; không dùng `delay()` để che deadline miss.

Ghi tần số thực tế, độ lệch chu kỳ, thời gian thực thi cực đại và số deadline miss. Đo khi kết nối ứng dụng, stream telemetry, xử lý packet lỗi và I2C timeout. Nếu cần deadline 4 ms mà transaction I2C có thể timeout dài hơn, phải ghi nhận đây là lỗi thời gian thực và chuyển trạng thái tương ứng; không xem một cảm biến còn ACK là loop tốt. Chọn tần số thấp hơn hoặc đổi đường dữ liệu chỉ sau khi phân tích và đo lại.

## Trạng thái và quyền sở hữu

Luồng: BOOT → SELF_TEST → DISARMED → PRE_ARM_CHECK → ARMED_IDLE → ACTIVE. FAILSAFE và EMERGENCY_STOP khóa lại việc tiếp tục lệnh cũ. ARM cần IMU/calibration hợp lệ, pin đã cấu hình đúng, throttle tối thiểu, loop ổn định, session xác thực, không lỗi nghiêm trọng và xác nhận riêng. GPS không phải điều kiện bắt buộc của rate/angle manual; chưa có mode định vị để bật khi GPS hợp lệ.

Mặc định mock mode cho phép xem đầu ra tính toán mà không phát PWM thật. Lưu NVS và calibration chỉ cho phép khi disarmed; không ghi mỗi packet. Session mới không được kế thừa quyền điều khiển hoặc throttle cũ; command queue có generation để loại lệnh thuộc session cũ. Emergency được chốt riêng ở network và runtime, không mất khi auth/revoke hoặc reset queue do DISARM. Không dựa vào WebSocket còn mở để kết luận người dùng đang điều khiển: phải có command freshness và deadman tương tác ứng dụng.

## Dữ liệu GPS và chế độ tương lai

UART2 dùng NMEA đã kiểm tra checksum. Nếu module đang UBX-only, cấu hình module phát NMEA bằng công cụ của nhà sản xuất hoặc phát triển parser UBX riêng; không gắn nhãn UBX là đã hỗ trợ. Home chỉ được ghi khi có fix đủ chất lượng và đủ mới. Mất GPS chỉ vô hiệu hóa dữ liệu định vị, không tự bật RTH. Interface giai đoạn 2 và các điều kiện chưa đáp ứng nằm trong `phase2.md`.
