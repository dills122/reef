package loadtest

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

func TestRunAlternatingCrossProducesArtifactsAndMetrics(t *testing.T) {
	outputDir := t.TempDir()
	report, err := Run(context.Background(), app.NewService(), Config{
		RunID:         "test-cross",
		Scenario:      ScenarioAlternatingCross,
		RatePerSecond: 100,
		Duration:      100 * time.Millisecond,
		Workers:       1,
		Instruments:   1,
		OutputDir:     outputDir,
	})
	if err != nil {
		t.Fatalf("run load harness: %v", err)
	}

	if report.Attempted != 10 || report.Processed != 10 {
		t.Fatalf("expected 10 attempted and processed, got attempted=%d processed=%d", report.Attempted, report.Processed)
	}
	if report.Accepted != 10 || report.Rejected != 0 || report.SystemFailures != 0 {
		t.Fatalf("unexpected result counts: accepted=%d rejected=%d failures=%d", report.Accepted, report.Rejected, report.SystemFailures)
	}
	if report.Trades != 5 || report.Executions != 10 {
		t.Fatalf("expected 5 trades and 10 executions, got trades=%d executions=%d", report.Trades, report.Executions)
	}
	if report.LatencyMicros.Count != 10 {
		t.Fatalf("expected latency count 10, got %d", report.LatencyMicros.Count)
	}
	assertFileExists(t, filepath.Join(outputDir, "test-cross", "summary.json"))
	assertFileExists(t, filepath.Join(outputDir, "test-cross", "intervals.csv"))
}

func TestRunCanRecordResultSamples(t *testing.T) {
	outputDir := t.TempDir()
	report, err := Run(context.Background(), app.NewService(), Config{
		RunID:              "test-record",
		Scenario:           ScenarioRestingBook,
		RatePerSecond:      50,
		Duration:           100 * time.Millisecond,
		Workers:            1,
		Instruments:        1,
		OutputDir:          outputDir,
		RecordResults:      true,
		MaxRecordedResults: 3,
	})
	if err != nil {
		t.Fatalf("run load harness: %v", err)
	}

	resultsPath := filepath.Join(outputDir, "test-record", "results.ndjson")
	assertFileExists(t, resultsPath)
	data, err := os.ReadFile(resultsPath)
	if err != nil {
		t.Fatalf("read results artifact: %v", err)
	}
	if lines := countLines(data); lines != 3 {
		t.Fatalf("expected 3 recorded result lines, got %d", lines)
	}
	if report.Artifacts["results"] != resultsPath {
		t.Fatalf("expected results artifact path %q, got %q", resultsPath, report.Artifacts["results"])
	}
}

func TestRunFailsWhenMinimumProcessedRateMisses(t *testing.T) {
	_, err := Run(context.Background(), app.NewService(), Config{
		RunID:            "test-min-rate",
		Scenario:         ScenarioRestingBook,
		RatePerSecond:    10,
		Duration:         100 * time.Millisecond,
		Workers:          1,
		Instruments:      1,
		OutputDir:        t.TempDir(),
		MinProcessedRate: 1000000,
	})
	if err == nil {
		t.Fatalf("expected minimum processed rate failure")
	}
}

func assertFileExists(t *testing.T, path string) {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("expected file %s: %v", path, err)
	}
	if info.IsDir() {
		t.Fatalf("expected file %s, got directory", path)
	}
}

func countLines(data []byte) int {
	lines := 0
	for _, value := range data {
		if value == '\n' {
			lines++
		}
	}
	return lines
}
