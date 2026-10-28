# Điều kiện an toàn của RC bench

Đây là công cụ gửi RC override để quan sát kênh khi ArduPilot DISARMED. Tháo toàn bộ cánh quạt, ngắt nguồn công suất ESC và ngăn mọi nguồn ARM ngoài ứng dụng. Cần inhibit phù hợp ở phía firmware autopilot/rig thử; chỉ kiểm tra một bit DISARMED trên điện thoại là không đủ.

## RC yaw có thể là thao tác ARM

Không có MAV_CMD ARM/DISARM không loại bỏ rudder arming của ArduPilot. `ARMING_RUDDER` chưa được cung cấp/xác minh; một RC yaw cực trị kết hợp ga thấp có thể là thao tác ARM ngoài ý định người dùng. Do đó stage 1 **cấm full yaw endpoint** và cap CH4 ở ±25% quãng trim→endpoint, cả dual-stick lẫn từng kênh. Với endpoint mặc định 1100/1500/1900, CH4 chỉ được phát 1400–1600 µs. Không có cờ UI mở full yaw hoặc sửa tham số ArduPilot.

Cap này chỉ giảm nguy cơ thao tác cực trị đã biết, không chứng minh mọi cấu hình firmware đều không thể ARM. Muốn full yaw về sau phải có inhibit phía firmware được kiểm chứng, model đe dọa và test độc lập; trước đó vẫn tháo cánh, ngắt nguồn ESC và chặn nguồn ARM khác. CH1/CH2 có thể hết quãng đã hiệu chuẩn; CH3 dùng min→max chỉ khi mọi điều kiện DISARMED/fresh/deadman đạt và rig an toàn.

## Khóa phát RC

Chỉ phát khi đồng thời có kết nối TCP, target đã được khám phá từ HEARTBEAT ArduPilot component 1 hợp lệ CRC, HEARTBEAT còn mới và báo DISARMED, cấu hình kênh được xác minh trong phiên TCP hiện tại, ứng dụng ở foreground/focus và người dùng đang giữ deadman. ARMED, không có/không rõ trạng thái hoặc stale phải dừng mọi RC ngay khi được phát hiện, kể cả một kênh riêng lẻ. Không có nút bỏ qua khóa này.

HEARTBEAT phải mới dưới 1500 ms, RC_CHANNELS dưới 1200 ms. Deadman cần pulse tương tác dưới 200 ms; RC tối đa 20 Hz và GCS HEARTBEAT 1 Hz, không catch-up tick bị lỡ. Deadman phải gắn với tương tác thực, không chỉ một checkbox lưu trạng thái. Nhả tay, pointer cancel, app vào background, màn hình/route mất focus hoặc disconnect đều dừng phát; reconnect không tự khôi phục deadman/lệnh cũ. Các lần phát kiểm tra điều kiện hiện tại ngay trước thao tác gửi; không drain một queue chứa RC cũ sau khi gate đã đóng.

Không gửi ARM, DISARM, motor test, thay mode, `COMMAND_LONG` hoặc `PARAM_SET`. Gửi DISARM từ app cũng là command ngoài phạm vi của công cụ này, không được thêm như đường vòng “an toàn”. Dừng phát RC không thay thế nút ngắt nguồn vật lý.

## Khoảng trống quan sát HEARTBEAT

HEARTBEAT là snapshot. Khi heartbeat mới nhất báo DISARMED, autopilot vẫn có thể ARM trước heartbeat kế tiếp. CRC đúng và timestamp mới không loại bỏ được khoảng trống này. App chỉ phản ứng với dữ liệu đã nhận, không chứng minh trạng thái tức thời bên trong flight controller. Do đó phải chặn vận hành motor bằng phần cứng/quy trình và inhibit phía ArduPilot, độc lập với ứng dụng.

Không đặt tên kiểm thử này là “không thể ARM” nếu chỉ chứng minh app không có nút ARM. Receiver, script, Mission Planner hoặc client khác vẫn có thể thay đổi trạng thái. Không kiểm tra tình huống ARM thật khi ESC được cấp nguồn.

## Theo dõi phản hồi RC

Ở cấu hình không có receiver, chancount 0 được chấp nhận và app giữ đủ 18 raw channel; giá trị raw ban đầu 0 không tự khóa khả năng kiểm tra. Trước phát vẫn cần RC_CHANNELS mới, DISARMED mới, calibration được xác minh và deadman mới. App chỉ cho cửa sổ bootstrap ban đầu tối đa 750 ms để nhận feedback; không có phản hồi phù hợp thì mất lease, không retry.

Sau khi bắt đầu override, app so RC_CHANNELS thực với những RC đã gửi trong 500 ms gần nhất, dung sai ±25 µs cho các kênh đang điều khiển. Nếu 750 ms không có phản hồi phù hợp, thu hồi lease và cần thao tác bật/deadman mới. Đây là kiểm tra nhất quán có độ trễ, không phải ACK hay xác thực nguồn. Không phản hồi không được xem là autopilot đã nhận RC.

## Dừng phát và timeout

`RC_OVERRIDE_TIME=0.5` giây là tham số đã cung cấp. Nhả deadman khiến app dừng TX ngay; quyền override còn hiệu lực hoặc fallback sau đó do ArduPilot thực hiện theo cấu hình/phiên bản firmware. Cần đo thời gian thực, không cộng một con số lý thuyết rồi tuyên bố đã release. `RC_CHANNELS.radio_in` có thể giữ số cuối dù override đã hết hạn trong cấu hình receiverless; kiểm tra trạng thái failsafe/override thực ở autopilot, ví dụ bằng Mission Planner theo khả năng bridge, không chỉ chờ PWM hiển thị trở lại trung lập.

Không giả định lệnh neutral hay release đã tới autopilot khi TCP mất kết nối hoặc app treo. Không dựa vào một gói cuối cùng để bảo đảm dừng. Nếu app crash/OS kill, chỉ timeout/inhibit phía autopilot và phần cứng còn có hiệu lực. Điều kiện bench phải an toàn trong trường hợp app không thể gửi thêm bất cứ byte nào.

Các giá trị ignore trong RC_CHANNELS_OVERRIDE không đồng nghĩa release kênh. Chế độ từng kênh có thể để override cũ của kênh khác còn tồn tại tới timeout; quy trình phải nhả deadman, chờ timeout và đối chiếu telemetry trước khi đổi kênh kiểm tra. Không gán 1500 cho throttle CH3 để “trung lập”.

## Liên kết và bảo mật

TCP không cung cấp xác thực MAVLink. CRC chỉ kiểm tra toàn vẹn ngẫu nhiên, không ngăn giả mạo. Ứng dụng chưa xác minh MAVLink 2 signing và loại signed frame không được xác minh; không nâng frame unsigned thành nguồn “đáng tin cậy bảo mật”. Dùng mạng bench cô lập có kiểm soát, không xem Wi-Fi chung là trusted.

Sender system 255/component 190 cố định là định danh GCS của app, không phải credential. `MAV_OPTIONS=0` không được diễn giải thành “force/miễn kiểm tra identity”; cần đọc định nghĩa đúng firmware. Target system khám phá từ heartbeat, không suy ra từ SSID/IP.

Nếu bridge không công bố hỗ trợ nhiều client TCP, không giả định Mission Planner và app cùng kết nối được. Trước test cần xác minh bridge chuyển tiếp HEARTBEAT/RC_CHANNELS đúng hướng và client khác không can thiệp. Không dùng thêm client để ARM trong lúc app đang giữ deadman.

## Điều kiện dừng nghiệm thu

Nếu chưa xác minh endpoint RC2/3/4, không phát kênh đó hoặc chế độ full. Nếu không có heartbeat phù hợp/stale, signed chưa xác minh, telemetry không tới hoặc RC_CHANNELS không khớp, dừng bench để chẩn đoán. Không đổi tham số, cố định state giả hoặc lặp lại RC để ép autopilot chấp nhận.
