package main

import (
	"context"
	"math/rand"
	"testing"
	"time"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
)

func TestPickOrderIndex(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	if got := pickOrderIndex(rng, nil, "default"); got != 0 {
		t.Errorf("pickOrderIndex empty = %d, want 0", got)
	}

	orders := make([]trackedOrder, 10)
	for i := range orders {
		orders[i] = trackedOrder{OrderID: string(rune('a' + i))}
	}
	for i := 0; i < 20; i++ {
		idx := pickOrderIndex(rng, orders, "strict-lifecycle")
		if idx < 0 || idx >= len(orders) {
			t.Fatalf("pickOrderIndex out of range: %d", idx)
		}
	}
	for i := 0; i < 20; i++ {
		idx := pickOrderIndex(rng, orders, "default")
		if idx < 0 || idx >= len(orders) {
			t.Fatalf("pickOrderIndex(default) out of range: %d", idx)
		}
	}
}

func TestLifecycleOrderWindow(t *testing.T) {
	if got := lifecycleOrderWindow("capacity-baseline"); got != 4 {
		t.Errorf("lifecycleOrderWindow capacity-baseline = %d, want 4", got)
	}
	if got := lifecycleOrderWindow("strict-lifecycle"); got != 8 {
		t.Errorf("lifecycleOrderWindow strict-lifecycle = %d, want 8", got)
	}
}

func TestIsTerminalOrderRejection(t *testing.T) {
	if !isTerminalOrderRejection("INVALID_STATE") || !isTerminalOrderRejection("NOT_FOUND") {
		t.Error("expected known terminal codes to be terminal")
	}
	if isTerminalOrderRejection("VALIDATION_ERROR") {
		t.Error("expected unknown code to be non-terminal")
	}
}

func TestIsLifecycleManagedMode(t *testing.T) {
	if !isLifecycleManagedMode("strict-lifecycle") || !isLifecycleManagedMode("capacity-baseline") {
		t.Error("expected known lifecycle modes to be managed")
	}
	if isLifecycleManagedMode("default") {
		t.Error("expected default mode to be unmanaged")
	}
}

func TestRemoveOrder(t *testing.T) {
	orders := []trackedOrder{{OrderID: "a"}, {OrderID: "b"}, {OrderID: "c"}}
	got := removeOrder(orders, "b")
	if len(got) != 2 || got[0].OrderID != "a" || got[1].OrderID != "c" {
		t.Errorf("removeOrder = %#v", got)
	}
	unchanged := removeOrder(orders, "not-present")
	if len(unchanged) != 3 {
		t.Errorf("removeOrder missing id should be unchanged, got %#v", unchanged)
	}
}

func TestProfileForWorker(t *testing.T) {
	cfg := Config{ProfileMixMM: 30, ProfileMixInst: 30, ProfileMixRetail: 20}
	cases := map[int]string{
		0:  profileMarketMaker,
		29: profileMarketMaker,
		30: profileInstitutional,
		59: profileInstitutional,
		60: profileRetail,
		79: profileRetail,
		80: profileNoise,
		99: profileNoise,
	}
	for workerID, want := range cases {
		if got := profileForWorker(workerID, 100, cfg); got != want {
			t.Errorf("profileForWorker(%d) = %s, want %s", workerID, got, want)
		}
	}
}

func TestReverseTrades(t *testing.T) {
	values := []trade{{TradeID: "1"}, {TradeID: "2"}, {TradeID: "3"}}
	reverseTrades(values)
	if values[0].TradeID != "3" || values[2].TradeID != "1" {
		t.Errorf("reverseTrades = %#v", values)
	}
	empty := []trade{}
	reverseTrades(empty)
}

func TestReverseEvents(t *testing.T) {
	values := []event{{EventID: "1"}, {EventID: "2"}, {EventID: "3"}, {EventID: "4"}}
	reverseEvents(values)
	if values[0].EventID != "4" || values[3].EventID != "1" {
		t.Errorf("reverseEvents = %#v", values)
	}
}

func TestChooseSide(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	seen := map[string]bool{}
	for i := 0; i < 50; i++ {
		side := chooseSide(rng)
		if side != "BUY" && side != "SELL" {
			t.Fatalf("unexpected side: %s", side)
		}
		seen[side] = true
	}
	if len(seen) != 2 {
		t.Error("expected both BUY and SELL to occur over 50 draws")
	}
}

func TestRandomInt(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	if got := randomInt(rng, 5, 5); got != 5 {
		t.Errorf("randomInt(5,5) = %d, want 5", got)
	}
	for i := 0; i < 20; i++ {
		got := randomInt(rng, 1, 10)
		if got < 1 || got > 10 {
			t.Fatalf("randomInt out of range: %d", got)
		}
	}
}

func TestProfileQuantity(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	cfg := Config{QuantityMin: 1, QuantityMax: 500}
	for _, profile := range []string{profileMarketMaker, profileInstitutional, profileRetail, profileNoise} {
		for i := 0; i < 5; i++ {
			got := profileQuantity(rng, cfg, profile)
			if got < 0 {
				t.Errorf("profileQuantity(%s) negative: %d", profile, got)
			}
		}
	}
}

func TestRandomInt64(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	if got := randomInt64(rng, 5, 5); got != 5 {
		t.Errorf("randomInt64(5,5) = %d, want 5", got)
	}
	for i := 0; i < 20; i++ {
		got := randomInt64(rng, 1, 100)
		if got < 1 || got > 100 {
			t.Fatalf("randomInt64 out of range: %d", got)
		}
	}
}

func TestProfilePrice(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	cfg := Config{PriceMin: 100, PriceMax: 1000}
	for _, profile := range []string{profileMarketMaker, profileInstitutional} {
		got := profilePrice(rng, cfg, profile, nil)
		if got < 0 {
			t.Errorf("profilePrice(%s) negative: %d", profile, got)
		}
	}

	instrument := &sessionconfig.Equity{StartingPriceNanos: 500, VolatilityBps: 100}
	got := profilePrice(rng, cfg, profileRetail, instrument)
	if got <= 0 {
		t.Errorf("profilePrice with instrument non-positive: %d", got)
	}
}

func TestNormalizeProfile(t *testing.T) {
	if got := normalizeProfile("market_maker"); got != profileMarketMaker {
		t.Errorf("normalizeProfile(market_maker) = %s, want %s", got, profileMarketMaker)
	}
	if got := normalizeProfile("retail"); got != "retail" {
		t.Errorf("normalizeProfile passthrough = %s", got)
	}
}

func TestMinIntHelper(t *testing.T) {
	if minInt(3, 5) != 3 || minInt(5, 3) != 3 {
		t.Error("minInt failed")
	}
}

func TestMaxIntHelper(t *testing.T) {
	if maxInt(3, 5) != 5 || maxInt(5, 3) != 5 {
		t.Error("maxInt failed")
	}
}

func TestMaxInt64Helper(t *testing.T) {
	if maxInt64(3, 5) != 5 || maxInt64(5, 3) != 5 {
		t.Error("maxInt64 failed")
	}
}

func TestValidTransport(t *testing.T) {
	if !validTransport("http") || !validTransport("STREAM") || !validTransport(" stream ") {
		t.Error("expected known transports to validate")
	}
	if validTransport("carrier-pigeon") {
		t.Error("expected unknown transport to be invalid")
	}
}

func TestCommandRoute(t *testing.T) {
	apiV1 := Config{UseApiV1: true}
	if commandRoute(apiV1, ActionSubmit) != "/api/v1/orders/submit" {
		t.Error("expected api-v1 submit route")
	}
	if commandRoute(apiV1, ActionModify) != "/api/v1/orders/modify" {
		t.Error("expected api-v1 modify route")
	}
	if commandRoute(apiV1, ActionCancel) != "/api/v1/orders/cancel" {
		t.Error("expected api-v1 cancel route")
	}

	legacy := Config{UseApiV1: false}
	if commandRoute(legacy, ActionSubmit) != "/orders/submit" {
		t.Error("expected legacy submit route")
	}
	if commandRoute(legacy, ActionModify) != "/orders/modify" {
		t.Error("expected legacy modify route")
	}
	if commandRoute(legacy, ActionCancel) != "/orders/cancel" {
		t.Error("expected legacy cancel route")
	}
}

func TestCommandHeaders(t *testing.T) {
	apiV1 := Config{UseApiV1: true, ClientIDPrefix: "bot"}
	headers := commandHeaders(apiV1, 3, "cmd-1", "trace-1")
	if headers["X-Client-Id"] != "bot-3" || headers["Idempotency-Key"] != "cmd-1" || headers["X-Correlation-Id"] != "trace-1" {
		t.Errorf("unexpected api-v1 headers: %#v", headers)
	}

	legacyInternal := Config{UseApiV1: false, LegacyInternalRoute: true}
	headers = commandHeaders(legacyInternal, 0, "cmd-1", "trace-1")
	if headers["X-Reef-Internal-Route"] != "true" {
		t.Errorf("expected internal route header, got %#v", headers)
	}

	legacy := Config{UseApiV1: false, LegacyInternalRoute: false}
	if headers := commandHeaders(legacy, 0, "cmd-1", "trace-1"); headers != nil {
		t.Errorf("expected nil headers for plain legacy route, got %#v", headers)
	}
}

func TestShouldAllowLifecycleActionNonManagedModeAlwaysAllows(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	cfg := Config{Mode: "default", StrictMinLiveOrders: 4}
	state := workerState{orders: make([]trackedOrder, 100)}
	for i := 0; i < 20; i++ {
		if !shouldAllowLifecycleAction(rng, cfg, state) {
			t.Fatal("expected non-lifecycle-managed mode to always allow the action")
		}
	}
}

func TestShouldAllowLifecycleActionDeniesBelowMinLiveOrders(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	cfg := Config{Mode: "strict-lifecycle", StrictMinLiveOrders: 4}
	state := workerState{orders: make([]trackedOrder, 2)}
	if shouldAllowLifecycleAction(rng, cfg, state) {
		t.Fatal("expected action to be denied below the strict minimum live-order depth")
	}
}

// TestShouldAllowLifecycleActionRespectsProbabilityBounds exercises the
// capacity-baseline and strict-lifecycle allow-percentage branches
// (including the reject-streak penalty and the floor at 5%) by sampling many
// draws from a fixed-seed RNG and checking the observed allow-rate is within
// a wide tolerance of the expected percentage, following this file's
// existing convention of statistical assertions over many draws.
func TestShouldAllowLifecycleActionRespectsProbabilityBounds(t *testing.T) {
	cases := []struct {
		name        string
		cfg         Config
		state       workerState
		expectedPct float64
	}{
		{
			name:        "strict-lifecycle base rate",
			cfg:         Config{Mode: "strict-lifecycle", StrictMinLiveOrders: 4},
			state:       workerState{orders: make([]trackedOrder, 4)},
			expectedPct: 40,
		},
		{
			name:        "strict-lifecycle high depth boosts rate",
			cfg:         Config{Mode: "strict-lifecycle", StrictMinLiveOrders: 4},
			state:       workerState{orders: make([]trackedOrder, 12)},
			expectedPct: 55,
		},
		{
			name:        "strict-lifecycle reject streak penalty",
			cfg:         Config{Mode: "strict-lifecycle", StrictMinLiveOrders: 4},
			state:       workerState{orders: make([]trackedOrder, 4), rejectStreak: 1},
			expectedPct: 20,
		},
		{
			name:        "capacity-baseline base rate",
			cfg:         Config{Mode: "capacity-baseline", StrictMinLiveOrders: 4},
			state:       workerState{orders: make([]trackedOrder, 4)},
			expectedPct: 18,
		},
		{
			name:        "capacity-baseline mid depth",
			cfg:         Config{Mode: "capacity-baseline", StrictMinLiveOrders: 4},
			state:       workerState{orders: make([]trackedOrder, 8)},
			expectedPct: 24,
		},
		{
			name:        "capacity-baseline high depth",
			cfg:         Config{Mode: "capacity-baseline", StrictMinLiveOrders: 4},
			state:       workerState{orders: make([]trackedOrder, 12)},
			expectedPct: 30,
		},
		{
			name:        "capacity-baseline reject streak floors at 5",
			cfg:         Config{Mode: "capacity-baseline", StrictMinLiveOrders: 4},
			state:       workerState{orders: make([]trackedOrder, 4), rejectStreak: 1},
			expectedPct: 8,
		},
	}

	const trials = 4000
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rng := rand.New(rand.NewSource(42))
			allowed := 0
			for i := 0; i < trials; i++ {
				if shouldAllowLifecycleAction(rng, tc.cfg, tc.state) {
					allowed++
				}
			}
			observedPct := float64(allowed) / trials * 100
			if observedPct < tc.expectedPct-6 || observedPct > tc.expectedPct+6 {
				t.Fatalf("observed allow rate %.1f%%, want approximately %.1f%%", observedPct, tc.expectedPct)
			}
		})
	}
}

func TestRateSchedulerQuantum(t *testing.T) {
	if got := rateSchedulerQuantum(20_000); got != 5*time.Millisecond {
		t.Errorf("rateSchedulerQuantum(20000) = %v, want 5ms", got)
	}
	if got := rateSchedulerQuantum(10_000); got != 5*time.Millisecond {
		t.Errorf("rateSchedulerQuantum(10000) = %v, want 5ms", got)
	}
	if got := rateSchedulerQuantum(5_000); got != 10*time.Millisecond {
		t.Errorf("rateSchedulerQuantum(5000) = %v, want 10ms", got)
	}
	if got := rateSchedulerQuantum(1_000); got != 10*time.Millisecond {
		t.Errorf("rateSchedulerQuantum(1000) = %v, want 10ms", got)
	}
	if got := rateSchedulerQuantum(100); got != 20*time.Millisecond {
		t.Errorf("rateSchedulerQuantum(100) = %v, want 20ms", got)
	}
}

func TestRateChannelDepth(t *testing.T) {
	if got := rateChannelDepth(Config{RateQueueDepth: 50}); got != 50 {
		t.Errorf("rateChannelDepth explicit = %d, want 50", got)
	}
	if got := rateChannelDepth(Config{RateSchedule: rateSchedulePrecise, Workers: 10, RatePerSecond: 5}); got != 40 {
		t.Errorf("rateChannelDepth precise = %d, want 40", got)
	}
	if got := rateChannelDepth(Config{RateSchedule: rateSchedulePrecise, Workers: 1, RatePerSecond: 500}); got != 500 {
		t.Errorf("rateChannelDepth precise high rate = %d, want 500", got)
	}
	if got := rateChannelDepth(Config{RateSchedule: "burst"}); got != 1 {
		t.Errorf("rateChannelDepth default = %d, want 1", got)
	}
}

func TestTokenFeederStopsOnContextCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	out := make(chan struct{}, 100)
	counters := &loadScheduleCounters{}

	done := make(chan struct{})
	go func() {
		tokenFeeder(ctx, 1000, rateSchedulePrecise, out, counters)
		close(done)
	}()

	time.Sleep(30 * time.Millisecond)
	cancel()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("expected tokenFeeder to return after context cancellation")
	}

	if counters.scheduled == 0 {
		t.Error("expected some tokens to be scheduled before cancellation")
	}
}

func TestTokenFeederNonPreciseDropsWhenChannelFull(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	out := make(chan struct{}) // unbuffered so sends without a receiver drop immediately
	counters := &loadScheduleCounters{}

	done := make(chan struct{})
	go func() {
		tokenFeeder(ctx, 5000, "burst", out, counters)
		close(done)
	}()

	time.Sleep(30 * time.Millisecond)
	cancel()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("expected tokenFeeder to return after context cancellation")
	}

	if counters.dropped == 0 {
		t.Error("expected some tokens to be dropped when the output channel has no receiver")
	}
}

func TestBatchedTokenFeederZeroRateReturnsImmediately(t *testing.T) {
	ctx := context.Background()
	out := make(chan struct{}, 1)
	done := make(chan struct{})
	go func() {
		batchedTokenFeeder(ctx, 0, out, nil, false)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("expected batchedTokenFeeder to return immediately for rate <= 0")
	}
}
