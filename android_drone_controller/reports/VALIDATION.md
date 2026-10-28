> Báo cáo lịch sử28/10/2026 cho0.2.0. Kết quả mới và giới hạn sourceGG ở [20261029/VALIDATION.md](20261029/VALIDATION.md).

# Kết quả kiểm định phần mềm — 28/10/2026

Đây là **bản ứng viên kiểm thử bench**, chưa phải sản phẩm hoàn tất nghiệm thu Android–ESP32 hoặc sẵn sàng bay. Mã mới nằm riêng `android_drone_controller`; app cũ và firmware trong kho giữ nguyên, không commit/push hoặc phát hành từ xa.

## Các phép đo đã hoàn thành

| Phép kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| Baseline app cũ | 49/49 test PASS; build PASS; lint0error/2warning cũ | logs/baseline-rc-bench.log |
| App mới, reference ArduCopter4.6.3 | 121 test,0failure,0error,0skipped | junit-reference-4.6.3/*.xml |
| App mới, upstream base afeb9c49 | 121 test,0failure,0error,0skipped | junit-target-afeb9c49/*.xml |
| Công cụ telemetry ngoại tuyến | 5/5 test PASS | logs/reliability-final-tests.log; lượt cuối cùng trong logs/controller-final-setup.log |
| Lint cuối | 0error,1warning ViewConstructor | lint-results-debug.xml / .txt |
| assembleDebug | PASS, package dev.drone.controller, version0.2.0-bench/code1 | logs/controller-final-setup.log; APK |
| assembleDebugAndroidTest | PASS, APK test đã biên dịch | Không phải instrumentation đã chạy |
| Chữ ký APK debug | apksigner verify PASS, APK Signature Scheme v2 | logs/apk-verification.txt |
| Cài đặt tái sử dụng | install-controller.sh chạy đầy đủ exit0 | logs/controller-final-setup.log; setup/install-controller.sh |
| Draft cấu hình cloud | install_script và start_skill đã lưu, revision2; đọc lại khớp | Cần người dùng Review/Save/Publish trong cài đặt môi trường |

121 là số test duy nhất trong mỗi lượt, không cộng thành242 test khác nhau. Thành phần: core45, MAVLink30, ARM command model13, TCP8, ViewModel7, joystick10, telemetry request2, network lease2, logger2, SITL2. Không có test bị bỏ qua trong hai lượt được kê ở trên. Các lần lỗi trước được giữ ở `diagnosis/`, không xóa bằng chứng để báo PASS.

Warning cuối không bị suppress: `ControllerScreen` nhận Activity+UiActions và chỉ được tạo bằng code, không được inflate bằng XML/layout editor. Giữ minSdk26; lỗi API27 đã sửa bằng values-v27, không nâng minSdk để né lint. 20 chuỗi UI động chuyển sang resources, không tắt rule.

## Bằng chứng RC thực thi trong SITL

- Reference4.6.3 commit `92b0cd788ec29406f26c6f9c31d5ceedbd1cc538`; binary SHA256 `6c8ed51ba7346216b83d758532e2b3558e67660f92fd62aae1ac455466ee9c26`.
- Upstream base4.8dev commit `afeb9c4941543d97f3e04fecf257b8f598f49a6a`; binary SHA256 `41fd63749fa164c226a23ee395e683064553660f9b202581e94ba952ced6c9bf`.
- Đúng BenchEngine/BenchSession/encoder Kotlin của app → TCP loopback → ArduPilot SITL → RC input. Reference có13 override và target có15 override hữu hạn, CH1 được quan sát1520µs, CH2–4 vẫn0. Log DataFlash do firmware mô phỏng tự ghi cũng thể hiện RCIN0→1520, không chỉ peer echo hoặc TX bytes.
- Encoder trực tiếp kiểm thử riêng cả RC_CHANNELS_OVERRIDE và MANUAL_CONTROL trên CH1–4, kênh phụ giữ nguyên, sender GCS sai bị từ chối, timeout trở về receiver mô phỏng với RC_OVERRIDE_TIME0.5. Đường này dùng encoder thật nhưng **không phải** đường joystick live của APK; không dùng kết quả đó để nói APK đã điều khiển đầy đủ bốn kênh.
- Không có bản ghi ARM trong log các phép thử, không gửi ARM/Motor Test/đổi mode. Thiếu bản ghi ARM không tự là bằng chứng toàn diện về an toàn vật lý.
- Receiverless rawRC có thể giữ số cuối sau hết override. Thời điểm radio failsafe xuất hiện trong log không chứng minh CH1 override đã hết hạn. **Timeout nguồn override khi không receiver vẫn chưa có phép quan sát độc lập đủ mạnh**, ngoài logic source và bài đối chiếu có receiver mô phỏng. Không gắn nhãn failsafe acceptance PASS.

Đọc `sitl-reference/dataflash-summary.json`, `sitl-target/dataflash-summary.json`, các XML stdout và `docs/INTEGRATION.md`. Full checkout upstream không bao gồm custom board/patch ESP32 INA219/rotation/Wi-Fi trong bộ firmware người dùng; checksum binary đính kèm không biến SITL thành thử phần cứng.

## Thiết lập và giới hạn

JDK21.0.9, SDK36/BuildTools35, Gradle8.12, AGP8.9.1, Kotlin2.1.0; JVM target17. JDK/SDK/Gradle checksum và TLS giữ nguyên. Script tái sử dụng đã chạy thực tế; hai helper SITL chạy lại incremental1.7giây, không rebuild toàn bộ. Không cần Flutter/NDK/secret/Wi-Fi password để build/test này; không mở rộng allowlist.

ADB chạy thành công nhưng danh sách thiết bị rỗng. Ba instrumentation test đã viết và compile, **NOT VERIFIED** khi chưa có thiết bị. Không có Windows Desktop mount trong cloud, nên bản giao lưu ở workspace. Publication/snapshot và khôi phục trên task mới **chưa được thực hiện**; draft save không thay cho publish.

Các giới hạn tính năng, phần cứng và failsafe nằm trong FEATURE_STATUS.md, HARDWARE_TEST.md, WIFI_RELIABILITY.md và FAILSAFE.md. Bước tiếp theo là bài đo chỉ đọc60giây trong `docs/HARDWARE_AND_FAILSAFE.md`; không tự phát RC trên thiết bị thật.

## Tiếp tục đóng gói ngày29/10/2026

Giữ nguyên mã ứng dụng và kết quả kiểm thử nêu trên; hoàn tất gói APK/source/báo cáo bị gián đoạn, xác minh lại chữ ký và hash bản giao. App Google AI Studio mới được người dùng báo đã đưa CH1 input vào Mission Planner, nhưng ZIP vượt giới hạn chuyển tệp32MiB nên chưa đọc/đối chiếu. Không coi kết quả của app đó là nghiệm thu phần cứng của APK mới. Chi tiết và phần đang chờ: `docs/AI_STUDIO_REFERENCE.md`.
