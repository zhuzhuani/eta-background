"""Require real mksh/ash and Android's descriptor-only Toybox flock, also in CI."""
import os
import pathlib
import shlex
import shutil

def executable(name):
    value = os.environ.get('ETA_TEST_' + name.upper()) or shutil.which(name)
    if not value or not pathlib.Path(value).is_file():
        raise RuntimeError(f'{name} is required for Android shell regression tests')
    return value

def shells():
    return [('mksh', [executable('mksh')]), ('ash', [executable('busybox'), 'ash']),
            ('sh', [shutil.which('sh')])]

def environment(root, shell):
    directory = root / 'bin'; directory.mkdir(exist_ok=True)
    for name, command in {'sh': shell, 'flock': [executable('toybox'), 'flock'],
                          'grep': [executable('toybox'), 'grep']}.items():
        target = directory / name
        target.write_text('#!/bin/sh\nexec ' + shlex.join(command) + ' "$@"\n')
        target.chmod(0o700)
    identity = directory / 'id'; identity.write_text('#!/bin/sh\necho 0\n'); identity.chmod(0o700)
    env = dict(os.environ, PATH=str(directory) + ':' + os.environ['PATH'])
    env.pop('ETA_ENGINE_LOCKED', None)
    return env
