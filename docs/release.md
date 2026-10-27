# Gói thử nghiệm v1.0.0-test

Tag này đóng gói mã nguồn và các bản build bench đã kiểm thử trên máy phát triển. Chuỗi phiên bản firmware vẫn là `0.1.0-bench`; `v1.0.0-test` là tên bản phát hành thử nghiệm, không phải chứng nhận đủ điều kiện bay. Không thay đổi pin mapping, tham số phần cứng hoặc khóa ESC để tạo gói.

Các tệp dự kiến đính kèm Release:

- `ESP32-Drone-source-v1.0.0-test.zip`: source tạo trực tiếp bằng `git archive` từ đúng tag.
- `ESP32-Drone-firmware-bench.bin`: ứng dụng ESP32 Arduino/PlatformIO, motor output mock, không có mật khẩu/token AP cục bộ.
- `ESP32-Drone-Android-debug.apk`: APK universal, ký debug, không phải bản phát hành production.
- `SHA256SUMS.txt`: checksum SHA256 của ba tệp trên.

Nạp lần đầu bằng `scripts/upload_firmware.sh` để PlatformIO nạp đúng bootloader, partition table và ứng dụng. Không nạp tệp BIN ứng dụng vào offset 0. APK được cài thủ công trên Android sau khi cho phép cài ứng dụng từ nguồn đã chọn.

Đối chiếu `docs/validation.md` cho kết quả build/test. Không có kiểm thử ESP32/ESC/cảm biến/điện thoại thật trong phiên cloud. Giữ motor mock và ngắt nguồn ESC khi làm việc với firmware. Cần tạo file ignored `firmware/include/local_secrets.h`, build lại và hoàn tất quy trình bench trước khi xét hoạt động phần cứng.
