# Kết nối và thông số phải xác minh

Thực hiện khi đã tháo cánh quạt, nguồn công suất ESC ngắt và ESP32 được cấp nguồn giới hạn dòng. ESP32 GPIO dùng logic 3,3 V, không chịu 5 V. GND tín hiệu cần chung với GND ESC theo thiết kế nguồn thực tế; không nối nhiều đầu ra BEC song song nếu nhà sản xuất không cho phép. Không cấp động cơ từ ESP32 hoặc cổng USB.

| Thành phần | ESP32 | Kiểm tra trước khi nối |
|---|---|---|
| ESC motor ID 1 | GPIO14 | Điện áp logic, chuẩn và dải xung |
| ESC motor ID 2 | GPIO27 | Như trên |
| ESC motor ID 3 | GPIO26 | Như trên |
| ESC motor ID 4 | GPIO25 | Như trên |
| I2C SDA | GPIO21 | Pull-up về 3,3 V, không về 5 V |
| I2C SCL | GPIO22 | Điện trở pull-up tổng, dây ngắn, mức điện áp |
| GPS TX | GPIO16 / UART2 RX | Mức logic TX GPS theo datasheet module |
| GPS RX | GPIO17 / UART2 TX | Mức logic RX GPS và baud |
| GPS VCC | Nguồn theo module | Điện áp module không suy ra từ tên M10 |
| Các module GND | GND | Kiểm tra liên tục và đường hồi dòng công suất |

ICM-20948 IC trần và board breakout không nhất thiết dùng cùng điện áp cấp/logic. Kiểm tra sơ đồ board cụ thể, bộ chuyển mức và pull-up. Quét I2C lúc khởi động rồi cấu hình đúng địa chỉ. Các giá trị thường gặp chỉ là gợi ý để đối chiếu:

| Module | Địa chỉ thường gặp | Điều phải đối chiếu |
|---|---|---|
| ICM-20948 | 0x68 / 0x69 | AD0, WHO_AM_I, magnetometer trong IMU |
| BMP280 | 0x76 / 0x77 | SDO, chip ID; board ghi GY không xác định chip |
| INA219 | 0x40 và các biến thể strap | A0/A1, shunt thực tế, dải đo |

Có ACK không chứng minh đúng loại cảm biến. ICM/BMP cần đúng chip ID và dữ liệu hợp lý; thiếu/sai IMU không cho ARM. INA219 không có thanh ghi chip ID phổ quát tương tự: cần xác minh module/topology, đọc lại cấu hình, đối chiếu giá trị đo và phần cứng thực tế, không chỉ ACK. Không suy luận GPS đã hoạt động chỉ vì UART có byte: cần checksum và tuổi bản tin hợp lệ.


## Nơi cấu hình trong mã

| File | Trường | Mặc định / ý nghĩa |
|---|---|---|
| `firmware/include/pins.h` | GPIO ESC/I2C/UART | GPIO theo bảng trên |
| `firmware/include/hardware_config.h` | `IMU_ADDRESS`, `BMP_ADDRESS`, `INA_ADDRESS` | 0x69 / 0x76 / 0x40 chỉ là địa chỉ thử, cần scan/ID |
| `firmware/include/hardware_config.h` | `IMU_AXES` | `{1,2,3}`: phép hoán vị có dấu từ chip sang thân; chưa xác nhận lắp |
| `firmware/include/hardware_config.h` | `SHUNT_OHMS`, `CURRENT_LSB_A` | Cả hai 0: dòng chưa được cấu hình, không có số đo dòng hợp lệ |
| `firmware/include/hardware_config.h` | `MOTORS` | Bốn geometry `{0,0,0}`: cố ý không hợp lệ, cần map vật lý |
| `firmware/include/config.h` | `BATTERY_CELLS` | 0: chưa biết pin, không ARM |
| `firmware/include/config.h` | `ESC_SIGNAL_VERIFIED`, `MOTOR_MAPPING_VERIFIED`, `IMU_MOUNT_VERIFIED`, `BATTERY_CONFIG_VERIFIED`, `REAL_FAILSAFE_VERIFIED` | Tất cả false, chỉ đổi khi có bằng chứng tương ứng |
| `firmware/include/config.h` | `PWM_HZ`, `PWM_MIN_US`, `PWM_MAX_US` | 50 Hz / 1000 / 2000 µs provisional |
| `firmware/include/config.h` | `THROTTLE_LIMIT`, `THROTTLE_SLEW_PER_SEC` | 0,15 / 0,25 mỗi giây, giới hạn bench chưa tuning |
| `firmware/include/config.h` | `GPS_BAUD` | 9600, cần khớp module |
| `firmware/include/local_secrets.h` | `DRONE_AP_PASSWORD`, `DRONE_SESSION_TOKEN` | File cục bộ ignored, theo mẫu `.example.h`; không commit giá trị thật |

Ngay cả mock ARM hiện cũng bị chặn nếu geometry, hướng IMU hoặc pin chưa xác minh. Có thể chạy unit test/mock network không cần sửa các cờ nghiệm thu; không đổi cờ chỉ để làm màn hình chuyển ACTIVE. Để cấu hình thử một khung giả, dùng fixture unit test tách khỏi cấu hình thiết bị.

## ESC chưa xác minh

Động cơ DJI Phantom 3 850KV không xác định loại giao thức của ESC 30 A. Phải lấy datasheet ESC cụ thể hoặc đo bằng thiết bị phù hợp. Ghi lại: model/firmware ESC, PWM tần số được hỗ trợ, mức HIGH/LOW được chấp nhận, độ rộng xung min/idle/max, hành vi khi mất xung và thứ tự cấp nguồn. Cấu hình provisional PWM 50 Hz, 1000–2000 µs chỉ là điểm xuất phát đo bench, chưa được xác nhận với ESC này. Không mặc định hỗ trợ DShot/OneShot. Không chạy hiệu chuẩn endpoint ESC tự động.

Trước tiên đo bốn GPIO khi mock mode: không được có chuỗi xung điều khiển. Khi bản build có chủ ý cho phép đầu ra thật, đo từng kênh bằng oscilloscope/logic analyzer khi chưa nối động cơ; kiểm tra cả boot, reboot, watchdog, disarm và estop. Việc cắt xung không bảo đảm mọi ESC đều dừng giống nhau; cần xác minh hành vi thật.

## Motor mapping Quad X

Số 1–4 chỉ chỉ định dây GPIO. Người lắp phải điền vị trí thực tế và cấu hình mixer theo bảng sau. Không gán sẵn ID 1 thành front-right.

| ID / GPIO | Vị trí thật | x trước (+) / sau (-) | y trái (+) / phải (-) | Chiều quay nhìn từ trên | Dấu torque yaw phản lực | Loại cánh | Đã đo |
|---|---|---|---|---|---|---|---|
| 1 / 14 | Chưa xác minh | — | — | — | — | — | Chưa |
| 2 / 27 | Chưa xác minh | — | — | — | — | — | Chưa |
| 3 / 26 | Chưa xác minh | — | — | — | — | — | Chưa |
| 4 / 25 | Chưa xác minh | — | — | — | — | — | Chưa |

Hệ trục thân FLU thuận tay phải: X về trước, Y sang trái, Z lên trên. Lực nâng +Z tại y>0 sinh torque roll +X; tại x<0 sinh torque pitch +Y. Yaw +Z là quay ngược chiều kim đồng hồ nhìn từ trên. Dấu phản lực yaw của thân ngược chiều quay rotor. Cần hai rotor mỗi chiều, lắp cánh tương ứng, xác minh cả dấu cảm biến và dấu hiệu chỉnh mixer. Các geometry có sẵn cho test chỉ là cấu hình toán học.

## Pin và INA219

Ghi loại pin, số cell, điện áp tối đa, mức cảnh báo/cắt theo datasheet và thử tải. Chưa cấu hình pin không cho ARM. INA219 không đương nhiên đo được dòng tổng bốn ESC: module shunt thông dụng có thể cháy hoặc bão hòa. Tính `I = Vshunt / Rshunt` và `Pshunt = I² × Rshunt`, kiểm tra định mức shunt, PCB, dây, đầu nối và điện áp common-mode. Không mắc toàn bộ dòng motor qua module chưa xác minh.

Phân biệt điện áp bus tại chân đo INA219, sụt áp shunt và nguồn phía trước shunt. Với high-side đúng cực, điện áp nguồn xấp xỉ bus + shunt; đảo cực hoặc topology khác cần xử lý riêng. Dòng tại nhánh điện tử không phải dòng tổng động cơ. Ghi cả topology trong biên bản. Bão hòa hoặc số đo quá hạn phải được đánh dấu không hợp lệ, không biến thành pin còn tốt.
