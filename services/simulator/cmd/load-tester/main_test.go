package main

import (
	"fmt"
	"math"
	"math/rand"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
)

func TestChooseSessionActorWeighted(t *testing.T) {
	cfg := Config{
		HasSessionConfig: true,
		SessionActors: []sessionconfig.Actor{
			{ActorID: "a", ActorType: "market_maker", Weight: 80},
			{ActorID: "b", ActorType: "retail", Weight: 20},
		},
	}
	rng := rand.New(rand.NewSource(42))
	countA := 0
	countB := 0
	for i := 0; i < 1000; i++ {
		picked := chooseSessionActor(rng, cfg)
		if picked == nil {
			t.Fatal("expected actor")
		}
		switch picked.ActorID {
		case "a":
			countA++
		case "b":
			countB++
		}
	}
	if countA <= countB {
		t.Fatalf("expected actor a to dominate: a=%d b=%d", countA, countB)
	}
}

func TestChooseSideForConfigRespectsBias(t *testing.T) {
	cfg := Config{HasSessionConfig: true, SideBiasBuyPct: 100}
	rng := rand.New(rand.NewSource(7))
	for i := 0; i < 20; i++ {
		if got := chooseSideForConfig(rng, cfg); got != "BUY" {
			t.Fatalf("expected BUY, got %s", got)
		}
	}
}

func TestChooseInstrumentForActorEligibility(t *testing.T) {
	cfg := Config{
		HasSessionConfig: true,
		MarketEquities: []sessionconfig.Equity{
			{Symbol: "AAPL", InstrumentID: "AAPL", StartingPriceNanos: 100},
			{Symbol: "MSFT", InstrumentID: "MSFT", StartingPriceNanos: 200},
		},
	}
	actor := &sessionconfig.Actor{ActorID: "mm-1", Symbols: []string{"MSFT"}}
	rng := rand.New(rand.NewSource(11))
	for i := 0; i < 20; i++ {
		eq := chooseInstrumentForActor(rng, cfg, actor)
		if eq == nil || eq.Symbol != "MSFT" {
			t.Fatalf("expected MSFT, got %+v", eq)
		}
	}
}

func TestBuildSummaryIncludesActorAndStrategyAttribution(t *testing.T) {
	start := time.Date(2026, 5, 23, 12, 0, 0, 0, time.UTC)
	end := start.Add(2 * time.Second)
	results := []requestResult{
		{ActorID: "mm-1", Persona: "electronic_liquidity_provider", StrategyID: "two_sided_quote", Profile: "market_maker", Action: ActionSubmit, Success: true, Latency: 10 * time.Millisecond, StatusCode: 200},
		{ActorID: "mm-1", Persona: "electronic_liquidity_provider", StrategyID: "two_sided_quote", Profile: "market_maker", Action: ActionModify, Success: false, Latency: 20 * time.Millisecond, StatusCode: 409, ErrorText: "rejected"},
		{ActorID: "ret-1", Persona: "dip_buyer", StrategyID: "dip_buyer", Profile: "retail", Action: ActionSubmit, Success: true, Latency: 12 * time.Millisecond, StatusCode: 200},
	}
	report := buildSummary("s-1", start, end, Config{}, results)
	if report.ByActor["mm-1"].Requests != 2 {
		t.Fatalf("expected actor attribution, got %+v", report.ByActor["mm-1"])
	}
	if report.ByStrategy["two_sided_quote"].Requests != 2 {
		t.Fatalf("expected strategy attribution, got %+v", report.ByStrategy["two_sided_quote"])
	}
	if report.ByPersona["electronic_liquidity_provider"].Requests != 2 {
		t.Fatalf("expected persona attribution, got %+v", report.ByPersona["electronic_liquidity_provider"])
	}
}

func TestBuildSummaryIncludesRejectTaxonomyPercentages(t *testing.T) {
	start := time.Date(2026, 5, 23, 12, 0, 0, 0, time.UTC)
	end := start.Add(2 * time.Second)
	results := []requestResult{
		{Profile: "retail", Action: ActionSubmit, Success: false, Latency: 10 * time.Millisecond, StatusCode: 409, ErrorText: "rejected:INVALID_STATE", RejectCode: "INVALID_STATE"},
		{Profile: "retail", Action: ActionModify, Success: false, Latency: 10 * time.Millisecond, StatusCode: 409, ErrorText: "rejected:INVALID_STATE", RejectCode: "INVALID_STATE"},
		{Profile: "retail", Action: ActionCancel, Success: false, Latency: 10 * time.Millisecond, StatusCode: 409, ErrorText: "rejected:NOT_FOUND", RejectCode: "NOT_FOUND"},
		{Profile: "retail", Action: ActionSubmit, Success: false, Latency: 10 * time.Millisecond, StatusCode: 500, ErrorText: "http 500"},
	}
	report := buildSummary("s-1", start, end, Config{}, results)
	if len(report.RejectTaxonomy) != 2 {
		t.Fatalf("expected 2 reject taxonomy rows, got %d", len(report.RejectTaxonomy))
	}
	first := report.RejectTaxonomy[0]
	second := report.RejectTaxonomy[1]
	if first.Code != "INVALID_STATE" || first.Count != 2 {
		t.Fatalf("unexpected first reject taxonomy row: %+v", first)
	}
	if !approxEqual(first.PercentOfFailures, 50) || !approxEqual(first.PercentOfRejects, 66.66666666666666) {
		t.Fatalf("unexpected first row percentages: %+v", first)
	}
	if second.Code != "NOT_FOUND" || second.Count != 1 {
		t.Fatalf("unexpected second reject taxonomy row: %+v", second)
	}
	if !approxEqual(second.PercentOfFailures, 25) || !approxEqual(second.PercentOfRejects, 33.33333333333333) {
		t.Fatalf("unexpected second row percentages: %+v", second)
	}
}

func TestFillResultParsesBoundaryErrorEnvelope(t *testing.T) {
	result := requestResult{}
	fillResult(
		&result,
		429,
		[]byte(`{"code":"ABUSE_BLOCKED","message":"temporarily blocked","correlationId":"trace-1"}`),
		nil,
		time.Now(),
	)
	if result.ErrorText != "http_429:ABUSE_BLOCKED" {
		t.Fatalf("unexpected error text: %s", result.ErrorText)
	}
	if result.RejectCode != "ABUSE_BLOCKED" {
		t.Fatalf("expected reject code ABUSE_BLOCKED, got %s", result.RejectCode)
	}
	if result.RejectReason != "temporarily blocked" {
		t.Fatalf("expected reject reason to be populated, got %s", result.RejectReason)
	}
}

func TestFillResultParsesRuntimeRejectedEnvelope(t *testing.T) {
	result := requestResult{}
	fillResult(
		&result,
		200,
		[]byte(`{"rejected":{"code":"INVALID_STATE","reason":"order already terminal"}}`),
		nil,
		time.Now(),
	)
	if result.Success {
		t.Fatal("expected rejected response to remain unsuccessful")
	}
	if result.ErrorText != "rejected:INVALID_STATE" {
		t.Fatalf("unexpected error text: %s", result.ErrorText)
	}
	if result.RejectCode != "INVALID_STATE" {
		t.Fatalf("expected reject code INVALID_STATE, got %s", result.RejectCode)
	}
	if result.RejectReason != "order already terminal" {
		t.Fatalf("expected reject reason from payload, got %s", result.RejectReason)
	}
}

func approxEqual(a, b float64) bool {
	return math.Abs(a-b) < 0.000001
}

func TestDeterministicSelectionWithFixedSeed(t *testing.T) {
	cfg := Config{
		HasSessionConfig: true,
		Seed:             424242,
		SubmitPct:        60,
		ModifyPct:        30,
		CancelPct:        10,
		SideBiasBuyPct:   65,
		SessionActors: []sessionconfig.Actor{
			{ActorID: "a", ActorType: "market_maker", Weight: 70},
			{ActorID: "b", ActorType: "retail", Weight: 30},
		},
		MarketEquities: []sessionconfig.Equity{
			{Symbol: "AAPL", InstrumentID: "AAPL", StartingPriceNanos: 100, VolatilityBps: 100},
			{Symbol: "MSFT", InstrumentID: "MSFT", StartingPriceNanos: 200, VolatilityBps: 100},
		},
	}
	seq1 := generateDecisionSequence(cfg, 0, 20)
	seq2 := generateDecisionSequence(cfg, 0, 20)
	for i := range seq1 {
		if seq1[i] != seq2[i] {
			t.Fatalf("sequence mismatch at %d: %q vs %q", i, seq1[i], seq2[i])
		}
	}
}

func TestChooseActionForActorStrategyMix(t *testing.T) {
	cfg := Config{
		Mode: "chaos",
		StrategyProfiles: map[string]sessionconfig.StrategyProfile{
			"mm-tight": {
				Strategy: "two_sided_quote",
				Params:   map[string]interface{}{"submitPct": 0, "modifyPct": 100, "cancelPct": 0},
			},
		},
	}
	actor := &sessionconfig.Actor{ActorID: "mm-1", StrategyID: "mm-tight", ActorType: "market_maker"}
	rng := rand.New(rand.NewSource(123))
	for i := 0; i < 10; i++ {
		action := chooseActionForActor(rng, cfg, true, "market_maker", actor)
		if action != ActionModify {
			t.Fatalf("expected strategy-driven modify action, got %s", action)
		}
	}
}

func TestChooseActionForActorDirectStrategyID(t *testing.T) {
	cfg := Config{Mode: "chaos"}
	actor := &sessionconfig.Actor{ActorID: "ret-1", StrategyID: "dip_buyer", ActorType: "retail"}
	rng := rand.New(rand.NewSource(77))
	for i := 0; i < 10; i++ {
		action := chooseActionForActor(rng, cfg, true, "retail", actor)
		if action == ActionCancel {
			t.Fatalf("expected dip_buyer strategy to bias away from cancel, got %s", action)
		}
	}
}

func TestChooseActionForProfileUsesConfigMixWhenNoSessionConfig(t *testing.T) {
	cfg := Config{
		HasSessionConfig: false,
		Mode:             "strict-lifecycle",
		SubmitPct:        100,
		ModifyPct:        0,
		CancelPct:        0,
	}
	rng := rand.New(rand.NewSource(9))
	for i := 0; i < 20; i++ {
		action := chooseActionForProfile(rng, cfg, true, profileMarketMaker)
		if action != ActionSubmit {
			t.Fatalf("expected config-driven submit action, got %s", action)
		}
	}
}

func generateDecisionSequence(cfg Config, workerID int, count int) []string {
	rng := rand.New(rand.NewSource(cfg.Seed + int64(workerID)*7919))
	out := make([]string, 0, count)
	for i := 0; i < count; i++ {
		actor := chooseSessionActor(rng, cfg)
		profile := "noise"
		if actor != nil {
			profile = actor.ActorType
		}
		action := chooseActionForProfile(rng, cfg, true, profile)
		instrument := chooseInstrumentForActor(rng, cfg, actor)
		side := chooseSideForConfig(rng, cfg)
		symbol := ""
		if instrument != nil {
			symbol = instrument.Symbol
		}
		out = append(out, actor.ActorID+"|"+string(action)+"|"+symbol+"|"+side)
	}
	return out
}

func TestBuildCommandPayloadIncludesScenarioMetadata(t *testing.T) {
	cfg := Config{ScenarioRunID: "sim-1", Seed: 4242}
	payload := buildCommandPayload(cfg, "cmd-1", "trace-1", "actor-1", "retail", "dip_buyer", "dip_buyer", 1)
	if payload["scenarioRunId"] != "sim-1" {
		t.Fatalf("expected scenarioRunId, got: %+v", payload)
	}
	if payload["seed"] != "4242" {
		t.Fatalf("expected seed metadata, got: %+v", payload)
	}
}

func TestBuildCommandPayloadUsesDeterministicCommandClock(t *testing.T) {
	cfg := Config{
		CommandClockStart: "2026-03-14T18:00:00Z",
		CommandClockStep:  2 * time.Second,
	}

	first := buildCommandPayload(cfg, "cmd-1", "trace-1", "actor-1", "retail", "", "", 1)
	third := buildCommandPayload(cfg, "cmd-3", "trace-3", "actor-1", "retail", "", "", 3)

	if first["occurredAt"] != "2026-03-14T18:00:00Z" {
		t.Fatalf("unexpected first command timestamp: %+v", first)
	}
	if third["occurredAt"] != "2026-03-14T18:00:04Z" {
		t.Fatalf("unexpected third command timestamp: %+v", third)
	}
}

func TestTopProfileKeysSortsByRequests(t *testing.T) {
	values := map[string]profileSummary{
		"a": {Requests: 2},
		"c": {Requests: 1},
		"b": {Requests: 2},
	}
	keys := topProfileKeys(values, 3)
	if len(keys) != 3 {
		t.Fatalf("unexpected keys length: %d", len(keys))
	}
	if keys[0] != "a" || keys[1] != "b" || keys[2] != "c" {
		t.Fatalf("unexpected order: %+v", keys)
	}
}

func TestShouldPruneTerminalOrder(t *testing.T) {
	if !shouldPruneTerminalOrder("strict-lifecycle") {
		t.Fatal("strict-lifecycle should prune terminal orders")
	}
	if !shouldPruneTerminalOrder("capacity-baseline") {
		t.Fatal("capacity-baseline should prune terminal orders")
	}
	if shouldPruneTerminalOrder("chaos") {
		t.Fatal("chaos should not prune terminal orders")
	}
}

func TestHasActionableOrders(t *testing.T) {
	cfg := Config{Mode: "strict-lifecycle", StrictMinLiveOrders: 2}
	if hasActionableOrders(cfg, workerState{orders: []string{"o1"}}) {
		t.Fatal("expected strict mode to require min live orders")
	}
	if !hasActionableOrders(cfg, workerState{orders: []string{"o1", "o2"}}) {
		t.Fatal("expected strict mode with sufficient orders to be actionable")
	}
	if !hasActionableOrders(Config{Mode: "chaos", StrictMinLiveOrders: 2}, workerState{orders: []string{"o1"}}) {
		t.Fatal("expected non-strict mode to allow any non-empty order set")
	}
}

func TestUpdateRecoveryState(t *testing.T) {
	state := workerState{rejectStreak: 2}
	updateRecoveryState(&state, Config{Mode: "strict-lifecycle"})
	if state.submitOnlyTicks != 20 || state.rejectStreak != 0 {
		t.Fatalf("expected recovery submit-only window, got %+v", state)
	}
	state = workerState{rejectStreak: 2}
	updateRecoveryState(&state, Config{Mode: "capacity-baseline"})
	if state.submitOnlyTicks != 36 || state.rejectStreak != 0 {
		t.Fatalf("expected stronger capacity-baseline recovery window, got %+v", state)
	}
	state = workerState{rejectStreak: 2}
	updateRecoveryState(&state, Config{Mode: "chaos"})
	if state.submitOnlyTicks != 0 || state.rejectStreak != 2 {
		t.Fatalf("expected no recovery mutation outside lifecycle-managed modes, got %+v", state)
	}
}

func TestShouldAllowLifecycleAction(t *testing.T) {
	cfg := Config{Mode: "strict-lifecycle", StrictMinLiveOrders: 4}
	if shouldAllowLifecycleAction(rand.New(rand.NewSource(12)), cfg, workerState{orders: []string{"o1", "o2", "o3"}}) {
		t.Fatal("expected lifecycle action to be blocked when live-order depth is below strict minimum")
	}
	if !shouldAllowLifecycleAction(rand.New(rand.NewSource(1)), Config{Mode: "chaos", StrictMinLiveOrders: 4}, workerState{orders: []string{"o1"}}) {
		t.Fatal("expected non-strict mode to allow lifecycle action")
	}
}

func TestCompactTrackedOrders(t *testing.T) {
	cfg := Config{Mode: "strict-lifecycle", StrictMinLiveOrders: 4}
	orders := make([]string, 0, 40)
	for i := 0; i < 40; i++ {
		orders = append(orders, fmt.Sprintf("o-%d", i))
	}
	compacted := compactTrackedOrders(orders, cfg)
	if len(compacted) != 32 {
		t.Fatalf("expected compacted length 32, got %d", len(compacted))
	}
	if compacted[0] != "o-8" || compacted[len(compacted)-1] != "o-39" {
		t.Fatalf("unexpected compacted order slice: first=%s last=%s", compacted[0], compacted[len(compacted)-1])
	}
	compactedCapacity := compactTrackedOrders(orders, Config{Mode: "capacity-baseline", StrictMinLiveOrders: 4})
	if len(compactedCapacity) != 16 {
		t.Fatalf("expected capacity mode compaction length 16, got %d", len(compactedCapacity))
	}
	if compactedCapacity[0] != "o-24" || compactedCapacity[len(compactedCapacity)-1] != "o-39" {
		t.Fatalf("unexpected capacity compacted order slice: first=%s last=%s", compactedCapacity[0], compactedCapacity[len(compactedCapacity)-1])
	}
}

func TestLifecycleOrderWindowByMode(t *testing.T) {
	if got := lifecycleOrderWindow("capacity-baseline"); got != 4 {
		t.Fatalf("expected capacity-baseline window=4, got %d", got)
	}
	if got := lifecycleOrderWindow("strict-lifecycle"); got != 8 {
		t.Fatalf("expected strict-lifecycle window=8, got %d", got)
	}
	if got := lifecycleOrderWindow("chaos"); got != 8 {
		t.Fatalf("expected default window=8, got %d", got)
	}
}

func TestRecentOrderWindowStart(t *testing.T) {
	if got := recentOrderWindowStart(40, 8); got != 32 {
		t.Fatalf("expected window start 32, got %d", got)
	}
	if got := recentOrderWindowStart(8, 8); got != 0 {
		t.Fatalf("expected full-window start at 0, got %d", got)
	}
	if got := recentOrderWindowStart(1, 8); got != 0 {
		t.Fatalf("expected singleton window start at 0, got %d", got)
	}
}

func TestApplyFlagOverridesUsesParsedValues(t *testing.T) {
	cfg := Config{Workers: 24, RatePerSecond: 450, RateSchedule: rateScheduleDrop, PrettySummary: false, ReportOut: ""}
	parsed := Config{Workers: 12, RatePerSecond: 150, RateSchedule: rateSchedulePrecise, PrettySummary: true, ReportOut: "/tmp/report.json"}
	explicit := map[string]bool{
		"workers":        true,
		"rate":           true,
		"rate-schedule":  true,
		"pretty-summary": true,
		"report-out":     true,
	}
	applyFlagOverrides(&cfg, parsed, explicit)
	if cfg.Workers != 12 || cfg.RatePerSecond != 150 || cfg.RateSchedule != rateSchedulePrecise || !cfg.PrettySummary || cfg.ReportOut != "/tmp/report.json" {
		t.Fatalf("overrides not applied correctly: %+v", cfg)
	}
}

func TestRateScheduleValidationAndChannelDepth(t *testing.T) {
	for _, schedule := range []string{rateScheduleDrop, rateSchedulePrecise, " PRECISE "} {
		if !isValidRateSchedule(schedule) {
			t.Fatalf("expected valid rate schedule: %q", schedule)
		}
	}
	if isValidRateSchedule("burst") {
		t.Fatal("unexpected valid rate schedule: burst")
	}
	if got := rateChannelDepth(Config{RateSchedule: rateScheduleDrop, Workers: 8}); got != 1 {
		t.Fatalf("expected drop scheduler channel depth=1, got %d", got)
	}
	if got := rateChannelDepth(Config{RateSchedule: rateSchedulePrecise, Workers: 8}); got != 16 {
		t.Fatalf("expected precise scheduler channel depth=16, got %d", got)
	}
	if got := rateChannelDepth(Config{RateSchedule: " PRECISE ", Workers: 8}); got != 16 {
		t.Fatalf("expected normalized precise scheduler channel depth=16, got %d", got)
	}
	if got := rateChannelDepth(Config{RateSchedule: rateSchedulePrecise, Workers: 0}); got != 1 {
		t.Fatalf("expected precise scheduler minimum channel depth=1, got %d", got)
	}
}

func TestBuildHTTPClientUsesConfiguredTransport(t *testing.T) {
	cfg := Config{
		RequestTimeout:      7 * time.Second,
		HTTPMaxIdleConns:    123,
		HTTPIdleConnsHost:   45,
		HTTPMaxConnsHost:    67,
		HTTPIdleConnTimeout: 22 * time.Second,
	}
	client := buildHTTPClient(cfg)
	if client.Timeout != 7*time.Second {
		t.Fatalf("unexpected client timeout: %s", client.Timeout)
	}
	transport, ok := client.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("expected *http.Transport, got %T", client.Transport)
	}
	if transport.MaxIdleConns != 123 {
		t.Fatalf("unexpected MaxIdleConns: %d", transport.MaxIdleConns)
	}
	if transport.MaxIdleConnsPerHost != 45 {
		t.Fatalf("unexpected MaxIdleConnsPerHost: %d", transport.MaxIdleConnsPerHost)
	}
	if transport.MaxConnsPerHost != 67 {
		t.Fatalf("unexpected MaxConnsPerHost: %d", transport.MaxConnsPerHost)
	}
	if transport.IdleConnTimeout != 22*time.Second {
		t.Fatalf("unexpected IdleConnTimeout: %s", transport.IdleConnTimeout)
	}
}

func TestDeterministicSequenceFromExampleSession(t *testing.T) {
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	sessionPath := filepath.Clean(filepath.Join(filepath.Dir(currentFile), "../../../../packages/scenario-definitions/persona-session.example.yaml"))
	if _, err := os.Stat(sessionPath); err != nil {
		t.Fatalf("missing scenario example file: %v", err)
	}
	loaded, err := sessionconfig.LoadSessionFile(sessionPath)
	if err != nil {
		t.Fatalf("LoadSessionFile error: %v", err)
	}
	runtimeCfg, err := sessionconfig.ToRuntimeConfig(loaded)
	if err != nil {
		t.Fatalf("ToRuntimeConfig error: %v", err)
	}
	cfg := mergeSessionConfig(defaultConfigFromEnv(), runtimeCfg)
	seq := generateDecisionSequence(cfg, 0, 8)
	expected := []string{
		"inst-mutual-01|submit|META|BUY",
		"inst-hedge-01|submit|META|BUY",
		"algo-momo-01|submit|NVDA|SELL",
		"algo-momo-01|submit|MSFT|SELL",
		"retail-passive-01|submit|META|SELL",
		"retail-breakout-01|submit|MSFT|SELL",
		"retail-dip-01|submit|AAPL|BUY",
		"mm-02|submit|AMZN|BUY",
	}
	if len(seq) != len(expected) {
		t.Fatalf("sequence length mismatch: got %d want %d", len(seq), len(expected))
	}
	for i := range expected {
		if seq[i] != expected[i] {
			t.Fatalf("sequence mismatch at %d: got %q want %q", i, seq[i], expected[i])
		}
	}
}

func TestShouldInjectFault(t *testing.T) {
	cfg := Config{
		HasSessionConfig: true,
		MarketEquities: []sessionconfig.Equity{
			{Symbol: "AAPL", InstrumentID: "AAPL"},
		},
		Faults: []sessionconfig.FaultRule{
			{ID: "f1", Type: "reject_submit", Probability: 1.0, Symbol: "AAPL"},
			{ID: "f2", Type: "reject_modify", Probability: 1.0, Symbol: "AAPL"},
		},
	}
	rng := rand.New(rand.NewSource(1))
	if !shouldInjectFault(rng, cfg, "reject_submit", "AAPL") {
		t.Fatal("expected fault injection for matching symbol and probability=1")
	}
	if !shouldInjectFault(rng, cfg, "reject_modify", "AAPL") {
		t.Fatal("expected modify fault injection for matching symbol and probability=1")
	}
	if shouldInjectFault(rng, cfg, "reject_cancel", "AAPL") {
		t.Fatal("did not expect cancel fault injection when rule is absent")
	}
}
