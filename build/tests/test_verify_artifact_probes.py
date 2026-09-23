import contextlib
import importlib.util
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'build'))


def load_script(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / 'build' / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


JARS = load_script('verify-jars')
ACTUATOR = load_script('verify-actuator-auth')


class ArtifactProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = io.StringIO()

    def jar(self, name='example', main=True, launcher=True, start=True, dependencies=True):
        module = self.root / 'services' / name
        (module / 'target').mkdir(parents=True)
        (module / 'pom.xml').write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            f'<parent><version>2.0.0</version></parent><artifactId>{name}</artifactId></project>', encoding='utf-8')
        path = module / 'target' / f'{name}-2.0.0.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            manifest = 'Manifest-Version: 1.0\r\n'
            if main:
                manifest += 'Main-Class: example.Launcher\r\nStart-Class: example.Application\r\n'
            archive.writestr('META-INF/MANIFEST.MF', manifest + '\r\n')
            if launcher:
                archive.writestr('example/Launcher.class', b'class fixture')
            if start:
                archive.writestr('BOOT-INF/classes/example/Application.class', b'class fixture')
            if dependencies:
                archive.writestr('BOOT-INF/lib/dependency.jar', b'jar fixture')
        return path

    def run_jars(self, modules):
        with patch.object(JARS, 'ROOT', self.root), \
                patch.object(JARS, 'current_registry', return_value=(modules, modules)), \
                patch.object(JARS, 'validate_registry', return_value=[]), \
                contextlib.redirect_stdout(self.output), contextlib.redirect_stderr(self.output):
            return JARS.main()

    def test_empty_registry_is_not_success(self):
        self.assertEqual(1, self.run_jars([]))

    def test_one_present_jar_does_not_hide_another_missing_module(self):
        self.jar()
        self.assertEqual(1, self.run_jars(['example', 'missing']))

    def test_manifest_text_alone_does_not_prove_boot_archive_structure(self):
        for field in ('main', 'launcher', 'start', 'dependencies'):
            with self.subTest(field=field):
                self.jar(name=field, **{field: False})
                self.assertEqual(1, self.run_jars([field]))

    def test_corrupt_jar_returns_a_failed_check(self):
        self.jar().write_bytes(b'not a zip file')
        self.assertEqual(1, self.run_jars(['example']))

    def test_current_pom_version_and_complete_archive_pass(self):
        self.jar()
        self.assertEqual(0, self.run_jars(['example']))

    def actuator(self, health, protected=401):
        def response(url, timeout):
            return health if url.endswith('/actuator/health') else protected
        with patch.object(ACTUATOR, 'request_status', side_effect=response), \
                patch('sys.argv', ['verify-actuator-auth.py', '--url', 'http://127.0.0.1:1']), \
                contextlib.redirect_stdout(self.output):
            return ACTUATOR.main()

    def test_health_must_be_an_expected_actuator_response(self):
        for status in (200, 503):
            with self.subTest(status=status):
                self.assertEqual(0, self.actuator(status))
        for status in (401, 403, 404, 500):
            with self.subTest(status=status):
                self.assertEqual(1, self.actuator(status))

    def test_protected_endpoints_must_reject_without_credentials(self):
        for status in (200, 403, 404, 500):
            with self.subTest(status=status):
                self.assertEqual(1, self.actuator(200, protected=status))

    def test_actuator_command_checks_real_local_http_statuses(self):
        class Handler(BaseHTTPRequestHandler):
            health = 200

            def do_GET(self):
                self.send_response(self.health if self.path == '/actuator/health' else 401)
                self.end_headers()

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                for status, expected in ((200, 0), (503, 0), (404, 1), (500, 1)):
                    with self.subTest(status=status):
                        Handler.health = status
                        result = subprocess.run(
                            [sys.executable, str(ROOT / 'build/verify-actuator-auth.py'),
                             '--url', f'http://127.0.0.1:{server.server_port}'],
                            capture_output=True, text=True, timeout=10)
                        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
            finally:
                server.shutdown()
                thread.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
