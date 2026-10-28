# Snapshot tham số và điểm phải xác minh

App không đọc một snapshot cục bộ rồi giả định đó là trạng thái autopilot hiện tại. Trước bench, đọc lại tham số trực tiếp bằng công cụ cấu hình phù hợp và lưu phiên bản firmware/build. **Ứng dụng không gửi PARAM_SET hoặc thay đổi tham số để vượt kiểm tra.** Các số dưới đây là dữ liệu yêu cầu đã cung cấp, không phải kết quả đọc thiết bị trong cloud.

| Tham số / dữ liệu | Giá trị đã cung cấp | Cách dùng trong bench |
|---|---|---|
| TCP | 192.168.4.1:5760 | Xác minh bridge chạy TCP đúng cổng và chuyển MAVLink 2 |
| Sender app | system 255, component 190 | GCS identity, không phải bí mật xác thực |
| Target | Từ HEARTBEAT ArduPilot component 1 | Không cố định system ID 1 |
| RC1_MIN / RC1_MAX / RC1_TRIM | 1100 / 1900 / 1500 | Bộ endpoint đã được cung cấp cho RC1 |
| RC2/RC3/RC4 endpoint | Provisional 1100 / 1900 / 1500 | Cần người dùng xác minh từng kênh; không tự coi giống RC1 |
| CH4/yaw stage 1 | ±25% trim→endpoint; default 1400–1600 | Không full endpoint vì ARMING_RUDDER chưa xác minh, không UI bypass |
| Throttle CH3 thấp nhất | 1100 | Không dùng trim 1500 làm neutral throttle |
| RC_OVERRIDE_TIME | 0.5 giây | Cần kiểm tra timeout thực tế khi TX dừng |
| RC_OPTIONS | 33 | Bit 0 và bit 5; xem giải thích dưới |
| MAV_OPTIONS | 0 | Không suy ra bypass identity hay force quyền RC |
| FS_THR_ENABLE | 1 | Giữ nguyên; đọc định nghĩa/hành vi đúng phiên bản firmware |
| FS_GCS_ENABLE | 5 | Giữ nguyên; đọc định nghĩa/hành vi đúng phiên bản firmware |

Tất cả cờ xác minh kênh mặc định false và được xóa khi TCP kết nối mới. Các endpoint app cho nhập nằm trong 900–2100 µs và phải có min < trim < max; dải cho nhập không phải bằng chứng thiết bị hỗ trợ dải đó. CH3 ánh xạ throttle từ min đến max, không dùng trim làm vị trí nghỉ. CH4 chỉ dùng ±25% mỗi bên trim dù endpoint đã xác minh; muốn thử full yaw phải có inhibit firmware được xác minh riêng trong một giai đoạn sau.

## RC_OPTIONS = 33

33 = 1 + 32, tức bit 0 và bit 5 được bật. Theo định nghĩa ArduCopter tương ứng cần đối chiếu, bit 0 là bỏ qua RC receiver; bit 5 là yêu cầu throttle thấp khi ARM. **Bit 1 mới là bỏ qua MAVLink overrides**; 33 không chứa bit 1. Không nhầm “ignore receiver” với “ignore overrides”.

Đã đọc parameter reference chính thức đang công bố trong phiên này: bit 0 “Ignore RC Receiver”, bit 1 “Ignore MAVLink Overrides”, bit 5 “Arming check throttle for 0 input”. Đây là đối chiếu tài liệu công khai, chưa phải xác minh firmware trên thiết bị. Định nghĩa bit có thể phụ thuộc phiên bản/nhánh firmware. Trước khi dựa vào các cờ này, đối chiếu metadata tham số của đúng firmware đã nạp, màn hình parameter tool và nguồn mã/tag tương ứng. App không dùng RC_OPTIONS để khẳng định motor không thể chạy, vì ARM và các chức năng khác vẫn do autopilot quyết định.

Với RC_OPTIONS=33 và không có receiver, `RC_CHANNELS.chancount=0` vẫn có thể là dữ liệu hợp lệ. Engine giữ đủ 18 raw channel và cho bootstrap override hữu hạn 750 ms khi các gate khác đạt, để xác minh feedback thực; không yêu cầu receiver phải tồn tại. Sau expiry, raw `radio_in` có thể giữ giá trị cuối, vì vậy cần kiểm tra trạng thái override/failsafe, không chỉ xem các số RC có về giữa hay không. Nguồn/commit đối chiếu nằm trong `mavlink.md`; chưa xác minh phiên bản thực trên thiết bị.

## MAV_OPTIONS, failsafe và nhiều client

Không diễn giải `MAV_OPTIONS=0` thành một cơ chế ép nhận tất cả sender. Sender app vẫn giữ system 255/component 190 theo yêu cầu. Kiểm tra các tham số định danh/quyền override thực tế trong đúng firmware nếu autopilot không chấp nhận RC; không tự đổi sender hoặc tắt kiểm tra.

Tài liệu ArduCopter đang công bố mô tả `FS_THR_ENABLE=1` là “Enabled always RTL” và `FS_GCS_ENABLE=5` là “Land”; tài liệu GCS cũng nhắc xét `FS_OPTIONS`. Đây không phải kết luận hành vi thực tế của thiết bị chưa biết phiên bản. `FS_THR_ENABLE=1`, `FS_GCS_ENABLE=5` và timeout RC có thể gây thay đổi hành vi/mode/failsafe trên autopilot tùy trạng thái và phiên bản. Tài liệu này không chuyển hai số đó thành cam kết “không có motor vì DISARMED” và không giả định mọi firmware có cùng bảng enum. Đọc đúng parameter reference của bản đang chạy, kiểm tra trạng thái trên bench có inhibit và không sửa các giá trị từ app.

Nếu Mission Planner cùng nối bridge TCP, cần xác minh firmware bridge hỗ trợ bao nhiêu client, fan-out, packet routing và xung đột system ID. GCS heartbeat từ một client khác có thể làm việc đo failsafe khác đi. Ghi rõ client nào đang kết nối trong mỗi lần test.

## Nguồn đối chiếu

- MAVLink Common: HEARTBEAT, RC_CHANNELS và RC_CHANNELS_OVERRIDE: <https://mavlink.io/en/messages/common.html>.
- ArduPilot RC input qua MAVLink: <https://ardupilot.org/dev/docs/mavlink-rcinput.html>.
- Danh sách tham số ArduCopter: <https://ardupilot.org/copter/docs/parameters.html> — chọn bản firmware/tag chính xác, không mặc định tài liệu stable hiện tại khớp thiết bị.

Các link là nguồn hướng dẫn đối chiếu, chưa phải bằng chứng đã đọc tham số trên thiết bị của người dùng. Lưu parameter dump cùng phiên bản và kết quả đo timeout cho hồ sơ nghiệm thu.
