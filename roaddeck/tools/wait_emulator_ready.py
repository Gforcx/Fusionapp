#!/usr/bin/env python3
"""Wait for measured guest CPU headroom without starting the app or WebView."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
import time


CPU_FIELDS = ("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal")
MAX_WAIT_SECONDS = 60.0
READ_TIMEOUT_SECONDS = 4.0
SAMPLE_INTERVAL_SECONDS = 1.0
MAX_BUSY_PERCENT = 85.0
REQUIRED_CONSECUTIVE_DELTAS = 3
COMMAND = ["adb", "shell", "cat", "/proc/stat"]


def parse_cpu_counters(stdout):
    """Read only the aggregate CPU row; guest counters already occur in user/nice."""
    for line in stdout.splitlines():
        values = line.split()
        if values and values[0] == "cpu":
            if len(values) < len(CPU_FIELDS) + 1:
                raise ValueError("Aggregate /proc/stat CPU row has fewer than eight counters")
            numbers = values[1:len(CPU_FIELDS) + 1]
            if not all(value.isdecimal() for value in numbers):
                raise ValueError("Aggregate /proc/stat CPU counters are not nonnegative integers")
            return dict(zip(CPU_FIELDS, map(int, numbers)))
    raise ValueError("No aggregate CPU row in adb output")


def cpu_delta(previous, current):
    deltas = {name: current[name] - previous[name] for name in CPU_FIELDS}
    if any(value < 0 for value in deltas.values()):
        raise ValueError("CPU counters moved backwards; a new baseline is required")
    total = sum(deltas.values())
    if total <= 0:
        raise ValueError("CPU counters did not advance")
    idle = deltas["idle"] + deltas["iowait"]
    return {
        "totalTicks": total,
        "idleAndIoWaitTicks": idle,
        "busyPercent": 100.0 * (total - idle) / total,
    }


def output_text(value):
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value or ""


def save_evidence(path, evidence):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def wait_for_cpu_headroom(output):
    started = time.monotonic()
    deadline = started + MAX_WAIT_SECONDS
    evidence = {
        "schema": 1,
        "startedAtUtc": datetime.now(timezone.utc).isoformat(),
        "status": "waiting",
        "scope": "Emulator preparation before APK installation and cold application checks",
        "command": COMMAND,
        "maxWaitSeconds": MAX_WAIT_SECONDS,
        "readTimeoutSeconds": READ_TIMEOUT_SECONDS,
        "sampleIntervalSeconds": SAMPLE_INTERVAL_SECONDS,
        "busyPercentStrictlyBelow": MAX_BUSY_PERCENT,
        "requiredConsecutiveDeltas": REQUIRED_CONSECUTIVE_DELTAS,
        "applicationOrWebViewWarmupPerformed": False,
        "samples": [],
    }
    previous = None
    consecutive = 0
    valid_deltas = 0
    save_evidence(output, evidence)
    try:
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            sample = {"startedSeconds": round(time.monotonic() - started, 3)}
            try:
                result = subprocess.run(
                    COMMAND, capture_output=True, text=True, encoding="utf-8", errors="replace",
                    timeout=min(READ_TIMEOUT_SECONDS, remaining), check=False,
                )
                sample.update({"returncode": result.returncode,
                               "stdout": result.stdout, "stderr": result.stderr})
                if result.returncode != 0:
                    raise ValueError("adb could not read guest /proc/stat")
                current = parse_cpu_counters(result.stdout)
                sample["counters"] = current
                if previous is not None:
                    try:
                        delta = cpu_delta(previous, current)
                        sample["delta"] = delta
                        valid_deltas += 1
                        consecutive = consecutive + 1 if delta["busyPercent"] < MAX_BUSY_PERCENT else 0
                    except ValueError as error:
                        sample["error"] = str(error)
                        consecutive = 0
                previous = current
            except subprocess.TimeoutExpired as error:
                sample.update({"error": "adb CPU read timed out", "timedOut": True,
                               "stdout": output_text(error.stdout), "stderr": output_text(error.stderr)})
                previous = None
                consecutive = 0
            except (OSError, ValueError) as error:
                sample["error"] = str(error)
                previous = None
                consecutive = 0
            sample["finishedSeconds"] = round(time.monotonic() - started, 3)
            sample["consecutiveBelowThreshold"] = consecutive
            evidence["samples"].append(sample)
            save_evidence(output, evidence)
            if consecutive >= REQUIRED_CONSECUTIVE_DELTAS and time.monotonic() <= deadline:
                evidence["status"] = "passed"
                evidence["reason"] = "Three consecutive measured guest CPU deltas are below 85 percent"
                break
            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(SAMPLE_INTERVAL_SECONDS, remaining))
        if evidence["status"] != "passed":
            evidence["status"] = "failed"
            evidence["reason"] = (
                "Guest CPU counters were unavailable or invalid; preparation cannot be verified"
                if valid_deltas == 0 else
                "Guest CPU did not provide three consecutive deltas below 85 percent within 60 seconds"
            )
    except KeyboardInterrupt:
        evidence.update({"status": "failed", "reason": "Emulator preparation was interrupted"})
    finally:
        evidence["elapsedSeconds"] = round(time.monotonic() - started, 3)
        evidence["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        evidence["validDeltas"] = valid_deltas
        save_evidence(output, evidence)
    print("Emulator CPU preparation: " + evidence["status"] + ". " + evidence["reason"], flush=True)
    print("Evidence: " + str(output), flush=True)
    return 0 if evidence["status"] == "passed" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("ci-evidence/emulator-ready.json"))
    args = parser.parse_args()
    return wait_for_cpu_headroom(args.output)


if __name__ == "__main__":
    sys.exit(main())
