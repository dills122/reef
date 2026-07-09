package strategy

import (
	"testing"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
	"github.com/dills122/reef/services/simulator/internal/strategyname"
)

func TestResolveActionMixProfileOverride(t *testing.T) {
	actor := sessionconfig.Actor{ActorID: "a1", StrategyID: "mm-tight"}
	profiles := map[string]sessionconfig.StrategyProfile{
		"mm-tight": {Strategy: "two_sided_quote", Params: map[string]interface{}{"submitPct": 40, "modifyPct": 50, "cancelPct": 10}},
	}
	mix, ok := ResolveActionMix(&actor, profiles)
	if !ok {
		t.Fatal("expected strategy action mix")
	}
	if mix.SubmitPct != 40 || mix.ModifyPct != 50 || mix.CancelPct != 10 {
		t.Fatalf("unexpected mix: %+v", mix)
	}
}

func TestResolveActionMixDirectStrategyID(t *testing.T) {
	actor := sessionconfig.Actor{ActorID: "a1", StrategyID: "dip_buyer"}
	mix, ok := ResolveActionMix(&actor, nil)
	if !ok {
		t.Fatal("expected direct strategy mix resolution")
	}
	if mix.SubmitPct != 72 || mix.ModifyPct != 18 || mix.CancelPct != 10 {
		t.Fatalf("unexpected mix: %+v", mix)
	}
}

func TestResolveActionMixNilActor(t *testing.T) {
	if _, ok := ResolveActionMix(nil, nil); ok {
		t.Fatal("expected nil actor to yield no mix")
	}
}

func TestResolveActionMixUnknownStrategy(t *testing.T) {
	actor := sessionconfig.Actor{ActorID: "a1", StrategyID: "unknown_strategy"}
	if _, ok := ResolveActionMix(&actor, nil); ok {
		t.Fatal("expected unknown strategy to yield no mix")
	}
}

func TestIsKnownStrategy(t *testing.T) {
	if !IsKnownStrategy("two_sided_quote") {
		t.Fatal("expected two_sided_quote to be known")
	}
	if IsKnownStrategy("not_a_real_strategy") {
		t.Fatal("expected unknown strategy name to be unrecognized")
	}
}

func TestActionMixForProfileUnknownBaseStrategy(t *testing.T) {
	profile := sessionconfig.StrategyProfile{Strategy: "not_a_real_strategy"}
	mix, ok := ActionMixForProfile(profile)
	if ok {
		t.Fatalf("expected unknown base strategy to fail, got %+v", mix)
	}
}

func TestActionMixForProfileInvalidParamsFallsBackToDefault(t *testing.T) {
	profile := sessionconfig.StrategyProfile{
		Strategy: "two_sided_quote",
		Params:   map[string]interface{}{"submitPct": 40, "modifyPct": 40, "cancelPct": 30},
	}
	mix, ok := ActionMixForProfile(profile)
	if !ok {
		t.Fatal("expected fallback mix to succeed")
	}
	if mix != strategyname.NamedDefaultMixes["two_sided_quote"] {
		t.Fatalf("expected default mix on invalid params, got %+v", mix)
	}
}

func TestActionMixForProfileRejectsNegativePctEvenWhenSumIsOneHundred(t *testing.T) {
	profile := sessionconfig.StrategyProfile{
		Strategy: "two_sided_quote",
		Params:   map[string]interface{}{"submitPct": 120, "modifyPct": -20, "cancelPct": 0},
	}
	mix, ok := ActionMixForProfile(profile)
	if !ok {
		t.Fatal("expected fallback mix to succeed")
	}
	if mix != strategyname.NamedDefaultMixes["two_sided_quote"] {
		t.Fatalf("expected default mix on negative params, got %+v", mix)
	}
}

func TestIntParam(t *testing.T) {
	if _, ok := intParam(nil, "submitPct"); ok {
		t.Fatal("expected nil params to fail")
	}
	if _, ok := intParam(map[string]interface{}{}, "submitPct"); ok {
		t.Fatal("expected missing key to fail")
	}
	if v, ok := intParam(map[string]interface{}{"x": int32(5)}, "x"); !ok || v != 5 {
		t.Fatalf("expected int32 5, got %d ok=%v", v, ok)
	}
	if v, ok := intParam(map[string]interface{}{"x": int64(6)}, "x"); !ok || v != 6 {
		t.Fatalf("expected int64 6, got %d ok=%v", v, ok)
	}
	if v, ok := intParam(map[string]interface{}{"x": float64(7)}, "x"); !ok || v != 7 {
		t.Fatalf("expected float64 7, got %d ok=%v", v, ok)
	}
	if _, ok := intParam(map[string]interface{}{"x": float64(7.5)}, "x"); ok {
		t.Fatal("expected fractional float64 to fail")
	}
	if _, ok := intParam(map[string]interface{}{"x": "not-a-number"}, "x"); ok {
		t.Fatal("expected unsupported type to fail")
	}
}
