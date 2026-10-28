> **Cập nhật29/10/2026:** Đây là candidate Codex `dev.drone.controller`, chưa phải bản tiếp nối source Google AI Studio `com.aistudio.esp32drone.krvwpx`. ZIP Google AI Studio chưa chuyển vào workspace được do giới hạn32MiB. Phần protocol/qualification/test đang chuẩn bị để tái sử dụng; xem [kiểm kê nguồn](reports/00_SOURCE_INVENTORY.md) và [báo cáo mới](reports/20261029/VALIDATION.md). Không gọi đây là app hoàn tất theo master prompt mới.

# ESP32 Drone Controller — Android Kotlin

Ứng dụng mới, package `dev.drone.controller`, cài song song `dev.drone.rcbench`. Không sửa app cũ hoặc firmware. Bản `0.2.1-protocol` phục vụ telemetry, chẩn đoán và kiểm định đầu vào RC; **chưa được nghiệm thu trên điện thoại/ESP32 thật, không dùng để ARM, chạy động cơ hoặc bay**.

## Phạm vi thực hiện

- Android Native Kotlin, ViewModel/StateFlow; bảy trang tiếng Việt, theme tối, icon và splash.
- TCP Wi-Fi không cần Internet; chọn mạng Wi-Fi qua Android Network binding, connect timeout, I/O nonblocking một actor, partial read/write, parser MAVLink2 có CRC/CRC_EXTRA và buffer hữu hạn.
- HEARTBEAT, SYS_STATUS, ATTITUDE, GPS, GLOBAL_POSITION, SCALED_PRESSURE, RC_CHANNELS, BATTERY_STATUS, SERVO_OUTPUT_RAW, PARAM_VALUE, COMMAND_ACK, AUTOPILOT_VERSION, EKF và STATUSTEXT. Dữ liệu thiếu/quá hạn hiển thị rõ.
- Một đợt yêu cầu stream/tham số chỉ đọc mỗi kết nối, ACK/timeout hữu hạn; không `PARAM_SET`, motor test, thay mode hoặc ARM.
- Hai joystick Mode 2 độc lập **xem trước ngoại tuyến**. Chỉ trang KIỂM TRA RC có đường phát thật: CH1 quanh trim tối đa ±20 µs, tối đa 750 ms, HEARTBEAT DISARMED mới, calibration đã đối chiếu, ba xác nhận vật lý/người vận hành và deadman độc lập; trường hợp CRITICAL có điều kiện chẩn đoán bổ sung và xác nhận thứ tư riêng. Các kênh khác bỏ qua.
- STOP, mất focus, ra nền, mất Wi-Fi, CRC lỗi, ARMED, HEARTBEAT quá hạn và reconnect thu hồi quyền. Không tự nối lại hoặc khôi phục ga cũ. Phản hồi RC khớp không tự mở khóa cấp vận hành cao hơn.
- CSV nội bộ, xuất qua Android Documents; không gửi telemetry ra máy chủ ngoài.

Những phần còn khóa/chưa thực hiện và bằng chứng đo được nằm trong [báo cáo kiểm định](reports/VALIDATION.md). Không xem sự có mặt của giao diện hoặc APK là nghiệm thu tính năng bay.

## Build và kiểm thử

Mở **thư mục này** trong Android Studio. Dùng JDK 17 hoặc JDK 21 đã kiểm chứng, SDK Platform 36, Build Tools 35.0.0. Gradle wrapper 8.12 có SHA-256, AGP 8.9.1, Kotlin 2.1.0, Android tối thiểu API 26. Không cần Flutter hoặc nạp firmware.

```bash
# Trong môi trường cloud đã chuẩn bị:
source /workspace/tooling/onboarding/android-env.sh
cd /workspace/Drone-ESP32/android_drone_controller
bash scripts/verify.sh
python3 -m unittest discover -s integration -p 'test_*.py' -v
```

Windows PowerShell trong project:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

`assembleDebugAndroidTest` chỉ biên dịch APK test. Chạy instrumentation trên điện thoại đã cho phép USB debugging bằng `gradlew.bat connectedDebugAndroidTest`; lệnh này chưa được chạy khi không có thiết bị. Test SITL yêu cầu binary riêng, xem [integration](docs/INTEGRATION.md). Test bỏ qua không được tính PASS.

## Kết nối và sử dụng

1. Chọn Wi-Fi `ESP32_DRONE` trong cài đặt điện thoại; giữ kết nối khi Android báo không có Internet. Mật khẩu nhập qua cài đặt Wi-Fi, không hardcode trong app.
2. Mở KẾT NỐI, nhập `192.168.4.1:5760`, bấm kết nối. App chỉ dùng Wi-Fi, không dùng dữ liệu di động cho socket này. Người vận hành xác minh đã chọn đúng AP; unsigned MAVLink không xác thực danh tính thiết bị.
3. Quan sát SYS/COMP khám phá từ HEARTBEAT và DISARMED. TCP connected chưa phải đủ điều kiện điều khiển. Xem telemetry, tuổi mẫu, STATUSTEXT, tham số và yêu cầu stream trước.
4. TAY CẦN chỉ xem trước mapping. ARM/DISARM và thay mode vẫn khóa. RC1/2/3/4 tương ứng roll/pitch/ga/yaw theo cấu hình đã cung cấp; chưa tự suy ra mapping cho firmware khác.
5. Với bench thật: chỉ thực hiện sau mô phỏng đạt và có phép riêng. Xem bài đo cụ thể trong [quy trình phần cứng](docs/HARDWARE_AND_FAILSAFE.md). Không phát RC trong bài đo telemetry dài.

Đọc [kiểm toán hệ thống](docs/SYSTEM_AUDIT.md), [đối chiếu firmware](docs/FIRMWARE_COMPARISON.md), [MAVLink](docs/MAVLINK.md), [kiến trúc/RC/tham số](docs/ARCHITECTURE_RC.md).

## Lấy log

Chọn **XUẤT CSV** ở cấu hình/chẩn đoán rồi chọn nơi lưu trên Android. Thao tác xuất thu hồi quyền RC. Queue ghi log hữu hạn; xem dropped/error để biết log có thiếu hay không. File chứa dữ liệu thiết bị, giữ riêng khi chia sẻ.

Với APK debug:

```powershell
adb shell run-as dev.drone.controller ls files/logs
adb exec-out run-as dev.drone.controller cat files/logs/TEN_FILE.csv > controller.csv
adb logcat -d > android-logcat.txt
```

Đổi `TEN_FILE.csv` thành tên thật. Chương trình `integration/telemetry_reliability.py` dùng Python chuẩn, chạy ngoại tuyến trên Windows, lưu raw MAVLink và JSON, **không gửi byte nào**. Dùng `--help` để xem lệnh; kiểm tra stream đã phát ở firmware trước vì recorder không yêu cầu stream.

## Đóng gói và quay lại bản cũ

Sau build/test: `python3 scripts/package.py`. Bản giao nằm trong `dist/ESP32_DRONE_ANDROID_APP_<versionName>/` gồm APK debug, ZIP source, tài liệu, báo cáo và hash. Không chứa local.properties, cache, credential, binary firmware hoặc khóa ký production.

Ứng dụng cài cạnh bản cũ nên rollback bằng cách đóng/ngắt kết nối app mới, rồi dùng lại app cũ. Gỡ app mới bằng `adb uninstall dev.drone.controller` nếu cần; thao tác này xóa log của app mới, hãy xuất log trước. Không có thay đổi tham số/firmware cần rollback. Không chạy hai ứng dụng cùng tranh quyền RC. Bộ firmware đính kèm chỉ được phân tích đọc; không dùng merged BIN để rollback một cách tùy tiện vì vùng NVS có thể bị ghi đè.

Cloud hiện tại không phải WSL và không có Desktop Windows được mount; bản giao được lưu trong workspace. Không có upload, release GitHub, commit hoặc push từ xa.
