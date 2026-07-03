package main

import (
	"encoding/json"
	"testing"
	"time"
)

func TestBuildSubmitPayloadIncludesApiV1RequiredFields(t *testing.T) {
	cfg := defaultConfig()
	cfg.RunID = "run-1"
	cfg.RunKind = "intake-bench"
	cfg.ScenarioID = "raw-intake"
	body := buildSubmitPayload(cfg, "cmd-1", "trace-1", "order-1", 7)

	var payload map[string]string
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatalf("payload did not unmarshal: %v", err)
	}

	required := []string{
		"commandId",
		"traceId",
		"correlationId",
		"actorId",
		"occurredAt",
		"orderId",
		"instrumentId",
		"participantId",
		"accountId",
		"side",
		"orderType",
		"quantityUnits",
		"limitPrice",
		"currency",
		"timeInForce",
	}
	for _, field := range required {
		if payload[field] == "" {
			t.Fatalf("missing required field %s in payload %#v", field, payload)
		}
	}
	if payload["commandId"] != "cmd-1" || payload["traceId"] != "trace-1" || payload["orderId"] != "order-1" {
		t.Fatalf("payload identifiers were not preserved: %#v", payload)
	}
	if payload["actorId"] != "bot-7" {
		t.Fatalf("unexpected actorId: %s", payload["actorId"])
	}
	if payload["runId"] != "run-1" || payload["runKind"] != "intake-bench" || payload["scenarioId"] != "raw-intake" {
		t.Fatalf("unexpected run metadata: %#v", payload)
	}
}

func TestBuildReportComputesThroughputAndLatency(t *testing.T) {
	cfg := defaultConfig()
	started := time.Unix(10, 0)
	finished := started.Add(2 * time.Second)
	results := []requestResult{
		{Success: true, StatusCode: 202, Latency: 10 * time.Millisecond},
		{Success: true, StatusCode: 202, Latency: 30 * time.Millisecond},
		{Success: false, StatusCode: 503, Latency: 20 * time.Millisecond, ErrorText: "http_503"},
	}

	report := buildReport("session-1", cfg, started, finished, results)

	if report.SessionID != "session-1" {
		t.Fatalf("unexpected session id: %s", report.SessionID)
	}
	if report.Requests != 3 || report.Success != 2 || report.Failures != 1 {
		t.Fatalf("unexpected totals: %#v", report)
	}
	if report.ThroughputRPS != 1.5 || report.AcceptedRPS != 1.0 {
		t.Fatalf("unexpected throughput: throughput=%f accepted=%f", report.ThroughputRPS, report.AcceptedRPS)
	}
	if report.StatusCodes[202] != 2 || report.StatusCodes[503] != 1 {
		t.Fatalf("unexpected status code counts: %#v", report.StatusCodes)
	}
	if report.LatencyMs.P50 != 20 {
		t.Fatalf("unexpected p50 latency: %f", report.LatencyMs.P50)
	}
}

func TestParseConfigRejectsInvalidRateSchedule(t *testing.T) {
	if _, err := parseConfig([]string{"--rate-schedule", "burst"}); err == nil {
		t.Fatal("expected invalid rate schedule to fail")
	}
}
