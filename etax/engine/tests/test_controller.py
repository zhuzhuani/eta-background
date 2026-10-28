"""Run the real controller with real socket binding; no Android services are attached."""
import os
import pathlib
import shlex
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from shell_compat import environment, shells

ENGINE = pathlib.Path(__file__).resolve().parents[1]

class ControllerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classes = tempfile.TemporaryDirectory()
        subprocess.run([os.environ.get('JAVAC', 'javac'), '-d', cls.classes.name,
                        str(ENGINE / 'src/EngineControl.java')], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls): cls.classes.cleanup()

    def setup_controller(self, root, shell, port, timeout=2000):
        live = root / 'live'; live.mkdir()
        (live / 'vdrive.dex').write_text('fixture')
        (live / 'vdrive.version').write_text('test')
        (live / 'engine.token').write_text('a' * 64)
        android = root / 'app_process'
        android.write_text('#!/bin/sh\nshift\nif [ "$1" = EngineControl ]; then\n'
            + 'exec ' + shlex.join([os.environ.get('JAVA', 'java'), '-cp', self.classes.name]) + ' "$@"\nfi\n'
            + f'printf "%s\\n" "${{ETA_ENGINE_LOCKED:-unset}}" "$(readlink /proc/$$/fd/0)" > "{live}/daemon-environment"\n')
        android.chmod(0o700)
        source = (ENGINE / 'vd.sh').read_text().replace('/data/local/tmp/eta', str(live)).replace('/system/bin/app_process', str(android))
        source = source.replace('PORT=8733', f'PORT={port}').replace('"$PORT" 10000', f'"$PORT" {timeout}')
        script = live / 'vd.sh'; script.write_text(source)
        return live, shell + [str(script)], environment(root, shell)

    def listener(self):
        sock = socket.socket(); sock.bind(('127.0.0.1', 0)); sock.listen()
        self.addCleanup(sock.close)
        return sock

    def test_waits_for_actual_port_release_beyond_old_fixed_delay(self):
        for name, shell in shells():
            with self.subTest(shell=name), tempfile.TemporaryDirectory() as temp:
                sock = self.listener()
                live, command, env = self.setup_controller(pathlib.Path(temp), shell, sock.getsockname()[1])
                timer = threading.Timer(.8, sock.close); timer.start()
                started = time.monotonic()
                result = subprocess.run(command + ['stop'], env=env, text=True, capture_output=True, timeout=5)
                timer.join()
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertGreaterEqual(time.monotonic() - started, .75)
                self.assertIn('ETA_ENGINE_PORT_FREE', result.stdout)

    def test_unknown_listener_blocks_deployment_without_killing_it(self):
        for name, shell in shells():
            with self.subTest(shell=name), tempfile.TemporaryDirectory() as temp:
                sock = self.listener()
                live, command, env = self.setup_controller(pathlib.Path(temp), shell, sock.getsockname()[1], 300)
                result = subprocess.run(command, env=env, text=True, capture_output=True, timeout=5)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('ETA_ENGINE_ERROR=PORT_BUSY', result.stdout)
                self.assertFalse((live / 'daemon-environment').exists())
                with socket.create_connection(sock.getsockname(), timeout=1): pass

    def test_resident_launch_does_not_inherit_lock_or_ownership_flag(self):
        for name, shell in shells():
            with self.subTest(shell=name), tempfile.TemporaryDirectory() as temp:
                sock = self.listener(); port = sock.getsockname()[1]; sock.close()
                live, command, env = self.setup_controller(pathlib.Path(temp), shell, port)
                result = subprocess.run(command, env=env, text=True, capture_output=True, timeout=15)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(['unset', '/dev/null'], (live / 'daemon-environment').read_text().splitlines())
                lock = subprocess.run(['sh', '-c', 'flock -n 0 < "$1"', 'check', str(live / 'engine.lock')], env=env)
                self.assertEqual(0, lock.returncode)

if __name__ == '__main__': unittest.main()
