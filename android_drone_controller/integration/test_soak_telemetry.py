import io
import unittest
from types import SimpleNamespace

from soak_telemetry import BoundedCapture, SafeSender, SequenceStats, SimClockSpan


class SoakTelemetryTest(unittest.TestCase):
    def test_source_sequence_interleave_rollover_duplicates_and_reorder(self):
        stats = SequenceStats()
        for source, sequence in [('1:1',254),('2:1',10),('1:1',255),('2:1',11),
                                 ('1:1',0),('1:1',0),('1:1',255),('1:1',2)]:
            stats.observe(source, sequence)
        self.assertEqual(1, stats.counts['gaps'])
        self.assertEqual(1, stats.counts['duplicates'])
        self.assertEqual(1, stats.counts['reordered_or_restart'])

    def test_sim_span_requires_same_source_and_resets_on_reboot_but_accepts_wrap(self):
        clock = SimClockSpan()
        clock.observe('1:1', 0xfffffff0)
        clock.observe('2:1', 9000000)
        clock.observe('1:1', 16)
        self.assertEqual(32, clock.span_ms)
        clock.observe('1:1', 1800016)
        self.assertEqual(1800032, clock.span_ms)
        clock.observe('1:1', 100)
        self.assertEqual(0, clock.span_ms)
        self.assertEqual(1, clock.reboots)

    def test_capture_rejects_overflow_without_partial_extra_write(self):
        output = io.BytesIO()
        capture = BoundedCapture(output, 4)
        capture.write(b'abcd')
        with self.assertRaises(RuntimeError):
            capture.write(b'e')
        self.assertEqual(b'abcd', output.getvalue())
        self.assertEqual(4, capture.written)

    def test_read_only_wire_guard_rejects_arm_rc_param_and_bad_interval(self):
        class Socket:
            def __init__(self): self.data = []
            def sendall(self, value): self.data.append(value)

        class Message:
            def __init__(self, msgid, command=511, interval=200000):
                self.msgid, self.command, self.param1, self.param2 = msgid, command, 30, interval
            def get_msgId(self): return self.msgid
            def pack(self, encoder, force_mavlink1): return b'valid-test-wire'

        sock = Socket()
        sender = SafeSender(sock)
        encoder = SimpleNamespace(seq=255)
        for value in [Message(70), Message(69), Message(23), Message(76,400), Message(76,511,1)]:
            with self.assertRaises(ValueError): sender.send(value, encoder)
        self.assertEqual([], sock.data)
        sender.send(Message(76), encoder)
        self.assertEqual(0, encoder.seq)
        self.assertEqual({'76':1}, dict(sender.messages))


if __name__ == '__main__':
    unittest.main()
