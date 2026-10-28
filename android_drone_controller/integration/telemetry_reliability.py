#!/usr/bin/env python3
"""Đo telemetry thụ động, Python3 chuẩn; KHÔNG bao giờ ghi dữ liệu lên socket."""
import argparse
import collections
import json
import pathlib
import socket
import struct
import time

# CRC_EXTRA common dialect (MAVLink upstream); unknown messages are recorded,
# never described as CRC-verified. Signing is counted but never authenticated.
EXTRAS = {0:50,1:124,20:214,22:220,24:24,29:115,30:39,33:104,36:222,
          65:118,69:243,70:124,76:152,77:143,111:34,147:154,148:178,193:71,253:83}

def crc_x25(data, extra):
    value = 0xffff
    for byte in bytes(data) + bytes([extra]):
        tmp = byte ^ (value & 255)
        tmp ^= (tmp << 4) & 255
        value = ((value >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 65535
    return value

class Monitor:
    def __init__(self):
        self.buffer = bytearray()
        self.stats = collections.Counter()
        self.messages = collections.Counter()
        self.sequences = {}
        self.last_heartbeat = {}
        self.max_heartbeat_gap_seconds = 0.0
        self.last_boot = {}
        self.possible_reboots = 0
        self.arm = {}

    def feed(self, data, timestamp):
        self.stats['rx_bytes'] += len(data)
        self.buffer.extend(data)
        while self.buffer:
            if self.buffer[0] not in (0xfd, 0xfe):
                self.stats['garbage_bytes'] += 1
                del self.buffer[0]
                continue
            v2 = self.buffer[0] == 0xfd
            header = 10 if v2 else 6
            if len(self.buffer) < header: break
            length = self.buffer[1]
            signed = bool(v2 and self.buffer[2] & 1)
            total = header + length + 2 + (13 if signed else 0)
            if len(self.buffer) < total: break
            msgid = int.from_bytes(self.buffer[7:10], 'little') if v2 else self.buffer[5]
            extra = EXTRAS.get(msgid)
            # A complete unknown frame can be skipped by declared framing, but
            # cannot provide sequence, heartbeat or reboot evidence.
            if extra is None:
                self.stats['unknown_unverified_frames'] += 1
                del self.buffer[:total]
                continue
            if v2 and self.buffer[2] & ~1:
                self.stats['unsupported_flags'] += 1
                del self.buffer[:total]
                continue
            expected = int.from_bytes(self.buffer[header+length:header+length+2], 'little')
            if crc_x25(self.buffer[1:header+length], extra) != expected:
                self.stats['crc_errors'] += 1
                del self.buffer[0]
                continue
            if signed:
                self.stats['signed_unverified_frames'] += 1
                del self.buffer[:total]
                continue
            seq, sysid, compid = self.buffer[4:7] if v2 else self.buffer[2:5]
            peer = '%s:%s' % (sysid, compid)
            payload = bytes(self.buffer[header:header+length])
            del self.buffer[:total]
            self.stats['crc_valid_unsigned_frames'] += 1
            self.messages[str(msgid)] += 1
            previous = self.sequences.get(peer)
            if previous is not None:
                delta = (seq - previous) & 255
                if delta == 0: self.stats['duplicate_sequence'] += 1
                elif 2 <= delta <= 127: self.stats['observed_sequence_gaps'] += delta-1
                elif delta >= 128: self.stats['out_of_order_or_reset_sequence'] += 1
            self.sequences[peer] = seq
            if msgid == 0:
                p = payload.ljust(9,b'\0')
                previous = self.last_heartbeat.get(peer)
                if previous is not None:
                    self.max_heartbeat_gap_seconds = max(self.max_heartbeat_gap_seconds,timestamp-previous)
                self.last_heartbeat[peer] = timestamp
                self.arm[peer] = 'ARMED' if p[6] & 128 else 'DISARMED'
            if msgid in (30,33,65) and len(payload) >= 4:
                boot = struct.unpack_from('<I',payload)[0]
                key = (peer,msgid)
                previous = self.last_boot.get(key)
                if previous is not None and previous > boot + 5000:
                    self.possible_reboots += 1
                self.last_boot[key] = boot

    def report(self, duration, timestamp):
        ages = {peer:round(timestamp-at,3) for peer,at in self.last_heartbeat.items()}
        max_gap=max([self.max_heartbeat_gap_seconds]+list(ages.values()))
        return {'duration_seconds':round(duration,3),'statistics':dict(self.stats),
                'messages':dict(self.messages),'heartbeat_age_seconds':ages,
                'max_heartbeat_gap_seconds':round(max_gap,3),'last_arm_observation':self.arm,
                'possible_reboot_observations':self.possible_reboots,
                'interpretation':'Đo thụ động; sequence gap không đồng nghĩa packet loss Wi-Fi. '
                'CRC không xác thực nguồn. Không chứng minh RC/failsafe/động cơ hoạt động.',
                'tx_bytes':0,'rc_commands_sent':0}

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--host',default='192.168.4.1')
    ap.add_argument('--port',type=int,default=5760)
    ap.add_argument('--duration',type=float,default=1800)
    ap.add_argument('--output',type=pathlib.Path,default=pathlib.Path('telemetry'))
    ap.add_argument('--replay',type=pathlib.Path,help='Đọc lại .jsonl chứa thời gian và bytes; không kết nối mạng')
    args=ap.parse_args()
    if args.duration<=0 or not 1<=args.port<=65535: ap.error('Thời lượng/port không hợp lệ')
    if args.output.with_suffix('.json').exists() or (not args.replay and args.output.with_suffix('.jsonl').exists()):
        ap.error('Tên output đã tồn tại; chọn tên mới để giữ nguyên bằng chứng cũ')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    monitor=Monitor();started=time.monotonic();now=started
    events=[];exitcode=0
    if args.replay:
        initial=None
        with args.replay.open(encoding='utf-8') as source:
            for line in source:
                record=json.loads(line);now=record['elapsed_seconds']
                if initial is None: initial=now
                monitor.feed(bytes.fromhex(record['hex']),now)
        duration=now-(initial or 0)
    else:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        # No send/sendall/writer exists in this tool. A quiet FC can legitimately
        # emit no packets without GCS heartbeat; report that as NOT VERIFIED.
        try:
            with socket.create_connection((args.host,args.port),timeout=5) as connection, \
                 args.output.with_suffix('.jsonl').open('x',encoding='utf-8') as capture:
                connection.settimeout(1)
                events.append({'event':'tcp_connected','elapsed_seconds':0})
                while time.monotonic()-started<args.duration:
                    now=time.monotonic()
                    try:data=connection.recv(8192)
                    except socket.timeout:continue
                    now=time.monotonic()
                    if not data:
                        events.append({'event':'socket_closed','elapsed_seconds':now-started})
                        exitcode=2;break
                    elapsed=now-started
                    capture.write(json.dumps({'elapsed_seconds':elapsed,'hex':data.hex()})+'\n')
                    monitor.feed(data,elapsed)
        except (OSError,KeyboardInterrupt) as error:
            events.append({'event':'stopped','reason':str(error),'elapsed_seconds':time.monotonic()-started})
            exitcode=2
        duration=time.monotonic()-started;now=duration
    result=monitor.report(duration,now)
    result['events']=events
    result['outcome']='OBSERVED' if monitor.stats['crc_valid_unsigned_frames'] else 'NOT VERIFIED'
    with args.output.with_suffix('.json').open('x',encoding='utf-8') as report:
        report.write(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return exitcode if result['outcome']!='NOT VERIFIED' else 2

if __name__=='__main__':raise SystemExit(main())
