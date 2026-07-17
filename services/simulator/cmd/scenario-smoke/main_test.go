package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
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
			if !strings.HasPrefix(r.URL.Path, "/admin/v1/") {
				t.Fatalf("seed request did not use admin gateway for %s", r.URL.Path)
			}
			if r.Header.Get("X-Reef-Actor-Id") == "" {
				t.Fatalf("missing admin seed actor header for %s", r.URL.Path)
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"ok"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			if r.Header.Get("X-Client-Id") == "" {
				http.Error(w, "missing X-Client-Id", http.StatusUnauthorized)
				return
			}
			if r.Header.Get("X-Participant-Id") == "" {
				http.Error(w, "missing X-Participant-Id", http.StatusForbidden)
				return
			}
			if r.Header.Get("X-Reef-Actor-Id") == "" {
				http.Error(w, "missing X-Reef-Actor-Id", http.StatusForbidden)
				return
			}
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
	var eventMu sync.Mutex
	var events []string
	recordEvent := func(event string) {
		eventMu.Lock()
		defer eventMu.Unlock()
		events = append(events, event)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
		case r.Method == http.MethodPost:
			if r.URL.Path == "/api/v1/orders/submit" {
				recordEvent("submit")
			}
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
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSON(orderID)))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnFillsJSON(r.URL.Query().Get("participantId"))))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1DataAvailabilityJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			recordEvent("depth")
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
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
	if len(report.Reads) != 10 {
		t.Fatalf("reads: got %d want 10", len(report.Reads))
	}
	if len(report.Assertions) != 26 {
		t.Fatalf("assertions: got %d want 26", len(report.Assertions))
	}
	if len(report.ProjectionLag) == 0 {
		t.Fatalf("expected projection lag rows")
	}
	if report.VisibilityTimeline == nil || len(report.VisibilityTimeline.PublicDepthChecks) != 1 {
		t.Fatalf("expected one hidden-depth timeline check: %+v", report.VisibilityTimeline)
	}
	if report.VisibilityTimeline.PublicDepthHiddenRestingExposed {
		t.Fatalf("hidden resting depth should not be exposed: %+v", report.VisibilityTimeline)
	}
	if !hasAssertion(report, "p1-hidden-depth-timeline-proof", "pass") {
		t.Fatalf("missing hidden-depth timeline assertion: %+v", report.Assertions)
	}
	eventMu.Lock()
	gotEvents := append([]string(nil), events...)
	eventMu.Unlock()
	if strings.Join(gotEvents, ",") != "submit,depth,submit,submit,depth" {
		t.Fatalf("unexpected P1 submit/depth timing: %v", gotEvents)
	}
	for _, assertion := range report.Assertions {
		if assertion.Status != "pass" {
			t.Fatalf("assertion did not pass: %+v", assertion)
		}
	}
}

func TestScenarioSmokeLiveAssertionsFailOnP1ReadSurfaceProjectionLag(t *testing.T) {
	var availability dataAvailabilityBody
	if err := json.Unmarshal([]byte(p1DataAvailabilityJSON()), &availability); err != nil {
		t.Fatalf("unmarshal availability: %v", err)
	}
	for index := range availability.Surfaces {
		if availability.Surfaces[index].Name == "orderHistory" {
			availability.Surfaces[index].Lag = 2
		}
	}
	report := smokeReport{}
	assertP1DataAvailability(&report, "/api/v1/data/availability", availability, true)

	if !hasFailure(report, "p1-read-surface-projection-lag-zero") {
		t.Fatalf("expected projection lag failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsUseSplitStatusAndReadBaseURLs(t *testing.T) {
	submitPosts := 0
	submitServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/orders/submit" {
			http.NotFound(w, r)
			return
		}
		submitPosts++
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"status":"accepted"}`))
	}))
	defer submitServer.Close()

	statusReadServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"status":"ok","pipeline":{"commandStatusSources":["canonical_outcome"]},"dependencies":{"commandStatusLookup":false}}`))
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
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSON(orderID)))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnFillsJSON(r.URL.Query().Get("participantId"))))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1DataAvailabilityJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer statusReadServer.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-split-live",
		"--base-url", submitServer.URL,
		"--status-base-url", statusReadServer.URL,
		"--read-base-url", statusReadServer.URL,
		"--live",
		"--assertions",
		"--seed-reference=false",
	}, &stdout, statusReadServer.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}
	if submitPosts != 3 {
		t.Fatalf("submit posts: got %d want 3", submitPosts)
	}
	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if !report.Pass || len(report.Failures) != 0 || len(report.Errors) != 0 {
		t.Fatalf("unexpected failed assertion report: %+v", report)
	}
	if !hasAssertion(report, "command-001-completed", "pass") || !hasAssertion(report, "p1-trade-tape-count", "pass") {
		t.Fatalf("missing split-base-url assertions: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnFilledQuantityMismatch(t *testing.T) {
	server := p1AssertionServer(t, p1AssertionServerOptions{
		filledQuantityByOrderID: map[string]string{
			"p1_golden_hidden_cross_t1-ord-002": "39",
		},
	})
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--base-url", server.URL,
		"--live",
		"--assertions",
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected filled quantity assertion failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-first-visible-buy-filled-quantity") {
		t.Fatalf("expected p1-first-visible-buy-filled-quantity failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnReadSurfaceSourceMismatch(t *testing.T) {
	server := p1AssertionServer(t, p1AssertionServerOptions{
		dataAvailabilityJSON: p1DataAvailabilityJSONWithOrderHistorySource("runtime.orders"),
	})
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--base-url", server.URL,
		"--live",
		"--assertions",
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected read surface source assertion failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-order-history-source-declared") {
		t.Fatalf("expected p1-order-history-source-declared failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnPublicTradeIdentityLeak(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
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
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSON(orderID)))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnFillsJSON(r.URL.Query().Get("participantId"))))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1DataAvailabilityJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeWithPublicIdentityLeakJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
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
		t.Fatal("expected public trade identity leak failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-trade-tape-public-safe") {
		t.Fatalf("expected p1-trade-tape-public-safe failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnOwnFillCounterpartyLeak(t *testing.T) {
	server := p1AssertionServer(t, p1AssertionServerOptions{
		ownFillsJSONByParticipant: map[string]string{
			"VISIBLE_BUYER_B": `{"meta":{"source":"runtime.orders + runtime.executions","freshness":"durable execution rows scoped by participant order ownership"},"fills":[{"executionId":"exec-p1-001","orderId":"p1_golden_hidden_cross_t1-ord-002","instrumentId":"XYZ","side":"BUY","quantityUnits":"40","executionPrice":"100000000000","currency":"USD","counterpartyParticipantId":"HIDDEN_SELLER_A","occurredAt":"2026-03-14T18:00:03Z"}]}`,
		},
	})
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--base-url", server.URL,
		"--live",
		"--assertions",
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected own fill counterparty leak failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-own-fills-counterparty-safe") {
		t.Fatalf("expected p1-own-fills-counterparty-safe failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnPublicHiddenDepth(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
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
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSON(orderID)))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnFillsJSON(r.URL.Query().Get("participantId"))))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1DataAvailabilityJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"depth":{"projectionName":"market-data-depth","instrumentId":"XYZ","bidLevels":[],"askLevels":[{"price":"100000000000","quantity":"100"}]}}`))
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
		t.Fatal("expected public hidden depth failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-public-depth-hidden-size-not-visible") {
		t.Fatalf("expected p1-public-depth-hidden-size-not-visible failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsAttachReplayChecksumEvidence(t *testing.T) {
	replayPath := filepath.Join(t.TempDir(), "replay-check.json")
	replayJSON := p1CleanReplayCheckJSON()
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
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
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSON(orderID)))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnFillsJSON(r.URL.Query().Get("participantId"))))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1DataAvailabilityJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-replay-assert-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--replay-check-report", replayPath,
		"--require-replay-check",
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
	if len(report.ArtifactPaths) != 1 || report.ArtifactPaths[0] != replayPath {
		t.Fatalf("artifact paths: %+v", report.ArtifactPaths)
	}
	if report.ReplayChecksum["pass"] != true {
		t.Fatalf("missing replay checksum pass: %+v", report.ReplayChecksum)
	}
	if !hasAssertion(report, "p1-replay-checksum-clean", "pass") {
		t.Fatalf("missing replay checksum assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p1-replay-stored-command-count", "pass") {
		t.Fatalf("missing replay counter assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p1-replay-hidden-depth-timeline-proof", "pass") {
		t.Fatalf("missing hidden-depth replay timeline assertion: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsRequireHiddenDepthReplayProof(t *testing.T) {
	tempDir := t.TempDir()
	replayPath := filepath.Join(tempDir, "replay-check.json")
	replayJSON := `{"pass":true,"checkedAt":"2026-03-14T18:00:30Z","report":{"batchCount":1,"storedCommandCount":3,"payloadOutcomeCount":3,"canonicalOutcomeCount":3,"duplicateReplayInserted":0,"checksumMismatchCount":0,"batchCommandCountMismatchCount":0,"payloadHashMismatchCount":0,"missingOutcomeCount":0,"extraOutcomeCount":0,"streamGapCount":0,"streamOverlapCount":0,"watermarkLagCount":0},"failures":[]}`
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}
	reportPath := filepath.Join(tempDir, "scenario-report.json")
	reportJSON := `{"mode":"live","pass":true,"pathId":"P1_GOLDEN_HIDDEN_CROSS_T1","scenarioRunId":"p1-missing-timeline","seed":0,"requests":[],"results":[]}`
	if err := os.WriteFile(reportPath, []byte(reportJSON), 0o644); err != nil {
		t.Fatalf("write scenario report: %v", err)
	}

	var stdout bytes.Buffer
	err := run([]string{
		"--attach-replay-to-report", reportPath,
		"--replay-check-report", replayPath,
		"--require-replay-check",
	}, &stdout, nil)
	if err == nil {
		t.Fatal("expected hidden-depth replay proof failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-replay-hidden-depth-timeline-proof") {
		t.Fatalf("expected p1-replay-hidden-depth-timeline-proof failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeReplayAttachmentUsesReportVisibilityTimeline(t *testing.T) {
	tempDir := t.TempDir()
	replayPath := filepath.Join(tempDir, "replay-check.json")
	replayJSON := `{"pass":true,"checkedAt":"2026-03-14T18:00:30Z","report":{"batchCount":1,"storedCommandCount":3,"payloadOutcomeCount":3,"canonicalOutcomeCount":3,"duplicateReplayInserted":0,"checksumMismatchCount":0,"batchCommandCountMismatchCount":0,"payloadHashMismatchCount":0,"missingOutcomeCount":0,"extraOutcomeCount":0,"streamGapCount":0,"streamOverlapCount":0,"watermarkLagCount":0},"failures":[]}`
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}
	reportPath := filepath.Join(tempDir, "scenario-report.json")
	reportJSON := `{"mode":"live","pass":true,"pathId":"P1_GOLDEN_HIDDEN_CROSS_T1","scenarioRunId":"p1-report-timeline","seed":0,"requests":[],"results":[],"visibilityTimeline":{"scenarioId":"P1_GOLDEN_HIDDEN_CROSS_T1","scenarioRunId":"p1-report-timeline","publicDepthHiddenRestingExposed":false,"publicDepthChecks":[{"phase":"after-hidden-resting-before-first-execution","instrumentId":"XYZ","price":"100000000000","hiddenRestingQuantityVisible":false,"statusCode":404,"observed":"no public hidden resting sell level"}]}}`
	if err := os.WriteFile(reportPath, []byte(reportJSON), 0o644); err != nil {
		t.Fatalf("write scenario report: %v", err)
	}

	var stdout bytes.Buffer
	err := run([]string{
		"--attach-replay-to-report", reportPath,
		"--replay-check-report", replayPath,
		"--require-replay-check",
	}, &stdout, nil)
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasAssertion(report, "p1-replay-hidden-depth-timeline-proof", "pass") {
		t.Fatalf("missing replay hidden-depth timeline assertion: %+v", report.Assertions)
	}
	if _, ok := report.ReplayChecksum["visibilityTimeline"].(map[string]any); !ok {
		t.Fatalf("expected replay checksum to include attached visibility timeline: %+v", report.ReplayChecksum)
	}
}

func TestScenarioSmokeAttachReplayUsesP2AssertionPrefix(t *testing.T) {
	tempDir := t.TempDir()
	reportPath := filepath.Join(tempDir, "report.json")
	replayPath := filepath.Join(tempDir, "replay-check.json")
	reportJSON := `{"mode":"live","pass":true,"pathId":"P2_SETTLEMENT_BREAK_REPAIR","scenarioRunId":"p2-report-replay","seed":424243,"requests":[{"sequence":1,"command":"SubmitOrder"},{"sequence":2,"command":"SubmitOrder"}],"results":[],"assertions":[]}`
	replayJSON := `{"pass":true,"report":{"batchCount":2,"storedCommandCount":2,"payloadOutcomeCount":2,"canonicalOutcomeCount":2,"duplicateReplayInserted":0,"checksumMismatchCount":0,"batchCommandCountMismatchCount":0,"payloadHashMismatchCount":0,"missingOutcomeCount":0,"extraOutcomeCount":0,"streamGapCount":0,"streamOverlapCount":0,"watermarkLagCount":0},"failures":[]}`
	if err := os.WriteFile(reportPath, []byte(reportJSON), 0o644); err != nil {
		t.Fatalf("write report: %v", err)
	}
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}

	var stdout bytes.Buffer
	err := run([]string{
		"--attach-replay-to-report", reportPath,
		"--replay-check-report", replayPath,
		"--require-replay-check",
	}, &stdout, nil)
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasAssertion(report, "p2-replay-checksum-clean", "pass") {
		t.Fatalf("missing P2 replay checksum assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-replay-stored-command-count", "pass") {
		t.Fatalf("missing P2 replay counter assertion: %+v", report.Assertions)
	}
	if hasAssertion(report, "p1-replay-checksum-clean", "pass") {
		t.Fatalf("unexpected P1 replay assertion on P2 report: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnReplayChecksumEvidence(t *testing.T) {
	replayPath := filepath.Join(t.TempDir(), "replay-check.json")
	replayJSON := `{"pass":false,"report":{"batchCount":1,"checksumMismatchCount":1},"failures":["batch payload checksum mismatches: 1"]}`
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
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
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSON(orderID)))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnFillsJSON(r.URL.Query().Get("participantId"))))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1DataAvailabilityJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
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
		"--replay-check-report", replayPath,
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected replay checksum assertion failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-replay-checksum-clean") {
		t.Fatalf("expected replay checksum failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsCanRequireReplayChecksumEvidence(t *testing.T) {
	server := p1AssertionServer(t, p1AssertionServerOptions{})
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--require-replay-check",
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected missing replay checksum assertion failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if report.Pass {
		t.Fatalf("expected failed report: %+v", report)
	}
	if !hasFailure(report, "p1-replay-checksum-clean") {
		t.Fatalf("expected replay checksum required failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnMissingOwnOrderState(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
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
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","buyerParticipantId":"buyer-1","sellerParticipantId":"seller-1","instrumentId":"XYZ","quantity":"100","cashAmount":"10000.00","currency":"USD","state":"OBLIGATION_CREATED","occurredAt":"2026-03-14T18:00:04Z"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN","occurredAt":"2026-03-14T18:00:05Z"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR","actorType":"USER","actorId":"ops-1","occurredAt":"2026-03-14T18:00:06Z"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED","occurredAt":"2026-03-14T18:00:07Z"}]
	}`
	server := p2SettlementServer(t, settlementJSON)
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
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
	if !hasAssertion(report, "p2-no-direct-resolution-without-repair", "pass") {
		t.Fatalf("missing settlement causation assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-settlement-causation-fields", "pass") {
		t.Fatalf("missing settlement causation field assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-settlement-chain-linked", "pass") {
		t.Fatalf("missing settlement chain assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-settlement-scope-consistent", "pass") {
		t.Fatalf("missing settlement scope assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-exception-state-resolved", "pass") {
		t.Fatalf("missing exception queue resolved assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-exception-repair-action-matches-reason", "pass") {
		t.Fatalf("missing exception queue repair action assertion: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsAttachP2SecuritySettlementFacts(t *testing.T) {
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","buyerParticipantId":"buyer-1","sellerParticipantId":"seller-1","instrumentId":"XYZ","quantity":"100","cashAmount":"10000.00","currency":"USD","state":"OBLIGATION_CREATED","occurredAt":"2026-03-14T18:00:04Z"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"SECURITY_LEG_FAILED","state":"BROKEN","occurredAt":"2026-03-14T18:00:05Z"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_SECURITY_LEG_REPAIR","actorType":"USER","actorId":"ops-1","occurredAt":"2026-03-14T18:00:06Z"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED","occurredAt":"2026-03-14T18:00:07Z"}]
	}`
	server := p2SettlementServerWithExceptionQueue(
		t,
		settlementJSON,
		p2ResolvedSettlementExceptionQueueJSON("SECURITY_LEG_FAILED", "POST_SECURITY_LEG_REPAIR"),
	)
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
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
	if !hasAssertion(report, "p2-break-reason-security-leg-failed", "pass") {
		t.Fatalf("missing security break reason assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-repair-action-posted", "pass") {
		t.Fatalf("missing security repair action assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-exception-repair-action-matches-reason", "pass") {
		t.Fatalf("missing security exception queue repair action assertion: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsAttachP2OpsRealisticPendingFacts(t *testing.T) {
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","postTradeProfileId":"ops-realistic-v1","postTradePolicyVersion":1,"correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","buyerParticipantId":"buyer-1","sellerParticipantId":"seller-1","instrumentId":"XYZ","quantity":"100","cashAmount":"10000.00","currency":"USD","state":"OBLIGATION_CREATED","occurredAt":"2026-03-14T18:00:04Z"}],
		"allocations":[],
		"confirmations":[],
		"affirmations":[],
		"clearingSubmissions":[],
		"clearingAcceptances":[],
		"clearingRejections":[],
		"novations":[],
		"instructions":[],
		"attempts":[],
		"legOutcomes":[],
		"ledgerEntries":[],
		"settlements":[],
		"breaks":[],
		"repairs":[],
		"resolutions":[]
	}`
	server := p2SettlementServerWithExceptionQueue(t, settlementJSON, p2EmptySettlementExceptionQueueJSON())
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
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
	for _, assertionID := range []string{
		"p2-ops-pending-obligation-created",
		"p2-ops-pending-profile",
		"p2-ops-pending-zero-clearing-submissions",
		"p2-ops-pending-zero-clearing-acceptances",
		"p2-ops-pending-zero-novations",
		"p2-ops-pending-zero-leg-outcomes",
		"p2-ops-pending-zero-ledger-entries",
		"p2-ops-pending-zero-settlements",
		"p2-exception-queue-empty",
	} {
		if !hasAssertion(report, assertionID, "pass") {
			t.Fatalf("missing ops pending assertion %s: %+v", assertionID, report.Assertions)
		}
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

func TestScenarioSmokeLiveAssertionsAttachPostTradeSettlementChain(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"trade-event-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"allocations":[{"settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"obl-1","tradeId":"trade-1","buyOrderId":"buy-order-1","sellOrderId":"sell-order-1","state":"ALLOCATION_PROPOSED"}],
		"confirmations":[{"settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"allocation-1","tradeId":"trade-1","state":"CONFIRMATION_GENERATED"}],
		"affirmations":[{"settlementAffirmationId":"affirmation-1","settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"confirmation-1","tradeId":"trade-1","state":"AFFIRMATION_ACCEPTED"}],
		"clearingSubmissions":[{"settlementClearingSubmissionId":"clearing-submission-1","settlementObligationId":"obl-1","settlementAffirmationId":"affirmation-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"affirmation-1","state":"CLEARING_SUBMITTED"}],
		"clearingAcceptances":[{"settlementClearingAcceptanceId":"clearing-acceptance-1","settlementClearingSubmissionId":"clearing-submission-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"clearing-submission-1","state":"CLEARING_ACCEPTED"}],
		"clearingRejections":[],
		"novations":[{"settlementNovationId":"novation-1","settlementClearingAcceptanceId":"clearing-acceptance-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"clearing-acceptance-1","state":"NOVATION_RECORDED"}],
		"instructions":[{"settlementInstructionId":"instruction-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"novation-1","state":"INSTRUCTION_CREATED"}],
		"attempts":[{"settlementAttemptId":"attempt-1","settlementObligationId":"obl-1","settlementInstructionId":"instruction-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"instruction-1","state":"ATTEMPT_STARTED"}],
		"settlements":[{"settlementId":"settlement-1","settlementObligationId":"obl-1","settlementInstructionId":"instruction-1","settlementAttemptId":"attempt-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"attempt-1","settlementState":"SETTLED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
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
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasAssertion(report, "p2-post-trade-chain-linked", "pass") {
		t.Fatalf("missing post-trade chain assertion: %+v", report.Assertions)
	}
	for _, assertionID := range []string{
		"p2-obligation-state-created",
		"p2-allocation-state-proposed",
		"p2-confirmation-state-generated",
		"p2-affirmation-state-accepted",
		"p2-clearing-state-submitted",
		"p2-clearing-state-accepted",
		"p2-novation-state-recorded",
		"p2-instruction-state-created",
		"p2-attempt-state-started",
		"p2-settlement-state-settled",
	} {
		if !hasAssertion(report, assertionID, "pass") {
			t.Fatalf("missing post-trade state assertion %s: %+v", assertionID, report.Assertions)
		}
	}
}

func TestScenarioSmokeLiveAssertionsFailOnPostTradeSettlementChainWrongState(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"trade-event-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"allocations":[{"settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"obl-1","tradeId":"trade-1","buyOrderId":"buy-order-1","sellOrderId":"sell-order-1","state":"ALLOCATION_SKIPPED"}],
		"confirmations":[{"settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"allocation-1","tradeId":"trade-1","state":"CONFIRMATION_GENERATED"}],
		"affirmations":[{"settlementAffirmationId":"affirmation-1","settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"confirmation-1","tradeId":"trade-1","state":"AFFIRMATION_ACCEPTED"}],
		"clearingSubmissions":[{"settlementClearingSubmissionId":"clearing-submission-1","settlementObligationId":"obl-1","settlementAffirmationId":"affirmation-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"affirmation-1","state":"CLEARING_SUBMITTED"}],
		"clearingAcceptances":[{"settlementClearingAcceptanceId":"clearing-acceptance-1","settlementClearingSubmissionId":"clearing-submission-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"clearing-submission-1","state":"CLEARING_ACCEPTED"}],
		"clearingRejections":[],
		"novations":[{"settlementNovationId":"novation-1","settlementClearingAcceptanceId":"clearing-acceptance-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"clearing-acceptance-1","state":"NOVATION_RECORDED"}],
		"instructions":[{"settlementInstructionId":"instruction-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"novation-1","state":"INSTRUCTION_CREATED"}],
		"attempts":[{"settlementAttemptId":"attempt-1","settlementObligationId":"obl-1","settlementInstructionId":"instruction-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"instruction-1","state":"ATTEMPT_STARTED"}],
		"settlements":[{"settlementId":"settlement-1","settlementObligationId":"obl-1","settlementInstructionId":"instruction-1","settlementAttemptId":"attempt-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"attempt-1","settlementState":"SETTLED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
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
		t.Fatal("expected post-trade settlement state failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p2-allocation-state-proposed") {
		t.Fatalf("expected allocation state failure: %+v", report.Failures)
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
	server := p2SettlementServer(t, "")
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

func TestScenarioSmokeLiveAssertionsFailOnP2SettlementBrokenChain(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"other-obligation","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
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
		t.Fatal("expected settlement fact chain failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p2-settlement-chain-linked") {
		t.Fatalf("expected full chain linkage failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnP2SettlementCrossRunFact(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"other-run","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
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
		t.Fatal("expected settlement fact scope failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p2-settlement-scope-consistent") {
		t.Fatalf("expected scenario scope failure: %+v", report.Failures)
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

func p1OwnOrderHistoryJSON(orderID string) string {
	return p1OwnOrderHistoryJSONWithFilled(orderID, "")
}

func p1CleanReplayCheckJSON() string {
	return `{"pass":true,"checkedAt":"2026-03-14T18:00:30Z","report":{"batchCount":1,"storedCommandCount":3,"payloadOutcomeCount":3,"canonicalOutcomeCount":3,"duplicateReplayInserted":0,"checksumMismatchCount":0,"batchCommandCountMismatchCount":0,"payloadHashMismatchCount":0,"missingOutcomeCount":0,"extraOutcomeCount":0,"streamGapCount":0,"streamOverlapCount":0,"watermarkLagCount":0},"visibilityTimeline":{"scenarioId":"P1_GOLDEN_HIDDEN_CROSS_T1","publicDepthHiddenRestingExposed":false,"publicDepthChecks":[{"phase":"before-first-execution","instrumentId":"XYZ","price":"100000000000","hiddenRestingQuantityVisible":false}]},"failures":[]}`
}

func p1OwnOrderHistoryJSONWithFilled(orderID string, filledOverride string) string {
	filled := map[string]string{
		"p1_golden_hidden_cross_t1-ord-001": "100",
		"p1_golden_hidden_cross_t1-ord-002": "40",
		"p1_golden_hidden_cross_t1-ord-003": "60",
	}[orderID]
	if filledOverride != "" {
		filled = filledOverride
	}
	return `{"meta":{"source":"runtime.orders + runtime.order_lifecycle_state","freshness":"dirty-tracked lifecycle projection"},"orders":[{"orderId":"` + orderID + `","status":"FILLED","filledQuantityUnits":"` + filled + `"}]}`
}

func p1OwnFillsJSON(participantID string) string {
	fills := map[string]string{
		"HIDDEN_SELLER_A": `{"executionId":"exec-p1-001","orderId":"p1_golden_hidden_cross_t1-ord-001","instrumentId":"XYZ","side":"SELL","quantityUnits":"40","executionPrice":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:03Z"},{"executionId":"exec-p1-002","orderId":"p1_golden_hidden_cross_t1-ord-001","instrumentId":"XYZ","side":"SELL","quantityUnits":"60","executionPrice":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:07Z"}`,
		"VISIBLE_BUYER_B": `{"executionId":"exec-p1-001","orderId":"p1_golden_hidden_cross_t1-ord-002","instrumentId":"XYZ","side":"BUY","quantityUnits":"40","executionPrice":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:03Z"}`,
		"VISIBLE_BUYER_C": `{"executionId":"exec-p1-002","orderId":"p1_golden_hidden_cross_t1-ord-003","instrumentId":"XYZ","side":"BUY","quantityUnits":"60","executionPrice":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:07Z"}`,
	}[participantID]
	if fills == "" {
		fills = ""
	}
	return `{"meta":{"source":"runtime.orders + runtime.executions","freshness":"durable execution rows scoped by participant order ownership"},"fills":[` + fills + `]}`
}

func p1TradeTapeJSON() string {
	return `{"instrumentId":"XYZ","meta":{"source":"runtime.trades","freshness":"durable fact rows"},"trades":[{"sequence":3,"tradeId":"trade-p2_settlement_break_repair-ord-001-p2_settlement_break_repair-ord-002-1","instrumentId":"XYZ","quantityUnits":"1000","price":"150000000000","currency":"USD","occurredAt":"2026-03-14T18:00:02Z"},{"sequence":2,"tradeId":"` + p1SecondVisibleBuyTradeID + `","instrumentId":"XYZ","quantityUnits":"60","price":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:07Z"},{"sequence":1,"tradeId":"` + p1FirstVisibleBuyTradeID + `","instrumentId":"XYZ","quantityUnits":"40","price":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:03Z"}]}`
}

func p1TradeTapeWithPublicIdentityLeakJSON() string {
	return `{"instrumentId":"XYZ","meta":{"source":"runtime.trades","freshness":"durable fact rows"},"trades":[{"tradeId":"` + p1FirstVisibleBuyTradeID + `","instrumentId":"XYZ","quantityUnits":"40","price":"100000000000","buyOrderId":"p1_golden_hidden_cross_t1-ord-002"},{"tradeId":"` + p1SecondVisibleBuyTradeID + `","instrumentId":"XYZ","quantityUnits":"60","price":"100000000000"}]}`
}

func p1DataAvailabilityJSON() string {
	return p1DataAvailabilityJSONWithOrderHistorySource("runtime.orders + runtime.order_lifecycle_state")
}

func p1DataAvailabilityJSONWithOrderHistorySource(orderHistorySource string) string {
	return `{
		"generatedAt":"2026-03-14T18:00:30Z",
		"source":"venue-event-batch",
		"projections":[
			{"projectionName":"runtime-normalized-venue-outcomes","role":"canonical venue outcome projection","projectedCount":3,"lag":0,"watermarks":[{"lastPartitionSequence":3,"lag":0,"updatedAt":"2026-03-14T18:00:30Z"}]},
			{"projectionName":"market-data-top-of-book","role":"top-of-book market data projection","projectedCount":1,"lag":0,"watermarks":[{"lastPartitionSequence":3,"lag":0,"updatedAt":"2026-03-14T18:00:30Z"}]}
		],
		"surfaces":[
			{"name":"marketDataDepth","endpoint":"/api/v1/market-data/depth/{instrumentId}","source":"runtime.order_lifecycle_state","freshness":"read-time bounded aggregation","scope":"public-market-data","projectionName":"runtime-normalized-venue-outcomes","lag":0,"lastPartitionSequence":3,"lastUpdatedAt":"2026-03-14T18:00:30Z"},
			{"name":"tradeTape","endpoint":"/api/v1/market-data/trades/{instrumentId}","source":"runtime.trades","freshness":"durable fact rows","scope":"public-market-data","projectionName":"runtime-normalized-venue-outcomes","lag":0,"lastPartitionSequence":3,"lastUpdatedAt":"2026-03-14T18:00:30Z"},
			{"name":"orderHistory","endpoint":"/api/v1/orders/history","source":"` + orderHistorySource + `","freshness":"dirty-tracked lifecycle projection","scope":"participant-own-orders","projectionName":"runtime-normalized-venue-outcomes","lag":0,"lastPartitionSequence":3,"lastUpdatedAt":"2026-03-14T18:00:30Z"},
			{"name":"orderFills","endpoint":"/api/v1/orders/fills","source":"runtime.orders + runtime.executions","freshness":"durable execution rows scoped by participant order ownership","scope":"participant-own-orders","projectionName":"runtime-normalized-venue-outcomes","lag":0,"lastPartitionSequence":3,"lastUpdatedAt":"2026-03-14T18:00:30Z"}
		]
	}`
}

type p1AssertionServerOptions struct {
	filledQuantityByOrderID   map[string]string
	ownFillsJSONByParticipant map[string]string
	dataAvailabilityJSON      string
}

func p1AssertionServer(t *testing.T, options p1AssertionServerOptions) *httptest.Server {
	t.Helper()
	availabilityJSON := options.dataAvailabilityJSON
	if availabilityJSON == "" {
		availabilityJSON = p1DataAvailabilityJSON()
	}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet &&
			r.URL.Path != "/readyz" &&
			!strings.HasPrefix(r.URL.Path, "/api/v1/commands/") &&
			r.Header.Get("X-Client-Id") == "" {
			http.Error(w, "missing X-Client-Id", http.StatusUnauthorized)
			return
		}
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/history":
			participantID := r.URL.Query().Get("participantId")
			if r.Header.Get("X-Participant-Id") != participantID {
				http.Error(w, "missing participant scope", http.StatusForbidden)
				return
			}
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
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSONWithFilled(orderID, options.filledQuantityByOrderID[orderID])))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			participantID := r.URL.Query().Get("participantId")
			if r.Header.Get("X-Participant-Id") != participantID {
				http.Error(w, "missing participant scope", http.StatusForbidden)
				return
			}
			responseJSON := p1OwnFillsJSON(participantID)
			if options.ownFillsJSONByParticipant != nil && options.ownFillsJSONByParticipant[participantID] != "" {
				responseJSON = options.ownFillsJSONByParticipant[participantID]
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(responseJSON))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(availabilityJSON))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
		default:
			http.NotFound(w, r)
		}
	}))
}

func p2SettlementServer(t *testing.T, settlementFacts string) *httptest.Server {
	return p2SettlementServerWithExceptionQueue(
		t,
		settlementFacts,
		p2ResolvedSettlementExceptionQueueJSON("CASH_LEG_FAILED", "POST_CASH_LEG_REPAIR"),
	)
}

func p2SettlementServerWithExceptionQueue(t *testing.T, settlementFacts string, exceptionQueue string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/settlement/facts/p2-settlement-live" && settlementFacts != "":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(settlementFacts))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/settlement/exceptions/p2-settlement-live" && settlementFacts != "":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(exceptionQueue))
		default:
			http.NotFound(w, r)
		}
	}))
}

func p2ResolvedSettlementExceptionQueueJSON(reason string, repairAction string) string {
	return fmt.Sprintf(`{
		"scenarioRunId":"p2-settlement-live",
		"exceptionsCount":1,
		"openCount":0,
		"repairPostedCount":0,
		"resolvedCount":1,
		"clearingRejectedCount":0,
		"settlementBreakCount":1,
		"exceptions":[{
			"settlementExceptionId":"break-1",
			"exceptionType":"SETTLEMENT_BREAK",
			"exceptionState":"RESOLVED",
			"state":"RESOLVED",
			"severity":"MEDIUM",
			"ownerRole":"SETTLEMENT_OPS",
			"actionRequired":"NONE",
			"reason":"%s",
			"settlementObligationId":"obl-1",
			"tradeId":"trade-1",
			"buyerParticipantId":"buyer-1",
			"sellerParticipantId":"seller-1",
			"instrumentId":"XYZ",
			"quantity":"100",
			"cashAmount":"10000.00",
			"currency":"USD",
			"settlementClearingSubmissionId":"",
			"settlementClearingRejectionId":"",
			"settlementBreakId":"break-1",
			"settlementRepairId":"repair-1",
			"settlementResolutionId":"resolution-1",
			"repairAction":"%s",
			"actorId":"ops-1",
			"correlationId":"corr-1",
			"postTradeProfileId":"ops-realistic-v1",
			"postTradePolicyVersion":1,
			"openedAt":"2026-03-14T18:00:05Z",
			"lastUpdatedAt":"2026-03-14T18:00:07Z",
			"resolvedAt":"2026-03-14T18:00:07Z",
			"occurredAt":"2026-03-14T18:00:05Z",
			"updatedAt":"2026-03-14T18:00:07Z"
		}]
	}`, reason, repairAction)
}

func p2EmptySettlementExceptionQueueJSON() string {
	return `{
		"scenarioRunId":"p2-settlement-live",
		"exceptionsCount":0,
		"openCount":0,
		"repairPostedCount":0,
		"resolvedCount":0,
		"clearingRejectedCount":0,
		"settlementBreakCount":0,
		"exceptions":[]
	}`
}

func writeReadyzOK(w http.ResponseWriter) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok","dependencies":{"commandStatusLookup":true}}`))
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
