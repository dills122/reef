package http

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

func TestHealth(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
}

func TestSubmitOrder(t *testing.T) {
	server := NewServer(app.NewService())

	body := []byte(`{
		"commandId":"cmd-1",
		"correlationId":"corr-1",
		"actorId":"trader-1",
		"occurredAt":"2026-03-14T18:00:00Z",
		"orderId":"ord-1",
		"instrumentId":"AAPL",
		"participantId":"participant-1",
		"accountId":"account-1",
		"side":"BUY",
		"orderType":"LIMIT",
		"quantityUnits":"100",
		"limitPrice":"150250000000",
		"currency":"USD",
		"timeInForce":"DAY"
	}`)

	req := httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}

	if !bytes.Contains(rec.Body.Bytes(), []byte(`"accepted"`)) {
		t.Fatalf("expected accepted payload, got %s", rec.Body.String())
	}
}
