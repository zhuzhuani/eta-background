"""Real installer and Toybox locks under mksh/ash; only Android process control is simulated."""
import pathlib
import subprocess
import tempfile
import time
import unittest
from shell_compat import environment, shells

ENGINE = pathlib.Path(__file__).resolve().parents[1]
FILES = 'vdrive.dex vdrive.version vd.sh install_vdrive.sh check_pair.sh'.split()
OLD_TOKEN, NEW_TOKEN = 'a' * 64, 'b' * 64

class InstallerTest(unittest.TestCase):
    def setup_install(self, root, shell, mode='success', legacy=False):
        live, stage = root / 'live', root / 'stage'
        live.mkdir(); stage.mkdir()
        android = root / 'app_process'; android.write_text('#!/bin/sh\n'); android.chmod(0o700)
        for name in FILES:
            (live / name).write_text('old-' + name)
            (stage / name).write_text('new-' + name)
        if not legacy: (live / 'engine.token').write_text(OLD_TOKEN)
        (stage / 'engine.token').write_text(NEW_TOKEN)
        (stage / 'vd.sh').write_text(f'''#!/bin/sh
D='{live}'
case "${{1:-}}" in
status) exit 1 ;;
stop)
  echo "stop:$(cat "$D/vdrive.dex")" >> "$D/trace"
  [ '{mode}' != stop_fail ] || exit 1
  if [ '{mode}' = hold ]; then
    touch "$D/entered"
    while [ ! -f "$D/release" ]; do sleep 0.05; done
  fi
  exit 0 ;;
esac
echo "start:$(cat "$D/vdrive.dex")" >> "$D/trace"
[ '{mode}' != start_fail ]
''')
        installer = (ENGINE / 'install_vdrive.sh').read_text().replace('/data/local/tmp/eta', str(live)).replace('/system/bin/app_process', str(android))
        script = root / 'install.sh'; script.write_text(installer)
        return live, stage, shell + [str(script), str(stage)], environment(root, shell)

    def test_transaction_and_legacy_rollback_in_every_android_shell(self):
        for name, shell in shells():
            for mode in ('success', 'stop_fail', 'start_fail'):
                for legacy in (False, True):
                    with self.subTest(shell=name, mode=mode, legacy=legacy), tempfile.TemporaryDirectory() as temp:
                        live, stage, command, env = self.setup_install(pathlib.Path(temp), shell, mode, legacy)
                        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=10)
                        self.assertEqual(mode == 'success', result.returncode == 0, result.stdout + result.stderr)
                        for file in FILES:
                            self.assertEqual((stage / file).read_text() if mode == 'success' else 'old-' + file,
                                             (live / file).read_text())
                        token = live / 'engine.token'
                        if mode == 'stop_fail' and legacy: self.assertFalse(token.exists())
                        else: self.assertEqual(OLD_TOKEN if mode == 'stop_fail' else NEW_TOKEN, token.read_text())
                        trace = ['stop:old-vdrive.dex']
                        if mode != 'stop_fail': trace += ['start:new-vdrive.dex']
                        if mode == 'start_fail': trace += ['stop:new-vdrive.dex']
                        self.assertEqual(trace, (live / 'trace').read_text().splitlines())
                        self.assertFalse(list(live.glob('.install.*')))
                        lock = subprocess.run(['sh', '-c', 'flock -n 0 < "$1"', 'check', str(live / 'engine.lock')], env=env)
                        self.assertEqual(0, lock.returncode)

    def test_parallel_installer_cannot_enter_transaction(self):
        for name, shell in shells():
            with self.subTest(shell=name), tempfile.TemporaryDirectory() as temp:
                live, stage, command, env = self.setup_install(pathlib.Path(temp), shell, 'hold')
                first = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    deadline = time.monotonic() + 5
                    while not (live / 'entered').exists() and time.monotonic() < deadline: time.sleep(.02)
                    self.assertTrue((live / 'entered').exists())
                    second = subprocess.run(command, env=env, capture_output=True, text=True, timeout=5)
                    self.assertEqual(75, second.returncode, second.stderr + second.stdout)
                    self.assertIn('ETA_ENGINE_ERROR=DEPLOY_BUSY', second.stdout)
                    self.assertEqual('old-vdrive.dex', (live / 'vdrive.dex').read_text())
                finally:
                    (live / 'release').touch()
                    out, err = first.communicate(timeout=5)
                self.assertEqual(0, first.returncode, out + err)

    def test_missing_and_malformed_credentials_never_stop_live_engine(self):
        for name, shell in shells():
            for token in (None, '', 'bad', NEW_TOKEN + '\nx', NEW_TOKEN + '\x00', NEW_TOKEN + '\n\n'):
                with self.subTest(shell=name, token_length=None if token is None else len(token)), tempfile.TemporaryDirectory() as temp:
                    live, stage, command, env = self.setup_install(pathlib.Path(temp), shell)
                    if token is None: (stage / 'engine.token').unlink()
                    else: (stage / 'engine.token').write_text(token)
                    result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=5)
                    self.assertEqual(78, result.returncode, result.stdout + result.stderr)
                    self.assertIn('TOKEN_MISSING' if token is None else 'TOKEN_INVALID', result.stdout)
                    self.assertFalse((live / 'trace').exists())
                    self.assertEqual(OLD_TOKEN, (live / 'engine.token').read_text())

if __name__ == '__main__': unittest.main()
