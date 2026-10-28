"""Golden vectors từ thư viện MAVLink độc lập; không dùng encoder Kotlin."""
from pathlib import Path
import importlib.metadata
from pymavlink.dialects.v20 import ardupilotmega as mav
assert importlib.metadata.version('pymavlink')=='2.4.49'
packets={
 'TX_OVERRIDE_1470':mav.MAVLink_rc_channels_override_message(1,1,1470,1500,1100,1500,65535,65535,65535,65535),
 'RX_VFR_HUD':mav.MAVLink_vfr_hud_message(1.5,2.5,123,4,42.5,-.25),
 'RX_SENSOR_OFFSETS':mav.MAVLink_sensor_offsets_message(1,-2,3,.125,101325,2500,.1,.2,.3,1.1,1.2,1.3),
 'RX_QUATERNION':mav.MAVLink_attitude_quaternion_message(123456,1,0,0,0,.1,.2,.3),
 'RX_AHRS':mav.MAVLink_ahrs_message(.1,.2,.3,.4,.5,.6,.7),
 'RX_RC_CHANNELS_RAW':mav.MAVLink_rc_channels_raw_message(123456,0,1470,1500,1100,1500,65535,65535,65535,65535,255),
}
lines=['package dev.drone.controller.mavlink','','/** Independent pymavlink2.4.49 ardupilotmega-v2 fixtures. */','internal object DialectVectors {']
for i,(key,message) in enumerate(packets.items()):
 encoder=mav.MAVLink(None,srcSystem=255 if key.startswith('TX') else 42,srcComponent=190 if key.startswith('TX') else 1);encoder.seq=0 if key.startswith('TX') else 100+i
 lines.append(f'    const val {key}="{message.pack(encoder).hex()}"')
lines+=['}','']
Path(__file__).with_name('DialectVectors.kt').write_text('\n'.join(lines))
