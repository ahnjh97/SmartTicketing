import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace


class CoreControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        with patch.dict(os.environ, {'BENCHMARK_OUTPUT': self.temp.name}):
            spec = importlib.util.spec_from_file_location('core', Path(__file__).with_name('core-controller.py'))
            self.c = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(self.c)
        self.c.database = 'booking_test_abc123'

    def test_sql_counters_require_enabled_collector(self):
        with patch.object(self.c, 'output', return_value='100\n0\n1\nYES'):
            self.assertEqual(self.c.sql_stats(), (100, 0))
        with patch.object(self.c, 'output', return_value='0\n0\n0\nNO'):
            with self.assertRaises(RuntimeError):
                self.c.sql_stats()
        self.c.database = 'production'
        with self.assertRaises(ValueError):
            self.c.sql_stats()

    def test_digest_loss_or_reset_is_not_reported_as_zero_sql(self):
        for after in [(90, 0), (150, 1)]:
            self.c.backend = 'test'
            with patch.object(self.c, 'sql_stats', side_effect=[(100, 0), after]), \
                 patch.object(self.c, 'redis_stats', return_value={'keyspace_hits': 0}), \
                 patch.object(self.c.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
                self.c.handle({'action': 'k6', 'env': {}, 'args': [], 'log': '/results/load.log'})
            result = json.loads((Path(self.temp.name) / 'load.sql.json').read_text())
            self.assertFalse(result['valid'])
            self.assertIsNone(result['statements'])

    def test_before_uses_historical_image_and_unreachable_redis(self):
        info = {'Image': 'sha256:before', 'Config': {'Entrypoint': ['sh', '/app/backend-entrypoint.sh']},
                'HostConfig': {'NanoCpus': 0, 'Memory': 0}}
        with patch.object(self.c, 'output', side_effect=['appendonly\nyes', 'container', json.dumps([info]), 'true']) as out, \
             patch.object(self.c, 'run'), patch.object(self.c.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
            self.c.handle({'action': 'start', 'args': ['--app.cache.enabled=false', '--spring.data.redis.host=redis', '--app.cache.main-local-ttl-ms=500',
                           '--spring.datasource.url=jdbc:mysql://mysql:3306/booking_test_abc123'], 'evidence': '/results/backend'})
        args = out.call_args_list[1].args[0]
        self.assertIn('backend-before', args)
        self.assertIn('--spring.data.redis.host=redis-disabled.invalid', args)
        self.assertIn('--app.admission.enabled=false', args)
        self.assertNotIn('--app.cache.main-local-ttl-ms=500', args)
        self.assertEqual(args.count('--app.cache.main-local-ttl-ms=0'), 1)
        self.assertEqual(self.c.database, 'booking_test_abc123')


if __name__ == '__main__':
    unittest.main()
