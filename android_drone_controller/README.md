# ESP32 Drone Controller 0.3.0 — Android Kotlin

Ứng dụng chính thức của dự án `nhanhlen1/Drone-ESP32`, tiếp tục từ checkpoint `0.2.1-protocol`; package `dev.drone.controller`. Theo yêu cầu ngày 29/10/2026, bản này phát triển ứng dụng Codex hiện có. Các báo cáo 0.2.x được giữ làm lịch sử. Đặc tả hiện hành: [yêu cầu 0.3.0](docs/USER_SPEC_LIVE_CONTROL_20261029.md).

Bản thử chuẩn bị kiểm tra trên ESP32: **phải tháo cánh**. Kết quả phần mềm, SITL, emulator và phần cứng được phân biệt trong [báo cáo 0.3.0](reports/20261029-live/VALIDATION.md). **FLIGHT READY NOT VERIFIED**. Không có thay đổi firmware hoặc tham số FC thật trong công việc này.

## Chức năng và đường điều khiển

- Hai joystick Mode 2 đa điểm: trái ga giữ vị trí/yaw tự hồi tâm; phải roll/pitch tự hồi tâm. Giao diện ngang, HUD và bảy trang tiếng Việt.
- Đường chạy thực: joystick → ViewModel → ControlAuthority → scheduler một actor TCP → MAVLink2 RC_CHANNELS_OVERRIDE → FC. Mục tiêu 20 Hz; không xếp hàng hoặc chạy bù những đầu vào đã cũ.
- Ba profile riêng: chỉ đọc, thử bốn kênh khi DISARMED và ESC cách ly, điều khiển bốn kênh có xác nhận. Profile điều khiển hợp lệ tiếp tục RC khi FC chuyển ARMED; profile bench bị thu hồi khi ARMED. Phép chẩn đoán CH1 cũ vẫn có trang riêng.
- ARM/DISARM thường bằng COMMAND_LONG 400, không force; xác nhận chủ động, kiểm tra PreArm/EKF/tiếp đất/RC mới. Ghi socket, COMMAND_ACK và HEARTBEAT xác nhận được hiển thị riêng. Không ARM tự động hoặc tự thử lại.
- Chọn STABILIZE và các mã chế độ ArduCopter trong danh mục; FC có quyền từ chối chế độ không hỗ trợ hoặc chưa đủ điều kiện. Kết quả ACK và HEARTBEAT mới được kiểm tra riêng.
- TCP `192.168.4.1:5760`, chọn Wi-Fi Android không cần Internet; GCS heartbeat 1 Hz, yêu cầu telemetry/tham số chỉ đọc hữu hạn. Metadata 301 message, parser CRC/fragment/sequence có kiểm thử; signed frame bị từ chối khi chưa có xác minh chữ ký.
- Attitude, GPS, pin, độ cao, vận tốc, RC input, servo output, EKF, PreArm, STATUSTEXT và tuổi mẫu. INA219 không có dữ liệu hợp lệ được báo thiếu; không tạo dòng điện, phần trăm pin hoặc thời gian bay giả.
- STOP, stale input/heartbeat/RC echo, mất focus/Wi-Fi, lỗi parser hoặc session đều thu hồi quyền. Không tự reconnect, ARM hoặc phát lại ga cũ. STOP là dừng phát RC, không phải lệnh ngừng động cơ.
- CSV nội bộ có quyền điều khiển, kênh yêu cầu/TX/RC echo, lệnh/ACK/HEARTBEAT, epoch, timing và lỗi. Queue và dung lượng hữu hạn, có bộ đếm dropped/error; đây là log trạng thái và sự kiện, không phải bản sao đầy đủ mọi byte TCP.

## Cài đặt và sử dụng

Xem [hướng dẫn tiếng Việt](README_VI.md) và [quy trình phần cứng](docs/HARDWARE_AND_FAILSAFE.md). Đóng ứng dụng/tay điều khiển khác trước khi kết nối, để một nguồn duy nhất phát RC. Không tự tắt PreArm, EKF hoặc failsafe để mở khóa.

## Build và kiểm thử

Mở thư mục này bằng Android Studio; JDK 17/21, SDK 36, Build Tools 35.0.0, Gradle wrapper 8.12, AGP 8.9.1, Kotlin 2.1.0; minSdk 26. Trong cloud:

```bash
source /workspace/tooling/onboarding/android-env.sh
export DRONE_SITL_BINARY=/workspace/tooling/ardupilot-target-sitl/build/sitl/bin/arducopter
export DRONE_SITL_PARAM_PROFILE=modern
bash scripts/verify.sh clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 -m unittest discover -s integration -p 'test_*.py' -v
```

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

SITL và bài emulator kết nối SITL cần binary/môi trường riêng. Các test chưa thực thi phải được báo BLOCKED/SKIPPED, không tính PASS. `assembleDebugAndroidTest` chỉ build APK test. Xem [integration](docs/INTEGRATION.md) và [báo cáo mới](reports/20261029-live/VALIDATION.md) để biết lệnh và kết quả thật.

## Artifact và rollback

Sau khi kiểm tra build/test, chạy `python3 scripts/package.py`. Thư mục `dist/ESP32_DRONE_ANDROID_APP_0.3.0-rc-control/` chứa APK debug, ZIP source, tài liệu, báo cáo và `SHA256SUMS.json`; ZIP gói đầy đủ nằm cạnh thư mục này. Không chứa local.properties/cache/firmware hoặc khóa ký production.

Nhánh `codex/controller-live-20261029` giữ checkpoint WIP `783da6b` trước khi sửa build; checkpoint 0.2.1 `7e0b589` và nhánh qualification cũ được giữ. Không reset hay ghi đè ứng dụng cũ. APK mới cập nhật package Codex hiện có; cần xuất log trước khi gỡ/cài lùi vì gỡ ứng dụng xóa dữ liệu nội bộ. Không có thay đổi firmware/tham số FC cần rollback.
