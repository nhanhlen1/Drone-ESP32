# Hiệu chuẩn và kiểm tra trục

Mọi bước thực hiện khi DISARMED, ESC không có nguồn và tháo cánh. Đợi nhiệt độ cảm biến ổn định. Lưu cấu hình có chủ ý sau khi xem kết quả; không ghi flash theo telemetry hoặc mỗi mẫu. Không thay calibration khi motor hoạt động.

## ICM-20948 gyro

Đặt khung bất động trên bề mặt cứng. Yêu cầu `gyro`, giữ bất động trong toàn bộ cửa sổ thu mẫu. Hiệu chuẩn hợp lệ cần đủ số mẫu, gia tốc gần 1 g, phương sai gyro thấp và không bão hòa; nếu di chuyển, từ chối và làm lại. Cửa sổ gyro/gia tốc kế cần 400 mẫu tĩnh hợp lệ; giới hạn variance hiện tại 0,0004 theo đơn vị mẫu bình phương. Đây là kiểm tra kỹ thuật ban đầu, cần đánh giá theo nhiễu cảm biến thực tế. Bias trừ theo từng trục trước AHRS. Khởi động lại và kiểm tra bias đã lưu; không nhầm yaw trôi với sai bias khi từ kế không được chấp nhận.

## Gia tốc kế sáu mặt

Lần lượt đặt trục cảm biến/thân đã cấu hình hướng +X, -X, +Y, -Y, +Z, -Z lên trên, gửi `accel_xp`, `accel_xn`, `accel_yp`, `accel_yn`, `accel_zp`, `accel_zn` tương ứng. Dừng ở mỗi mặt cho đến khi thu đủ mẫu. Mặt đang hướng lên nhận specific force gần +1 g trên trục đó. Không bấm sáu lệnh khi khung nằm nguyên một tư thế.

Offset mỗi trục lấy trung điểm hai cực; scale dựa trên chênh lệch hai cực. Từ chối dữ liệu khi span quá nhỏ, sai mặt hoặc khung rung. Phương pháp này không tự xác định misalignment, phi tuyến và mọi sai số cross-axis. Sau lưu, đặt khung nằm ngang: norm gần 1 g và roll/pitch gần 0; nghiêng góc biết trước để kiểm tra độ chính xác.

## Magnetometer

Thực hiện xa sắt, nam châm, nguồn xung và dây công suất. Gửi `mag`, quay khung chậm đủ cả ba trục để phủ không gian ba chiều. Chỉ xoay ngang không đủ hiệu chuẩn. Min/max từng trục cho hard-iron offset và scale chéo đơn giản; không phải fit ellipsoid đầy đủ soft-iron. Implementation yêu cầu ít nhất 1.000 mẫu từ kế thay đổi và ít nhất 15 giây, mỗi bán kính min/max 10–100 µT, tỉ lệ scale 0,25–4; timeout thu mẫu 65 giây. Các ngưỡng này là gate kỹ thuật ban đầu, không chứng minh fit đủ chính xác. Trường sau hiệu chỉnh cần nằm trong dải hợp lý.

Kiểm tra heading ở nhiều hướng và nhiều góc nghiêng; đo lại khi dây/motor có dòng trên rig an toàn. Trường thay đổi do dòng không thể được khắc phục bằng một offset tĩnh. Khi trường không đáng tin, AHRS không được ép yaw theo từ kế. Không coi yaw telemetry là heading tuyệt đối đã xác minh.

## Kiểm tra hướng lắp

Thân FLU: +X trước, +Y trái, +Z lên. Đứng yên, accel +Z. Nghiêng/quay lần lượt quanh từng trục theo bàn tay phải; gyro và góc phải cùng dấu dự kiến. Chuyển đổi trục phải là phép quay đúng; kiểm tra `det(R)=+1` và trực giao. Thực hiện kiểm tra bằng tay trước khi xét dấu mixer.

## BMP280

Đặt khung ở cao độ tham chiếu, tránh luồng gió, gửi `baro` khi cảm biến ổn định. Ghi áp suất tham chiếu và nhiệt độ. Reference barometer hiện chỉ nằm trong RAM, tự thiết lập lại khi reboot; lệnh save không lưu reference. Chênh cao là tương đối theo mô hình khí quyển, không phải cao tuyệt đối chính xác. Thử thay đổi độ cao có mốc biết trước và quan sát bộ lọc/độ trễ. Không dùng số đo này làm bằng chứng auto land.

## INA219 và GPS

Đo shunt thực tế, cấu hình hệ số dòng theo phần cứng; so bus/shunt/nguồn và dòng với thiết bị đo ít nhất tại không tải và vài tải nằm trong định mức. Từ chối giá trị bão hòa. Ghi rõ đang đo nhánh điện tử hay dòng tổng.

GPS cần fix mới, chất lượng/HDOP và số vệ tinh phù hợp mới ghi Home. Kiểm tra tại nơi nhìn trời thoáng; trong nhà có thể không fix dù UART đúng. Đối chiếu tọa độ với mốc độc lập. Sau ngắt anten/module hoặc dừng bản tin, trạng thái phải chuyển stale; không giữ `fresh=true` nhờ bản tin cũ.

## Biên bản

Có thể gửi `cal gyro`, `cal accel_xp` (và từng mặt còn lại), `cal mag`, `cal baro`, `cal save` qua Serial 115200 baud; chỉ channel vật lý này không có lệnh ARM/motor test. Lưu calibration cần bộ gyro + accel hợp lệ; magnetometer tùy chọn cho heading và chỉ được sử dụng sau khi có cờ hiệu chuẩn riêng.

Lưu phiên bản firmware, lắp đặt IMU, nhiệt độ, số mẫu/span/variance, offset/scale, ngày và người thực hiện. Khởi động lại để kiểm tra dữ liệu NVS đúng version và được validate. Cấu hình thiếu/hỏng không được coi là hiệu chuẩn xong. Test trên máy không thay nghiệm thu này.
