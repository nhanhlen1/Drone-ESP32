# Kiểm chứng phần cứng và failsafe — 0.3.0

**Tất cả hạng mục phần cứng dưới đây: NOT VERIFIED cho APK Codex0.3.0.** Cloud chỉ truy cập mô phỏng local; không phát RC/ARM đến ESP32 thật, không sửa firmware hoặc tham số FC. Việc người dùng trước đây thấy CH1 trên Mission Planner bằng APK khác không nghiệm thu bản này. Xem [báo cáo phần mềm/SITL/emulator](../reports/20261029-live/VALIDATION.md).

## Dữ liệu đã có và cần đọc lại

Người dùng cung cấp ESP32DevModule, ArduCopter4.8dev baseafeb9c49 với board/INA219 patches riêng; ICM20948/AK09916/BMP280/INA219/GPSM10. ESCGPIO14/27/26/25, motor850kV, LiPo3S5400mAh, ESCXXDHW30A đã hiệu chuẩn1000–2000µs, quay ổn định từ khoảng1036µs. MOT_PWM_MIN1000/MAX2000/TYPE0, MOT_SPIN_ARM0.06/MOT_SPIN_MIN0.09 là giá trị người dùng báo. Không tự suy ra đườngRC→mixer→ESC đã đúng hoặc thay đổi các giá trị này.

Ứng dụng chỉ đọc RC/GCS/mapping/failsafe; yêu cầu calibration phù hợp theo từng phiên. `RC_OPTIONS33` gồm ignore receiver và kiểm tra ga idle khiARM, không phải bỏ qua failsafe. Bản0.3 khóa đường bốn kênh khi ARMING_RUDDER chưa đọc/bật để ngăn ARM/DISARM bằng cần; FLTMODE_CH không được nằm trongCH1–4 và RC1–4_OPTION phải0 để tránh các trục điều khiển kích hoạt chức năng phụ. Người vận hành cần đối chiếu cấu hình thực; app không tự sửa.

## Quy trình tăng dần

1. **Chỉ telemetry:** tháo cánh, cách ly nguồn ESC, một client điều khiển. Cài APK kiểm hash, chọnESP32_DRONE khôngInternet, kết nối192.168.4.1:5760. Giữ60s không cấp quyềnRC; ghiTXoverride0, SYS/COMP, HB/health, trạng thái message65/count/raw/age, tham số vàSTATUSTEXT. ACK511 không chứng minh stream đã đến.
2. **RC bốn kênh DISARMED, ESC cách ly:** đọc lại tham số, xác nhận riêng, chọnBENCH_4CH. Thay đổi từng trục nhỏ rồi đồng thời; MissionPlanner/logFC độc lập phải thể hiện đúngCH1roll/CH2pitch/CH3ga/CH4yaw, kênh phụ không đổi và không phátARM. Kiểm tra dấu, trim, min/max từng kênh trước khi tăng biên. Tương quan RC_CHANNELS không phảiACK của override.
3. **Thời hạn override không receiver:** STOP/mấtWi-Fi nhưng rawRCIN có thể giữ số cuối. Cần nguồn quan sát trạng thái override/failsafe độc lập của đúng binaryESP32; không dùng riêng giá trịPWM cuối để kết luận expiry. Nếu chưa có nguồn này, giữNOTVERIFIED.
4. **Động lực tháo cánh:** chỉ sau khi đã xác minh dừng độc lập và điều kiện rig. Đối chiếu mixer/mapping bốn đầu ra, nguồnpin, bảo vệ vàARMthường dưới sự giám sát trực tiếp. Không dùng MotorTestPASS để thay đườngRC/ARMthực. Không forceARM hoặc tắtARMING_CHECK/EKF/PreArm/battery/GCSfailsafe để vượt lỗi.
5. **ARM/DISARM từ app:** profileCONTROL, ga min/RPYtrim, PreArm/EKF/landedstate mới, xác nhận riêng từng lệnh. Ghi intent/socketwrite/ACK/HEARTBEAT cùng logFC. RC phải tiếp tục khiFCARMtrong phiên hợp lệ; FC DISARM thu hồi quyền. Chỉ DISARMthường khi trênđất, không forceDISARMsau khi mấtWi-Fi.
6. **Mất điều khiển và kiểm tra dài:** ma trận dưới đây trước mọi xem xét bay. Không tuyên bốFLIGHTREADY dựa vào software/SITL/emulatorPASS.

## Ma trận cần thực hiện và lưu bằng chứng

| Tình huống | Phần cần xác minh trên ESP32/điện thoại thật | Trạng thái |
|---|---|---|
| Cài APK, landscape, đa điểm trên điện thoại | Không crash/ANR, hai pointer độc lập, ga giữ/RPYhồi tâm, thao tác vừa ngón | NOT VERIFIED |
| Wi-Fi khôngInternet cùng dữ liệu di động | Socket dùng đúngAP, không đổi sangcellular, pin/foreground/networkcallbacks | NOT VERIFIED |
| CH1–4 khiDISARMED vàARMED | RCbackend thực, hướng/trims/calibration, không đổi kênh phụ | NOT VERIFIED |
| ARM/DISARM vàmode | PreArmthực, ACK/HB, FCtừ chối đúng; khôngstickARM hoặcauxtrigger | NOT VERIFIED |
| STOP/ra nền/khóa màn hình/mấtfocus | TXcuối, byteskernelđếnmuộn, overrideexpiry vàFCfailsafethực | NOT VERIFIED |
| HB/RCechoquáhạn, TCPcòn hoặchalf-open | Appthu hồiquyền vàFCxử lý, không phát lại oldthrottle | NOT VERIFIED |
| TắtWi-Fi/APreset/ESP32reset | RC/GCSfailsafe theo đúngFS_THR_ENABLE/FS_GCS_ENABLE/timeout/mode | NOT VERIFIED |
| App/điện thoại treo/mấtnguồn | FCfailsafe vàdừng độc lập khôngphụthuộcWi-Fi | NOT VERIFIED |
| Reconnect nhiều lần | Ga min, xác nhậnmới, khôngoldRC/oldARM/oldmode | NOT VERIFIED |
| 30phút thời gian thật | Android/ESP32CPU/RAM/nhiệt/pin/RX/CRC/seq/jitter/reset, khôngARM/RCtrong telemetrysoak | NOT VERIFIED |
| INA219 vàLiPo3S | Điệnáp thực; dòng/%/dunglượng/time remaining chưađượcxácminh | NOT VERIFIED |
| Động cơ/thứtự/chiều/mixer/dừngđộclập | Cảbốnđầura vàESCnguồnthực; khôngsuytừservoPWMmôphỏng | NOT VERIFIED |

Log cần giữ: SHA-256APK, model/APIđiệnthoại, phiên bảnFC/paramsreadback, CSVapp(dropped/error), logcat, raw/traceFCđượcphépđọc vàtimestamp. Script `integration/telemetry_reliability.py` chỉ nhận, khôngphátbyte; dùng`--help`. Không mặc định bridge hỗ trợ nhiềuTCPclients cùng lúc. Bàisoak1800sFCtime accelerated ở0.2.1 khôngphảisoak30phútAndroid/Wi-Fi thực.
