# Kiểm kê nguồn trước triển khai đặc tả29/10/2026

## Kết luận về base bắt buộc

**Chưa truy cập được source Google AI Studio bắt buộc.** Không tìm thấy ZIP `esp32-drone-controller-oct-28-2026-7-45-49-pm(1).zip`, ZIP cùng tên không có`(1)`, hoặc các lớp `com/example/DroneViewModel.kt`, `MavlinkFrameParser.kt`, `SafetyState.kt`, `MainControlScreen.kt`, `VirtualJoystick.kt` trong các vị trí đã kiểm tra. Không gán nhãn project Codex có sẵn là app Google AI Studio.

Tệp đính kèm ZIP trước đó `file_00000000a92c8211b240f12554cc1cc3` và bản gửi lại `file_00000000cf1c8211b710d91c827b5183` đã trả lỗi chuyển tệp: `file exceeds the executor transfer limit of 32 MiB`. ZIP chưa có bản local, nên **không tính được SHA-256 của ZIP/APK Google AI Studio**, không thể xác nhận source/APK cùng phiên bản, không thể đọc tài liệu/đặc tả lồng ZIP. Giới hạn này không phải lỗi quyền GitHub và không cần thêm token.

## Các vị trí đã tìm

- Duyệt tên tệp/directory không đi theo symlink trong `/workspace`, `/tmp`, `/home`, `/mnt`, `/media`; loại trừ `.git`, `.gradle`, `.codex`, `.aws`, `.ssh`, `node_modules`, `.cache` và thư mục hệ thống. Không đọc credential files.
- `/home/drone/drone-project`, Downloads/Desktop/Windows mount không hiện diện trong kết quả cây khả dụng. `/home` không có dự án Android của người dùng; `/mnt` và `/media` không có mount Windows chứa source yêu cầu.
- Một đường dẫn không đọc được là `/tmp/codex-daemon-1000` (nội bộ runtime), không cố vượt quyền để tìm source.
- `/workspace/attachments` chỉ có master prompt mới, đặc tả trước và các file firmware đã gửi; không có ZIP Google AI Studio cục bộ.
- Chỉ thấy ZIP và APK của project Codex đã làm trong phiên trước, cùng APK RC Bench cũ; danh tính ở bảng dưới.

Lệnh kiểm tra bổ sung: `git ls-remote origin`, sau đó fetch read-only các branch/tag công khai vào remote refs và dùng `git ls-tree -r --name-only <ref>`. Không checkout/reset/push, không thay file làm việc. Kết nối Git mặc định trong sandbox không tới được proxy; cùng thao tác read-only chạy qua `require_escalated` thành công với xác thực nền tảng sẵn có.

## Repository đang có

Checkout `/workspace/Drone-ESP32`, origin GitHub `nhanhlen1/Drone-ESP32`. Trước tác vụ mới, HEAD (nhánh local work, cùng commit origin/main): `ba634f4ea796fcbc9e776f86c55bc8d1ae529190`; source tracked gồm `android_rc_bench`, `mobile_app`, `firmware` prototype và docs/scripts. `android_drone_controller/` là candidate Codex từ phiên trước, khi kiểm kê còn untracked; không phải source Google AI Studio được yêu cầu.

Đã kiểm tra mọi branch/tag do `ls-remote` trả về; không có đường dẫn `com/example`, `aistudio`, tên ZIP hoặc năm lớp định danh ở trên:

| Ref | Commit được đọc | Số file trong tree |
|---|---|---:|
| main | ba634f4ea796fcbc9e776f86c55bc8d1ae529190 |158|
| release-status/v1.0.0-test | f605a5efbf56789816d45f01978223c519df3cbc |112|
| release-status/v1.0.1-test | e91ed6213da2a2c8be23f1718de2807b8ce2d7a2 |116|
| rc-bench-v0.1.1-test | ea778e08349706d13ca59114d419f449c62cf0e8 |158|
| rc-bench-v0.1.0-test |17c570c3bb2115a0d40bf6943832e28245d1ef18|155|
| v1.0.0-test |0da34685b3b1a921f4c560e58685918a108962f6|109|
| v1.0.1-test |268b672181923547f60081b97a840b79852a5e87|114|

Các checkout `tooling/ardupilot-sitl` và `tooling/ardupilot-target-sitl` là dependency/build cache của upstream SITL, không phải source app Google AI Studio hoặc repository dự án bổ sung.

## Danh tính artifact trước sửa

Chi tiết máy đọc được ở `00_SOURCE_BASELINE_HASHES.json`. Hash sau thuộc bản Codex, **không thuộc ZIP Google AI Studio**:

| Artifact | Kích thước | SHA-256 |
|---|---:|---|
| app/build/outputs/apk/debug/app-debug.apk |2.412.500|d3917857b64283f1ac61b037b5d3d248cd66179c089a253fb9fbec0a5e6f684f|
| dist/ESP32_DRONE_ANDROID_APP.zip |3.585.615|f17dfc3d102d8621038b2eae793d149d6db5171b714ccec29b19f991665dd143|
| dist/ESP32_DRONE_ANDROID_APP/android_drone_controller-source.zip |768.495|fe49685c443fbde6ce503c543c54fad78bce13625bbe3c3083e295364e8a015f|
| CODEX_MASTER_PROMPT_ESP32_DRONE_ANDROID_20261029.md |31.063|d55cd4d043313bbfad6e8fa0a22aa695ed4f2a682afedd0222354d10af770721|

Candidate Codex: package `dev.drone.controller`, versionName `0.2.0-bench`, versionCode1, minSdk26/targetSdk36. Base Google AI Studio theo đặc tả là `com.aistudio.esp32drone.krvwpx`; metadata này chưa được kiểm tra từ APK Google AI. Không có căn cứ coi APK Codex là bản cập nhật cùng package.

Toolchain sẵn có của candidate: Gradle8.12 có SHA-256 wrapper, AGP8.9.1, Kotlin2.1.0, JDK21.0.9, JVM target17, SDK36/BuildTools35. Wrapper `gradlew`, `gradlew.bat`, JAR tồn tại trong candidate Codex. Tình trạng thiếu wrapper của ZIP Google AI vẫn **UNVERIFIED** do ZIP chưa đọc được.

## Hướng tiến hành không đánh tráo nguồn

Có thể tiếp tục các thư viện giao thức, kiểm thử chuẩn và công cụ SITL độc lập trên candidate hiện có để tái sử dụng. Không được báo đã sửa/migrate source Google AI Studio, bảo toàn cùng UI/applicationId, hoặc đối chiếu đường gửiCH1 của app đó khi chưa đọc được source.

Blocker tối thiểu cho bước tiếp tục đúng base: cung cấp ZIP source dưới32MiB (bỏ APK/gói lồng/build/.gradle/node_modules), các file tạo/gửi MAVLink+ViewModel+Gradle, hoặc đưa source vào repo/đường dẫn workspace đã cấp quyền. Không cần gửi lại bộ firmware hoặc mật khẩu trong chat. Chưa yêu cầu thêm thông tin phần cứng để làm các bước host độc lập.
