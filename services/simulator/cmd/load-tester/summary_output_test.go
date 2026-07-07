package main

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// captureStdout redirects os.Stdout for the duration of fn and returns
// everything written to it.
func captureStdout(t *testing.T, fn func()) string {
	t.Helper()
	original := os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("failed to create pipe: %v", err)
	}
	os.Stdout = w
	defer func() { os.Stdout = original }()

	fn()

	if err := w.Close(); err != nil {
		t.Fatalf("failed to close pipe writer: %v", err)
	}
	out, err := io.ReadAll(r)
	if err != nil {
		t.Fatalf("failed to read pipe: %v", err)
	}
	return string(out)
}

func sampleSummary() summary {
	return summary{
		SessionID:              "session-1",
		StartedAt:              time.Date(2026, 7, 7, 0, 0, 0, 0, time.UTC),
		FinishedAt:             time.Date(2026, 7, 7, 0, 1, 0, 0, time.UTC),
		DurationSeconds:        60,
		Config:                 Config{Mode: "strict-lifecycle", Workers: 4, RatePerSecond: 100, PrettySummary: true, ProfileMixMM: 25, ProfileMixInst: 25, ProfileMixRetail: 25, ProfileMixNoise: 25},
		ThroughputRPS:          10.5,
		AcceptedBusinessOpsRPS: 9.5,
		TotalRequests:          100,
		TotalSuccess:           90,
		TotalFailures:          10,
		ByAction: map[Action]actionSummary{
			ActionSubmit: {Requests: 60, Success: 55, Failures: 5},
			ActionModify: {Requests: 30, Success: 28, Failures: 2},
			ActionCancel: {Requests: 10, Success: 7, Failures: 3},
		},
		ByProfile: map[string]profileSummary{
			profileMarketMaker: {Requests: 40, Success: 38, Failures: 2},
			profileRetail:      {Requests: 30, Success: 27, Failures: 3},
		},
		ByActor: map[string]profileSummary{
			"actor-1": {Requests: 50, Success: 45, Failures: 5},
			"actor-2": {Requests: 50, Success: 45, Failures: 5},
		},
		ByStrategy: map[string]profileSummary{
			"strategy-1": {Requests: 70, Success: 65, Failures: 5},
		},
		ByPersona: map[string]profileSummary{
			"persona-1": {Requests: 100, Success: 90, Failures: 10},
		},
		StatusCodes: map[int]int64{200: 90, 409: 5, 500: 5},
		TopErrors: []errorSummary{
			{Error: "timeout", Count: 5},
			{Error: "connection reset", Count: 3},
		},
		RejectReasons: []errorSummary{
			{Error: "DUPLICATE_ORDER_ID", Count: 4},
		},
		RejectTaxonomy: []rejectTaxonomySummary{
			{Code: "DUPLICATE_ORDER_ID", Count: 4, PercentOfFailures: 40, PercentOfRejects: 100},
		},
		LatencyMs: latencySummary{Min: 1, P50: 5, P95: 20, P99: 40, Max: 50},
		TraceChecks: traceChecks{
			Checked:       10,
			Pass:          8,
			Fail:          2,
			FailedTraceID: []string{"trace-1", "trace-2"},
		},
		LoadSchedule: loadScheduleSummary{
			Mode:                  rateSchedulePrecise,
			TargetRequests:        100,
			Scheduled:             100,
			Enqueued:              95,
			Dropped:               5,
			Completed:             90,
			ScheduleDeficit:       5,
			CompletionDeficit:     10,
			CompletionToTargetPct: 90,
		},
	}
}

func TestPrintSummaryJSONMode(t *testing.T) {
	report := sampleSummary()
	report.Config.PrettySummary = false

	out := captureStdout(t, func() { printSummary(report) })

	var decoded summary
	if err := json.Unmarshal([]byte(out), &decoded); err != nil {
		t.Fatalf("expected JSON output, got error %v; output=%q", err, out)
	}
	if decoded.SessionID != "session-1" {
		t.Errorf("decoded sessionId = %q, want session-1", decoded.SessionID)
	}
}

func TestPrintSummaryPrettyMode(t *testing.T) {
	report := sampleSummary()
	report.Config.PrettySummary = true

	out := captureStdout(t, func() { printSummary(report) })

	for _, want := range []string{
		"Reef Load Test Summary",
		"Session: session-1",
		"By Action",
		"By Profile",
		"Top Actors",
		"Top Strategies",
		"Top Personas",
		"Status Codes",
		"Trace Checks",
		"Top Reject Reasons",
		"Reject Taxonomy",
		"Top Errors",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("expected pretty summary output to contain %q; got:\n%s", want, out)
		}
	}
}

func TestPrintPrettySummaryHandlesZeroRequestsAndEmptyCollections(t *testing.T) {
	report := summary{
		SessionID: "empty-session",
		Config:    Config{Mode: "chaos"},
	}

	out := captureStdout(t, func() { printPrettySummary(report) })

	if !strings.Contains(out, "Session: empty-session") {
		t.Errorf("expected session header in output, got:\n%s", out)
	}
	if strings.Contains(out, "Top Actors") {
		t.Error("expected Top Actors section to be omitted when ByActor is empty")
	}
	if strings.Contains(out, "Status Codes") {
		t.Error("expected Status Codes section to be omitted when StatusCodes is empty")
	}
	if strings.Contains(out, "success-rate=NaN") {
		t.Error("expected zero-request success rate to avoid division by zero")
	}
}

func TestWriteReportWritesValidJSON(t *testing.T) {
	report := sampleSummary()
	dir := t.TempDir()
	path := filepath.Join(dir, "report.json")

	if err := writeReport(path, report); err != nil {
		t.Fatalf("writeReport failed: %v", err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("failed to read report: %v", err)
	}
	var decoded summary
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("report file is not valid JSON: %v", err)
	}
	if decoded.SessionID != report.SessionID {
		t.Errorf("decoded sessionId = %q, want %q", decoded.SessionID, report.SessionID)
	}
}

func TestWriteReportPropagatesWriteError(t *testing.T) {
	report := sampleSummary()
	// A path inside a non-existent directory should fail to write.
	path := filepath.Join(t.TempDir(), "missing-dir", "report.json")

	if err := writeReport(path, report); err == nil {
		t.Fatal("expected writeReport to return an error for an unwritable path")
	}
}
