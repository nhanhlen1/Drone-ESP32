# Kiểm thử và bằng chứng nghiệm thu

Ghi commit/source snapshot, lệnh, exit code, thời gian và log của mỗi lần chạy. Tách `PASS`, `FAIL`, `SKIP`, `NOT RUN`; không xem zero-test run hoặc một cổng đang mở là kiểm thử chức năng.

## Trên máy phát triển/cloud

```sh
./scripts/test_native.sh
./scripts/build_firmware.sh
./scripts/test_network.sh
./scripts/test_sensors.sh
./scripts/test_gps.sh
cd mobile_app
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

`test_network.sh` biên dịch module Network thật với ArduinoJson 6.21.5 và transport HAL giả; nó cần source dependency từ PlatformIO hoặc `ARDUINOJSON_SRC` trỏ đến source đáng tin. `test_sensors.sh` kiểm thử driver/calibration với HAL giả. `test_gps.sh` chạy manager GPS với parser TinyGPSPlus 1.1.0 thật và UART giả; cần source dependency hoặc `TINYGPSPLUS_SRC`. Các test này không đo radio, UART hay I2C vật lý.

Native tests phải kiểm tra PID hữu hạn/saturation/anti-windup/reset, dấu mixer từng trục, AHRS với dữ liệu mô phỏng, sequence/freshness và state/failsafe khi có hỗ trợ. Build firmware kiểm tra compiler và dependency/API thực tế; không kiểm tra được tín hiệu hay dữ liệu I2C. Flutter tests không thay thử cảm ứng đa điểm trên điện thoại. Xem kết quả hiện tại trong `validation.md`.

## Ma trận phần cứng bắt buộc

| Test | Điều kiện và thao tác | Kết quả cần chứng minh |
|---|---|---|
| Firmware build | Toolchain/dependency được pin; build sạch | Không lỗi compiler/linker; ghi kích thước flash/RAM |
| Sensor I2C | ESC ngắt, scan bus, đọc ID/dữ liệu từng module | Đúng địa chỉ/ID; lỗi thiếu chip bị phát hiện; số đo hợp lý |
| GPS UART2 | NMEA đúng baud; checksum/fix/age | Có dữ liệu mới, HDOP/sat/fix và chuyển stale khi ngắt |
| Attitude | Nằm ngang, nghiêng/quay mỗi trục; ghi gyro và angle | Dấu/trục đúng FLU; độ trôi/rung được định lượng |
| PID/mixer | Mock; từng trục setpoint và mô phỏng feedback | Torque giảm sai số, motor correction đúng geometry |
| PWM | Không cánh, đầu tiên không động cơ; đo 4 GPIO | Tần số/độ rộng xung đạt datasheet, boot không tự ARM |
| Motor mapping | Không cánh, rig cố định, từng ESC riêng | Đúng ID/chiều quay; không tự tăng tốc; ngắt nguồn vật lý sẵn sàng |
| Wi-Fi functional | App thật trên AP; auth, telemetry và control mock | State/attitude cập nhật đúng; ack/error rõ; token sai bị từ chối |
| Multi-touch | Hai ngón, thêm/bớt ngón, kéo ra ngoài vùng | Không đổi pointer; roll/pitch/yaw về giữa; throttle không tự về giữa |
| App ngừng tương tác | Nhả tay, chạy nền, khóa màn hình, mất focus | Không stream vô hạn lệnh cũ; firmware timeout |
| Freshness | Trùng/lùi seq, packet cũ/tương lai, NaN/ngoài dải | Không cập nhật control/freshness; có error/drop accounting |
| Link loss | Ngắt Wi-Fi và giữ socket không gửi control | FAILSAFE đúng chính sách bench; lệnh cũ không chạy vô hạn |
| Sensor failure | Ngắt IMU, sai ID, I2C stuck/timeout | Health stale/lỗi; không ARM hoặc FAILSAFE |
| Pin | Nguồn kiểm thử trong định mức, dưới ngưỡng, bão hòa đo | Cảnh báo/khóa đúng; không báo pin tốt do lỗi cảm biến |
| GPS loss | Ngắt anten/module/dừng bản tin | Không dữ liệu vị trí mới giả; manual không tự chuyển RTH |
| Reboot/watchdog | Reset có kiểm soát trên bench | Không tự ARM, không tái dùng session/throttle, output khóa |
| Emergency Stop | Kích hoạt khi mock ACTIVE, thử ARM lại | Dừng/chốt; không bị heartbeat xóa |
| Timing | Telemetry + control + malformed traffic + slow sensors | Đo Hz/jitter/WCET/deadline misses; mọi lỗi được giải thích |

Mỗi test cần log hoặc hình đo của lần chạy cụ thể. Chỉ đánh dấu các mục đã thực hiện. Chưa kết nối phần cứng thì tất cả test thiết bị là NOT RUN.

## Kiểm tra phản hồi trước thử có cánh

Trên rig không cánh, khi roll/pitch bị nghiêng khỏi setpoint, hiệu chỉnh phải tạo moment khôi phục. Kiểm tra bằng output mock trước, đối chiếu geometry và chiều gyro; sau đó mới xét tín hiệu ESC. Không cầm tay khung có động cơ đang quay và không lắp cánh để “thử nhanh”. Cần mô phỏng/HIL và đánh giá độc lập động lực học/timing/failsafe trước mọi test ngoài bench.

Kiểm tra driver ESC trên host: `./scripts/test_esc.sh`. Script chạy driver mock thật qua HAL giả và xác nhận cấu hình bật ESC thật bị compiler từ chối khi các điều kiện phần cứng chưa được xác minh. Không thay thế việc đo xung PWM trên GPIO.
