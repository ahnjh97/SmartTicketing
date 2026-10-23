"""Deployment gates and A/B order checks, without SSH, Docker, or AWS access."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location('distributed_cd', Path(__file__).with_name('distributed.py'))
cd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cd)
REVISION = 'a' * 40
FINGERPRINT = 'b' * 64
PAYLOAD = b'verified compressed images'


class DistributedDeploymentTests(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[1] / '.local'
        scratch.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.root = self.home / 'SmartTicketing'
        self.env = self.root / 'scripts/distributed/.env'
        self.env.parent.mkdir(parents=True)
        self.env.write_text('SERVER_A_IP=10.0.1.10\nMYSQL_PASSWORD=keep-secret\nBACKEND_IMAGE=old\n')
        self.archive = self.home / '.smartticketing-artifacts/distributed-123-1.tar.gz'
        self.archive.parent.mkdir()
        self.archive.write_bytes(PAYLOAD)
        self.args = argparse.Namespace(revision=REVISION, artifact_id='123-1',
            digest=hashlib.sha256(PAYLOAD).hexdigest(), fingerprint=FINGERPRINT,
            role='a', phase='prepare', bootstrap=False)
        self.lock = patch.object(cd, 'deployment_lock', side_effect=lambda root: contextlib.nullcontext())
        self.lock.start()
        self.addCleanup(self.lock.stop)

    def command(self, argv, **kwargs):
        if argv[:2] == ['git', 'rev-parse']:
            return REVISION + '\n'
        return ''

    def apply(self):
        return cd.apply(self.args, self.root, self.home)

    def test_corrupt_upload_never_changes_checkout_or_images(self):
        self.archive.write_bytes(b'corrupt')
        before = self.env.read_bytes()
        with patch.object(cd, 'command') as command:
            with self.assertRaisesRegex(RuntimeError, 'checksum'):
                self.apply()
        command.assert_not_called()
        self.assertEqual(self.env.read_bytes(), before)
        self.assertFalse(self.archive.exists())

    def test_moved_main_rejected_before_loading_images(self):
        with patch.object(cd, 'command', return_value='c' * 40) as command:
            with self.assertRaisesRegex(RuntimeError, 'main moved'):
                self.apply()
        self.assertFalse(any(call.args[0][0] == 'docker' for call in command.call_args_list))

    def test_wrong_backend_fingerprint_rejected_before_checkout(self):
        with patch.object(cd, 'command', side_effect=self.command) as command, \
                patch.object(cd, 'image_fingerprint', return_value='c' * 64):
            with self.assertRaisesRegex(RuntimeError, 'fingerprint mismatch'):
                self.apply()
        self.assertFalse(any(call.args[0][:2] == ['git', 'reset'] for call in command.call_args_list))
        self.assertIn('BACKEND_IMAGE=old', self.env.read_text())

    def test_prepare_persists_tested_image_and_preserves_secrets(self):
        with patch.object(cd, 'command', side_effect=self.command), \
                patch.object(cd, 'image_fingerprint', return_value=FINGERPRINT):
            result = self.apply()
        self.assertEqual(result['phase'], 'prepared')
        self.assertEqual(result['configDigest'], cd.file_digest(self.env))
        self.assertIn('MYSQL_PASSWORD=keep-secret', self.env.read_text())
        self.assertIn('BACKEND_IMAGE=smartticketing-backend:' + REVISION, self.env.read_text())
        self.assertFalse(self.archive.exists())

    def test_schema_bootstrap_is_explicit_and_only_on_a(self):
        for role, bootstrap, expected in (('a', False, False), ('a', True, True), ('b', True, False)):
            with self.subTest(role=role, bootstrap=bootstrap):
                self.args.phase, self.args.role, self.args.bootstrap = 'start', role, bootstrap
                with patch.object(cd, 'command', side_effect=self.command) as command, \
                        patch.object(cd, 'image_fingerprint', return_value=FINGERPRINT):
                    self.apply()
                calls = [call.args[0] for call in command.call_args_list]
                self.assertEqual(any('DDL_MODE=update' in call for call in calls), expected)
                self.assertIn(['python3', 'scripts/distributed/lab.py', 'up', '--role', role], calls)

    def test_cd_only_checks_readiness_without_choosing_test_population(self):
        self.args.phase = 'verify'
        with patch.object(cd, 'command', side_effect=self.command) as command, \
                patch.object(cd, 'image_fingerprint', return_value=FINGERPRINT):
            result = self.apply()
        self.assertEqual(result['checks'], ['preflight'])
        self.assertEqual(command.call_args_list[-1].args[0][-1], 'preflight')
        self.assertFalse(any('balancing' in call.args[0] for call in command.call_args_list))

    def run_runner(self, *, mismatched=False, fail_b=False):
        fingerprint_file = self.home / 'backend.fingerprint'
        fingerprint_file.write_text(FINGERPRINT)
        args = argparse.Namespace(revision=REVISION, artifact_id='123-1', bootstrap=False,
                                  archive=str(self.archive), fingerprint_file=str(fingerprint_file))
        phases = []

        def remote(argv, **kwargs):
            if argv[0] == 'ssh-keyscan':
                return 'host ssh-ed25519 dummy\n'
            script_args = argv[-1].split()
            role = script_args[script_args.index('--role') + 1]
            phase = script_args[script_args.index('--phase') + 1]
            phases.append((role, phase))
            if fail_b and role == 'b' and phase == 'start':
                raise RuntimeError('B not ready')
            return json.dumps({'role': role, 'phase': phase,
                               'configDigest': role if mismatched else 'same-config'})

        factory = tempfile.TemporaryDirectory
        report = self.home / 'report.json'
        env = dict(AWS_USERNAME='ubuntu', AWS_SSH_KEY='test-key',
                   AWS_DISTRIBUTED_A_HOST='a.example.com', AWS_DISTRIBUTED_B_HOST='b.example.com')
        with patch.dict(os.environ, env), patch.object(cd, 'command', side_effect=remote), \
                patch.object(cd.subprocess, 'run', return_value=Mock(returncode=0)), \
                patch.object(cd.tempfile, 'TemporaryDirectory', side_effect=lambda: factory(dir=self.home)), \
                contextlib.redirect_stdout(io.StringIO()):
            if mismatched or fail_b:
                with self.assertRaises(RuntimeError):
                    cd.run(args, report)
            else:
                cd.run(args, report)
        result = json.loads(report.read_text())
        self.assertNotIn('configDigest', report.read_text())
        self.assertEqual(result['status'], 'failed' if mismatched or fail_b else 'passed')
        return phases

    def test_prepares_both_hosts_before_starting_then_verifies(self):
        self.assertEqual(self.run_runner(), [('a', 'prepare'), ('b', 'prepare'),
                                            ('a', 'start'), ('b', 'start'), ('a', 'verify')])

    def test_different_shared_configuration_prevents_start(self):
        self.assertEqual(self.run_runner(mismatched=True), [('a', 'prepare'), ('b', 'prepare')])

    def test_b_failure_is_not_reported_as_success(self):
        phases = self.run_runner(fail_b=True)
        self.assertNotIn(('a', 'verify'), phases)

    def test_fingerprint_matches_shell_metadata_hash(self):
        metadata = '["sha256:layer"]|"linux"|"amd64"|""|""'
        with patch.object(cd, 'command', return_value=metadata + '\n'):
            self.assertEqual(cd.image_fingerprint('image'), hashlib.sha256((metadata + '\n').encode()).hexdigest())


if __name__ == '__main__':
    unittest.main()
