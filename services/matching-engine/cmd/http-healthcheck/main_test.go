package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCheckHealthAcceptsSuccess(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := &http.Client{Timeout: time.Second}
	if err := checkHealth(client, server.URL); err != nil {
		t.Fatalf("expected healthy response: %v", err)
	}
}

func TestCheckHealthRejectsServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	client := &http.Client{Timeout: time.Second}
	if err := checkHealth(client, server.URL); err == nil {
		t.Fatal("expected server error to fail healthcheck")
	}
}

func TestCheckHealthRejectsConnectionFailure(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	url := server.URL
	server.Close()

	client := &http.Client{Timeout: time.Second}
	if err := checkHealth(client, url); err == nil {
		t.Fatal("expected connection failure to fail healthcheck")
	}
}
