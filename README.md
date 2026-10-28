# ESP32 Drone — bộ điều khiển bench và ứng dụng Android

Ứng dụng **Kotlin Android Studio cho ArduCopter/MAVLink2** hiện nằm riêng tại [`android_rc_bench`](android_rc_bench/README.md): TCP `192.168.4.1:5760`, kiểm tra RC chỉ khi DISARMED. Dự án này dùng firmware ArduPilot đang hoạt động trên thiết bị của bạn; không cần nạp firmware Arduino/WebSocket prototype bên dưới để dùng app mới.

Dự án điều khiển quadcopter ESP32-WROOM-32 qua Wi-Fi AP và WebSocket. Firmware Arduino/PlatformIO đọc ICM-20948, BMP280, INA219 và GPS NMEA, ước lượng tư thế, chạy PID nối tầng, mixer Quad X và phát telemetry cho ứng dụng Flutter.

**Đây là mã phát triển để kiểm thử bench. Build thành công không chứng minh đủ an toàn để bay. Đầu ra motor mặc định ở mock mode; không cấp tín hiệu cho ESC thật. Không lắp cánh quạt khi nạp firmware, hiệu chuẩn hoặc kiểm thử motor.** PID ban đầu chưa được tuning trên khung thực tế. Các chế độ giữ độ cao/vị trí, RTH và tự hạ cánh chưa được triển khai.

## Bắt đầu

Các lệnh sau chạy từ thư mục kho `/workspace/Drone-ESP32` hoặc thư mục tương đương trên máy cá nhân:

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install platformio==6.1.18
./scripts/test_native.sh
./scripts/build_firmware.sh
```

PlatformIO dùng nền tảng `espressif32@6.9.0`, Arduino-ESP32 2.0.17 và board `esp32dev`; xem phiên bản dependency thực tế trong `firmware/platformio.ini`. Không đổi sang API LEDC Arduino-ESP32 3.x mà chưa cập nhật driver và kiểm tra lại tín hiệu bằng thiết bị đo.

Flutter SDK 3.35.7:

```sh
cd mobile_app
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

APK debug dùng cho phát triển; cấu hình ký và build release là bước riêng. Ứng dụng không có dữ liệu bay giả khi chưa kết nối. Sao chép `firmware/include/local_secrets.example.h` thành `firmware/include/local_secrets.h` (file thật bị ignore), đặt mật khẩu AP 8–63 ký tự và token ngẫu nhiên riêng tối thiểu 32 ký tự. Nếu thiếu cấu hình hợp lệ, AP mặc định không được mở. Kết nối Wi-Fi AP của ESP32, nhập địa chỉ `ws://192.168.4.1:81/ws` và token phiên trong ứng dụng, quan sát telemetry trước khi thử lệnh. Trên Android, chọn tiếp tục dùng mạng Wi-Fi khi hệ điều hành cảnh báo mạng không có Internet.

Nạp và theo dõi Serial bằng USB sau khi ngắt nguồn ESC:

```sh
./scripts/upload_firmware.sh /dev/ttyUSB0
./scripts/monitor_firmware.sh /dev/ttyUSB0
```

Không có thiết bị USB trong cloud không ngăn build/test thuật toán; nó ngăn xác minh cảm biến, tín hiệu PWM, chiều quay và động lực học thật. Không tự chạy `upload`, motor test hoặc calibration tại startup.

## Cấu trúc

- `firmware/include`, `firmware/src`: cấu hình, thuật toán độc lập phần cứng, driver và các tác vụ ESP32.
- `firmware/test`, `scripts/test_native.sh`: kiểm thử thuật toán trên host.
- `mobile_app`: ứng dụng Flutter Android; kiến trúc cho phép mở rộng iOS nhưng iOS cần cấu hình ATS/quyền mạng và build trên macOS riêng.
- `protocol/websocket_protocol.md`: định dạng phiên, lệnh và telemetry.
- `docs/wiring.md`: nối dây, bảng motor và các thông số chưa được xác minh.
- `docs/architecture.md`: quy ước trục, tác vụ và phạm vi tính năng.
- `docs/calibration.md`: hiệu chuẩn có kiểm tra chất lượng.
- `docs/safety.md`: khóa đầu ra, failsafe và điều kiện nghiệm thu.
- `docs/testing.md`: quy trình test phần mềm/phần cứng.
- `docs/validation.md`: bằng chứng build/test hiện có và giới hạn.

Cần đọc wiring, calibration và safety trước khi cấp nguồn ESC. Không commit mật khẩu AP hay token thật. Sao lưu cấu hình máy cục bộ; kiểm tra `.gitignore` trước khi thêm file.

## Phạm vi nghiệm thu

Mock mode cho phép kiểm tra state machine, sensor fusion, PID, mixer và giao tiếp mà không quay motor. Chuyển sang ESC thật chỉ sau khi đã ghi đủ bằng chứng bench: chuẩn PWM/điện áp, mức xung, nguồn cấp/tiếp địa, map và chiều quay từng motor, chiều phản hồi PID, pin/shunt, I2C dưới tải, timeout lệnh và deadline thực tế. Không có thao tác trong ứng dụng biến một máy chưa nghiệm thu thành máy đủ điều kiện bay.

PID chỉnh qua ứng dụng hiện chỉ áp dụng trong RAM và trở về mặc định sau reboot. Lệnh `calibrate/save` lưu bộ hiệu chuẩn cảm biến; không lưu PID hoặc áp suất tham chiếu barometer.

Xem `docs/phase2.md` cho các interface và điều kiện kỹ thuật của các chế độ điều hướng tương lai. Chưa có tự hạ cánh hoặc RTH để xử lý mất liên kết; chính sách thử bench không được xem là chính sách xử lý drone trên không.

## Môi trường cloud đã chuẩn bị

Toolchain và SDK nằm tại `/workspace/tooling`. Kích hoạt PlatformIO bằng `export PLATFORMIO_CORE_DIR=/workspace/tooling/platformio` và `export PATH=/workspace/tooling/pio-venv/bin:$PATH`; kích hoạt Flutter/Android bằng `source scripts/flutter_env.sh`. `scripts/setup_toolchain.sh` dùng nguồn phân phối chính thức có kiểm tra checksum khi registry PlatformIO bị giới hạn tải. `scripts/setup_flutter.sh` tái tạo Flutter/JDK/Android SDK; `scripts/build_android.sh` tạo APK debug.

Mã nguồn được quản lý trong repository `nhanhlen1/Drone-ESP32`. Gói thử nghiệm mang tag `v1.0.0-test`; xem `docs/release.md` để biết nội dung và giới hạn. Bản nháp cấu hình môi trường chứa script cài đặt và hướng dẫn khởi động; cần lưu/xuất bản trong cài đặt môi trường để tạo snapshot. Việc build hiện tại không chứng minh một task mới đã khôi phục snapshot thành công.
