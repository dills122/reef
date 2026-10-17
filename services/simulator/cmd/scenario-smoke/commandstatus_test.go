package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestCommandStatusScopeIgnoresCapturedResponsePayload(t *testing.T) {
	status := commandStatusBody{
		ResponsePayloadJSON: `{"accepted":{"orderId":"order-1"}}`,
	}
	if commandStatusHasCanonicalScopeEvidence(status) {
		t.Fatal("captured synchronous response must not be treated as canonical scope evidence")
	}
}

func TestScenarioSmokeLiveAssertionsPassOnScopedCanonicalCommandStatus(t *testing.T) {
	report := smokeReport{
		ScenarioRunID: "p1-current-run",
		Results: []smokeResult{{
			Sequence:          1,
			Command:           "submit-sell-hidden",
			Path:              "/api/v1/orders",
			CommandStatusCode: 200,
			CommandStatusBody: `{"commandId":"cmd-001","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome","eventStream":"REEF_EVENTS_CURRENT","responsePayloadJson":"{\"acceptedOrder\":{\"runId\":\"p1-current-run\"}}"}`,
		}},
	}

	runAssertions(config{}, nil, &report)

	if hasFailure(report, "command-001-scenario-run") {
		t.Fatalf("unexpected scenario scope failure: %+v", report.Failures)
	}
	if !hasAssertion(report, "command-001-scenario-run", "pass") {
		t.Fatalf("expected scenario scope assertion: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnStaleCanonicalCommandStatusRun(t *testing.T) {
	report := smokeReport{
		ScenarioRunID: "p1-current-run",
		Results: []smokeResult{{
			Sequence:          1,
			Command:           "submit-sell-hidden",
			Path:              "/api/v1/orders",
			CommandStatusCode: 200,
			CommandStatusBody: `{"commandId":"cmd-001","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome","eventStream":"REEF_EVENTS_PREVIOUS","responsePayloadJson":"{\"acceptedOrder\":{\"runId\":\"p1-previous-run\"}}"}`,
		}},
	}

	runAssertions(config{}, nil, &report)

	if !hasFailure(report, "command-001-scenario-run") {
		t.Fatalf("expected stale scenario scope failure: %+v", report.Failures)
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

func TestScenarioSmokeLiveAssertionsFailFastWhenCommandStatusLookupUnavailable(t *testing.T) {
	posts := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"ok","dependencies":{"commandStatusLookup":false}}`))
		case r.Method == http.MethodPost:
			posts++
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
		"--assertions",
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected command status readiness failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("report json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if posts != 0 {
		t.Fatalf("expected fail-fast before posts, got %d posts", posts)
	}
	if len(report.Errors) != 1 || !strings.Contains(report.Errors[0], "commandStatusLookup=false") {
		t.Fatalf("expected command status lookup error, got %+v", report.Errors)
	}
}
