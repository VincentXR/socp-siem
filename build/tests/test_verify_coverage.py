import contextlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / 'build' / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


AGGREGATE = load_script('verify-coverage')
CHANGED = load_script('verify-changed-coverage')


class CoverageGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = io.StringIO()

    def report(self, module='services/example', counters=True, lines=True):
        source = self.root / module / 'src/main/java/example/Example.java'
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text('package example; public class Example {}\n', encoding='utf-8')
        # Deterministic old source timestamp; report is newer unless a test changes it.
        os.utime(source, (1, 1))
        report = self.root / module / 'target/site/jacoco/jacoco.xml'
        report.parent.mkdir(parents=True, exist_ok=True)
        line = '<line nr="1" mi="0" ci="1"/>' if lines else ''
        counter = '<counter type="LINE" missed="0" covered="1"/>' if counters else ''
        report.write_text(f'<report><package name="example"><sourcefile name="Example.java">'
                          f'{line}</sourcefile></package>{counter}</report>', encoding='utf-8')
        return source, report

    def run_changed(self, changed):
        with patch.object(CHANGED, 'ROOT', self.root), patch.object(CHANGED, 'diff_lines', return_value=changed), \
                patch('sys.argv', ['verify-changed-coverage.py']), patch.dict(os.environ, {}, clear=True), \
                contextlib.redirect_stdout(self.output), contextlib.redirect_stderr(self.output):
            return CHANGED.main()

    def run_aggregate(self, env=None):
        with patch.object(AGGREGATE, 'ROOT', self.root), patch('sys.argv', ['verify-coverage.py']), \
                patch.dict(os.environ, env or {}, clear=True), \
                contextlib.redirect_stdout(self.output), contextlib.redirect_stderr(self.output):
            return AGGREGATE.main()

    def complete_reports(self):
        return [self.report(f'services/module-{i}') for i in range(8)]

    def test_changed_file_without_any_report_fails(self):
        self.assertEqual(1, self.run_changed({'services/example/src/main/java/example/Example.java': {1}}))

    def test_changed_file_missing_from_existing_report_fails(self):
        self.report()
        self.assertEqual(1, self.run_changed({'services/example/src/main/java/example/Other.java': {1}}))

    def test_changed_file_with_stale_report_fails(self):
        source, report = self.report()
        os.utime(source, ns=(report.stat().st_mtime_ns + 1_000_000_000,) * 2)
        self.assertEqual(1, self.run_changed({source.relative_to(self.root).as_posix(): {1}}))

    def test_changed_non_executable_source_is_allowed_when_present_in_report(self):
        source, _ = self.report(lines=False)
        self.assertEqual(0, self.run_changed({source.relative_to(self.root).as_posix(): {1}}))

    def test_changed_metadata_or_deletion_needs_no_executable_report(self):
        self.assertEqual(0, self.run_changed({
            'services/example/src/main/java/example/package-info.java': {1},
            'services/example/src/main/java/module-info.java': {1},
            'services/example/src/main/java/example/Example.java': set(),
        }))

    def test_changed_covered_lines_pass(self):
        source, _ = self.report()
        self.assertEqual(0, self.run_changed({source.relative_to(self.root).as_posix(): {1}}))

    def test_missing_module_line_counter_is_an_error(self):
        self.complete_reports()
        self.report('services/ninth', counters=False)
        self.assertEqual(1, self.run_aggregate())

    def test_stale_module_report_is_an_error(self):
        reports = self.complete_reports()
        source, report = reports[0]
        os.utime(source, ns=(report.stat().st_mtime_ns + 1_000_000_000,) * 2)
        self.assertEqual(1, self.run_aggregate())

    def test_zero_module_counter_is_an_error(self):
        self.complete_reports()
        _, report = self.report('services/ninth')
        report.write_text('<report><counter type="LINE" missed="0" covered="0"/></report>', encoding='utf-8')
        self.assertEqual(1, self.run_aggregate())

    def test_module_specific_threshold_rejects_nan_negative_and_invalid_text(self):
        self.complete_reports()
        for value in ('nan', '-0.1', '1.1', 'invalid'):
            with self.subTest(value=value), self.assertRaises(SystemExit) as failure:
                self.run_aggregate({'SOCP_MIN_SERVICES_MODULE_0_LINE_COVERAGE': value})
            self.assertEqual(2, failure.exception.code)

    def test_valid_complete_module_reports_pass(self):
        self.complete_reports()
        self.assertEqual(0, self.run_aggregate())


if __name__ == '__main__':
    unittest.main()
