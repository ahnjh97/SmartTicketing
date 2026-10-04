"""Verify secret transport without SSH or production credentials."""
import os
from pathlib import Path
import shutil
import subprocess
import unittest


class AdminSecretTests(unittest.TestCase):
    def render(self, password):
        shell = os.environ.get('TEST_SHELL') or shutil.which('bash')
        result = subprocess.run(
            [shell, str(Path(__file__).with_name('admin-secret.sh'))],
            env=dict(os.environ, ADMIN_INITIAL_PASSWORD=password),
            capture_output=True, text=True, timeout=10)
        return shell, result

    def test_special_characters_are_literal_and_not_executed(self):
        password = "ab'\" $HOME $(exit 42) `exit 43` \\ end\nline"
        shell, result = self.render(password)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, '')
        recovered = subprocess.run(
            [shell, '-s'], input=result.stdout + 'printf "%s" "$ADMIN_INITIAL_PASSWORD"\n',
            capture_output=True, text=True, timeout=10)
        self.assertEqual(recovered.returncode, 0)
        self.assertEqual(recovered.stdout, password)

    def test_missing_invalid_and_overlong_secrets_fail_without_output(self):
        for password in ('', '123', '    ', 'x' * 73):
            with self.subTest(length=len(password)):
                _, result = self.render(password)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, '')
                if password:
                    self.assertNotIn(password, result.stderr)

    def test_four_character_password_is_supported(self):
        _, result = self.render('0000')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, '')
