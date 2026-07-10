package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestDoPOST(t *testing.T) {
	var gotHeader, gotBody string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHeader = r.Header.Get("X-Client-Id")
		body := make([]byte, r.ContentLength)
		_, _ = r.Body.Read(body)
		gotBody = string(body)
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte(`{"accepted":true}`))
	}))
	defer server.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	status, body, err := doPOST(client, server.URL+"/orders/submit", map[string]string{"orderId": "ord-1"}, map[string]string{"X-Client-Id": "client-1"})
	if err != nil {
		t.Fatalf("doPOST failed: %v", err)
	}
	if status != http.StatusAccepted {
		t.Errorf("status = %d, want 202", status)
	}
	if gotHeader != "client-1" {
		t.Errorf("X-Client-Id = %s, want client-1", gotHeader)
	}
	if gotBody == "" {
		t.Error("expected request body to be sent")
	}
	if len(body) == 0 {
		t.Error("expected non-empty response body")
	}
}

func TestDoPOSTReturnsErrorOnUnreachableServer(t *testing.T) {
	client := &http.Client{Timeout: 200 * time.Millisecond}
	_, _, err := doPOST(client, "http://127.0.0.1:1/orders/submit", map[string]string{"orderId": "ord-1"}, nil)
	if err == nil {
		t.Fatal("expected error for unreachable server")
	}
}

func TestSubmitCommandUsesHTTPTransport(t *testing.T) {
	var gotPath, gotAuthorization string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotAuthorization = r.Header.Get("Authorization")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"accepted":true}`))
	}))
	defer server.Close()

	cfg := Config{BaseURL: server.URL, Transport: transportHTTP, UseApiV1: true, ClientIDPrefix: "bot", APIBearerToken: "token-1"}
	client := &http.Client{Timeout: 2 * time.Second}

	status, body, err := submitCommand(client, nil, cfg, 0, "cmd-1", "trace-1", map[string]string{"orderId": "ord-1"}, ActionSubmit)
	if err != nil {
		t.Fatalf("submitCommand failed: %v", err)
	}
	if status != http.StatusOK {
		t.Errorf("status = %d, want 200", status)
	}
	if gotPath != "/api/v1/orders/submit" {
		t.Errorf("path = %s, want /api/v1/orders/submit", gotPath)
	}
	if gotAuthorization != "Bearer token-1" {
		t.Errorf("Authorization = %s, want Bearer token-1", gotAuthorization)
	}
	if len(body) == 0 {
		t.Error("expected non-empty response body")
	}
}

func TestSubmitCommandUsesCancelRoute(t *testing.T) {
	var gotPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	cfg := Config{BaseURL: server.URL, Transport: transportHTTP, UseApiV1: false}
	client := &http.Client{Timeout: 2 * time.Second}

	if _, _, err := submitCommand(client, nil, cfg, 0, "cmd-1", "trace-1", map[string]string{"orderId": "ord-1"}, ActionCancel); err != nil {
		t.Fatalf("submitCommand failed: %v", err)
	}
	if gotPath != "/orders/cancel" {
		t.Errorf("path = %s, want /orders/cancel", gotPath)
	}
}

func TestFetchNewTrades(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"trades":[{"eventId":"e1","tradeId":"t1"},{"eventId":"e2","tradeId":"t2"}]}`))
	}))
	defer server.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	seen := map[string]struct{}{}
	trades := fetchNewTrades(client, server.URL, seen, 10)
	if len(trades) != 2 {
		t.Fatalf("expected 2 trades, got %d", len(trades))
	}
	if trades[0].TradeID != "t1" || trades[1].TradeID != "t2" {
		t.Errorf("expected original response order preserved, got %+v", trades)
	}
	if len(seen) != 2 {
		t.Errorf("expected 2 seen entries, got %d", len(seen))
	}

	again := fetchNewTrades(client, server.URL, seen, 10)
	if len(again) != 0 {
		t.Errorf("expected no new trades on second fetch, got %d", len(again))
	}
}

func TestFetchNewTradesHandlesErrorsGracefully(t *testing.T) {
	client := &http.Client{Timeout: 200 * time.Millisecond}
	if got := fetchNewTrades(client, "http://127.0.0.1:1", map[string]struct{}{}, 10); got != nil {
		t.Errorf("expected nil on unreachable server, got %v", got)
	}

	badServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer badServer.Close()
	if got := fetchNewTrades(client, badServer.URL, map[string]struct{}{}, 10); got != nil {
		t.Errorf("expected nil on error status, got %v", got)
	}
}

func TestFetchNewEvents(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"events":[{"eventId":"e1","eventType":"OrderAccepted"},{"eventId":"e2","eventType":"OrderRejected"}]}`))
	}))
	defer server.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	seen := map[string]struct{}{}
	events := fetchNewEvents(client, server.URL, seen, 10)
	if len(events) != 2 {
		t.Fatalf("expected 2 events, got %d", len(events))
	}
	if events[0].EventID != "e1" || events[1].EventID != "e2" {
		t.Errorf("expected original response order preserved, got %+v", events)
	}
}

func TestCheckTraceOnceValidatesSequenceOrdering(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"events":[{"traceId":"trace-1","sequenceNumber":2},{"traceId":"trace-1","sequenceNumber":1}]}`))
	}))
	defer server.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	if !checkTraceOnce(client, server.URL, "trace-1") {
		t.Error("expected valid ordered sequence to pass")
	}
}

func TestCheckTraceOnceRejectsWrongTraceID(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"events":[{"traceId":"other-trace","sequenceNumber":1}]}`))
	}))
	defer server.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	if checkTraceOnce(client, server.URL, "trace-1") {
		t.Error("expected mismatched trace id to fail")
	}
}

func TestCheckTraceOnceRejectsEmptyEvents(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"events":[]}`))
	}))
	defer server.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	if checkTraceOnce(client, server.URL, "trace-1") {
		t.Error("expected empty events to fail")
	}
}

func TestCheckTraceRetriesUntilSuccess(t *testing.T) {
	attempts := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts < 2 {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		_, _ = w.Write([]byte(`{"events":[{"traceId":"trace-1","sequenceNumber":1}]}`))
	}))
	defer server.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	if !checkTrace(client, server.URL, "trace-1") {
		t.Error("expected checkTrace to succeed after retry")
	}
	if attempts < 2 {
		t.Errorf("expected at least 2 attempts, got %d", attempts)
	}
}

func TestCheckTraceGivesUpAfterRetries(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	client := &http.Client{Timeout: 2 * time.Second}
	if checkTrace(client, server.URL, "trace-1") {
		t.Error("expected checkTrace to fail after exhausting retries")
	}
}

func TestRunTraceChecksReportsPassAndFail(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/traces/trace-good/events" {
			_, _ = w.Write([]byte(`{"events":[{"traceId":"trace-good","sequenceNumber":1}]}`))
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	seen := newTraceSampler(10)
	seen.offer("trace-good")
	seen.offer("trace-bad")

	client := &http.Client{Timeout: 2 * time.Second}
	cfg := Config{BaseURL: server.URL, TraceCheckLimit: 10}
	checks := runTraceChecks(client, cfg, seen)
	if checks.Checked != 2 {
		t.Errorf("Checked = %d, want 2", checks.Checked)
	}
	if checks.Pass != 1 || checks.Fail != 1 {
		t.Errorf("Pass=%d Fail=%d, want 1/1", checks.Pass, checks.Fail)
	}
	if len(checks.FailedTraceID) != 1 || checks.FailedTraceID[0] != "trace-bad" {
		t.Errorf("FailedTraceID = %v, want [trace-bad]", checks.FailedTraceID)
	}
}

func TestRunTraceChecksSkippedWhenLimitZero(t *testing.T) {
	seen := newTraceSampler(10)
	seen.offer("trace-1")
	checks := runTraceChecks(&http.Client{}, Config{TraceCheckLimit: 0}, seen)
	if checks.Checked != 0 {
		t.Errorf("expected 0 checked when limit disabled, got %d", checks.Checked)
	}
}

func TestTraceSamplerCapsStoredTraceIDs(t *testing.T) {
	sampler := newTraceSampler(3)
	for i := 0; i < 1000; i++ {
		sampler.offer(fmt.Sprintf("trace-%d", i))
	}
	ids := sampler.ids(1000)
	if len(ids) > 3 {
		t.Fatalf("expected sampler to retain at most 3 trace ids, got %d", len(ids))
	}
}

func TestTraceSamplerDisabledWhenLimitZero(t *testing.T) {
	sampler := newTraceSampler(0)
	sampler.offer("trace-1")
	if len(sampler.ids(10)) != 0 {
		t.Fatalf("expected no trace ids retained when limit is 0")
	}
}
