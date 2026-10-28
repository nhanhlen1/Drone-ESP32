import unittest
import pathlib,sys,tempfile,subprocess,json
from telemetry_reliability import Monitor,crc_x25

def frame(seq=0,msg=0,payload=bytes([0,0,0,0,2,3,0,3,3]),extra=50):
    b=bytes([253,len(payload),0,0,seq,1,1,msg,0,0])+payload
    return b+crc_x25(b[1:],extra).to_bytes(2,'little')

class ReliabilityTest(unittest.TestCase):
    def test_fragmentation_coalescing_gap_and_heartbeat_age(self):
        m=Monitor(); a=frame(254);b=frame(0)
        m.feed(a[:4],0);self.assertEqual(0,m.stats['crc_valid_unsigned_frames'])
        m.feed(a[4:]+b,1)
        self.assertEqual(2,m.stats['crc_valid_unsigned_frames'])
        self.assertEqual(1,m.stats['observed_sequence_gaps'])
        self.assertEqual(2,m.report(3,3)['heartbeat_age_seconds']['1:1'])
        self.assertEqual(0,m.report(3,3)['tx_bytes'])
    def test_crc_fault_resynchronizes_without_accepting_invalid_arm(self):
        invalid=bytearray(frame());invalid[16]=128
        m=Monitor();m.feed(invalid+frame(1),0)
        self.assertEqual(1,m.stats['crc_errors'])
        self.assertEqual(1,m.stats['crc_valid_unsigned_frames'])
        self.assertEqual('DISARMED',m.arm['1:1'])
    def test_duplicates_reset_and_unknown_are_distinct(self):
        m=Monitor();m.feed(frame(10)+frame(10)+frame(2),0)
        m.feed(frame(3,msg=250),0)
        self.assertEqual(1,m.stats['duplicate_sequence'])
        self.assertEqual(1,m.stats['out_of_order_or_reset_sequence'])
        self.assertEqual(1,m.stats['unknown_unverified_frames'])
        self.assertEqual(0,m.stats['observed_sequence_gaps'])
    def test_signed_frames_never_treated_as_trusted_heartbeat(self):
        b=bytearray(frame());b[2]=1;b[-2:]=crc_x25(b[1:-2],50).to_bytes(2,'little');b+=bytes(13)
        m=Monitor();m.feed(b,0)
        self.assertEqual(1,m.stats['signed_unverified_frames'])
        self.assertEqual({},m.last_heartbeat)

    def test_cli_replay_is_offline_and_preserves_existing_report(self):
        with tempfile.TemporaryDirectory() as root:
            root=pathlib.Path(root);capture=root/'capture.jsonl';output=root/'nested'/'analysis'
            capture.write_text(json.dumps({'elapsed_seconds':1,'hex':frame().hex()})+'\n')
            command=[sys.executable,str(pathlib.Path(__file__).with_name('telemetry_reliability.py')),
                     '--replay',str(capture),'--output',str(output)]
            result=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            report=output.with_suffix('.json');contents=report.read_bytes()
            self.assertEqual(0,json.loads(contents)['tx_bytes'])
            again=subprocess.run(command,capture_output=True,text=True)
            self.assertNotEqual(0,again.returncode)
            self.assertEqual(contents,report.read_bytes())

if __name__=='__main__':unittest.main()
