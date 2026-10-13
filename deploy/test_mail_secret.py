"""Verify SMTP credential transport with synthetic values only."""
import os
from pathlib import Path
import shutil
import subprocess
import unittest


class MailSecretTests(unittest.TestCase):
    def render(self, overrides=None):
        shell = os.environ.get('TEST_SHELL') or shutil.which('bash')
        env = dict(os.environ, MAIL_HOST='smtp.example.test', MAIL_USERNAME='demo',
                   MAIL_PASSWORD='test-password', MAIL_FROM='demo@example.test', MAIL_PORT='587')
        env.update(overrides or {})
        result = subprocess.run([shell, str(Path(__file__).with_name('mail-secret.sh'))],
                                env=env, capture_output=True, text=True, timeout=10)
        return shell, result

    def test_password_special_characters_remain_literal(self):
        password = "test_'\" $HOME $(exit 42) `exit 43` \\ end"
        shell, result = self.render({'MAIL_PASSWORD': password})
        self.assertEqual(result.returncode, 0, result.stderr)
        recovered = subprocess.run([shell, '-s'], input=result.stdout + 'printenv MAIL_PASSWORD\n',
                                   capture_output=True, text=True, timeout=10)
        self.assertEqual(recovered.returncode, 0)
        self.assertEqual(recovered.stdout, password + '\n')

    def test_invalid_configuration_never_outputs_any_credentials(self):
        for name in ('MAIL_HOST', 'MAIL_USERNAME', 'MAIL_PASSWORD', 'MAIL_FROM'):
            for value in ('', '   ', 'bad\nvalue', 'bad\rvalue'):
                with self.subTest(name=name, value=value):
                    _, result = self.render({name: value})
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(result.stdout, '')
        for value in ('0', '65536', '587; exit 0', '-1'):
            _, result = self.render({'MAIL_PORT': value})
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, '')
