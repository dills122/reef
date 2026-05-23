package strategy

import (
	"testing"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
)

func TestActionMixForActor(t *testing.T) {
	actor := sessionconfig.Actor{ActorID: "a1", StrategyID: "mm-tight"}
	profiles := map[string]sessionconfig.StrategyProfile{
		"mm-tight": {Strategy: "two_sided_quote", Params: map[string]interface{}{"submitPct": 40, "modifyPct": 50, "cancelPct": 10}},
	}
	mix, ok := ActionMixForActor(actor, profiles)
	if !ok {
		t.Fatal("expected strategy action mix")
	}
	if mix.ModifyPct != 50 {
		t.Fatalf("unexpected mix: %+v", mix)
	}
}
