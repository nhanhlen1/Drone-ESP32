# Báo cáo xác thực RC Bench

Đã build/test source Kotlin hiện tại bằng `./scripts/build_rc_bench.sh testDebugUnitTest lintDebug assembleDebug` từ gốc kho. Gradle báo **BUILD SUCCESSFUL** trong 29 giây. XML kết quả của lần chạy bắt đầu ngày 28/10/2026 lúc 14:54:55 (UTC+7) xác nhận 47 test, 0 thất bại, 0 lỗi, 0 skipped. Không kế thừa kết quả Flutter, firmware ESP32 hoặc C++ từ dự án khác.

| Kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| BenchEngine JUnit | PASS | 23 test; source có receiverless count0, yaw cap, heartbeat/RC/deadman/feedback gate |
| MAVLink JUnit | PASS | 19 test; parser/encoder và golden frame sinh độc lập bằng pymavlink, có SYS_STATUS 43 byte |
| BenchSession TCP loopback | PASS | 3 test với TCP socket loopback thật trên host; chưa phải Wi-Fi/flight controller thật |
| SessionLogger | PASS | 2 test; CSV và xử lý lỗi ghi log |
| Gradle testDebugUnitTest | PASS | Tổng 47 test; 0 failures/errors/skipped theo XML `app/build/test-results/testDebugUnitTest/` |
| Android lintDebug | PASS với cảnh báo | 0 error, 2 warning không chặn build; chi tiết dưới |
| assembleDebug / APK | PASS | `app/build/outputs/apk/debug/app-debug.apk`, khoảng 908 KiB; output metadata xác nhận package/version |
| TCP/MAVLink với ArduPilot thật | NOT RUN | Không có thiết bị được nối vào phiên cloud |
| RC_CHANNELS so với override thật | NOT RUN | Cần bench inhibit/không cánh/ESC không nguồn |
| Deadman/background/ARMED/stale trên điện thoại thật | NOT RUN | Unit test không thay kiểm tra lifecycle/multi-touch/thiết bị |
| Failsafe RC_OVERRIDE_TIME=0.5 thực tế | NOT RUN | Cần đo hành vi đúng firmware autopilot, không chỉ số RC hiển thị |

Artifact đã xác nhận: application ID `dev.drone.rcbench`, version `0.1.0-disarmed`, versionCode 1, minSdk 26, compile/target SDK 36. Đây là APK **debug signing**, không phải bản phát hành production có signing/key management đã thiết kế.

SHA-256 của APK từ lần build được báo cáo:

```text
bc5e835d305195a0ac621bf1c9ba031f423607e42c657917b8dbb353b19e3672
```

Hai lint warning là `PluralsCandidate` cho từ “byte” trong chuỗi tiếng Việt và `ViewConstructor` cho `BenchScreen` được khởi tạo bằng code, không inflate qua XML. Warning chưa được sửa/ẩn để làm report sạch; không có lint error. Report tại `app/build/reports/lint-results-debug.html` và `.xml`.

Source đã kiểm tra gồm kiểm tra IPv4 literal để không chờ DNS, socket NIO nonblocking và gate cuối được đồng bộ với thay đổi epoch/stop ở local sender, không có queue RC cũ để phát bù. Các tính chất này không loại bỏ ARMED xảy ra trên autopilot giữa hai heartbeat và không xác thực frame unsigned bằng mật mã.

Dự kiến bàn giao APK debug và ZIP project native qua GitHub Release `rc-bench-v0.1.0-test`; báo cáo này chưa xác nhận upload/publication. Chỉ link/artifact release sau khi workflow thực sự thành công mới là bằng chứng bàn giao online.

Build/test thành công không xác minh các endpoint RC2/3/4, `ARMING_RUDDER`, firmware trên thiết bị, inhibit phía autopilot, bridge nhiều TCP client, timing timeout thật hay khả năng chạy motor an toàn. Không dùng bản này để bay hoặc vận hành motor; điều kiện bench và các mục NOT RUN vẫn phải nghiệm thu riêng.
