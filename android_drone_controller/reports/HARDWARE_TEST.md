# Kiểm thử Android và ESP32 thật

**NOT VERIFIED**. `adb devices -l` thực thi thành công nhưng không có thiết bị. Cloud không có ESP32 USB/serial, không có mạng AP ESP32 hoặc Windows Desktop mount. Không nạp bootloader/application/merged image, không đọc trực tiếp flash, không đổi tham số và không phát RC/ARM/Motor Test trên phần cứng.

Bộ BIN người dùng cung cấp đã phân tích chỉ đọc và khớp checksum: xem `docs/FIRMWARE_COMPARISON.md`. Người dùng xác nhận đây là firmware đã nạp; sự khớp giữa file và runtime/flash chip chưa được kiểm tra độc lập.

Ba instrumentation test được compile thành APK test, không coi là đã chạy. Cài APK debug trên Android API26+ bằng hướng dẫn ADB trong README; bài đo đầu tiên chỉ đọc60giây, TXoverride0. Mục đích, kết quả kỳ vọng và tệp log cần thu được ghi cụ thể ở `docs/HARDWARE_AND_FAILSAFE.md`. Chưa cần mật khẩu Wi-Fi trong chat hoặc token mới cho cloud.

Phép thử CH1 thật cần xác nhận riêng sau khi đọc kết quả telemetry, tháo cánh và ngắt vật lý nguồn ESC. Không chỉ dựa vào DISARMED hoặc một nút STOP qua Wi-Fi để bảo đảm động lực không hoạt động.
