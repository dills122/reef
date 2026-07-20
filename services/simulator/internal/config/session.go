package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"hash/fnv"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/dills122/reef/services/simulator/internal/strategyname"
	"gopkg.in/yaml.v3"
)

type SessionFile struct {
	Session SessionSection `json:"session" yaml:"session"`
	Runtime RuntimeSection `json:"runtime" yaml:"runtime"`
	Market  MarketSection  `json:"market" yaml:"market"`
	Actors  []Actor        `json:"actors" yaml:"actors"`
	Mix     MixSection     `json:"mix" yaml:"mix"`
	// Forward-compatibility fields for v2.
	StrategyProfiles map[string]StrategyProfile `json:"strategyProfiles,omitempty" yaml:"strategyProfiles,omitempty"`
	ActorGroups      []ActorGroup               `json:"actorGroups,omitempty" yaml:"actorGroups,omitempty"`
	Faults           []FaultRule                `json:"faults,omitempty" yaml:"faults,omitempty"`
}

type SessionSection struct {
	Name          string `json:"name" yaml:"name"`
	ScenarioRunID string `json:"scenarioRunId" yaml:"scenarioRunId"`
	Seed          int64  `json:"seed" yaml:"seed"`
	Mode          string `json:"mode" yaml:"mode"`
}

type RuntimeSection struct {
	BaseURL         string `json:"baseUrl" yaml:"baseUrl"`
	Duration        string `json:"duration" yaml:"duration"`
	Workers         int    `json:"workers" yaml:"workers"`
	RatePerSecond   int    `json:"ratePerSecond" yaml:"ratePerSecond"`
	Timeout         string `json:"timeout" yaml:"timeout"`
	TraceCheckLimit int    `json:"traceCheckLimit" yaml:"traceCheckLimit"`
}

type MarketSection struct {
	Timezone string   `json:"timezone" yaml:"timezone"`
	Equities []Equity `json:"equities" yaml:"equities"`
}

type Equity struct {
	Symbol             string `json:"symbol" yaml:"symbol"`
	InstrumentID       string `json:"instrumentId" yaml:"instrumentId"`
	StartingPriceNanos int64  `json:"startingPriceNanos" yaml:"startingPriceNanos"`
	PriceTickNanos     int64  `json:"priceTickNanos,omitempty" yaml:"priceTickNanos,omitempty"`
	AvgDailyVolume     int64  `json:"avgDailyVolume" yaml:"avgDailyVolume"`
	SharesOutstanding  int64  `json:"sharesOutstanding" yaml:"sharesOutstanding"`
	MarketCap          int64  `json:"marketCap" yaml:"marketCap"`
	VolatilityBps      int    `json:"volatilityBps" yaml:"volatilityBps"`
	SpreadBps          int    `json:"spreadBps" yaml:"spreadBps"`
}

type Actor struct {
	ActorID    string                 `json:"actorId" yaml:"actorId"`
	ActorType  string                 `json:"actorType" yaml:"actorType"`
	Persona    string                 `json:"persona,omitempty" yaml:"persona,omitempty"`
	StrategyID string                 `json:"strategyId" yaml:"strategyId"`
	Weight     int                    `json:"weight" yaml:"weight"`
	Symbols    []string               `json:"symbols,omitempty" yaml:"symbols,omitempty"`
	Params     map[string]interface{} `json:"params,omitempty" yaml:"params,omitempty"`
}

type MixSection struct {
	Actions  MixActions `json:"actions" yaml:"actions"`
	SideBias SideBias   `json:"sideBias" yaml:"sideBias"`
}

type MixActions struct {
	SubmitPct int `json:"submitPct" yaml:"submitPct"`
	ModifyPct int `json:"modifyPct" yaml:"modifyPct"`
	CancelPct int `json:"cancelPct" yaml:"cancelPct"`
}

type SideBias struct {
	BuyPct  int `json:"buyPct" yaml:"buyPct"`
	SellPct int `json:"sellPct" yaml:"sellPct"`
}

type StrategyProfile struct {
	Strategy string                 `json:"strategy" yaml:"strategy"`
	Params   map[string]interface{} `json:"params,omitempty" yaml:"params,omitempty"`
}

type ActorGroup struct {
	ID                          string             `json:"id" yaml:"id"`
	ActorType                   string             `json:"actorType,omitempty" yaml:"actorType,omitempty"`
	Count                       int                `json:"count,omitempty" yaml:"count,omitempty"`
	Symbols                     []string           `json:"symbols,omitempty" yaml:"symbols,omitempty"`
	PersonaDistribution         map[string]float64 `json:"personaDistribution,omitempty" yaml:"personaDistribution,omitempty"`
	StrategyProfileDistribution map[string]float64 `json:"strategyProfileDistribution,omitempty" yaml:"strategyProfileDistribution,omitempty"`
}

type FaultRule struct {
	ID          string  `json:"id" yaml:"id"`
	Type        string  `json:"type" yaml:"type"`
	Probability float64 `json:"probability,omitempty" yaml:"probability,omitempty"`
	Symbol      string  `json:"symbol,omitempty" yaml:"symbol,omitempty"`
}

type RuntimeConfig struct {
	SessionName      string
	ScenarioRunID    string
	Seed             int64
	Mode             string
	BaseURL          string
	Duration         time.Duration
	Workers          int
	RatePerSecond    int
	RequestTimeout   time.Duration
	TraceCheckLimit  int
	SubmitPct        int
	ModifyPct        int
	CancelPct        int
	InstrumentID     string
	InstrumentSymbol string
	PriceMin         int64
	PriceMax         int64
	SideBiasBuyPct   int
	Actors           []Actor
	Equities         []Equity
	StrategyProfiles map[string]StrategyProfile
	Faults           []FaultRule
}

func LoadSessionFile(path string) (SessionFile, error) {
	var cfg SessionFile
	content, err := os.ReadFile(path)
	if err != nil {
		return cfg, fmt.Errorf("read session config: %w", err)
	}
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".yaml", ".yml":
		if err := yaml.Unmarshal(content, &cfg); err != nil {
			return cfg, fmt.Errorf("parse yaml session config: %w", err)
		}
	case ".json":
		if err := json.Unmarshal(content, &cfg); err != nil {
			return cfg, fmt.Errorf("parse json session config: %w", err)
		}
	default:
		if err := yaml.Unmarshal(content, &cfg); err != nil {
			if jErr := json.Unmarshal(content, &cfg); jErr != nil {
				return cfg, fmt.Errorf("parse session config: unsupported extension %q and content is not valid YAML/JSON", ext)
			}
		}
	}
	if err := ValidateSessionFile(cfg); err != nil {
		return cfg, err
	}
	return cfg, nil
}

func ValidateSessionFile(cfg SessionFile) error {
	if cfg.Session.Seed == 0 {
		return errors.New("session.seed is required and must be non-zero")
	}
	if cfg.Runtime.BaseURL == "" {
		return errors.New("runtime.baseUrl is required")
	}
	if cfg.Runtime.Duration == "" {
		return errors.New("runtime.duration is required")
	}
	if _, err := time.ParseDuration(cfg.Runtime.Duration); err != nil {
		return fmt.Errorf("runtime.duration: %w", err)
	}
	if cfg.Runtime.Timeout == "" {
		return errors.New("runtime.timeout is required")
	}
	if _, err := time.ParseDuration(cfg.Runtime.Timeout); err != nil {
		return fmt.Errorf("runtime.timeout: %w", err)
	}
	if cfg.Runtime.Workers <= 0 {
		return errors.New("runtime.workers must be > 0")
	}
	if cfg.Runtime.RatePerSecond < 0 {
		return errors.New("runtime.ratePerSecond must be >= 0")
	}
	if cfg.Runtime.TraceCheckLimit < 0 {
		return errors.New("runtime.traceCheckLimit must be >= 0")
	}
	if len(cfg.Market.Equities) == 0 {
		return errors.New("market.equities must include at least one symbol")
	}
	symbols := make(map[string]struct{}, len(cfg.Market.Equities))
	for _, eq := range cfg.Market.Equities {
		if eq.Symbol == "" {
			return errors.New("market.equities[].symbol is required")
		}
		if eq.InstrumentID == "" {
			return fmt.Errorf("market.equities[%s].instrumentId is required", eq.Symbol)
		}
		if eq.StartingPriceNanos <= 0 {
			return fmt.Errorf("market.equities[%s].startingPriceNanos must be > 0", eq.Symbol)
		}
		if eq.PriceTickNanos < 0 {
			return fmt.Errorf("market.equities[%s].priceTickNanos must be >= 0", eq.Symbol)
		}
		if eq.SpreadBps <= 0 || eq.VolatilityBps <= 0 {
			return fmt.Errorf("market.equities[%s].spreadBps and volatilityBps must be > 0", eq.Symbol)
		}
		if _, exists := symbols[eq.Symbol]; exists {
			return fmt.Errorf("duplicate market symbol: %s", eq.Symbol)
		}
		symbols[eq.Symbol] = struct{}{}
	}
	if len(cfg.Actors) == 0 {
		if len(cfg.ActorGroups) == 0 {
			return errors.New("actors or actorGroups must include at least one actor definition")
		}
	}
	actorIDs := make(map[string]struct{}, len(cfg.Actors))
	weights := 0
	for _, actor := range cfg.Actors {
		if actor.ActorID == "" {
			return errors.New("actors[].actorId is required")
		}
		if _, exists := actorIDs[actor.ActorID]; exists {
			return fmt.Errorf("duplicate actorId: %s", actor.ActorID)
		}
		actorIDs[actor.ActorID] = struct{}{}
		if actor.ActorType == "" || actor.StrategyID == "" {
			return fmt.Errorf("actors[%s] must include actorType and strategyId", actor.ActorID)
		}
		if actor.Weight <= 0 {
			return fmt.Errorf("actors[%s].weight must be > 0", actor.ActorID)
		}
		weights += actor.Weight
		for _, sym := range actor.Symbols {
			if _, ok := symbols[sym]; !ok {
				return fmt.Errorf("actors[%s] references unknown symbol: %s", actor.ActorID, sym)
			}
		}
	}
	if weights <= 0 {
		if len(cfg.ActorGroups) == 0 {
			return errors.New("actors total weight must be > 0")
		}
	}
	if cfg.Mix.Actions.SubmitPct+cfg.Mix.Actions.ModifyPct+cfg.Mix.Actions.CancelPct != 100 {
		return errors.New("mix.actions submitPct+modifyPct+cancelPct must equal 100")
	}
	if cfg.Mix.SideBias.BuyPct+cfg.Mix.SideBias.SellPct != 100 {
		return errors.New("mix.sideBias buyPct+sellPct must equal 100")
	}
	if err := validateStrategyProfiles(cfg.Actors, cfg.StrategyProfiles); err != nil {
		return err
	}
	if err := validateActorGroups(cfg.ActorGroups, symbols, cfg.StrategyProfiles); err != nil {
		return err
	}
	if err := validateFaultRules(cfg.Faults, symbols); err != nil {
		return err
	}
	return nil
}

func validateStrategyProfiles(actors []Actor, profiles map[string]StrategyProfile) error {
	for _, actor := range actors {
		if len(profiles) > 0 {
			if _, ok := profiles[actor.StrategyID]; ok {
				continue
			}
		}
		if !isKnownStrategyName(actor.StrategyID) {
			return fmt.Errorf("actors[%s] references unknown strategy profile or strategy: %s", actor.ActorID, actor.StrategyID)
		}
	}
	for name, profile := range profiles {
		if profile.Strategy == "" {
			return fmt.Errorf("strategyProfiles[%s].strategy is required", name)
		}
		if !isKnownStrategyName(profile.Strategy) {
			return fmt.Errorf("strategyProfiles[%s].strategy is unknown: %s", name, profile.Strategy)
		}
	}
	return nil
}

func validateActorGroups(groups []ActorGroup, marketSymbols map[string]struct{}, profiles map[string]StrategyProfile) error {
	if len(groups) == 0 {
		return nil
	}
	groupIDs := make(map[string]struct{}, len(groups))
	for _, group := range groups {
		if group.ID == "" {
			return errors.New("actorGroups[].id is required")
		}
		if _, exists := groupIDs[group.ID]; exists {
			return fmt.Errorf("duplicate actorGroup id: %s", group.ID)
		}
		groupIDs[group.ID] = struct{}{}
		if group.ActorType == "" {
			return fmt.Errorf("actorGroups[%s].actorType is required", group.ID)
		}
		if group.Count <= 0 {
			return fmt.Errorf("actorGroups[%s].count must be > 0", group.ID)
		}
		for _, symbol := range group.Symbols {
			if _, ok := marketSymbols[symbol]; !ok {
				return fmt.Errorf("actorGroups[%s] references unknown symbol: %s", group.ID, symbol)
			}
		}
		if err := validateDistributionSum(group.ID, "personaDistribution", group.PersonaDistribution); err != nil {
			return err
		}
		if err := validateDistributionSum(group.ID, "strategyProfileDistribution", group.StrategyProfileDistribution); err != nil {
			return err
		}
		if len(group.StrategyProfileDistribution) > 0 {
			for strategyID := range group.StrategyProfileDistribution {
				if len(profiles) > 0 {
					if _, ok := profiles[strategyID]; !ok {
						if !isKnownStrategyName(strategyID) {
							return fmt.Errorf("actorGroups[%s] references unknown strategy profile or strategy: %s", group.ID, strategyID)
						}
					}
				} else if !isKnownStrategyName(strategyID) {
					return fmt.Errorf("actorGroups[%s] references unknown strategy profile or strategy: %s", group.ID, strategyID)
				}
			}
		}
	}
	return nil
}

func validateDistributionSum(groupID, field string, values map[string]float64) error {
	if len(values) == 0 {
		return nil
	}
	sum := 0.0
	for _, value := range values {
		if value < 0 {
			return fmt.Errorf("actorGroups[%s].%s values must be >= 0", groupID, field)
		}
		sum += value
	}
	if sum < 0.9999 || sum > 1.0001 {
		return fmt.Errorf("actorGroups[%s].%s must sum to 1.0", groupID, field)
	}
	return nil
}

func validateFaultRules(faults []FaultRule, marketSymbols map[string]struct{}) error {
	for _, fault := range faults {
		if fault.ID == "" {
			return errors.New("faults[].id is required")
		}
		if fault.Type == "" {
			return fmt.Errorf("faults[%s].type is required", fault.ID)
		}
		switch fault.Type {
		case "reject_submit", "reject_modify", "reject_cancel":
		default:
			return fmt.Errorf("faults[%s].type is unsupported: %s", fault.ID, fault.Type)
		}
		if fault.Probability < 0 || fault.Probability > 1 {
			return fmt.Errorf("faults[%s].probability must be between 0 and 1", fault.ID)
		}
		if fault.Symbol != "" {
			if _, ok := marketSymbols[fault.Symbol]; !ok {
				return fmt.Errorf("faults[%s] references unknown symbol: %s", fault.ID, fault.Symbol)
			}
		}
	}
	return nil
}

func isKnownStrategyName(name string) bool {
	return strategyname.Known(name)
}

func ToRuntimeConfig(session SessionFile) (RuntimeConfig, error) {
	duration, err := time.ParseDuration(session.Runtime.Duration)
	if err != nil {
		return RuntimeConfig{}, fmt.Errorf("runtime.duration: %w", err)
	}
	timeout, err := time.ParseDuration(session.Runtime.Timeout)
	if err != nil {
		return RuntimeConfig{}, fmt.Errorf("runtime.timeout: %w", err)
	}
	priceMin := session.Market.Equities[0].StartingPriceNanos
	priceMax := session.Market.Equities[0].StartingPriceNanos
	for _, eq := range session.Market.Equities {
		if eq.StartingPriceNanos < priceMin {
			priceMin = eq.StartingPriceNanos
		}
		if eq.StartingPriceNanos > priceMax {
			priceMax = eq.StartingPriceNanos
		}
	}
	expandedActors := expandActorGroups(session.Session.Seed, session.ActorGroups)
	runtimeActors := make([]Actor, 0, len(session.Actors)+len(expandedActors))
	runtimeActors = append(runtimeActors, session.Actors...)
	runtimeActors = append(runtimeActors, expandedActors...)

	return RuntimeConfig{
		SessionName:      session.Session.Name,
		ScenarioRunID:    session.Session.ScenarioRunID,
		Seed:             session.Session.Seed,
		Mode:             session.Session.Mode,
		BaseURL:          session.Runtime.BaseURL,
		Duration:         duration,
		Workers:          session.Runtime.Workers,
		RatePerSecond:    session.Runtime.RatePerSecond,
		RequestTimeout:   timeout,
		TraceCheckLimit:  session.Runtime.TraceCheckLimit,
		SubmitPct:        session.Mix.Actions.SubmitPct,
		ModifyPct:        session.Mix.Actions.ModifyPct,
		CancelPct:        session.Mix.Actions.CancelPct,
		InstrumentID:     session.Market.Equities[0].InstrumentID,
		InstrumentSymbol: session.Market.Equities[0].Symbol,
		PriceMin:         priceMin,
		PriceMax:         priceMax,
		SideBiasBuyPct:   session.Mix.SideBias.BuyPct,
		Actors:           runtimeActors,
		Equities:         append([]Equity(nil), session.Market.Equities...),
		StrategyProfiles: session.StrategyProfiles,
		Faults:           append([]FaultRule(nil), session.Faults...),
	}, nil
}

func expandActorGroups(seed int64, groups []ActorGroup) []Actor {
	out := make([]Actor, 0)
	for _, group := range groups {
		rng := rand.New(rand.NewSource(seed + hashID(group.ID)))
		for i := 1; i <= group.Count; i++ {
			actorID := fmt.Sprintf("%s-%03d", group.ID, i)
			out = append(out, Actor{
				ActorID:    actorID,
				ActorType:  group.ActorType,
				Persona:    chooseWeightedKey(rng, group.PersonaDistribution),
				StrategyID: chooseWeightedKey(rng, group.StrategyProfileDistribution),
				Weight:     1,
				Symbols:    append([]string(nil), group.Symbols...),
			})
		}
	}
	return out
}

func chooseWeightedKey(rng *rand.Rand, dist map[string]float64) string {
	if len(dist) == 0 {
		return ""
	}
	keys := make([]string, 0, len(dist))
	for key := range dist {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	target := rng.Float64()
	running := 0.0
	for _, key := range keys {
		running += dist[key]
		if target <= running {
			return key
		}
	}
	return keys[len(keys)-1]
}

func hashID(value string) int64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte(value))
	return int64(h.Sum64())
}
