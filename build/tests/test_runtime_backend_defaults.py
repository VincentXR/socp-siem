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


class LocalMemoryDefaultsTest(unittest.TestCase):
    KEYS = ('SOCP_SEARCH_CACHE_MAX_BYTES_TOTAL',
            'SOCP_SEARCH_CACHE_MAX_BYTES_PER_TENANT',
            'SOCP_SEARCH_CACHE_MAX_CONCURRENT_WARMUPS')

    def selection(self, profiles, overrides=None):
        env = os.environ.copy()
        for key in self.KEYS:
            env.pop(key, None)
        env.update(overrides or {})
        script = ('source "$1"; socp_local_memory_defaults "$2"; '
                  'printf "%s|%s|%s" "${SOCP_SEARCH_CACHE_MAX_BYTES_TOTAL-unset}" '
                  '"${SOCP_SEARCH_CACHE_MAX_BYTES_PER_TENANT-unset}" '
                  '"${SOCP_SEARCH_CACHE_MAX_CONCURRENT_WARMUPS-unset}"')
        return subprocess.check_output(
            ['bash', '-c', script, 'memory-policy', str(TOOLCHAIN), profiles],
            env=env, text=True)

    def test_only_explicit_development_profiles_receive_trial_budgets(self):
        for profiles in ('dev', 'dev,pg', 'dev, pg'):
            with self.subTest(profiles=profiles):
                self.assertEqual('67108864|8388608|1', self.selection(profiles))
        for profiles in ('prod', 'prod,pg', 'dev,prod', 'pg', ''):
            with self.subTest(profiles=profiles):
                self.assertEqual('unset|unset|unset', self.selection(profiles))

    def test_operator_budgets_are_preserved_in_every_profile(self):
        overrides = dict(zip(self.KEYS, ('33554432', '4194304', '3')))
        for profiles in ('dev,pg', 'prod'):
            with self.subTest(profiles=profiles):
                self.assertEqual('33554432|4194304|3', self.selection(profiles, overrides))

    def test_partial_override_retains_other_development_defaults(self):
        self.assertEqual('33554432|8388608|1', self.selection(
            'dev,pg', {self.KEYS[0]: '33554432'}))
