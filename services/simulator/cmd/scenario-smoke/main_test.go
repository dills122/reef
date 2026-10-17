package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestScenarioSmokeDryRunPrintsExecutableRequests(t *testing.T) {
	t.Setenv("ADMIN_API_TOKEN", "test-token")
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
	if report.SeedRequests[0].Path != "/admin/v1/reference/instruments" {
		t.Fatalf("unexpected first seed request: %+v", report.SeedRequests[0])
	}
	if report.SeedRequests[0].Headers["X-Reef-Actor-Id"] != "scenario-smoke-seed" {
		t.Fatalf("missing admin seed actor header: %+v", report.SeedRequests[0].Headers)
	}
	if _, found := report.SeedRequests[0].Headers["Authorization"]; found {
		t.Fatalf("authorization header leaked into report: %+v", report.SeedRequests[0].Headers)
	}
	if first.Headers["Idempotency-Key"] != first.Payload["commandId"] {
		t.Fatalf("idempotency header mismatch: %+v", first.Headers)
	}
	if first.Headers["X-Participant-Id"] != first.Payload["participantId"] {
		t.Fatalf("participant header mismatch: %+v", first.Headers)
	}
	if first.Headers["X-Reef-Actor-Id"] != first.Payload["actorId"] {
		t.Fatalf("actor header mismatch: %+v", first.Headers)
	}
}

func TestScenarioSmokeDryRunCanScopeCommandIDsToRun(t *testing.T) {
	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1 replay/run 01",
		"--run-scoped-command-ids",
	}, &stdout, nil)
	if err != nil {
		t.Fatalf("run error: %v", err)
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("smoke json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if len(report.Requests) != 3 {
		t.Fatalf("requests: got %d want 3", len(report.Requests))
	}
	first := report.Requests[0]
	expected := "p1-replay-run-01-p1_golden_hidden_cross_t1-cmd-001"
	if first.Payload["commandId"] != expected {
		t.Fatalf("unexpected scoped command id: got %q want %q", first.Payload["commandId"], expected)
	}
	if first.Headers["Idempotency-Key"] != expected {
		t.Fatalf("idempotency key did not follow scoped command id: %+v", first.Headers)
	}
	if first.Payload["scenarioRunId"] != "p1 replay/run 01" || first.Payload["runId"] != "p1 replay/run 01" {
		t.Fatalf("scenario run metadata was rewritten: %+v", first.Payload)
	}
	if first.Payload["orderId"] != "p1_golden_hidden_cross_t1-ord-001" {
		t.Fatalf("order id should remain contract-stable: %+v", first.Payload)
	}
}

func TestRunLiveDoesNotMutateCallerClientTimeout(t *testing.T) {
	client := &http.Client{Timeout: 123 * time.Millisecond}
	report := &smokeReport{}

	runLive(config{timeout: 5 * time.Second}, client, report)

	if client.Timeout != 123*time.Millisecond {
		t.Fatalf("caller client timeout mutated: got %s", client.Timeout)
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

func TestScenarioSmokeReplayCheckReportRequiresAssertions(t *testing.T) {
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--live",
		"--replay-check-report", filepath.Join(t.TempDir(), "replay.json"),
	}, &bytes.Buffer{}, nil)
	if err == nil {
		t.Fatal("expected --replay-check-report without --assertions to fail")
	}
	if !strings.Contains(err.Error(), "--replay-check-report requires --assertions") {
		t.Fatalf("unexpected error: %v", err)
	}
}
