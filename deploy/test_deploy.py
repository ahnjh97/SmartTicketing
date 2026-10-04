"""Exercise artifact integrity and deployment gates without contacting AWS."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REVISION = 'a' * 40
ARCHIVE = b'verified-image-archive'
BACKEND_METADATA = '["sha256:layer-backend"]|"linux"|"amd64"|""|""|["PATH=/bin"]|["sh","/app/backend-entrypoint.sh"]'
FRONTEND_METADATA = '["sha256:layer-frontend"]|"linux"|"amd64"|""|""|["PATH=/bin"]|["/docker-entrypoint.sh"]'
BACKEND_FINGERPRINT = hashlib.sha256((BACKEND_METADATA + '\n').encode()).hexdigest()
FRONTEND_FINGERPRINT = hashlib.sha256((FRONTEND_METADATA + '\n').encode()).hexdigest()


class DeploymentTests(unittest.TestCase):
    def run_deploy(self, *, payload=ARCHIVE, latest=REVISION, docker_failure='', artifact_id='123-1',
                   loaded_backend_metadata=BACKEND_METADATA, loaded_frontend_metadata=FRONTEND_METADATA):
        shell = os.environ.get('TEST_SHELL') or shutil.which('bash')
        self.assertIsNotNone(shell, 'Bash is required')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            checkout = root / 'SmartTicketing'
            checkout.mkdir()
            artifacts = root / '.smartticketing-artifacts'
            artifacts.mkdir()
            upload = artifacts / '123-1.tar.gz'
            if payload is not None:
                upload.write_bytes(payload)
            unrelated = artifacts / '999-1.tar.gz'
            unrelated.write_bytes(b'other-run')
            (checkout / '.env').write_text('preserve-me', encoding='utf-8')
            commands = root / 'commands.log'
            binaries = root / 'bin'
            binaries.mkdir()
            scripts = {
                'flock': '#!/bin/sh\nexit 0\n',
                'git': '''#!/bin/sh
printf 'git %s\\n' "$*" >> "$COMMAND_LOG"
if [ "$1" = rev-parse ]; then printf '%s\\n' "$LATEST_REVISION"; fi
''',
                'docker': '''#!/bin/sh
printf 'docker backend=%s frontend=%s %s\\n' "${BACKEND_IMAGE:-}" "${FRONTEND_IMAGE:-}" "$*" >> "$COMMAND_LOG"
if [ -n "$DOCKER_FAILURE" ]; then
  case "$*" in *"$DOCKER_FAILURE"*) exit 42 ;; esac
fi
if [ "$1 $2" = 'image inspect' ]; then
  case "$*" in
    *smartticketing-backend:*) printf '%s\\n' "$LOADED_BACKEND_METADATA" ;;
    *smartticketing-frontend:*) printf '%s\\n' "$LOADED_FRONTEND_METADATA" ;;
  esac
fi
''',
            }
            for name, script in scripts.items():
                command = binaries / name
                command.write_text(script, encoding='utf-8', newline='\n')
                command.chmod(0o755)
            env = dict(os.environ, HOME=root.as_posix(),
                       COMMAND_LOG=commands.as_posix(), LATEST_REVISION=latest,
                       DOCKER_FAILURE=docker_failure, MOCK_BIN=binaries.as_posix(),
                       LOADED_BACKEND_METADATA=loaded_backend_metadata, LOADED_FRONTEND_METADATA=loaded_frontend_metadata)
            env['PATH'] = str(binaries) + os.pathsep + env['PATH']
            script = 'export PATH="$(cd "$MOCK_BIN" && pwd):$PATH"\n'
            script += Path(__file__).with_name('image-fingerprint.sh').read_text(encoding='utf-8') + '\n'
            script += Path(__file__).with_name('deploy.sh').read_text(encoding='utf-8')
            result = subprocess.run(
                [shell, '-s', '--', REVISION, artifact_id, hashlib.sha256(ARCHIVE).hexdigest(), BACKEND_FINGERPRINT, FRONTEND_FINGERPRINT],
                input=script, text=True, capture_output=True, env=env, timeout=15)
            return {
                'result': result,
                'commands': commands.read_text() if commands.exists() else '',
                'upload_exists': upload.exists(),
                'other_run': unrelated.read_bytes(),
                'env': (checkout / '.env').read_text(),
            }

    def test_verified_images_are_loaded_and_used_without_build_or_pull(self):
        run = self.run_deploy()
        self.assertEqual(run['result'].returncode, 0, run['result'].stderr)
        self.assertIn('load --input', run['commands'])
        self.assertIn(f'backend=smartticketing-backend:{REVISION}', run['commands'])
        self.assertIn(f'frontend=smartticketing-frontend:{REVISION}', run['commands'])
        self.assertIn('compose up -d --no-build --pull never --wait', run['commands'])
        self.assertNotIn('compose build', run['commands'])
        self.assertNotIn('{{.Id}}', run['commands'])
        self.assertIn('.RootFS.Layers', run['commands'])
        self.assertIn('.Config.Entrypoint', run['commands'])
        self.assertIn('--force-recreate nginx', run['commands'])
        self.assertFalse(run['upload_exists'])
        self.assertEqual(run['other_run'], b'other-run')
        self.assertEqual(run['env'], 'preserve-me')

    def test_corrupt_archive_stops_before_checkout_or_load(self):
        run = self.run_deploy(payload=b'incomplete-transfer')
        self.assertNotEqual(run['result'].returncode, 0)
        self.assertNotIn('git reset', run['commands'])
        self.assertNotIn('compose build', run['commands'])
        self.assertNotIn('load --input', run['commands'])
        self.assertFalse(run['upload_exists'])

    def test_missing_upload_stops_before_checkout(self):
        run = self.run_deploy(payload=None)
        self.assertNotEqual(run['result'].returncode, 0)
        self.assertNotIn('git reset', run['commands'])

    def test_stale_commit_does_not_load_images(self):
        run = self.run_deploy(latest='b' * 40)
        self.assertNotEqual(run['result'].returncode, 0)
        self.assertNotIn('load --input', run['commands'])
        self.assertNotIn('compose build', run['commands'])
        self.assertFalse(run['upload_exists'])

    def test_unhealthy_service_does_not_replace_proxy(self):
        run = self.run_deploy(docker_failure='up -d --no-build')
        self.assertEqual(run['result'].returncode, 42)
        self.assertNotIn('--force-recreate nginx', run['commands'])
        self.assertIn('compose logs', run['commands'])
        self.assertFalse(run['upload_exists'])

    def test_invalid_artifact_id_is_rejected_before_commands(self):
        run = self.run_deploy(artifact_id='../123')
        self.assertNotEqual(run['result'].returncode, 0)
        self.assertEqual(run['commands'], '')
        self.assertTrue(run['upload_exists'])

    def test_image_content_or_config_mismatch_stops_before_checkout_and_restart(self):
        for settings in (
            {'loaded_backend_metadata': BACKEND_METADATA.replace('layer-backend', 'different-layer')},
            {'loaded_frontend_metadata': FRONTEND_METADATA.replace('/docker-entrypoint.sh', '/wrong-entrypoint.sh')},
        ):
            with self.subTest(settings=settings):
                run = self.run_deploy(**settings)
                self.assertNotEqual(run['result'].returncode, 0)
                self.assertNotIn('git reset', run['commands'])
                self.assertNotIn('compose up', run['commands'])

    def test_inspect_failure_does_not_accept_empty_fingerprint(self):
        run = self.run_deploy(docker_failure='image inspect')
        self.assertEqual(run['result'].returncode, 42)
        self.assertNotIn('git reset', run['commands'])
        self.assertNotIn('compose up', run['commands'])

    def test_load_failure_stops_before_checkout_and_restart(self):
        run = self.run_deploy(docker_failure='load --input')
        self.assertEqual(run['result'].returncode, 42)
        self.assertNotIn('git reset', run['commands'])
        self.assertNotIn('compose up', run['commands'])
