# Kết quả29/10/2026 — candidate và phần có thể tái sử dụng

## Kết luận về nguồn và phạm vi

**Chưa hoàn thành yêu cầu tiếp nối app Google AI Studio.** Source/APK đó chưa vào workspace được: ZIP đính kèm cũ và ZIP mới đều vượt giới hạn chuyển32MiB. Đã kiểm tra các đường dẫn được cấp quyền và tất cả ref GitHub; không thiếu token đọc Git. Không biết hash ZIP/APKGG, chưa xác minh source/APKGG cùng phiên bản, chưa đọc các tài liệu lồng hoặc sửa các lớp `com.example`. Kiểm kê chính xác ở `../00_SOURCE_INVENTORY.md`, hash baseline ở `../00_SOURCE_BASELINE_HASHES.json`.

Các kết quả sau thuộc **candidate Codex `dev.drone.controller`, version0.2.1-protocol/code2** và thư viện chuẩn bị port, không phải APKGoogleAIStudio `com.aistudio.esp32drone.krvwpx`. APK này vẫn chỉ có đường livebench CH1±20µs/750ms, hai joystick xem trước và ARM/mode command khóa. Không đổi ứng dụng đó thành flight controller bằng một cờ thiếu bằng chứng.

## Kết quả thực thi

| Gate | Kết quả | Bằng chứng |
|---|---|---|
| Gradle clean+compile/test/lint/builddebug | PASS; lỗiSITL1470startup ban đầu đã tái hiện/sửa/hồi quy | logs/controller-20261029-final.log; diagnosis/first-1470-startup-failure.xml |
| JVM test toàn bộ | **182PASS,0failure,0error,0skipped** | junit/*.xml; lần xác minh cache cũng thực thi lại, không UP-TO-DATE |
| Protocol | **51testPASS**: XML301message/golden1470/fuzz/seq/signature | docs/PROTOCOL_REVIEW_20261029.md; loghost và XML |
| Authority model | **27testPASS**: BENCHARMEDrevoke, qualifiedflightARMEDcontinue, startup/watchdog/qualification | docs/CONTROL_AUTHORITY_20261029.md; XML |
| Reconnect | Một test chạy **100connection cycles**: noRC, nooldthrottle, nooldcalibration | ConnectionChurnTest.xml |
| RobolectricUI | **4testPASS** trên Androidframeworkhost API28 | RobolectricUiTest.xml; không phải thiết bị thật |
| SITL thật upstreamafeb9c49 | **3testPASS**, gồm1470→1500→1530 receiverless, sessionCH1, encoder2phươngthức/timeout với receiver mô phỏng | SitlIntegrationTest.xml +DataFlash/thưmụcSITL |
| Telemetrysoak | **1800giâyFC-time** trong **21.298giâywall**,14.667frame,0CRCgap/reboot/ARM; TXRC=0 | 30_TELEMETRY_SOAK.md; telemetry-soak/metrics.json |
| JVM replaysoak | PASS toàn trace, phân mảnh1…257byte, đúng hash,182test đã gồm testnày | TelemetrySoakReplayTest.xml; testresource |
| Python | **9testPASS**, gồm4soak+5recorder | logs/python-tests.log |
| Lint | **0error,1warningViewConstructor** đã phân loại, không suppress | lint-results-debug.xml/.txt |
| APKdebug+APKtest | BuildPASS; chữ kýdebugv2verifyPASS | logs/apk-verification.txt; artifacthash |
| AndroidAPI26emulator | **6/6instrumentationPASS**,0failed/skipped; actualMotionEvent/rotation/lifecycle | [báo cáoAndroid](../ANDROID_UI_20261029.md); ../android-ui-20261029/ và loginstrumentation |
| VisualreviewAPI26 | PARTIAL/BLOCKED: sauinstrumentation xuất hiệnSystemUIANR; ảnh giữ nguyên overlay, không coi là ảnh app khỏe | ../android-ui-20261029/system-ui-anr.png |
| AndroidAPI35emulator | Frameworkboot/package services bất ổn trước cài APK; không coi là appcrash | Báo cáoAndroid và log35; không tính PASS |

182 là số testcase JVM trong một lượt, bao gồm4Robolectric và3SITL; không cộng các số con lần nữa. Sáu instrumentation và9Python được báo riêng. Test timing30phútvirtual và soak1800giâyFC-time không tương đương30phútAndroid/Wi-Fi thời gian thật.

## Thay đổi đã kiểm thử trên candidate

- BảngCRC/length301message được sinh từ closure9XML official, pin commit/submodule+hash, không dùngcrcExtra0 cho unknown. Message có metadata nhưng chưa decoder vẫn CRC-valid và có counterunsupported riêng.
- Signedframe có13signaturebytes, bị từ chối theo policy không cókey/replayverifier; MAVLink1 được nhận diện và bỏ nguyên frame. Buffer280byte, sourcehistories128, timeoutfragment1500ms.
- Sequence FIRST/IN_ORDER/GAP/DUPLICATE/REORDERED theoSYS/COMP; duplicate/reorder vẫn đếm chẩn đoán nhưng không làm mới heartbeat/echo/ACK trong consumer. Không coi sequencegap là mất góiRF.
- Thống kê p50/p95/p99 khoảng cách ghiRC và jitterp95 từ mẫu socketwrite, bounded256; không gọi đó là độ trễ FC. Log có wall+monotonic time, queue128, giới hạn8MiB/file và20file của logger, quá giới hạn báo dropped/error.
- Authoritydomain phân biệt bench/flightcandidate. Productionqualification mặc địnhdeny; không có wireARM/motor command, không được dùng các flag giả trong test để nghiệm thu phần cứng. Module này **chưa nối vào UI/đường liveAPK**.

## Các trạng thái nghiệm thu độc lập

| Trạng thái | Phạm vi chính xác |
|---|---|
| **SOFTWARE TESTED** | CandidateCodex và các module tái sử dụng; không phải đã sửa appGG |
| **SITL TESTED** | Protocol/session trên upstreambaseafeb9c49; không gồm customESP32board/patch của binary thật |
| **HARDWARE RC INPUT VERIFIED** | Chỉ **USER-REPORTED cho APKGoogleAIStudio**: CH11470 trênMissionPlanner; candidate0.2.1 vẫn **NOT VERIFIED** |
| **FLIGHT READY NOT VERIFIED** | Mọi APK; chưa nghiệm thu động lực, dừng độc lập, failsafe, Wi-Fi thật, ARM/controllerlive |

Thông tin mới của người dùng:4ESCXXDHW30A đã hiệu chuẩn, quay ổn định từ1036µs, MOT_SPIN_ARM0.06/MOT_SPIN_MIN0.09. Đây là thông tin người dùng cung cấp, chưa đọc lại FC trong cloud. Không thay firmware/tham số; MotorTest thành công không xác minh đườngRC→ARM→mixer→motor của app.

## Blocker và phần chưa triển khai

Để sửa đúngbase cần ZIP chỉ source dưới32MiB hoặc source ở checkout được cấp quyền. TệpZIP mới `file_00000000cf1c8211b710d91c827b5183` cũng trả cùng lỗi transferlimit. Có thể bỏ APK và `ESP32_DRONE_ANDROID_APP.zip` lồng để giảm kích thước; helperPowerShell tùy chọn `scripts/prepare_ai_source.ps1` chỉ tạoZIPmới, chưa chạy trênWindows trong phiên này.

Chưa kiểm tra crashARM/ComposeUI/CRC/table/handshake của sourceGG; chưa portmodule vào đó; chưa triển khai liveflight, wireARM/DISARM/mode trên candidate; chưa có RC phần cứng của candidate; chưa đo Wi-Fi30phút thực/timeoutreceiverless/failsafethật. Không phân loại các mục này PASS dựa vào module/test của project khác.
