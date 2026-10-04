"""Validate runtime configuration output without Docker or real credentials."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class FrontendRuntimeTests(unittest.TestCase):
    def run_config(self, key):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'runtime-config.js'
            env = dict(os.environ, VITE_KAKAO_MAP_JS_KEY=key, RUNTIME_CONFIG_PATH=output.as_posix(),
                       DB_PASSWORD='must-not-appear', KAKAO_MAP_REST_API_KEY='server-only')
            shell = os.environ.get('TEST_SHELL') or shutil.which('sh')
            script = Path(__file__).resolve().parents[1] / 'frontend/docker/40-runtime-config.sh'
            result = subprocess.run([shell, str(script)], env=env, capture_output=True, text=True, timeout=10)
            return result, output.read_text() if output.exists() else ''

    def test_only_browser_key_is_written(self):
        result, output = self.run_config('testBrowserKey123')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, 'window.__SMART_TICKETING_CONFIG__ = {"kakaoMapJsKey":"testBrowserKey123"};\n')
        self.assertNotIn('testBrowserKey123', result.stdout + result.stderr)

    def test_missing_key_blocks_startup(self):
        result, output = self.run_config('')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, '')

    def test_script_injection_is_rejected(self):
        result, output = self.run_config('\"};alert(1);//')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output, '')
