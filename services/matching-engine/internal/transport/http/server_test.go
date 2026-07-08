package http

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
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

func TestStreamDirectStatsDisabled(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodGet, "/internal/stream-direct/stats", nil)
	rec := httptest.NewRecorder()

	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"enabled":false`)) {
		t.Fatalf("expected disabled stats payload, got %s", rec.Body.String())
	}
}

func TestStreamDirectStatsEnabled(t *testing.T) {
	server := NewServer(app.NewService())
	server.SetStreamDirectStatsProvider(func() any {
		return []map[string]any{{"partition": 0, "acked": 10}}
	})

	req := httptest.NewRequest(http.MethodGet, "/internal/stream-direct/stats", nil)
	rec := httptest.NewRecorder()

	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"enabled":true`)) {
		t.Fatalf("expected enabled stats payload, got %s", rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"partition":0`)) {
		t.Fatalf("expected partition stats payload, got %s", rec.Body.String())
	}
}

func TestBookStatsEndpoint(t *testing.T) {
	server := NewServer(app.NewService())
	body := []byte(`{
		"orderId":"ord-1",
		"instrumentId":"AAPL",
		"side":"BUY",
		"quantityUnits":"100",
		"limitPrice":"150250000000",
		"currency":"USD"
	}`)
	server.Routes().ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(body)))

	req := httptest.NewRequest(http.MethodGet, "/internal/books/AAPL/stats", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"instrumentId":"AAPL"`)) {
		t.Fatalf("expected AAPL book stats, got %s", rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"buyOrders":1`)) {
		t.Fatalf("expected buy order count, got %s", rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"buyPriceLevels":1`)) {
		t.Fatalf("expected buy price level count, got %s", rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"checksum"`)) {
		t.Fatalf("expected checksum in book stats, got %s", rec.Body.String())
	}
}

func TestBookStatsRejectsWrongMethod(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodPost, "/internal/books/AAPL/stats", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", rec.Code)
	}
}

func TestBookStatsRejectsMalformedPath(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodGet, "/internal/books/AAPL/depth/stats", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", rec.Code)
	}
}

func TestBookSnapshotEndpoint(t *testing.T) {
	server := NewServer(app.NewService())
	body := []byte(`{
		"orderId":"ord-1",
		"instrumentId":"AAPL",
		"side":"BUY",
		"quantityUnits":"100",
		"limitPrice":"150250000000",
		"currency":"USD"
	}`)
	server.Routes().ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(body)))

	req := httptest.NewRequest(http.MethodGet, "/internal/books/AAPL/snapshot", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"bookKeys":["AAPL"]`)) {
		t.Fatalf("expected AAPL snapshot metadata, got %s", rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"orderId":"ord-1"`)) {
		t.Fatalf("expected order in snapshot, got %s", rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"checksum"`)) {
		t.Fatalf("expected checksum in snapshot, got %s", rec.Body.String())
	}
}

func TestBookSnapshotEndpointRejectsMissingBook(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodGet, "/internal/books/AAPL/snapshot", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", rec.Code)
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

func TestModifyOrderEndpoint(t *testing.T) {
	server := NewServer(app.NewService())

	submitBody := []byte(`{
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
	server.Routes().ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(submitBody)))

	modifyBody := []byte(`{"orderId":"ord-1","quantityUnits":"50","limitPrice":"150250000000"}`)
	req := httptest.NewRequest(http.MethodPost, "/orders/modify", bytes.NewReader(modifyBody))
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestModifyOrderRejectsWrongMethod(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodGet, "/orders/modify", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", rec.Code)
	}
}

func TestCancelOrderRejectsWrongMethod(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodGet, "/orders/cancel", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", rec.Code)
	}
}

func TestSubmitOrderRejectsWrongMethod(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodGet, "/orders/submit", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", rec.Code)
	}
}

func TestStreamDirectStatsRejectsWrongMethod(t *testing.T) {
	server := NewServer(app.NewService())

	req := httptest.NewRequest(http.MethodPost, "/internal/stream-direct/stats", nil)
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", rec.Code)
	}
}

func TestSubmitOrderRejectsUnknownFields(t *testing.T) {
	server := NewServer(app.NewService())

	body := []byte(`{
		"orderId":"ord-1",
		"instrumentId":"AAPL",
		"side":"BUY",
		"quantityUnits":"100",
		"limitPrice":"150250000000",
		"currency":"USD",
		"unexpected":"field"
	}`)

	req := httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestSubmitOrderRejectsMultipleJSONValues(t *testing.T) {
	server := NewServer(app.NewService())

	body := []byte(`{"orderId":"ord-1","instrumentId":"AAPL","side":"BUY","quantityUnits":"100","limitPrice":"150250000000","currency":"USD"} {"orderId":"ord-2"}`)

	req := httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestSubmitOrderRejectsOversizedBody(t *testing.T) {
	server := NewServer(app.NewService())

	body := `{"orderId":"ord-1","instrumentId":"AAPL","side":"BUY","quantityUnits":"100","limitPrice":"150250000000","currency":"` +
		strings.Repeat("x", int(maxRequestBodyBytes)) +
		`"}`

	req := httptest.NewRequest(http.MethodPost, "/orders/submit", strings.NewReader(body))
	rec := httptest.NewRecorder()

	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestSubmitOrderReturnsTradeForCrossingOrder(t *testing.T) {
	server := NewServer(app.NewService())

	firstBody := []byte(`{
		"commandId":"cmd-1",
		"correlationId":"corr-1",
		"actorId":"trader-1",
		"occurredAt":"2026-03-14T18:00:00Z",
		"orderId":"ord-buy-1",
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

	secondBody := []byte(`{
		"commandId":"cmd-2",
		"correlationId":"corr-2",
		"actorId":"trader-2",
		"occurredAt":"2026-03-14T18:01:00Z",
		"orderId":"ord-sell-1",
		"instrumentId":"AAPL",
		"participantId":"participant-2",
		"accountId":"account-2",
		"side":"SELL",
		"orderType":"LIMIT",
		"quantityUnits":"100",
		"limitPrice":"150000000000",
		"currency":"USD",
		"timeInForce":"DAY"
	}`)

	server.Routes().ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(firstBody)))

	req := httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(secondBody))
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if !bytes.Contains(rec.Body.Bytes(), []byte(`"trades"`)) {
		t.Fatalf("expected trades payload, got %s", rec.Body.String())
	}

	if !bytes.Contains(rec.Body.Bytes(), []byte(`"buyOrderId":"ord-buy-1"`)) {
		t.Fatalf("expected matched buy order id, got %s", rec.Body.String())
	}
}

func TestCancelOrderEndpoint(t *testing.T) {
	server := NewServer(app.NewService())

	submitBody := []byte(`{
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
	server.Routes().ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/orders/submit", bytes.NewReader(submitBody)))

	cancelBody := []byte(`{"orderId":"ord-1"}`)
	req := httptest.NewRequest(http.MethodPost, "/orders/cancel", bytes.NewReader(cancelBody))
	rec := httptest.NewRecorder()
	server.Routes().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte(`"accepted"`)) {
		t.Fatalf("expected accepted payload, got %s", rec.Body.String())
	}
}
