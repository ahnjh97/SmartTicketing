import csv
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from urllib.parse import urlencode

from harness import (Client, FixtureServer, default_fixtures, percentile, summarize,
                     validate_fixtures, validate_response)
from runner import BRANCHES, FIELDS, measure, report, seed_sql


class QualityTests(unittest.TestCase):
    def setUp(self):
        self.scenario = default_fixtures()[0]
        self.rows = [dict(kakaoPlaceId=t['id'], transitMinutes=t['transitMinutes'],
                          transitDistance=t['transitDistance'], walkMinutes=30)
                     for t in self.scenario['theaters']]

    def validate(self, original=False):
        return validate_response(200, json.dumps(self.rows), self.scenario, 'stub', original)

    def test_http_200_with_null_route_is_failure(self):
        self.rows[0]['transitMinutes'] = None
        self.assertEqual(self.validate()[1], 'missing_or_invalid_transit')

    def test_empty_duplicate_and_wrong_candidates_fail(self):
        self.rows[1]['kakaoPlaceId'] = self.rows[0]['kakaoPlaceId']
        self.assertEqual(self.validate()[1], 'candidate_mismatch')
        self.rows = []
        self.assertFalse(self.validate()[0])

    def test_incorrect_sort_and_wrong_route_fail(self):
        self.rows.reverse()
        self.assertEqual(self.validate()[1], 'incorrect_sort')
        self.rows.reverse()
        self.rows[0]['transitDistance'] += 1
        self.assertEqual(self.validate()[1], 'route_mismatch')

    def test_original_must_complete_walk_too(self):
        self.rows[0]['walkMinutes'] = None
        self.assertTrue(self.validate()[0])
        self.assertEqual(self.validate(True)[1], 'missing_walk')

    def test_semantic_signature_ignores_database_id(self):
        signature = self.validate()[3]
        for r in self.rows:
            r['theaterId'] = 9999
        self.assertEqual(self.validate()[3], signature)

    def test_fixture_candidates_match_db_filter(self):
        scenarios = default_fixtures()
        validate_fixtures(scenarios)
        scenarios[1]['latitude'] = scenarios[0]['latitude']
        scenarios[1]['longitude'] = scenarios[0]['longitude']
        with self.assertRaises(ValueError):
            validate_fixtures(scenarios)

    def test_seed_escapes_strings_without_sql_interpolation(self):
        self.scenario['theaters'][0]['name'] = "x'); DROP DATABASE important; --"
        sql = seed_sql([self.scenario])
        self.assertNotIn('DROP DATABASE', sql)
        self.assertEqual(sql.count('INSERT INTO theaters'), 3)

    def test_failures_and_warmups_do_not_improve_percentiles(self):
        rows = [dict(branch='b', scenario='s', round='1', phase='measure', valid=True, response_ms=i)
                for i in range(1, 101)]
        rows += [dict(branch='b', scenario='s', round='1', phase='measure', valid=False, response_ms=.1),
                 dict(branch='b', scenario='s', round='1', phase='warmup', valid=True, response_ms=9999)]
        result = summarize(rows)[0]
        self.assertEqual(result['p95_ms'], 95)
        self.assertEqual(result['p50_ms'], 50)
        self.assertEqual(result['requests'], 101)
        self.assertEqual(result['valid'], 100)
        self.assertGreater(result['error_pct'], 0)
        self.assertIsNone(percentile([], .95))

    def test_partial_run_does_not_publish_improvement(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / 'environment.json').write_text('{"mode":"stub"}', encoding='utf-8')
            with (folder / 'raw.csv').open('w', newline='', encoding='utf-8') as stream:
                writer = csv.DictWriter(stream, fieldnames=FIELDS)
                writer.writeheader()
                writer.writerow(dict(branch=BRANCHES[0], scenario='s', round=1,
                                     phase='measure', valid=True, response_ms=100))
            self.assertFalse(report(folder, False))
            text = (folder / 'report.md').read_text()
            self.assertNotIn('% reduction', text)
            self.assertIn('Incomplete/invalid', text)

    def test_failed_preflight_flushes_row_and_cleans_owned_database(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            process = Mock()
            process.poll.return_value = None
            client = Mock()
            client.request.return_value = (200, b'[]', 10, '')
            args = SimpleNamespace(scenarios=[self.scenario], startup_timeout=1, timeout=1,
                                   warmup=0, runs=1, mode='stub')
            build = dict(branch=BRANCHES[0], sha='test', jar=out / 'test.jar')
            with patch('runner.database') as db, patch('runner.subprocess.Popen', return_value=process), \
                    patch('runner.wait_ready'), patch('runner.Client', return_value=client):
                with (out / 'raw.csv').open('w', newline='', encoding='utf-8') as raw:
                    writer = csv.DictWriter(raw, fieldnames=FIELDS)
                    writer.writeheader()
                    with self.assertRaisesRegex(RuntimeError, 'preflight failed'):
                        measure(build, 1, self.scenario, args, {'BENCH_JWT_SECRET': 'test'},
                                out, writer, raw, None)
                self.assertEqual([c.args[0] for c in db.call_args_list], ['CREATE', 'DROP'])
                process.terminate.assert_called_once()
                client.close.assert_called_once()
                with (out / 'raw.csv').open() as raw:
                    rows = list(csv.DictReader(raw))
                self.assertEqual(rows[0]['valid'], 'False')
                self.assertEqual(rows[0]['phase'], 'preflight')


class HttpTests(unittest.TestCase):
    def test_real_http_fixture_and_unknown_routes(self):
        server = FixtureServer(default_fixtures(), route_ms=0, search_ms=0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
            query = urlencode(dict(query='CGV', y=37.5665, x=126.9780))
            connection.request('GET', '/v2/local/search/keyword.json?' + query)
            response = connection.getresponse()
            self.assertEqual(response.status, 200)
            self.assertEqual(len(json.loads(response.read())['documents']), 1)
            connection.request('GET', '/not-a-route')
            response = connection.getresponse()
            self.assertEqual(response.status, 404)
            response.read()
            self.assertEqual(server.snapshot()['errors'], 1)
            connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_client_records_actual_http_status(self):
        server = FixtureServer(default_fixtures(), route_ms=0, search_ms=0)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        client = Client(server.server_port, 'test-key')
        try:
            status, body, elapsed, error = client.request(default_fixtures()[0])
            self.assertEqual(status, 404)
            self.assertGreater(elapsed, 0)
            self.assertEqual(error, '')
        finally:
            client.close()
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    unittest.main()
