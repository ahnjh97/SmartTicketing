"""Verify backend payment secret transport using synthetic credentials only."""
import os
from pathlib import Path
import shutil
import subprocess
import unittest


class TossSecretTests(unittest.TestCase):
    def render(self, key):
        shell = os.environ.get('TEST_SHELL') or shutil.which('bash')
        env = dict(os.environ)
        env.pop('TOSS_SECRET_KEY', None)
        if key is not None:
            env['TOSS_SECRET_KEY'] = key
        result = subprocess.run(
            [shell, str(Path(__file__).with_name('toss-secret.sh'))],
            env=env, capture_output=True, text=True, timeout=10)
        return shell, result

    def test_special_characters_survive_transport_without_execution(self):
        key = "test_'\" $HOME $(exit 42) `exit 43` \\ end\nline"
        shell, result = self.render(key)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, '')
        recovered = subprocess.run(
            [shell, '-s'], input=result.stdout + 'printenv TOSS_SECRET_KEY\n',
            capture_output=True, text=True, timeout=10)
        self.assertEqual(recovered.returncode, 0)
        self.assertEqual(recovered.stdout, key + '\n')

    def test_missing_or_blank_key_fails_without_secret_output(self):
        for key in (None, '', '   ', '\t\n'):
            with self.subTest(key=key):
                _, result = self.render(key)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, '')
                self.assertEqual(result.stderr,
                                 'Set TOSS_SECRET_KEY in GitHub Actions Secrets.\n')
