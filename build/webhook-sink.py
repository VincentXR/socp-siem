#!/usr/bin/env python3
"""Small hermetic HTTP sink used by multi-process full-stack verification."""

import argparse
import json
import signal
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class WebhookSink(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.rstrip("/") not in ("", "/health"):
            self.send_error(404)
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok\n")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        sys.stdout.write(json.dumps({
            "path": self.path,
            "bytes": len(body),
        }) + "\n")
        sys.stdout.flush()
        self.send_response(204)
        self.end_headers()

    def log_message(self, _format, *_args):
        return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=38421)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), WebhookSink)
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
