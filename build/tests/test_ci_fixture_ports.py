"""The launcher, probes and CI must agree on non-ephemeral fixture listeners."""
import errno
import importlib.util
import os
from pathlib import Path
import socket
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import yaml


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("fixture_ports_chaos", ROOT / "build/chaos-pipeline.py")
CHAOS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHAOS)


class CiFixturePortsTest(unittest.TestCase):
    def test_auto_cluster_defaults_are_outside_the_linux_ephemeral_range(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(CHAOS.subprocess, "run", return_value=SimpleNamespace(returncode=0, stderr="")):
            ports = CHAOS.start_auto_detection_cluster()
            self.assertEqual([18082, 28082, 28083], ports)
            self.assertEqual(",".join(f"http://127.0.0.1:{port}" for port in ports), os.environ["DETECTION_INSTANCE_URLS"])
        bounds = Path("/proc/sys/net/ipv4/ip_local_port_range")
        low, high = map(int, bounds.read_text().split()) if bounds.exists() else (32768, 60999)
        self.assertTrue(all(not low <= port <= high for port in ports), (ports, low, high))

    def test_real_launcher_and_ci_probe_addresses_use_the_same_defaults(self):
        environment = {key: value for key, value in os.environ.items()
                       if key not in ("SOCP_DETECT_CLUSTER_PORTS", "SOCP_PORT_DETECT_WEB")}
        result = subprocess.run(["bash", str(ROOT / "build/detection-cluster.sh"), "ports"],
                                env=environment, capture_output=True, text=True, check=True)
        ports = list(map(int, result.stdout.split()))
        self.assertEqual([18082, 28082, 28083], ports)
        job = yaml.safe_load((ROOT / ".github/workflows/full-stack.yml").read_text())["jobs"]["full-stack-heavy"]
        addresses = [step["env"]["DETECTION_INSTANCE_URLS"] for step in job["steps"]
                     if "DETECTION_INSTANCE_URLS" in step.get("env", {})]
        self.assertEqual(2, len(addresses))
        self.assertTrue(all(value == ",".join(f"http://127.0.0.1:{port}" for port in ports) for value in addresses))
        self.assertEqual("28421", job["env"]["SOCP_WEBHOOK_FIXTURE_PORT"])
        self.assertEqual("http://127.0.0.1:28421/notify", job["env"]["SOCP_WEBHOOK_FIXTURE_URL"])

    def test_explicit_cluster_port_override_is_preserved(self):
        ports = [18182, 28182, 28183]
        override = {"SOCP_DETECT_CLUSTER_PORTS": ",".join(map(str, ports))}
        result = subprocess.run(["bash", str(ROOT / "build/detection-cluster.sh"), "ports"],
                                env={**os.environ, **override}, capture_output=True, text=True, check=True)
        self.assertEqual(ports, list(map(int, result.stdout.split())))
        with patch.dict(os.environ, override, clear=True), \
                patch.object(CHAOS.subprocess, "run", return_value=SimpleNamespace(returncode=0, stderr="")):
            self.assertEqual(ports, CHAOS.start_auto_detection_cluster())

    def test_outbound_port_reservation_blocks_a_listener_without_itself_listening(self):
        with socket.socket() as peer, socket.socket() as outgoing, socket.socket() as listener:
            peer.bind(("127.0.0.1", 0))
            peer.listen(1)
            outgoing.bind(("127.0.0.1", 0))
            # The reservation before connect is portable; established-socket
            # reuse differs between Darwin and Linux. Neither is a listener.
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            with self.assertRaises(OSError) as failure:
                listener.bind(outgoing.getsockname())
            self.assertEqual(errno.EADDRINUSE, failure.exception.errno)
            outgoing.connect(peer.getsockname())
            connection, _ = peer.accept()
            with connection:
                self.assertEqual(outgoing.getsockname(), connection.getpeername())


if __name__ == "__main__":
    unittest.main()
