#!/usr/bin/env python3
"""Small hermetic HTTP sink used by multi-process full-stack verification."""

import argparse
import json
import signal
import sys
import threading
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=38421)
    args = parser.parse_args()
    server = WebhookSinkServer((args.host, args.port), WebhookSink)
    def stop(_signum, _frame):
        # shutdown() must run from a thread other than serve_forever(), or the
        # signal handler would deadlock the server loop.
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print(f"webhook sink listening on http://{args.host}:{args.port}/notify", flush=True)
    server.serve_forever()
    server.server_close()


if __name__ == "__main__":
    main()
