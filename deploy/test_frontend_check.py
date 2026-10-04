"""Regression tests for startup resets and persistent frontend failures."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class FrontendCheckTests(unittest.TestCase):
    def run_check(self, scenario):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            scripts = {
                'docker': '''#!/bin/sh
echo "$*" >> "$CHECK_LOG"
if [ "$1" = inspect ]; then
  if [ "$SCENARIO" = exited ]; then echo false; else echo true; fi
fi
''',
                'sleep': '#!/bin/sh\nexit 0\n',
                'curl': '''#!/bin/sh
if [ "$SCENARIO" = timeout ]; then exit 56; fi
if [ "$SCENARIO" = reset ] && [ ! -f "$RESET_MARKER" ]; then
  touch "$RESET_MARKER"
  exit 56
fi
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) output=$2; shift ;;
    http*) url=$1 ;;
  esac
  shift
done
case "$url" in
  */runtime-config.js)
    if [ "$SCENARIO" = wrong ]; then
      printf '%s' '{}' > "$output"
    else
      printf '%s' 'window.__SMART_TICKETING_CONFIG__ = {"kakaoMapJsKey":"ciRuntimeKey"};' > "$output"
    fi ;;
  *) printf '%s' '<script src="/runtime-config.js"></script>' > "$output" ;;
esac
''',
            }
            for name, contents in scripts.items():
                path = root / name
                path.write_text(contents, encoding='utf-8', newline='\n')
                path.chmod(0o755)
            env = dict(os.environ, MOCK_BIN=root.as_posix(), SCENARIO=scenario,
                       CHECK_LOG=(root / 'commands.log').as_posix(),
                       RESET_MARKER=(root / 'reset.marker').as_posix(), FRONTEND_IMAGE='test-image')
            shell = os.environ.get('TEST_SHELL') or shutil.which('bash')
            code = 'export PATH="$(cd "$MOCK_BIN" && pwd):$PATH"\n'
            code += Path(__file__).with_name('check-frontend-container.sh').read_text(encoding='utf-8')
            result = subprocess.run([shell, '-s'], input=code, text=True, capture_output=True,
                                    env=env, timeout=15)
            return result, (root / 'commands.log').read_text()

    def test_connection_reset_is_retried_and_then_verified(self):
        result, log = self.run_check('reset')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Waiting for frontend HTTP readiness (1/30)', result.stdout)
        self.assertIn('runtime configuration verified', result.stdout)
        self.assertIn('rm -f frontend-check', log)

    def test_persistent_resets_fail_after_bounded_wait(self):
        result, log = self.run_check('timeout')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('readiness timed out', result.stderr)
        self.assertEqual(result.stdout.count('Waiting for frontend'), 30)
        self.assertIn('rm -f frontend-check', log)

    def test_exited_container_fails_immediately(self):
        result, log = self.run_check('exited')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('exited before becoming ready', result.stderr)
        self.assertNotIn('Waiting for frontend', result.stdout)
        self.assertIn('rm -f frontend-check', log)

    def test_wrong_runtime_configuration_still_fails(self):
        result, log = self.run_check('wrong')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('rm -f frontend-check', log)
