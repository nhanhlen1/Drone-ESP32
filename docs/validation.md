# Báo cáo xác thực

Trạng thái ban đầu: chưa có thiết bị ESP32, ESC, cảm biến hoặc điện thoại được kết nối với phiên cloud để kiểm thử thực. Không có bằng chứng bay hoặc quay động cơ thật.

| Kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| Native algorithms | PASS | `./scripts/test_native.sh`: 12 nhóm, 18.026 assertions; AddressSanitizer + UndefinedBehaviorSanitizer, exit 0 |
| WebSocket validator | PASS | `./scripts/test_network.sh`: 45 assertions; module Network thật/ArduinoJson thật, transport HAL giả, exit 0 |
| Driver/calibration host | PASS | `./scripts/test_sensors.sh`: 4.063 assertions; SensorManager + CalibrationStore thật, HAL giả, exit 0 |
| GPS parser/manager host | PASS | `./scripts/test_gps.sh`: 52 assertions; TinyGPSPlus thật, UART giả, exit 0 |
| PlatformIO ESP32 build | PASS | Lệnh PlatformIO chuẩn, espressif32 6.9.0 / Arduino 2.0.17; RAM 38.656/327.680 bytes, flash 496.045/1.310.720 bytes; firmware.bin được tạo, exit 0 |
| ESC mock/interlock host | PASS | `scripts/test_esc.sh`: boot không PWM, slew/cap/cutoff, dữ liệu lỗi, compile-time từ chối cấu hình motor thật chưa xác minh |
| Flutter tests | PASS | `flutter test`: 9 test được chạy và qua theo runner; không thay thao tác trên điện thoại |
| Flutter analyze | PASS | `flutter analyze`: No issues found |
| Android APK debug | PASS | `scripts/build_android.sh`, Flutter 3.35.7 / Temurin JDK 21 / Android SDK 36; `mobile_app/build/app/outputs/flutter-apk/app-debug.apk` được tạo; ký debug để kiểm thử |
| I2C/UART/AHRS trên thiết bị | NOT RUN | Không có thiết bị trong cloud |
| PWM/ESC/motor mapping | NOT RUN | Cần thiết bị đo, nguồn và rig không cánh |
| Wi-Fi với ESP32/điện thoại thật | NOT RUN | Cần nạp firmware và kết nối AP |
| Bay/altitude hold/RTH/auto land | NOT RUN / chưa triển khai | Ngoài phạm vi nghiệm thu bench |

Các kết quả PASS trên là kết quả runner thực thi trong phiên phát triển; HAL giả không phải thiết bị thật. Những dòng còn chờ không được suy thành PASS. Cấu hình phần cứng còn chưa xác minh trong `wiring.md` tiếp tục chặn việc xem đây là hệ thống đủ điều kiện bay.

Firmware mặc định chưa chứa mật khẩu/token AP: cần tạo `firmware/include/local_secrets.h` cục bộ rồi build lại để mở AP. Bản APK hiện tại là universal debug; chưa ký phát hành production. Không tìm thấy cổng `/dev/ttyUSB*` hoặc `/dev/ttyACM*` trong lần kiểm tra cuối.
