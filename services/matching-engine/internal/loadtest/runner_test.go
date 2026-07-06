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

func TestRunDeepLifecycleScenario(t *testing.T) {
	outputDir := t.TempDir()
	report, err := Run(context.Background(), app.NewService(), Config{
		RunID:         "test-deep-lifecycle",
		Scenario:      ScenarioDeepLifecycle,
		RatePerSecond: 1000,
		Duration:      100 * time.Millisecond,
		Workers:       1,
		Instruments:   1,
		OutputDir:     outputDir,
	})
	if err != nil {
		t.Fatalf("run deep lifecycle load harness: %v", err)
	}

	if report.Attempted != 100 || report.Processed != 100 {
		t.Fatalf("expected 100 attempted and processed, got attempted=%d processed=%d", report.Attempted, report.Processed)
	}
	if report.Accepted != 100 || report.Rejected != 0 || report.SystemFailures != 0 {
		t.Fatalf("unexpected deep lifecycle counts: accepted=%d rejected=%d failures=%d", report.Accepted, report.Rejected, report.SystemFailures)
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

func TestNormalizeConfigAppliesDefaults(t *testing.T) {
	cfg := normalizeConfig(Config{})
	if cfg.RunID == "" {
		t.Error("expected generated RunID")
	}
	if cfg.Scenario != ScenarioAlternatingCross {
		t.Errorf("Scenario = %v, want %v", cfg.Scenario, ScenarioAlternatingCross)
	}
	if cfg.RatePerSecond != 10000 {
		t.Errorf("RatePerSecond = %d, want 10000", cfg.RatePerSecond)
	}
	if cfg.Duration != 30*time.Second {
		t.Errorf("Duration = %v, want 30s", cfg.Duration)
	}
	if cfg.Workers != 1 {
		t.Errorf("Workers = %d, want 1", cfg.Workers)
	}
	if cfg.Instruments != 1 {
		t.Errorf("Instruments = %d, want 1", cfg.Instruments)
	}
	if cfg.OutputDir == "" {
		t.Error("expected default OutputDir")
	}

	explicit := normalizeConfig(Config{
		RunID:         "explicit-run",
		Scenario:      ScenarioRestingBook,
		RatePerSecond: 5,
		Duration:      time.Second,
		Workers:       3,
		Instruments:   2,
		OutputDir:     "custom-dir",
	})
	if explicit.RunID != "explicit-run" || explicit.Scenario != ScenarioRestingBook ||
		explicit.RatePerSecond != 5 || explicit.Duration != time.Second ||
		explicit.Workers != 3 || explicit.Instruments != 2 || explicit.OutputDir != "custom-dir" {
		t.Errorf("normalizeConfig overwrote explicit values: %#v", explicit)
	}
}

func TestValidateConfig(t *testing.T) {
	base := Config{RatePerSecond: 10, Duration: time.Second, Workers: 1, Instruments: 1, Scenario: ScenarioAlternatingCross}

	if err := validateConfig(base); err != nil {
		t.Fatalf("expected valid config, got %v", err)
	}

	cases := []struct {
		name string
		cfg  Config
	}{
		{"rate", Config{Duration: time.Second, Workers: 1, Instruments: 1, Scenario: ScenarioAlternatingCross}},
		{"duration", Config{RatePerSecond: 10, Workers: 1, Instruments: 1, Scenario: ScenarioAlternatingCross}},
		{"workers", Config{RatePerSecond: 10, Duration: time.Second, Instruments: 1, Scenario: ScenarioAlternatingCross}},
		{"instruments", Config{RatePerSecond: 10, Duration: time.Second, Workers: 1, Scenario: ScenarioAlternatingCross}},
		{"scenario", Config{RatePerSecond: 10, Duration: time.Second, Workers: 1, Instruments: 1, Scenario: "unknown"}},
	}
	for _, c := range cases {
		if err := validateConfig(c.cfg); err == nil {
			t.Errorf("%s: expected validation error", c.name)
		}
	}

	for _, scenario := range []string{ScenarioAlternatingCross, ScenarioRestingBook, ScenarioLifecycle, ScenarioDeepLifecycle} {
		cfg := base
		cfg.Scenario = scenario
		if err := validateConfig(cfg); err != nil {
			t.Errorf("scenario %v: expected valid, got %v", scenario, err)
		}
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
