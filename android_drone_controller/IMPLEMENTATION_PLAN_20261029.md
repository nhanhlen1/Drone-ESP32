# Kế hoạch và trạng thái thực hiện master prompt29/10/2026

## Điều kiện nguồn bắt buộc

Tiếp tục app Google AI Studio, không dựng lại. Đã tìm workspace/home/mount và toàn bộ branch/tag GitHub: chưa có source `com.example`; hai lần tải ZIP đính kèm đều vượt giới hạn32MiB. Bước sửa trực tiếp base này **BLOCKED**. Không giả định package `dev.drone.controller` là bản mới của `com.aistudio.esp32drone.krvwpx`.

## Việc độc lập đã thực hiện

1. Kiểm kê/hash candidate trước sửa, đọc đầy đủ master prompt, giữ nguyên app cũ/firmware; lập nhánh `codex/controller-qualification-20261029`.
2. Sinh metadata301message từ XML ArduPilot/MAVLink đã pin; fuzz/golden tests, phân biệt unsupported decoder và unknown dialect, signed framing, seq từng nguồn.
3. Chặn duplicate/reordered frame làm mới heartbeat/RC/ACK; xử lý fragment quá hạn. Đo khoảng cách ghiRC/jitter hữu hạn trong transport, số heartbeatGCS, hiển thị thống kê rõ nguồn.
4. Thêm mô-đun authority BENCH/FLIGHT_CANDIDATE độc lập với qualification mặc định khóa; kiểm thử expectedARMED không cắt authority đã đủ điều kiện. Chưa nối module này vào UI/schedulerlive vì chưa đọc sourceGG.
5. Kiểm thử100vòng reconnect/reset, giới hạn log trên đĩa+queue, wall/monotonic timestamp, replay30phútFC-time, RC1470/1500/1530 trên SITL thật.
6. Build sạch/hồi quy, RobolectricUI và instrumentation trên API26, phân loại lỗiframework emulatorAPI35 riêng; không gửiRC trong UItest.
7. Đóng gói bản Codex0.2.1-protocol, log/hash/report/checkpoint. Không ghi đây là bản hoàn tất tiếp nối Google AI Studio.

## Việc tiếp theo khi có source đọc được

Hashsource/APKGG và diff đúng base; tái hiện các lỗi P0 trên code đó; port phần protocol/authority đã kiểm thử, giữ wire1470 thành công; sửa commandARM/DISARM/mode, scheduler và ComposeUI trên chính projectGG; chạy lại toàn bộ gates cho package đó. Không sử dụng kết quả candidate làm kết quả appGG.

Phần cứng hiện chỉ có kết quả CH1 do người dùng báo cho appGG; không có điện thoại/ESP32 của người dùng trong cloud. Các phép bench thật cần xác nhận vật lý/quyền riêng, không nạp firmware/đổi tham số/ARM thật. Giữ `FLIGHT READY NOT VERIFIED`.
