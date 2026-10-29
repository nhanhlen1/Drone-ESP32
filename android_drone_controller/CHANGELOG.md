# Thay đổi

## 0.3.0-rc-control —29/10/2026

Ứng dụng Codex trở thành sản phẩm chính theo đặc tả mới. Nối hai joystick Mode2 qua ViewModel/ControlAuthority vào schedulerTCP duy nhất cho CH1–4; tách benchDISARMED và phiên điều khiển tiếp tục khiARMED, giữ phépCH1cũ. Thêm ARM/DISARMthường và mode với xác nhận riêng, wireencoder/ACK/HEARTBEAT, EXTENDED_SYS_STATE và PreArm/EKF/readback gates. Giao diện ngang/HUD, CSV gồm quyền/kênh/lệnh/timing và tests phần mềm/SITL/emulator được ghi trong `reports/20261029-live/VALIDATION.md`. VersionCode3; không đổi firmware/tham số FC thật.

Giữ checkpoint0.2.1 và WIP783da6b để rollback. Trạng thái phần cứng và FLIGHT READY vẫn NOT VERIFIED.

## 0.2.1-protocol —29/10/2026

CandidateCodex, không phải bản chuyển từ GoogleAIStudio. Thêm metadataMAVLink301message, sequenceper-source+freshnessguard, boundedfragmenttimeout, metricsRCjitter/heartbeat, boundedpersistentlogs và walltime. Thêm authoritydomain bench/qualifiedflight chưa nối live, protocol/golden/fuzz/100reconnect/soakreplay/UItests. Build/lint/SITL/software checks và instrumentationAPI26 được báo riêng; sourceGG vẫn BLOCKED bởi transferlimit.

Package `dev.drone.controller`, versionCode2; cài cập nhật candidatecode1 hoặc cạnh appGG có applicationId khác. APKdebug ký development. Không thay firmware/FCparameter; ARM/mode/liveflight trên candidate vẫn khóa.

## 0.2.0-bench —28/10/2026

Candidatebenchđầu: telemetryTCP/MAVLink2, khám pháFC, CH1hữu hạn, joystickoffline, CSV và121test/SITL. Các báo cáo lịch sử giữ nguyên; không dùng thay kết quả bản mới.
