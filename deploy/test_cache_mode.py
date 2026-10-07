"""Exercise mode changes and rollback without connecting to Docker or AWS."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('cache_mode', Path(__file__).with_name('cache-mode.py'))
mode = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mode)


class FakeDocker:
    running_mode = 'false'
    calls = []
    fail_modes = set()
    image = 'sha256:' + 'a' * 64

    def __init__(self, root, values):
        self.values = values

    def container(self, service, required=True):
        if service == 'backend':
            return {'id': 'backend-id', 'image': self.image, 'mode': self.running_mode}
        if service == 'frontend':
            return {'id': 'frontend-id', 'image': 'sha256:' + 'b' * 64, 'mode': ''}
        return None

    def compose(self, *args):
        self.calls.append(('compose', args))

    def run(self, *args):
        self.calls.append(('docker', args))

    def recreate(self):
        self.calls.append(('recreate', dict(self.values)))
        FakeDocker.running_mode = self.values['APP_CACHE_ENABLED']
        if self.values['APP_CACHE_ENABLED'] in self.fail_modes:
            raise RuntimeError('Simulated unhealthy backend')


class ModeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / '.env').write_text('JWT_SECRET=private\nAPP_CACHE_ENABLED=false\n')
        FakeDocker.running_mode = 'false'
        FakeDocker.calls = []
        FakeDocker.fail_modes = set()
        patcher = patch.object(mode, 'Docker', FakeDocker)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_switch_pins_running_images_and_leaves_secrets_untouched(self):
        mode.switch(self.root, 'true')
        state = mode.read_state(self.root)
        self.assertEqual(state['APP_CACHE_ENABLED'], 'true')
        self.assertEqual(state['BACKEND_IMAGE'], FakeDocker.image)
        self.assertEqual(state['FRONTEND_IMAGE'], 'sha256:' + 'b' * 64)
        self.assertNotIn('private', (self.root / mode.STATE).read_text())
        self.assertEqual((self.root / '.env').read_text(), 'JWT_SECRET=private\nAPP_CACHE_ENABLED=false\n')
        mode.switch(self.root, 'false')
        self.assertEqual(mode.read_state(self.root)['APP_CACHE_ENABLED'], 'false')

    def test_failure_restores_previous_mode_and_preserves_saved_state(self):
        mode.write_state(self.root, {'APP_CACHE_ENABLED': 'false', 'BACKEND_IMAGE': 'old:tag'})
        before = (self.root / mode.STATE).read_bytes()
        FakeDocker.fail_modes = {'true'}
        with self.assertRaises(RuntimeError):
            mode.switch(self.root, 'true')
        recreates = [values for command, values in FakeDocker.calls if command == 'recreate']
        self.assertEqual([x['APP_CACHE_ENABLED'] for x in recreates], ['true', 'false'])
        self.assertTrue(all(x['BACKEND_IMAGE'] == FakeDocker.image for x in recreates))
        self.assertEqual(FakeDocker.running_mode, 'false')
        self.assertEqual((self.root / mode.STATE).read_bytes(), before)

    def test_legacy_container_is_rejected_before_mutation(self):
        FakeDocker.running_mode = ''
        with self.assertRaisesRegex(RuntimeError, 'Deploy this tooling revision first'):
            mode.switch(self.root, 'true')
        self.assertEqual(FakeDocker.calls, [])
        self.assertFalse((self.root / mode.STATE).exists())

    def test_noop_persists_current_image_without_restarting(self):
        mode.switch(self.root, 'false')
        self.assertFalse(any(x[0] == 'recreate' for x in FakeDocker.calls))
        self.assertEqual(mode.read_state(self.root)['BACKEND_IMAGE'], FakeDocker.image)

    def test_unknown_or_invalid_state_is_rejected(self):
        for content in ['APP_CACHE_ENABLED=maybe', 'DB_PASSWORD=secret', 'BACKEND_IMAGE=$(evil)',
                        'APP_CACHE_ENABLED=true\nAPP_CACHE_ENABLED=false']:
            (self.root / mode.STATE).write_text(content)
            with self.subTest(content=content), self.assertRaises(ValueError):
                mode.switch(self.root, 'true')

    def test_concurrent_switch_is_rejected(self):
        with mode.operation_lock(self.root):
            with self.assertRaises(RuntimeError):
                mode.switch(self.root, 'true')
        self.assertFalse((self.root / mode.STATE).exists())

    def test_state_write_failure_also_restores_previous_mode(self):
        with patch.object(mode, 'write_state', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                mode.switch(self.root, 'true')
        self.assertEqual(FakeDocker.running_mode, 'false')


class DockerCommandTests(unittest.TestCase):
    def test_explicit_env_files_and_selected_images_override_caller_environment(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            mode.write_state(root, {'APP_CACHE_ENABLED': 'false'})
            with patch.dict(os.environ, {'BACKEND_IMAGE': 'wrong:tag', 'APP_CACHE_ENABLED': 'false'}):
                docker = mode.Docker(root, {'BACKEND_IMAGE': 'sha256:abc', 'APP_CACHE_ENABLED': 'true'})
            with patch.object(mode.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '', '')) as run:
                docker.compose('config', '--quiet')
            self.assertEqual(run.call_args.args[0], ['docker', 'compose', '-f', 'docker-compose.yml',
                                                   '--env-file', '.env', '--env-file', '.env.deployment',
                                                   'config', '--quiet'])
            self.assertEqual(run.call_args.kwargs['env']['BACKEND_IMAGE'], 'sha256:abc')
            self.assertEqual(run.call_args.kwargs['env']['APP_CACHE_ENABLED'], 'true')

    def test_recreate_waits_and_does_not_build_pull_or_restart_dependencies(self):
        docker = mode.Docker(Path('.'), {'BACKEND_IMAGE': 'sha256:abc', 'APP_CACHE_ENABLED': 'true'})
        with patch.object(docker, 'compose') as compose, patch.object(docker, 'container',
                return_value={'image': 'sha256:abc', 'mode': 'true'}), patch.object(docker, 'reload_proxy') as reload:
            docker.recreate()
        compose.assert_called_once_with('up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                                        '--force-recreate', '--wait', '--wait-timeout', '600', 'backend')
        reload.assert_called_once()

    def test_image_or_mode_mismatch_is_not_reported_as_success(self):
        docker = mode.Docker(Path('.'), {'BACKEND_IMAGE': 'sha256:abc', 'APP_CACHE_ENABLED': 'true'})
        for actual in [{'image': 'wrong', 'mode': 'true'}, {'image': 'sha256:abc', 'mode': 'false'}]:
            with patch.object(docker, 'compose'), patch.object(docker, 'container', return_value=actual):
                with self.assertRaises(RuntimeError):
                    docker.recreate()

    def test_command_errors_do_not_expose_secret_output(self):
        docker = mode.Docker(Path('.'), {})
        with patch.object(mode.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, 'secret', 'secret')):
            with self.assertRaises(RuntimeError) as error:
                docker.compose('config', '--quiet')
        self.assertNotIn('secret', str(error.exception))


if __name__ == '__main__':
    unittest.main()
