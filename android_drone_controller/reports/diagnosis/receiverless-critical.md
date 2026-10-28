# Lỗi tích hợp được SITL phát hiện

Bản kiểm thử đầu tiên của `realBenchSessionReceiverlessCh1ReachesArduPilotRadioInputAndStops` chờ DISARMED và thất bại sau 20 giây. Đây không phải lỗi TCP, checksum hoặc stream: snapshot ghi TCP đã kết nối, CRC errors 0, parser errors 0, HEARTBEAT mới 15 ms, RC_CHANNELS mới 180 ms, system 42/component 1 và các kênh raw 0.

HEARTBEAT có `base_mode=81` (bit ARMED không bật), `system_status=5` (CRITICAL). `SYS_STATUS` có mask lỗi của sensor đãpresent+enabled bằng `0x10010000`, gồm RC_RECEIVER (bit 16) và PREARM_CHECK (bit 28). RC_OPTIONS 33, RC_OVERRIDE_TIME 0.5, FS_THR_ENABLE 1.

Source tham chiếu 4.6.3 `ArduCopter/GCS_Mavlink.cpp:67` chọn MAV_STATE_CRITICAL khi `any_failsafe_triggered()`; bit ARMED lấy riêng từ `motors->armed()`. Engine ban đầu gộp system_status ngoài 3/4 thành ARM UNKNOWN nên tạo một gate khác bên cạnh vòng phụ thuộc RC stream.

Không sửa bằng cách tắt failsafe hoặc bỏ kiểm tra critical chung. Bằng chứng này cần được giữ ngay cả khi regression sau đó PASS. XML chứa snapshot đầy đủ ở `sitl-critical-first-failure.xml`. Phần cứng và firmware ESP32 chính xác vẫn NOT VERIFIED.
