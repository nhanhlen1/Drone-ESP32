# Phạm vi bản 0.3.0-rc-control — 29/10/2026

Yêu cầu mới trong `docs/USER_SPEC_LIVE_CONTROL_20261029.md` thay yêu cầu tiếp nối Google AI Studio. Từ checkpoint `7e0b589` (0.2.1-protocol), `android_drone_controller`, package `dev.drone.controller`, là sản phẩm được tiếp tục phát triển. Không cần ZIP Google để thực hiện bản này. Nhánh mới: `codex/controller-live-20261029`; nhánh qualification cũ giữ nguyên để rollback.

## Đường chạy phải hoàn thiện

`MotionEvent đa điểm → Mode2Mapping → ControllerViewModel → ControlAuthority → BenchSession/scheduler một actor → Mavlink2Encoder → AndroidWifiBinder/TCP → FC`.

Bench CH1 cũ được giữ để hồi quy. Phiên bốn kênh mới có quyền riêng cho kiểm tra DISARMED/ESC cách ly và điều khiển có xác nhận. Cờ ARMED thu hồi quyền bench; phiên điều khiển hợp lệ được bắt đầu khi DISARMED tiếp tục phát sau khi FC ARM. Không dùng một cờ chứng nhận phần cứng giả để mở đường chạy. Bản thử nghiệm yêu cầu người vận hành xác nhận tháo cánh và không được quảng bá là đã nghiệm thu bay.

ARM/DISARM là COMMAND_LONG 400, `param2=0`, không force và không retry tự động. Chỉ ARM khi có dữ liệu mới về PreArm, EKF, trạng thái trên đất và RC phản hồi. ACK chấp nhận và HEARTBEAT mới xác nhận trạng thái được ghi riêng. Flight mode dùng COMMAND_LONG 176, chờ ACK và mode trong HEARTBEAT mới; danh sách chế độ ArduCopter không được coi là danh sách hỗ trợ đã được FC chứng nhận.

Mọi STOP, lỗi session, dữ liệu quá hạn, mất Wi-Fi/focus hoặc reconnect đều xóa quyền và đầu vào cũ. Reconnect cần xác nhận mới; không tự phát lại throttle. Dừng phát không đồng nghĩa đã ngừng động cơ hoặc FC đã thực hiện failsafe.

## Bằng chứng cần thu

- Build sạch, hồi quy tất cả test 0.2.1 cùng test mới; lint và chữ ký APK.
- Golden vectors độc lập cho ARM/DISARM/mode; parser 301 message tiếp tục được kiểm tra.
- Mock TCP: bốn kênh, partial write, backlog, stale RX/input, reconnect và dữ liệu điều khiển cũ.
- SITL upstream đúng base `afeb9c49`: production `BenchSession`, CH1–4, PreArm từ chối/cho phép ARM thường, ACK + HEARTBEAT, RC liên tục khi ARMED, DISARM và mất kết nối.
- Android instrumentation và emulator: cảm ứng đa điểm, lifecycle, giao diện ngang; ưu tiên factory/AndroidWifiBinder thật kết nối SITL qua mạng emulator. Bất kỳ seam kiểm thử nào phải được chỉ rõ.
- Khoảng cách socket write và jitter được đo riêng với thời gian chờ RC echo. Không gọi khoảng cách ghi socket là độ trễ điều khiển đầu cuối.

## Phạm vi phần cứng

Không có ESP32, điện thoại hay nguồn động lực thật nối vào cloud. Không phát lệnh vào `192.168.4.1`, không ARM phần cứng, không đổi firmware/tham số FC. Các tham số đầu vào do người dùng cung cấp phải được đọc lại theo phiên. `RC_OPTIONS=33` gồm bit0 bỏ RC receiver và bit5 kiểm tra ga idle khi ARM; bit1 mới là bỏ MAVLink override.

Kết quả cuối phân biệt SOFTWARE TESTED, SITL TESTED, HARDWARE RC VERIFIED và FLIGHT READY NOT VERIFIED. Các báo cáo cũ là lịch sử, không thay thế kết quả bản mới.
