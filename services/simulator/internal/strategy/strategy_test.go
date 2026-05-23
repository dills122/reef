package strategy

import (
	"testing"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
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
