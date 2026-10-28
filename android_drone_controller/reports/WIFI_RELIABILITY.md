# Độ bền Wi-Fi

**Wi-Fi Android–ESP32 thật và bài đo30phút: NOT VERIFIED.** Không suy từ TCP loopback hoặc SITL thành chất lượng sóng/AP/nguồn ESP32.

Đã chạy recorder chỉ nhận trên TCP SITL khoảng5.962giây:180byte,8HEARTBEAT CRC hợp lệ, TX0/RC0, không phát hiện reboot trong mẫu ngắn. Có12byte không phải frame được đếm riêng. Bản smoke này được thu trước sửa timestamp-sau-recv của recorder, nên không dùng làm bằng chứng timing chính xác hoặc30phút. Capture gốc và replay ở `telemetry-passive-smoke/`.

Host TCP tests thực thi fragmented/coalesced reads, partial/zero writes, reconnect,cancel generation,CRC/ARM/background/deadman; đó là regression của phần mềm, không mô phỏng mọi loại nhiễu Wi-Fi. Sequence gap được đếm theo header, không gắn nhãn mất gói RF; không có phép đo RTT được hiển thị giả.

`integration/telemetry_reliability.py` chạy bằng Python chuẩn ngoại tuyến, không gửi byte nào, không ghi đè report cũ. Năm test parser/replay/capture integrity PASS. Bài đo thật cần lưu raw+JSON/CSV, event socket close/reset/heartbeat age và nguồn cấp, không gửi RC trong bài telemetry dài. Bridge chưa chứng minh đa TCP client; đọc quy trình trong `docs/HARDWARE_AND_FAILSAFE.md` trước khi dùng app và recorder đồng thời.
