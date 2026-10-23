"""Deploy CI-verified images to EC2 A/B. No AWS resource creation or database reset."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def command(args, *, cwd=None, data=None):
    result = subprocess.run(args, cwd=cwd, input=data, capture_output=True)
    if result.returncode:
        # Do not echo command input: it may contain the SSH private key or configuration.
        raise RuntimeError(f'{args[0]} {args[1]} failed (exit {result.returncode}): '
                           + result.stderr.decode(errors='replace')[-2000:])
    return result.stdout.decode()


def validate(revision, artifact_id, digest, fingerprint):
    for value, pattern, label in (
            (revision, r'[0-9a-f]{40}', 'revision'),
            (artifact_id, r'[0-9]+-[0-9]+', 'artifact ID'),
            (digest, r'[0-9a-f]{64}', 'archive digest'),
            (fingerprint, r'[0-9a-f]{64}', 'image fingerprint')):
        if not re.fullmatch(pattern, value):
            raise ValueError(f'Invalid {label}')


def file_digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def image_fingerprint(image):
    # Same content/config fingerprint as deploy/image-fingerprint.sh, across image stores.
    template = '{{json .RootFS.Layers}}|{{json .Os}}|{{json .Architecture}}|{{json (or .Variant "")}}'
    fields = ('User Env Entrypoint Cmd WorkingDir ExposedPorts Volumes Labels StopSignal '
              'Healthcheck Shell OnBuild ArgsEscaped').split()
    for field in fields:
        template += '|{{json (or .Config.' + field + ' "")}}'
    metadata = command(['docker', 'image', 'inspect', '--format', template, image]).strip()
    if not metadata:
        raise RuntimeError('Empty image metadata')
    return hashlib.sha256((metadata + '\n').encode()).hexdigest()


@contextlib.contextmanager
def deployment_lock(root):
    import fcntl  # apply runs on Linux EC2; the runner and unit tests need no Linux imports.
    with (root / '.distributed-deploy.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def record_image(env_file, image):
    lines = env_file.read_text().splitlines()
    lines = [line for line in lines if line.split('=', 1)[0].strip() != 'BACKEND_IMAGE']
    lines.append('BACKEND_IMAGE=' + image)
    # Atomic update; all other settings and secrets remain on the server.
    temp = env_file.with_name('.env.cd-next')
    descriptor = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            stream.write('\n'.join(lines) + '\n')
        temp.replace(env_file)
    finally:
        temp.unlink(missing_ok=True)


def apply(args, root=None, home=None):
    validate(args.revision, args.artifact_id, args.digest, args.fingerprint)
    if args.role not in ('a', 'b') or args.phase not in ('prepare', 'start', 'verify'):
        raise ValueError('Invalid role or deployment phase')
    home = home or Path.home()
    root = root or home / 'SmartTicketing'
    image = 'smartticketing-backend:' + args.revision
    env_file = root / 'scripts/distributed/.env'
    archive = home / '.smartticketing-artifacts' / f'distributed-{args.artifact_id}.tar.gz'
    with deployment_lock(root):
        if args.phase == 'prepare':
            try:
                if not env_file.is_file():
                    raise RuntimeError('Create scripts/distributed/.env on both EC2 instances before CD')
                if file_digest(archive) != args.digest:
                    raise RuntimeError('Uploaded archive checksum mismatch')
                command(['git', 'fetch', 'origin', 'main'], cwd=root)
                head = command(['git', 'rev-parse', 'origin/main'], cwd=root).strip()
                if head != args.revision:
                    raise RuntimeError('main moved; deploy the latest tested commit')
                command(['docker', 'load', '--input', str(archive)])
                if image_fingerprint(image) != args.fingerprint:
                    raise RuntimeError('Loaded backend image fingerprint mismatch')
                command(['git', 'checkout', 'main'], cwd=root)
                command(['git', 'reset', '--hard', args.revision], cwd=root)
                record_image(env_file, image)
                # Same file on A/B: compare hashes privately in the runner, never print secrets.
                config_digest = file_digest(env_file)
                command(['python3', 'scripts/distributed/lab.py', 'config'], cwd=root)
                return {'role': args.role, 'phase': 'prepared', 'configDigest': config_digest}
            finally:
                archive.unlink(missing_ok=True)
        # Reject an old SSH operation resuming after a different revision was prepared.
        if command(['git', 'rev-parse', 'HEAD'], cwd=root).strip() != args.revision:
            raise RuntimeError('Checkout changed during deployment')
        if image_fingerprint(image) != args.fingerprint:
            raise RuntimeError('Backend image changed during deployment')
        if args.phase == 'start':
            if args.role == 'a':
                for base_image in ('mysql:8.4', 'redis:7-alpine', 'nginx:alpine'):
                    try:
                        command(['docker', 'image', 'inspect', base_image])
                    except RuntimeError:
                        command(['docker', 'pull', base_image])
                if args.bootstrap:
                    # Explicit first-run schema creation on the dedicated lab DB only.
                    command(['env', 'DDL_MODE=update', 'docker', 'compose', '--env-file',
                             'scripts/distributed/.env', '-f', 'scripts/distributed/compose.a.yml',
                             'up', '-d', '--no-build', '--pull', 'never', '--wait',
                             '--wait-timeout', '600', 'backend-a'], cwd=root)
            command(['python3', 'scripts/distributed/lab.py', 'up', '--role', args.role], cwd=root)
            output = root / '.local/distributed/deployment.json'
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps({'revision': args.revision, 'image': image,
                                          'fingerprint': args.fingerprint, 'role': args.role}, indent=2))
            return {'role': args.role, 'phase': 'started', 'revision': args.revision}
        if args.role != 'a':
            raise ValueError('Run final verification on EC2 A')
        command(['python3', 'scripts/distributed/lab.py', 'preflight'], cwd=root)
        return {'role': 'a', 'phase': 'verified', 'revision': args.revision,
                'checks': ['preflight']}


def run(args, report_path=None):
    archive = Path(args.archive)
    digest = file_digest(archive)
    fingerprint = Path(args.fingerprint_file).read_text().strip()
    validate(args.revision, args.artifact_id, digest, fingerprint)
    username = os.environ.get('AWS_USERNAME', '')
    key = os.environ.get('AWS_SSH_KEY', '')
    hosts = {role: os.environ.get(f'AWS_DISTRIBUTED_{role.upper()}_HOST', '') for role in ('a', 'b')}
    if not re.fullmatch(r'[a-z_][a-z0-9_-]*', username) or not key.strip():
        raise ValueError('Set AWS_USERNAME and AWS_SSH_KEY')
    for host in hosts.values():
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*', host):
            raise ValueError('Set valid AWS_DISTRIBUTED_A_HOST and AWS_DISTRIBUTED_B_HOST')
    if hosts['a'] == hosts['b']:
        raise ValueError('A and B must be different EC2 SSH hosts')
    payload = Path(__file__).read_bytes()
    report = {'revision': args.revision, 'status': 'running', 'steps': []}
    result_file = report_path or Path('build/distributed-deployment.json')
    result_file.parent.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            private_key = folder / 'key'
            private_key.write_text(key.rstrip() + '\n')
            private_key.chmod(0o600)
            known_hosts = folder / 'known_hosts'
            # Matches the existing CD's network-discovered host-key trust model.
            scanned = ''.join(command(['ssh-keyscan', '-4', '-T', '30', '-H', host])
                              for host in hosts.values())
            if not scanned.strip():
                raise RuntimeError('Could not discover EC2 SSH host keys')
            known_hosts.write_text(scanned)
            options = ['ssh', '-4', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
                       '-o', f'UserKnownHostsFile={known_hosts}', '-o', 'ConnectTimeout=30',
                       '-o', 'ServerAliveInterval=30', '-o', 'ServerAliveCountMax=6', '-i', str(private_key)]

            def remote(role, phase):
                argv = ['python3', '-', 'apply', '--revision', args.revision,
                        '--artifact-id', args.artifact_id, '--digest', digest,
                        '--fingerprint', fingerprint, '--role', role, '--phase', phase]
                if args.bootstrap:
                    argv.append('--bootstrap')
                # Every interpolated argument is validated or selected from fixed choices.
                result = json.loads(command(options + [f'{username}@{hosts[role]}', ' '.join(argv)], data=payload))
                report['steps'].append({k: v for k, v in result.items() if k != 'configDigest'})
                print(f'EC2 {role.upper()}: {result["phase"]}', flush=True)
                return result

            prepared = []
            for role in ('a', 'b'):
                destination = f'~/.smartticketing-artifacts/distributed-{args.artifact_id}.tar.gz'
                # Stream archives instead of loading them fully into runner memory.
                with archive.open('rb') as stream:
                    upload = subprocess.run(options + [f'{username}@{hosts[role]}',
                        f'umask 077 && mkdir -p ~/.smartticketing-artifacts && cat > {destination}'], stdin=stream)
                if upload.returncode:
                    raise RuntimeError(f'Image upload to EC2 {role.upper()} failed')
                prepared.append(remote(role, 'prepare'))
            if prepared[0]['configDigest'] != prepared[1]['configDigest']:
                raise RuntimeError('A/B .env files differ; copy the SAME shared configuration to both hosts')
            remote('a', 'start')
            remote('b', 'start')
            remote('a', 'verify')
        report['status'] = 'passed'
    except Exception:
        report['status'] = 'failed'
        raise
    finally:
        result_file.write_text(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    for name in ('run', 'apply'):
        child = sub.add_parser(name)
        child.add_argument('--revision', required=True)
        child.add_argument('--artifact-id', required=True)
        child.add_argument('--bootstrap', action='store_true')
        if name == 'run':
            child.add_argument('--archive', required=True)
            child.add_argument('--fingerprint-file', required=True)
        else:
            child.add_argument('--digest', required=True)
            child.add_argument('--fingerprint', required=True)
            child.add_argument('--role', choices=['a', 'b'], required=True)
            child.add_argument('--phase', choices=['prepare', 'start', 'verify'], required=True)
    args = parser.parse_args()
    if args.mode == 'run':
        run(args)
    else:
        print(json.dumps(apply(args)))


if __name__ == '__main__':
    main()
