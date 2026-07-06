package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
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

func TestErrorText(t *testing.T) {
	if got := errorText(200, nil, errors.New("boom")); got != "boom" {
		t.Errorf("errorText with err = %q", got)
	}
	if got := errorText(202, []byte(`{"accepted":true}`), nil); got != "" {
		t.Errorf("errorText success no body issue = %q", got)
	}
	if got := errorText(202, []byte(`{"rejected":true}`), nil); got != `http_202:{"rejected":true}` {
		t.Errorf("errorText rejected-in-2xx = %q", got)
	}
	if got := errorText(500, nil, nil); got != "http_500" {
		t.Errorf("errorText empty body = %q", got)
	}
	longBody := make([]byte, 250)
	for i := range longBody {
		longBody[i] = 'x'
	}
	got := errorText(500, longBody, nil)
	if got != "http_500:"+string(longBody[:180]) {
		t.Errorf("errorText truncation wrong, len=%d", len(got))
	}
}

func TestRateChannelDepth(t *testing.T) {
	cfg := defaultConfig()
	cfg.Workers = 5
	cfg.RateSchedule = rateSchedulePrecise
	if got := rateChannelDepth(cfg); got != 10 {
		t.Errorf("rateChannelDepth precise = %d, want 10", got)
	}
	cfg.RateSchedule = rateScheduleDrop
	if got := rateChannelDepth(cfg); got != 1 {
		t.Errorf("rateChannelDepth drop = %d, want 1", got)
	}
}

func TestValidRateSchedule(t *testing.T) {
	if !validRateSchedule("drop") || !validRateSchedule("PRECISE") || !validRateSchedule(" precise ") {
		t.Error("expected known schedules to validate")
	}
	if validRateSchedule("burst") {
		t.Error("expected unknown schedule to be invalid")
	}
}

func TestMaxInt(t *testing.T) {
	if maxInt(3, 5) != 5 {
		t.Error("maxInt(3,5) should be 5")
	}
	if maxInt(5, 3) != 5 {
		t.Error("maxInt(5,3) should be 5")
	}
}

func TestTokenFeederSchedulesSelectAppropriateFeeder(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	out := make(chan struct{}, 10)
	tokenFeeder(ctx, 200, rateSchedulePrecise, out)
	if len(out) == 0 {
		t.Error("expected precise feeder to emit tokens")
	}

	ctx2, cancel2 := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel2()
	out2 := make(chan struct{}, 10)
	tokenFeeder(ctx2, 200, rateScheduleDrop, out2)
	if len(out2) == 0 {
		t.Error("expected drop feeder to emit tokens")
	}
}

func withEnv(t *testing.T, key, value string) {
	t.Helper()
	original, had := os.LookupEnv(key)
	if value == "" {
		os.Unsetenv(key)
	} else {
		os.Setenv(key, value)
	}
	t.Cleanup(func() {
		if had {
			os.Setenv(key, original)
		} else {
			os.Unsetenv(key)
		}
	})
}

func TestEnvOr(t *testing.T) {
	withEnv(t, "IB_TEST_STR", "")
	if got := envOr("IB_TEST_STR", "fallback"); got != "fallback" {
		t.Errorf("envOr fallback = %q", got)
	}
	withEnv(t, "IB_TEST_STR", "value")
	if got := envOr("IB_TEST_STR", "fallback"); got != "value" {
		t.Errorf("envOr set = %q", got)
	}
}

func TestEnvIntHelper(t *testing.T) {
	withEnv(t, "IB_TEST_INT", "")
	if got := envInt("IB_TEST_INT", 9); got != 9 {
		t.Errorf("envInt fallback = %d", got)
	}
	withEnv(t, "IB_TEST_INT", "42")
	if got := envInt("IB_TEST_INT", 9); got != 42 {
		t.Errorf("envInt parsed = %d", got)
	}
	withEnv(t, "IB_TEST_INT", "nope")
	if got := envInt("IB_TEST_INT", 9); got != 9 {
		t.Errorf("envInt invalid fallback = %d", got)
	}
}

func TestEnvBoolHelper(t *testing.T) {
	withEnv(t, "IB_TEST_BOOL", "")
	if got := envBool("IB_TEST_BOOL", true); got != true {
		t.Errorf("envBool fallback = %v", got)
	}
	withEnv(t, "IB_TEST_BOOL", "false")
	if got := envBool("IB_TEST_BOOL", true); got != false {
		t.Errorf("envBool parsed = %v", got)
	}
	withEnv(t, "IB_TEST_BOOL", "nope")
	if got := envBool("IB_TEST_BOOL", true); got != true {
		t.Errorf("envBool invalid fallback = %v", got)
	}
}

func TestEnvDurationHelper(t *testing.T) {
	withEnv(t, "IB_TEST_DUR", "")
	if got := envDuration("IB_TEST_DUR", 5*time.Second); got != 5*time.Second {
		t.Errorf("envDuration fallback = %v", got)
	}
	withEnv(t, "IB_TEST_DUR", "10s")
	if got := envDuration("IB_TEST_DUR", 5*time.Second); got != 10*time.Second {
		t.Errorf("envDuration parsed = %v", got)
	}
	withEnv(t, "IB_TEST_DUR", "nope")
	if got := envDuration("IB_TEST_DUR", 5*time.Second); got != 5*time.Second {
		t.Errorf("envDuration invalid fallback = %v", got)
	}
}

func TestBuildHTTPClient(t *testing.T) {
	cfg := defaultConfig()
	cfg.RequestTimeout = 7 * time.Second
	client := buildHTTPClient(cfg)
	if client.Timeout != 7*time.Second {
		t.Errorf("buildHTTPClient timeout = %v, want 7s", client.Timeout)
	}
	if client.Transport == nil {
		t.Error("expected non-nil transport")
	}
}

func TestPrintReport(t *testing.T) {
	cfg := defaultConfig()
	cfg.PrettySummary = true
	started := time.Unix(10, 0)
	finished := started.Add(time.Second)
	report := buildReport("session-1", cfg, started, finished, []requestResult{
		{Success: true, StatusCode: 202, Latency: 5 * time.Millisecond},
	})
	printReport(report)

	cfg.PrettySummary = false
	report2 := buildReport("session-2", cfg, started, finished, []requestResult{
		{Success: true, StatusCode: 202, Latency: 5 * time.Millisecond},
	})
	printReport(report2)
}
