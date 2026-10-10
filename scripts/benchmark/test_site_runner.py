"""Run with: python3 -m unittest discover -s scripts/benchmark -p test_site_runner.py"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('site_runner', Path(__file__).with_name('run-site.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class SiteRunnerTests(unittest.TestCase):
    def test_capacity_excludes_failed_slow_and_incomplete_repetitions(self):
        def row(users, status='passed', p95=100, rate=10):
            return {'label':'final-on','users':users,'status':status,
                    'metrics':{'p95Ms':p95,'successfulBusinessRequestsPerSecond':rate}}
        runs = [row(10),row(10),row(20,rate=30),row(20,status='failed',rate=100),row(40,rate=50)]
        result = runner.capacity_summary(runs,[10,20,40],2,1000,False)
        self.assertEqual(result['variants']['final-on']['bestObservedPassingRate']['users'],10)
        self.assertFalse(result['variants']['final-on']['highestTestedLoadPassed'])
        self.assertEqual(runner.capacity_summary(runs,[10,20,40],2,1000,True)['status'],'not-measured')
        self.assertIsNone(runner.capacity_summary([row(10,p95=2000)], [10,20],1,1000,False)[
            'variants']['final-on']['bestObservedPassingRate'])

    def test_comparison_conditions(self):
        self.assertEqual(runner.comparison_cases('db'), [('before-off',0,'off'),('final-off',1,'off')])
        self.assertEqual(runner.comparison_cases('redis'), [('final-off',0,'off'),('final-on',0,'on')])
        self.assertEqual(runner.comparison_cases('overall'), [('before-off',0,'off'),('final-on',1,'on')])
        self.assertEqual(len(runner.comparison_cases('all')), 3)

    def test_redis_run_reuses_exact_images_and_propagates_failure(self):
        seen = []
        def execute(args, source, folder, label, ref, sha, cache, images):
            seen.append((sha, cache, images))
            return {'label':label,'commit':sha,'status':'passed' if cache == 'off' else 'failed',
                    'images':{'backend':'sha256:backend','frontend':'sha256:frontend'}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / 'scripts/benchmark'
            scripts.mkdir(parents=True)
            for filename in ('site-load.js','site-fixture.py'):
                (scripts / filename).write_text('fixture')
            with patch.object(runner,'ROOT',root), patch.object(runner,'command',return_value=''), \
                    patch.object(runner,'snapshot',return_value='abc123'), patch.object(runner,'git',return_value=''), \
                    patch.object(runner,'execute',execute), \
                    patch.object(sys,'argv',['run-site.py','--mode','compare','--comparison','redis','--ref','final','--smoke']):
                self.assertEqual(runner.main(), 1)
            self.assertEqual(seen, [('abc123','off',None), ('abc123','on',('sha256:backend','sha256:frontend'))])
            report = next(root.glob('benchmark-results/*/comparison.json'))
            self.assertEqual(json.loads(report.read_text())['runs'][1]['status'],'failed')

    def test_sql_collector_excludes_default_database_and_rejects_disabled_collection(self):
        with patch.object(runner,'command',return_value='250\n0\n1\nYES') as command:
            self.assertEqual(runner.sql_snapshot(['docker','compose'],'local'), (250,0))
            self.assertNotIn('site_benchmark', command.call_args.args[0][:-1])
        with patch.object(runner,'command',return_value='0\n0\n1\nNO'):
            with self.assertRaises(RuntimeError): runner.sql_snapshot(['docker','compose'],'local')

    def test_percentiles_merge_raw_samples_and_keep_journey_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, samples in enumerate(([1, 2, 3], [100])):
                path = root / f'client-{index}'
                path.mkdir()
                points = [{'type':'Point', 'metric':'business_latency', 'data':{'value':n}} for n in samples]
                points += [{'type':'Point', 'metric':'completed_journeys',
                            'data':{'value':1, 'tags':{'scenario':'default', 'journey':'smart'}}}]
                (path / 'metrics.jsonl').write_text('\n'.join(map(json.dumps, points)))
            result = runner.summarize(root)
            self.assertAlmostEqual(result['p95Ms'], 85.45)
            self.assertEqual(result['requests'], 4)
            self.assertEqual(result['scenarios'], {'smart':2})
            self.assertIsNone(result['failureRate'])

    def test_compose_cannot_attach_production_names_ports_or_secrets(self):
        production = {
            'services':{name:{'container_name':'production-'+name, 'image':'example',
                               'ports':[{'published':'3306','target':3306}],
                               'environment':{}} for name in ('backend','frontend','nginx','mysql','redis','admission-redis')},
            'volumes':{'mysql_data':{'name':'production_mysql'}},
            'networks':{'smartticketing':{'name':'production_network'}}}
        production['services']['backend']['environment'] = {'ADMISSION_ENABLED':'true'}
        observed = []
        def command(args, **kwargs):
            if args[:2] == ['docker','compose']:
                observed.append(kwargs['env'])
                return json.dumps(production)
            return ''
        with tempfile.TemporaryDirectory() as directory, patch.object(runner, 'command', command), \
                patch.dict(runner.os.environ, {'MYSQL_ROOT_PASSWORD':'production-secret', 'ADMISSION_ENABLED':'false'}):
            path, password, _ = runner.compose_config(Path(directory), 'test-only', 'backend:test', 'frontend:test', 'off')
            data = json.loads(path.read_text())
            self.assertNotIn('production-secret', path.read_text())
            self.assertNotIn('MYSQL_ROOT_PASSWORD', observed[0])
            self.assertFalse((Path(directory) / 'private.env').exists())
            self.assertNotIn('name', data['volumes']['mysql_data'])
            self.assertNotIn('name', data['networks']['smartticketing'])
            self.assertNotIn('ports', data['services']['mysql'])
            self.assertEqual(data['services']['nginx']['ports'][0]['host_ip'], '127.0.0.1')
            self.assertTrue(all('container_name' not in item for item in data['services'].values()))


if __name__ == '__main__':
    unittest.main()
