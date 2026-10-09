import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        with patch.dict(os.environ, {'BENCHMARK_OUTPUT': self.temp.name}):
            spec = importlib.util.spec_from_file_location('controller', Path(__file__).with_name('container-controller.py'))
            self.controller = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(self.controller)
        self.controller.compose = ['docker', 'compose', '--project-name', 'isolated-test']

    def test_result_paths_cannot_escape_run(self):
        c = self.controller
        for path in ['/etc/passwd', '/results/../outside', '/results/a/../../outside']:
            with self.assertRaises(ValueError):
                c.result_path(path)
        self.assertEqual(c.result_path('/results/run/output.json'), Path(self.temp.name) / 'run/output.json')

    def test_start_preserves_image_entrypoint_and_confirms_aof(self):
        c = self.controller
        info = {'Image': 'sha256:app', 'Config': {'Entrypoint': ['sh', '/app/backend-entrypoint.sh']},
                'HostConfig': {'NanoCpus': 4000000000, 'Memory': 4294967296}}
        with patch.object(c, 'output', side_effect=['appendonly\nyes', 'container-id', json.dumps([info]), 'true']) as output, \
             patch.object(c, 'run'), patch.object(c.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
            result = c.handle({'action': 'start', 'args': ['--app.cache.enabled=false'], 'evidence': '/results/backend'})
        self.assertFalse(result['cacheEnabled'])
        self.assertTrue(result['redisAof'])
        self.assertEqual(result['entrypoint'], ['sh', '/app/backend-entrypoint.sh'])
        launch = output.call_args_list[1].args[0]
        self.assertIn('--use-aliases', launch)
        self.assertNotIn('--entrypoint', launch)

    def test_k6_preserves_failure_code_without_putting_token_on_command_line(self):
        c = self.controller
        c.backend = 'backend-id'
        with patch.object(c.subprocess, 'run', return_value=SimpleNamespace(returncode=99)) as run, \
             patch.object(c, 'redis_stats', side_effect=[{'keyspace_hits': 1}, {'keyspace_hits': 5}]):
            result = c.handle({'action': 'k6', 'args': ['run', 'k6/test.js'],
                               'env': {'K6_ACCESS_TOKEN': 'temporary-token'}, 'log': '/results/k6.log'})
        self.assertEqual(result['exitCode'], 99)
        self.assertNotIn('temporary-token', run.call_args.args[0])
        self.assertEqual(run.call_args.kwargs['env']['K6_ACCESS_TOKEN'], 'temporary-token')
        self.assertEqual(json.loads((Path(self.temp.name) / 'k6.redis.json').read_text()), {'keyspace_hits': 4})

    def test_failed_backend_is_removed_and_reported(self):
        c = self.controller
        info = {'Image': 'sha256:app', 'Config': {'Entrypoint': ['sh', '/app/backend-entrypoint.sh']},
                'HostConfig': {'NanoCpus': 4000000000, 'Memory': 4294967296}}
        with patch.object(c, 'output', side_effect=['appendonly\nyes', 'dead-id', json.dumps([info]), 'false']), \
             patch.object(c, 'run') as run, patch.object(c.subprocess, 'run'):
            with self.assertRaisesRegex(RuntimeError, 'failed readiness'):
                c.handle({'action': 'start', 'args': ['--app.cache.enabled=true'], 'evidence': '/results/backend'})
        self.assertIsNone(c.backend)
        self.assertTrue((Path(self.temp.name) / 'backend.log').exists())
        self.assertIn(['docker', 'rm', '-f', 'dead-id'], [call.args[0] for call in run.call_args_list])


if __name__ == '__main__':
    unittest.main()
