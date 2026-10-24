"""Prevent the deployed stack from silently omitting its operational assets."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

BUILD = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('observability_assets', BUILD / 'verify-observability-assets.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class ObservabilityWiringTest(unittest.TestCase):
    def test_repository_loads_its_dashboards_rules_and_log_delivery(self):
        errors = []
        gate.check_runtime_wiring(errors)
        self.assertEqual([], errors)

    def test_removing_a_grafana_mount_rejects_orphaned_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            compose = Path(directory) / 'compose.yml'
            compose.write_text(gate.COMPOSE.read_text().replace(
                '      - ./observability/grafana/provisioning:/etc/grafana/provisioning:ro\n', ''))
            with patch.object(gate, 'COMPOSE', compose):
                errors = []
                gate.check_runtime_wiring(errors)
            self.assertTrue(any('Grafana deployment' in x for x in errors))

    def test_equal_boot_banners_cannot_reenable_content_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'logs.toml'
            config.write_text(gate.LOG_VECTOR.read_text().replace('"device_and_inode"', '"checksum"'))
            with patch.object(gate, 'LOG_VECTOR', config):
                errors = []
                gate.check_runtime_wiring(errors)
            self.assertTrue(any('fingerprint.strategy' in x for x in errors))

    def test_missing_notification_route_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'prometheus.yml'
            config.write_text(gate.OBS_PROMETHEUS.read_text().replace('alertmanager:9093', 'unconfigured:9093'))
            with patch.object(gate, 'OBS_PROMETHEUS', config):
                errors = []
                gate.check_runtime_wiring(errors)
            self.assertTrue(any('alertmanager:9093' in x for x in errors))
