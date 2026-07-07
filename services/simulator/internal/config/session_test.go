package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const validYAML = `session:
  name: us-equities-persona-baseline
  scenarioRunId: sim-2026-05-22-a
  seed: 424242
  mode: capacity-baseline
runtime:
  baseUrl: http://localhost:8080
  duration: 60s
  workers: 24
  ratePerSecond: 450
  timeout: 5s
  traceCheckLimit: 100
market:
  timezone: America/New_York
  equities:
    - symbol: AAPL
      instrumentId: AAPL
      startingPriceNanos: 190000000000
      avgDailyVolume: 60000000
      sharesOutstanding: 15400000000
      marketCap: 2926000000000
      volatilityBps: 110
      spreadBps: 4
actors:
  - actorId: mm-01
    actorType: market_maker
    strategyId: two_sided_quote
    weight: 70
  - actorId: retail-01
    actorType: retail
    strategyId: dip_buyer
    weight: 30
mix:
  actions:
    submitPct: 82
    modifyPct: 12
    cancelPct: 6
  sideBias:
    buyPct: 51
    sellPct: 49
`

func TestLoadSessionFileYAML(t *testing.T) {
	path := writeFile(t, "session.yaml", validYAML)
	cfg, err := LoadSessionFile(path)
	if err != nil {
		t.Fatalf("LoadSessionFile error: %v", err)
	}
	if cfg.Session.Seed != 424242 {
		t.Fatalf("unexpected seed: %d", cfg.Session.Seed)
	}
	if got, want := len(cfg.Actors), 2; got != want {
		t.Fatalf("actors: got %d want %d", got, want)
	}
}

func TestLoadSessionFileJSON(t *testing.T) {
	jsonInput := `{"session":{"seed":42,"mode":"chaos"},"runtime":{"baseUrl":"http://localhost:8080","duration":"30s","workers":2,"ratePerSecond":0,"timeout":"5s","traceCheckLimit":10},"market":{"equities":[{"symbol":"AAPL","instrumentId":"AAPL","startingPriceNanos":190000000000,"volatilityBps":100,"spreadBps":5}]},"actors":[{"actorId":"a-1","actorType":"retail","strategyId":"dip_buyer","weight":100}],"mix":{"actions":{"submitPct":60,"modifyPct":30,"cancelPct":10},"sideBias":{"buyPct":50,"sellPct":50}}}`
	path := writeFile(t, "session.json", jsonInput)
	_, err := LoadSessionFile(path)
	if err != nil {
		t.Fatalf("LoadSessionFile error: %v", err)
	}
}

func TestLoadSessionFileAllowsDisabledTraceChecks(t *testing.T) {
	input := strings.Replace(validYAML, "traceCheckLimit: 100", "traceCheckLimit: 0", 1)
	path := writeFile(t, "trace-disabled.yaml", input)
	_, err := LoadSessionFile(path)
	if err != nil {
		t.Fatalf("LoadSessionFile error: %v", err)
	}
}

func TestValidateSessionFileRejectsBadMix(t *testing.T) {
	broken := strings.Replace(validYAML, "submitPct: 82", "submitPct: 70", 1)
	path := writeFile(t, "bad.yaml", broken)
	_, err := LoadSessionFile(path)
	if err == nil || !strings.Contains(err.Error(), "mix.actions") {
		t.Fatalf("expected mix.actions error, got: %v", err)
	}
}

func TestToRuntimeConfig(t *testing.T) {
	path := writeFile(t, "session.yaml", validYAML)
	s, err := LoadSessionFile(path)
	if err != nil {
		t.Fatalf("LoadSessionFile error: %v", err)
	}
	rt, err := ToRuntimeConfig(s)
	if err != nil {
		t.Fatalf("ToRuntimeConfig error: %v", err)
	}
	if rt.Workers != 24 || rt.InstrumentID != "AAPL" {
		t.Fatalf("unexpected runtime config: %+v", rt)
	}
}

func TestLoadSessionFileRejectsUnknownStrategyProfile(t *testing.T) {
	input := strings.Replace(validYAML, "strategyId: dip_buyer", "strategyId: profile-only", 1)
	input = strings.Replace(input, "mix:\n", "strategyProfiles:\n  known:\n    strategy: two_sided_quote\n    params:\n      submitPct: 40\n      modifyPct: 40\n      cancelPct: 20\nmix:\n", 1)
	path := writeFile(t, "bad-strategy.yaml", input)
	_, err := LoadSessionFile(path)
	if err == nil || !strings.Contains(err.Error(), "unknown strategy profile or strategy") {
		t.Fatalf("expected unknown strategy profile error, got: %v", err)
	}
}

func TestActorGroupsExpandDeterministically(t *testing.T) {
	input := `session:
  name: grouped
  scenarioRunId: sim-1
  seed: 4242
  mode: chaos
runtime:
  baseUrl: http://localhost:8080
  duration: 30s
  workers: 4
  ratePerSecond: 20
  timeout: 5s
  traceCheckLimit: 10
market:
  timezone: America/New_York
  equities:
    - symbol: AAPL
      instrumentId: AAPL
      startingPriceNanos: 100
      avgDailyVolume: 1
      sharesOutstanding: 1
      marketCap: 1
      volatilityBps: 100
      spreadBps: 5
strategyProfiles:
  passive:
    strategy: two_sided_quote
  aggressive:
    strategy: momentum_taker
actorGroups:
  - id: retail-crowd
    actorType: retail
    count: 3
    symbols: [AAPL]
    personaDistribution:
      dip_buyer: 0.5
      passive_limit: 0.5
    strategyProfileDistribution:
      passive: 0.7
      aggressive: 0.3
mix:
  actions:
    submitPct: 70
    modifyPct: 20
    cancelPct: 10
  sideBias:
    buyPct: 50
    sellPct: 50
`
	path := writeFile(t, "grouped.yaml", input)
	s, err := LoadSessionFile(path)
	if err != nil {
		t.Fatalf("LoadSessionFile error: %v", err)
	}
	r1, err := ToRuntimeConfig(s)
	if err != nil {
		t.Fatalf("ToRuntimeConfig error: %v", err)
	}
	r2, err := ToRuntimeConfig(s)
	if err != nil {
		t.Fatalf("ToRuntimeConfig error: %v", err)
	}
	if len(r1.Actors) != 3 || len(r2.Actors) != 3 {
		t.Fatalf("expected 3 expanded actors: %d, %d", len(r1.Actors), len(r2.Actors))
	}
	for i := range r1.Actors {
		if r1.Actors[i].ActorID != r2.Actors[i].ActorID || r1.Actors[i].StrategyID != r2.Actors[i].StrategyID {
			t.Fatalf("non-deterministic expansion at %d: %+v vs %+v", i, r1.Actors[i], r2.Actors[i])
		}
		if r1.Actors[i].Persona == "" {
			t.Fatalf("expected expanded actor persona to be set: %+v", r1.Actors[i])
		}
	}
}

func TestLoadSessionFileRejectsInvalidFaultProbability(t *testing.T) {
	input := `session:
  name: invalid-fault
  scenarioRunId: sim-1
  seed: 1
runtime:
  baseUrl: http://localhost:8080
  duration: 10s
  workers: 1
  ratePerSecond: 1
  timeout: 1s
  traceCheckLimit: 1
market:
  timezone: America/New_York
  equities:
    - symbol: AAPL
      instrumentId: AAPL
      startingPriceNanos: 100
      avgDailyVolume: 1
      sharesOutstanding: 1
      marketCap: 1
      volatilityBps: 100
      spreadBps: 5
actors:
  - actorId: a1
    actorType: retail
    strategyId: dip_buyer
    weight: 1
mix:
  actions:
    submitPct: 70
    modifyPct: 20
    cancelPct: 10
  sideBias:
    buyPct: 50
    sellPct: 50
faults:
  - id: f1
    type: reject_submit
    probability: 1.2
`
	path := writeFile(t, "bad-fault.yaml", input)
	_, err := LoadSessionFile(path)
	if err == nil || !strings.Contains(err.Error(), "probability") {
		t.Fatalf("expected probability validation error, got: %v", err)
	}
}

func TestLoadSessionFileRejectsUnsupportedFaultType(t *testing.T) {
	input := strings.Replace(validYAML, "mix:\n", "faults:\n  - id: f1\n    type: latency_spike\n    probability: 0.4\nmix:\n", 1)
	path := writeFile(t, "bad-fault-type.yaml", input)
	_, err := LoadSessionFile(path)
	if err == nil || !strings.Contains(err.Error(), "unsupported") {
		t.Fatalf("expected unsupported fault type error, got: %v", err)
	}
}

func TestValidateActorGroupsRequiresID(t *testing.T) {
	err := validateActorGroups([]ActorGroup{{ActorType: "retail", Count: 1}}, nil, nil)
	if err == nil || !strings.Contains(err.Error(), "id is required") {
		t.Fatalf("expected id-required error, got: %v", err)
	}
}

func TestValidateActorGroupsRejectsDuplicateID(t *testing.T) {
	groups := []ActorGroup{
		{ID: "g1", ActorType: "retail", Count: 1},
		{ID: "g1", ActorType: "retail", Count: 1},
	}
	err := validateActorGroups(groups, nil, nil)
	if err == nil || !strings.Contains(err.Error(), "duplicate actorGroup id") {
		t.Fatalf("expected duplicate id error, got: %v", err)
	}
}

func TestValidateActorGroupsRequiresActorType(t *testing.T) {
	err := validateActorGroups([]ActorGroup{{ID: "g1", Count: 1}}, nil, nil)
	if err == nil || !strings.Contains(err.Error(), "actorType is required") {
		t.Fatalf("expected actorType-required error, got: %v", err)
	}
}

func TestValidateActorGroupsRejectsNonPositiveCount(t *testing.T) {
	err := validateActorGroups([]ActorGroup{{ID: "g1", ActorType: "retail", Count: 0}}, nil, nil)
	if err == nil || !strings.Contains(err.Error(), "count must be > 0") {
		t.Fatalf("expected count error, got: %v", err)
	}
}

func TestValidateActorGroupsRejectsUnknownSymbol(t *testing.T) {
	groups := []ActorGroup{{ID: "g1", ActorType: "retail", Count: 1, Symbols: []string{"MSFT"}}}
	err := validateActorGroups(groups, map[string]struct{}{"AAPL": {}}, nil)
	if err == nil || !strings.Contains(err.Error(), "unknown symbol") {
		t.Fatalf("expected unknown symbol error, got: %v", err)
	}
}

func TestValidateActorGroupsRejectsUnknownStrategyProfile(t *testing.T) {
	groups := []ActorGroup{{
		ID: "g1", ActorType: "retail", Count: 1,
		StrategyProfileDistribution: map[string]float64{"not_a_strategy": 1.0},
	}}
	err := validateActorGroups(groups, nil, nil)
	if err == nil || !strings.Contains(err.Error(), "unknown strategy profile") {
		t.Fatalf("expected unknown strategy error, got: %v", err)
	}
}

func TestValidateActorGroupsAcceptsKnownProfileID(t *testing.T) {
	groups := []ActorGroup{{
		ID: "g1", ActorType: "retail", Count: 1,
		StrategyProfileDistribution: map[string]float64{"custom_profile": 1.0},
	}}
	profiles := map[string]StrategyProfile{"custom_profile": {Strategy: "dip_buyer"}}
	if err := validateActorGroups(groups, nil, profiles); err != nil {
		t.Fatalf("expected no error for known profile id, got: %v", err)
	}
}

func TestValidateDistributionSumRejectsNegativeValue(t *testing.T) {
	err := validateDistributionSum("g1", "personaDistribution", map[string]float64{"a": -0.1, "b": 1.1})
	if err == nil || !strings.Contains(err.Error(), "must be >= 0") {
		t.Fatalf("expected negative-value error, got: %v", err)
	}
}

func TestValidateDistributionSumRejectsBadSum(t *testing.T) {
	err := validateDistributionSum("g1", "personaDistribution", map[string]float64{"a": 0.3, "b": 0.3})
	if err == nil || !strings.Contains(err.Error(), "must sum to 1.0") {
		t.Fatalf("expected sum error, got: %v", err)
	}
}

func TestValidateFaultRulesRequiresID(t *testing.T) {
	err := validateFaultRules([]FaultRule{{Type: "reject_submit"}}, nil)
	if err == nil || !strings.Contains(err.Error(), "id is required") {
		t.Fatalf("expected fault id-required error, got: %v", err)
	}
}

func TestValidateFaultRulesRequiresType(t *testing.T) {
	err := validateFaultRules([]FaultRule{{ID: "f1"}}, nil)
	if err == nil || !strings.Contains(err.Error(), "type is required") {
		t.Fatalf("expected fault type-required error, got: %v", err)
	}
}

func TestValidateFaultRulesRejectsUnknownSymbol(t *testing.T) {
	rules := []FaultRule{{ID: "f1", Type: "reject_cancel", Symbol: "MSFT"}}
	err := validateFaultRules(rules, map[string]struct{}{"AAPL": {}})
	if err == nil || !strings.Contains(err.Error(), "unknown symbol") {
		t.Fatalf("expected unknown symbol error, got: %v", err)
	}
}

func TestValidateFaultRulesAcceptsKnownSymbol(t *testing.T) {
	rules := []FaultRule{{ID: "f1", Type: "reject_modify", Symbol: "AAPL", Probability: 0.5}}
	if err := validateFaultRules(rules, map[string]struct{}{"AAPL": {}}); err != nil {
		t.Fatalf("expected no error for known symbol, got: %v", err)
	}
}

func writeFile(t *testing.T, name, content string) string {
	t.Helper()
	d := t.TempDir()
	path := filepath.Join(d, name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("writeFile error: %v", err)
	}
	return path
}
