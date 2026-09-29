"""The full-stack webhook fixture must return deterministic receipts under load."""

from concurrent.futures import ThreadPoolExecutor
import contextlib
import http.client
import importlib.util
import io
import json
from pathlib import Path
import threading
import unittest


SPEC = importlib.util.spec_from_file_location(
    "webhook_sink", Path(__file__).resolve().parents[1] / "webhook-sink.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class WebhookSinkTest(unittest.TestCase):
    def setUp(self):
        self.output = io.StringIO()
        self.redirect = contextlib.redirect_stdout(self.output)
        self.redirect.__enter__()
        self.server = MODULE.WebhookSinkServer(
            ("127.0.0.1", 0), MODULE.WebhookSink)
        self.thread = threading.Thread(
            target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.host, self.port = self.server.server_address

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.redirect.__exit__(None, None, None)

    def test_persistent_connection_has_explicit_response_boundaries(self):
        connection = http.client.HTTPConnection(
            self.host, self.port, timeout=5)
        self.addCleanup(connection.close)

        for index in range(20):
            body = json.dumps({"delivery": index}).encode()
            connection.request(
                "POST",
                "/notify",
                body=body,
                headers={"Content-Type": "application/json"},
            )
            response = connection.getresponse()
            self.assertEqual(204, response.status)
            self.assertEqual("0", response.getheader("Content-Length"))
            self.assertEqual(b"", response.read())

    def test_concurrent_burst_does_not_drop_receipts(self):
        def post(index):
            connection = http.client.HTTPConnection(
                self.host, self.port, timeout=10)
            try:
                body = json.dumps({"delivery": index}).encode()
                connection.request(
                    "POST",
                    "/notify",
                    body=body,
                    headers={"Content-Type": "application/json"},
                )
                response = connection.getresponse()
                return response.status, response.read()
            finally:
                connection.close()

        with ThreadPoolExecutor(max_workers=32) as executor:
            receipts = list(executor.map(post, range(96)))

        self.assertEqual([(204, b"")] * 96, receipts)


if __name__ == "__main__":
    unittest.main()
