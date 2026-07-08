package main

import (
	"context"
	"math/rand"
	"strings"
	"sync/atomic"
	"time"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
	"github.com/dills122/reef/services/simulator/internal/strategy"
)

const (
	rateScheduleDrop    = "drop"
	rateSchedulePrecise = "precise"
)

func chooseAction(rng *rand.Rand, cfg Config, hasOrders bool) Action {
	if isLifecycleManagedMode(cfg.Mode) && !hasOrders {
		return ActionSubmit
	}
	if cfg.Mode == "capacity-baseline" {
		return weightedAction(rng, 94, 4)
	}
	n := rng.Intn(100)
	if n < cfg.SubmitPct {
		return ActionSubmit
	}
	if n < cfg.SubmitPct+cfg.ModifyPct {
		return ActionModify
	}
	return ActionCancel
}

func chooseActionForProfile(rng *rand.Rand, cfg Config, hasOrders bool, profile string) Action {
	if isLifecycleManagedMode(cfg.Mode) && !hasOrders {
		return ActionSubmit
	}
	if cfg.ActionMixOverride {
		return chooseAction(rng, cfg, hasOrders)
	}
	if !cfg.HasSessionConfig {
		return chooseAction(rng, cfg, hasOrders)
	}
	if cfg.Mode == "capacity-baseline" {
		return weightedAction(rng, 94, 4)
	}
	switch profile {
	case profileMarketMaker:
		return weightedAction(rng, 45, 40)
	case profileInstitutional:
		return weightedAction(rng, 55, 30)
	case profileRetail:
		return weightedAction(rng, 70, 20)
	case profileNoise:
		return weightedAction(rng, 50, 20)
	default:
		return chooseAction(rng, cfg, hasOrders)
	}
}

func chooseActionForActor(rng *rand.Rand, cfg Config, hasOrders bool, profile string, actor *sessionconfig.Actor) Action {
	if cfg.ActionMixOverride {
		return chooseActionForProfile(rng, cfg, hasOrders, profile)
	}
	if mix, ok := strategy.ResolveActionMix(actor, cfg.StrategyProfiles); ok {
		if isLifecycleManagedMode(cfg.Mode) && !hasOrders {
			return ActionSubmit
		}
		return weightedAction(rng, mix.SubmitPct, mix.ModifyPct)
	}
	return chooseActionForProfile(rng, cfg, hasOrders, normalizeProfile(profile))
}

func weightedAction(rng *rand.Rand, submitPct, modifyPct int) Action {
	n := rng.Intn(100)
	if n < submitPct {
		return ActionSubmit
	}
	if n < submitPct+modifyPct {
		return ActionModify
	}
	return ActionCancel
}

func pickOrder(rng *rand.Rand, orders []trackedOrder, mode string) trackedOrder {
	if len(orders) == 0 {
		return trackedOrder{}
	}
	if mode == "capacity-baseline" || mode == "strict-lifecycle" {
		start := recentOrderWindowStart(len(orders), lifecycleOrderWindow(mode))
		return orders[start+rng.Intn(len(orders)-start)]
	}
	return orders[rng.Intn(len(orders))]
}

func pickOrderIndex(rng *rand.Rand, orders []trackedOrder, mode string) int {
	if len(orders) == 0 {
		return 0
	}
	if mode == "capacity-baseline" || mode == "strict-lifecycle" {
		start := recentOrderWindowStart(len(orders), lifecycleOrderWindow(mode))
		return start + rng.Intn(len(orders)-start)
	}
	return rng.Intn(len(orders))
}

func lifecycleOrderWindow(mode string) int {
	if mode == "capacity-baseline" {
		return 4
	}
	return 8
}

func recentOrderWindowStart(total, window int) int {
	if total <= 1 || window <= 0 || total <= window {
		return 0
	}
	return total - window
}

func hasActionableOrders(cfg Config, state workerState) bool {
	if len(state.orders) == 0 {
		return false
	}
	if isLifecycleManagedMode(cfg.Mode) {
		return len(state.orders) >= cfg.StrictMinLiveOrders
	}
	return true
}

func shouldAllowLifecycleAction(rng *rand.Rand, cfg Config, state workerState) bool {
	if !isLifecycleManagedMode(cfg.Mode) {
		return true
	}
	if len(state.orders) < cfg.StrictMinLiveOrders {
		return false
	}
	allowPct := 40
	if cfg.Mode == "capacity-baseline" {
		allowPct = 18
		if len(state.orders) >= cfg.StrictMinLiveOrders*3 {
			allowPct = 30
		} else if len(state.orders) >= cfg.StrictMinLiveOrders*2 {
			allowPct = 24
		}
	} else if len(state.orders) >= cfg.StrictMinLiveOrders*3 {
		allowPct = 55
	}
	if state.rejectStreak > 0 {
		if cfg.Mode == "capacity-baseline" {
			allowPct -= 10
		} else {
			allowPct -= 20
		}
	}
	if allowPct < 5 {
		allowPct = 5
	}
	return rng.Intn(100) < allowPct
}

func updateRecoveryState(state *workerState, cfg Config) {
	if !isLifecycleManagedMode(cfg.Mode) {
		return
	}
	state.rejectStreak++
	state.submitOnlyTicks = maxInt(state.submitOnlyTicks, lifecycleRecoveryTicks(cfg.Mode))
	if state.rejectStreak >= 3 {
		if cfg.Mode == "capacity-baseline" {
			state.submitOnlyTicks = 36
		} else {
			state.submitOnlyTicks = 20
		}
		state.rejectStreak = 0
	}
}

func lifecycleRecoveryTicks(mode string) int {
	if mode == "capacity-baseline" {
		return 12
	}
	return 8
}

func compactTrackedOrders(orders []trackedOrder, cfg Config) []trackedOrder {
	if !isLifecycleManagedMode(cfg.Mode) {
		return orders
	}
	maxTracked := maxInt(cfg.StrictMinLiveOrders*2, 32)
	if cfg.Mode == "capacity-baseline" {
		maxTracked = maxInt(cfg.StrictMinLiveOrders*2, 16)
	}
	if len(orders) <= maxTracked {
		return orders
	}
	return append([]trackedOrder(nil), orders[len(orders)-maxTracked:]...)
}

func isTerminalOrderRejection(code string) bool {
	return code == "INVALID_STATE" || code == "NOT_FOUND"
}

func isLifecycleManagedMode(mode string) bool {
	return mode == "strict-lifecycle" || mode == "capacity-baseline"
}

func shouldPruneTerminalOrder(mode string) bool {
	return isLifecycleManagedMode(mode)
}

func removeOrder(orders []trackedOrder, orderID string) []trackedOrder {
	for i, existing := range orders {
		if existing.OrderID == orderID {
			return append(orders[:i], orders[i+1:]...)
		}
	}
	return orders
}

func profileForWorker(workerID, workers int, cfg Config) string {
	if workers <= 0 {
		return profileNoise
	}
	pct := workerID * 100 / workers
	if pct < cfg.ProfileMixMM {
		return profileMarketMaker
	}
	if pct < cfg.ProfileMixMM+cfg.ProfileMixInst {
		return profileInstitutional
	}
	if pct < cfg.ProfileMixMM+cfg.ProfileMixInst+cfg.ProfileMixRetail {
		return profileRetail
	}
	return profileNoise
}

func reverseTrades(values []trade) {
	for i, j := 0, len(values)-1; i < j; i, j = i+1, j-1 {
		values[i], values[j] = values[j], values[i]
	}
}

func reverseEvents(values []event) {
	for i, j := 0, len(values)-1; i < j; i, j = i+1, j-1 {
		values[i], values[j] = values[j], values[i]
	}
}

func chooseSide(rng *rand.Rand) string {
	if rng.Intn(2) == 0 {
		return "BUY"
	}
	return "SELL"
}

func chooseSideForConfig(rng *rand.Rand, cfg Config) string {
	if cfg.HasSessionConfig {
		if cfg.SideBiasBuyPct <= 0 {
			return "SELL"
		}
		if cfg.SideBiasBuyPct >= 100 {
			return "BUY"
		}
		if rng.Intn(100) < cfg.SideBiasBuyPct {
			return "BUY"
		}
		return "SELL"
	}
	return chooseSide(rng)
}

func chooseSessionActor(rng *rand.Rand, cfg Config) *sessionconfig.Actor {
	if !cfg.HasSessionConfig || len(cfg.SessionActors) == 0 {
		return nil
	}
	total := 0
	for _, actor := range cfg.SessionActors {
		total += actor.Weight
	}
	if total <= 0 {
		return nil
	}
	pick := rng.Intn(total)
	running := 0
	for i := range cfg.SessionActors {
		running += cfg.SessionActors[i].Weight
		if pick < running {
			return &cfg.SessionActors[i]
		}
	}
	return &cfg.SessionActors[len(cfg.SessionActors)-1]
}

func randomInt(rng *rand.Rand, min, max int) int {
	if max <= min {
		return min
	}
	return min + rng.Intn(max-min+1)
}

func profileQuantity(rng *rand.Rand, cfg Config, profile string) int {
	switch profile {
	case profileMarketMaker:
		return profileQuantityBand(rng, cfg, 25, 250)
	case profileInstitutional:
		return profileQuantityBand(rng, cfg, 200, cfg.QuantityMax)
	case profileRetail:
		return profileQuantityBand(rng, cfg, cfg.QuantityMin, 100)
	default:
		return randomInt(rng, cfg.QuantityMin, cfg.QuantityMax)
	}
}

func profileQuantityBand(rng *rand.Rand, cfg Config, preferredMin, preferredMax int) int {
	minValue := maxInt(cfg.QuantityMin, preferredMin)
	maxValue := minInt(cfg.QuantityMax, preferredMax)
	if maxValue < minValue {
		minValue = cfg.QuantityMin
		maxValue = cfg.QuantityMax
	}
	return randomInt(rng, minValue, maxValue)
}

func randomInt64(rng *rand.Rand, min, max int64) int64 {
	if max <= min {
		return min
	}
	return min + rng.Int63n(max-min+1)
}

func profilePrice(rng *rand.Rand, cfg Config, profile string, instrument *sessionconfig.Equity) int64 {
	if instrument != nil {
		base := instrument.StartingPriceNanos
		if base <= 0 {
			base = cfg.PriceMin
		}
		volBps := instrument.VolatilityBps
		if volBps <= 0 {
			volBps = 100
		}
		span := (base * int64(volBps)) / 10_000
		if span <= 0 {
			span = maxInt64(1, (cfg.PriceMax-cfg.PriceMin)/10)
		}
		return randomInt64(rng, maxInt64(1, base-span), base+span)
	}
	switch profile {
	case profileMarketMaker:
		mid := (cfg.PriceMin + cfg.PriceMax) / 2
		span := (cfg.PriceMax - cfg.PriceMin) / 8
		if span <= 0 {
			return mid
		}
		return randomInt64(rng, mid-span, mid+span)
	default:
		return randomInt64(rng, cfg.PriceMin, cfg.PriceMax)
	}
}

func chooseInstrumentForActor(rng *rand.Rand, cfg Config, actor *sessionconfig.Actor) *sessionconfig.Equity {
	if !cfg.HasSessionConfig || len(cfg.MarketEquities) == 0 {
		return nil
	}
	if actor == nil || len(actor.Symbols) == 0 {
		return &cfg.MarketEquities[rng.Intn(len(cfg.MarketEquities))]
	}
	eligible := make([]sessionconfig.Equity, 0, len(cfg.MarketEquities))
	allow := make(map[string]struct{}, len(actor.Symbols))
	for _, symbol := range actor.Symbols {
		allow[symbol] = struct{}{}
	}
	for _, eq := range cfg.MarketEquities {
		if _, ok := allow[eq.Symbol]; ok {
			eligible = append(eligible, eq)
		}
	}
	if len(eligible) == 0 {
		return &cfg.MarketEquities[rng.Intn(len(cfg.MarketEquities))]
	}
	return &eligible[rng.Intn(len(eligible))]
}

func shouldInjectFault(rng *rand.Rand, cfg Config, faultType, instrumentID string) bool {
	if !cfg.HasSessionConfig || len(cfg.Faults) == 0 {
		return false
	}
	symbol := instrumentID
	for _, eq := range cfg.MarketEquities {
		if eq.InstrumentID == instrumentID {
			symbol = eq.Symbol
			break
		}
	}
	for _, fault := range cfg.Faults {
		if fault.Type != faultType {
			continue
		}
		if fault.Symbol != "" && fault.Symbol != symbol {
			continue
		}
		probability := fault.Probability
		if probability <= 0 {
			probability = 1.0
		}
		if rng.Float64() <= probability {
			return true
		}
	}
	return false
}

func normalizeProfile(profile string) string {
	switch profile {
	case "market_maker":
		return profileMarketMaker
	default:
		return profile
	}
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func maxInt64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

func isValidRateSchedule(schedule string) bool {
	switch strings.ToLower(strings.TrimSpace(schedule)) {
	case rateScheduleDrop, rateSchedulePrecise:
		return true
	default:
		return false
	}
}

func rateChannelDepth(cfg Config) int {
	if cfg.RateQueueDepth > 0 {
		return cfg.RateQueueDepth
	}
	if strings.EqualFold(strings.TrimSpace(cfg.RateSchedule), rateSchedulePrecise) {
		return maxInt(maxInt(cfg.Workers*4, cfg.RatePerSecond), 1)
	}
	return 1
}

func tokenFeeder(ctx context.Context, rate int, schedule string, out chan<- struct{}, counters *loadScheduleCounters) {
	if strings.EqualFold(strings.TrimSpace(schedule), rateSchedulePrecise) {
		batchedTokenFeeder(ctx, rate, out, counters, true)
		return
	}
	batchedTokenFeeder(ctx, rate, out, counters, false)
}

func batchedTokenFeeder(ctx context.Context, rate int, out chan<- struct{}, counters *loadScheduleCounters, blockWhenFull bool) {
	if rate <= 0 {
		return
	}
	started := time.Now()
	ticker := time.NewTicker(rateSchedulerQuantum(rate))
	defer ticker.Stop()
	var scheduled int64
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			target := int64(time.Since(started).Seconds() * float64(rate))
			for scheduled < target {
				scheduled++
				if counters != nil {
					atomic.AddInt64(&counters.scheduled, 1)
				}
				if blockWhenFull {
					select {
					case <-ctx.Done():
						return
					case out <- struct{}{}:
						if counters != nil {
							atomic.AddInt64(&counters.enqueued, 1)
						}
					}
					continue
				}
				select {
				case out <- struct{}{}:
					if counters != nil {
						atomic.AddInt64(&counters.enqueued, 1)
					}
				default:
					if counters != nil {
						atomic.AddInt64(&counters.dropped, 1)
					}
				}
			}
		}
	}
}

func rateSchedulerQuantum(rate int) time.Duration {
	switch {
	case rate >= 10_000:
		return 5 * time.Millisecond
	case rate >= 1_000:
		return 10 * time.Millisecond
	default:
		return 20 * time.Millisecond
	}
}
