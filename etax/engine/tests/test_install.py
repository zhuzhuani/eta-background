"""Execute the real installer with isolated directories and a simulated Android engine."""
import pathlib
import subprocess
import tempfile
import unittest

ENGINE = pathlib.Path(__file__).resolve().parents[1]
FILES = 'vdrive.dex vdrive.version vd.sh install_vdrive.sh check_pair.sh engine.token'.split()

class InstallerTest(unittest.TestCase):
    def run_install(self, mode):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            live, stage = root / 'live', root / 'stage'
            live.mkdir(); stage.mkdir()
            android = root / 'app_process'; android.write_text('#!/bin/sh\n'); android.chmod(0o700)
            mockbin = root / 'bin'; mockbin.mkdir()
            identity = mockbin / 'id'; identity.write_text('#!/bin/sh\necho 0\n'); identity.chmod(0o700)
            for name in FILES:
                (live / name).write_text('old-' + name)
                (stage / name).write_text('new-' + name)
            controller = f'''#!/bin/sh
D='{live}'
case "${{1:-}}" in
status) exit 1 ;;
stop)
  echo "stop:$(cat "$D/vdrive.dex")" >> "$D/trace"
  [ '{mode}' != stop_fail ] || exit 1
  exit 0 ;;
esac
echo "start:$(cat "$D/vdrive.dex")" >> "$D/trace"
[ '{mode}' != start_fail ]
'''
            (stage / 'vd.sh').write_text(controller)
            installer = (ENGINE / 'install_vdrive.sh').read_text().replace('/data/local/tmp/eta', str(live)).replace('/system/bin/app_process', str(android))
            script = root / 'install.sh'; script.write_text(installer)
            import os
            env = dict(os.environ, PATH=str(mockbin) + ':' + os.environ['PATH'])
            result = subprocess.run(['sh', str(script), str(stage)], env=env, capture_output=True, text=True, timeout=10)
            actual = {name: (live / name).read_text() for name in FILES}
            trace = (live / 'trace').read_text().splitlines()
            return result, actual, trace

    def test_stop_is_verified_before_replacing_mapped_dex(self):
        result, files, trace = self.run_install('success')
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertEqual(['stop:old-vdrive.dex', 'start:new-vdrive.dex'], trace)
        self.assertEqual('new-vdrive.dex', files['vdrive.dex'])

    def test_stop_failure_does_not_modify_any_live_file(self):
        result, files, trace = self.run_install('stop_fail')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(['stop:old-vdrive.dex'], trace)
        self.assertEqual({name: 'old-' + name for name in FILES}, files)

    def test_start_failure_stops_new_engine_and_restores_the_complete_pair(self):
        result, files, trace = self.run_install('start_fail')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(['stop:old-vdrive.dex', 'start:new-vdrive.dex', 'stop:new-vdrive.dex'], trace)
        self.assertEqual({name: 'old-' + name for name in FILES}, files)

if __name__ == '__main__':
    unittest.main()
