# Giao thức MAVLink 2 qua TCP

## Transport và định danh

Endpoint mặc định `192.168.4.1:5760`, TCP client từ Android tới bridge Wi-Fi/autopilot. App chỉ nhận IPv4 literal để tránh DNS không có giới hạn thời gian trong actor; không hỗ trợ hostname hoặc IPv6 trong bản này. TCP là byte stream: một lần đọc có thể chứa nửa frame hoặc nhiều frame; parser phải giữ buffer, kiểm tra length, message ID, CRC extra và resync sau frame lỗi. Không xem ranh giới `read()` là ranh giới MAVLink.

App gửi MAVLink 2 (`0xFD`) từ system ID 255, component ID 190. Target chỉ được chọn từ HEARTBEAT của `MAV_AUTOPILOT_ARDUPILOTMEGA` với component ID 1; system ID target lấy từ header. Không giả định target luôn là 1 hoặc peer TCP chính là autopilot.

CRC MAVLink/X.25 cùng CRC extra của message kiểm tra lỗi truyền, không xác thực nguồn. Frame signed MAVLink 2 chưa xác minh bị từ chối. Chưa có signing secret/provisioning/signature verification trong ứng dụng; unsigned chỉ được dùng theo giả định mạng bench được kiểm soát, không coi là transport an toàn trước kẻ tấn công.

## Những message được dùng

- HEARTBEAT của autopilot: khám phá target, trạng thái ARMED từ `MAV_MODE_FLAG_SAFETY_ARMED` và freshness. HEARTBEAT GCS nếu được app phát chỉ công bố định danh, không phải lệnh ARM.
- RC_CHANNELS_OVERRIDE: message ID 70, lệnh duy nhất tác động tới kênh RC của công cụ này. Không đồng thời gửi COMMAND_LONG, SET_MODE, PARAM_SET hay motor test.
- RC_CHANNELS: message ID 65, giá trị quan sát autopilot báo về để đối chiếu. Đây không phải ACK dành riêng cho override của app; cần đọc timestamp/freshness và nguồn message.

Nếu firmware không stream RC_CHANNELS, trạng thái telemetry phải thể hiện chưa có dữ liệu. Không tự tuyên bố app gửi thành công dựa trên giá trị local. Việc yêu cầu stream/đổi rate qua command chưa nằm trong bề mặt gửi đã cho phép; cấu hình telemetry bằng công cụ ngoài theo quy trình bench.

## Dialect đã đối chiếu

Parser hỗ trợ một tập message Common đã audit, không tuyên bố hỗ trợ mọi dialect hoặc extension tương lai. Định nghĩa được đối chiếu với `mavlink/mavlink` commit `9324a919111e64449ee2e49b839f75730e733da3`; SYS_STATUS cũng được đối chiếu với `ArduPilot/mavlink` commit `e03dad40efb3a4009ad6d78de5e55997139ac2f3`. Golden frame được sinh độc lập bằng pymavgen/pymavlink từ XML chính thức được pin kèm provenance/checksum trong fixture, không dùng chính encoder app để tạo đáp án test.

| Message ID | Tên | Payload tối đa đã audit | CRC extra |
|---|---|---|---|
| 0 | HEARTBEAT | 9 byte | 50 |
| 1 | SYS_STATUS | 43 byte, base cũ 31 | 124 |
| 24 | GPS_RAW_INT | 52 byte | 24 |
| 30 | ATTITUDE | 28 byte | 39 |
| 65 | RC_CHANNELS | 42 byte | 118 |
| 70 | RC_CHANNELS_OVERRIDE | 38 byte | 124 |
| 253 | STATUSTEXT | 54 byte | 83 |

SYS_STATUS hiện có ba extension uint32 sensor mask sau base 31 byte; decoder vẫn xử lý bản base và MAVLink 2 trailing-zero truncation hợp lệ. Message ngoài subset hoặc vượt độ dài đã biết không được trình bày là telemetry CRC-verified. Không tăng max tùy tiện để “chấp nhận mọi phiên bản”.

## RC_CHANNELS_OVERRIDE và giá trị ignore

| Vùng kênh | Đang điều khiển | Giá trị ignore | Giá trị release theo giao thức |
|---|---|---|---|
| CH1–CH8 | PWM µs đã xác minh | 65535 (`UINT16_MAX`) | 0 |
| CH9–CH18, extension MAVLink 2 | PWM µs đã xác minh | 0 hoặc 65535 | 65534 |

Ứng dụng hiện chỉ điều khiển CH1–CH4, để CH5–CH8 là 65535 và CH9–CH18 là 0. Trong chế độ kiểm tra từng kênh, chỉ kênh được chọn trong CH1–CH4 nhận PWM; ba kênh còn lại cũng là 65535. Không nhầm 0 của extension với 0 của CH1–CH8.

Ignore không xóa override đã gửi trước đó. Dừng TX/deadman và chờ `RC_OVERRIDE_TIME` của autopilot là cơ chế chính của bench; không phụ thuộc việc packet release cuối cùng đã tới. Nếu đổi kênh test, nhả deadman và xác minh timeout trước khi bắt đầu kênh khác.

## Đơn vị và endpoint

Dữ liệu RC dùng µs, không phải góc attitude hoặc phần trăm motor. RC1 đã cung cấp min/max/trim 1100/1900/1500. RC2/RC3/RC4 dùng cùng min/max/trim chỉ là provisional, cần xác minh riêng; không tự ghi vào autopilot. CH4/yaw hiện bị cap ±25% từ trim đến mỗi endpoint, không hỗ trợ full yaw endpoint vì `ARMING_RUDDER` chưa xác minh; default chỉ 1400–1600 µs, áp dụng cả từng kênh. Throttle CH3 có vị trí thấp nhất 1100 µs; trim/giữa dải không được dùng làm mức ga nghỉ.

Tần số RC tối đa 20 Hz, GCS HEARTBEAT 1 Hz. HEARTBEAT autopilot phải mới dưới 1500 ms; RC_CHANNELS dưới 1200 ms; pulse deadman dưới 200 ms. Những giá trị này cần đối chiếu build và đo trong log. Không suy tần số từ tốc độ cập nhật UI. Khi nhận ARMED hoặc gate đóng, sender dừng ngay lần gửi tiếp theo theo trạng thái mới; parser/UI không được giữ một queue override cũ để phát bù.

## RC_CHANNELS chancount khi không có receiver

`RC_OPTIONS=33` bỏ qua RC receiver; khi không có receiver vật lý, `RC_CHANNELS.chancount` có thể bằng 0 dù các trường raw channel đã phản ánh override. Không dùng `chancount` để cắt bỏ 18 raw channel hoặc kết luận override không thể hoạt động.

Đối chiếu nguồn ArduPilot commit `7246115bd69dd038d204916b6184c6fe3939cfef`: [GCS_Common.cpp dòng 2173–2204](https://github.com/ArduPilot/ardupilot/blob/7246115bd69dd038d204916b6184c6fe3939cfef/libraries/GCS_MAVLink/GCS_Common.cpp#L2173-L2204) lấy chancount theo HAL receiver count nhưng phát các `RC_Channel.radio_in`; [RC_Channels.cpp dòng 76–85](https://github.com/ArduPilot/ardupilot/blob/7246115bd69dd038d204916b6184c6fe3939cfef/libraries/RC_Channel/RC_Channels.cpp#L76-L85) và [160–163](https://github.com/ArduPilot/ardupilot/blob/7246115bd69dd038d204916b6184c6fe3939cfef/libraries/RC_Channel/RC_Channels.cpp#L160-L163) xử lý/lưu đầu vào có override. Đây là bằng chứng theo nguồn tại commit đã đọc, chưa xác nhận firmware được nạp trên thiết bị của người dùng.

Engine chấp nhận chancount 0–18 và giữ đủ 18 raw value để đối chiếu; 19/255 là count không hợp lệ và đóng gate. Raw value ban đầu có thể là 0 khi receiverless; không dùng giá trị 0 đó để khóa vĩnh viễn khả năng bắt đầu kiểm thử. Vẫn bắt buộc RC_CHANNELS mới, HEARTBEAT DISARMED mới, calibration xác minh trong phiên và deadman mới. Lần đầu được thử override trong cửa sổ bootstrap hữu hạn 750 ms; nếu không có raw selected channel khớp feedback thì thu hồi lease, không tự thử lại.

## Đối chiếu telemetry

Màn hình phải tách rõ RC đã gửi với RC_CHANNELS nhận được. RC_CHANNELS chỉ là quan sát do autopilot báo: thời điểm nhận không chứng minh packet override cụ thể đã được áp dụng, và giá trị có thể chịu nguồn RC khác/chính sách firmware. Không tự tính một ACK từ việc local TX tăng counter. Engine chấp nhận phản hồi khớp một setpoint đã gửi trong 500 ms gần nhất với sai số ±25 µs của kênh được điều khiển; 750 ms không có phản hồi khớp sẽ thu hồi quyền gửi, không auto retry.

Sau timeout override, `radio_in`/RC_CHANNELS có thể tiếp tục giữ giá trị cuối khi không có đầu vào thay thế. Vì vậy không suy expiry từ việc số PWM trở về trung lập, và cũng không suy override vẫn đang hiệu lực chỉ vì PWM không đổi. Cần quan sát trạng thái failsafe/quyền override/timing của đúng firmware qua Mission Planner hoặc công cụ bench phù hợp; không chỉ nhìn giá trị RC.

Sau disconnect/reconnect mọi cờ calibration verified đều false, cần xác nhận thủ công từng kênh và heartbeat mới, target mới được xác minh và deadman mới. Không dùng target/state cũ từ phiên trước. Không chắc bridge hỗ trợ nhiều TCP client; thử Mission Planner song song là kiểm tra khả năng bridge riêng, không phải tính năng mặc định của MAVLink.
