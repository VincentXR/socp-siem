#!/usr/bin/env python3
"""Preserve bounded, read-only diagnostics before a failed fixture is stopped."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request


def capture(jcmd, port, pid, output, timeout=8):
    result = {"port": port, "pid": pid}
    try:
        with urllib.request.urlopen(
            f"http://127.0.0.1:{port}/detect-web/actuator/health", timeout=2
        ) as response:
            result["health"] = {
                "http": response.status,
                "body": response.read(8192).decode(errors="replace"),
            }
    except urllib.error.HTTPError as failure:
        result["health"] = {
            "http": failure.code,
            "body": failure.read(8192).decode(errors="replace"),
        }
    except Exception as failure:
        result["health"] = {"errorType": type(failure).__name__}
    if not jcmd or not pid:
        result["threads"] = "unavailable: missing jcmd or live process ID"
        return result
    # Diagnostic JVMs need no application secrets or inherited JVM options.
    environment = {
        key: value
        for key, value in os.environ.items()
        if key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}
    }
    commands = {
        "platform": ["Thread.print", "-l"],
        "virtual": [
            "Thread.dump_to_file",
            "-format=json",
            str(output / f"threads-{port}.json"),
        ],
    }
    for kind, command in commands.items():
        with (output / f"threads-{port}-{kind}.log").open("w") as log:
            try:
                completed = subprocess.run(
                    [jcmd, str(pid), *command],
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    timeout=timeout,
                    check=False,
                    env=environment,
                )
                result[kind] = {"exitCode": completed.returncode}
            except subprocess.TimeoutExpired:
                result[kind] = {"timedOut": True}
            except OSError as failure:
                result[kind] = {"errorType": type(failure).__name__}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--log-dir", type=Path, required=True)
    parser.add_argument(
        "--instance",
        action="append",
        default=[],
        help="port:pid, with an empty pid if unavailable",
    )
    args = parser.parse_args()
    java = Path(shutil.which(args.java) or args.java).resolve()
    candidate = java.with_name("jcmd.exe" if os.name == "nt" else "jcmd")
    jcmd = str(candidate) if candidate.is_file() else shutil.which("jcmd")
    output = args.log_dir.resolve() / f"startup-failure-{time.time_ns()}"
    output.mkdir(parents=True)
    instances = []
    for value in args.instance:
        port, pid = value.split(":", 1)
        port, pid = int(port), int(pid) if pid else None
        if not 1 <= port <= 65535 or (pid is not None and pid <= 0):
            raise ValueError("invalid fixture port or process ID")
        instances.append((port, pid))
    # Three failed JVMs must not multiply the launcher's diagnostic deadline.
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as workers:
        futures = [
            workers.submit(capture, jcmd, port, pid, output) for port, pid in instances
        ]
        results = [future.result() for future in futures]
    (output / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f"Detection startup diagnostics: {output}")


if __name__ == "__main__":
    main()
