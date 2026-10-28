# Baseline lỗi và khoảng trống —29/10/2026

Đây là baseline **trước thay đổi theo master prompt mới**. Phải phân biệt: (A) lỗi của source Google AI Studio được mô tả trong đặc tả nhưng chưa đọc được source; (B) khoảng trống đã quan sát trong candidate Codex có sẵn; (C) kết quả đã kiểm thử ở phiên trước. `00_SOURCE_INVENTORY.md` và JSON hash xác định đúng danh tính; không gọi candidate Codex là app Google AI Studio.

## A. Google AI Studio: chưa thể xác minh trực tiếp

| Mục trong master prompt | Trạng thái source lúc kiểm kê | Bằng chứng cần để kiểm tra |
|---|---|---|
| getCrcExtra unknown trả0 | UNVERIFIED | MavlinkCrc.kt và frame không nằm trong bảng |
| Thiếu13byte signature, incompat flags/bounded resync | UNVERIFIED | MavlinkFrameParser.kt và vector ký/phân mảnh |
| lastSequence toàn cục gây false gap | UNVERIFIED | Parser/counter reducer và luồng nhiều SYS/COMP |
| level3FcReceived=true từ HEARTBEAT | UNVERIFIED | DroneViewModel.kt và test âm không có RC echo |
| Request telemetry trước khám phá target | UNVERIFIED | DroneTcpClient/handshake/ViewModel |
| ARMED_MONITORING luôn khóa RC/stop khi ARMED | UNVERIFIED cho Google AI | SafetyState.kt và state reducer |
| ARM gửi trực tiếp, crash khi ARM | UNVERIFIED | Command workflow, MainControlScreen và crash log |
| “SITL13PASS” thực ra ServerSocket mock | UNVERIFIED | Test harness gốc và log mới |
| Thiếu wrapper, dependency Firebase/Google thừa | UNVERIFIED | Gradle files, wrapper tree và APK metadata |

Đặc tả mới là yêu cầu bắt buộc do người dùng chỉ định; các mô tả lỗi trong đó chưa thay cho việc đọc mã/tái hiện lỗi. Người dùng báo CH1=1470 và Mission Planner ch1in1470; ghi nhận đây là **USER-REPORTED HARDWARE RC EVIDENCE**, chưa phải test phần cứng của candidate Codex hay capture do môi trường này thu được.

## B. Candidate Codex hiện có: khoảng trống đã thấy

| Ưu tiên | Khoảng trống baseline | Source/bằng chứng | Hệ quả với yêu cầu mới |
|---|---|---|---|
| P0 nguồn | Base Google AI Studio chưa có trong workspace/kho | Inventory, lỗi transfer32MiB | Chưa thể hoàn thành continuation đúng base hoặc diff source/APK |
| P0 phạm vi điều khiển | BenchEngine dừng RC khi HEARTBEAT ARMED | control/BenchEngine.kt:285, state ARMED_MONITORING | Đúng cho bench hiện tại; chưa có mode flight-candidate đủ qualification, không được mở bằng cách bỏ gate chung |
| P1 chức năng | Đường live chỉ CH1±20µs/750ms, hai joystick chỉ xem trước | control/BenchEngine.kt, UI/docs FEATURE_STATUS | Chưa đáp ứng bench1470/1500/1530, CH2–4 và tay cầm live theo đặc tả mới |
| P1 lệnh | ArmCommandService là semantic model; không có ARM/DISARM encoder nối TCP | command/ArmCommandService.kt, docs ARM_COMMANDS | Có13test mô hình nhưng chưa phải command workflow live; flight-mode command chưa triển khai |
| P1 dialect | CommonDialect baseline chỉ19message thay vì bảng common+ardupilotmega đầy đủ | mavlink/MavlinkCommon.kt | Unknown đã được tách riêng, không dùngCRC_EXTRA0; vẫn chưa validate được mọi message chuẩn/dialect được yêu cầu |
| P1 hiệu năng | Chưa có bằng chứng30phút scheduler/telemetry hoặc100reconnectloops ở baseline | reports/VALIDATION.md, WIFI_RELIABILITY.md | Cần bài đo dài/rate-jitter/bounded state để phát hiện lỗi bền vững |
| P1 UI/Android | UI native Views7trang, chưa có HUD hoàn chỉnh/cấu hình mọi deadzone/expo/rate | ui/ControllerScreen.kt, UI_DESIGN.md | Test host/compile không thay cho kiểm chứng giao diện thật, rotate/multitouch Android |
| P1 phần cứng | Không có bằng chứng ADB/ESP32 thật của candidate | HARDWARE_TEST.md | Phần cứng và bay không được gắn PASS |

Số dòng chỉ định ở trên thuộc baseline trước sửa; JSON lưu hash file tham chiếu, không phải khẳng định file hiện hành vẫn ở cùng dòng sau sửa.

## C. Các điểm đã có bằng chứng, cần giữ regression

- Golden vectors từ pymavlink; parser bounded, partial reads/coalesced frames, CRC_EXTRA chính xác cho tập message hiện hỗ trợ, unknown tách khỏi CRC lỗi, signed frames bị từ chối có chủ đích và tính cả13byte signature.
- Không chờ RC_CHANNELS trước phép bootstrap; target từ HEARTBEAT hợp lệ; request telemetry/tham số chỉ đọc hữu hạn sau target discovery.
- Control epoch/serial/permit tuổi50ms, một TCP writer, partial-write cancellation đóng socket; generation khóa callback kết nối cũ; ga/cờ xác nhận không sống qua reconnect/background/STOP.
- ARM state tách SystemHealth; CRITICAL mặc định khóa, ngoại lệ chẩn đoán hẹp cần xác nhận riêng và ESC ngắt vật lý; không coi mẫu lỗi RC/PreArm là chứng minh mọi failsafe khác vắng mặt.
- Bằng chứng phiên trước: mỗi lượt121test PASS trên SITL4.6.3 và upstream base afeb9c49,5test recorder, lint0error/1warning ViewConstructor; logDataFlash RCIN0→1520. Đây là kết quả lịch sử được lưu, không phải lượt mới của master prompt29/10/2026.
- Timeout có receiver mô phỏng đã test; timeout receiverless không được chứng minh chỉ bằng rawRC giữ số cuối hoặc radiofailsafe event. Không gọi motor/flight/failsafe phần cứng PASS.

## Gate cho bàn giao tiếp theo

1. Giữ hash artifact/source baseline và checkpoint nguyên vẹn trước thay đổi.
2. Các thay đổi thư viện/test độc lập phải được build/test thực tế và ghi đúng candidate, không giả làm bản đã migrate Google AI Studio.
3. Khi đọc được source gốc, tái hiện các lỗi mụcA bằng regression có vector/log, áp dụng thay đổi cần thiết, giữ functionalityCH1 thực đã có.
4. Báo độc lập SOFTWARE TESTED / SITL TESTED / HARDWARE RC INPUT VERIFIED / FLIGHT READY NOT VERIFIED, với phương pháp và timestamp; mọi mục chưa chạy phải NOT RUN/BLOCKED, không đổi nhãn để xanh.
