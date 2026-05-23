package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
	"github.com/dills122/reef/services/simulator/internal/strategy"
)

type Config struct {
	SessionConfigPath string
	SessionName       string
	ScenarioRunID     string
	Seed              int64
	HasSessionConfig  bool
	SideBiasBuyPct    int
	SessionActors     []sessionconfig.Actor
	MarketEquities    []sessionconfig.Equity
	StrategyProfiles  map[string]sessionconfig.StrategyProfile
	Faults            []sessionconfig.FaultRule
	BaseURL           string
	Duration          time.Duration
	Workers           int
	RatePerSecond     int
	RequestTimeout    time.Duration
	SubmitPct         int
	ModifyPct         int
	CancelPct         int
	InstrumentID      string
	InstrumentSymbol  string
	ParticipantID     string
	ParticipantName   string
	AccountID         string
	QuantityMin       int
	QuantityMax       int
	PriceMin          int64
	PriceMax          int64
	TraceCheckLimit   int
	ReportOut         string
	Mode              string
	Tail              bool
	TailInterval      time.Duration
	TailLines         int
	ProfileMixMM      int
	ProfileMixInst    int
	ProfileMixRetail  int
	ProfileMixNoise   int
	PrettySummary     bool
}

type Action string

const (
	ActionSubmit Action = "submit"
	ActionModify Action = "modify"
	ActionCancel Action = "cancel"
)

type requestResult struct {
	Profile      string
	ActorID      string
	ActorType    string
	Persona      string
	StrategyID   string
	Action       Action
	Success      bool
	StatusCode   int
	Latency      time.Duration
	ErrorText    string
	TraceID      string
	OrderID      string
	CommandID    string
	RejectCode   string
	RejectReason string
}

type summary struct {
	SessionID              string                    `json:"sessionId"`
	StartedAt              time.Time                 `json:"startedAt"`
	FinishedAt             time.Time                 `json:"finishedAt"`
	DurationSeconds        float64                   `json:"durationSeconds"`
	Config                 Config                    `json:"config"`
	ThroughputRPS          float64                   `json:"throughputRps"`
	AcceptedBusinessOpsRPS float64                   `json:"acceptedBusinessOpsRps"`
	TotalRequests          int64                     `json:"totalRequests"`
	TotalSuccess           int64                     `json:"totalSuccess"`
	TotalFailures          int64                     `json:"totalFailures"`
	ByAction               map[Action]actionSummary  `json:"byAction"`
	ByProfile              map[string]profileSummary `json:"byProfile"`
	ByActor                map[string]profileSummary `json:"byActor"`
	ByPersona              map[string]profileSummary `json:"byPersona"`
	ByStrategy             map[string]profileSummary `json:"byStrategy"`
	StatusCodes            map[int]int64             `json:"statusCodes"`
	TopErrors              []errorSummary            `json:"topErrors"`
	RejectReasons          []errorSummary            `json:"rejectReasons"`
	RejectTaxonomy         []rejectTaxonomySummary   `json:"rejectTaxonomy"`
	LatencyMs              latencySummary            `json:"latencyMs"`
	TraceChecks            traceChecks               `json:"traceChecks"`
}

type profileSummary struct {
	Requests int64                    `json:"requests"`
	Success  int64                    `json:"success"`
	Failures int64                    `json:"failures"`
	ByAction map[Action]actionSummary `json:"byAction"`
	Latency  latencySummary           `json:"latencyMs"`
}

type actionSummary struct {
	Requests int64          `json:"requests"`
	Success  int64          `json:"success"`
	Failures int64          `json:"failures"`
	Latency  latencySummary `json:"latencyMs"`
}

type latencySummary struct {
	Min float64 `json:"min"`
	P50 float64 `json:"p50"`
	P95 float64 `json:"p95"`
	P99 float64 `json:"p99"`
	Max float64 `json:"max"`
}

type errorSummary struct {
	Error string `json:"error"`
	Count int64  `json:"count"`
}

type rejectTaxonomySummary struct {
	Code               string  `json:"code"`
	Count              int64   `json:"count"`
	PercentOfFailures  float64 `json:"percentOfFailures"`
	PercentOfRejects   float64 `json:"percentOfRejects"`
}

type traceChecks struct {
	Checked       int      `json:"checked"`
	Pass          int      `json:"pass"`
	Fail          int      `json:"fail"`
	FailedTraceID []string `json:"failedTraceIds"`
}

type runtimeEvent struct {
	TraceID        string `json:"traceId"`
	SequenceNumber int64  `json:"sequenceNumber"`
}

type traceEventsResponse struct {
	Events []runtimeEvent `json:"events"`
}

type workerState struct {
	orders []string
}

const (
	profileMarketMaker   = "market-maker"
	profileInstitutional = "institutional"
	profileRetail        = "retail"
	profileNoise         = "noise"
)

type trade struct {
	EventID       string `json:"eventId"`
	TradeID       string `json:"tradeId"`
	BuyOrderID    string `json:"buyOrderId"`
	SellOrderID   string `json:"sellOrderId"`
	QuantityUnits string `json:"quantityUnits"`
	Price         string `json:"price"`
	OccurredAt    string `json:"occurredAt"`
}

type tradesResponse struct {
	Trades []trade `json:"trades"`
}

type event struct {
	EventID        string `json:"eventId"`
	EventType      string `json:"eventType"`
	OrderID        string `json:"orderId"`
	TraceID        string `json:"traceId"`
	SequenceNumber int64  `json:"sequenceNumber"`
	OccurredAt     string `json:"occurredAt"`
}

type eventsResponse struct {
	Events []event `json:"events"`
}

type runtimeReject struct {
	Code   string `json:"code"`
	Reason string `json:"reason"`
}

type runtimeResponse struct {
	Rejected *runtimeReject `json:"rejected,omitempty"`
}

func main() {
	cfg, err := parseConfig()
	if err != nil {
		fmt.Fprintf(os.Stderr, "config error: %v\n", err)
		os.Exit(1)
	}

	started := time.Now().UTC()
	sessionID := fmt.Sprintf("load-%d", started.UnixNano())
	client := &http.Client{Timeout: cfg.RequestTimeout}

	if err := seedReferenceData(client, cfg); err != nil {
		fmt.Fprintf(os.Stderr, "reference data seed failed: %v\n", err)
		os.Exit(1)
	}

	ctx, cancel := context.WithTimeout(context.Background(), cfg.Duration)
	defer cancel()

	results := make(chan requestResult, cfg.Workers*8)
	var counter int64
	traceSeen := sync.Map{}
	var wg sync.WaitGroup
	rateCh := make(chan struct{}, 1)
	if cfg.RatePerSecond > 0 {
		go tokenFeeder(ctx, cfg.RatePerSecond, rateCh)
	}
	if cfg.Tail {
		go tailLoop(ctx, client, cfg)
	}

	for workerID := 0; workerID < cfg.Workers; workerID++ {
		wg.Add(1)
		profile := profileForWorker(workerID, cfg.Workers, cfg)
		go func(id int, workerProfile string) {
			defer wg.Done()
			runWorker(ctx, client, cfg, id, workerProfile, &counter, rateCh, results, &traceSeen)
		}(workerID, profile)
	}

	go func() {
		wg.Wait()
		close(results)
	}()

	all := make([]requestResult, 0, 1024)
	for result := range results {
		all = append(all, result)
	}

	finished := time.Now().UTC()
	report := buildSummary(sessionID, started, finished, cfg, all)
	report.TraceChecks = runTraceChecks(client, cfg, &traceSeen)
	printSummary(report)
	if cfg.ReportOut != "" {
		if err := writeReport(cfg.ReportOut, report); err != nil {
			fmt.Fprintf(os.Stderr, "failed to write report: %v\n", err)
			os.Exit(1)
		}
	}
}

func parseConfig() (Config, error) {
	defaults := defaultConfigFromEnv()
	cfg := defaults

	flag.StringVar(&cfg.SessionConfigPath, "session-config", envOr("REEF_SESSION_CONFIG", ""), "path to persona session config (yaml/json)")
	flag.StringVar(&cfg.BaseURL, "base-url", cfg.BaseURL, "platform runtime base url")
	flag.DurationVar(&cfg.Duration, "duration", cfg.Duration, "test duration")
	flag.IntVar(&cfg.Workers, "workers", cfg.Workers, "concurrent workers")
	flag.IntVar(&cfg.RatePerSecond, "rate", cfg.RatePerSecond, "global request rate per second (0 = unthrottled)")
	flag.DurationVar(&cfg.RequestTimeout, "timeout", cfg.RequestTimeout, "request timeout")
	flag.IntVar(&cfg.SubmitPct, "submit-pct", cfg.SubmitPct, "submit action percentage")
	flag.IntVar(&cfg.ModifyPct, "modify-pct", cfg.ModifyPct, "modify action percentage")
	flag.IntVar(&cfg.CancelPct, "cancel-pct", cfg.CancelPct, "cancel action percentage")
	flag.StringVar(&cfg.InstrumentID, "instrument-id", cfg.InstrumentID, "instrument id used for orders")
	flag.StringVar(&cfg.InstrumentSymbol, "instrument-symbol", cfg.InstrumentSymbol, "instrument symbol")
	flag.StringVar(&cfg.ParticipantID, "participant-id", cfg.ParticipantID, "participant id")
	flag.StringVar(&cfg.ParticipantName, "participant-name", cfg.ParticipantName, "participant name")
	flag.StringVar(&cfg.AccountID, "account-id", cfg.AccountID, "account id")
	flag.IntVar(&cfg.QuantityMin, "qty-min", cfg.QuantityMin, "minimum order quantity")
	flag.IntVar(&cfg.QuantityMax, "qty-max", cfg.QuantityMax, "maximum order quantity")
	flag.Int64Var(&cfg.PriceMin, "price-min", cfg.PriceMin, "minimum order price nanos")
	flag.Int64Var(&cfg.PriceMax, "price-max", cfg.PriceMax, "maximum order price nanos")
	flag.IntVar(&cfg.TraceCheckLimit, "trace-check-limit", cfg.TraceCheckLimit, "max unique traces to validate")
	flag.StringVar(&cfg.ReportOut, "report-out", cfg.ReportOut, "optional json report output path")
	flag.StringVar(&cfg.Mode, "mode", cfg.Mode, "traffic mode: chaos, strict-lifecycle, or capacity-baseline")
	flag.BoolVar(&cfg.Tail, "tail", cfg.Tail, "stream new trades/events during the run")
	flag.DurationVar(&cfg.TailInterval, "tail-interval", cfg.TailInterval, "tail poll interval")
	flag.IntVar(&cfg.TailLines, "tail-lines", cfg.TailLines, "max trade/event rows per tail poll")
	flag.IntVar(&cfg.ProfileMixMM, "profile-mm-pct", cfg.ProfileMixMM, "market-maker worker percentage")
	flag.IntVar(&cfg.ProfileMixInst, "profile-inst-pct", cfg.ProfileMixInst, "institutional worker percentage")
	flag.IntVar(&cfg.ProfileMixRetail, "profile-retail-pct", cfg.ProfileMixRetail, "retail worker percentage")
	flag.IntVar(&cfg.ProfileMixNoise, "profile-noise-pct", cfg.ProfileMixNoise, "noise worker percentage")
	flag.BoolVar(&cfg.PrettySummary, "pretty-summary", cfg.PrettySummary, "print a human-readable console summary (default prints JSON)")
	flag.Parse()
	parsedConfig := cfg

	explicitFlags := make(map[string]bool)
	flag.Visit(func(f *flag.Flag) {
		explicitFlags[f.Name] = true
	})

	if cfg.SessionConfigPath != "" {
		requestedSessionPath := cfg.SessionConfigPath
		session, err := sessionconfig.LoadSessionFile(cfg.SessionConfigPath)
		if err != nil {
			return cfg, err
		}
		runtimeConfig, err := sessionconfig.ToRuntimeConfig(session)
		if err != nil {
			return cfg, err
		}
		cfg = mergeSessionConfig(defaults, runtimeConfig)
		cfg.SessionConfigPath = requestedSessionPath
		applyFlagOverrides(&cfg, parsedConfig, explicitFlags)
	}

	if cfg.Duration <= 0 || cfg.Workers <= 0 {
		return cfg, errors.New("duration and workers must be > 0")
	}
	if cfg.QuantityMin <= 0 || cfg.QuantityMax < cfg.QuantityMin {
		return cfg, errors.New("invalid quantity range")
	}
	if cfg.PriceMin <= 0 || cfg.PriceMax < cfg.PriceMin {
		return cfg, errors.New("invalid price range")
	}
	if cfg.SubmitPct+cfg.ModifyPct+cfg.CancelPct != 100 {
		return cfg, errors.New("submit-pct + modify-pct + cancel-pct must equal 100")
	}
	if cfg.Mode != "chaos" && cfg.Mode != "strict-lifecycle" && cfg.Mode != "capacity-baseline" {
		return cfg, errors.New("mode must be chaos, strict-lifecycle, or capacity-baseline")
	}
	if cfg.TailInterval <= 0 {
		return cfg, errors.New("tail-interval must be > 0")
	}
	if cfg.TailLines <= 0 {
		return cfg, errors.New("tail-lines must be > 0")
	}
	if cfg.ProfileMixMM+cfg.ProfileMixInst+cfg.ProfileMixRetail+cfg.ProfileMixNoise != 100 {
		return cfg, errors.New("profile mix percentages must sum to 100")
	}
	return cfg, nil
}

func defaultConfigFromEnv() Config {
	return Config{
		BaseURL:          envOr("REEF_BASE_URL", "http://localhost:8080"),
		Duration:         envDuration("REEF_DURATION", 30*time.Second),
		Workers:          envInt("REEF_WORKERS", 8),
		RatePerSecond:    envInt("REEF_RATE", 0),
		RequestTimeout:   envDuration("REEF_TIMEOUT", 5*time.Second),
		SubmitPct:        envInt("REEF_SUBMIT_PCT", 60),
		ModifyPct:        envInt("REEF_MODIFY_PCT", 25),
		CancelPct:        envInt("REEF_CANCEL_PCT", 15),
		InstrumentID:     envOr("REEF_INSTRUMENT_ID", "AAPL"),
		InstrumentSymbol: envOr("REEF_INSTRUMENT_SYMBOL", "AAPL"),
		ParticipantID:    envOr("REEF_PARTICIPANT_ID", "participant-1"),
		ParticipantName:  envOr("REEF_PARTICIPANT_NAME", "Participant 1"),
		AccountID:        envOr("REEF_ACCOUNT_ID", "account-1"),
		QuantityMin:      envInt("REEF_QTY_MIN", 10),
		QuantityMax:      envInt("REEF_QTY_MAX", 1000),
		PriceMin:         envInt64("REEF_PRICE_MIN", 149_000_000_000),
		PriceMax:         envInt64("REEF_PRICE_MAX", 151_000_000_000),
		TraceCheckLimit:  envInt("REEF_TRACE_CHECK_LIMIT", 50),
		ReportOut:        envOr("REEF_REPORT_OUT", ""),
		Mode:             envOr("REEF_MODE", "chaos"),
		Tail:             envBool("REEF_TAIL", false),
		TailInterval:     envDuration("REEF_TAIL_INTERVAL", 2*time.Second),
		TailLines:        envInt("REEF_TAIL_LINES", 5),
		ProfileMixMM:     envInt("REEF_PROFILE_MM_PCT", 35),
		ProfileMixInst:   envInt("REEF_PROFILE_INST_PCT", 30),
		ProfileMixRetail: envInt("REEF_PROFILE_RETAIL_PCT", 25),
		ProfileMixNoise:  envInt("REEF_PROFILE_NOISE_PCT", 10),
		PrettySummary:    envBool("REEF_PRETTY_SUMMARY", false),
	}
}

func mergeSessionConfig(defaults Config, session sessionconfig.RuntimeConfig) Config {
	cfg := defaults
	cfg.SessionName = session.SessionName
	cfg.ScenarioRunID = session.ScenarioRunID
	cfg.Seed = session.Seed
	cfg.BaseURL = session.BaseURL
	cfg.Duration = session.Duration
	cfg.Workers = session.Workers
	cfg.RatePerSecond = session.RatePerSecond
	cfg.RequestTimeout = session.RequestTimeout
	cfg.TraceCheckLimit = session.TraceCheckLimit
	cfg.SubmitPct = session.SubmitPct
	cfg.ModifyPct = session.ModifyPct
	cfg.CancelPct = session.CancelPct
	cfg.InstrumentID = session.InstrumentID
	cfg.InstrumentSymbol = session.InstrumentSymbol
	cfg.PriceMin = session.PriceMin
	cfg.PriceMax = session.PriceMax
	cfg.SideBiasBuyPct = session.SideBiasBuyPct
	cfg.SessionActors = append([]sessionconfig.Actor(nil), session.Actors...)
	cfg.MarketEquities = append([]sessionconfig.Equity(nil), session.Equities...)
	cfg.StrategyProfiles = session.StrategyProfiles
	cfg.Faults = append([]sessionconfig.FaultRule(nil), session.Faults...)
	cfg.HasSessionConfig = true
	if session.Mode != "" {
		cfg.Mode = session.Mode
	}
	return cfg
}

func applyFlagOverrides(cfg *Config, parsed Config, explicit map[string]bool) {
	if explicit["base-url"] {
		cfg.BaseURL = parsed.BaseURL
	}
	if explicit["duration"] {
		cfg.Duration = parsed.Duration
	}
	if explicit["workers"] {
		cfg.Workers = parsed.Workers
	}
	if explicit["rate"] {
		cfg.RatePerSecond = parsed.RatePerSecond
	}
	if explicit["timeout"] {
		cfg.RequestTimeout = parsed.RequestTimeout
	}
	if explicit["submit-pct"] {
		cfg.SubmitPct = parsed.SubmitPct
	}
	if explicit["modify-pct"] {
		cfg.ModifyPct = parsed.ModifyPct
	}
	if explicit["cancel-pct"] {
		cfg.CancelPct = parsed.CancelPct
	}
	if explicit["instrument-id"] {
		cfg.InstrumentID = parsed.InstrumentID
	}
	if explicit["instrument-symbol"] {
		cfg.InstrumentSymbol = parsed.InstrumentSymbol
	}
	if explicit["participant-id"] {
		cfg.ParticipantID = parsed.ParticipantID
	}
	if explicit["participant-name"] {
		cfg.ParticipantName = parsed.ParticipantName
	}
	if explicit["account-id"] {
		cfg.AccountID = parsed.AccountID
	}
	if explicit["qty-min"] {
		cfg.QuantityMin = parsed.QuantityMin
	}
	if explicit["qty-max"] {
		cfg.QuantityMax = parsed.QuantityMax
	}
	if explicit["price-min"] {
		cfg.PriceMin = parsed.PriceMin
	}
	if explicit["price-max"] {
		cfg.PriceMax = parsed.PriceMax
	}
	if explicit["trace-check-limit"] {
		cfg.TraceCheckLimit = parsed.TraceCheckLimit
	}
	if explicit["report-out"] {
		cfg.ReportOut = parsed.ReportOut
	}
	if explicit["mode"] {
		cfg.Mode = parsed.Mode
	}
	if explicit["tail"] {
		cfg.Tail = parsed.Tail
	}
	if explicit["tail-interval"] {
		cfg.TailInterval = parsed.TailInterval
	}
	if explicit["tail-lines"] {
		cfg.TailLines = parsed.TailLines
	}
	if explicit["profile-mm-pct"] {
		cfg.ProfileMixMM = parsed.ProfileMixMM
	}
	if explicit["profile-inst-pct"] {
		cfg.ProfileMixInst = parsed.ProfileMixInst
	}
	if explicit["profile-retail-pct"] {
		cfg.ProfileMixRetail = parsed.ProfileMixRetail
	}
	if explicit["profile-noise-pct"] {
		cfg.ProfileMixNoise = parsed.ProfileMixNoise
	}
	if explicit["pretty-summary"] {
		cfg.PrettySummary = parsed.PrettySummary
	}
}

func runWorker(
	ctx context.Context,
	client *http.Client,
	cfg Config,
	workerID int,
	profile string,
	counter *int64,
	rateCh <-chan struct{},
	results chan<- requestResult,
	traceSeen *sync.Map,
) {
	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(workerID)*7919))
	if cfg.HasSessionConfig && cfg.Seed != 0 {
		rng = rand.New(rand.NewSource(cfg.Seed + int64(workerID)*7919))
	}
	state := workerState{orders: make([]string, 0, 128)}
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		if cfg.RatePerSecond > 0 {
			select {
			case <-ctx.Done():
				return
			case <-rateCh:
			}
		}

		actor := chooseSessionActor(rng, cfg)
		effectiveProfile := profile
		if actor != nil {
			effectiveProfile = actor.ActorType
		}
		action := chooseActionForActor(rng, cfg, len(state.orders) > 0, effectiveProfile, actor)
		reqID := atomic.AddInt64(counter, 1)
		traceID := fmt.Sprintf("trace-%d-%d", workerID, reqID)
		commandID := fmt.Sprintf("cmd-%d-%d", workerID, reqID)
		start := time.Now()
		actorID := fmt.Sprintf("bot-%d", workerID)
		actorType := effectiveProfile
		persona := ""
		strategyID := ""
		if actor != nil {
			actorID = actor.ActorID
			actorType = actor.ActorType
			persona = actor.Persona
			strategyID = actor.StrategyID
		}
		result := requestResult{
			Profile:    effectiveProfile,
			ActorID:    actorID,
			ActorType:  actorType,
			Persona:    persona,
			StrategyID: strategyID,
			Action:     action,
			TraceID:    traceID,
			CommandID:  commandID,
		}
		switch action {
		case ActionSubmit:
			orderID := fmt.Sprintf("ord-%d-%d", workerID, reqID)
			result.OrderID = orderID
			instrumentID := cfg.InstrumentID
			instrument := chooseInstrumentForActor(rng, cfg, actor)
			if instrument != nil {
				instrumentID = instrument.InstrumentID
			}
			if shouldInjectSubmitFault(rng, cfg, instrumentID) {
				result.StatusCode = 200
				result.ErrorText = "rejected:INJECTED_FAULT"
				result.RejectCode = "INJECTED_FAULT"
				result.RejectReason = "deterministic fault injection"
				results <- result
				continue
			}
			payload := buildCommandPayload(cfg, commandID, traceID, actorID, actorType, persona, strategyID)
			payload["orderId"] = orderID
			payload["instrumentId"] = instrumentID
			payload["participantId"] = cfg.ParticipantID
			payload["accountId"] = cfg.AccountID
			payload["side"] = chooseSideForConfig(rng, cfg)
			payload["orderType"] = "LIMIT"
			payload["quantityUnits"] = fmt.Sprintf("%d", profileQuantity(rng, cfg, profile))
			payload["limitPrice"] = fmt.Sprintf("%d", profilePrice(rng, cfg, effectiveProfile, instrument))
			payload["currency"] = "USD"
			payload["timeInForce"] = "DAY"
			status, body, err := doPOST(client, cfg.BaseURL+"/orders/submit", payload)
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.orders = append(state.orders, orderID)
				traceSeen.Store(traceID, struct{}{})
			}
		case ActionModify:
			if len(state.orders) == 0 {
				continue
			}
			orderID := pickOrderID(rng, state.orders, cfg.Mode)
			result.OrderID = orderID
			payload := buildCommandPayload(cfg, commandID, traceID, actorID, actorType, persona, strategyID)
			payload["orderId"] = orderID
			payload["quantityUnits"] = fmt.Sprintf("%d", profileQuantity(rng, cfg, profile))
			payload["limitPrice"] = fmt.Sprintf("%d", profilePrice(rng, cfg, effectiveProfile, nil))
			status, body, err := doPOST(client, cfg.BaseURL+"/orders/modify", payload)
			fillResult(&result, status, body, err, start)
			if result.Success {
				traceSeen.Store(traceID, struct{}{})
			} else if shouldPruneTerminalOrder(cfg.Mode) && isTerminalOrderRejection(result.RejectCode) {
				state.orders = removeOrder(state.orders, orderID)
			}
		case ActionCancel:
			if len(state.orders) == 0 {
				continue
			}
			idx := pickOrderIndex(rng, state.orders, cfg.Mode)
			orderID := state.orders[idx]
			result.OrderID = orderID
			payload := buildCommandPayload(cfg, commandID, traceID, actorID, actorType, persona, strategyID)
			payload["orderId"] = orderID
			payload["reason"] = "load test"
			status, body, err := doPOST(client, cfg.BaseURL+"/orders/cancel", payload)
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.orders = append(state.orders[:idx], state.orders[idx+1:]...)
				traceSeen.Store(traceID, struct{}{})
			} else if shouldPruneTerminalOrder(cfg.Mode) && isTerminalOrderRejection(result.RejectCode) {
				state.orders = removeOrder(state.orders, orderID)
			}
		}
		results <- result
	}
}

func fillResult(result *requestResult, status int, body []byte, err error, start time.Time) {
	result.StatusCode = status
	result.Latency = time.Since(start)
	if err != nil {
		result.ErrorText = err.Error()
		return
	}
	if status < 200 || status >= 300 {
		result.ErrorText = fmt.Sprintf("http_%d", status)
		return
	}
	text := string(body)
	if strings.Contains(text, "\"rejected\"") {
		var parsed runtimeResponse
		if err := json.Unmarshal(body, &parsed); err == nil && parsed.Rejected != nil {
			result.RejectCode = parsed.Rejected.Code
			result.RejectReason = parsed.Rejected.Reason
			if parsed.Rejected.Code != "" {
				result.ErrorText = "rejected:" + parsed.Rejected.Code
			} else {
				result.ErrorText = "rejected"
			}
		} else {
			result.ErrorText = "rejected"
		}
		return
	}
	result.Success = true
}

func buildSummary(sessionID string, started, finished time.Time, cfg Config, results []requestResult) summary {
	report := summary{
		SessionID:       sessionID,
		StartedAt:       started,
		FinishedAt:      finished,
		DurationSeconds: finished.Sub(started).Seconds(),
		Config:          cfg,
		ByAction: map[Action]actionSummary{
			ActionSubmit: {},
			ActionModify: {},
			ActionCancel: {},
		},
		ByProfile:   map[string]profileSummary{},
		ByActor:     map[string]profileSummary{},
		ByPersona:   map[string]profileSummary{},
		ByStrategy:  map[string]profileSummary{},
		StatusCodes: make(map[int]int64),
	}

	errorCounts := make(map[string]int64)
	rejectReasons := make(map[string]int64)
	rejectCodes := make(map[string]int64)
	totalRejects := int64(0)
	allLatencies := make([]float64, 0, len(results))
	actionLatencies := map[Action][]float64{
		ActionSubmit: {},
		ActionModify: {},
		ActionCancel: {},
	}
	profileLatencies := map[string][]float64{}
	actorLatencies := map[string][]float64{}
	personaLatencies := map[string][]float64{}
	strategyLatencies := map[string][]float64{}

	for _, r := range results {
		report.TotalRequests++
		report.StatusCodes[r.StatusCode]++
		allLatencies = append(allLatencies, r.Latency.Seconds()*1000)
		actionLatencies[r.Action] = append(actionLatencies[r.Action], r.Latency.Seconds()*1000)
		profileLatencies[r.Profile] = append(profileLatencies[r.Profile], r.Latency.Seconds()*1000)
		if r.ActorID != "" {
			actorLatencies[r.ActorID] = append(actorLatencies[r.ActorID], r.Latency.Seconds()*1000)
		}
		if r.Persona != "" {
			personaLatencies[r.Persona] = append(personaLatencies[r.Persona], r.Latency.Seconds()*1000)
		}
		if r.StrategyID != "" {
			strategyLatencies[r.StrategyID] = append(strategyLatencies[r.StrategyID], r.Latency.Seconds()*1000)
		}

		current := report.ByAction[r.Action]
		current.Requests++
		if r.Success {
			report.TotalSuccess++
			current.Success++
		} else {
			report.TotalFailures++
			current.Failures++
			if r.ErrorText != "" {
				errorCounts[r.ErrorText]++
			}
			if r.RejectCode != "" {
				rejectCodes[r.RejectCode]++
				totalRejects++
				key := r.RejectCode
				if r.RejectReason != "" {
					key = key + ": " + r.RejectReason
				}
				rejectReasons[key]++
			}
		}
		report.ByAction[r.Action] = current

		pCurrent, ok := report.ByProfile[r.Profile]
		if !ok {
			pCurrent = profileSummary{
				ByAction: map[Action]actionSummary{
					ActionSubmit: {},
					ActionModify: {},
					ActionCancel: {},
				},
			}
		}
		pCurrent.Requests++
		pAction := pCurrent.ByAction[r.Action]
		pAction.Requests++
		if r.Success {
			pCurrent.Success++
			pAction.Success++
		} else {
			pCurrent.Failures++
			pAction.Failures++
		}
		pCurrent.ByAction[r.Action] = pAction
		report.ByProfile[r.Profile] = pCurrent
		updateDimensionSummary(report.ByActor, r.ActorID, r.Action, r.Success)
		updateDimensionSummary(report.ByPersona, r.Persona, r.Action, r.Success)
		updateDimensionSummary(report.ByStrategy, r.StrategyID, r.Action, r.Success)
	}

	report.ThroughputRPS = float64(report.TotalRequests) / report.DurationSeconds
	report.AcceptedBusinessOpsRPS = float64(report.TotalSuccess) / report.DurationSeconds
	report.LatencyMs = computeLatency(allLatencies)
	for action, values := range actionLatencies {
		current := report.ByAction[action]
		current.Latency = computeLatency(values)
		report.ByAction[action] = current
	}
	for profile, values := range profileLatencies {
		pCurrent := report.ByProfile[profile]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencies(&pCurrent, results, func(r requestResult) bool { return r.Profile == profile })
		report.ByProfile[profile] = pCurrent
	}
	for actorID, values := range actorLatencies {
		pCurrent := report.ByActor[actorID]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencies(&pCurrent, results, func(r requestResult) bool { return r.ActorID == actorID })
		report.ByActor[actorID] = pCurrent
	}
	for persona, values := range personaLatencies {
		pCurrent := report.ByPersona[persona]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencies(&pCurrent, results, func(r requestResult) bool { return r.Persona == persona })
		report.ByPersona[persona] = pCurrent
	}
	for strategyID, values := range strategyLatencies {
		pCurrent := report.ByStrategy[strategyID]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencies(&pCurrent, results, func(r requestResult) bool { return r.StrategyID == strategyID })
		report.ByStrategy[strategyID] = pCurrent
	}
	report.TopErrors = topErrors(errorCounts, 8)
	report.RejectReasons = topErrors(rejectReasons, 12)
	report.RejectTaxonomy = summarizeRejectTaxonomy(rejectCodes, report.TotalFailures, totalRejects, 12)
	return report
}

func summarizeRejectTaxonomy(rejectCodes map[string]int64, totalFailures int64, totalRejects int64, limit int) []rejectTaxonomySummary {
	if len(rejectCodes) == 0 {
		return nil
	}
	keys := make([]string, 0, len(rejectCodes))
	for code := range rejectCodes {
		keys = append(keys, code)
	}
	sort.Slice(keys, func(i, j int) bool {
		left := rejectCodes[keys[i]]
		right := rejectCodes[keys[j]]
		if left == right {
			return keys[i] < keys[j]
		}
		return left > right
	})
	if len(keys) > limit {
		keys = keys[:limit]
	}
	out := make([]rejectTaxonomySummary, 0, len(keys))
	for _, code := range keys {
		count := rejectCodes[code]
		row := rejectTaxonomySummary{Code: code, Count: count}
		if totalFailures > 0 {
			row.PercentOfFailures = (float64(count) / float64(totalFailures)) * 100
		}
		if totalRejects > 0 {
			row.PercentOfRejects = (float64(count) / float64(totalRejects)) * 100
		}
		out = append(out, row)
	}
	return out
}

func updateDimensionSummary(target map[string]profileSummary, key string, action Action, success bool) {
	if key == "" {
		return
	}
	current, ok := target[key]
	if !ok {
		current = profileSummary{
			ByAction: map[Action]actionSummary{
				ActionSubmit: {},
				ActionModify: {},
				ActionCancel: {},
			},
		}
	}
	current.Requests++
	actionRow := current.ByAction[action]
	actionRow.Requests++
	if success {
		current.Success++
		actionRow.Success++
	} else {
		current.Failures++
		actionRow.Failures++
	}
	current.ByAction[action] = actionRow
	target[key] = current
}

func applyActionLatencies(current *profileSummary, results []requestResult, match func(requestResult) bool) {
	for action, actionSummary := range current.ByAction {
		actionValues := make([]float64, 0, len(results))
		for _, r := range results {
			if match(r) && r.Action == action {
				actionValues = append(actionValues, r.Latency.Seconds()*1000)
			}
		}
		actionSummary.Latency = computeLatency(actionValues)
		current.ByAction[action] = actionSummary
	}
}

func runTraceChecks(client *http.Client, cfg Config, seen *sync.Map) traceChecks {
	traceIDs := make([]string, 0, cfg.TraceCheckLimit)
	seen.Range(func(key, _ any) bool {
		traceIDs = append(traceIDs, key.(string))
		return len(traceIDs) < cfg.TraceCheckLimit
	})
	sort.Strings(traceIDs)

	checks := traceChecks{Checked: len(traceIDs), FailedTraceID: make([]string, 0, 8)}
	for _, traceID := range traceIDs {
		ok := checkTrace(client, cfg.BaseURL, traceID)
		if ok {
			checks.Pass++
		} else {
			checks.Fail++
			if len(checks.FailedTraceID) < 8 {
				checks.FailedTraceID = append(checks.FailedTraceID, traceID)
			}
		}
	}
	return checks
}

func tailLoop(ctx context.Context, client *http.Client, cfg Config) {
	ticker := time.NewTicker(cfg.TailInterval)
	defer ticker.Stop()
	seenTrades := make(map[string]struct{})
	seenEvents := make(map[string]struct{})

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			trades := fetchNewTrades(client, cfg.BaseURL, seenTrades, cfg.TailLines)
			events := fetchNewEvents(client, cfg.BaseURL, seenEvents, cfg.TailLines)
			if len(trades) == 0 && len(events) == 0 {
				continue
			}
			fmt.Printf("[tail %s] new_trades=%d new_events=%d\n", time.Now().UTC().Format(time.RFC3339), len(trades), len(events))
			for _, t := range trades {
				fmt.Printf("  trade %s buy=%s sell=%s qty=%s px=%s at=%s\n", t.TradeID, t.BuyOrderID, t.SellOrderID, t.QuantityUnits, t.Price, t.OccurredAt)
			}
			for _, e := range events {
				fmt.Printf("  event %s type=%s order=%s trace=%s seq=%d at=%s\n", e.EventID, e.EventType, e.OrderID, e.TraceID, e.SequenceNumber, e.OccurredAt)
			}
		}
	}
}

func fetchNewTrades(client *http.Client, baseURL string, seen map[string]struct{}, limit int) []trade {
	resp, err := client.Get(fmt.Sprintf("%s/trades?limit=%d", baseURL, limit))
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil
	}
	var parsed tradesResponse
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil
	}
	out := make([]trade, 0, limit)
	for i := len(parsed.Trades) - 1; i >= 0 && len(out) < limit; i-- {
		row := parsed.Trades[i]
		if _, ok := seen[row.EventID]; ok {
			continue
		}
		seen[row.EventID] = struct{}{}
		out = append(out, row)
	}
	reverseTrades(out)
	return out
}

func fetchNewEvents(client *http.Client, baseURL string, seen map[string]struct{}, limit int) []event {
	resp, err := client.Get(fmt.Sprintf("%s/events?limit=%d", baseURL, limit))
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil
	}
	var parsed eventsResponse
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil
	}
	out := make([]event, 0, limit)
	for i := len(parsed.Events) - 1; i >= 0 && len(out) < limit; i-- {
		row := parsed.Events[i]
		if _, ok := seen[row.EventID]; ok {
			continue
		}
		seen[row.EventID] = struct{}{}
		out = append(out, row)
	}
	reverseEvents(out)
	return out
}

func checkTrace(client *http.Client, baseURL, traceID string) bool {
	for attempt := 0; attempt < 3; attempt++ {
		if checkTraceOnce(client, baseURL, traceID) {
			return true
		}
		time.Sleep(50 * time.Millisecond)
	}
	return false
}

func checkTraceOnce(client *http.Client, baseURL, traceID string) bool {
	resp, err := client.Get(baseURL + "/traces/" + traceID + "/events")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return false
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false
	}
	var parsed traceEventsResponse
	if err := json.Unmarshal(body, &parsed); err != nil {
		return false
	}
	if len(parsed.Events) == 0 {
		return false
	}
	sort.Slice(parsed.Events, func(i, j int) bool {
		return parsed.Events[i].SequenceNumber < parsed.Events[j].SequenceNumber
	})
	last := int64(0)
	for _, event := range parsed.Events {
		if event.TraceID != traceID {
			return false
		}
		if event.SequenceNumber <= 0 {
			return false
		}
		// tolerate occasional sequence gaps under high churn, but never allow regressions.
		if event.SequenceNumber < last {
			return false
		}
		last = event.SequenceNumber
	}
	return true
}

func seedReferenceData(client *http.Client, cfg Config) error {
	if cfg.HasSessionConfig && len(cfg.MarketEquities) > 0 {
		for _, eq := range cfg.MarketEquities {
			if _, _, err := doPOST(client, cfg.BaseURL+"/reference/instruments", map[string]string{
				"instrumentId": eq.InstrumentID,
				"symbol":       eq.Symbol,
			}); err != nil {
				return err
			}
		}
	} else {
		if _, _, err := doPOST(client, cfg.BaseURL+"/reference/instruments", map[string]string{
			"instrumentId": cfg.InstrumentID,
			"symbol":       cfg.InstrumentSymbol,
		}); err != nil {
			return err
		}
	}
	if _, _, err := doPOST(client, cfg.BaseURL+"/reference/participants", map[string]string{
		"participantId": cfg.ParticipantID,
		"name":          cfg.ParticipantName,
	}); err != nil {
		return err
	}
	if _, _, err := doPOST(client, cfg.BaseURL+"/reference/accounts", map[string]string{
		"accountId":     cfg.AccountID,
		"participantId": cfg.ParticipantID,
	}); err != nil {
		return err
	}
	return nil
}

func doPOST(client *http.Client, url string, payload map[string]string) (int, []byte, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, err
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(resp.Body)
	return resp.StatusCode, respBody, err
}

func buildCommandPayload(cfg Config, commandID, traceID, actorID, actorType, persona, strategyID string) map[string]string {
	payload := map[string]string{
		"commandId":     commandID,
		"traceId":       traceID,
		"causationId":   "",
		"correlationId": traceID,
		"actorId":       actorID,
		"actorType":     actorType,
		"strategyId":    strategyID,
		"occurredAt":    time.Now().UTC().Format(time.RFC3339),
	}
	if persona != "" {
		payload["persona"] = persona
	}
	if cfg.ScenarioRunID != "" {
		payload["scenarioRunId"] = cfg.ScenarioRunID
	}
	if cfg.Seed != 0 {
		payload["seed"] = strconv.FormatInt(cfg.Seed, 10)
	}
	return payload
}

func chooseAction(rng *rand.Rand, cfg Config, hasOrders bool) Action {
	if (cfg.Mode == "strict-lifecycle" || cfg.Mode == "capacity-baseline") && !hasOrders {
		return ActionSubmit
	}
	if cfg.Mode == "capacity-baseline" {
		return weightedAction(rng, 88, 8)
	}
	n := rng.Intn(100)
	if n < cfg.SubmitPct {
		return ActionSubmit
	}
	if n < cfg.SubmitPct+cfg.ModifyPct {
		return ActionModify
	}
	return ActionCancel
}

func chooseActionForProfile(rng *rand.Rand, cfg Config, hasOrders bool, profile string) Action {
	if (cfg.Mode == "strict-lifecycle" || cfg.Mode == "capacity-baseline") && !hasOrders {
		return ActionSubmit
	}
	if cfg.Mode == "capacity-baseline" {
		return weightedAction(rng, 88, 8)
	}
	switch profile {
	case profileMarketMaker:
		return weightedAction(rng, 45, 40)
	case profileInstitutional:
		return weightedAction(rng, 55, 30)
	case profileRetail:
		return weightedAction(rng, 70, 20)
	case profileNoise:
		return weightedAction(rng, 50, 20)
	default:
		return chooseAction(rng, cfg, hasOrders)
	}
}

func chooseActionForActor(rng *rand.Rand, cfg Config, hasOrders bool, profile string, actor *sessionconfig.Actor) Action {
	if actor != nil {
		if mix, ok := strategy.ActionMixForActor(*actor, cfg.StrategyProfiles); ok {
			if (cfg.Mode == "strict-lifecycle" || cfg.Mode == "capacity-baseline") && !hasOrders {
				return ActionSubmit
			}
			return weightedAction(rng, mix.SubmitPct, mix.ModifyPct)
		}
	}
	return chooseActionForProfile(rng, cfg, hasOrders, profile)
}

func weightedAction(rng *rand.Rand, submitPct, modifyPct int) Action {
	n := rng.Intn(100)
	if n < submitPct {
		return ActionSubmit
	}
	if n < submitPct+modifyPct {
		return ActionModify
	}
	return ActionCancel
}

func pickOrderID(rng *rand.Rand, orders []string, mode string) string {
	if len(orders) == 0 {
		return ""
	}
	if mode == "capacity-baseline" {
		return orders[len(orders)-1]
	}
	return orders[rng.Intn(len(orders))]
}

func pickOrderIndex(rng *rand.Rand, orders []string, mode string) int {
	if len(orders) == 0 {
		return 0
	}
	if mode == "capacity-baseline" {
		return len(orders) - 1
	}
	return rng.Intn(len(orders))
}

func isTerminalOrderRejection(code string) bool {
	return code == "INVALID_STATE" || code == "NOT_FOUND"
}

func shouldPruneTerminalOrder(mode string) bool {
	return mode == "strict-lifecycle" || mode == "capacity-baseline"
}

func removeOrder(orders []string, orderID string) []string {
	for i, existing := range orders {
		if existing == orderID {
			return append(orders[:i], orders[i+1:]...)
		}
	}
	return orders
}

func profileForWorker(workerID, workers int, cfg Config) string {
	pct := workerID * 100 / workers
	if pct < cfg.ProfileMixMM {
		return profileMarketMaker
	}
	if pct < cfg.ProfileMixMM+cfg.ProfileMixInst {
		return profileInstitutional
	}
	if pct < cfg.ProfileMixMM+cfg.ProfileMixInst+cfg.ProfileMixRetail {
		return profileRetail
	}
	return profileNoise
}

func reverseTrades(values []trade) {
	for i, j := 0, len(values)-1; i < j; i, j = i+1, j-1 {
		values[i], values[j] = values[j], values[i]
	}
}

func reverseEvents(values []event) {
	for i, j := 0, len(values)-1; i < j; i, j = i+1, j-1 {
		values[i], values[j] = values[j], values[i]
	}
}

func chooseSide(rng *rand.Rand) string {
	if rng.Intn(2) == 0 {
		return "BUY"
	}
	return "SELL"
}

func chooseSideForConfig(rng *rand.Rand, cfg Config) string {
	if cfg.HasSessionConfig {
		if cfg.SideBiasBuyPct <= 0 {
			return "SELL"
		}
		if cfg.SideBiasBuyPct >= 100 {
			return "BUY"
		}
		if rng.Intn(100) < cfg.SideBiasBuyPct {
			return "BUY"
		}
		return "SELL"
	}
	return chooseSide(rng)
}

func chooseSessionActor(rng *rand.Rand, cfg Config) *sessionconfig.Actor {
	if !cfg.HasSessionConfig || len(cfg.SessionActors) == 0 {
		return nil
	}
	total := 0
	for _, actor := range cfg.SessionActors {
		total += actor.Weight
	}
	if total <= 0 {
		return nil
	}
	pick := rng.Intn(total)
	running := 0
	for i := range cfg.SessionActors {
		running += cfg.SessionActors[i].Weight
		if pick < running {
			return &cfg.SessionActors[i]
		}
	}
	return &cfg.SessionActors[len(cfg.SessionActors)-1]
}

func randomInt(rng *rand.Rand, min, max int) int {
	if min == max {
		return min
	}
	return min + rng.Intn(max-min+1)
}

func profileQuantity(rng *rand.Rand, cfg Config, profile string) int {
	switch profile {
	case profileMarketMaker:
		return randomInt(rng, maxInt(cfg.QuantityMin, 25), minInt(cfg.QuantityMax, 250))
	case profileInstitutional:
		return randomInt(rng, maxInt(cfg.QuantityMin, 200), cfg.QuantityMax)
	case profileRetail:
		return randomInt(rng, cfg.QuantityMin, minInt(cfg.QuantityMax, 100))
	default:
		return randomInt(rng, cfg.QuantityMin, cfg.QuantityMax)
	}
}

func randomInt64(rng *rand.Rand, min, max int64) int64 {
	if min == max {
		return min
	}
	return min + rng.Int63n(max-min+1)
}

func profilePrice(rng *rand.Rand, cfg Config, profile string, instrument *sessionconfig.Equity) int64 {
	if instrument != nil {
		base := instrument.StartingPriceNanos
		if base <= 0 {
			base = cfg.PriceMin
		}
		volBps := instrument.VolatilityBps
		if volBps <= 0 {
			volBps = 100
		}
		span := (base * int64(volBps)) / 10_000
		if span <= 0 {
			span = maxInt64(1, (cfg.PriceMax-cfg.PriceMin)/10)
		}
		return randomInt64(rng, maxInt64(1, base-span), base+span)
	}
	switch profile {
	case profileMarketMaker:
		mid := (cfg.PriceMin + cfg.PriceMax) / 2
		span := (cfg.PriceMax - cfg.PriceMin) / 8
		if span <= 0 {
			return mid
		}
		return randomInt64(rng, mid-span, mid+span)
	default:
		return randomInt64(rng, cfg.PriceMin, cfg.PriceMax)
	}
}

func chooseInstrumentForActor(rng *rand.Rand, cfg Config, actor *sessionconfig.Actor) *sessionconfig.Equity {
	if !cfg.HasSessionConfig || len(cfg.MarketEquities) == 0 {
		return nil
	}
	if actor == nil || len(actor.Symbols) == 0 {
		return &cfg.MarketEquities[rng.Intn(len(cfg.MarketEquities))]
	}
	eligible := make([]sessionconfig.Equity, 0, len(cfg.MarketEquities))
	allow := make(map[string]struct{}, len(actor.Symbols))
	for _, symbol := range actor.Symbols {
		allow[symbol] = struct{}{}
	}
	for _, eq := range cfg.MarketEquities {
		if _, ok := allow[eq.Symbol]; ok {
			eligible = append(eligible, eq)
		}
	}
	if len(eligible) == 0 {
		return &cfg.MarketEquities[rng.Intn(len(cfg.MarketEquities))]
	}
	return &eligible[rng.Intn(len(eligible))]
}

func shouldInjectSubmitFault(rng *rand.Rand, cfg Config, instrumentID string) bool {
	if !cfg.HasSessionConfig || len(cfg.Faults) == 0 {
		return false
	}
	symbol := instrumentID
	for _, eq := range cfg.MarketEquities {
		if eq.InstrumentID == instrumentID {
			symbol = eq.Symbol
			break
		}
	}
	for _, fault := range cfg.Faults {
		if fault.Type != "reject_submit" {
			continue
		}
		if fault.Symbol != "" && fault.Symbol != symbol {
			continue
		}
		probability := fault.Probability
		if probability <= 0 {
			probability = 1.0
		}
		if rng.Float64() <= probability {
			return true
		}
	}
	return false
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func maxInt64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

func tokenFeeder(ctx context.Context, rate int, out chan<- struct{}) {
	period := time.Second / time.Duration(rate)
	if period <= 0 {
		period = time.Microsecond
	}
	ticker := time.NewTicker(period)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			select {
			case out <- struct{}{}:
			default:
			}
		}
	}
}

func computeLatency(values []float64) latencySummary {
	if len(values) == 0 {
		return latencySummary{}
	}
	sorted := append([]float64(nil), values...)
	sort.Float64s(sorted)
	return latencySummary{
		Min: sorted[0],
		P50: percentile(sorted, 50),
		P95: percentile(sorted, 95),
		P99: percentile(sorted, 99),
		Max: sorted[len(sorted)-1],
	}
}

func percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	rank := p / 100 * float64(len(sorted)-1)
	lo := int(rank)
	hi := lo + 1
	if hi >= len(sorted) {
		return sorted[lo]
	}
	frac := rank - float64(lo)
	return sorted[lo] + (sorted[hi]-sorted[lo])*frac
}

func topErrors(m map[string]int64, limit int) []errorSummary {
	values := make([]errorSummary, 0, len(m))
	for errText, count := range m {
		values = append(values, errorSummary{Error: errText, Count: count})
	}
	sort.Slice(values, func(i, j int) bool {
		if values[i].Count == values[j].Count {
			return values[i].Error < values[j].Error
		}
		return values[i].Count > values[j].Count
	})
	if len(values) > limit {
		values = values[:limit]
	}
	return values
}

func printSummary(report summary) {
	if report.Config.PrettySummary {
		printPrettySummary(report)
		return
	}
	blob, _ := json.MarshalIndent(report, "", "  ")
	fmt.Println(string(blob))
}

func printPrettySummary(report summary) {
	successRate := 0.0
	if report.TotalRequests > 0 {
		successRate = (float64(report.TotalSuccess) / float64(report.TotalRequests)) * 100
	}
	tracePassRate := 0.0
	if report.TraceChecks.Checked > 0 {
		tracePassRate = (float64(report.TraceChecks.Pass) / float64(report.TraceChecks.Checked)) * 100
	}

	fmt.Printf("\nReef Load Test Summary\n")
	fmt.Printf("Session: %s\n", report.SessionID)
	fmt.Printf("Window : %s -> %s (%.1fs)\n", report.StartedAt.Format(time.RFC3339), report.FinishedAt.Format(time.RFC3339), report.DurationSeconds)
	fmt.Printf("Mode   : %s | Workers: %d | Rate: %d rps\n", report.Config.Mode, report.Config.Workers, report.Config.RatePerSecond)
	fmt.Printf("Mix    : mm=%d inst=%d retail=%d noise=%d\n\n", report.Config.ProfileMixMM, report.Config.ProfileMixInst, report.Config.ProfileMixRetail, report.Config.ProfileMixNoise)

	fmt.Printf("Totals\n")
	fmt.Printf("  requests=%d success=%d failures=%d throughput=%.2f rps accepted=%.2f rps\n",
		report.TotalRequests, report.TotalSuccess, report.TotalFailures, report.ThroughputRPS, report.AcceptedBusinessOpsRPS)
	fmt.Printf("  success-rate=%.2f%%\n", successRate)
	fmt.Printf("  latency(ms): min=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f\n\n",
		report.LatencyMs.Min, report.LatencyMs.P50, report.LatencyMs.P95, report.LatencyMs.P99, report.LatencyMs.Max)

	fmt.Printf("By Action\n")
	fmt.Printf("  %-8s %10s %10s %10s %10s %10s %10s\n", "action", "req", "ok", "fail", "p50ms", "p95ms", "p99ms")
	actions := []Action{ActionSubmit, ActionModify, ActionCancel}
	for _, action := range actions {
		v := report.ByAction[action]
		fmt.Printf("  %-8s %10d %10d %10d %10.2f %10.2f %10.2f\n",
			action, v.Requests, v.Success, v.Failures, v.Latency.P50, v.Latency.P95, v.Latency.P99)
	}
	fmt.Printf("\n")

	fmt.Printf("By Profile\n")
	fmt.Printf("  %-14s %10s %10s %10s %10s %10s %10s\n", "profile", "req", "ok", "fail", "p50ms", "p95ms", "p99ms")
	profiles := []string{profileMarketMaker, profileInstitutional, profileRetail, profileNoise}
	for _, profile := range profiles {
		v, ok := report.ByProfile[profile]
		if !ok {
			continue
		}
		fmt.Printf("  %-14s %10d %10d %10d %10.2f %10.2f %10.2f\n",
			profile, v.Requests, v.Success, v.Failures, v.Latency.P50, v.Latency.P95, v.Latency.P99)
	}
	fmt.Printf("\n")

	if len(report.ByActor) > 0 {
		fmt.Printf("Top Actors\n")
		fmt.Printf("  %-20s %10s %10s %10s %10s %10s %10s\n", "actor", "req", "ok", "fail", "p50ms", "p95ms", "p99ms")
		for _, actorID := range topProfileKeys(report.ByActor, 8) {
			v := report.ByActor[actorID]
			fmt.Printf("  %-20s %10d %10d %10d %10.2f %10.2f %10.2f\n",
				actorID, v.Requests, v.Success, v.Failures, v.Latency.P50, v.Latency.P95, v.Latency.P99)
		}
		fmt.Printf("\n")
	}

	if len(report.ByStrategy) > 0 {
		fmt.Printf("Top Strategies\n")
		fmt.Printf("  %-20s %10s %10s %10s %10s %10s %10s\n", "strategy", "req", "ok", "fail", "p50ms", "p95ms", "p99ms")
		for _, strategyID := range topProfileKeys(report.ByStrategy, 8) {
			v := report.ByStrategy[strategyID]
			fmt.Printf("  %-20s %10d %10d %10d %10.2f %10.2f %10.2f\n",
				strategyID, v.Requests, v.Success, v.Failures, v.Latency.P50, v.Latency.P95, v.Latency.P99)
		}
		fmt.Printf("\n")
	}

	if len(report.ByPersona) > 0 {
		fmt.Printf("Top Personas\n")
		fmt.Printf("  %-24s %10s %10s %10s %10s %10s %10s\n", "persona", "req", "ok", "fail", "p50ms", "p95ms", "p99ms")
		for _, persona := range topProfileKeys(report.ByPersona, 8) {
			v := report.ByPersona[persona]
			fmt.Printf("  %-24s %10d %10d %10d %10.2f %10.2f %10.2f\n",
				persona, v.Requests, v.Success, v.Failures, v.Latency.P50, v.Latency.P95, v.Latency.P99)
		}
		fmt.Printf("\n")
	}

	if len(report.StatusCodes) > 0 {
		fmt.Printf("Status Codes\n")
		codes := make([]int, 0, len(report.StatusCodes))
		for code := range report.StatusCodes {
			codes = append(codes, code)
		}
		sort.Ints(codes)
		for _, code := range codes {
			fmt.Printf("  %d: %d\n", code, report.StatusCodes[code])
		}
		fmt.Printf("\n")
	}

	fmt.Printf("Trace Checks\n")
	fmt.Printf("  checked=%d pass=%d fail=%d\n", report.TraceChecks.Checked, report.TraceChecks.Pass, report.TraceChecks.Fail)
	fmt.Printf("  pass-rate=%.2f%%\n", tracePassRate)
	if len(report.TraceChecks.FailedTraceID) > 0 {
		limit := len(report.TraceChecks.FailedTraceID)
		if limit > 5 {
			limit = 5
		}
		fmt.Printf("  failed trace sample: %s\n", strings.Join(report.TraceChecks.FailedTraceID[:limit], ", "))
	}
	fmt.Printf("\n")

	if len(report.RejectReasons) > 0 {
		fmt.Printf("Top Reject Reasons\n")
		limit := len(report.RejectReasons)
		if limit > 8 {
			limit = 8
		}
		for i := 0; i < limit; i++ {
			item := report.RejectReasons[i]
			fmt.Printf("  %2d. %s (%d)\n", i+1, item.Error, item.Count)
		}
		fmt.Printf("\n")
	}

	if len(report.RejectTaxonomy) > 0 {
		fmt.Printf("Reject Taxonomy\n")
		fmt.Printf("  %-22s %10s %10s %10s\n", "code", "count", "fail%", "reject%")
		for _, item := range report.RejectTaxonomy {
			fmt.Printf("  %-22s %10d %9.2f%% %9.2f%%\n", item.Code, item.Count, item.PercentOfFailures, item.PercentOfRejects)
		}
		fmt.Printf("\n")
	}

	if len(report.TopErrors) > 0 {
		fmt.Printf("Top Errors\n")
		limit := len(report.TopErrors)
		if limit > 8 {
			limit = 8
		}
		for i := 0; i < limit; i++ {
			item := report.TopErrors[i]
			fmt.Printf("  %2d. %s (%d)\n", i+1, item.Error, item.Count)
		}
		fmt.Printf("\n")
	}
}

func topProfileKeys(values map[string]profileSummary, limit int) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool {
		left := values[keys[i]]
		right := values[keys[j]]
		if left.Requests == right.Requests {
			return keys[i] < keys[j]
		}
		return left.Requests > right.Requests
	})
	if len(keys) > limit {
		return keys[:limit]
	}
	return keys
}

func writeReport(path string, report summary) error {
	blob, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, blob, 0o644)
}

func envOr(key, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}

func envInt(key string, fallback int) int {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	var parsed int
	if _, err := fmt.Sscanf(value, "%d", &parsed); err != nil {
		return fallback
	}
	return parsed
}

func envInt64(key string, fallback int64) int64 {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	var parsed int64
	if _, err := fmt.Sscanf(value, "%d", &parsed); err != nil {
		return fallback
	}
	return parsed
}

func envDuration(key string, fallback time.Duration) time.Duration {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func envBool(key string, fallback bool) bool {
	value := strings.TrimSpace(strings.ToLower(os.Getenv(key)))
	if value == "" {
		return fallback
	}
	switch value {
	case "1", "true", "t", "yes", "y", "on":
		return true
	case "0", "false", "f", "no", "n", "off":
		return false
	default:
		return fallback
	}
}
