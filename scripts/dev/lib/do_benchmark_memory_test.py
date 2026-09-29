import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SOURCE = Path(__file__).with_name("do-benchmark-memory.py")
SPEC = importlib.util.spec_from_file_location("do_benchmark_memory", SOURCE)
memory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(memory)


class BenchmarkMemoryTest(unittest.TestCase):
    def test_parse_docker_stats_rows(self):
        output = '{"ID":"abc","Name":"platform-api","MemUsage":"1.5GiB / 32GiB"}\n'
        with patch.object(memory.subprocess, "run", return_value=type("Result", (), {"stdout": output})()):
            sample = memory.sample_once()
        self.assertEqual(sample["containers"][0]["memoryUsageBytes"], 1610612736)
        self.assertEqual(sample["containers"][0]["memoryLimitBytes"], 34359738368)
        self.assertEqual(memory.parse_bytes("500kB"), 500000)
        self.assertEqual(memory.parse_time("2026-09-29T00:00:00.123456789Z").microsecond, 123456)

    def test_summary_uses_only_measured_window_and_checks_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "report.json"
            raw = root / "samples.jsonl"
            report.write_text(json.dumps({"startedAt": "2026-09-29T00:00:00Z",
                                          "finishedAt": "2026-09-29T00:00:30Z"}))
            rows = [
                {"capturedAt": "2026-09-28T23:59:55Z", "containers": [{"id": "a", "name": "api", "memoryUsageBytes": 900}]},
                {"capturedAt": "2026-09-29T00:00:05Z", "containers": [{"id": "a", "name": "api", "memoryUsageBytes": 100}, {"id": "b", "name": "db", "memoryUsageBytes": 50}]},
                {"capturedAt": "2026-09-29T00:00:15Z", "containers": [{"id": "a", "name": "api", "memoryUsageBytes": 200}, {"id": "b", "name": "db", "memoryUsageBytes": 70}]},
                {"capturedAt": "2026-09-29T00:00:25Z", "containers": [{"id": "a", "name": "api", "memoryUsageBytes": 150}, {"id": "b", "name": "db", "memoryUsageBytes": 80}]},
            ]
            raw.write_text("\n".join(json.dumps(row) for row in rows) + "\n")
            summary = memory.summarize(raw, report, 10)
            self.assertEqual(summary["status"], "complete")
            self.assertEqual(summary["sampleCount"], 3)
            self.assertEqual(summary["peakTotalRunningContainersBytes"]["peakUsageBytes"], 270)
            self.assertEqual(summary["perContainer"][0]["peakUsageBytes"], 200)
            self.assertEqual(summary["coverage"]["maxBetweenSamplesSeconds"], 10)

    def test_missing_and_gapped_samples_cannot_claim_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "report.json"
            raw = root / "samples.jsonl"
            report.write_text(json.dumps({"startedAt": "2026-09-29T00:00:00Z",
                                          "finishedAt": "2026-09-29T00:05:00Z"}))
            self.assertEqual(memory.summarize(raw, report, 10)["status"], "incomplete")
            rows = [
                {"capturedAt": "2026-09-29T00:00:05Z", "containers": [{"id": "a", "name": "api", "memoryUsageBytes": 100}]},
                {"capturedAt": "2026-09-29T00:04:55Z", "containers": [{"id": "a", "name": "api", "memoryUsageBytes": 200}]},
            ]
            raw.write_text("\n".join(json.dumps(row) for row in rows) + "\n")
            summary = memory.summarize(raw, report, 10)
            self.assertEqual(summary["status"], "incomplete")
            self.assertIn("measured sample gap exceeded 20s", summary["reasons"])

    def test_check_requires_raw_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summary = root / "container-memory-summary.json"
            summary.write_text(json.dumps({"status": "complete", "sampleCount": 30}))
            command = [sys.executable, str(SOURCE), "check", str(summary)]
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            (root / "container-memory-samples.jsonl").write_text("{}\n")
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
