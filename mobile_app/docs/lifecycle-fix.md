# Sửa lỗi vòng đời Flutter / kết nối ESP32

## Nguyên nhân đã tái hiện

Flutter 3.35.7: mở Connect, focus ô token, nhập nội dung rồi Cancel trong animation đóng. Bản cũ phát sinh lỗi đầu tiên `A TextEditingController was used after being disposed` ở TextField của hộp thoại trong `console.dart`; sau đó Overlay gặp đúng assertion `framework.dart:6171: _dependents.isEmpty`.

`showDialog()` trả kết quả ngay khi route được pop; widget trong route vẫn còn được dựng để chạy reverse transition. Bản cũ hủy hai TextEditingController ngay sau `await showDialog()`, khiến TextField còn mounted dùng controller đã hủy. Đây là lỗi sở hữu tài nguyên theo vòng đời, không cần tắt assertion hoặc thay đổi Flutter SDK.

## Các file đã sửa

- `lib/widgets/connection_dialog.dart` (mới): StatefulWidget sở hữu hai TextEditingController; khởi tạo trong initState, hủy trong State.dispose khi subtree thực sự unmount. Trả endpoint/token bằng kết quả dialog; giữ endpoint mặc định `ws://192.168.4.1:81/ws`. Nội dung cuộn được khi bàn phím chiếm màn hình.
- `lib/screens/console.dart`: dùng dialog mới, chặn mở dialog trùng; kiểm tra mounted và controller hiện tại sau await trước khi truy cập context/widget hoặc gửi lệnh ARM/calibration/PID. Tiêu đề co vừa màn hình điện thoại, không đổi thiết kế/chức năng điều khiển.
- `lib/services/drone_link.dart`: từng lần kết nối sở hữu riêng socket, subscription và timeout. Giành quyền lần kết nối mới trước await; hủy phiên cũ, giới hạn thời gian cleanup và bỏ callback đã hết quyền sở hữu. Lỗi từ cả handshake/stream được xử lý; không phát notify sau dispose hoặc tự khôi phục ARM/throttle.
- `lib/widgets/flight_widgets.dart`: hủy timer và vô hiệu pointer khi dispose. Đã tái hiện sự kiện PointerCancel đến hit-test target cũ sau khi widget/controller tháo khỏi cây; bản sửa ngăn callback muộn gọi notifier đã dispose.
- Các regression test nằm trong `test/console_lifecycle_test.dart`, `test/drone_link_test.dart`, `test/joystick_test.dart`.

## Kiểm tra toàn bộ cây widget

Ứng dụng dùng AnimatedBuilder/ChangeNotifier, không có Provider hoặc StreamBuilder. GlobalKey duy nhất là FormState của PID editor, được tạo một lần trong State, không tạo lại trong build hoặc dùng chung giữa form. Các InheritedWidget như Theme/MediaQuery là thành phần framework; assertion deactivation xuất hiện sau lỗi controller nêu trên. Root app sở hữu FlightController/DroneLink và bỏ observer/listener/timer khi dispose; không cần sửa main.dart hoặc firmware.

## Kết quả

- Regression dialog trên bản cũ: FAIL, tái hiện cả lỗi controller và đúng assertion `_dependents.isEmpty`.
- `flutter analyze`: No issues found.
- `flutter test --reporter expanded`: 27 test PASS; bao gồm Cancel/Back/barrier, keyboard trên màn hình 390 px, unmount trong khi kết nối, xác nhận ARM/PID sau unmount, WebSocket localhost, timeout, rapid reconnect, dispose/teardown chồng nhau và callback chạm muộn.
- `scripts/build_android.sh`: PASS, tạo APK debug Android mới sau bản sửa.
- Firmware và dependency/lockfile không thay đổi.
- Chưa thử trên ESP32/điện thoại thật trong phiên cloud. Các test socket dùng localhost và fake transport có kiểm soát, không chứng minh radio hoặc routing của điện thoại.

## Kiểm thử lại trên điện thoại

1. Build/cài APK từ mã mới (`scripts/build_android.sh` hoặc `flutter build apk --debug` trong mobile_app). Dừng app cũ hoàn toàn; hot reload không thay thế kiểm tra cold start. Nếu chữ ký debug khác bản đã cài, Android yêu cầu gỡ bản cũ trước khi cài lại.
2. Giữ drone DISARMED. Kết nối điện thoại với AP ESP32, chọn tiếp tục dùng Wi-Fi không có Internet nếu Android hỏi. Nếu cần, tắt tuyến mobile data/VPN đang giành kết nối tới mạng AP.
3. Mở Connect, giữ URL `ws://192.168.4.1:81/ws` và nhập session token đúng của firmware. Thử nhập rồi Cancel, Back và chạm ngoài hộp thoại nhiều lần, cả khi bàn phím đang mở.
4. Connect và kiểm tra telemetry; thử Disconnect/Connect nhanh, đưa app ra nền/rồi trở lại, ngắt AP và kết nối lại thủ công. Không được có màn hình assertion, lệnh của phiên cũ hoặc tự ARM.
5. Theo dõi `flutter logs`/adb logcat nếu lỗi khác xuất hiện; cần stack trace đầu tiên, thao tác tái hiện, phiên bản Android và firmware. Không gửi token/mật khẩu trong log chia sẻ.

Bản Release v1.0.0-test đã xuất bản trước đó không tự thay đổi khi sửa mã trong workspace. Bản sửa được đóng gói riêng cho Release `v1.0.1-test`, ứng dụng phiên bản `1.0.1` / build `2`; Release cũ được giữ nguyên.
