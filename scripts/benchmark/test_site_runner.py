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
    def test_reanalysis_preserves_original_and_refuses_unsynchronized_old_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); run = root/'run-1'; run.mkdir()
            payload = {'smoke':True,'runs':[{'artifacts':'run-1','label':'current','users':2,
                       'commit':'abc','status':'passed','metrics':{}}]}
            original = json.dumps(payload)
            (root/'comparison.json').write_text(original)
            with self.assertRaisesRegex(RuntimeError,'no shared measurement window'):
                runner.reanalyze(root)
            (run/'window.json').write_text('{}')
            with patch.object(runner,'summarize',return_value={'allPhaseErrors':0}):
                self.assertEqual(runner.reanalyze(root),0)
            self.assertEqual((root/'comparison.json').read_text(),original)
            report = json.loads((root/'reanalysis-v2/comparison.json').read_text())
            self.assertEqual(report['performance']['comparisons'],[])
            self.assertTrue((root/'reanalysis-v2/analyzer.py').exists())

    def test_measurement_excludes_warmup_drain_and_boundary_crossing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'window.json').write_text(json.dumps({'start':100,'end':160,'durationSeconds':60}))
            client = root/'client-0'; client.mkdir()
            def point(at, value, started=None, metric='business_latency'):
                return {'type':'Point','metric':metric,'data':{'time':runner.datetime.datetime.fromtimestamp(
                    at,runner.datetime.timezone.utc).isoformat(),'value':value,
                    'tags':{'endpoint':'GET /api/movies','startedMs':str((at if started is None else started)*1000)}}}
            samples = [point(99,999),point(101,999,99),point(110,10),point(150,20),point(160,999),point(161,50,159),
                       point(110,1,metric='successful_business_requests'),point(150,1,metric='successful_business_requests')]
            samples[5]['data']['tags']={'endpoint':'GET /api/movies','phase':'measurement'}
            (client/'metrics.jsonl').write_text('\n'.join(map(json.dumps,samples)))
            (client/'summary.json').write_text(json.dumps({'metrics':{
                'business_failures':{'values':{'passes':1,'fails':999}},
                'admission_errors':{'values':{'passes':0,'fails':100}}}}))
            result = runner.summarize(root)
            self.assertEqual(result['allPhaseErrors'],1)
            self.assertEqual(result['requests'],3)
            self.assertEqual(result['successfulBusinessRequestsPerSecond'],2/60)
            self.assertEqual(result['endpoints']['GET /api/movies']['samples'],3)
            self.assertGreater(result['p95Ms'],40)
            self.assertIsNone(result['endpoints']['GET /api/movies']['p99Ms'])

    def test_performance_requires_paired_repeats_samples_and_identical_redis_images(self):
        def row(mode, repeat, p95):
            return {'users':50,'label':'final-'+mode,'repeat':repeat,'status':'passed','warmupSeconds':30,
                    'measurementWindow':{'durationSeconds':60},'images':{'backend':'same','frontend':'same'},'cacheEnabled':mode=='on',
                    'metrics':{'failedChecks':0,'admissionErrors':0,'allPhaseErrors':0,
                               'endpoints':{'GET /api/movies':{'samples':250,'p95Ms':p95}}}}
        rows = [row(mode,n,10 if mode=='off' else (9 if n%2 else 11)) for n in range(1,5) for mode in ('off','on')]
        self.assertEqual(runner.performance_summary(rows,True)['comparisons'],[])
        result = runner.performance_summary(rows,False)['comparisons'][0]
        self.assertIn('방향 다름',result['endpoints']['GET /api/movies']['observation'])
        self.assertEqual(runner.performance_summary(rows[:2],False)['comparisons'][0]['status'],'비교 보류')
        rows[-1]['metrics']['endpoints']['GET /api/movies']['samples']=199
        self.assertEqual(runner.performance_summary(rows,False)['comparisons'][0]['endpoints'],{})
        rows[-1]['images']={'backend':'different'}
        self.assertIn('이미지 불일치',runner.performance_summary(rows,False)['comparisons'][0]['reason'])

    def test_capacity_excludes_failed_slow_and_incomplete_repetitions(self):
        def row(users, status='passed', p95=100, rate=10):
            return {'label':'final-on','users':users,'status':status,'warmupSeconds':30,
                    'measurementWindow':{'durationSeconds':60},
                    'metrics':{'p95Ms':p95,'successfulBusinessRequestsPerSecond':rate,'requests':300,'failedChecks':0,'admissionErrors':0}}
        runs = [row(10) for _ in range(4)] + [row(20,rate=30) for _ in range(3)] + [row(20,status='failed',rate=100),row(40,rate=50)]
        result = runner.capacity_summary(runs,[10,20,40],4,1000,False)
        self.assertEqual(result['variants']['final-on']['bestObservedPassingRate']['users'],10)
        self.assertFalse(result['variants']['final-on']['highestTestedLoadPassed'])
        self.assertEqual(runner.capacity_summary(runs,[10,20,40],2,1000,True)['status'],'not-measured')
        self.assertEqual(runner.capacity_summary(runs,[10,20,40],1,1000,False)['status'],'not-measured')
        self.assertIsNone(runner.capacity_summary([row(10,p95=2000) for _ in range(4)], [10,20],4,1000,False)[
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
            for filename in ('site-load.js','site-fixture.py','run-site.py'):
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
