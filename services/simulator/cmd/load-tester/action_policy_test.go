package main

import (
	"math/rand"
	"testing"

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
