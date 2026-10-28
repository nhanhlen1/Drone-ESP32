# Soak telemetry SITL tăng tốc —29/10/2026

**PASS cho30phút thời gian mô phỏng; không phải30phút wall-clock hoặc Wi-Fi thật.** Đây là hạ tầng kiểm thử độc lập của candidate hiện có, không được coi là đã migrate/kiểm thử source Google AI Studio còn bị chặn chuyển tệp.

## Lệnh và kết quả thực

```bash
/workspace/tooling/mavlink-venv/bin/python integration/soak_telemetry.py \
  --output reports/20261029/telemetry-soak \
  --sim-seconds 1800 --speedup 100 --wall-limit 120
```

Lệnh đã thực thi, exit0. Bắt đầu01:06:42 ngày29/10/2026 UTC+7 (`2026-10-28T18:06:42.651913+00:00` trong metadata máy). SITL upstream base `afeb9c4941543d97f3e04fecf257b8f598f49a6a`, binarySHA256 `41fd63749fa164c226a23ee395e683064553660f9b202581e94ba952ced6c9bf`.

| Phép đo | Kết quả |
|---|---:|
| ATTITUDE.time_boot_ms đầu/cuối |5224→1805224|
| Thời gian FC thực bao phủ |1800.000giây|
| Wall-clock elapsed |21.298329534giây|
| Tỉ lệ mô phỏng/wall đo được |84.51367x|
| RX raw TCP |576505byte|
| Frame unsigned CRC hợp lệ theo pymavlink2.4.49 |14667|
| RC/ARM command phát |0/0|
| HEARTBEAT GCS phát |1801|
| COMMAND_LONG511 yêu cầu interval chỉ đọc |4|
| Sequence gap quan sát theo source |0|
| CRC hỏng/ARMED heartbeat/reboot phát hiện |0/0/0|
| HEARTBEAT gap wall lớn nhất |0.032324826giây|

Frame nhận: HEARTBEAT1803, SYS_STATUS1802, ATTITUDE9001, GPS_RAW_INT1800, COMMAND_ACK4, STATUSTEXT14, TIMESYNC180, GPS_GLOBAL_ORIGIN1, HOME_POSITION1, PARAM_VALUE61. Không lấy một số message chưa có decoder UI rồi báo false CRC alarm.

## Bằng chứng

- `telemetry-soak/metrics.json`: metadata, commandline đầy đủ, clock span, counts, binary/captureSHA256 và tình trạng kết thúc process.
- `telemetry-soak/telemetry.raw.bin`: toàn bộ RX TCP dùng cho replay JVM parser. SHA256 `0c65b55c1322a10073f6b76240659aa8ed5585ac97aa7dd86248eea6eb0ecc05`.
- `telemetry-soak/frames.jsonl`: timestamp wallmonotonic, SYS/COMP, msgid, sequence, boot timestamp ATTITUDE.
- `telemetry-soak/sitl.log`, `soak.parm`: process riêng trong thư mục evidence, cổng loopback tạm, startup parameters mô phỏng. Không kết nối192.168.4.1, không thay parameter runtime hoặc firmware ESP32.
- `telemetry-soak-command.log`: stdout thực của command. Process SITL được chính script dừng và wait sau phép đo, không giữ daemon nền.

## Giới hạn và kiểm thử công cụ

Chỉ dùng127.0.0.1 và process ArduPilot SITL do script tự khởi động. SafeSender có allowlist wire, chỉ HEARTBEAT GCS và COMMAND_LONG511 với bốn message/rate cố định; từ chối RC_CHANNELS_OVERRIDE, MANUAL_CONTROL, ARM, parameterwrite và modecommand. Raw capture giới hạn16MiB, eventlog100000frame; không phát backlog hoặc tạo tệp vô hạn.

Đã chạy4testPython, exit0: sequence nhiều source/wrap/duplicate/reorder; clockspan tách source/reboot/wrap; giới hạn capture không ghi thêm phần dư; wireguard từ chối RC/ARM/parameter/badinterval. Lệnh: `python3 -m unittest discover -s integration -p 'test_soak_telemetry.py' -v`.

Soak này kiểm chứng nguồn telemetry thật của ArduPilot trên30phút đồng hồ mô phỏng và cung cấp capture dài cho parser app. Nó **không** chứng minh Android sống30phút wall-clock, heap/ANR của UI, Wi-Fi ESP32 không Internet, ứng dụng Google AI Studio, nguồn/motor hoặc failsafe phần cứng. Các cấp đó vẫn NOT TESTED/NOT VERIFIED cho tới khi có phép đo tương ứng.
