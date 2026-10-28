"""Regenerate GoldenVectors.kt using independent pymavlink==2.4.49.
Run: /workspace/tooling/mavlink-reference/bin/python reference_vectors.py
The production Android app has no Python or pymavlink dependency.
Official common-v2 dialect serialized here checks wire layout and CRC_EXTRA.
"""
from importlib.metadata import version
from pathlib import Path
from pymavlink.dialects.v20 import common as m

assert version("pymavlink") == "2.4.49", "Reference generator is pinned for reproducibility"
app = m.MAVLink(None, srcSystem=255, srcComponent=190)
app.seq = 0
vectors = {"TX_HEARTBEAT": m.MAVLink_heartbeat_message(6, 8, 0, 0, 4, 3).pack(app).hex()}
app.seq = 1
vectors["TX_OVERRIDE"] = m.MAVLink_rc_channels_override_message(
    1, 1, 1100, 1500, 1100, 1500, 65535, 65535, 65535, 65535
).pack(app).hex()
fc = m.MAVLink(None, srcSystem=42, srcComponent=1)
messages = {
    "RX_HEARTBEAT": m.MAVLink_heartbeat_message(2, 3, 128, 0x03040506, 4, 3),
    "RX_SYS_STATUS": m.MAVLink_sys_status_message(
        0x80000001, 7, 5, 250, 11870, -123, 64, 10, 2, 3, 4, 5, 6
    ),
    "RX_ATTITUDE": m.MAVLink_attitude_message(0xf1234567, .125, -.25, 1.5, .01, -.02, .03),
    "RX_GPS_RAW_INT": m.MAVLink_gps_raw_int_message(
        1234567890123, 3, 137560123, 1005012345, 42000, 75, 120, 234, 9000, 14,
        45000, 500, 700, 50, 120, 9000
    ),
    "RX_RC_CHANNELS": m.MAVLink_rc_channels_message(
        123456, 18, 1100, 1500, 1100, 1500, 1700, 1800, 1900, 2000,
        1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 200
    ),
    "RX_STATUSTEXT": m.MAVLink_statustext_message(4, b"BENCH ONLY - REMOVE PROPELLERS", 513, 2),
}
for sequence, (name, msg) in enumerate(messages.items(), start=10):
    fc.seq = sequence
    vectors[name] = msg.pack(fc).hex()
# Deterministic public test signing key only. The application cannot authenticate
# signatures and must reject this valid, independently signed reference frame.
fc.signing.secret_key = bytes(range(32))
fc.signing.sign_outgoing = True
fc.signing.link_id = 7
fc.signing.timestamp = 123456
fc.seq = 16
vectors["RX_SIGNED_HEARTBEAT"] = messages["RX_HEARTBEAT"].pack(fc).hex()
header = '''package dev.drone.controller.mavlink

// Generated with pymavlink==2.4.49, dialects.v20.common. See reference_vectors.py.
// These golden bytes are independent of the production Kotlin CRC/encoder.
internal object GoldenVectors {
'''
body = ''.join(f'    const val {name} = "{data}"\n' for name, data in vectors.items())
Path(__file__).with_name("GoldenVectors.kt").write_text(header + body + '}\n')
print("Generated", len(vectors), "independent MAVLink2 reference vectors")
