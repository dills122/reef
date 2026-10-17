package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

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
