"""Check the production-image startup gate without Docker or production access."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class BackendCheckTests(unittest.TestCase):
    def run_check(self, scenario):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            scripts = {
                'docker': '''#!/bin/sh
echo "$*" >> "$CHECK_LOG"
case "$1" in
  inspect) if [ "$SCENARIO" = exited ]; then echo false; else echo true; fi ;;
  exec)
    case "$*" in
      *information_schema.tables*) if [ "$SCENARIO" = schema ]; then echo 2; else echo 3; fi ;;
      *) if [ "$SCENARIO" = mysql ]; then exit 1; fi ;;
    esac ;;
esac
''',
                'sleep': '#!/bin/sh\nexit 0\n',
                'curl': '''#!/bin/sh
if [ "$SCENARIO" = timeout ]; then exit 7; fi
if [ "$SCENARIO" = catalog ]; then
  case "$*" in */api/movies*) exit 22 ;; esac
fi
if [ "$SCENARIO" = reset ] && [ ! -f "$RESET_MARKER" ]; then
  touch "$RESET_MARKER"; exit 56
fi
''',
            }
            for name, content in scripts.items():
                path = root / name
                path.write_text(content, encoding='utf-8', newline='\n')
                path.chmod(0o755)
            env = dict(os.environ, MOCK_BIN=root.as_posix(), SCENARIO=scenario,
                       CHECK_LOG=(root / 'commands.log').as_posix(),
                       RESET_MARKER=(root / 'reset.marker').as_posix(), BACKEND_IMAGE='tested-image',
                       GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1')
            shell = os.environ.get('TEST_SHELL') or shutil.which('bash')
            code = 'export PATH="$(cd "$MOCK_BIN" && pwd):$PATH"\n'
            code += Path(__file__).with_name('check-backend-container.sh').read_text(encoding='utf-8')
            result = subprocess.run([shell, '-s'], input=code, text=True, capture_output=True, env=env, timeout=20)
            return result, (root / 'commands.log').read_text()

    def test_success_uses_tested_image_and_always_removes_only_test_resources(self):
        result, log = self.run_check('reset')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('schema verified', result.stdout)
        self.assertIn('tested-image', log)
        self.assertIn('rm -f -v backend-check-123-1-app backend-check-123-1-redis backend-check-123-1-mysql', log)
        self.assertIn('network rm backend-check-123-1-network', log)
        self.assertNotIn('compose', log)

    def test_unhealthy_database_application_catalog_or_schema_fails_gate_and_cleans_up(self):
        for scenario in ('mysql', 'exited', 'timeout', 'catalog', 'schema'):
            with self.subTest(scenario=scenario):
                result, log = self.run_check(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('rm -f -v backend-check-123-1-app', log)
                self.assertIn('network rm backend-check-123-1-network', log)
                self.assertNotIn('schema verified', result.stdout)
