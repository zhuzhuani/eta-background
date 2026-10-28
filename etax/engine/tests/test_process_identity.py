"""Test PID discovery against procfs fixtures, including app_process argv rewriting."""
import pathlib
import subprocess
import tempfile
import unittest

ENGINE = pathlib.Path(__file__).resolve().parents[1]

class ProcessIdentityTest(unittest.TestCase):
    def discover(self, fixtures, listener=None):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            proc = root / 'proc'; proc.mkdir(); (proc / 'net').mkdir()
            (proc / 'net/tcp').write_text('0: 0100007F:221D 00000000:0000 0A 0 0 0 0 0 9001\n' if listener else '')
            for pid, comm, argv, uid in fixtures:
                directory = proc / str(pid); directory.mkdir(); (directory / 'fd').mkdir()
                (directory / 'comm').write_text(comm + '\n')
                (directory / 'cmdline').write_bytes(b'\0'.join(arg.encode() for arg in argv) + b'\0')
                (directory / 'status').write_text(f'Uid:\t{uid}\t{uid}\t{uid}\t{uid}\n')
                if pid == listener: (directory / 'fd/5').symlink_to('socket:[9001]')
            source = (ENGINE / 'vd.sh').read_text().split('\ncase "${1:-}"')[0]
            source = source.replace('/data/local/tmp/eta', str(root / 'eta')).replace('/proc/', str(proc) + '/')
            script = root / 'probe.sh'; script.write_text(source + '\nengine_pids\n')
            result = subprocess.run(['sh', str(script)], capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            return set(result.stdout.split())

    def test_new_name_legacy_full_argv_and_other_ports(self):
        self.assertEqual({'10', '11'}, self.discover([
            (10, 'eta-vdrive-8733', ['eta-vdrive-8733'], 0),
            (11, 'VDrive', ['app_process', '/system/bin', 'VDrive', '--tcp=8733'], 0),
            (12, 'VDrive', ['app_process', '/system/bin', 'VDrive', '--tcp=8736'], 0),
            (13, 'eta-vdrive-8733', ['eta-vdrive-8733'], 10001),
            (14, 'other', ['unrelated'], 0),
        ]))

    def test_rewritten_legacy_argv_requires_listening_socket_ownership(self):
        self.assertEqual({'20'}, self.discover([
            (20, 'VDrive', ['VDrive'], 0), (21, 'VDrive', ['VDrive'], 0),
        ], listener=20))

if __name__ == '__main__': unittest.main()
