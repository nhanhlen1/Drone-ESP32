# Kiểm chứng dừng phát và failsafe

## Đã chứng minh trong phần mềm/mô phỏng

- Engine có lease750ms tuyệt đối, cửa sổ bắt đầu5giây, deadman200ms, HEARTBEAT1500ms và permit dưới50ms. Không gia hạn bởi feedback, không catch-up backlog, không khôi phục throttle cũ.
- CRC/nguồn mơ hồ/ARMED/mất focus/background/STOP/reconnect thu hồi quyền. RC frame dở bị thu hồi đóng socket; một actor, kiểm tra generation và gate trước mỗi partial write.
- CRITICAL mặc định khóa; chỉ chẩn đoán CH1 đã xác nhận thứ tư, SYS_STATUS mới và mẫu lỗi RC/PreArm hẹp mới được cấp lease. Mẫu này không chứng minh không có failsafe khác.
- Reference và upstream base SITL: RC_OVERRIDE_TIME0.5, encoder override hết hạn và trở về receiver mô phỏng. Không đổi ARMING_CHECK/FS_THR_ENABLE để làm test qua.

## Chưa chứng minh

**NOT VERIFIED** cho timeout nguồn RC receiverless qua quan sát độc lập, radio/GCS failsafe thực của firmware ESP32, FS_GCS_TIMEOUT/RC_FS_TIMEOUT runtime, mất Wi-Fi trong app thật, AP reset, Android treo/OS kill/khóa màn hình, half-open trên đường thật, ngắt nguồn điện thoại, joystick/cảm ứng thật và dừng động lực độc lập.

DataFlash có radiofailsafe event trong phép CH1 receiverless; nó có thể do các kênh khác chưa có đầu vào và không chứng minh thời điểm override CH1 hết hạn. RawRC giữ số cuối sau expiry là hành vi source đã xác định, không phải bằng chứng lệnh còn hoạt động. Không ghi PASS radio failsafe hoặc hạ cánh an toàn từ sự kiện này.

Ma trận đo từng tình huống và tệp cần thu: `docs/HARDWARE_AND_FAILSAFE.md`. ARM, motor,flight mode command và liveflight joystick không được mở sau bench. Không có giải pháp phần mềm thay thế cơ chế dừng khẩn cấp độc lập khi Wi-Fi mất.
