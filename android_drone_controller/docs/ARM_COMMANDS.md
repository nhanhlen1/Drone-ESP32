# Kiến trúc lệnh ARM/DISARM: chưa bật trên thiết bị

`command/ArmCommandService.kt` tách dịch vụ lệnh khỏi joystick và RC scheduler. Đây là mô hình ý định lệnh và theo dõi kết quả, không có bộ mã hóa ARM/DISARM thành MAVLink, không có socket và không được nối với phiên TCP của ứng dụng. Hai điều kiện mặc định luôn chặn phát: nguồn nghiệm thu độc lập trả về false và sink ý định từ chối. Giao diện tiếp tục khóa ARM/DISARM; kiểm thử phần mềm không tự thay đổi cấp nghiệm thu này.

Muốn tạo ý định ARM, mô hình yêu cầu xác nhận chủ động riêng từng lần, cấp nghiệm thu hệ động lực/failsafe độc lập, target hợp lệ, HEARTBEAT mới dưới 1,5 giây với trạng thái ARM xác định, đã xác minh trên mặt đất, nguồn RC áp dụng và PreArm đã hết. Ý định DISARM cũng yêu cầu cấp nghiệm thu, xác nhận, HEARTBEAT và bằng chứng đang trên mặt đất; mô hình không tạo lệnh tắt động cơ khi đang bay. Không hỗ trợ force-arm, force-disarm hoặc tự ARM.

Các unit test tiêm nguồn nghiệm thu mô phỏng và sink thu thập ý định thuần Kotlin. `intentAccepted=true` chỉ chứng minh callback tiếp nhận ý định; không chứng minh ghi socket hoặc FC nhận lệnh. Không có byte điều khiển được tạo trong các test này.

Một yêu cầu duy nhất được chờ tại một thời điểm. COMMAND_ACK phải từ đúng system/component FC, command=400, và target GCS nếu extension có giá trị. ACK `ACCEPTED` chỉ chuyển sang chờ HEARTBEAT. Chỉ HEARTBEAT mới hơn cả lúc yêu cầu và ACK, từ đúng FC và có trạng thái ARM khớp mới xác nhận trạng thái thực. HEARTBEAT khớp trước ACK được lưu là quan sát riêng, không quy kết trạng thái do lệnh app. ACK cũng không cung cấp mã yêu cầu riêng: không thể loại trừ tuyệt đối ACK trễ từ một GCS cạnh tranh chỉ bằng command ID; triển khai tương lai phải kiểm soát nguồn gửi và tiếp tục đối chiếu trạng thái thật.

DENIED, UNSUPPORTED, FAILED, CANCELLED và kết quả lạ đều kết thúc theo dõi mà không tự gửi lại. IN_PROGRESS không kéo dài deadline tuyệt đối 3 giây. Quá hạn, link mất hoặc hủy không tự gửi lại khi nối lại. Hủy theo dõi không thể thu hồi một lệnh đã đến FC; cần đọc lại trạng thái thực. Cần kiểm định giá trị timeout với firmware thực trước khi tích hợp sống.

Giới hạn: đây là kiến trúc và kiểm thử mô phỏng dịch vụ lệnh, không phải tính năng ARM/DISARM trực tiếp đã vận hành. Chưa nghiệm thu failsafe, nguồn động lực, ngắt khẩn cấp độc lập hoặc điện thoại/ESP32 thật. Việc nối dịch vụ này vào bộ mã hóa/socket, cấp bằng chứng nghiệm thu và cho phép ARM thực cần công việc triển khai cùng chấp thuận riêng, không được suy ra từ bench PASS hoặc build APK.
