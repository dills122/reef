package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestSubmitPairWaitsForDurableBuyBeforeSell(t *testing.T) {
	var mu sync.Mutex
	var sides []string
	var keys []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/orders/submit" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		var payload struct {
			Side       string `json:"side"`
			CommandID  string `json:"commandId"`
			Instrument string `json:"instrumentId"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Error(err)
		}
		mu.Lock()
		sides = append(sides, payload.Side)
		keys = append(keys, r.Header.Get("Idempotency-Key"))
		mu.Unlock()
		w.WriteHeader(http.StatusAccepted)
		w.Write([]byte(`{"status":"accepted"}`))
	}))
	defer server.Close()

	cfg := config{BaseURL: server.URL, SmokeID: "test"}
	var counts counters
	if err := submitPair(server.Client(), cfg, 4, &counts); err != nil {
		t.Fatal(err)
	}
	if counts.acceptedOrders.Load() != 2 || counts.completedPairs.Load() != 1 {
		t.Fatalf("accepted=%d completed=%d", counts.acceptedOrders.Load(), counts.completedPairs.Load())
	}
	if len(sides) != 2 || sides[0] != "BUY" || sides[1] != "SELL" {
		t.Fatalf("sides=%v", sides)
	}
	if len(keys) != 2 || keys[0] != "buyer-load-idem-4-test" || keys[1] != "seller-load-idem-4-test" {
		t.Fatalf("keys=%v", keys)
	}
}

func TestGateSeparatesAcceptedRateFromReceiptGap(t *testing.T) {
	lower, upper := gapRange(100, 130, 90)
	if lower != 11 || upper != 41 {
		t.Fatalf("gap range=%d..%d", lower, upper)
	}
	gaps := []int64{100, 200, 300}
	samples := []sample{
		{Gap: &gaps[0]}, {Gap: &gaps[1]}, {Gap: &gaps[2]},
	}
	p95, max := gapBounds(samples)
	if *p95 != 300 || *max != 300 {
		t.Fatalf("p95=%d max=%d", *p95, *max)
	}
	receipts, drain := int64(950), int64(1000)
	result := report{
		TargetPairs: 1000, TargetPairsPerSecond: 100, AcceptedOrdersAtDeadline: 1900,
		CompletedPairs: 949, DroppedPairs: 50, ReceiptGeneration: 1,
		FinalReceipts: &receipts, DrainMs: &drain, GapP95: p95, GapMax: max,
		Samples: samples,
	}
	// A measured gap can be steady while still exceeding requested freshness.
	if len(gateReasons(result)) != 1 {
		t.Fatalf("reasons=%v", gateReasons(result))
	}
	result.TargetPairsPerSecond = 200
	if reasons := gateReasons(result); len(reasons) != 0 {
		t.Fatalf("unexpected reasons=%v", reasons)
	}
	result.AcceptedOrdersAtDeadline = 1899
	if reasons := gateReasons(result); len(reasons) != 1 {
		t.Fatalf("rate failure reasons=%v", reasons)
	}
}

func TestRunPacesFullCrossingPairsThroughHTTP(t *testing.T) {
	var mu sync.Mutex
	orders := make(map[string]int)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var payload struct {
			CommandID string `json:"commandId"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Error(err)
		}
		mu.Lock()
		orders[payload.CommandID]++
		mu.Unlock()
		w.WriteHeader(http.StatusAccepted)
		w.Write([]byte(`{"accepted":true}`))
	}))
	defer server.Close()

	result := run(config{
		BaseURL: server.URL, SmokeID: "paced-test", Duration: time.Second,
		PairsPerSecond: 10, Workers: 10,
	})
	if result.ScheduledPairs != 10 || result.DispatchedPairs != 10 || result.DroppedPairs != 0 {
		t.Fatalf("schedule=%d dispatched=%d dropped=%d", result.ScheduledPairs, result.DispatchedPairs, result.DroppedPairs)
	}
	if result.CompletedPairs != 10 || result.AcceptedOrders != 20 || result.Failures != 0 || result.Retries != 0 {
		t.Fatalf("completed=%d accepted=%d failures=%d retries=%d", result.CompletedPairs, result.AcceptedOrders, result.Failures, result.Retries)
	}
	if len(orders) != 20 {
		t.Fatalf("unique commands=%d", len(orders))
	}
}

func TestSubmitRejectsNonDurableAcknowledgement(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusAccepted)
		w.Write([]byte(`{"accepted":false,"status":"queued"}`))
	}))
	defer server.Close()
	var counts counters
	if err := submitPair(server.Client(), config{BaseURL: server.URL, SmokeID: "ack-test"}, 0, &counts); err == nil {
		t.Fatal("queued acknowledgement accepted as durable")
	}
	if counts.acceptedOrders.Load() != 0 || counts.completedPairs.Load() != 0 {
		t.Fatalf("accepted=%d completed=%d", counts.acceptedOrders.Load(), counts.completedPairs.Load())
	}
}
