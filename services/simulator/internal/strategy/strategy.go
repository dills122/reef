package strategy

import (
	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
	"github.com/dills122/reef/services/simulator/internal/strategyname"
)

// ActionMix is an alias for strategyname.ActionMix so existing callers keep
// referring to strategy.ActionMix while the canonical name/mix data lives in
// internal/strategyname (importable from both this package and
// internal/config without a cycle).
type ActionMix = strategyname.ActionMix

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
	if !okS || !okM || !okC || !validPct(submit) || !validPct(modify) || !validPct(cancel) || submit+modify+cancel != 100 {
		return base, true
	}
	return ActionMix{SubmitPct: submit, ModifyPct: modify, CancelPct: cancel}, true
}

func ActionMixForStrategyName(name string) (ActionMix, bool) {
	mix, ok := strategyname.NamedDefaultMixes[name]
	return mix, ok
}

func IsKnownStrategy(name string) bool {
	return strategyname.Known(name)
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
		if value != float64(int(value)) {
			return 0, false
		}
		return int(value), true
	default:
		return 0, false
	}
}

func validPct(value int) bool {
	return value >= 0 && value <= 100
}
