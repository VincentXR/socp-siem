#!/usr/bin/env python3
"""CLI entry point for the shared hermetic webhook fixture."""
import argparse
import importlib.util
from pathlib import Path
import signal
import threading


# Resolve the sibling by its file, not by the caller's import search path.
# CI also imports this entry point with spec_from_file_location(), which does
# not add build/ to sys.path as direct script execution would.
_SPEC = importlib.util.spec_from_file_location(
    "_socp_webhook_sink", Path(__file__).resolve().with_name("webhook_sink.py"))
assert _SPEC is not None and _SPEC.loader is not None
_FIXTURE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_FIXTURE)
WebhookSink = _FIXTURE.WebhookSink
WebhookSinkServer = _FIXTURE.WebhookSinkServer


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
