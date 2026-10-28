# Kiểm thử Android UI — 29/10/2026

**Ứng viên hiện có `dev.drone.controller`: 6/6 instrumentation test PASS trên Android emulator API26 x86.** Đây không phải kiểm thử app Google AI Studio chưa đọc được source/APK. Bốn test Robolectric host-framework API28 cũng PASS trong pipeline182test của root; chúng không được gọi là kiểm thử thiết bị.

## Kết quả thực thi

Lệnh chạy thực tế sau khi cài đúng APK từ pipeline hiện tại:

```bash
source /workspace/tooling/onboarding/android-env.sh
adb -s emulator-5556 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r dev.drone.controller.test/androidx.test.runner.AndroidJUnitRunner
```

Runner ghi `OK (6 tests)`, thời gian43.229giây và `INSTRUMENTATION_CODE: -1` (RESULT_OK). Đã kiểm đủ6status kết thúc PASS, không chỉ dựa vào exit code của lệnhADB. Danh sách case và hash: [instrumentation-summary.json](android-ui-20261029/instrumentation-summary.json). Log đầy đủ: [instrumentation](android-ui-20261029/android-api26-final-instrumentation.txt) và [cài APK](android-ui-20261029/android-api26-final-install.txt).

Ba case Activity kiểm tra recreation, background/foreground và permission. Ba case tương tác thực thi Android MotionEvent với hai pointerID độc lập, kiểm tra mapping4kênh xem trước, hồi tâm/giữga khi nhấc tay, touch cancel/STOP reset và chuyển landscape. Các case xác nhận không kết nối, không có quyền RC, TXoverride0. Không gửi RC hoặc ARM/MotorTest đến ESP32. Lượt baseline3case cũ PASS18.423giây được giữ riêng, không cộng thành9case duy nhất.

APK SHA256: `20bde5c9d3e1793d5c53f0eb4b6a22fdfb3b9fb39b04ff635da8585b5308974f`.

APK test SHA256: `ae140a73643da85c3ed6db0d1fd2b232ac0c3f81d5a78c9f9f4de2c7b7ea6d05`.

## Giới hạn môi trường và hình ảnh

Máy cloud không có `/dev/kvm`; emulator37.2.12 build16428233 dùng software TCG. Đã cài image chính thức qua sdkmanager, giữ TLS/checksum củaSDKmanager. Metadata package/version ở [toolchain-metadata.json](android-ui-20261029/toolchain-metadata.json).

API35 x86_64 đã boot rất chậm và framework có `DeadSystemException`, BluetoothSIGABRT/Watchdog, package service biến mất trước khi cài được APK. Vì vậy **API35 instrumentation NOT RUN/BLOCKED**, không gắn lỗi này thành crash của ứng dụng. API35 đã được dừng; log crash/framework giữ riêng trong thư mục bằng chứng.

API26 đã boot và cài/chạy đủ6test đạt. Tuy nhiên khi chụp giao diện thủ công sau test, Android hiển thị **“System UI isn't responding”**. Ảnh [system-ui-anr.png](android-ui-20261029/system-ui-anr.png) được giữ nguyên, không chỉnh sửa/crop hoặc gọi là ảnh UI sạch. **Visual UI review: PARTIAL/BLOCKED bởi emulator System UI ANR.** Không suy6test chức năng thành bảo đảm không ANR/crash trong mọi điều kiện; chưa có phép đo hiệu năng trên điện thoại thật.

Không có ảnh chụp sạch xác minh toàn bộ7tab. Không có phép thử Wi-Fi ESP32 thật, màn hình/cảm ứng điện thoại thật hoặc nguồn động lực. Hardware RC input và flight readiness vẫn NOT VERIFIED.

## Khởi động lại AVD đã chuẩn bị

Các tệp SDK, image và AVD nằm ngoài checkout tại `/workspace/tooling`. Process emulator không được giả định sống qua snapshot/task. Lệnh đã dùng để chạy API26:

```bash
source /workspace/tooling/onboarding/android-env.sh
export ANDROID_AVD_HOME=/workspace/tooling/android-user/avd
"$ANDROID_HOME/emulator/emulator" -avd Drone_API26 -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot -accel off -gpu swiftshader -cores 2 -memory 1536 -camera-back none -camera-front none -netdelay none -netspeed full
```

Trước test, xác minh `adb -s emulator-5556 shell getprop sys.boot_completed` trả1 và `adb -s emulator-5556 shell service check package` báo found; tiếp tục kiểm tra framework/log nếu service khởi động lại. Hai tín hiệu readiness một lần không bảo đảm framework khỏe như sự cố API35 đã chứng minh. Luôn chỉ định serial emulator, không chọn nhầm thiết bị vật lý. Cài lại APK đúng hash trước mỗi lượt kiểm định. SDK/image có thể tái tạo bằng `sdkmanager 'emulator' 'system-images;android-26;default;x86'` và avdmanager; khôi phục AVD trong task mới chưa được thử.

AVDAPI26 có thể vẫn đang chạy khi báo cáo được ghi; root có thể dừng process do phiên này khởi tạo bằng `adb -s emulator-5556 emu kill`. Không killADBserver hoặc các thiết bị khác.
