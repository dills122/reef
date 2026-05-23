package strategy

import (
	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
)

type ActionMix struct {
	SubmitPct int
	ModifyPct int
	CancelPct int
}

func ActionMixForActor(actor sessionconfig.Actor, profiles map[string]sessionconfig.StrategyProfile) (ActionMix, bool) {
	if len(profiles) == 0 {
		return ActionMix{}, false
	}
	profile, ok := profiles[actor.StrategyID]
	if !ok {
		return ActionMix{}, false
	}
	submit, okS := intParam(profile.Params, "submitPct")
	modify, okM := intParam(profile.Params, "modifyPct")
	cancel, okC := intParam(profile.Params, "cancelPct")
	if !okS || !okM || !okC {
		return ActionMix{}, false
	}
	if submit+modify+cancel != 100 {
		return ActionMix{}, false
	}
	return ActionMix{SubmitPct: submit, ModifyPct: modify, CancelPct: cancel}, true
}

func intParam(params map[string]interface{}, key string) (int, bool) {
	if params == nil {
		return 0, false
	}
	raw, ok := params[key]
	if !ok {
		return 0, false
	}
	switch value := raw.(type) {
	case int:
		return value, true
	case int32:
		return int(value), true
	case int64:
		return int(value), true
	case float64:
		return int(value), true
	default:
		return 0, false
	}
}
