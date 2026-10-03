"""Behavioral guards for refactored test helpers, not implementation-string matching."""
import importlib.util
from pathlib import Path
import sys
import unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
def load(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/(name+'.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
class CleanupContracts(unittest.TestCase):
    def test_unbounded_java_handler_recognizer(self):
        check=load('verify-contracts').has_unbounded_body_handler
        for source in ['BodyHandlers.ofString()', 'HttpResponse . BodyHandlers . ofString (UTF_8)']:
            self.assertTrue(check(source),source)
        for source in ['BoundedBodyHandlers.ofString(100)', 'CustomBodyHandlers.ofString()']:
            self.assertFalse(check(source),source)
    def test_importing_mutating_cli_does_not_issue_requests(self):
        from unittest.mock import patch
        with patch('urllib.request.urlopen',side_effect=AssertionError('import issued a request')):
            load('verify-full');load('verify-slice')
    def test_shared_webhook_fixture_is_bounded_and_owned(self):
        from webhook_sink import webhook_fixture
        import urllib.request
        with webhook_fixture() as url:
            with urllib.request.urlopen(urllib.request.Request(url,data=b'{}',headers={'Content-Type':'application/json'})) as response:
                self.assertEqual(204,response.status)
        with webhook_fixture('http://external-fixture.invalid') as url:
            self.assertEqual('http://external-fixture.invalid',url)

    def test_extracted_chaos_context_keeps_imported_dependencies_injectable(self):
        from unittest.mock import patch
        runner=load('chaos-pipeline')
        runner.GATEWAY_URL='http://disposable.invalid'
        with patch.object(runner.recovery,'scenario_detection_restart',lambda ctx,*args:ctx.GATEWAY_URL):
            self.assertEqual('http://disposable.invalid',runner.scenario_detection_restart('token',1))
        with patch.object(runner.recovery,'scenario_opensearch_outage',lambda ctx,*args:(ctx.GATEWAY_URL,ctx.health_url)):
            self.assertEqual(('http://disposable.invalid',runner.health_url),runner.scenario_opensearch_outage('token'))
        with patch.object(runner.migration,'scenario_routed_migration',lambda ctx,*args:(ctx.GATEWAY_URL,ctx.login_token)):
            self.assertEqual(('http://disposable.invalid',runner.login_token),runner.scenario_routed_migration('token',1))

    def test_extracted_oracles_have_no_undefined_global_dependencies(self):
        import symtable
        import builtins
        for path in (ROOT/'chaos_scenarios').glob('*.py'):
            table=symtable.symtable(path.read_text(),str(path),'exec')
            bound=set(table.get_identifiers())|set(dir(builtins))
            def check(scope):
                for symbol in scope.get_symbols():
                    if symbol.is_referenced() and symbol.is_global():
                        self.assertIn(symbol.get_name(),bound,f'{path.name}: {symbol.get_name()}')
                for child in scope.get_children():check(child)
            for scope in table.get_children():check(scope)
