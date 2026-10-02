package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
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
		if payload.Instrument != "AAPL-test" {
			t.Errorf("default instrument=%s", payload.Instrument)
		}
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

	beforeRun := time.Now().UnixMilli()
	result := run(config{
		BaseURL: server.URL, SmokeID: "paced-test", Duration: time.Second,
		PairsPerSecond: 10, Workers: 10,
	})
	afterRun := time.Now().UnixMilli()
	if result.LoadStartedEpochMs < beforeRun || result.AcceptedAllEpochMs > afterRun || result.AcceptedAllEpochMs < result.LoadStartedEpochMs {
		t.Fatalf("run instants outside observed bounds: before=%d start=%d acceptedAll=%d after=%d", beforeRun, result.LoadStartedEpochMs, result.AcceptedAllEpochMs, afterRun)
	}
	if result.ScheduledDeadlineEpochMs-result.LoadStartedEpochMs != 1000 {
		t.Fatalf("scheduled duration=%d want1000", result.ScheduledDeadlineEpochMs-result.LoadStartedEpochMs)
	}
	wallElapsed := result.AcceptedAllEpochMs - result.LoadStartedEpochMs
	if delta := wallElapsed - result.ElapsedMs; delta < -1 || delta > 1 {
		t.Fatalf("completion elapsed=%d wallElapsed=%d", result.ElapsedMs, wallElapsed)
	}
	if result.DeadlineAccounting == "" || result.ReceiptDrainAccounting == "" {
		t.Fatal("missing deadline/drain measurement definitions")
	}
	if result.ScheduledPairs != 10 || result.DispatchedPairs != 10 || result.DroppedPairs != 0 {
		t.Fatalf("schedule=%d dispatched=%d dropped=%d", result.ScheduledPairs, result.DispatchedPairs, result.DroppedPairs)
	}
	if result.WorkloadMode != "paired" || result.OrdersPerTrade != 2 || result.CompletedTrades != result.CompletedPairs || result.JobAccounting == "" {
		t.Fatalf("paired report mode/units=%+v", result)
	}
	if !reflect.DeepEqual(result.InstrumentIDs, []string{"AAPL-paced-test"}) {
		t.Fatalf("default report instruments=%v", result.InstrumentIDs)
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

func TestAcknowledgementDeadlineIsStrictAndIndependentOfSnapshotTime(t *testing.T) {
	deadline := time.Now().Add(time.Second)
	counts := counters{loadDeadline: deadline}
	for _, observedAt := range []time.Time{deadline.Add(-time.Nanosecond), deadline, deadline.Add(time.Nanosecond)} {
		counts.recordAcknowledgement(observedAt)
	}
	if got := counts.acceptedOrders.Load(); got != 3 {
		t.Fatalf("total accepted=%d want3", got)
	}
	// Reading after every response has completed must not turn late/equal ACKs
	// into in-window ACKs. Deadline equality is excluded explicitly.
	if got := counts.acceptedBeforeDeadline.Load(); got != 1 {
		t.Fatalf("before deadline=%d want1", got)
	}
}

func TestSubmitPairClassifiesEachValidatedHTTPAcknowledgementAtObservation(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusAccepted)
		w.Write([]byte(`{"accepted":true}`))
	}))
	defer server.Close()
	deadline := time.Now().Add(time.Second)
	observations := []time.Time{deadline.Add(-time.Nanosecond), deadline.Add(time.Nanosecond)}
	calls := 0
	counts := counters{loadDeadline: deadline, now: func() time.Time { at := observations[calls]; calls++; return at }}
	if err := submitPair(server.Client(), config{BaseURL: server.URL, SmokeID: "deadline-test"}, 0, &counts); err != nil {
		t.Fatal(err)
	}
	if calls != 2 || counts.acceptedOrders.Load() != 2 || counts.completedPairs.Load() != 1 || counts.acceptedBeforeDeadline.Load() != 1 {
		t.Fatalf("ACK clocks=%d total=%d pairs=%d beforeDeadline=%d", calls, counts.acceptedOrders.Load(), counts.completedPairs.Load(), counts.acceptedBeforeDeadline.Load())
	}
}

func TestParseInstrumentIDsValidatesBoundedExplicitList(t *testing.T) {
	for _, test := range []struct {
		raw  string
		want []string
	}{
		{"", []string{"AAPL-test"}}, {"lane-a, lane-b,lane-c", []string{"lane-a", "lane-b", "lane-c"}},
		{strings.Repeat("x", 128), []string{strings.Repeat("x", 128)}},
	} {
		got, err := parseInstrumentIDs(test.raw, "test")
		if err != nil || !reflect.DeepEqual(got, test.want) {
			t.Fatalf("raw=%q got=%v err=%v want=%v", test.raw, got, err, test.want)
		}
	}
	many := make([]string, 65)
	for i := range many {
		many[i] = fmt.Sprintf("lane-%d", i)
	}
	if got, err := parseInstrumentIDs(strings.Join(many[:64], ","), "test"); err != nil || len(got) != 64 {
		t.Fatalf("64 lanes got=%d err=%v", len(got), err)
	}
	for _, raw := range []string{"lane-a,", "lane-a,,lane-b", "lane-a,lane-a", strings.Join(many, ","), strings.Repeat("界", 43)} {
		if _, err := parseInstrumentIDs(raw, "test"); err == nil {
			t.Fatalf("invalid list accepted: %q", raw)
		}
	}
}

func TestSubmitPairRoutesBothSidesRoundRobinWithoutChangingIdentity(t *testing.T) {
	type submitted struct {
		Instrument string `json:"instrumentId"`
		Side       string `json:"side"`
		CommandID  string `json:"commandId"`
		OrderID    string `json:"orderId"`
		RunID      string `json:"runId"`
		SessionID  string `json:"venueSessionId"`
	}
	var orders []submitted
	var ordersMu sync.Mutex
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var payload submitted
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Error(err)
		}
		ordersMu.Lock()
		orders = append(orders, payload)
		ordersMu.Unlock()
		w.WriteHeader(http.StatusAccepted)
		w.Write([]byte(`{"accepted":true}`))
	}))
	defer server.Close()
	cfg := config{BaseURL: server.URL, SmokeID: "lanes-test", StartIndex: 7, InstrumentIDs: []string{"lane-a", "lane-b", "lane-c"}}
	var counts counters
	for index := int64(7); index < 10; index++ {
		if err := submitPair(server.Client(), cfg, index, &counts); err != nil {
			t.Fatal(err)
		}
	}
	ordersMu.Lock()
	defer ordersMu.Unlock()
	if len(orders) != 6 {
		t.Fatalf("orders=%d want6", len(orders))
	}
	for i, order := range orders {
		index := int64(7 + i/2)
		party, side := "buyer", "BUY"
		if i%2 == 1 {
			party, side = "seller", "SELL"
		}
		if order.Instrument != cfg.InstrumentIDs[index%3] || order.Side != side || order.CommandID != fmt.Sprintf("%s-load-%d-lanes-test", party, index) || order.OrderID != fmt.Sprintf("%s-load-order-%d-lanes-test", party, index) || order.RunID != "lanes-test" || order.SessionID != "session-lanes-test" {
			t.Fatalf("order%d routing/identity=%+v", i, order)
		}
	}
}

func TestWorkloadModeValidatesAndGateCountsOrdersPerTrade(t *testing.T) {
	for _, test := range []struct {
		mode       string
		generation int
		orders     int64
		valid      bool
	}{
		{"", 0, 2, true}, {"paired", 0, 2, true}, {"paired", 7, 2, true}, {"aggressor", 0, 1, true}, {"aggressor", 7, 0, false}, {"invalid", 0, 0, false},
	} {
		_, orders, err := workloadParameters(test.mode, test.generation)
		if (err == nil) != test.valid || (test.valid && orders != test.orders) {
			t.Fatalf("mode=%q generation=%d orders=%d err=%v", test.mode, test.generation, orders, err)
		}
	}
	result := report{WorkloadMode: "aggressor", OrdersPerTrade: 1, TargetPairs: 100, AcceptedOrdersAtDeadline: 95}
	if reasons := gateReasons(result); len(reasons) != 0 {
		t.Fatalf("95 aggressor orders/100 tradejobs failed: %v", reasons)
	}
	result.AcceptedOrdersAtDeadline = 94
	if reasons := gateReasons(result); len(reasons) != 1 {
		t.Fatalf("94 aggressor orders failed threshold reasons=%v", reasons)
	}
	result.WorkloadMode = "paired"
	result.OrdersPerTrade = 2
	result.AcceptedOrdersAtDeadline = 190
	if reasons := gateReasons(result); len(reasons) != 0 {
		t.Fatalf("190 paired orders/100 tradejobs failed: %v", reasons)
	}
}

func TestAggressorPostsOnlySellerAndClassifiesSingleAcknowledgement(t *testing.T) {
	var count atomic.Int64
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		if body["side"] != "SELL" || body["participantId"] != "seller-mode-test" || body["instrumentId"] != "lane-b" || body["quantityUnits"] != "100" || body["commandId"] != "seller-load-7-mode-test" {
			t.Errorf("aggressor payload=%v", body)
		}
		count.Add(1)
		w.WriteHeader(http.StatusAccepted)
		w.Write([]byte(`{"accepted":true}`))
	}))
	defer server.Close()
	deadline := time.Now().Add(time.Second)
	var clockCalls atomic.Int64
	counts := counters{loadDeadline: deadline, now: func() time.Time { clockCalls.Add(1); return deadline.Add(-time.Nanosecond) }}
	cfg := config{WorkloadMode: "aggressor", BaseURL: server.URL, SmokeID: "mode-test", InstrumentIDs: []string{"lane-a", "lane-b"}}
	if err := submitPair(server.Client(), cfg, 7, &counts); err != nil {
		t.Fatal(err)
	}
	if count.Load() != 1 || counts.acceptedOrders.Load() != 1 || counts.acceptedBeforeDeadline.Load() != 1 || counts.completedPairs.Load() != 1 || clockCalls.Load() != 1 {
		t.Fatalf("posts=%d ACKs=%d deadlineACKs=%d jobs=%d clocks=%d", count.Load(), counts.acceptedOrders.Load(), counts.acceptedBeforeDeadline.Load(), counts.completedPairs.Load(), clockCalls.Load())
	}
	cfg.WorkloadMode = "bad"
	if err := submitPair(server.Client(), cfg, 8, &counts); err == nil {
		t.Fatal("invalid mode accepted")
	}
	if count.Load() != 1 {
		t.Fatal("invalid mode issued HTTP request")
	}
}
