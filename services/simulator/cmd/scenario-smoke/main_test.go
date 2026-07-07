package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
)

func TestScenarioSmokeDryRunPrintsExecutableRequests(t *testing.T) {
	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-smoke-dry",
	}, &stdout, nil)
	if err != nil {
		t.Fatalf("run error: %v", err)
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("smoke json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if report.Mode != "dry-run" || !report.Pass {
		t.Fatalf("unexpected dry-run report: %+v", report)
	}
	if len(report.Requests) != 3 {
		t.Fatalf("requests: got %d want 3", len(report.Requests))
	}
	if len(report.SeedRequests) != 11 {
		t.Fatalf("seed requests: got %d want 11", len(report.SeedRequests))
	}
	first := report.Requests[0]
	if first.Path != "/api/v1/orders/submit" || first.Payload["scenarioRunId"] != "p1-smoke-dry" {
		t.Fatalf("unexpected first executable request: %+v", first)
	}
	if first.Headers["Idempotency-Key"] != first.Payload["commandId"] {
		t.Fatalf("idempotency header mismatch: %+v", first.Headers)
	}
}

func TestScenarioSmokeLivePostsSeedAndExecutableRequests(t *testing.T) {
	var mu sync.Mutex
	posts := make([]string, 0)
	statusCommands := map[string]bool{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		switch {
		case r.Method == http.MethodPost:
			posts = append(posts, r.URL.Path)
			if r.URL.Path == "/api/v1/orders/submit" {
				var payload map[string]string
				if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
					t.Fatalf("decode submit payload: %v", err)
				}
				if r.Header.Get("Idempotency-Key") != payload["commandId"] {
					t.Fatalf("missing idempotency key for %s", payload["commandId"])
				}
				if payload["scenarioRunId"] != "p1-smoke-live" || payload["seed"] != "424242" {
					t.Fatalf("missing scenario metadata: %+v", payload)
				}
				statusCommands[payload["commandId"]] = true
				w.WriteHeader(http.StatusAccepted)
				_, _ = w.Write([]byte(`{"status":"accepted"}`))
				return
			}
			if r.Header.Get("X-Reef-Internal-Route") == "" {
				t.Fatalf("missing internal seed header for %s", r.URL.Path)
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"ok"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			if !statusCommands[commandID] {
				http.NotFound(w, r)
				return
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-smoke-live",
		"--base-url", server.URL,
		"--live",
	}, &stdout, server.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("smoke json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if report.Mode != "live" || !report.Pass || len(report.Errors) != 0 {
		t.Fatalf("unexpected live report: %+v", report)
	}
	if len(report.Results) != len(report.SeedRequests)+len(report.Requests) {
		t.Fatalf("results: got %d want %d", len(report.Results), len(report.SeedRequests)+len(report.Requests))
	}
	submits := 0
	for _, path := range posts {
		if path == "/api/v1/orders/submit" {
			submits++
		}
	}
	if submits != 3 {
		t.Fatalf("submit posts: got %d want 3; posts=%v", submits, posts)
	}
}

func TestScenarioSmokeLiveFailsWhenCommandStatusMissing(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--base-url", server.URL,
		"--live",
		"--seed-reference=false",
		"--status-timeout", "1ms",
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected live smoke failure")
	}
	if !strings.Contains(err.Error(), "live scenario smoke failed") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestScenarioSmokeWritesReportOut(t *testing.T) {
	reportPath := filepath.Join(t.TempDir(), "p1-smoke.json")
	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-smoke-report",
		"--report-out", reportPath,
	}, &stdout, nil)
	if err != nil {
		t.Fatalf("run error: %v", err)
	}
	written, err := os.ReadFile(reportPath)
	if err != nil {
		t.Fatalf("read report-out: %v", err)
	}
	if !bytes.Equal(stdout.Bytes(), written) {
		t.Fatalf("report-out differs from stdout\nstdout=%s\nfile=%s", stdout.String(), string(written))
	}
	var report smokeReport
	if err := json.Unmarshal(written, &report); err != nil {
		t.Fatalf("report-out json did not unmarshal: %v", err)
	}
	if report.ScenarioRunID != "p1-smoke-report" || !report.Pass {
		t.Fatalf("unexpected report-out: %+v", report)
	}
}

func TestScenarioSmokeGoldenReportMatchesGeneratedDryRun(t *testing.T) {
	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-golden-hidden-cross-golden",
		"--start", defaultScenarioStart,
		"--pretty",
	}, &stdout, nil)
	if err != nil {
		t.Fatalf("run error: %v", err)
	}
	golden, err := os.ReadFile(filepath.Join(simulatorRoot(t), "replay/golden/p1-golden-hidden-cross.smoke.json"))
	if err != nil {
		t.Fatalf("read golden smoke report: %v", err)
	}
	if !bytes.Equal(stdout.Bytes(), golden) {
		t.Fatalf("golden smoke report differs\nwant=%s\ngot=%s", string(golden), stdout.String())
	}
}

func TestScenarioSmokeRequiresScenario(t *testing.T) {
	err := run(nil, &bytes.Buffer{}, nil)
	if err == nil {
		t.Fatal("expected missing scenario to fail")
	}
	if !strings.Contains(err.Error(), "missing --scenario") {
		t.Fatalf("expected missing --scenario error, got %v", err)
	}
}

func scenarioDefinitionsRoot(t *testing.T) string {
	return filepath.Join(simulatorRoot(t), "../../packages/scenario-definitions/scenarios/v1")
}

func simulatorRoot(t *testing.T) string {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(currentFile), "../.."))
}
