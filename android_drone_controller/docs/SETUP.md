# Môi trường phát triển đã chuẩn bị

Kho ứng dụng mới: `/workspace/Drone-ESP32/android_drone_controller`. Không dùng worktree; task cloud đã có checkout cô lập. Không cần build/nạp firmware prototype hoặc cài Flutter để phát triển app Kotlin này.

## Android trên cloud

Toolchain được giữ bên ngoài checkout trong `/workspace/tooling`: JDK Temurin 21.0.9+10, Android SDK 36, Build Tools 35.0.0, Gradle 8.12; AGP 8.9.1 và Kotlin 2.1.0 theo cấu hình app. Helper cài đặt/kích hoạt được lưu trong `/workspace/tooling/onboarding/`. Xem báo cáo validation để biết kết quả chạy thực tế; tệp có mặt không tự chứng minh build thành công.

```bash
source /workspace/tooling/onboarding/android-env.sh
cd /workspace/Drone-ESP32/android_drone_controller
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

SDK path cục bộ được đọc từ `ANDROID_HOME`, không đóng gói đường dẫn máy riêng trong source để mở bằng Android Studio trên Windows. Java dùng proxy/truststore được hỗ trợ của môi trường; không bỏ TLS, chữ ký hay checksum để tải dependency. Toolchain helper giữ cache ngoài source. Không cần secret cho runtime ESP32/MAVLink này.

Chạy Android instrumentation cần thiết bị/emulator sẵn sàng; `adb devices` rỗng nghĩa là NOT VERIFIED, không phải PASS. Cloud không có đường Wi-Fi vật lý tới AP ESP32 chỉ vì cùng có địa chỉ mặc định 192.168.4.1; không tự kết nối hay thử RC trên địa chỉ này từ máy cloud.

## ArduPilot tham chiếu cho SITL

`integration/setup_sitl.sh` cài dependency Python đã pin vào `/workspace/tooling/mavlink-venv`, lấy source upstream đúng commit và submodule đúng revision, cấu hình SITL và build bằng sáu job. Cài source native không đòi Docker, cross-compiler, ESP-IDF, NDK hoặc USB.

```bash
./integration/setup_sitl.sh
DRONE_SITL_BINARY=/workspace/tooling/ardupilot-sitl/build/sitl/bin/arducopter \
DRONE_SITL_EVIDENCE_DIR=/workspace/Drone-ESP32/android_drone_controller/reports/sitl \
  ./gradlew --no-daemon testDebugUnitTest --tests '*SitlIntegrationTest'
```

Helper dùng cache cấu hình đúng board/flags để tránh cấu hình lại khiến Waf build lại toàn bộ. Nó vẫn chạy Waf để kiểm tra dependency và cập nhật output cần thiết. Revision không khớp sẽ dừng để giữ source hiện có; không reset/xóa work ngoài ý muốn.

Mỗi test tự khởi động và tự dừng SITL; không có service phải tự chạy ở startup. Tham số và đường ghi log thuộc thư mục riêng của từng process. Không tái sử dụng state EEPROM từ phiên mô phỏng trước. SITL 4.6.3 là tham chiếu; chưa thay thế kiểm định firmware ESP32 revision afeb9c49.

## Các đường mạng cần cho cài đặt

Nguồn GitHub chính thức và submodule; PyPI/files.pythonhosted.org; Gradle/services.gradle.org/downloads.gradle.org; Maven/repo.maven.apache.org/repo1.maven.org; Google/dl.google.com. Binary SITL host firmware.ardupilot.org từng bị policy 403 nên đã build từ source GitHub thay vì nới mạng hoặc bỏ xác minh.

App chạy trực tiếp giữa Android và ESP32 không cần Internet hoặc server bên ngoài. Mật khẩu Wi-Fi thực tế chưa xác minh từ source ArduPilot; người dùng cấu hình mạng trên Android, không hardcode mật khẩu giả.

Cấu hình draft, runtime đang chạy và bản môi trường publish là ba trạng thái riêng. Việc cài/build trong instance hiện tại không chứng minh task mới đã restore snapshot; chỉ ghi nhận restore khi được kiểm thử độc lập.

## SITL đúng upstream base afeb9c49

Đã chuẩn bị thêm checkout riêng `/workspace/tooling/ardupilot-target-sitl` tại full commit `afeb9c4941543d97f3e04fecf257b8f598f49a6a`. Helper `/workspace/tooling/onboarding/install-target-sitl.sh` tái tạo dependency/submodule và build sáu job, dùng lại cache hợp lệ. Không ghi đè source 4.6.3 và không đưa source SITL vào ứng dụng Android.

```bash
source /workspace/tooling/onboarding/android-env.sh
cd /workspace/Drone-ESP32/android_drone_controller
DRONE_SITL_BINARY=/workspace/tooling/ardupilot-target-sitl/build/sitl/bin/arducopter \
DRONE_SITL_PARAM_PROFILE=modern \
DRONE_SITL_EVIDENCE_DIR=/workspace/Drone-ESP32/android_drone_controller/reports/sitl-target \
  ./gradlew --no-daemon testDebugUnitTest --tests '*SitlIntegrationTest'
```

Gradle Test khai báo binary/profile làm input, kể cả file binary, để đổi phiên bản SITL không bị nhầm với kết quả UP-TO-DATE của binary khác. Profile modern dùng tên MAV_SYSID/MAV_GCS_SYSID; profile mặc định dùng tên legacy tương ứng 4.6.3. Sender GCS là 255, còn target được khám phá từ HEARTBEAT.

SHA256 executable đã dùng trong các phép đo tại instance này:

| Upstream source | SHA256 binary |
|---|---|
| Copter-4.6.3, `92b0cd788ec29406f26c6f9c31d5ceedbd1cc538` | `6c8ed51ba7346216b83d758532e2b3558e67660f92fd62aae1ac455466ee9c26` |
| 4.8.0-dev base, `afeb9c4941543d97f3e04fecf257b8f598f49a6a` | `41fd63749fa164c226a23ee395e683064553660f9b202581e94ba952ced6c9bf` |

Đây là checksum để nhận diện executable cục bộ được build và kiểm thử, không phải checksum phát hành của ArduPilot. Build lại bằng compiler khác có thể cho binary khác. Upstream base cùng revision không bao gồm mọi bản sửa ESP32/driver/hwdef riêng đang nạp trên drone; kiểm định phần cứng vẫn cần đúng artifact đang dùng và log thực.

Bản sao cùng nội dung của helper base afeb9c49 được đóng gói trong source tại `integration/setup_target_sitl.sh`, để không phụ thuộc tệp helper ngoài project khi cần tái tạo SITL. Trên Linux có thể chạy `./integration/setup_target_sitl.sh`; `DRONE_TOOLING_ROOT` chọn vị trí cache và mặc định là `/workspace/tooling`.
