"""Check the verifier's pass/fail decisions; these do not replace EC2 integration tests."""
from collections import OrderedDict
import threading
import unittest
from unittest.mock import patch

import checks


class CheckTests(unittest.TestCase):
    def test_balancing_requires_two_upstreams_including_b(self):
        replies = [(200, {}, '172.18.0.5:8080'), (200, {}, '10.0.1.11:8080')] * 2
        with patch.object(checks.Client, 'call', side_effect=replies):
            result = checks.balancing('proxy', '10.0.1.11:8080', 4, 2)
        self.assertEqual(result['upstreamCounts'], {'172.18.0.5:8080': 2, '10.0.1.11:8080': 2})
        with patch.object(checks.Client, 'call', return_value=(200, {}, '172.18.0.5:8080')):
            with self.assertRaises(AssertionError):
                checks.balancing('proxy', '10.0.1.11:8080', 4, 2)

    def test_balancing_rejects_failover_as_normal_distribution(self):
        with patch.object(checks.Client, 'call', return_value=(200, {}, 'a:8080, b:8080')):
            with self.assertRaises(AssertionError):
                checks.balancing('proxy', 'b:8080', 2, 1)

    def test_disabled_gate_is_not_a_pass(self):
        with patch.object(checks.Client, 'call', return_value=(200, [], '')):
            with self.assertRaises(AssertionError):
                checks.admission('a', 'b', 'proxy', 2, 4)

    def run_gate(self, *, broken_b=False, excess=False):
        sessions = OrderedDict()
        lock = threading.Lock()
        observed_calls = []

        class Browser:
            def __init__(self):
                self.cookie = None

            def call(self, base, path, method='GET'):
                with lock:
                    observed_calls.append((base, path))
                    if path == '/api/admission/enter':
                        self.cookie = str(len(sessions) + 1)
                        sessions[self.cookie] = 'WAITING'
                    if path == '/api/admission/leave':
                        sessions.pop(self.cookie, None)
                        self.cookie = None
                        return 200, {'state': 'EXPIRED'}, ''
                    for key in sessions:
                        if sessions[key] == 'WAITING' and (excess or list(sessions.values()).count('ADMITTED') < 2):
                            sessions[key] = 'ADMITTED'
                    state = sessions.get(self.cookie, 'EXPIRED')
                    if path == '/api/movies':
                        if state == 'ADMITTED' and not (broken_b and base == 'b'):
                            return 200, [], ''
                        return 429, {'code': 'ADMISSION_REQUIRED'}, ''
                    return 200, {'state': state}, ''

        with patch.object(checks, 'Client', Browser):
            if broken_b or excess:
                with self.assertRaises(AssertionError):
                    checks.admission('a', 'b', 'proxy', 2, 4)
            else:
                result = checks.admission('a', 'b', 'proxy', 2, 4)
                self.assertEqual(result['initialAdmitted'], 2)
                self.assertEqual(result['initialWaiting'], 2)
                self.assertEqual(result['promoted'], 2)
                self.assertIn(('a', '/api/movies'), observed_calls)
                self.assertIn(('b', '/api/movies'), observed_calls)
        self.assertEqual(sessions, {}, 'Test-owned sessions must be released even on failure')

    def test_cross_server_admission_and_promotion(self):
        self.run_gate()

    def test_admitted_cookie_rejected_by_b_fails(self):
        self.run_gate(broken_b=True)

    def test_global_capacity_violation_fails(self):
        self.run_gate(excess=True)


if __name__ == '__main__':
    unittest.main()
