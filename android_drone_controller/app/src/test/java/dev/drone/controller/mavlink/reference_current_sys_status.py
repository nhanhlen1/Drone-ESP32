"""Reproduce current SYS_STATUS reference using verified, pinned official XML.
Run with pymavlink==2.4.49 in /workspace/tooling/mavlink-reference/bin/python.
The PyPI package's bundled dialect predates the 3 SYS_STATUS extension masks.
Only this message and its verbatim enum definitions are selected, because the
old generator's schema cannot validate newer unrelated MAV_CMD metadata.
Message fields/enums are not modified. XML validation remains enabled.
"""
from copy import deepcopy
from hashlib import sha256
from importlib.metadata import version
from pathlib import Path
from tempfile import TemporaryDirectory
import importlib.util
import urllib.request
import xml.etree.ElementTree as E
from pymavlink.generator import mavgen

assert version("pymavlink") == "2.4.49"
COMMIT = "9324a919111e64449ee2e49b839f75730e733da3"
URL = f"https://raw.githubusercontent.com/mavlink/mavlink/{COMMIT}/message_definitions/v1.0/common.xml"
data = urllib.request.urlopen(URL, timeout=30).read()
assert sha256(data).hexdigest() == "cfda5c0766698cb0f4d5725cd22fb38bf74cea1ba516c98a11b7c36479dae2c6"
source = E.fromstring(data)
message = source.find("messages/message[@id='1']")
subset = E.Element("mavlink")
E.SubElement(subset, "version").text = "3"
E.SubElement(subset, "dialect").text = "0"
enums = E.SubElement(subset, "enums")
for name in sorted({f.attrib["enum"] for f in message.findall("field") if "enum" in f.attrib}):
    enums.append(deepcopy(source.find(f"enums/enum[@name='{name}']")))
E.SubElement(subset, "messages").append(deepcopy(message))
with TemporaryDirectory() as directory:
    xml = Path(directory) / "sys_status.xml"
    E.ElementTree(subset).write(xml, encoding="utf-8", xml_declaration=True)
    generated = Path(directory) / "current_sys_status.py"
    assert mavgen.mavgen(mavgen.Opts(str(generated), wire_protocol="2.0", language="Python3"), [str(xml)])
    spec = importlib.util.spec_from_file_location("current_sys_status_reference", generated)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    sender = m.MAVLink(None, srcSystem=42, srcComponent=1)
    sender.seq = 17
    message = m.MAVLink_sys_status_message(
        0x80000001, 7, 5, 250, 11870, -123, 64, 10, 2, 3, 4, 5, 6,
        0x80000001, 0x10203040, 0xf1234567,
    )
    assert message.crc_extra == 124
    encoded = message.pack(sender).hex()
header = f'''package dev.drone.controller.mavlink

// Official common.xml at mavlink/mavlink commit {COMMIT}.
// Independent pymavlink-generated 43-byte SYS_STATUS with three extension masks.
// Regenerate with reference_current_sys_status.py; legacy 31-byte vector remains in GoldenVectors.
internal object CurrentVectors {{
    const val RX_SYS_STATUS_43 = "{encoded}"
}}
'''
Path(__file__).with_name("CurrentVectors.kt").write_text(header)
print("Generated current official SYS_STATUS, 43 payload bytes, CRC_EXTRA 124")
