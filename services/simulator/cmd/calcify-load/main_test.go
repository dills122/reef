package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
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
