# Interface giai đoạn 2 — chưa triển khai điều hướng

ALTITUDE HOLD, GPS POSITION HOLD, RETURN TO HOME và AUTO LAND chưa phải chế độ hoạt động. Không tạo button có thể ARM những mode này và không giả lập thành công. GPS vị trí/Home và barometer trong telemetry chỉ phục vụ quan sát.

Một giao diện điều hướng tương lai cần nhận snapshot có timestamp đơn điệu: position/velocity cùng covariance; height/vertical velocity; attitude quaternion; quality flags; nguồn tham chiếu Home và giới hạn vùng hoạt động. Controller xuất attitude/rate/thrust setpoint có thời hạn. State machine chỉ chấp nhận đầu ra khi estimator, clock và mức tin cậy hợp lệ; mode transition cần kiểm tra và reset integrator để tránh bước nhảy.

| Chế độ | Thành phần còn cần | Điều kiện trước khi triển khai |
|---|---|---|
| Altitude hold | Estimator cao/vận tốc đứng, bù gia tốc và controller thrust | Đo nhiễu BMP trong prop wash, range/landing sensing phù hợp, kiểm tra chuyển mode |
| Position hold | Estimator vị trí/vận tốc, điều khiển XY, kiểm tra compass/GNSS | GNSS delay/outage, multipath, giới hạn nghiêng/tốc độ, gió |
| RTH | Planner, Home hợp lệ, độ cao hành trình, địa hình/vật cản, quản lý năng lượng | Vùng bay và đường về được đánh giá, GNSS/compass lỗi, pin không đủ |
| Auto land | Estimator tiếp đất, điều khiển vận tốc đứng, phát hiện chạm đất | Kiểm tra bề mặt/người/vật cản, ground effect và land detector |

Cần mô phỏng động lực học, HIL, fault injection và đánh giá độc lập trước test có cánh. Một bộ đếm giảm throttle không phải auto land, cũng không chứng minh an toàn khi mất Wi-Fi trên không. Chính sách mất liên kết cho bay thật hiện chưa được nghiệm thu.
