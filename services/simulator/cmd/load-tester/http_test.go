package main

import (
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
	var gotPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"accepted":true}`))
	}))
	defer server.Close()

	cfg := Config{BaseURL: server.URL, Transport: transportHTTP, UseApiV1: true, ClientIDPrefix: "bot"}
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
