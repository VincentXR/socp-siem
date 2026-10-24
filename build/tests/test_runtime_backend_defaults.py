"""Exercise the actual launcher policy without starting application processes."""
import os
from pathlib import Path
import subprocess
import unittest

TOOLCHAIN = Path(__file__).resolve().parents[1] / 'toolchain.sh'

class RuntimeBackendDefaultsTest(unittest.TestCase):
    def selection(self, profiles, overrides=None):
        env = os.environ.copy()
        env.pop('SOCP_RATELIMIT_BACKEND', None)
        env.pop('SOCP_RATELIMIT_FAIL_CLOSED', None)
        env.update(overrides or {})
        script = 'source "$1"; socp_ratelimit_defaults "$2"; printf "%s|%s" "$SOCP_RATELIMIT_BACKEND" "$SOCP_RATELIMIT_FAIL_CLOSED"'
        return subprocess.check_output(['bash', '-c', script, 'backend-policy', str(TOOLCHAIN), profiles], env=env, text=True)

    def test_pg_and_prod_require_shared_fail_closed_defaults(self):
        for profiles in ['dev,pg', 'prod', 'prod,pg', 'dev, pg']:
            with self.subTest(profiles=profiles):
                self.assertEqual('redis|true', self.selection(profiles))

    def test_local_h2_mode_keeps_its_process_local_default(self):
        self.assertEqual('memory|false', self.selection('dev'))

    def test_explicit_operator_choices_are_preserved_for_prod_guard(self):
        self.assertEqual('memory|false', self.selection('dev,pg', {'SOCP_RATELIMIT_BACKEND':'memory'}))
        self.assertEqual('redis|false', self.selection('prod', {'SOCP_RATELIMIT_FAIL_CLOSED':'false'}))
        self.assertEqual('redis|true', self.selection('dev', {'SOCP_RATELIMIT_BACKEND':'redis'}))
