#!/usr/bin/env python3
"""Sample Docker-reported memory and summarize one measured load window."""

import argparse
import json
import re
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path


UNITS = {"B": 1, "kB": 1000, "MB": 1000**2, "GB": 1000**3,
         "TB": 1000**4, "KiB": 1024, "MiB": 1024**2,
         "GiB": 1024**3, "TiB": 1024**4}
MEMORY_RE = re.compile(r"^([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z]+)$")


def timestamp():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_time(value):
    # Go's RFC3339Nano reports can carry nine fractional digits; older Python
    # datetime parsers accept at most six.
    normalized = re.sub(r"(\.\d{6})\d+(?=(?:Z|[+-]\d{2}:\d{2})$)", r"\1", value)
    parsed = datetime.fromisoformat(normalized.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp lacks timezone")
    return parsed


def parse_bytes(value):
    match = MEMORY_RE.fullmatch(value.strip())
    if not match or match.group(2) not in UNITS:
        raise ValueError(f"unknown Docker memory unit: {value!r}")
    return int(Decimal(match.group(1)) * UNITS[match.group(2)])


def sample_once():
    result = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{json .}}"],
        capture_output=True, text=True, timeout=8, check=True,
    )
    containers = []
    for line in result.stdout.splitlines():
        row = json.loads(line)
        usage, limit = row["MemUsage"].split("/", 1)
        containers.append({
            "id": row["ID"], "name": row["Name"],
            "memoryUsageBytes": parse_bytes(usage),
            "memoryLimitBytes": parse_bytes(limit),
        })
    return {"capturedAt": timestamp(), "containers": containers}


def sample(raw_path, interval_seconds):
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda _signum, _frame: stop.set())
    raw_path.parent.mkdir(parents=True, exist_ok=True)
    with raw_path.open("a", encoding="utf-8") as output:
        while not stop.is_set():
            started = time.monotonic()
            try:
                entry = sample_once()
            except (OSError, subprocess.SubprocessError, ValueError, KeyError,
                    json.JSONDecodeError) as error:
                entry = {"capturedAt": timestamp(), "error": str(error)}
                print(f"Docker memory sample failed: {error}", file=sys.stderr, flush=True)
            output.write(json.dumps(entry, separators=(",", ":")) + "\n")
            output.flush()
            stop.wait(max(0, interval_seconds - (time.monotonic() - started)))


def summarize(raw_path, report_path, interval_seconds):
    result = {
        "status": "incomplete", "source": "docker stats --no-stream MemUsage",
        "meaning": "sampled Docker stats MemUsage maxima; instantaneous peaks may be higher",
        "intervalSeconds": interval_seconds, "measuredWindow": None,
        "sampleCount": 0, "sampleErrors": 0,
        "peakTotalRunningContainersBytes": None, "perContainer": [],
        "coverage": None, "reasons": [],
    }
    if not report_path.is_file():
        result["reasons"].append("measured load report missing")
        return result
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        start = parse_time(report["startedAt"])
        end = parse_time(report["finishedAt"])
        if end <= start:
            raise ValueError("non-positive measured window")
    except (ValueError, KeyError, json.JSONDecodeError) as error:
        result["reasons"].append(f"invalid measured load window: {error}")
        return result
    result["measuredWindow"] = {"startedAt": start.isoformat(),
                                 "finishedAt": end.isoformat()}
    if not raw_path.is_file():
        result["reasons"].append("raw memory samples missing")
        return result

    rows = []
    for number, line in enumerate(raw_path.read_text(encoding="utf-8").splitlines(), 1):
        try:
            row = json.loads(line)
            at = parse_time(row["capturedAt"])
        except (ValueError, KeyError, json.JSONDecodeError) as error:
            result["reasons"].append(f"invalid raw sample line {number}: {error}")
            continue
        if start <= at <= end:
            rows.append((at, row))
    rows.sort(key=lambda pair: pair[0])
    valid = [(at, row) for at, row in rows if "error" not in row]
    result["sampleCount"] = len(valid)
    result["sampleErrors"] = len(rows) - len(valid)
    if result["sampleErrors"]:
        result["reasons"].append("Docker stats errors within measured window")
    if len(valid) < 2:
        result["reasons"].append("fewer than two measured memory samples")
        return result

    first, last = valid[0][0], valid[-1][0]
    gaps = [(right[0] - left[0]).total_seconds()
            for left, right in zip(valid, valid[1:])]
    coverage = {
        "firstSampleAt": first.isoformat(), "lastSampleAt": last.isoformat(),
        "startGapSeconds": (first - start).total_seconds(),
        "endGapSeconds": (end - last).total_seconds(),
        "maxBetweenSamplesSeconds": max(gaps),
    }
    result["coverage"] = coverage
    allowed_gap = interval_seconds * 2
    if max(coverage["startGapSeconds"], coverage["endGapSeconds"],
           coverage["maxBetweenSamplesSeconds"]) > allowed_gap:
        result["reasons"].append(f"measured sample gap exceeded {allowed_gap}s")

    peaks = {}
    total_peak = None
    for at, row in valid:
        if not isinstance(row.get("containers"), list):
            result["reasons"].append("sample missing containers list")
            continue
        total = 0
        for container in row["containers"]:
            try:
                identifier = container["id"]
                usage = container["memoryUsageBytes"]
                if not isinstance(usage, int) or usage < 0:
                    raise ValueError("invalid usage bytes")
                total += usage
                prior = peaks.get(identifier)
                if prior is None or usage > prior["peakUsageBytes"]:
                    peaks[identifier] = {"id": identifier, "name": container["name"],
                                         "peakUsageBytes": usage,
                                         "sampledAt": at.isoformat()}
            except (KeyError, ValueError, TypeError) as error:
                result["reasons"].append(f"invalid container sample: {error}")
        if total_peak is None or total > total_peak["peakUsageBytes"]:
            total_peak = {"peakUsageBytes": total, "sampledAt": at.isoformat(),
                          "containerCount": len(row["containers"])}
    result["peakTotalRunningContainersBytes"] = total_peak
    result["perContainer"] = sorted(peaks.values(), key=lambda value: value["name"])
    if not peaks:
        result["reasons"].append("no running containers sampled")
    result["reasons"] = sorted(set(result["reasons"]))
    if not result["reasons"]:
        result["status"] = "complete"
    return result


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    sampler = commands.add_parser("sample")
    sampler.add_argument("raw", type=Path)
    sampler.add_argument("--interval-seconds", type=int, default=10)
    summary = commands.add_parser("summarize")
    summary.add_argument("raw", type=Path)
    summary.add_argument("report", type=Path)
    summary.add_argument("out", type=Path)
    summary.add_argument("--interval-seconds", type=int, default=10)
    checker = commands.add_parser("check")
    checker.add_argument("summary", type=Path)
    args = parser.parse_args()
    if args.command == "sample":
        if args.interval_seconds < 1:
            parser.error("interval must be positive")
        sample(args.raw, args.interval_seconds)
    elif args.command == "summarize":
        result = summarize(args.raw, args.report, args.interval_seconds)
        args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(f"container memory evidence: {result['status']} ({', '.join(result['reasons']) or result['sampleCount']})")
    else:
        try:
            result = json.loads(args.summary.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            print(f"container memory evidence missing or invalid: {error}", file=sys.stderr)
            return 1
        raw_path = args.summary.with_name("container-memory-samples.jsonl")
        if not raw_path.is_file() or raw_path.stat().st_size == 0:
            print("raw container memory samples missing or empty", file=sys.stderr)
            return 1
        if result.get("status") != "complete":
            print(f"container memory evidence incomplete: {result.get('reasons')}", file=sys.stderr)
            return 1
        print(f"container memory evidence complete: {result.get('sampleCount')} measured samples")
    return 0


if __name__ == "__main__":
    sys.exit(main())
