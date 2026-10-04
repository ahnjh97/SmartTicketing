"""Exercise OAuth profile selection without starting Java or contacting a provider."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class EntrypointTests(unittest.TestCase):
    def run_entrypoint(self, **settings):
        shell = os.environ.get('TEST_SHELL') or shutil.which('sh')
        self.assertIsNotNone(shell, 'A POSIX shell is required')
        with tempfile.TemporaryDirectory() as temp:
            java = Path(temp) / 'java'
            java.write_text('#!/bin/sh\nprintf "%s\\n" "${SPRING_PROFILES_ACTIVE:-}"\n', encoding='utf-8', newline='\n')
            java.chmod(0o755)
            env = dict(os.environ)
            for key in list(env):
                if key.startswith(('GOOGLE_', 'NAVER_', 'KAKAO_')) or key == 'SPRING_PROFILES_ACTIVE':
                    env.pop(key)
            env.update(settings)
            env['PATH'] = temp + os.pathsep + env['PATH']
            return subprocess.run([shell, str(Path(__file__).with_name('backend-entrypoint.sh').resolve())],
                                  env=env, text=True, capture_output=True, timeout=10)

    def test_no_social_credentials_starts_without_oauth_profiles(self):
        result = self.run_entrypoint()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '')

    def test_only_configured_providers_are_enabled(self):
        result = self.run_entrypoint(GOOGLE_CLIENT_ID='test', GOOGLE_CLIENT_SECRET='test',
                                     KAKAO_CLIENT_ID='test', KAKAO_CLIENT_SECRET='test')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'oauth-google,oauth-kakao')

    def test_partial_credentials_fail_without_disclosing_value(self):
        result = self.run_entrypoint(NAVER_CLIENT_ID='do-not-print-this')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('naver needs both', result.stderr)
        self.assertNotIn('do-not-print-this', result.stdout + result.stderr)

    def test_explicit_profile_selection_is_preserved(self):
        result = self.run_entrypoint(SPRING_PROFILES_ACTIVE='test')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'test')
