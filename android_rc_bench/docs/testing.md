# Kiểm thử phần mềm và bench

Các test phần mềm chạy trên host không chứng minh đã nối flight controller thật. Ghi commit/snapshot, lệnh, runner, exit code và số test thực sự chạy; tách PASS/FAIL/NOT RUN. Build APK thành công chỉ xác nhận khả năng biên dịch/đóng gói.

## Trên Windows hoặc CI

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Dùng JDK 17, Android SDK Platform 36 và Gradle wrapper 8.12. Nếu SDK path không tự nhận, cấu hình `local.properties` trên máy đó qua Android Studio; không commit đường dẫn SDK của người khác. Không disable kiểm tra checksum/TLS khi download Gradle/dependency.

Unit test cần receiverless count 0/raw ban đầu 0 bootstrap hữu hạn, giữ đủ 18 raw channel thay vì cắt theo chancount, count 19/255 bị từ chối và timeout khi không feedback. Unit test cần xác minh yaw cap trong mọi mode, không có đường vòng full RC4 endpoint, và RC3 low không ánh xạ trim. Unit test cần kiểm tra parser TCP phân mảnh/gộp frame, CRC lỗi/resync, unsigned/signed handling, target ArduPilot component 1, trạng thái heartbeat/freshness và encoder RC override đúng giá trị ignore mỗi vùng kênh. Gate test cần kiểm tra deadman/focus/background/disconnect/ARMED/stale, không phát lệnh cũ sau khóa, endpoint chưa xác minh bị từ chối. App không được phát command ngoài whitelist.

## Ma trận bench với autopilot thật

Điều kiện chung: không cánh, nguồn ESC ngắt, inhibit phía autopilot được xác minh và có cách ngắt nguồn vật lý. Không ARM flight controller có thể vận hành động cơ chỉ để test màn hình.

| Mục | Thao tác | Bằng chứng yêu cầu |
|---|---|---|
| TCP | Điện thoại nối AP, connect endpoint | Có byte stream đúng, lỗi rõ khi sai IP/cổng; không hiển thị connected đồng nghĩa target hợp lệ |
| Target | Quan sát heartbeat ArduPilot component 1 | Header system/component, autopilot type và tuổi heartbeat đúng |
| CRC/parser | Feed frame chia nhỏ, gộp và lỗi | Chỉ frame hợp lệ làm mới state; sai CRC không chuyển DISARMED |
| Signing | Feed signed frame không có verifier | Bị từ chối, không xem là nguồn secure |
| Endpoint | Đọc lại RC1–RC4 trên đúng autopilot | RC1 1100/1500/1900; cả RC1–4 phải xác nhận lại theo phiên; RC2/3/4 đối chiếu riêng; CH3 low 1100 |
| Một kênh | Giữ deadman test từng CH1–4 | Chỉ kênh chọn có PWM; kênh còn lại ignore; CH4 chỉ ±25% span (mặc định 1400–1600), không test endpoint |
| Full CH1–4 | Chỉ sau mọi endpoint được xác minh | CH5–8=65535, CH9–18=0; CH3 không tự về 1500; CH4 bị cap như từng kênh |
| Receiverless / chancount | Peer mô phỏng và bench RC_OPTIONS=33 không receiver | Count 0 hợp lệ, giữ đủ 18 raw channel; count 19/255 đóng gate; raw ban đầu 0 chỉ được bootstrap 750 ms rồi cần feedback khớp |
| RC telemetry | Quan sát RC_CHANNELS và local sent tách biệt | Có timestamp/source; không giả ACK khi chỉ TX thành công |
| Feedback mismatch | Dùng peer mô phỏng phát RC_CHANNELS không khớp | Quá 750 ms thu hồi lease; phản hồi có thể khớp lệnh trong 500 ms, dung sai 25 µs; không tự retry |
| Nhả deadman | Nhả tay khi đang gửi bench | TX dừng, timeout/fallback autopilot đo thực tế; không cần packet cuối |
| Wi-Fi không Internet | Điện thoại bật cả AP bench và mobile data | Socket gắn đúng Wi-Fi; mất Wi-Fi ngắt phiên, không tự reconnect hoặc lén dùng mobile data |
| CSV | Ghi session và đọc file qua adb run-as của APK debug | Có TX/RX/state/reason; báo dropped/error, không block control gate |
| Background/focus | Home, đổi app, khóa màn hình, mất focus | Dừng RC ngay; resume không tự khôi phục deadman/throttle |
| Disconnect | Rút mạng/đóng socket/bridge reset | Dừng TX; sau reconnect cần heartbeat/deadman mới |
| Unknown/stale | Ngừng heartbeat, sai component/type/CRC | Không phát RC dù socket còn mở |
| ARMED gate | Inject mô phỏng hoặc rig inhibit đã xác minh | Nhận ARMED dừng mọi RC; không thử motor thật |
| Client khác | Mission Planner nếu bridge hỗ trợ | Ghi số client/routes; không giả định bridge fan-out |
| Failsafe/timeout | Dừng TX không gửi release; sau đó ngừng GCS heartbeat riêng | Quan sát trạng thái override/failsafe đúng firmware, không yêu cầu raw RC tự về giữa; radio_in có thể giữ số cũ; không đổi tham số để làm test qua |

Khi đổi kênh test, nhả deadman và chờ override cũ hết hạn trước khi bắt đầu kênh mới. Ignore không release trạng thái override trước đó. Ghi cả RC đã gửi và RC_CHANNELS nhận trong log.

## Điều không thể chứng minh bằng app

Test HEARTBEAT gate chỉ chứng minh hành vi theo frame đã nhận; không loại bỏ ARM xảy ra giữa hai heartbeat. Không có test Android nào thay inhibit firmware, cánh đã tháo và nguồn ESC ngắt. CRC không kiểm thử xác thực bảo mật. Không có flight test trong phạm vi dự án này.
