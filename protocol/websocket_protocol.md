# WebSocket JSON v1

ESP32 Access Point: `192.168.4.1`. Endpoint điều khiển là `ws://192.168.4.1:81/ws`; HTTP port 80 chỉ cung cấp trạng thái. Client phải yêu cầu đúng endpoint, không giả định HTTP và WebSocket chung port. Mỗi WebSocket frame chứa một JSON object hoàn chỉnh, `v:1`, tối đa 1.400 byte; bản hiện tại từ chối binary và fragment. Firmware giới hạn kích thước frame và tốc độ xử lý; không dùng JSON cho vòng control nội bộ.

`ws://` không mã hóa end-to-end. WPA2 AP và token giảm truy cập vô ý nhưng không phải kênh chống tấn công chủ động hoàn chỉnh. Không dùng mật khẩu/token thật trong source, log hoặc ví dụ. Firmware không mở AP nếu thiếu cấu hình cục bộ hợp lệ.

## Xác thực, đồng hồ và quyền điều khiển

Kết nối socket xong chưa có quyền điều khiển. Gửi:

```json
{"v":1,"type":"auth","token":"TOKEN_NHAP_CUC_BO"}
```

Auth thành công trả `type:"auth"`, `session` ngẫu nhiên và `now_ms` là đồng hồ monotonic của ESP32. App tính offset với đồng hồ monotonic cục bộ để xây `sent_ms`; không dùng thời gian UTC/wall clock của điện thoại. Mọi lệnh tiếp theo mang `session`, `seq` và `sent_ms`. Client chỉ có một session điều khiển; session mới không được giành quyền khi đang armed. Mất socket thu hồi lease của session. Không tái sử dụng session sau reboot hoặc reconnect.

```json
{"v":1,"type":"control","session":"NONCE_TU_AUTH","seq":1,"sent_ms":12340,"throttle":0.0,"roll":0.0,"pitch":0.0,"yaw":0.0,"mode":"rate"}
```

- `seq`: số nguyên tăng nghiêm ngặt trong session; không wrap. Tạo session mới trước khi hết miền số. Trùng/lùi thứ tự không cập nhật thời hạn.
- `sent_ms`: thời điểm gửi theo clock ESP32 đã đồng bộ, tối đa 250 ms tuổi; timestamp tương lai hoặc ngoài miền hợp lệ bị từ chối.
- Tối đa 100 message trong cửa sổ cố định 1 giây cho mỗi client, bao gồm auth/packet lỗi; ứng dụng hiện gửi control khoảng 20 Hz khi còn tương tác hợp lệ. Gửi nhanh hơn không cải thiện vòng điều khiển.
- Control timeout 300 ms tính từ control hợp lệ cuối. Heartbeat không làm mới control timeout.
- Lease heartbeat 500 ms; client gửi heartbeat ngắn hơn giới hạn này và ngừng/reconnect khi lease hết.
- Packet sai token/session/version, thiếu/trái loại trường, NaN/Inf, ngoài dải hoặc mode không hỗ trợ bị từ chối; không tự kẹp một lệnh mạng sai thành lệnh điều khiển hợp lệ.

Tuổi packet có jitter Wi-Fi và sai số đồng bộ; `sent_ms` không phải phép đo latency một chiều chính xác nếu chưa có đồng bộ/RTT đúng. Không dựa vào socket đang connected để ARM.

## Các lệnh

Mỗi ví dụ dưới đây phải bổ sung envelope `v`, `session`, `seq`, `sent_ms` như ví dụ control.

| `type` | Payload | Điều kiện / ý nghĩa |
|---|---|---|
| `control` | `throttle` [0,1]; `roll`,`pitch`,`yaw` [-1,1]; `mode`: `rate` hoặc `angle` | Throttle normalized; roll/pitch/yaw đổi sang giới hạn vật lý đã cấu hình trong firmware |
| `heartbeat` | Không | Gia hạn lease, không giữ throttle sống |
| `arm` | `confirm:true` | Thao tác riêng; pre-arm checks và throttle thấp; không mở khóa hardware |
| `disarm` | Không | Reset tích phân và khóa đầu ra theo chính sách bench |
| `estop` | Không | Dừng khẩn có chốt; không tự phục hồi khi nhận heartbeat |
| `telemetry` | Không | Yêu cầu snapshot hiện tại, không làm mới control |
| `pid` | `gains` object như dưới | Chỉ DISARMED; cần xác nhận người dùng trước gửi |
| `calibrate` | `target` một giá trị trong bảng dưới | Chỉ DISARMED; thu mẫu không đồng nghĩa kết quả đã hợp lệ |

```json
{
  "v":1,"type":"pid","session":"NONCE_TU_AUTH","seq":20,"sent_ms":13000,
  "gains":{
    "roll":{"p":0.1,"i":0.0,"d":0.0},
    "pitch":{"p":0.1,"i":0.0,"d":0.0},
    "yaw":{"p":0.1,"i":0.0,"d":0.0},
    "attitude":{"p":1.0,"i":0.0,"d":0.0}
  }
}
```

Các số ví dụ không phải tuning cho động cơ/khung thực tế. PID RAM chỉ thay đổi sau khi validate toàn bộ cấu hình và được reset về mặc định khi reboot. Bản hiện tại chưa lưu PID vào NVS; `calibrate/save` chỉ lưu calibration cảm biến. Không ghi flash mỗi packet.

| Calibration target | Thao tác |
|---|---|
| `gyro` | Khung tĩnh, thu bias và kiểm tra độ ổn định |
| `accel_xp`, `accel_xn` | Mặt +X/-X hướng lên, thu dữ liệu từng mặt |
| `accel_yp`, `accel_yn` | Mặt +Y/-Y hướng lên |
| `accel_zp`, `accel_zn` | Mặt +Z/-Z hướng lên |
| `mag` | Quay đủ ba trục; kiểm tra coverage/span |
| `baro` | Đặt áp suất tham chiếu tại vị trí hiện tại |
| `save` | Lưu cấu hình hợp lệ có chủ ý khi disarmed |

Calibration không ghi đè âm thầm một bộ hiệu chuẩn hợp lệ khi thu mẫu lỗi. Đọc telemetry/log để xác nhận kết quả. Lệnh được nhận không đồng nghĩa cảm biến đã hiệu chuẩn thành công.

## Telemetry

Firmware gửi JSON snapshot tốc độ thấp. Trường đo không hợp lệ/quá hạn phải kèm health/freshness; client hiển thị thiếu dữ liệu, không thay bằng giá trị giả. Ví dụ cấu trúc:

```json
{
  "v":1,"type":"telemetry","now_ms":15000,
  "firmware":"development","state":"DISARMED","mode":"rate","mock":true,"failsafe":"",
  "attitude":{"roll":0.0,"pitch":0.0,"yaw":0.0},
  "rates":{"x":0.0,"y":0.0,"z":0.0},
  "battery":{"voltage":0.0,"current":0.0,"bus_voltage":0.0,"shunt_voltage":0.0},
  "gps":{"lat":0.0,"lon":0.0,"altitude":null,"satellites":0,"fix":0,"hdop":0.0,"speed":null,"altitude_valid":false,"speed_valid":false,"fresh":false,"home_lat":null,"home_lon":null},
  "altitude":0.0,
  "sensors":{"imu":false,"bmp":false,"ina":false},
  "diagnostics":{"hz":0.0,"jitter_us":0,"misses":0,"packet_loss":0,"latency_ms":0}
}
```

Attitude là độ; rates rad/s; điện áp V, dòng A; cao m; GPS lat/lon độ, tốc độ m/s. `gps.fix` trong parser NMEA thể hiện chất lượng fix do parser hỗ trợ; không khẳng định phân loại UBX NAV-PVT nếu không có parser đó. `gps.altitude_valid` và `gps.speed_valid` kiểm tra freshness riêng của hai trường này. Khi chưa hợp lệ, altitude/speed bị bỏ qua hoặc null; client phải coi là thiếu dữ liệu, không tái sử dụng số đo cũ chỉ vì lat/lon vẫn fresh. `home_lat/home_lon` chỉ hợp lệ sau kiểm tra fix/freshness. GPS coordinate trail hoạt động offline, không phụ thuộc map tile Internet.

`diagnostics.packet_loss` hiện là tổng gap của sequence đã được chấp nhận; có thể bao gồm message đã bị từ chối, không phải phép đo mất gói RF. RSSI AP của chính ESP32 không phải đo trực tiếp chất lượng đường điều khiển tới điện thoại. Nếu chưa có phép đo, hiển thị không có dữ liệu. `latency_ms` là tuổi lệnh theo clock ESP32 ước tính từ client, không phải RTT. `jitter_us` là độ lệch chu kỳ cao nhất được ghi nhận; telemetry hiện 20 Hz không cho biết vòng control đạt 250 Hz.

## Lỗi và reconnect

Ack nhận vào queue có dạng `{"v":1,"type":"ack","reason":"control","now_ms":15000}`; error dùng cùng envelope với `type:"error"`, `reason` là mã lỗi. Ack xác nhận validator/queue, không xác nhận hiệu chuẩn hoàn tất hay ARM thành công; xem state/command_result/sensors.calibration trong telemetry. Client xử lý response error bằng thông báo, không lặp vô hạn ARM hoặc calibration. Reconnect phải auth và bắt đầu control từ throttle thấp, không tự ARM hay phát lại lệnh queue cũ. Khi nhả cả hai joystick/app background, client ngừng control để firmware timeout; throttle chốt được giữ khi vẫn còn ít nhất một joystick contact, các trục yaw/roll/pitch được nhả sẽ về giữa. Emergency stop vẫn có nút riêng. Log chỉ giữ metadata lỗi, không ghi token.

Giao thức nhị phân tương lai cần version, message type, payload length, session/sequence/timestamp và checksum; phải định nghĩa byte order, đơn vị và chiến lược tương thích. Chưa có giao thức nhị phân trong bản này.

Giới hạn cấu hình PID trên wire: P/I trong [0,20], D trong [0,10], tất cả phải là số hữu hạn. Vòng attitude có giới hạn tích phân 0,5 rad/s; giới hạn output tốc độ là 3 rad/s. Các giá trị này là giới hạn bench, chưa được tuning cho khung thật. Telemetry bổ sung `prearm` gồm imu/calibrated/authenticated/hardware/battery/timing để chẩn đoán điều kiện khóa ARM.
