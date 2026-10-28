# Đối chiếu bộ firmware được cung cấp

Phân tích chỉ đọc ngày 28/10/2026 (UTC+7). Không kết nối COM/USB, không reset, erase hoặc nạp ESP32. Kết quả chi tiết theo máy: `firmware-analysis.json`. Tài liệu `README_FLASH.txt` là mô tả lịch sử của bộ file; các hướng dẫn thao tác trong đó không được thực thi.

## Danh tính và tính toàn vẹn của file

Tất cả **7 mục được liệt kê** trong `SHA256SUMS_DESKTOP.txt` đều có file và khớp SHA-256: README_FLASH, SHA256SUMS, ba BIN rời, flasher_args và merged BIN. `SHA256SUMS_DESKTOP.txt` không tự liệt kê hash của chính nó. Manifest gốc `SHA256SUMS` có 17 mục: 4 mục được cung cấp và khớp (app, bootloader, partition-table, flasher_args), **13 mục chưa được cung cấp**, không phải checksum thất bại. Checksum khớp chứng minh các file nhất quán với manifest người dùng gửi; manifest không phải chữ ký nhà phát hành hoặc bằng chứng file đã chạy trên chip.

| File | Kích thước | SHA-256 đo được |
|---|---:|---|
| ardupilot.bin | 1.905.328 byte (`0x1d12b0`) | `f5b725ba347bda4c9a9701b79503e53cacdac2a4bcf05ddab515d3293d047db3` |
| bootloader.bin | 24.000 byte (`0x5dc0`) | `6915b7b85111eeb192af88194a83ca2cd39af346c706d976712698736cd66704` |
| partition-table.bin | 3.072 byte (`0xc00`) | `82f10134bb73cf1c49ea8fd8ed5d0b1be40c402fb641a9a44eeaadb55c0aa92e` |
| merged-flash.bin | 1.970.864 byte (`0x1e12b0`) | `541976dbdc383e64edf3c562a3beeda9967498e318090fee4b64e56fd600908f` |

Đã giải cấu trúc Espressif image header/segments và tính lại trên payload:

- Bootloader: magic `0xe9`, chip ID0 (ESP32), 4 segment, entry `0x400805a8`.
- Application: magic `0xe9`, chip ID0, 5 segment, entry `0x40083654`.
- Cả hai dùng SPI mode2 (DIO), flash frequency/size byte `0x2f` (4MB/80MHz), phù hợp `flasher_args.json`.
- XOR checksum ESP image và SHA-256 được append của **cả hai ảnh** đều khớp. Đây là phép kiểm tính toàn vẹn tệp, không phải secure-boot/signature verification hoặc readback trên chip.

## Thông tin application

App descriptor tại offset `0x20` có magic `0xabcd5432`, secure_version0 và `idf_ver = v5.3.1-1-gcc3203dc4f0`. Các trường version/project_name/build_time/build_date của descriptor rỗng; không tạo ra ngày build từ tên thư mục. ELF SHA-256 trong descriptor là:

`64216782a408f864607d773748016a544db2afb02afbe77b69f83c8a901361ec`

Giá trị này khớp hash `ardupilot.elf` được kê trong manifest gốc, nhưng file ELF chưa có nên chưa tính lại hash ELF. Chuỗi nhận dạng trong binary là `ArduCopter V4.8.0-dev (afeb9c49)` và `ArduCopter V4.8.0-dev`; SSID đã biết `ESP32_DRONE` và tên `AK09916` cũng hiện diện. Không tìm/in mật khẩu Wi-Fi. Sự hiện diện hoặc vắng mặt của chuỗi tên tham số không chứng minh một handler, driver hay cấu hình runtime cụ thể được bật.

`README_FLASH.txt` mô tả base đầy đủ `afeb9c4941543d97f3e04fecf257b8f598f49a6a` cộng patch cục bộ MAVLink/INA219/rotation. Binary xác nhận revision ngắn; không có ELF/map/source/patch để xác nhận từng thay đổi hoặc khôi phục chính xác source. Không thể kết luận ROTATION_YAW_90 hoặc driver INA219 đã được áp dụng đúng chỉ từ file name hoặc nhận dạng phiên bản.

## Partition và merged image

Đã parse từng entry32 byte theo định dạng partition table ESP-IDF và tính MD5 của table; **MD5 khớp**.

| Label | Type/subtype | Offset | Kích thước | Kết thúc, không bao gồm |
|---|---|---|---|---|
| nvs | `0x01/0x02` | `0x9000` | `0x6000` | `0xf000` |
| phy_init | `0x01/0x01` | `0xf000` | `0x1000` | `0x10000` |
| factory | `0x00/0x00` | `0x10000` | `0x300000` | `0x310000` |
| storage | `0x45/0x00` | `0x310000` | `0x40000` | `0x350000` |

Factory partition đủ chứa application; app kết thúc tại flash address `0x1e12b0`. `flasher_args.json` khai báo bootloader tại0x1000, partition tại0x8000, app tại0x10000. So sánh **từng byte** của ba đoạn tương ứng trong `merged-flash.bin` với BIN rời đều khớp.

Các khoảng0x0–0x0fff, 0x6dc0–0x7fff, 0x8c00–0xffff của merged đều là0xff. Vì merged là ảnh liên tục tại0x0, vùng NVS/PHY nằm trong ảnh và không được xem là “giữ nguyên” khi nạp merged. Ba BIN rời không chứa payload cho vùng đó. Merged dừng trước storage0x310000; tuy nhiên điều này không xác minh tham số/calibration thực trên chip, cũng không tự cấp quyền nạp ảnh nào.

## So với checkout và thiết bị

| Đối tượng | Đã xác minh | Chưa xác minh |
|---|---|---|
| Firmware trong repository `firmware/` | Arduino/WebSocket prototype, khác kiến trúc và giao thức app ArduPilot | Không dùng prototype để mô tả binary đã gửi. |
| Bộ BIN người dùng gửi | Nội bộ nhất quán, nhận dạng ArduCopter4.8-dev/afeb9c49, đúng header/partition/merged | Source các patch, hwdef, SDK config, WiFiDriver compiled, handler RC và stream policy cụ thể. |
| ESP32 đang hoạt động | Người dùng xác nhận rõ bộ này là firmware đã nạp vào ESP; nhận dạng revision đã cung cấp | Không có readback/hash/heartbeat AUTOPILOT_VERSION/log từ thiết bị trong phiên cloud để kiểm tra độc lập sự trùng khớp. |
| Upstream afeb9c49 | Đã đọc source handler RC, backend và WiFiDriver chính thức; xem SYSTEM_AUDIT | Patch cục bộ có thể thay đổi hành vi. |

Trong README của bộ file có câu “Bộ Desktop này chưa được nạp” và mô tả một lượt ghi cũ bị ngắt. **Xác nhận mới của người dùng “đây là firmware đã nạp vào esp” thay thế trạng thái lịch sử đó.** Ghi nhận bộ file là firmware đã nạp theo thông tin người dùng; readback/hash runtime chưa được kiểm tra độc lập. Khi nghiệm thu phần cứng, cần gắn bằng chứng firmware runtime với bộ file cụ thể, rồi mới dùng kết quả về RC/timeout cho đúng bản đang chạy.

Các file chưa có theo manifest gốc: BUILD_INFO.json, COMPASS_ORIENTATION_REPORT.md, README.md, ardupilot.elf, ardupilot.map, compass-orientation.patch, defaults.parm, hwdef.dat, hwdef.h, partitions.csv, partitions.json, sdkconfig, test_compass_orientation.py. Sự thiếu những file này không chặn việc viết/test app trên host; nó giới hạn độ chắc chắn khi kết luận về patch và phần cứng.

## Bằng chứng read-only cần ở bước nghiệm thu thiết bị

Mục đích là nối kết binary với runtime và phân biệt thiếu stream với từ chối RC. Sau khi có điện thoại/ESP32 và điều kiện truy cập, thu một phiên telemetry-only gồm AUTOPILOT_VERSION/HEARTBEAT, ACK yêu cầu interval65, số lượng RC_CHANNELS cùng count/raw values và parameter dump đọc từ FC. Không phát RC, không ARM/đổi mode/ghi tham số ở bước này. Lưu log nguyên bản với timestamp và ghi GCS/client đang kết nối. Kết quả kỳ vọng: nhận dạng build và trạng thái DISARMED mới, phân loại rõ msg65 absent/count0/raw0/stale. Phép thử CH1 và kiểm timeout thật cần xác nhận riêng và bằng chứng ESC mất nguồn, tháo cánh; tài liệu này chưa ghi PASS cho các phép thử đó.
