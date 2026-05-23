package strategy

import (
	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
)

type ActionMix struct {
	SubmitPct int
	ModifyPct int
	CancelPct int
}

var namedDefaults = map[string]ActionMix{
	"two_sided_quote":   {SubmitPct: 45, ModifyPct: 40, CancelPct: 15},
	"inventory_skew_quote": {SubmitPct: 42, ModifyPct: 43, CancelPct: 15},
	"undercut_spread":   {SubmitPct: 48, ModifyPct: 37, CancelPct: 15},
	"momentum_taker":    {SubmitPct: 66, ModifyPct: 24, CancelPct: 10},
	"momentum_follow":   {SubmitPct: 66, ModifyPct: 24, CancelPct: 10},
	"vwap_slice":        {SubmitPct: 58, ModifyPct: 30, CancelPct: 12},
	"tactical_entry":    {SubmitPct: 62, ModifyPct: 28, CancelPct: 10},
	"intraday_rotation": {SubmitPct: 60, ModifyPct: 30, CancelPct: 10},
	"dip_buyer":         {SubmitPct: 72, ModifyPct: 18, CancelPct: 10},
	"breakout_chaser":   {SubmitPct: 69, ModifyPct: 21, CancelPct: 10},
	"passive_limit":     {SubmitPct: 64, ModifyPct: 23, CancelPct: 13},
}

func ResolveActionMix(actor *sessionconfig.Actor, profiles map[string]sessionconfig.StrategyProfile) (ActionMix, bool) {
	if actor == nil {
		return ActionMix{}, false
	}
	if profile, ok := profiles[actor.StrategyID]; ok {
		return ActionMixForProfile(profile)
	}
	return ActionMixForStrategyName(actor.StrategyID)
}

func ActionMixForProfile(profile sessionconfig.StrategyProfile) (ActionMix, bool) {
	base, ok := ActionMixForStrategyName(profile.Strategy)
	if !ok {
		return ActionMix{}, false
	}
	submit, okS := intParam(profile.Params, "submitPct")
	modify, okM := intParam(profile.Params, "modifyPct")
	cancel, okC := intParam(profile.Params, "cancelPct")
	if !okS || !okM || !okC || submit+modify+cancel != 100 {
		return base, true
	}
	return ActionMix{SubmitPct: submit, ModifyPct: modify, CancelPct: cancel}, true
}

func ActionMixForStrategyName(name string) (ActionMix, bool) {
	mix, ok := namedDefaults[name]
	return mix, ok
}

func IsKnownStrategy(name string) bool {
	_, ok := namedDefaults[name]
	return ok
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
