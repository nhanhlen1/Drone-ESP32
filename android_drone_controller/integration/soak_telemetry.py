#!/usr/bin/env python3
"""Soak telemetry SITL tăng tốc; chỉ127.0.0.1, không RC/ARM, không ghi tham số runtime.

PASS được đo bằng1800giây ATTITUDE.time_boot_ms thật, không đổi nhãn walltime
thành30phút. Hạ tầng độc lập phục vụ migration khi source Google AI có thể đọc.
"""
import argparse
import collections
import datetime
import hashlib
import json
import pathlib
import socket
import subprocess
import sys
import time


class BoundedCapture:
    def __init__(self, stream, limit=16 * 1024 * 1024):
        self.stream = stream
        self.limit = limit
        self.written = 0
        self.sha256 = hashlib.sha256()

    def write(self, data):
        if self.written + len(data) > self.limit:
            raise RuntimeError('Raw capture vượt giới hạn; dừng thay vì tăng bộ nhớ/tệp vô hạn')
        self.stream.write(data)
        self.sha256.update(data)
        self.written += len(data)


class SequenceStats:
    def __init__(self):
        self.previous = {}
        self.counts = collections.Counter()

    def observe(self, source, sequence):
        if source not in self.previous and len(self.previous) >= 64:
            self.counts['sources_rejected_bound'] += 1
            return
        old = self.previous.get(source)
        if old is not None:
            delta = (sequence - old) & 255
            if delta == 0:
                self.counts['duplicates'] += 1
                return
            if delta >= 128:
                self.counts['reordered_or_restart'] += 1
                return  # Do not move the high-water sequence backwards.
            self.counts['gaps'] += delta - 1
        self.previous[source] = sequence


class SimClockSpan:
    def __init__(self):
        self.source = None
        self.first = None
        self.last = None
        self.span_ms = 0
        self.reboots = 0

    def observe(self, source, boot_ms):
        if self.source is None:
            self.source = source
        if source != self.source:
            return
        if self.last is None:
            self.first = self.last = boot_ms
            return
        delta = (boot_ms - self.last) & 0xffffffff
        if delta >= 0x80000000:
            self.reboots += 1
            # A restart cannot satisfy an uninterrupted30minute simulated run.
            self.first = self.last = boot_ms
            self.span_ms = 0
            return
        self.span_ms += delta
        self.last = boot_ms


class SafeSender:
    """Wire allowlist applies even if the caller accidentally creates another type."""
    STREAMS = {0: 1_000_000, 1: 1_000_000, 24: 1_000_000, 30: 200_000}

    def __init__(self, sock):
        self.sock = sock
        self.bytes = 0
        self.messages = collections.Counter()

    def send(self, message, encoder):
        msgid = message.get_msgId()
        if msgid == 76:
            if message.command != 511 or int(message.param1) not in self.STREAMS:
                raise ValueError('Chỉ cho phép yêu cầu interval telemetry trong phép soak')
            if message.param2 != self.STREAMS[int(message.param1)]:
                raise ValueError('Interval không nằm trong cấu hình đo đã định nghĩa')
        elif msgid != 0:
            raise ValueError('Soak cấm RC, ARM, mode, actuator và parameter write')
        data = message.pack(encoder, force_mavlink1=False)
        self.sock.sendall(data)
        encoder.seq = (encoder.seq + 1) & 255
        self.messages[str(msgid)] += 1
        self.bytes += len(data)


def run(args):
    # Imported lazily: unit tests of bounds, timing and policy need no SDK/network.
    from pymavlink.dialects.v20 import ardupilotmega as mavlink

    binary = pathlib.Path(args.binary).resolve()
    if not binary.is_file():
        raise ValueError('Thiếu binary SITL đã build')
    evidence = pathlib.Path(args.output).resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    metrics = {'status': 'FAIL', 'environment': 'ArduPilot SITL upstream, localhost, accelerated simulation',
               'hardware_wifi_30_minute_test': 'NOT TESTED', 'rc_tx': 0, 'arm_commands_tx': 0,
               'target_simulated_seconds': args.sim_seconds, 'requested_speedup': args.speedup,
               'utc_started': datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'binary': str(binary), 'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest()}
    defaults = evidence / 'soak.parm'
    defaults.write_text('FRAME_CLASS 1\nFRAME_TYPE 1\nMAV_SYSID 42\nMAV_GCS_SYSID 255\nSERIAL0_PROTOCOL 2\n')
    with socket.socket() as reserve:
        reserve.bind(('127.0.0.1', 0))
        port = reserve.getsockname()[1]
    instance = 100 + port % 1000
    command = [str(binary), '--model', 'quad', '--speedup', str(args.speedup), '--instance', str(instance),
               '--serial0', 'tcp:' + str(port), '--serial1', 'none', '--serial2', 'none',
               '--defaults', str(defaults), '--home', '-35.363261,149.165230,584,0']
    metrics['command'] = command
    metrics['endpoint'] = '127.0.0.1:' + str(port)
    started = time.monotonic()
    process = None
    sock = None
    sequences = SequenceStats()
    sim = SimClockSpan()
    counts = collections.Counter()
    messages = collections.Counter()
    source_messages = collections.Counter()
    hb_times = {}
    max_hb_wall_gap = 0.0
    last_hb_sim = None
    last_hb_wall = 0.0
    target = None
    sender = None
    capture = None
    log_stream = (evidence / 'sitl.log').open('wb')
    capture_stream = (evidence / 'telemetry.raw.bin').open('wb')
    events = (evidence / 'frames.jsonl').open('w')
    try:
        process = subprocess.Popen(command, cwd=evidence, stdout=log_stream, stderr=subprocess.STDOUT)
        connect_deadline = started + min(15.0, args.wall_limit)
        while time.monotonic() < connect_deadline:
            if process.poll() is not None:
                raise RuntimeError('SITL kết thúc trước khi mở TCP; xem sitl.log')
            try:
                sock = socket.create_connection(('127.0.0.1', port), timeout=0.2)
                break
            except OSError:
                time.sleep(0.05)
        if sock is None:
            raise RuntimeError('Quá hạn chờ TCP SITL localhost')
        sock.settimeout(0.1)
        sender = SafeSender(sock)
        encoder = mavlink.MAVLink(None, srcSystem=255, srcComponent=190)
        parser = mavlink.MAVLink(None)
        parser.robust_parsing = True
        capture = BoundedCapture(capture_stream, args.capture_limit)
        configured = False
        while time.monotonic() - started < args.wall_limit:
            now = time.monotonic()
            sim_due = sim.last is not None and (last_hb_sim is None or ((sim.last-last_hb_sim) & 0xffffffff) >= 1000)
            if sim_due or now - last_hb_wall >= 1.0:
                sender.send(encoder.heartbeat_encode(mavlink.MAV_TYPE_GCS, mavlink.MAV_AUTOPILOT_INVALID,
                                                    0, 0, mavlink.MAV_STATE_ACTIVE), encoder)
                last_hb_wall = now
                last_hb_sim = sim.last
            try:
                data = sock.recv(65536)
            except socket.timeout:
                continue
            if not data:
                raise RuntimeError('SITL đóng TCP trước khi đủ thời gian mô phỏng')
            now = time.monotonic()
            capture.write(data)
            for message in parser.parse_buffer(data) or []:
                kind = message.get_type()
                if kind == 'BAD_DATA':
                    reason = str(getattr(message, 'reason', ''))
                    counts['crc_bad_frames' if 'CRC' in reason else 'noise_or_unknown_frames'] += 1
                    counts['unparsed_bytes'] += len(getattr(message, 'data', b''))
                    continue
                header = message.get_header()
                if getattr(header, 'incompat_flags', 0) & 1:
                    counts['signed_unverified_rejected'] += 1
                    continue
                if getattr(header, 'incompat_flags', 0) & ~1:
                    counts['unsupported_flags'] += 1
                    continue
                source = '%s:%s' % (message.get_srcSystem(), message.get_srcComponent())
                sequences.observe(source, header.seq)
                counts['valid_unsigned_frames'] += 1
                messages[str(message.get_msgId())] += 1
                source_messages[source] += 1
                if kind == 'HEARTBEAT':
                    if source in hb_times:
                        max_hb_wall_gap = max(max_hb_wall_gap, now-hb_times[source])
                    hb_times[source] = now
                    if message.base_mode & mavlink.MAV_MODE_FLAG_SAFETY_ARMED:
                        counts['armed_heartbeats'] += 1
                        raise RuntimeError('SITL bất ngờ ARMED trong bài chỉ đọc')
                    if message.autopilot == mavlink.MAV_AUTOPILOT_ARDUPILOTMEGA and message.get_srcComponent() == 1:
                        identity = (message.get_srcSystem(), message.get_srcComponent())
                        if target is not None and target != identity:
                            raise RuntimeError('Nhiều định danh autopilot trong phép soak')
                        target = identity
                if target is not None and not configured:
                    for msgid, interval in sender.STREAMS.items():
                        sender.send(encoder.command_long_encode(target[0], target[1], 511, 0,
                                                               msgid, interval, 0, 0, 0, 0, 0), encoder)
                    configured = True
                if kind == 'ATTITUDE' and target == (message.get_srcSystem(), message.get_srcComponent()):
                    sim.observe(source, message.time_boot_ms)
                if counts['valid_unsigned_frames'] <= 100_000:
                    events.write(json.dumps({'wall_elapsed_s': now-started, 'source': source,
                                             'msgid': message.get_msgId(), 'seq': header.seq,
                                             'attitude_boot_ms': message.time_boot_ms if kind == 'ATTITUDE' else None})+'\n')
                if sim.span_ms >= args.sim_seconds * 1000:
                    break
            if sim.span_ms >= args.sim_seconds * 1000:
                break
        if sim.span_ms < args.sim_seconds * 1000:
            raise RuntimeError('Chưa đủ1800giây FC-time trong wall-limit; không tính PASS')
        if sim.reboots or counts['crc_bad_frames'] or counts['armed_heartbeats']:
            raise RuntimeError('Soak có reboot/CRC/ARM ngoài kỳ vọng; xem thống kê')
        metrics['status'] = 'PASS'
    except Exception as error:
        metrics['error'] = str(error)
    finally:
        if sock is not None:
            sock.close()
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
            metrics['sitl_exit_after_own_termination'] = process.returncode
        capture_stream.close()
        events.close()
        log_stream.close()
        wall = time.monotonic()-started
        metrics.update({'wall_elapsed_seconds': wall, 'simulated_span_seconds': sim.span_ms/1000,
                        'first_attitude_boot_ms': sim.first, 'last_attitude_boot_ms': sim.last,
                        'effective_simulated_to_wall_ratio': sim.span_ms/1000/wall,
                        'possible_reboots': sim.reboots, 'counts': dict(counts),
                        'message_counts': dict(messages), 'per_source_message_counts': dict(source_messages),
                        'sequence': dict(sequences.counts), 'maximum_heartbeat_gap_wall_seconds': max_hb_wall_gap,
                        'rx_bytes': capture.written if capture else 0,
                        'capture_sha256': capture.sha256.hexdigest() if capture else None,
                        'tx_bytes': sender.bytes if sender else 0,
                        'tx_message_counts': dict(sender.messages) if sender else {}})
        (evidence/'metrics.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return 0 if metrics['status'] == 'PASS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', default='/workspace/tooling/ardupilot-target-sitl/build/sitl/bin/arducopter')
    parser.add_argument('--output', required=True)
    parser.add_argument('--sim-seconds', type=int, default=1800)
    parser.add_argument('--speedup', type=int, default=100)
    parser.add_argument('--wall-limit', type=int, default=120)
    parser.add_argument('--capture-limit', type=int, default=16*1024*1024)
    args = parser.parse_args()
    if args.sim_seconds < 1800 or not 1 <= args.speedup <= 200 or not 1 <= args.wall_limit <= 600:
        parser.error('Yêu cầu sim>=1800giây, speedup1..200, wall-limit1..600giây')
    if not 1 <= args.capture_limit <= 16*1024*1024:
        parser.error('capture-limit1byte..16MiB')
    return run(args)


if __name__ == '__main__':
    sys.exit(main())
