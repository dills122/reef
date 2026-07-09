// Package strategyname holds the canonical list of built-in simulator
// strategy names and their default action mixes. It has no dependency on
// internal/config or internal/strategy so both can import it without a
// cycle, keeping the valid-strategy-name list in exactly one place.
package strategyname

type ActionMix struct {
	SubmitPct int
	ModifyPct int
	CancelPct int
}

var NamedDefaultMixes = map[string]ActionMix{
	"two_sided_quote":      {SubmitPct: 45, ModifyPct: 40, CancelPct: 15},
	"inventory_skew_quote": {SubmitPct: 42, ModifyPct: 43, CancelPct: 15},
	"undercut_spread":      {SubmitPct: 48, ModifyPct: 37, CancelPct: 15},
	"momentum_taker":       {SubmitPct: 66, ModifyPct: 24, CancelPct: 10},
	"momentum_follow":      {SubmitPct: 66, ModifyPct: 24, CancelPct: 10},
	"vwap_slice":           {SubmitPct: 58, ModifyPct: 30, CancelPct: 12},
	"tactical_entry":       {SubmitPct: 62, ModifyPct: 28, CancelPct: 10},
	"intraday_rotation":    {SubmitPct: 60, ModifyPct: 30, CancelPct: 10},
	"dip_buyer":            {SubmitPct: 72, ModifyPct: 18, CancelPct: 10},
	"breakout_chaser":      {SubmitPct: 69, ModifyPct: 21, CancelPct: 10},
	"passive_limit":        {SubmitPct: 64, ModifyPct: 23, CancelPct: 13},
}

func Known(name string) bool {
	_, ok := NamedDefaultMixes[name]
	return ok
}
