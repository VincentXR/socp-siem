#!/usr/bin/env python3
"""Small hermetic HTTP sink used by multi-process full-stack verification."""

import json
import sys
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class WebhookSink(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path.rstrip("/") not in ("", "/health"):
            self.send_error(404)
            return
        body = b"ok\n"
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        sys.stdout.write(json.dumps({
            "path": self.path,
            "bytes": len(body),
        }) + "\n")
        sys.stdout.flush()
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()
        self.wfile.flush()

    def log_message(self, _format, *_args):
        return


class WebhookSinkServer(ThreadingHTTPServer):
    # Notification and SOAR recovery scenarios intentionally create bursts.
    # The stdlib default backlog is only five and can make the hermetic sink
    # look like an unknown third-party outcome under otherwise healthy load.
    request_queue_size = 128



@contextmanager
def webhook_fixture(external_url=""):
    """Own a temporary server only when an explicit external fixture is absent."""
    if external_url.strip():
        yield external_url.strip()
        return
    server = WebhookSinkServer(("127.0.0.1", 0), WebhookSink)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}/notify"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
