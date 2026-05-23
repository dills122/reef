package main

import (
	"math"
	"math/rand"
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
	payload := buildCommandPayload(cfg, "cmd-1", "trace-1", "actor-1", "retail", "dip_buyer", "dip_buyer")
	if payload["scenarioRunId"] != "sim-1" {
		t.Fatalf("expected scenarioRunId, got: %+v", payload)
	}
	if payload["seed"] != "4242" {
		t.Fatalf("expected seed metadata, got: %+v", payload)
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

func TestApplyFlagOverridesUsesParsedValues(t *testing.T) {
	cfg := Config{Workers: 24, RatePerSecond: 450, PrettySummary: false, ReportOut: ""}
	parsed := Config{Workers: 12, RatePerSecond: 150, PrettySummary: true, ReportOut: "/tmp/report.json"}
	explicit := map[string]bool{
		"workers":        true,
		"rate":           true,
		"pretty-summary": true,
		"report-out":     true,
	}
	applyFlagOverrides(&cfg, parsed, explicit)
	if cfg.Workers != 12 || cfg.RatePerSecond != 150 || !cfg.PrettySummary || cfg.ReportOut != "/tmp/report.json" {
		t.Fatalf("overrides not applied correctly: %+v", cfg)
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
