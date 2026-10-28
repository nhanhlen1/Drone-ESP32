# Kiểm thử tích hợp và giới hạn bằng chứng

## Ba nguồn bằng chứng

`app/src/test/.../transport/BenchSessionTest.kt` dùng TCP loopback thật và peer mô hình phát MAVLink2 có CRC. Nó thực thi `BenchSession` và `BenchEngine` của app: HEARTBEAT, stream RC vắng mặt ban đầu, phân mảnh/gộp read, CH1 nhỏ, deadline tuyệt đối, phản hồi tương quan, ARMED, CRC hỏng, background, deadman, HEARTBEAT stale và reconnect. Peer mô hình không phải ArduPilot; phản hồi mirror chỉ chứng minh đường truyền/phân tích, không chứng minh FC áp dụng RC.

`app/src/test/.../integration/SitlIntegrationTest.kt` chạy **ArduCopter SITL thật**, mã upstream `Copter-4.6.3`, commit `92b0cd788ec29406f26c6f9c31d5ceedbd1cc538`. Mỗi test khởi động process riêng, thư mục tạm riêng, cổng localhost riêng và tham số mô phỏng riêng. Không giao tiếp thiết bị vật lý. Không gửi ARM, Motor Test, đổi flight mode hay thay tham số trên FC thật.

Nguồn đang nạp trên ESP32 của người dùng là 4.8.0-dev revision `afeb9c49`, chưa có đầy đủ trong kho. Vì vậy PASS SITL upstream không tương đương PASS firmware ESP32 hoặc kiểm định phần cứng. Host JVM sử dụng đúng logic domain/encoder/TCP của app nhưng không thực thi Android OS, Wi-Fi binding, lifecycle framework, permission hoặc cảm ứng thật. Các phần đó cần instrumentation/điện thoại riêng.

## Chạy lại

```bash
# Cài/build reference SITL từ source chính thức (mất vài phút, cần GitHub/PyPI).
./integration/setup_sitl.sh
source /workspace/tooling/onboarding/android-env.sh
cd /workspace/Drone-ESP32/android_drone_controller
DRONE_SITL_BINARY=/workspace/tooling/ardupilot-sitl/build/sitl/bin/arducopter \
  ./gradlew --no-daemon testDebugUnitTest --tests '*SitlIntegrationTest'
```

Không đặt `DRONE_SITL_BINARY` thì hai test SITL báo **SKIPPED**, không phải PASS. Chi tiết XML ở `app/build/test-results/testDebugUnitTest/`. Chạy riêng test bằng bộ lọc có thể thay báo cáo của lần test toàn bộ; giữ bản sao XML khi cần đối chiếu từng lượt.

SITL được cấu hình `--board sitl --disable-scripting --disable-networking --no-submodule-update`, build `copter -j6`. Lua/AP_Networking peripheral không cần cho đường MAVLink TCP serial SITL; TCP serial vẫn hoạt động. Build dùng nguồn/submodule tại đúng revision Git, không đổi checksum hoặc bỏ TLS verification.

## Điều từng test có thể chứng minh

1. `realBenchSessionReceiverlessCh1ReachesArduPilotRadioInputAndStops`: cấu hình mô phỏng `RC_OPTIONS=33`, không đòi RC stream đầu vào để cấp phép. Chạy đúng session của app, kiểm tra CH1=1520 trong RC_CHANNELS do ArduPilot phát, dừng session theo deadline, không mở `inputVerified`. Trong source tham chiếu, `GCS_Common.cpp::send_rc_channels()` lấy `rc().get_radio_in`, và `RC_Channel.cpp::update()` chọn override vào `radio_in`. Đây là bằng chứng RC input trên bản SITL đã chạy, không chỉ TX socket.
2. `referenceEncoderBothMethodsMapAllAxesAndOverrideTimeoutReturnsToSimulatedReceiver`: dùng encoder Kotlin của app để thử CH1–4 và MANUAL_CONTROL trên mô phỏng, tách hoàn toàn khỏi quyền phát CH1 của app sản phẩm. MIN/TRIM/MAX 1100/1500/1900. Kiểm tra sign pitch của MANUAL_CONTROL qua implementation Copter. Test `RC_OVERRIDE_TIME=0.5` với receiver **mô phỏng tồn tại**, quan sát trở về baseline receiver sau khi ngừng override.

Trong cấu hình **không receiver**, `radio_in` có thể giữ giá trị cuối sau khi override hết hiệu lực: source `RC_Channel::update()` trả về false khi không có override hoặc receiver được phép. Do đó raw RC_CHANNELS giữ số cuối không chứng minh timeout thất bại, cũng không chứng minh timeout đã đúng. Test timeout có receiver mô phỏng không giải quyết trọn receiverless failsafe. Cần đối chiếu trạng thái override/failsafe/log độc lập trên đúng firmware ESP32 trước nghiệm thu phần cứng. `RC_CHANNELS` không phải COMMAND_ACK của override.

## Đo telemetry ngoại tuyến trên Windows

`integration/telemetry_reliability.py` chỉ dùng thư viện chuẩn Python3, không cần Internet, không cần pip. Nó **không gửi byte nào** lên socket: không HEARTBEAT, không yêu cầu stream, không RC. Nếu firmware không tự stream khi thiếu heartbeat, kết quả là NOT VERIFIED; dùng app để yêu cầu stream cần xác minh bridge hỗ trợ client đồng thời, không tự giả định.

Sau khi điện thoại/máy tính kết nối đúng AP ESP32, trên Windows:

```powershell
python integration\telemetry_reliability.py --host 192.168.4.1 --port 5760 --duration 1800 --output reports\telemetry-30min
```

Tên output phải mới; công cụ không ghi đè file capture sẵn có. Kết quả gồm `.jsonl` lưu timestamp monotonic/bytes và `.json` tổng hợp: RX bytes, CRC lỗi của message đã biết, message chưa xác minh, sequence gap/out-of-order/duplicate riêng, HEARTBEAT age/gap, socket close, dấu hiệu boot time giảm. CRC unsigned không xác thực nguồn; chưa xác minh chữ ký nên signed frame không được tính là heartbeat đáng tin cậy. Sequence gap là phép quan sát MAVLink, không tự quy thành Wi-Fi packet loss.

Phân tích lại ngoại tuyến:

```powershell
python integration\telemetry_reliability.py --replay reports\telemetry-30min.jsonl --output reports\replay
```

Chạy test công cụ:

```bash
python3 -m unittest discover -s integration -p 'test_*.py' -v
```

Cần thu log Android riêng cho background/foreground và network binding; recorder host không thay thế phép đo Android. Chưa chạy 30 phút trên ESP32 thật thì báo cáo độ ổn định phải ghi **NOT VERIFIED**. Không phát RC trong bài đo telemetry dài.

## Phát hiện CRITICAL trong tích hợp receiverless

Lượt đầu tiên đã phát hiện một gate bổ sung: ArduPilot không ARM nhưng báo `MAV_STATE_CRITICAL` khi radio failsafe hoạt động; gộp trạng thái sức khỏe này thành ARM UNKNOWN làm phiên không thể bắt đầu phép chẩn đoán. Snapshot thất bại được giữ trong `reports/diagnosis/`, không xóa dấu vết để báo PASS.

Engine sau sửa tách bit ARMED khỏi `system_status`. CRITICAL vẫn hiện rõ và mặc định khóa phát. Chỉ lời đồng ý **riêng** cho chẩn đoán CRITICAL/receiverless, cùng ba xác nhận vật lý/cho phép đã có, mới xét phép thử CH1 nhỏ: SYS_STATUS phải mới, các bit unhealthy enabled chỉ được gồm RC_RECEIVER/PREARM_CHECK, bắt buộc RC_RECEIVER unhealthy, không có lỗi mở rộng và RC đầu vào ban đầu vắng/0. Kiểm tra sức khỏe/deadman/HEARTBEAT tiếp tục trong lease; feedback không gia hạn 750 ms hoặc mở điều khiển khác.

Mẫu bit trên không chứng minh radio là nguyên nhân duy nhất của CRITICAL: một số lỗi/failsafe không được thể hiện trong sensor mask. Vì vậy không gọi máy là khỏe hay sẵn sàng bay; ngoại lệ này chỉ phục vụ phép đo nhỏ khi ESC đã ngắt nguồn vật lý và có xác nhận riêng. Không tắt FS_THR_ENABLE, GCS failsafe hay ARMING_CHECK.

## Đối chiếu DataFlash độc lập

`integration/summarize_sitl_logs.py` đọc file `.BIN` do firmware SITL ghi, cần pymavlink 2.4.49 đã được cài trong venv phát triển. Nó xuất các thay đổi RCIN, ERR và ARM; không kết nối mạng hay gửi lệnh. Ví dụ:

```bash
/workspace/tooling/mavlink-venv/bin/python integration/summarize_sitl_logs.py \
  reports/sitl-reference-direct/00000001.BIN
```

Lượt trực tiếp ban đầu đã ghi 59 bản tin RCIN với chuỗi baseline `[1500,1500,1000,1500]` → `[1540,1460,1140,1540]` → baseline sau ngừng override → giá trị MANUAL_CONTROL; không có bản tin ARM. Thư mục chứa cả BIN, tham số, console và JSON tóm tắt, là bằng chứng của đúng lượt mô phỏng đó. Kết quả regression mới nhất được ghi riêng trong báo cáo tổng hợp.

Để chạy trên upstream base `afeb9c4941543d97f3e04fecf257b8f598f49a6a` với binary riêng, đặt `DRONE_SITL_PARAM_PROFILE=modern`: fixture dùng tên `MAV_SYSID`/`MAV_GCS_SYSID` thay cho `SYSID_THISMAV`/`SYSID_MYGCS`. Các ID target dùng encoder được khám phá từ HEARTBEAT thật, không lấy từ giả định tên tham số. Base upstream này vẫn không chứa bản sửa/board ESP32 riêng nếu chưa được cung cấp.

## Kết quả chạy tham chiếu 4.6.3

Lượt regression JUnit ngày 28/10/2026 đã thực thi **121 test, 0 failure, 0 error, 0 skipped**, gồm cả hai test SITL thật. XML được giữ tại `reports/junit-reference-4.6.3/`. Đây là kết quả test; lỗi lint ở lượt build cùng thời điểm được xử lý và ghi riêng, không gộp thành build PASS.

- Phiên receiverless qua **BenchSession sản phẩm**: 13 override CH1. DataFlash có 20 RCIN records, chuyển `[0,0,0,0]` → `[1520,0,0,0]`; CH2–CH4 không bị app thay đổi. Lease tự hết, không có ARM record. Trường `inputVerified` của app vẫn false; SITL không tự mở quyền phần cứng.
- Phiên **encoder trực tiếp trong test mô phỏng**: 65 RCIN records, chuyển baseline receiver `[1500,1500,1000,1500]` → `[1540,1460,1140,1540]` → baseline sau dừng override → giá trị tương ứng MANUAL_CONTROL. Các assert cũng kiểm tra sender GCS sai bị từ chối và kênh phụ không bị override. Đây không phải quyền phát hai joystick của app trên FC thật.

`reports/sitl-reference/dataflash-summary.json` chỉ tới BIN gốc và các thời điểm firmware ghi thay đổi. Hai file BIN đều không có ARM record. Không sử dụng việc vắng ARM record để chứng minh an toàn động lực; hai process chỉ là mô phỏng.

Timeout 0.5 giây được quan sát bằng trở lại receiver **mô phỏng hiện hữu**. Với receiverless, raw RCIN có thể giữ số cuối; các log này chưa cung cấp bằng chứng độc lập rằng override expired/failsafe đúng trên ESP32. Trạng thái đó phải tiếp tục ghi NOT VERIFIED cho phần cứng thật.

## Kết quả trên upstream base afeb9c49

Lượt riêng với profile modern và binary `afeb9c4941543d97f3e04fecf257b8f598f49a6a` cũng chạy **121 test, 0 failure, 0 error, 0 skipped**. XML giữ trong `reports/junit-target-afeb9c49/`; stdout ghi rõ hai thư mục evidence khác với lượt 4.6.3. Lint sau sửa lỗi tương thích API đạt 0 error, còn 1 warning ViewConstructor được ghi trong báo cáo tổng hợp; APK ứng dụng và APK instrumentation đều đã assemble. Instrumentation APK được build không đồng nghĩa đã chạy trên thiết bị.

DataFlash tại `reports/sitl-target/dataflash-summary.json` xác nhận:

- **BenchSession receiverless**: 15 override trong XML; 17 RCIN records, từ `[0,0,0,0]` tới `[1520,0,0,0]` tại TimeUS 5092129. Không có ARM record.
- **Encoder trực tiếp**: 72 RCIN records, baseline receiver → `[1540,1460,1140,1540]` → baseline sau dừng override → giá trị MANUAL_CONTROL. Các assert của GCS sender sai và kênh phụ vẫn đạt.
- Phiên receiverless có `ERR Subsys=5, ECode=1` tại TimeUS 5169598, là sự kiện radio failsafe trong lượt này. Không coi sự kiện đó là bằng chứng timeout override: nó xuất hiện gần lúc CH1 thử nghiệm đầu tiên, và CH3 vẫn 0 vì app không phát throttle. Cảnh báo/failsafe không bị tắt hoặc che.

Hai lượt cùng đạt đường RC input ở SITL và các kiểm tra khóa/timeout đã nêu. Chúng không đủ để nghiệm thu timeout receiverless, failsafe vật lý, nguồn ESC, Wi-Fi hoặc điện thoại thật. Những hạng mục đó tiếp tục NOT VERIFIED cho đến khi thu được bằng chứng từ thiết bị.
