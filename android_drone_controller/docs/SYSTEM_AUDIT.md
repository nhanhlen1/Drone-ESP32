# Kiểm toán hệ thống trước triển khai

Ngày kiểm toán: 28/10/2026 (UTC+7). Checkout được kiểm tra: `/workspace/Drone-ESP32`, HEAD `ba634f4ea796fcbc9e776f86c55bc8d1ae529190`. Thư mục `/home/drone/drone-project` trong yêu cầu không phải workspace của phiên cloud này. Không có `AGENTS.md` được tìm thấy dưới `/workspace` khi bắt đầu. App cũ, firmware và tham số thiết bị được giữ nguyên.

## Phạm vi bằng chứng

Có thể kiểm toán mã Kotlin, bộ fixture/test, firmware Arduino/WebSocket, script và tài liệu hiện có. Không có `hwdef.dat`, board `esp32drone`, source ArduPilot tùy chỉnh, parameter dump, log thiết bị, APK hoặc binary firmware lưu trong checkout khi bắt đầu. Sau đó người dùng cung cấp bộ BIN và manifest; đã phân tích chỉ đọc trong [FIRMWARE_COMPARISON.md](FIRMWARE_COMPARISON.md), không đồng nhất tự động với readback của thiết bị. `SessionLogger.kt` là mã ghi log, không phải log thực nghiệm. Báo cáo `android_rc_bench/docs/validation.md` ghi kết quả của một phiên trước; số liệu đó không thay cho việc chạy lại trong phiên hiện tại.

Đã đọc bổ sung các file nguồn chính thức tại revision upstream `afeb9c49` qua HTTPS có xác minh TLS. SHA-256 của file đã tải nằm trong `ardupilot-source-sha256.json`. Đây là đối chiếu source upstream đúng định danh ngắn được người dùng cung cấp, **không chứng minh binary và các patch riêng trên ESP32 giống upstream**. Không sao chép/nạp firmware, không gửi RC tới phần cứng, không sửa tham số.

Sau khi chuẩn bị SITL, đã đối chiếu thêm checkout đầy đủ tại `/workspace/tooling/ardupilot-target-sitl`, HEAD `afeb9c4941543d97f3e04fecf257b8f598f49a6a`. Hash của các file đã tải raw khớp checkout; manifest nguồn lưu thêm hash theo đường dẫn. Checkout này vẫn là upstream, không có patch ESP32 cục bộ của bộ BIN người dùng.

## Phát hiện có bằng chứng trong app cũ

Các đường dẫn dưới đây tương đối với gốc repository.

| Phát hiện | Bằng chứng mã nguồn | Ý nghĩa |
|---|---|---|
| Gate yêu cầu từng nhận RC_CHANNELS mới trước lần phát đầu | `android_rc_bench/app/src/main/java/dev/drone/rcbench/control/BenchEngine.kt:80`, dòng 88–89 | Chỉ có HEARTBEAT DISARMED vẫn không được bắt đầu; đây là điểm khóa đã xác định bằng source. |
| Không từ chối riêng `chancount=0` hoặc raw bằng 0 | Cùng file dòng 171–176 | Count 0–18 được chấp nhận, giữ đủ 18 raw channel. Không được quy lỗi hiện tại đơn thuần cho count=0. |
| Không yêu cầu stream | `android_rc_bench/app/src/main/java/dev/drone/rcbench/transport/BenchSession.kt:128`; `mavlink/Mavlink2Encoder.kt:16` | Bề mặt gửi chỉ HEARTBEAT và override. Không có SET_MESSAGE_INTERVAL/REQUEST_MESSAGE/REQUEST_DATA_STREAM. Firmware không tự stream msg65 sẽ giữ gate đóng. |
| Bootstrap cũ không có hạn tổng độc lập với feedback | `control/BenchEngine.kt:176`, dòng 186–187 | Timeout 750 ms được tính từ feedback khớp mới nhất hoặc lần TX đầu. Feedback khớp liên tục có thể duy trì phát; không phải phép thử CH1 có thời hạn tuyệt đối. |
| Có thể phát cả throttle/yaw trong bench cũ | `control/BenchEngine.kt:189`, dòng 195–196 | Yaw bị cap 25%, nhưng throttle vẫn ánh xạ min→max. Không tái sử dụng quyền phát này cho giai đoạn CH1 ban đầu của ứng dụng mới. |
| Target lấy từ HEARTBEAT, không cố định system=1 | `control/BenchEngine.kt:153`, dòng 155–162 | Chỉ nhận autopilot ArduPilot component 1, system 1–254, MAVLink version=3, không phải GCS. Sender mặc định 255/190 trong encoder. |
| HEARTBEAT từ autopilot khác bị bỏ qua | Cùng file dòng 157 | Không cảnh báo/xử lý xung đột nhiều autopilot thành khóa riêng; cần khóa khi phát hiện mục tiêu mơ hồ. Hai nguồn có **cùng** sysid/compid và payload không phân biệt được chỉ qua unsigned HEARTBEAT. |
| Chẩn đoán sequence chưa bao quát mọi frame | Cùng file dòng 154, 163–169 | Frame chưa hỗ trợ decoder bị trả về sớm trước khi đếm sequence; gap có thể là message không giải mã hoặc lọc, không phải phép đo mất gói RF. |
| TCP NIO ghi một frame tại một thời điểm, gate cuối dưới lock | `transport/BenchSession.kt:106`, dòng 123–153 | Có xử lý frame dở, write timeout 200 ms, không queue backlog RC; không đồng nghĩa FC đã nhận/chấp nhận/áp dụng. |
| Wi-Fi không Internet được chấp nhận | `transport/AndroidWifiBinder.kt:24`, dòng 25–37 | Bind socket vào Wi-Fi, không vô tình dùng đường mobile mặc định. Tuy nhiên chọn Wi-Fi hiện có không xác thực SSID/IP của đúng ESP32 trong cấu hình nhiều mạng. |
| Background/focus thu hồi quyền | `android_rc_bench/app/src/main/java/dev/drone/rcbench/MainActivity.kt:72`, dòng 73–88 | Pause đóng session; focus mất dừng engine. Kiểm tra mã không thay cho thử lifecycle thật. |
| Parser incremental có kiểm CRC, giới hạn buffer và resync | `mavlink/Mavlink2Parser.kt:15`, dòng 24, 34–109 | Signed frame bị từ chối có chủ đích; CRC không phải xác thực nguồn. Unknown message không được coi là CRC-verified. |
| Decoder và dialect cũ chỉ có một tập nhỏ telemetry | `mavlink/MavlinkCommon.kt:13`; `mavlink/TelemetryDecoder.kt:81` | Thiếu PARAM_VALUE, COMMAND_ACK, BATTERY_STATUS, GLOBAL_POSITION_INT, SCALED_PRESSURE, SERVO_OUTPUT_RAW, MANUAL_CONTROL. |

Các đường dẫn viết tắt `control/`, `transport/`, `mavlink/` trong bảng nằm dưới `android_rc_bench/app/src/main/java/dev/drone/rcbench/`.

## Phân biệt mười khả năng gây TX override = 0

| Trường hợp | Kết luận kiểm toán | Bằng chứng cần thu để xác minh trên thiết bị |
|---|---|---|
| 1. Không nhận msg65 | Gate chắc chắn chặn nếu tình trạng này xảy ra; tình trạng thực chưa có log | Count message65, RX bytes, timestamp HEARTBEAT và capture frame. |
| 2. Parser lỗi msg65 | Có decoder đúng layout và fixture độc lập; chưa có frame lỗi thực tế | Raw TCP/MAVLink capture, CRC counter, compare với pymavlink/Mission Planner. |
| 3. Nhận count=0 | Được source cũ chấp nhận; không tự gây khóa | Log count và toàn bộ raw channel. |
| 4. Nhận raw=0 | Được chấp nhận để bootstrap trong bản checkout này | Raw values và lý do gate; không thay raw=0 thành số giả. |
| 5. Chưa có receiver/override | Có cơ chế upstream cho tình huống này; chưa đọc thiết bị | Parameter dump, receiver status, RC backend/log đúng binary. |
| 6. Firmware không stream msg65 qua TCP | Chưa xác minh; không có hwdef/custom firmware ở đây | Read-only stream request/ACK, msg65 quan sát được, cấu hình SERIAL/WiFiDriver. |
| 7. App không yêu cầu stream | **Đã chứng minh bằng source cũ** | Có thể sửa ở app mới bằng yêu cầu hữu hạn; ACK không thay cho message. |
| 8. FC từ chối override | Không thể là nguyên nhân của counter TX=0 do app chưa tạo/ghi override; vẫn có thể là lỗi tiếp theo sau khi mở gate | Capture phía FC và trạng thái backend; không có ACK riêng cho msg70. |
| 9. SYSID/COMPID sai | Có kiểm tra/discovery trong app; target quá chặt có thể giữ UNKNOWN nếu telemetry khác | Header HEARTBEAT, AUTOPILOT_VERSION, MAV_GCS_SYSID/HIGH thực tế. |
| 10. Nhận nhưng backend không áp dụng | Chưa xác minh | `has_override`, override value/time, `radio_in` và nguồn RC độc lập theo thời gian. |

**Kết luận nguyên nhân:** đã chứng minh gate phụ thuộc việc nhận msg65 và sự thiếu yêu cầu stream trong app cũ. Chưa có log từ phiên lỗi để kết luận firmware thực không phát msg65 hoặc parser làm mất bản tin. Giả thuyết vòng phụ thuộc hợp lý ở trường hợp **không có bản tin**, nhưng mã hiện tại không tạo vòng đó chỉ vì `chancount=0` hay raw=0. Counter TX=0 chỉ chứng minh chưa ghi override qua socket theo quan sát đã cung cấp; chưa chứng minh từ chối ở ArduPilot.

## Đối chiếu ArduPilot upstream afeb9c49

Nguồn: <https://github.com/ArduPilot/ardupilot/tree/afeb9c49>. Các số dòng sau thuộc file upstream tải trong phiên này.

- `libraries/GCS_MAVLink/GCS.cpp:745`: `sysid_is_gcs` chấp nhận đúng MAV_GCS_SYSID khi HIGH<=LOW; nếu HIGH>LOW thì dùng khoảng đóng LOW..HIGH. Với 255/0 do người dùng cung cấp, sender255 phù hợp theo source này. MAV_OPTIONS=0 không phải quyền ép nhận mọi GCS.
- `libraries/GCS_MAVLink/GCS_Common.cpp:4110`: handler RC_CHANNELS_OVERRIDE kiểm `sysid_is_gcs`, giải mã rồi gọi `RC_Channels::set_override`. CH1–8 bỏ qua 65535, zero là release; CH9–16 bỏ qua 0/65535, 65534 được chuyển thành release0. Source revision này không xử lý CH17/18 trong mảng handler dù wire có extension; ứng dụng chỉ cần CH1 ở giai đoạn đầu.
- `libraries/RC_Channel/RC_Channel.h:669`: RC_OPTIONS bit0=IGNORE_RECEIVER, bit1=IGNORE_OVERRIDES, bit5=ARMING_CHECK_THROTTLE. 33=bit0+bit5, không chứa bit1. Không được diễn giải 33 thành “bỏ qua mọi MAVLink override”.
- `libraries/RC_Channel/RC_Channels.cpp:88`: `read_input` tiếp tục khi có `has_new_overrides` dù không có input receiver mới. `RC_Channel.cpp:302` chọn override còn hiệu lực nếu không IGNORE_OVERRIDES. `set_override:527` còn kiểm `gcs_overrides_enabled` và kênh AUX chức năng RC_OVERRIDE_ENABLE.
- `GCS_Common.cpp:2173` gửi RC_CHANNELS từ `get_radio_in`; `RC_Channels.cpp:160` lấy count từ `hal.rcin->num_channels()`. Do đó count vật lý0 có thể đi cùng raw override khác0.
- `RC_Channel.cpp:560`: `has_override` dùng timeout, zero timeout vô hiệu override, negative/config disabled timeout có thể giữ quyền. Khi hết hạn và không có receiver thay thế, `update` trả false mà không xóa `radio_in`. Vì vậy số PWM còn hiển thị không chứng minh override vẫn có hiệu lực, cũng không chứng minh timeout chưa chạy.
- `GCS_Common.cpp:7637` MANUAL_CONTROL dùng cùng kiểm sender và target system rồi `manual_override` vào chính backend RC. `ArduCopter/GCS_MAVLink_Copter.cpp:840` map roll=y, pitch=x đảo dấu, throttle=z không âm, yaw=r. Đổi message69 không loại bỏ gate sender/RC_OPTIONS/backend.
- `libraries/GCS_MAVLink/GCS_Common.cpp:1884` đưa frame qua MAVLink routing trước handler, sau đó `accept_packet:7378` xét GCS_SYSID_ENFORCE. Handler override vẫn kiểm sender GCS riêng ngay cả khi enforce chung tắt; không suy MAV_OPTIONS=0 thành bỏ kiểm sender của msg70.
- `libraries/AP_HAL_ESP32/RCInput.cpp:77` lấy count receiver thực; `read:85` trả0 khi kênh chưa tồn tại. Đây là lý do raw receiver0/count0 có thể xuất hiện trước override. `HAL_ESP32_Class.cpp:40` chọn WiFiDriver TCP khi HAL_ESP32_WIFI=1 vào serial1; `AP_SerialManager.cpp:47` mặc định SERIAL1_PROTOCOL=MAVLink2. Board/patch riêng hoặc tham số đã lưu có thể ghi đè mặc định đó.
- `libraries/RC_Channel/RC_Channel.h:697` chuyển RC_OVERRIDE_TIME dương thành millisecond, zero vô hiệu override, giá trị âm không timeout. `RC_Channels_VarInfo.h:84` ghi mặc định upstream3.0s; số0.5s được người dùng cung cấp cần đọc lại runtime trước đo, không thay bằng mặc định SITL.

Ưu tiên RC_CHANNELS_OVERRIDE cho phép thử CH1 vì wire PWM đối chiếu trực tiếp được trim/min/max đã cung cấp và bỏ qua toàn bộ kênh khác. MANUAL_CONTROL chỉ là phương án được test độc lập trong mô phỏng, không tự fallback lúc đang phát.

## WiFiDriver và firmware hiện có

`firmware/platformio.ini:4` chọn Arduino `esp32dev`; `firmware/src/network/Network.cpp:26` mở WebSocket:81/ws và HTTP. Đây **không phải ArduPilot MAVLink TCP:5760**. GPIO trong `firmware/include/pins.h:4` trùng dữ liệu phần cứng không biến prototype này thành source binary ArduPilot. Không nạp prototype lên ESP32 đang hoạt động.

Đã đọc `libraries/AP_HAL_ESP32/WiFiDriver.cpp` upstream afeb9c49: listen port5760 ở dòng128; nonblocking socket ở153; read/write vòng160/183; task accept ở383. Task chỉ gọi `try_accept()` trước vòng read/write nội bộ kéo dài; vì vậy số `WIFI_MAX_CONNECTION=5` mặc định trong header **không đủ chứng minh năm client TCP hoạt động đồng thời**. Vòng đọc/ghi cũng kết thúc khi gặp slot=-1; RX enqueue không kiểm kết quả byte ghi, `recv=0` không có nhánh đóng tức thời. Đây là điểm cần kiểm tra packet loss/half-open/multi-client trên đúng build, không phải chẩn đoán đã quan sát lỗi thiết bị của người dùng.

URL upstream cho `libraries/AP_HAL_ESP32/hwdef/esp32drone/hwdef.dat` và `.h` trả404. Không suy ra board thật không tồn tại: có thể ở fork hoặc local patch. Macro SSID/password mặc định của upstream không phải bằng chứng mật khẩu thực; app mới không được hardcode hoặc đoán password từ đó.

Đã duyệt checkout upstream đầy đủ: có các board như esp32diy/esp32empty/esp32nick/esp32buzz, nhưng không có thư mục `hwdef/esp32drone`. Vì vậy không chọn một board tên gần giống để suy mapping cảm biến/motor của binary đã nạp. Kết quả này xác định giới hạn source upstream, không phủ nhận cấu hình board riêng của người dùng.

## Chiến lược thay đổi và quality gate

1. Giữ app cũ làm đối chứng. Tách app mới, package mới để cài song song, không chỉnh firmware/tham số.
2. Thêm telemetry negotiation hữu hạn, chỉ message interval/request/parameter read theo danh sách cho phép; xử lý ACK UNSUPPORTED/DENIED/timeout và theo dõi message nhận độc lập.
3. Chẩn đoán không phát RC trước: phân loại absent/count0/raw0/invalid/stale. Không dùng RC_CHANNELS như điều kiện tiên quyết cho **một phép CH1 bootstrap**; vẫn cần DISARMED mới, target duy nhất, cấu hình đã đối chiếu, xác nhận vật lý từng phiên và deadman.
4. Phép bootstrap chỉ CH1 quanh trim rất nhỏ, hard deadline không gia hạn bởi telemetry, không retry tự động. Không tăng throttle, không yaw, không ARM/MotorTest/PARAM_SET/đổi mode thật. Sau khi hết hạn phải xác nhận lại.
5. State machine tách kết nối/telemetry/diagnostic/bench/verified/locked/lost/failsafe/armed-monitoring; lease có epoch, lifecycle thu hồi, kiểm gate sát trước ghi socket. Reconnect không mang throttle, permit hoặc xác nhận cũ.
6. Test byte-level với vector độc lập và TCP thật; receiverless, CRC, partial read/write, timeout, duplicate target, stale, STOP/deadman/focus/reconnect. Một FC giả chỉ echo số không đủ chứng minh source ArduPilot thật; báo riêng harness hợp đồng và SITL nếu có.
7. Đánh giá timeout ở backend đúng nguồn; phân biệt generated → socket-written → FC accepted → input-applied. RC_CHANNELS khớp là quan sát tương quan, không ACK hoặc xác thực nguồn.
8. Build/test/lint là gate phần mềm. Instrumentation/device, 30 phút Wi-Fi, timeout/failsafe thật, ESC protocol/motor order và phương án dừng độc lập vẫn **NOT VERIFIED** đến khi có bằng chứng riêng.

## Dữ liệu thiết yếu còn thiếu theo giai đoạn

Phần phát triển host không cần hỏi lại cấu hình cảm biến/IP/RC đã cung cấp. Để nghiệm thu thiết bị sẽ cần điện thoại/ADB, source hoặc build provenance ArduPilot tùy chỉnh, parameter dump hiện tại (đặc biệt RC mapping/reverse, ARMING_RUDDER, flight-mode channel, FS_GCS_TIMEOUT/RC_FS_TIMEOUT), log FC và phép đo timeout. Chưa có bằng chứng nguồn ESC bị ngắt trong phiên này, nên không có quyền phát thử RC vật lý. Tình trạng điều khiển dự phòng/dừng độc lập chưa được xác minh; phần mềm app không thể tự tạo sự bảo đảm vật lý đó.

## Bổ sung app tham khảo ngày29/10/2026

Người dùng xác nhận app Google AI Studio đã làm thay đổi CH1 input trên Mission Planner. Ghi nhận đây là kết quả thực tế do người dùng báo cáo; nó cho thấy có đường RC hoạt động trên hệ thống của họ, nhưng chưa xác định định dạng/gate khác nhau ở mã nào. ZIP đính kèm vượt giới hạn tải32MiB nên chưa đọc được. Không kết luận đã đối chiếu source hoặc tái hiện bằng app đó. Xem `AI_STUDIO_REFERENCE.md` cho trạng thái truy cập và các tệp cần để so sánh.
