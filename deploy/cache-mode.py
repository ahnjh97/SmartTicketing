#!/usr/bin/env python3
"""Switch the reserved cache flag without rebuilding or changing deployed images.

Requires Python 3.9+ and Docker Compose v2. No application cache exists yet.
Only deployment settings are changed; Redis remains available for ticket QR use.
"""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
STATE = '.env.deployment'
KEYS = {'BACKEND_IMAGE', 'FRONTEND_IMAGE', 'APP_CACHE_ENABLED'}
NOTICE = 'Configuration only: application cache behavior is not implemented yet. QR Redis stays enabled.'


def read_state(root):
    path = root / STATE
    result = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if not line or line.startswith('#'):
                continue
            key, sep, value = line.partition('=')
            if not sep or key not in KEYS or not re.fullmatch(r'[A-Za-z0-9_.:/@-]+', value):
                raise ValueError('Invalid deployment state; inspect .env.deployment locally.')
            if key in result:
                raise ValueError('Duplicate deployment state key: ' + key)
            result[key] = value
    if result.get('APP_CACHE_ENABLED', 'false') not in ('true', 'false'):
        raise ValueError('APP_CACHE_ENABLED must be true or false.')
    return result


def write_state(root, values):
    # The state contains only non-secret mode/image settings. Never rewrite .env.
    for key, value in values.items():
        if key not in KEYS or not re.fullmatch(r'[A-Za-z0-9_.:/@-]+', value):
            raise ValueError('Invalid deployment state value.')
    fd, temporary = tempfile.mkstemp(prefix='.env.deployment.', dir=root)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            stream.write('# Managed by deploy/cache-mode.py; no secrets.\n')
            for key in sorted(values):
                stream.write(f'{key}={values[key]}\n')
        os.replace(temporary, root / STATE)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextmanager
def operation_lock(root):
    # Same lock as deploy.sh on Linux. A failed acquisition never changes settings.
    with (root / '.deploy-operation.lock').open('a+b') as stream:
        if os.name == 'nt':
            import msvcrt
            stream.seek(0, 2)
            if stream.tell() == 0:
                stream.write(b'0')
                stream.flush()
            stream.seek(0)
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                raise RuntimeError('Another deployment or mode switch is running.') from None
        else:
            import fcntl
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError('Another deployment or mode switch is running.') from None
        try:
            yield
        finally:
            if os.name == 'nt':
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


class Docker:
    def __init__(self, root, values):
        self.root = root
        self.env = dict(os.environ)
        # Caller shell must not accidentally override the selected mode or images.
        for key in KEYS:
            self.env.pop(key, None)
        self.env.update(values)

    def run(self, *args):
        result = subprocess.run(['docker', *args], cwd=self.root, env=self.env,
                                text=True, capture_output=True, timeout=720)
        if result.returncode:
            # Docker config/inspect errors can contain expanded secrets. Don't echo output.
            raise RuntimeError('Docker command failed: ' + ' '.join(args[:2]))
        return result.stdout.strip()

    def compose(self, *args):
        options = ['compose', '-f', 'docker-compose.yml', '--env-file', '.env']
        if (self.root / STATE).exists():
            options += ['--env-file', STATE]
        return self.run(*options, *args)

    def container(self, service, required=True):
        ids = self.compose('ps', '-q', service).splitlines()
        if not ids and not required:
            return None
        if len(ids) != 1:
            raise RuntimeError(f'Expected one running {service} container. Deploy/start it first.')
        # Read only the fields we need; never print container environment secrets.
        image = self.run('inspect', '--format', '{{.Image}}', ids[0])
        mode = self.run('inspect', '--format',
                        '{{range .Config.Env}}{{if eq (index (split . "=") 0) "APP_CACHE_ENABLED"}}{{.}}{{end}}{{end}}', ids[0])
        return {'id': ids[0], 'image': image, 'mode': mode.partition('=')[2]}

    def reload_proxy(self):
        if self.container('nginx', required=False):
            self.compose('exec', '-T', 'nginx', 'nginx', '-t')
            self.compose('exec', '-T', 'nginx', 'nginx', '-s', 'reload')

    def recreate(self):
        self.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                     '--force-recreate', '--wait', '--wait-timeout', '600', 'backend')
        actual = self.container('backend')
        if actual['image'] != self.env['BACKEND_IMAGE'] or actual['mode'] != self.env['APP_CACHE_ENABLED']:
            raise RuntimeError('Backend image or configured mode does not match the requested settings.')
        self.reload_proxy()


def switch(root, enabled):
    with operation_lock(root):
        saved = read_state(root)
        docker = Docker(root, saved)
        backend = docker.container('backend')
        if backend['mode'] not in ('true', 'false'):
            raise RuntimeError('Running backend has no mode flag. Deploy this tooling revision first.')
        # Immutable local image IDs prevent tag changes and :local fallback during switching.
        values = dict(saved, BACKEND_IMAGE=backend['image'], APP_CACHE_ENABLED=enabled)
        frontend = docker.container('frontend', required=False)
        if frontend:
            values['FRONTEND_IMAGE'] = frontend['image']
        requested = Docker(root, values)
        requested.compose('config', '--quiet')
        requested.run('image', 'inspect', '--format', '{{.Id}}', backend['image'])
        if docker.container('nginx', required=False):
            docker.compose('exec', '-T', 'nginx', 'nginx', '-t')
        if backend['mode'] == enabled:
            write_state(root, values)
            print('Already configured; saved settings without restarting.')
        else:
            previous = dict(values, APP_CACHE_ENABLED=backend['mode'])
            try:
                requested.recreate()
                write_state(root, values)
            except (RuntimeError, OSError, subprocess.TimeoutExpired):
                print('Switch failed; restoring previous mode with the same image.', file=sys.stderr)
                try:
                    Docker(root, previous).recreate()
                except (RuntimeError, OSError, subprocess.TimeoutExpired):
                    print('Rollback failed; inspect backend and nginx on the host. Saved settings were not changed.',
                          file=sys.stderr)
                raise
        print(f'APP_CACHE_ENABLED={enabled}; BACKEND_IMAGE={backend["image"]}')
        print(NOTICE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['cache-on', 'cache-off', 'status', 'record-images'])
    parser.add_argument('--backend-image')
    parser.add_argument('--frontend-image')
    args = parser.parse_args()
    try:
        if args.mode == 'record-images':
            # Internal deploy.sh command: caller already holds .deploy-operation.lock.
            if not args.backend_image or not args.frontend_image:
                parser.error('record-images requires both image arguments')
            values = read_state(ROOT)
            values.update(BACKEND_IMAGE=args.backend_image, FRONTEND_IMAGE=args.frontend_image)
            if 'APP_CACHE_ENABLED' in os.environ:
                values['APP_CACHE_ENABLED'] = os.environ['APP_CACHE_ENABLED']
            if values.get('APP_CACHE_ENABLED', 'false') not in ('true', 'false'):
                raise ValueError('Invalid APP_CACHE_ENABLED.')
            write_state(ROOT, values)
        elif args.mode == 'status':
            with operation_lock(ROOT):
                values = read_state(ROOT)
                actual = Docker(ROOT, values).container('backend')
                print(json.dumps({'saved': values, 'running_backend': actual,
                                  'application_cache_implemented': False}, indent=2))
                print(NOTICE)
        else:
            switch(ROOT, 'true' if args.mode == 'cache-on' else 'false')
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as exc:
        # Do not print subprocess output or secrets from .env.
        print(str(exc) if isinstance(exc, (RuntimeError, ValueError)) else type(exc).__name__, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
