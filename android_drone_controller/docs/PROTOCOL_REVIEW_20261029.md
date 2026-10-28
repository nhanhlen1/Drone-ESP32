# Giao thức và thống kê nguồn — 29/10/2026

## Phạm vi và nguồn mã

Các thay đổi dưới đây áp dụng cho candidate Kotlin `dev.drone.controller` đang có trong workspace, không phải khẳng định đã sửa app Google AI Studio `com.example`. ZIP app Google AI Studio chưa đọc được do giới hạn chuyển tệp32MiB; nhiệm vụ kế thừa mã gốc của app đó vẫn cần source có thể truy cập. Module/test giao thức này độc lập Android và có thể chuyển sang base đó sau đối chiếu, không cần xây lại UI hoặc suy đoán mã chưa có.

## Bảng dialect đầy đủ

Candidate trước đây có metadata khoảng20message, đủ decoder đang dùng nhưng chưa đầy đủ luồng ArduPilot. Bảng mới sinh301message từ `ardupilotmega.xml` và toàn bộ9XML include: common,standard,minimal và các dialect vendor được upstream khai báo. Pin:

- Repository chính thức `mavlink/mavlink`, commit `e03dad40efb3a4009ad6d78de5e55997139ac2f3` được ArduPilot upstream base afeb9c49 dùng làm submodule.
- XML được giữ nguyên, generator XML parser từ `pymavlink==2.4.49`; hash từng XML và mã mavparse trong `MAVLINK_DIALECT_PROVENANCE.json`.
- `scripts/protocol/generate_dialect.py` kiểm commit, cây XML không có diff, phiên bản generator; sinh `GeneratedArduPilotDialect.kt`. CommonDialect là tên API cũ được giữ để tương thích, nay trỏ toàn bộ include closure ArduPilot.
- Generator in hai warning enum bitmask trong XML upstream; không sửa XML, không sửa CRC_EXTRA để né warning. Chúng không đổi định nghĩa wire-order của message.

Tái sinh bằng `/workspace/tooling/mavlink-venv/bin/python scripts/protocol/generate_dialect.py`. Không tải nguồn từ host lạ hoặc tắt TLS/checksum. Snapshot không bao gồm mọi dialect custom/tương lai; ID ngoài bảng vẫn không thể được xem là CRC-verified.

Ba trường hợp phân biệt rõ:

| Trường hợp | Counter / hành vi |
|---|---|
| ID có trong dialect, CRC đúng, decoder typed đã có | validFrames và telemetry nếu trường hợp lệ |
| ID có trong dialect, CRC đúng, chưa có decoder typed | validFrames + unsupportedMessages; vẫn trả MavFrame cho thống kê, không tạo telemetry giả |
| ID ngoài dialect | unknownMessages; bỏ đúng chiều dài, không dùng CRC_EXTRA=0 và không tăng crcErrors |

`crcErrors` chỉ tăng khi ID có định nghĩa CRC và checksum đo được sai. Chiều dài vượt maximum biết trước được tính malformed; không giả là CRC truyền sai. Payload MAVLink2 sau truncation vẫn tối thiểu1byte. SYS_STATUS cũ31byte và mới43byte cùng được kiểm thử; không dịch sai offset extension.

## Chữ ký, phân mảnh và phục hồi

Parser có buffer280byte: header10 + payload255 + CRC2 + signature13. Gói signed được giữ đủ13byte signature; không scan signature để phát hiện packet lồng. Không có key/replay verifier, vì vậy signed CRC-valid bị signedRejected, không vào telemetry hoặc sequence tracker. signedFrames mô tả header signed hoàn chỉnh đã thấy, không phải chữ ký đã xác thực. MAVLink1 được nhận diện và bỏ trọn frame, không giả hỗ trợV1.

Malformed/CRC/fragment quá hạn có counter resynchronizations; garbageBytes đếm byte rác. expirePartialFrame dùng clock monotonic, mặc định1500ms; feed tự kiểm trước dữ liệu mới, transport cần kiểm trong tick rỗi và thu hồi quyền RC khi timeout. Một payload hỏng có thể chứa0xFE/0xFF giống headerV1 dài. Không thể vừa tin chiều dài đó vừa scan phần chưa xác thực mà đảm bảo không nhận packet lồng giả; watchdog giới hạn thời gian phục hồi thay vì hứa mọi frame kế tiếp luôn được cứu ngay. Đây là trường hợp test có chủ đích, không gọi mất telemetry là đã áp dụng RC.

## Sequence theo từng nguồn

- Key `(systemId,componentId)`, chỉ frame unsigned đã kiểm CRC được đếm, kể cả message chưa có typed decoder. Không lấy một lastSequence toàn cục cho nhiều nguồn.
- Delta modulo256:0=duplicate;1=in-order;2..127=gaps;128..255=late/reordered. Frame muộn không kéo high-water mark lùi rồi gây gap giả ở gói tiếp theo. Wrap255→0 bình thường.
- Khoảng gap chưa chứng minh mất góiRF: có thể do frame CRC sai, frame signed bị từ chối, dialect unknown, filter bridge hoặc reorder. Counter không phải%loss hoặc phép đo chất lượng sóng.
- Mỗi MavFrame mang sequenceDisposition FIRST/IN_ORDER/GAP/DUPLICATE/REORDERED; parser vẫn trả mọi frame CRC-valid cho chẩn đoán. Consumer điều khiển phải bỏ DUPLICATE/REORDERED khi cập nhật tuổi HEARTBEAT, RC echo và command ACK; không được dùng gói cũ lặp lại để giữ quyền điều khiển. Đây là thứ tự8bit, không phải chống replay có xác thực.
- Lùi sequence không chứng minh FC reboot. resetSourceSequence chỉ gọi sau bằng chứng reboot độc lập; reset toàn parser/connection xóa counter và fragment cũ. Không tự tạo lại quyền điều khiển từ reset này.
- Giữ tối đa128nguồn theo thứ tự dùng; sequenceSourceEvictions báo khi loại nguồn cũ, không tăng memory vô hạn do các SYS/COMP khác nhau.

## Kiểm thử thực hiện

Host Kotlin compiler2.1.0/JVM17 chạy bằng JDK21, JUnit4.13.2: **51/51 test protocol PASS** gồm30test trước và21test mới. Đây là test JVM module, không thay cho Gradle/APK/SITL toàn ứng dụng mà root chạy riêng. Log: `/workspace/tooling/logs/protocol-upgrade-20261029.log`.

21test mới bao phủ metadata301message, vector CH1=1470 từ pymavlink độc lập,5message dialect chưa có decoder, CRC thật vs unknown, nhiều nguồn, duplicate/reorder/wrap, frame sai CRC không được cập nhật sequence, reboot reset có chủ đích, reset connection, fragment watchdog, signed/signature, incompatibility flag, randomnoise có byte magic, toàn301message maximum length qua random TCP fragment, và200nguồn chống tăng storage không giới hạn. Bốn test bổ sung đối chiếu disposition từng frame ở read gộp/phân mảnh, nhiều nguồn, wrap/reset và tương thích constructor dùng trong test. Việc consumer thu hồi quyền ở stale telemetry được root kiểm thử riêng.

Vector `TX_OVERRIDE_1470` dùng sender255/component190,target1/component1 vàCH1=1470,CH2=1500,CH3=1100,CH4=1500, các kênh khác ignore. Đây là byte-level regression cho giá trị người dùng đã quan sát trong MP, **không** phải tái kiểm chứng Google AI Studio source hay phần cứng trong phiên này. Script fixture `reference_dialect_vectors.py` pin pymavlink2.4.49, không gọi encoder Kotlin để tạo expected bytes.
