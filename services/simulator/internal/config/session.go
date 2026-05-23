package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

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
	AvgDailyVolume     int64  `json:"avgDailyVolume" yaml:"avgDailyVolume"`
	SharesOutstanding  int64  `json:"sharesOutstanding" yaml:"sharesOutstanding"`
	MarketCap          int64  `json:"marketCap" yaml:"marketCap"`
	VolatilityBps      int    `json:"volatilityBps" yaml:"volatilityBps"`
	SpreadBps          int    `json:"spreadBps" yaml:"spreadBps"`
}

type Actor struct {
	ActorID    string                 `json:"actorId" yaml:"actorId"`
	ActorType  string                 `json:"actorType" yaml:"actorType"`
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
	ID string `json:"id" yaml:"id"`
}

type FaultRule struct {
	ID string `json:"id" yaml:"id"`
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
	if cfg.Runtime.TraceCheckLimit <= 0 {
		return errors.New("runtime.traceCheckLimit must be > 0")
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
		if eq.SpreadBps <= 0 || eq.VolatilityBps <= 0 {
			return fmt.Errorf("market.equities[%s].spreadBps and volatilityBps must be > 0", eq.Symbol)
		}
		if _, exists := symbols[eq.Symbol]; exists {
			return fmt.Errorf("duplicate market symbol: %s", eq.Symbol)
		}
		symbols[eq.Symbol] = struct{}{}
	}
	if len(cfg.Actors) == 0 {
		return errors.New("actors must include at least one actor")
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
		return errors.New("actors total weight must be > 0")
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
	return nil
}

func validateStrategyProfiles(actors []Actor, profiles map[string]StrategyProfile) error {
	if len(profiles) == 0 {
		return nil
	}
	for _, actor := range actors {
		if _, ok := profiles[actor.StrategyID]; !ok {
			return fmt.Errorf("actors[%s] references unknown strategy profile: %s", actor.ActorID, actor.StrategyID)
		}
	}
	for name, profile := range profiles {
		if profile.Strategy == "" {
			return fmt.Errorf("strategyProfiles[%s].strategy is required", name)
		}
	}
	return nil
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
		Actors:           session.Actors,
		Equities:         append([]Equity(nil), session.Market.Equities...),
		StrategyProfiles: session.StrategyProfiles,
	}, nil
}
