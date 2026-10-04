"""Exercise reset ordering and recovery without accessing a real deployment."""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TheaterResetTests(unittest.TestCase):
    def run_reset(self, failure='', confirmation='RESET_THEATERS_KEEP_USERS'):
        shell = os.environ.get('TEST_SHELL') or shutil.which('bash')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            binaries = root / 'bin'
            binaries.mkdir()
            (binaries / 'flock').write_text('#!/bin/sh\nexit 0\n')
            if os.name == 'nt':
                # Git Bash cannot apply POSIX directory modes to sandbox-owned NTFS paths.
                (binaries / 'install').write_text('#!/bin/sh\nmkdir -p "$4"\n')
            (binaries / 'docker').write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$MOCK_ROOT/commands"
case "$1" in
  inspect)
    case "$3" in
      *Running*) if [ "$4" = smartticketing-backend ] && [ -f "$MOCK_ROOT/stopped" ]; then echo false; else echo true; fi ;;
      *Health*) echo healthy ;;
      *) echo running ;;
    esac ;;
  stop) touch "$MOCK_ROOT/stopped" ;;
  start) rm -f "$MOCK_ROOT/stopped" ;;
  exec)
    case "$*" in
      *mysqldump*) [ "$FAILURE" != backup ] || exit 7; echo 'test backup' ;;
      *) cat > "$MOCK_ROOT/executed.sql"; [ "$FAILURE" != sql ] || exit 8 ;;
    esac ;;
esac
''', newline='\n')
            for path in binaries.iterdir():
                path.chmod(0o755)
            env = dict(os.environ, HOME=root.as_posix(), MOCK_ROOT=root.as_posix(),
                       MOCK_BIN=binaries.as_posix(), FAILURE=failure,
                       cleanup_sql_base64=base64.b64encode(b'SELECT 1;\n').decode())
            script = 'export PATH="$(cd "$MOCK_BIN" && pwd):$PATH"\n'
            script += Path(__file__).with_name('reset-theaters.sh').read_text()
            result = subprocess.run([shell, '-s', '--', confirmation], input=script,
                                    text=True, encoding='utf-8', capture_output=True, env=env, timeout=15)
            return result, (root / 'commands').read_text() if (root / 'commands').exists() else '', (root / 'executed.sql').exists()

    def test_invalid_confirmation_does_not_touch_server(self):
        result, commands, executed = self.run_reset(confirmation='wrong')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(commands, '')
        self.assertFalse(executed)

    def test_account_reset_requires_its_distinct_confirmation_and_uses_backup(self):
        result, commands, executed = self.run_reset(confirmation='RESET_THEATERS_AND_USERS')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(executed)
        self.assertIn('ALL account data', result.stdout)
        self.assertIn('before-theater-and-users-reset-', result.stdout)
        self.assertLess(commands.index('mysqldump'), commands.index('exec -i'))

    def test_backup_failure_restarts_backend_without_deleting(self):
        result, commands, executed = self.run_reset(failure='backup')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(executed)
        self.assertIn('start smartticketing-backend', commands)

    def test_sql_failure_restarts_backend_and_reports_failure(self):
        result, commands, executed = self.run_reset(failure='sql')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(executed)
        self.assertIn('start smartticketing-backend', commands)
        self.assertNotIn('restart smartticketing-nginx', commands)

    def test_success_stops_writers_backs_up_deletes_and_restarts(self):
        result, commands, executed = self.run_reset()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(executed)
        self.assertLess(commands.index('stop --time'), commands.index('mysqldump'))
        self.assertLess(commands.index('mysqldump'), commands.index('exec -i'))
        self.assertLess(commands.index('exec -i'), commands.index('start smartticketing-backend'))
        self.assertIn('restart smartticketing-nginx', commands)
        self.assertNotIn('compose up', commands)
