package http

import (
	"github.com/dills122/reef/services/matching-engine/internal/app"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFiniteRoutesFenceEveryDirectMutationBeforeDecode(t *testing.T) {
	service := app.NewService()
	before := service.Snapshot().Checksum
	server := NewServer(service)
	readiness := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusUnauthorized) })
	handler := server.FiniteRoutes(readiness)
	for _, path := range []string{"/orders/submit", "/orders/modify", "/orders/cancel", "/orders/other"} {
		req := httptest.NewRequest("POST", path, strings.NewReader("malformed body"))
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, req)
		if response.Code != http.StatusForbidden || service.Snapshot().Checksum != before {
			t.Fatalf("direct finite mutation path=%s code=%d", path, response.Code)
		}
	}
	for _, c := range []struct {
		path   string
		status int
	}{{"/health", 200}, {"/internal/stream-direct/stats", 200}, {"/internal/calcify/finite-readiness?nonce=test", 401}} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("GET", c.path, nil))
		if response.Code != c.status {
			t.Fatalf("route%s code%d", c.path, response.Code)
		}
	}
	response := httptest.NewRecorder()
	server.FiniteRoutes(nil).ServeHTTP(response, httptest.NewRequest("GET", "/internal/calcify/finite-readiness?nonce=test", nil))
	if response.Code != 404 {
		t.Fatal("unverified readiness mounted")
	}
}
