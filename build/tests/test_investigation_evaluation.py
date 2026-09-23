import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


def script(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / 'build' / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


EVAL = script('eval-investigation')
DATASET = script('verify-investigation-dataset')


class InvestigationEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.case = {
            'id': 'case-a', 'alertId': 'alert-a',
            'alert': {'occurredAt': '2026-01-01T00:00:00Z', 'message': 'alarm'},
            'evidence': [{'eventId': 'event-a', 'timestamp': '2026-01-01T00:00:00Z',
                          'raw': 'captured fact', 'src_ip': '192.0.2.1'}],
            'expected': {'requiredCitationPrefixes': ['alert:', 'evidence:', 'search:'],
                         'requiredTimelineTypes': ['ALERT', 'EVIDENCE'], 'requiresHumanApproval': True},
        }
        self.result = {
            'alertId': 'alert-a',
            'relatedEvents': [{'eventId': 'event-a', 'timestamp': '2026-01-01T00:00:00Z', 'msg': 'captured fact'}],
            'iocMatches': {'192.0.2.1': {'matched': True}},
            'citations': [{'id': value} for value in ['alert:alert-a', 'evidence:event-a', 'search:event-a', 'ioc:192.0.2.1']],
            'timeline': [
                {'type': 'ALERT', 'timestamp': '2026-01-01T00:00:00Z', 'message': 'alarm', 'citation': 'alert:alert-a'},
                {'type': 'EVIDENCE', 'timestamp': '2026-01-01T00:00:00Z', 'message': 'captured fact', 'citation': 'evidence:event-a'},
            ],
            'nextActions': [{'type': 'SOAR_SUGGESTION', 'status': 'REQUIRES_HUMAN_APPROVAL', 'executable': False}],
        }

    def evaluate(self, result=None):
        return EVAL.evaluate({'cases': [self.case]}, {'case-a': self.result if result is None else result})

    def test_grounded_fixture_passes(self):
        self.assertEqual([], self.evaluate())

    def test_output_cannot_authorize_its_own_search_citation(self):
        self.result['relatedEvents'].append({'eventId': 'fabricated', 'timestamp': 'now', 'msg': 'invented'})
        self.result['citations'].append({'id': 'search:fabricated'})
        failures = self.evaluate()
        self.assertTrue(any('unsupported related event' in value for value in failures))
        self.assertTrue(any('unsupported citations' in value for value in failures))

    def test_output_cannot_authorize_its_own_ioc(self):
        self.result['iocMatches']['invented.example'] = {'matched': True}
        self.result['citations'].append({'id': 'ioc:invented.example'})
        self.assertTrue(any('unsupported IOC' in value for value in self.evaluate()))
        self.assertTrue(any('unsupported citations' in value for value in self.evaluate()))

    def test_known_event_identity_cannot_hide_fabricated_content(self):
        self.result['relatedEvents'][0]['msg'] = 'invented conclusion'
        self.assertTrue(self.evaluate())

    def test_timeline_requires_supported_identity_and_fact(self):
        for key, value in [('citation', 'evidence:invented'), ('type', 'ALERT'),
                           ('timestamp', '2099-01-01T00:00:00Z'), ('message', 'invented')]:
            with self.subTest(key=key):
                result = copy.deepcopy(self.result)
                result['timeline'][1][key] = value
                self.assertTrue(any('unsupported timeline' in failure for failure in self.evaluate(result)))

    def test_timeline_cannot_reference_an_undeclared_citation(self):
        self.result['citations'] = [item for item in self.result['citations'] if item['id'] != 'evidence:event-a']
        self.assertTrue(any('unsupported timeline' in value for value in self.evaluate()))

    def test_one_safe_action_cannot_mask_an_unsafe_suggestion(self):
        self.result['nextActions'].append({'type': 'SOAR_SUGGESTION', 'status': 'READY', 'executable': True})
        self.assertTrue(any('bypasses human approval' in value for value in self.evaluate()))

    def test_automatic_and_ambiguous_action_flags_fail(self):
        for flag in [{'executed': True}, {'executed': 'false'}, {'executable': 1}, {'mode': 'auto'}]:
            with self.subTest(flag=flag):
                result = copy.deepcopy(self.result)
                result['nextActions'].append({'type': 'CUSTOM_ACTION', **flag})
                self.assertTrue(any('automatic action' in failure for failure in self.evaluate(result)))

    def test_malformed_collections_fail_without_crashing(self):
        for key in ('citations', 'timeline', 'relatedEvents', 'nextActions', 'iocMatches'):
            for value in (None, 'wrong-shape', [None]):
                with self.subTest(key=key, value=value):
                    result = copy.deepcopy(self.result)
                    result[key] = value
                    self.assertTrue(self.evaluate(result))

    def test_duplicate_or_empty_citation_ids_fail(self):
        for item in ({'id': 'alert:alert-a'}, {'id': None}, {'id': []}):
            result = copy.deepcopy(self.result)
            result['citations'].append(item)
            self.assertTrue(self.evaluate(result))

    def test_missing_or_extra_case_is_not_ignored(self):
        self.assertTrue(EVAL.evaluate({'cases': [self.case]}, {}))
        self.assertTrue(EVAL.evaluate({'cases': [self.case]}, {'case-a': self.result, 'extra': self.result}))

    def test_empty_or_duplicate_dataset_cannot_report_a_pass(self):
        for cases in ([], [self.case, self.case]):
            with self.assertRaises(ValueError):
                EVAL.evaluate({'cases': cases}, {'case-a': self.result})

    def test_result_loader_rejects_duplicates_and_malformed_shapes(self):
        invalid = ['null', '{}', '[null]', '{"cases":{"x":{},"x":{}}}',
                   '{"results":[{"caseId":"x","result":{}},{"caseId":"x","result":{}}]}',
                   '{"cases":{"x":[]}}']
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'results.json'
            for value in invalid:
                with self.subTest(value=value):
                    path.write_text(value, encoding='utf-8')
                    with self.assertRaises(ValueError):
                        EVAL.load_results(path)

    def test_result_loader_accepts_both_documented_shapes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'results.json'
            for value in ({'cases': {'case-a': self.result}}, {'results': [{'caseId': 'case-a', 'result': self.result}]}):
                path.write_text(json.dumps(value), encoding='utf-8')
                self.assertEqual({'case-a': self.result}, EVAL.load_results(path))

    def test_checked_in_dataset_is_valid(self):
        DATASET.validate(json.loads(DATASET.DATASET.read_text(encoding='utf-8')))

    def test_dataset_validation_still_rejects_invalid_seed_under_python_optimization(self):
        payload = json.loads(DATASET.DATASET.read_text(encoding='utf-8'))
        payload['seed'] = 'not-an-integer'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'dataset.json'
            path.write_text(json.dumps(payload), encoding='utf-8')
            run = subprocess.run([sys.executable, '-O', str(ROOT / 'build/verify-investigation-dataset.py'),
                                  '--dataset', str(path)], text=True, capture_output=True, timeout=10)
            self.assertEqual(1, run.returncode)
            self.assertIn('[FAIL]', run.stdout)
            self.assertNotIn('[PASS]', run.stdout)

    def test_dataset_rejects_duplicate_evidence_and_boolean_limit(self):
        payload = json.loads(DATASET.DATASET.read_text(encoding='utf-8'))
        payload['cases'][0]['evidence'].append(payload['cases'][0]['evidence'][0])
        with self.assertRaisesRegex(ValueError, 'duplicate evidence'):
            DATASET.validate(payload)
        payload = json.loads(DATASET.DATASET.read_text(encoding='utf-8'))
        payload['limits']['maxToolCalls'] = True
        with self.assertRaisesRegex(ValueError, 'maxToolCalls'):
            DATASET.validate(payload)


if __name__ == '__main__':
    unittest.main()
