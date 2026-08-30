#!/usr/bin/env python3
"""Run repeated SOCP benchmark rounds and derive a stable baseline.

The single-round benchmark is intentionally focused on one workload.  This
runner adds the missing evidence contract for a baseline: the same 50k-event
shape is executed 3--5 times, every raw report/stdout/stderr is retained, and
throughput degradation is calculated from the successful rounds.  It does not
start services or claim production capacity; the caller supplies the already
started topology (including one, two, or three Detection instances).
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]


def read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def throughput(report: dict, mode: str) -> float:
    key = "endToEndEventsPerSecond" if mode == "e2e" else "eventsPerSecond"
    value = report.get(key, 0)
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def round_output(base: Path, number: int) -> Path:
    return base.with_name(f"{base.stem}.round{number}{base.suffix or '.json'}")


def run_round(args: argparse.Namespace, number: int, output: Path) -> dict:
    command = [
        sys.executable,
        str(ROOT / "build" / "benchmark-pipeline.py"),
        "--mode", args.mode,
        "--profile", args.profile,
        "--count", str(args.count),
        "--batch-size", str(args.batch_size),
        "--alert-every", str(args.alert_every),
        "--instances", str(args.instances),
        "--output", str(output),
        "--label", f"{args.label}-round{number}",
    ]
    if args.rules is not None:
        command.extend(("--rules", str(args.rules)))
    if args.gateway:
        command.extend(("--gateway", args.gateway))
    if args.timeout is not None:
        command.extend(("--timeout", str(args.timeout)))

    stdout_path = output.with_suffix(".stdout.log")
    stderr_path = output.with_suffix(".stderr.log")
    started = time.perf_counter()
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                               check=False, env=os.environ.copy())
    elapsed = time.perf_counter() - started
    output.parent.mkdir(parents=True, exist_ok=True)
    stdout_path.write_text(completed.stdout, encoding="utf-8")
    stderr_path.write_text(completed.stderr, encoding="utf-8")

    success_report = read_json(output)
    failed_report = read_json(output.with_name(output.stem + ".failed" + output.suffix))
    # The single-round benchmark performs its final correctness assertions
    # after printing the detailed report.  A late assertion can therefore
    # leave a partial JSON file whose status is still "passed" while the
    # process exits non-zero.  Prefer the failure sidecar and force the round
    # to failed so a stable baseline can never include an invalid round.
    if completed.returncode != 0:
        report = failed_report or success_report
        if report is not None:
            report["status"] = "failed"
            if failed_report is not None and success_report is not None:
                report["partialReport"] = success_report
    else:
        report = success_report or failed_report
    if report is None:
        report = {
            "status": "failed",
            "failureType": "BenchmarkProcessFailure",
            "failure": completed.stderr.strip() or completed.stdout.strip()
                       or f"benchmark exited with code {completed.returncode}",
        }
    report["seriesRound"] = number
    report["seriesProcessExitCode"] = completed.returncode
    report["seriesRunnerElapsedSeconds"] = round(elapsed, 3)
    artifacts = {
        "report": str(output),
        "stdout": str(stdout_path),
        "stderr": str(stderr_path),
    }
    if failed_report is not None:
        artifacts["failure"] = str(output.with_name(
            output.stem + ".failed" + output.suffix))
    report["seriesArtifacts"] = artifacts
    return report


def build_series_report(args: argparse.Namespace, rounds: list[dict]) -> dict:
    successful = [item for item in rounds if item.get("status") == "passed"
                  and throughput(item, args.mode) > 0]
    values = [throughput(item, args.mode) for item in successful]
    first = values[0] if values else 0.0
    minimum = min(values, default=0.0)
    maximum_drop = max(0.0, (first - minimum) / first) if first else 1.0
    tolerance = max(0.0, min(0.9, args.tolerance))
    return {
        "status": "passed" if len(successful) == len(rounds) else "failed",
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "mode": args.mode,
        "profile": args.profile,
        "requestedEventsPerRound": args.count,
        "batchSize": args.batch_size,
        "roundsRequested": args.rounds,
        "roundsCompleted": len(rounds),
        "instances": args.instances,
        "rules": args.rules,
        "tolerance": tolerance,
        "roundReports": rounds,
        "stableBaseline": {
            "throughputMetric": "endToEndEventsPerSecond"
                                  if args.mode == "e2e" else "eventsPerSecond",
            "successfulRounds": len(successful),
            "throughputEps": {
                "first": round(first, 3),
                "min": round(minimum, 3),
                "max": round(max(values, default=0.0), 3),
                "mean": round(statistics.mean(values), 3) if values else 0,
                "median": round(statistics.median(values), 3) if values else 0,
            },
            "maxRelativeDropFromFirst": round(maximum_drop, 4),
            "throughputStable": bool(values) and len(successful) == len(rounds)
                                and maximum_drop <= tolerance,
            "acceptance": "all rounds pass and minimum throughput stays within "
                          f"{tolerance:.0%} of the first successful round",
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="SOCP repeated benchmark baseline")
    parser.add_argument("--rounds", type=int, default=3,
                        help="number of identical rounds (3-5 recommended)")
    parser.add_argument("--count", type=int, default=50_000)
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--mode", choices=("bulk", "e2e"), default="e2e")
    parser.add_argument("--profile", choices=("realistic", "alert-heavy"), default="realistic")
    parser.add_argument("--alert-every", type=int, default=100,
                        help="realistic profile alert interval")
    parser.add_argument("--instances", type=int, default=1)
    parser.add_argument("--rules", type=int, default=None)
    parser.add_argument("--gateway", default=None)
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--tolerance", type=float, default=0.15,
                        help="maximum allowed relative drop from the first round")
    parser.add_argument("--label", default="baseline-series")
    parser.add_argument("--output", default=".cache/benchmark/baseline-series.json")
    args = parser.parse_args()
    if not 1 <= args.rounds <= 10:
        parser.error("--rounds must be between 1 and 10")
    if args.count <= 0 or args.batch_size <= 0 or args.alert_every <= 0:
        parser.error("--count, --batch-size, and --alert-every must be positive")
    if args.instances <= 0:
        parser.error("--instances must be positive")

    final_path = Path(args.output)
    rounds: list[dict] = []
    for number in range(1, args.rounds + 1):
        report_path = round_output(final_path, number)
        report = run_round(args, number, report_path)
        rounds.append(report)
        print("round %d/%d: status=%s throughput=%.2f eps" % (
            number, args.rounds, report.get("status", "failed"),
            throughput(report, args.mode)))

    report = build_series_report(args, rounds)
    final_path.parent.mkdir(parents=True, exist_ok=True)
    final_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8")
    print(json.dumps({
        "status": report["status"],
        "output": str(final_path),
        "stableBaseline": report["stableBaseline"],
    }, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "passed" \
        and report["stableBaseline"]["throughputStable"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
