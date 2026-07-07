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

func TestScenarioSmokeLiveAssertionsCheckCommandAndOwnOrderState(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/history":
			participantID := r.URL.Query().Get("participantId")
			orderID := map[string]string{
				"HIDDEN_SELLER_A": "p1_golden_hidden_cross_t1-ord-001",
				"VISIBLE_BUYER_B": "p1_golden_hidden_cross_t1-ord-002",
				"VISIBLE_BUYER_C": "p1_golden_hidden_cross_t1-ord-003",
			}[participantID]
			if orderID == "" {
				http.NotFound(w, r)
				return
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"meta":{"source":"runtime.orders + runtime.order_lifecycle_state","freshness":"dirty-tracked lifecycle projection"},"orders":[{"orderId":"` + orderID + `","status":"FILLED"}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-assert-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
	}, &stdout, server.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if !report.Pass || len(report.Failures) != 0 || len(report.Errors) != 0 {
		t.Fatalf("unexpected failed assertion report: %+v", report)
	}
	if len(report.Commands) != 3 {
		t.Fatalf("commands: got %d want 3", len(report.Commands))
	}
	if len(report.Reads) != 3 {
		t.Fatalf("reads: got %d want 3", len(report.Reads))
	}
	if len(report.Assertions) != 6 {
		t.Fatalf("assertions: got %d want 6", len(report.Assertions))
	}
	for _, assertion := range report.Assertions {
		if assertion.Status != "pass" {
			t.Fatalf("assertion did not pass: %+v", assertion)
		}
	}
}

func TestScenarioSmokeLiveAssertionsFailOnMissingOwnOrderState(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/history":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"meta":{"source":"runtime.orders + runtime.order_lifecycle_state","freshness":"dirty-tracked lifecycle projection"},"orders":[]}`))
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
		"--assertions",
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected assertion failure")
	}
	if !strings.Contains(err.Error(), "live scenario smoke failed") {
		t.Fatalf("unexpected error: %v", err)
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if report.Pass || len(report.Failures) == 0 {
		t.Fatalf("expected failures in report: %+v", report)
	}
}

func TestScenarioSmokeLiveAssertionsAttachP2SettlementFacts(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t)
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--settlement-facts-report", settlementPath,
	}, &stdout, server.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if !report.Pass || len(report.Failures) != 0 || len(report.Errors) != 0 {
		t.Fatalf("unexpected failed assertion report: %+v", report)
	}
	if !hasAssertion(report, "p2-no-direct-resolution-without-repair", "pass") {
		t.Fatalf("missing settlement causation assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-settlement-causation-fields", "pass") {
		t.Fatalf("missing settlement causation field assertion: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnP2SettlementFactsWithoutRepairLink(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t)
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--settlement-facts-report", settlementPath,
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected settlement fact causation failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p2-no-direct-resolution-without-repair") {
		t.Fatalf("expected repair linkage failure: %+v", report.Failures)
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

func TestScenarioSmokeAssertionsRequireLive(t *testing.T) {
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--assertions",
	}, &bytes.Buffer{}, nil)
	if err == nil {
		t.Fatal("expected --assertions without --live to fail")
	}
	if !strings.Contains(err.Error(), "--assertions requires --live") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestScenarioSmokeSettlementFactsReportRequiresAssertions(t *testing.T) {
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--live",
		"--settlement-facts-report", filepath.Join(t.TempDir(), "settlement.json"),
	}, &bytes.Buffer{}, nil)
	if err == nil {
		t.Fatal("expected --settlement-facts-report without --assertions to fail")
	}
	if !strings.Contains(err.Error(), "--settlement-facts-report requires --assertions") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func p2SettlementServer(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		default:
			http.NotFound(w, r)
		}
	}))
}

func hasAssertion(report smokeReport, assertionID string, status string) bool {
	for _, assertion := range report.Assertions {
		if assertion.ID == assertionID && assertion.Status == status {
			return true
		}
	}
	return false
}

func hasFailure(report smokeReport, assertionID string) bool {
	for _, failure := range report.Failures {
		if failure.AssertionID == assertionID {
			return true
		}
	}
	return false
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
