import csv
import http.client
import contextlib
import io
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from upstream import BudgetExceeded, Ledger, LiveGateway
from runner import BRANCHES, planned_calls, main
from harness import FixtureServer, default_fixtures


class BudgetTests(unittest.TestCase):
    def test_default_plan_and_over_budget_live_never_build_or_start_gateway(self):
        with tempfile.TemporaryDirectory() as tmp, \
             patch('runner.REPO', Path(tmp)), \
             patch('runner.environment', return_value={'KAKAO_MAP_REST_API_KEY': 'test'}), \
             patch('runner.snapshot_catalog', return_value=[]), \
             patch('runner.live_scenarios', return_value=default_fixtures()), \
             patch('runner.prepare') as build, patch('runner.LiveGateway') as gateway, \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            # A supplied catalog normally exists after snapshot_catalog returns.
            def snapshot(args, env, out):
                (out / 'seoul-theaters.csv').write_text('test')
                return []
            with patch('runner.snapshot_catalog', side_effect=snapshot):
                with patch('sys.argv', ['runner']):
                    self.assertEqual(main(), 0)
                with patch('sys.argv', ['runner', '--mode', 'live', '--budget-transit', '0']):
                    self.assertEqual(main(), 1)
            build.assert_not_called()
            gateway.assert_not_called()

    def test_concurrent_reservations_persist_across_instances(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'quota.db'
            ledgers = [Ledger(path, 'secret', dict(search=0, transit=7, walk=0)) for _ in range(4)]
            def reserve(i):
                try:
                    ledgers[i % 4].reserve('transit')
                    return 1
                except BudgetExceeded:
                    return 0
            with ThreadPoolExecutor(max_workers=12) as pool:
                self.assertEqual(sum(pool.map(reserve, range(30))), 7)
            self.assertEqual(Ledger(path, 'secret', ledgers[0].limits).remaining()['transit'], 0)
            self.assertEqual(Ledger(path, 'another-key', ledgers[0].limits).remaining()['transit'], 7)
            with patch.object(ledgers[0], 'day', return_value='2099-01-01'):
                self.assertEqual(ledgers[0].remaining()['transit'], 7)
            self.assertNotIn(b'secret', path.read_bytes())

    def test_corrupt_ledger_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'quota.db'
            path.write_bytes(b'broken' * 100)
            with self.assertRaises(sqlite3.DatabaseError):
                Ledger(path, 'key', dict(transit=10))

    def test_gateway_stops_after_error_and_never_refunds(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            ledger = Ledger(path / 'quota.db', 'test-secret', dict(search=0, transit=3, walk=0))
            gateway = LiveGateway('test-secret', ledger, path / 'events.csv')
            thread = threading.Thread(target=gateway.serve_forever)
            thread.start()
            upstream = Mock()
            upstream.getresponse.return_value = Mock(status=429, read=Mock(return_value=b'{}'))
            try:
                with patch('upstream.http.client.HTTPSConnection', return_value=upstream) as connect:
                    client = http.client.HTTPConnection('127.0.0.1', gateway.server_port)
                    for expected in [429, 429]:
                        client.request('GET', '/v2/routing/publictraffic?start_x=127')
                        response = client.getresponse()
                        self.assertEqual(response.status, expected)
                        response.read()
                    client.close()
                    self.assertEqual(connect.call_count, 1)
                    self.assertEqual(upstream.request.call_count, 1)
                    self.assertEqual(ledger.remaining()['transit'], 2)
            finally:
                gateway.shutdown()
                gateway.server_close()
                thread.join()
            with (path / 'events.csv').open(encoding='utf-8') as stream:
                events = list(csv.DictReader(stream))
            self.assertEqual([r['attempted'] for r in events], ['True', 'False'])
            self.assertNotIn('test-secret', (path / 'events.csv').read_text())

    def test_zero_budget_blocks_before_transmission(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            gateway = LiveGateway('key', Ledger(path / 'q.db', 'key', dict(search=0, transit=0, walk=0)), path / 'events.csv')
            try:
                with self.assertRaises(BudgetExceeded):
                    gateway.admit('/v2/routing/publictraffic')
                self.assertEqual(gateway.snapshot()['/v2/routing/publictraffic'], 0)
            finally:
                gateway.server_close()

    def test_plan_counts_preflight_warmup_all_rounds_and_original_limit(self):
        scenarios = default_fixtures()
        count = sum(len(s['theaters']) for s in scenarios)
        self.assertEqual(planned_calls(scenarios, BRANCHES[1:], 2, 1, 3)['transit'], count * 24)
        calls = planned_calls(scenarios, BRANCHES[:1], 1, 0, 1)
        self.assertEqual(calls, dict(search=len(scenarios)*6, transit=len(scenarios)*90, walk=len(scenarios)*90))

    def test_delay_is_repeatable_independent_of_request_order(self):
        server = FixtureServer(default_fixtures(), 100, 50, 'variable', 2026)
        try:
            server.begin_sample('same-sample')
            query = {'start_x': ['127'], 'end_x': ['128']}
            first = server.delay('/v2/routing/publictraffic', query)
            server.delay('/v2/routing/walk', {'end_x': ['129']})
            self.assertEqual(first, server.delay('/v2/routing/publictraffic', query))
        finally:
            server.server_close()
