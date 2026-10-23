"""EC2 configuration and command checks; no AWS or Docker access required."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import lab


class Ec2LabTests(unittest.TestCase):
    def config(self, **changes):
        config = dict(SERVER_A_IP='10.0.1.10', SERVER_B_IP='10.0.1.11',
                      MYSQL_PASSWORD='lab-password', REDIS_PASSWORD='redis-password',
                      JWT_SECRET='a' * 64, LAB_MODE='two')
        return config | changes

    def test_only_private_ipv4_on_separate_hosts(self):
        lab.validate_settings(self.config())
        for address in ('54.1.2.3', '127.0.0.1', '0.0.0.0', '169.254.169.254', '::1'):
            with self.subTest(address=address), self.assertRaises(ValueError):
                lab.validate_settings(self.config(SERVER_A_IP=address))
        with self.assertRaises(ValueError):
            lab.validate_settings(self.config(SERVER_B_IP='10.0.1.10'))

    def test_rejects_example_secrets_and_invalid_mode(self):
        for changes in ({'MYSQL_PASSWORD': 'change-this-lab-password'},
                        {'REDIS_PASSWORD': ''}, {'JWT_SECRET': 'short'}, {'LAB_MODE': 'three'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                lab.validate_settings(self.config(**changes))

    def test_init_generates_shared_config_without_overwriting(self):
        scratch = lab.ROOT / '.local'
        scratch.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            here = Path(directory)
            (here / '.env.example').write_text((lab.HERE / '.env.example').read_text())
            args = ['lab.py', 'init', '--server-a-ip', '10.20.1.5', '--server-b-ip', '10.20.1.6']
            with patch.object(lab, 'HERE', here), patch.object(lab, 'OUT', here / 'results'), \
                    patch('sys.argv', args), contextlib.redirect_stdout(io.StringIO()):
                lab.main()
                config = lab.settings()
                self.assertEqual(config['SERVER_A_IP'], '10.20.1.5')
                self.assertEqual(config['SERVER_B_IP'], '10.20.1.6')
                self.assertEqual(config['LAB_MODE'], 'two')
                self.assertEqual(len(config['JWT_SECRET']), 64)
                original = (here / '.env').read_bytes()
                with self.assertRaises(FileExistsError):
                    lab.main()
                self.assertEqual((here / '.env').read_bytes(), original)

    def test_compose_selects_local_ec2_role(self):
        with patch.object(lab.subprocess, 'run') as run:
            run.return_value.returncode = 0
            run.return_value.stdout = 'ready'
            lab.compose('config', '--quiet', role='b')
            command = run.call_args.args[0]
            self.assertIn(str(lab.HERE / 'compose.b.yml'), command)
            self.assertNotIn(str(lab.HERE / 'compose.a.yml'), command)

    def test_up_validates_before_starting_and_never_builds_or_pulls(self):
        with patch.object(lab, 'settings', return_value=self.config()), \
                patch.object(lab, 'compose', return_value='ready') as compose, \
                patch.object(lab, 'OUT'), patch('sys.argv', ['lab.py', 'up', '--role', 'b']), \
                contextlib.redirect_stdout(io.StringIO()):
            lab.main()
        calls = compose.call_args_list
        self.assertEqual(calls[0].args, ('config', '--quiet'))
        self.assertEqual(calls[0].kwargs, {'role': 'b'})
        self.assertEqual(calls[1].args,
                         ('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '600'))
        self.assertEqual(calls[1].kwargs, {'role': 'b'})

    def test_endpoint_overrides_are_shared_with_outage(self):
        with patch.dict(lab.os.environ, dict(LAB_A_URL='http://a:18080', LAB_B_URL='http://b:18080',
                                            LAB_PROXY_URL='http://proxy:18088')):
            self.assertEqual(lab.endpoints(self.config()),
                             ('http://a:18080', 'http://b:18080', 'http://proxy:18088'))

    def test_invalid_compose_does_not_start_services(self):
        with patch.object(lab, 'settings', return_value=self.config()), \
                patch.object(lab, 'compose', side_effect=RuntimeError('invalid config')) as compose, \
                patch.object(lab, 'OUT'), patch('sys.argv', ['lab.py', 'up', '--role', 'a']):
            with self.assertRaises(RuntimeError):
                lab.main()
        compose.assert_called_once_with('config', '--quiet', role='a')

    def test_outage_suppresses_auto_restart_and_restores_a_after_failure(self):
        seats = 0

        def request(base, path):
            nonlocal seats
            if path.endswith('/seats'):
                seats += 1
                return (200 if seats == 1 else 503), {}
            return 200, {}

        with patch.object(lab, 'settings', return_value=self.config()), \
                patch.object(lab, 'request', side_effect=request), \
                patch.object(lab, 'compose') as compose, patch.object(lab, 'OUT'), \
                patch.object(lab.time, 'sleep'):
            with self.assertRaises(AssertionError):
                lab.outage()
        self.assertEqual(compose.call_args_list[0].args, ('stop', '--timeout', '0', 'backend-a'))
        self.assertEqual(compose.call_args_list[-1].args, ('start', 'backend-a'))

    def test_config_output_does_not_expose_secrets(self):
        output = io.StringIO()
        with patch.object(lab, 'settings', return_value=self.config()), \
                patch.object(lab, 'OUT'), patch('sys.argv', ['lab.py', 'config']), \
                contextlib.redirect_stdout(output):
            lab.main()
        result = json.loads(output.getvalue())
        self.assertEqual(result['mode'], 'two')
        for secret in ('lab-password', 'redis-password', 'a' * 64):
            self.assertNotIn(secret, output.getvalue())


if __name__ == '__main__':
    unittest.main()
