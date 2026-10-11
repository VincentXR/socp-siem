"""Mutation coverage for the parsed SOAR request contract gate."""
import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("verify_soar", ROOT / "build/verify-soar.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

SOURCE = '''
class Controller {
    @PatchMapping("/playbooks/{id}")
    void update(@Valid @RequestBody(required = false) UpdatePlaybookRequest request) {}
    @PostMapping("/node-runs/{id}/resolve-unknown")
    void resolve(@Valid @RequestBody UnknownResolutionRequest request) {}
    @PostMapping("/approvals/{id}/decisions")
    void decide(@Valid @RequestBody ApprovalDecisionRequest request) {}
    @PostMapping("/manual-tasks/{id}/complete")
    void complete(@RequestBody PlaybookExecutionRequest request) {}
    @PatchMapping("/automation-rules/{id}")
    void patch(@RequestBody(required = false) Map<String, Object> request) {}
    @PostMapping("/automation-rules/evaluate")
    void evaluate(@RequestBody Map<String, Object> request) {}
    @PostMapping("/automation-rules/test")
    void test(@RequestBody Map<String, Object> request) {}
    @PostMapping("/events/evaluate")
    void event(@RequestBody Map<String, Object> request) {}
}
'''


class SoarRequestContractsTest(unittest.TestCase):
    def parse(self, source):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Controller.java"
            path.write_text(source, encoding="utf-8")
            return MODULE.request_body_contracts([path])

    def test_actual_controllers_satisfy_contracts(self):
        directory = ROOT / "services/soar-web/src/main/java/com/socp/soar/web/api/controller"
        contracts = MODULE.request_body_contracts(sorted(directory.glob("*Controller.java")))
        self.assertFalse(MODULE.request_body_violations(contracts))
        self.assertGreater(len(contracts), len(MODULE.TYPED_BODY_CONTRACTS))

    def test_formatting_annotation_order_and_qualified_names_do_not_change_contract(self):
        formatted = SOURCE.replace(
            '@Valid @RequestBody(required = false) UpdatePlaybookRequest',
            '@org.springframework.web.bind.annotation.RequestBody( required=false )\n'
            ' /* comment */ @jakarta.validation.Valid\n example.UpdatePlaybookRequest',
        ).replace('@PatchMapping("/playbooks/{id}")',
                  '@PatchMapping(path = {"/playbooks/{id}"})')
        self.assertEqual(self.parse(SOURCE), self.parse(formatted))

    def test_unmapped_compatibility_overloads_and_comments_do_not_satisfy_contract(self):
        missing = SOURCE.replace('@Valid @RequestBody ApprovalDecisionRequest',
                                 '@RequestBody ApprovalDecisionRequest')
        missing += '\n// @Valid @RequestBody ApprovalDecisionRequest\n'
        violations = MODULE.request_body_violations(self.parse(missing))
        self.assertEqual(len(violations), 1)
        self.assertIn('/approvals/{id}/decisions', violations[0])

    def test_same_map_count_at_wrong_route_is_rejected(self):
        moved = SOURCE.replace('/events/evaluate', '/runs/unsafe')
        violations = MODULE.request_body_violations(self.parse(moved))
        self.assertEqual(len(violations), 1)
        self.assertIn('/runs/unsafe', violations[0])

    def test_converting_an_extension_to_a_typed_body_does_not_require_a_map_quota(self):
        typed = SOURCE.replace('void event(@RequestBody Map<String, Object>',
                               'void event(@Valid @RequestBody EventRequest')
        self.assertFalse(MODULE.request_body_violations(self.parse(typed)))

    def test_missing_body_wrong_type_and_required_option_are_rejected(self):
        contracts = self.parse(SOURCE)
        route = ('PATCH', '/playbooks/{id}')
        for incorrect in (None, ('Map', True, False), ('UpdatePlaybookRequest', True, True)):
            with self.subTest(incorrect=incorrect):
                mutated = contracts.copy()
                if incorrect is None:
                    mutated.pop(route)
                else:
                    mutated[route] = incorrect
                self.assertTrue(MODULE.request_body_violations(mutated))

    def test_duplicate_routes_and_unparseable_source_fail_closed(self):
        duplicate = SOURCE.replace(
            '/events/evaluate', '/automation-rules/evaluate')
        for source in (duplicate, SOURCE + '\nclass Broken {'):
            with self.subTest(source=source[-60:]), self.assertRaises(ValueError):
                self.parse(source)

    def test_nonliteral_mapping_fails_closed(self):
        with self.assertRaises(ValueError):
            self.parse(SOURCE.replace('"/events/evaluate"', 'DYNAMIC_ROUTE'))


if __name__ == '__main__':
    unittest.main()
