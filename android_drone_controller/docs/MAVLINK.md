# MAVLink 2 và bằng chứng điều khiển

Ứng dụng dùng TCP như một luồng byte. Một lần đọc có thể chứa một phần frame hoặc nhiều frame; parser giữ bộ đệm hữu hạn 280 byte, kiểm tra X25 CRC và CRC_EXTRA theo message trước khi đưa dữ liệu cho engine. Frame MAVLink 1 được bỏ qua theo toàn bộ chiều dài. Frame không biết ID không được báo là telemetry đã kiểm tra CRC. Signed MAVLink 2 bị từ chối vì phiên bản này chưa có bộ xác minh chữ ký và chống phát lại. CRC không xác thực nguồn gửi.

## Định dạng và nguồn kiểm chứng

Header MAVLink 2 bắt đầu bằng `FD`, payload length, incompat/compat flags, sequence, system ID, component ID và message ID 24 bit little endian. CRC gồm header từ length đến message ID, payload và CRC_EXTRA; không gồm byte magic. Các byte 0 cuối payload được cắt, phần thiếu được điền 0 khi giải mã. Sequence là 8 bit, quay vòng 255 → 0; sequence gap chỉ là dấu hiệu thiếu frame trong tập message quan sát, không phải số đo mất gói Wi-Fi tuyệt đối.

Parser/encoder kế thừa phần đã có kiểm thử độc lập ở RC Bench; tập message bổ sung được so sánh với `pymavlink==2.4.49`, dialect `ardupilotmega` (bao gồm `common`). `reference_extended.py` tái tạo 12 frame tham chiếu không dùng encoder Kotlin. `ExtendedProtocolTest` so sánh byte phát, đơn vị/offset nhận và mọi vị trí phân mảnh của từng frame tham chiếu. `reference_vectors.py` và fixture hiện có tiếp tục kiểm tra HEARTBEAT, SYS_STATUS, ATTITUDE, GPS_RAW_INT, RC_CHANNELS, STATUSTEXT và chữ ký. Fixture SYS_STATUS 43 byte dùng định nghĩa common mới hơn; xem script tham chiếu riêng đi kèm. Việc test parser không chứng minh firmware ESP32 đang chạy cùng dialect hoặc thực sự phát các message đó.

Tập nhận: HEARTBEAT(0), SYS_STATUS(1), PARAM_VALUE(22), GPS_RAW_INT(24), SCALED_PRESSURE(29), ATTITUDE(30), GLOBAL_POSITION_INT(33), SERVO_OUTPUT_RAW(36), RC_CHANNELS(65), COMMAND_ACK(77), BATTERY_STATUS(147), AUTOPILOT_VERSION(148), EKF_STATUS_REPORT(193), EXTENDED_SYS_STATE(245), STATUSTEXT(253).

## Yêu cầu telemetry có giới hạn

Encoder chỉ cho tạo COMMAND_LONG lệnh `MAV_CMD_SET_MESSAGE_INTERVAL=511` và `MAV_CMD_REQUEST_MESSAGE=512` với danh sách message đọc cho phép. Khoảng phát phải từ 200.000 đến 10.000.000 µs. Bản0.3 bổ sung hai encoder cụ thể: ARM/DISARM COMMAND_LONG400 với param2=0 (không force), và flight mode COMMAND_LONG176 với base-mode custom-enabled. Không có encoder COMMAND_LONG tùy ý, motor test hoặc PARAM_SET. Quyền phát lệnh nằm trong LiveControlRuntime và được kiểm tra trước write. PARAM_REQUEST_READ chỉ đọc tên tham số ASCII tối đa 16 ký tự. Transport giới hạn số yêu cầu trong mỗi phiên; nhận ACK không có nghĩa telemetry yêu cầu đã đến.

COMMAND_ACK gồm command, result, progress, result_param2 và địa chỉ GCS đích nếu extension tồn tại. `ACCEPTED=0`, `TEMPORARILY_REJECTED=1`, `DENIED=2`, `UNSUPPORTED=3`, `FAILED=4`, `IN_PROGRESS=5`, `CANCELLED=6`. Thiếu ACK là timeout, không được diễn giải là chấp nhận. Với các yêu cầu cùng command 511, ACK không mang message ID được yêu cầu; vì thế phải tuần tự hóa và không suy diễn ACK trễ là bằng chứng chắc chắn cho một stream cụ thể. Đo message nhận thực tế riêng.

## RC_CHANNELS_OVERRIDE và MANUAL_CONTROL

Phương thức phát đang chọn là RC_CHANNELS_OVERRIDE(70); MANUAL_CONTROL(69) chỉ có encoder và test offline để so sánh giao thức, không được transport tự chuyển qua hoặc phát đồng thời.

RC_CHANNELS_OVERRIDE mang PWM theo RC calibration. Kênh 1–8: `65535=ignore`, `0=release`; kênh 9–18: `0` hoặc `65535=ignore`, `65534=release`. Encoder ứng dụng chỉ chấp nhận 4 giá trị kênh đầu từ 800–2200 µs hoặc 65535, luôn ignore CH5–CH8 bằng 65535 và CH9–CH18 bằng 0. Giá trị này là giới hạn framing, không phải quyền điều khiển: engine áp đặt giới hạn bench chặt hơn trước mỗi lần ghi. Không gửi release tự động hoặc dựa vào neutral để bảo đảm an toàn. Khi dừng, thu hồi quyền và ngừng phát; cần xác minh timeout FC độc lập.

MANUAL_CONTROL: x=pitch, y=roll, z=throttle, r=yaw. Encoder đánh giá dùng x/y/r từ −1000 đến 1000, z từ 0 đến 1000, không có nút hoặc extension chủ động. Mapping dấu theo firmware ArduPilot thực tế vẫn cần kiểm chứng SITL/thiết bị; frame byte đúng không chứng minh trục đúng trên drone.

GCS mặc định system ID 255/component ID 190. Target được lấy từ HEARTBEAT autopilot phù hợp, không broadcast. Các tham số MAV_GCS_SYSID, MAV_OPTIONS, RC_OPTIONS, RC_OVERRIDE_TIME, RCx_MIN/MAX/TRIM và cấu hình arming/failsafe phải được đọc theo đúng firmware. Không sửa tham số để vượt khóa.

## Giải thích dữ liệu và giới hạn

- SYS_STATUS: millivolt 65535 là không biết; dòng −1 centiamp và phần trăm −1 là không biết. Không biến thành 0 hoặc tự suy ra thời gian bay. INA219 của rig chỉ được người dùng xác minh điện áp, chưa xác minh dòng thực.
- GPS_RAW_INT: lat/lon độ ×10⁷, altitude mm; eph/epv là HDOP/VDOP ×100, không phải sai số centimet. Velocity 65535 và số vệ tinh 255 là không biết.
- GLOBAL_POSITION_INT: vận tốc cm/s, trục z dương xuống; heading 65535 là không biết. Relative altitude không được dán nhãn là độ cao khí áp thuần.
- SCALED_PRESSURE: áp suất hPa, nhiệt độ °C ×100. Chưa có áp suất tham chiếu thì không tự chuyển sang độ cao khí áp.
- BATTERY_STATUS: điện áp từng ô hoặc tổng tùy nguồn; giữ nguyên 65535 và 0 sentinel. Không cộng nhầm giá trị không biết hoặc gọi số đo pin là ước lượng bay đã hiệu chuẩn.
- SERVO_OUTPUT_RAW là đầu ra FC, không phải bằng chứng động cơ/ESC thật hoặc RC input đã áp dụng.
- RC_CHANNELS có thể chancount=0 khi không có receiver. Giá trị khớp TX chỉ là tương quan quan sát; RC_CHANNELS không phải ACK của override và có thể giữ giá trị sau expiry.
- AUTOPILOT_VERSION báo số phiên bản/revision từ FC; mã trong repository prototype không tự chứng minh đúng firmware đang chạy.

Bốn mức bằng chứng phải tách riêng: ứng dụng tạo lệnh → socket ghi đủ byte → FC nhận/chấp nhận → RC input áp dụng. Chỉ trace/log độc lập của ArduPilot hoặc kiểm tra tương đương theo đúng firmware mới có thể nâng mức bằng chứng. Không có timeout/lệnh dừng qua Wi-Fi nào thay thế được cơ chế dừng độc lập khi mất chính đường Wi-Fi.
