package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
	reporting "github.com/dills122/reef/services/simulator/internal/report"
)

type Config struct {
	SessionConfigPath   string
	SessionName         string
	ScenarioRunID       string
	RunID               string
	RunKind             string
	ScenarioID          string
	Seed                int64
	HasSessionConfig    bool
	SideBiasBuyPct      int
	SessionActors       []sessionconfig.Actor
	MarketEquities      []sessionconfig.Equity
	StrategyProfiles    map[string]sessionconfig.StrategyProfile
	Faults              []sessionconfig.FaultRule
	BaseURL             string
	Transport           string
	StreamAddress       string
	Duration            time.Duration
	Workers             int
	RatePerSecond       int
	RateSchedule        string
	RateQueueDepth      int
	RequestTimeout      time.Duration
	SubmitPct           int
	ModifyPct           int
	CancelPct           int
	ActionMixOverride   bool
	InstrumentID        string
	InstrumentSymbol    string
	ParticipantID       string
	ParticipantName     string
	AccountID           string
	QuantityMin         int
	QuantityMax         int
	PriceMin            int64
	PriceMax            int64
	TraceCheckLimit     int
	StrictMinLiveOrders int
	ReportOut           string
	Mode                string
	Tail                bool
	TailInterval        time.Duration
	TailLines           int
	ProfileMixMM        int
	ProfileMixInst      int
	ProfileMixRetail    int
	ProfileMixNoise     int
	PrettySummary       bool
	HTTPMaxIdleConns    int
	HTTPIdleConnsHost   int
	HTTPMaxConnsHost    int
	HTTPIdleConnTimeout time.Duration
	UseApiV1            bool
	LegacyInternalRoute bool
	ClientIDPrefix      string
	CommandClockStart   string
	CommandClockStep    time.Duration
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
	Throughput             throughputSummary         `json:"throughput"`
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
	Quality                qualitySummary            `json:"quality"`
	LatencyMs              latencySummary            `json:"latencyMs"`
	TraceChecks            traceChecks               `json:"traceChecks"`
	LoadSchedule           loadScheduleSummary       `json:"loadSchedule"`
}

type loadScheduleSummary struct {
	Mode                  string  `json:"mode"`
	TargetRatePerSecond   int     `json:"targetRatePerSecond"`
	TargetRequests        int64   `json:"targetRequests"`
	Scheduled             int64   `json:"scheduled"`
	Enqueued              int64   `json:"enqueued"`
	Dropped               int64   `json:"dropped"`
	Completed             int64   `json:"completed"`
	ScheduleDeficit       int64   `json:"scheduleDeficit"`
	CompletionDeficit     int64   `json:"completionDeficit"`
	EnqueuedPerSecond     float64 `json:"enqueuedPerSecond"`
	CompletedPerSecond    float64 `json:"completedPerSecond"`
	CompletionToTargetPct float64 `json:"completionToTargetPct"`
}

type throughputSummary = reporting.ThroughputSummary

type loadScheduleCounters struct {
	scheduled int64
	enqueued  int64
	dropped   int64
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

type latencySummary = reporting.LatencySummary
type errorSummary = reporting.ErrorSummary
type rejectTaxonomySummary = reporting.RejectTaxonomySummary
type qualitySummary = reporting.QualitySummary

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
	orders          []trackedOrder
	rejectStreak    int
	submitOnlyTicks int
}

type trackedOrder struct {
	OrderID      string
	InstrumentID string
}

const (
	profileMarketMaker   = "market-maker"
	profileInstitutional = "institutional"
	profileRetail        = "retail"
	profileNoise         = "noise"
)

const (
	transportHTTP   = "http"
	transportStream = "stream"
)

func validTransport(transport string) bool {
	switch strings.ToLower(strings.TrimSpace(transport)) {
	case transportHTTP, transportStream:
		return true
	default:
		return false
	}
}

var defaultInvalidIntentRejectCodes = []string{"INVALID_STATE", "NOT_FOUND", "VALIDATION_ERROR"}

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

type boundaryErrorResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func main() {
	cfg, err := parseConfig()
	if err != nil {
		fmt.Fprintf(os.Stderr, "config error: %v\n", err)
		os.Exit(1)
	}

	sessionCreatedAt := time.Now().UTC()
	sessionID := fmt.Sprintf("load-%d", sessionCreatedAt.UnixNano())
	if cfg.RunID == "" {
		cfg.RunID = sessionID
	}
	client := buildHTTPClient(cfg)
	defer client.CloseIdleConnections()

	if err := seedReferenceData(client, cfg); err != nil {
		fmt.Fprintf(os.Stderr, "reference data seed failed: %v\n", err)
		os.Exit(1)
	}

	started := time.Now().UTC()
	ctx, cancel := context.WithTimeout(context.Background(), cfg.Duration)
	defer cancel()

	results := make(chan requestResult, cfg.Workers*8)
	var counter int64
	traceSeen := sync.Map{}
	var wg sync.WaitGroup
	rateCh := make(chan struct{}, rateChannelDepth(cfg))
	rateCounters := &loadScheduleCounters{}
	if cfg.RatePerSecond > 0 {
		go tokenFeeder(ctx, cfg.RatePerSecond, cfg.RateSchedule, rateCh, rateCounters)
	}
	if cfg.Tail {
		go tailLoop(ctx, client, cfg)
	}

	for workerID := 0; workerID < cfg.Workers; workerID++ {
		wg.Add(1)
		profile := profileForWorker(workerID, cfg.Workers, cfg)
		go func(id int, workerProfile string) {
			defer wg.Done()
			runWorker(ctx, client, cfg, sessionID, id, workerProfile, &counter, rateCh, results, &traceSeen)
		}(workerID, profile)
	}

	go func() {
		wg.Wait()
		close(results)
	}()

	accumulator := newSummaryAccumulator(1024)
	completed := 0
	for result := range results {
		accumulator.add(result)
		completed++
	}

	finished := time.Now().UTC()
	report := accumulator.build(sessionID, started, finished, cfg, rateCounters.summary(cfg, completed, finished.Sub(started).Seconds()))
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
	flag.StringVar(&cfg.Transport, "transport", cfg.Transport, "command submit transport: http or stream")
	flag.StringVar(&cfg.StreamAddress, "stream-address", cfg.StreamAddress, "stream ingress host:port when transport=stream")
	flag.DurationVar(&cfg.Duration, "duration", cfg.Duration, "test duration")
	flag.IntVar(&cfg.Workers, "workers", cfg.Workers, "concurrent workers")
	flag.Int64Var(&cfg.Seed, "seed", cfg.Seed, "deterministic random seed")
	flag.IntVar(&cfg.RatePerSecond, "rate", cfg.RatePerSecond, "global request rate per second (0 = unthrottled)")
	flag.StringVar(&cfg.RateSchedule, "rate-schedule", cfg.RateSchedule, "rate scheduler: drop or precise")
	flag.IntVar(&cfg.RateQueueDepth, "rate-queue-depth", cfg.RateQueueDepth, "rate-token queue depth (0 = scheduler default)")
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
	flag.IntVar(&cfg.StrictMinLiveOrders, "strict-min-live-orders", cfg.StrictMinLiveOrders, "minimum local live-order depth before modify/cancel in strict modes")
	flag.StringVar(&cfg.ReportOut, "report-out", cfg.ReportOut, "optional json report output path")
	flag.StringVar(&cfg.Mode, "mode", cfg.Mode, "traffic mode: chaos, strict-lifecycle, or capacity-baseline")
	flag.StringVar(&cfg.RunID, "run-id", cfg.RunID, "run/session id stamped into command payloads")
	flag.StringVar(&cfg.RunKind, "run-kind", cfg.RunKind, "run kind stamped into command payloads")
	flag.StringVar(&cfg.ScenarioID, "scenario-id", cfg.ScenarioID, "scenario id stamped into command payloads")
	flag.BoolVar(&cfg.Tail, "tail", cfg.Tail, "stream new trades/events during the run")
	flag.DurationVar(&cfg.TailInterval, "tail-interval", cfg.TailInterval, "tail poll interval")
	flag.IntVar(&cfg.TailLines, "tail-lines", cfg.TailLines, "max trade/event rows per tail poll")
	flag.IntVar(&cfg.ProfileMixMM, "profile-mm-pct", cfg.ProfileMixMM, "market-maker worker percentage")
	flag.IntVar(&cfg.ProfileMixInst, "profile-inst-pct", cfg.ProfileMixInst, "institutional worker percentage")
	flag.IntVar(&cfg.ProfileMixRetail, "profile-retail-pct", cfg.ProfileMixRetail, "retail worker percentage")
	flag.IntVar(&cfg.ProfileMixNoise, "profile-noise-pct", cfg.ProfileMixNoise, "noise worker percentage")
	flag.BoolVar(&cfg.PrettySummary, "pretty-summary", cfg.PrettySummary, "print a human-readable console summary (default prints JSON)")
	flag.IntVar(&cfg.HTTPMaxIdleConns, "http-max-idle-conns", cfg.HTTPMaxIdleConns, "http client transport max idle connections")
	flag.IntVar(&cfg.HTTPIdleConnsHost, "http-max-idle-conns-per-host", cfg.HTTPIdleConnsHost, "http client transport max idle connections per host")
	flag.IntVar(&cfg.HTTPMaxConnsHost, "http-max-conns-per-host", cfg.HTTPMaxConnsHost, "http client transport max total connections per host (0 = no transport-side limit)")
	flag.DurationVar(&cfg.HTTPIdleConnTimeout, "http-idle-conn-timeout", cfg.HTTPIdleConnTimeout, "http client idle connection timeout")
	flag.BoolVar(&cfg.UseApiV1, "use-api-v1", cfg.UseApiV1, "submit/modify/cancel via /api/v1 boundary routes")
	flag.BoolVar(&cfg.LegacyInternalRoute, "legacy-internal-route", cfg.LegacyInternalRoute, "send internal marker header when use-api-v1=false")
	flag.StringVar(&cfg.ClientIDPrefix, "client-id-prefix", cfg.ClientIDPrefix, "X-Client-Id prefix used for /api/v1 traffic")
	flag.StringVar(&cfg.CommandClockStart, "command-clock-start", cfg.CommandClockStart, "optional RFC3339 start time for deterministic command occurredAt values")
	flag.DurationVar(&cfg.CommandClockStep, "command-clock-step", cfg.CommandClockStep, "deterministic command clock step")
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
	if !validTransport(cfg.Transport) {
		return cfg, errors.New("transport must be http or stream")
	}
	if cfg.Transport == transportStream && strings.TrimSpace(cfg.StreamAddress) == "" {
		return cfg, errors.New("stream-address must be non-empty when transport=stream")
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
	if cfg.RateQueueDepth < 0 {
		return cfg, errors.New("rate-queue-depth must be >= 0")
	}
	if !isValidRateSchedule(cfg.RateSchedule) {
		return cfg, errors.New("rate-schedule must be drop or precise")
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
	if cfg.StrictMinLiveOrders <= 0 {
		return cfg, errors.New("strict-min-live-orders must be > 0")
	}
	if cfg.HTTPMaxIdleConns < 0 {
		return cfg, errors.New("http-max-idle-conns must be >= 0")
	}
	if cfg.HTTPIdleConnsHost < 0 {
		return cfg, errors.New("http-max-idle-conns-per-host must be >= 0")
	}
	if cfg.HTTPMaxConnsHost < 0 {
		return cfg, errors.New("http-max-conns-per-host must be >= 0")
	}
	if cfg.HTTPIdleConnTimeout <= 0 {
		return cfg, errors.New("http-idle-conn-timeout must be > 0")
	}
	if cfg.UseApiV1 && strings.TrimSpace(cfg.ClientIDPrefix) == "" {
		return cfg, errors.New("client-id-prefix must be non-empty when use-api-v1=true")
	}
	if cfg.CommandClockStart != "" {
		if _, err := time.Parse(time.RFC3339, cfg.CommandClockStart); err != nil {
			return cfg, errors.New("command-clock-start must be RFC3339 when provided")
		}
		if cfg.CommandClockStep <= 0 {
			return cfg, errors.New("command-clock-step must be > 0 when command-clock-start is provided")
		}
	}
	return cfg, nil
}

func defaultConfigFromEnv() Config {
	return Config{
		BaseURL:             envOr("REEF_BASE_URL", "http://localhost:8080"),
		Transport:           envOr("REEF_TRANSPORT", transportHTTP),
		StreamAddress:       envOr("REEF_STREAM_ADDRESS", "127.0.0.1:8090"),
		Duration:            envDuration("REEF_DURATION", 30*time.Second),
		Workers:             envInt("REEF_WORKERS", 8),
		Seed:                envInt64("REEF_SEED", 0),
		RatePerSecond:       envInt("REEF_RATE", 0),
		RateSchedule:        envOr("REEF_RATE_SCHEDULE", rateScheduleDrop),
		RateQueueDepth:      envInt("REEF_RATE_QUEUE_DEPTH", 0),
		RequestTimeout:      envDuration("REEF_TIMEOUT", 5*time.Second),
		SubmitPct:           envInt("REEF_SUBMIT_PCT", 60),
		ModifyPct:           envInt("REEF_MODIFY_PCT", 25),
		CancelPct:           envInt("REEF_CANCEL_PCT", 15),
		InstrumentID:        envOr("REEF_INSTRUMENT_ID", "AAPL"),
		InstrumentSymbol:    envOr("REEF_INSTRUMENT_SYMBOL", "AAPL"),
		ParticipantID:       envOr("REEF_PARTICIPANT_ID", "participant-1"),
		ParticipantName:     envOr("REEF_PARTICIPANT_NAME", "Participant 1"),
		AccountID:           envOr("REEF_ACCOUNT_ID", "account-1"),
		QuantityMin:         envInt("REEF_QTY_MIN", 10),
		QuantityMax:         envInt("REEF_QTY_MAX", 1000),
		PriceMin:            envInt64("REEF_PRICE_MIN", 149_000_000_000),
		PriceMax:            envInt64("REEF_PRICE_MAX", 151_000_000_000),
		TraceCheckLimit:     envInt("REEF_TRACE_CHECK_LIMIT", 50),
		StrictMinLiveOrders: envInt("REEF_STRICT_MIN_LIVE_ORDERS", 4),
		ReportOut:           envOr("REEF_REPORT_OUT", ""),
		Mode:                envOr("REEF_MODE", "chaos"),
		RunID:               envOr("REEF_RUN_ID", ""),
		RunKind:             envOr("REEF_RUN_KIND", "stress"),
		ScenarioID:          envOr("REEF_SCENARIO_ID", ""),
		Tail:                envBool("REEF_TAIL", false),
		TailInterval:        envDuration("REEF_TAIL_INTERVAL", 2*time.Second),
		TailLines:           envInt("REEF_TAIL_LINES", 5),
		ProfileMixMM:        envInt("REEF_PROFILE_MM_PCT", 35),
		ProfileMixInst:      envInt("REEF_PROFILE_INST_PCT", 30),
		ProfileMixRetail:    envInt("REEF_PROFILE_RETAIL_PCT", 25),
		ProfileMixNoise:     envInt("REEF_PROFILE_NOISE_PCT", 10),
		PrettySummary:       envBool("REEF_PRETTY_SUMMARY", false),
		HTTPMaxIdleConns:    envInt("REEF_HTTP_MAX_IDLE_CONNS", 2048),
		HTTPIdleConnsHost:   envInt("REEF_HTTP_MAX_IDLE_CONNS_PER_HOST", 512),
		HTTPMaxConnsHost:    envInt("REEF_HTTP_MAX_CONNS_PER_HOST", 0),
		HTTPIdleConnTimeout: envDuration("REEF_HTTP_IDLE_CONN_TIMEOUT", 90*time.Second),
		UseApiV1:            envBool("REEF_USE_API_V1", true),
		LegacyInternalRoute: envBool("REEF_LEGACY_INTERNAL_ROUTE", false),
		ClientIDPrefix:      envOr("REEF_CLIENT_ID_PREFIX", "sim-client"),
		CommandClockStart:   envOr("REEF_COMMAND_CLOCK_START", ""),
		CommandClockStep:    envDuration("REEF_COMMAND_CLOCK_STEP", time.Second),
	}
}

func mergeSessionConfig(defaults Config, session sessionconfig.RuntimeConfig) Config {
	cfg := defaults
	cfg.SessionName = session.SessionName
	cfg.ScenarioRunID = session.ScenarioRunID
	if cfg.RunID == "" {
		cfg.RunID = session.ScenarioRunID
	}
	if cfg.ScenarioID == "" {
		cfg.ScenarioID = session.SessionName
	}
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
	if explicit["transport"] {
		cfg.Transport = parsed.Transport
	}
	if explicit["stream-address"] {
		cfg.StreamAddress = parsed.StreamAddress
	}
	if explicit["duration"] {
		cfg.Duration = parsed.Duration
	}
	if explicit["workers"] {
		cfg.Workers = parsed.Workers
	}
	if explicit["seed"] {
		cfg.Seed = parsed.Seed
	}
	if explicit["rate"] {
		cfg.RatePerSecond = parsed.RatePerSecond
	}
	if explicit["rate-schedule"] {
		cfg.RateSchedule = parsed.RateSchedule
	}
	if explicit["rate-queue-depth"] {
		cfg.RateQueueDepth = parsed.RateQueueDepth
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
	if explicit["submit-pct"] || explicit["modify-pct"] || explicit["cancel-pct"] {
		cfg.ActionMixOverride = true
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
	if explicit["strict-min-live-orders"] {
		cfg.StrictMinLiveOrders = parsed.StrictMinLiveOrders
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
	if explicit["http-max-idle-conns"] {
		cfg.HTTPMaxIdleConns = parsed.HTTPMaxIdleConns
	}
	if explicit["http-max-idle-conns-per-host"] {
		cfg.HTTPIdleConnsHost = parsed.HTTPIdleConnsHost
	}
	if explicit["http-max-conns-per-host"] {
		cfg.HTTPMaxConnsHost = parsed.HTTPMaxConnsHost
	}
	if explicit["http-idle-conn-timeout"] {
		cfg.HTTPIdleConnTimeout = parsed.HTTPIdleConnTimeout
	}
	if explicit["use-api-v1"] {
		cfg.UseApiV1 = parsed.UseApiV1
	}
	if explicit["legacy-internal-route"] {
		cfg.LegacyInternalRoute = parsed.LegacyInternalRoute
	}
	if explicit["client-id-prefix"] {
		cfg.ClientIDPrefix = parsed.ClientIDPrefix
	}
	if explicit["command-clock-start"] {
		cfg.CommandClockStart = parsed.CommandClockStart
	}
	if explicit["command-clock-step"] {
		cfg.CommandClockStep = parsed.CommandClockStep
	}
}

func buildHTTPClient(cfg Config) *http.Client {
	dialer := &net.Dialer{
		Timeout:   cfg.RequestTimeout,
		KeepAlive: 30 * time.Second,
	}
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           dialer.DialContext,
		ForceAttemptHTTP2:     false,
		MaxIdleConns:          cfg.HTTPMaxIdleConns,
		MaxIdleConnsPerHost:   cfg.HTTPIdleConnsHost,
		MaxConnsPerHost:       cfg.HTTPMaxConnsHost,
		IdleConnTimeout:       cfg.HTTPIdleConnTimeout,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}
	return &http.Client{
		Timeout:   cfg.RequestTimeout,
		Transport: transport,
	}
}

func runWorker(
	ctx context.Context,
	client *http.Client,
	cfg Config,
	sessionID string,
	workerID int,
	profile string,
	counter *int64,
	rateCh <-chan struct{},
	results chan<- requestResult,
	traceSeen *sync.Map,
) {
	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(workerID)*7919))
	if cfg.Seed != 0 {
		rng = rand.New(rand.NewSource(cfg.Seed + int64(workerID)*7919))
	}
	var stream *streamSubmitter
	if cfg.Transport == transportStream {
		stream = &streamSubmitter{
			address: cfg.StreamAddress,
			timeout: cfg.RequestTimeout,
		}
		defer stream.close()
	}
	state := workerState{orders: make([]trackedOrder, 0, 128)}
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
		action := chooseActionForActor(rng, cfg, hasActionableOrders(cfg, state), effectiveProfile, actor)
		if state.submitOnlyTicks > 0 {
			action = ActionSubmit
			state.submitOnlyTicks--
		} else if action != ActionSubmit && !shouldAllowLifecycleAction(rng, cfg, state) {
			action = ActionSubmit
		}
		reqID := atomic.AddInt64(counter, 1)
		traceID := fmt.Sprintf("%s-trace-%d-%d", sessionID, workerID, reqID)
		commandID := fmt.Sprintf("%s-cmd-%d-%d", sessionID, workerID, reqID)
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
			orderID := fmt.Sprintf("%s-ord-%d-%d", sessionID, workerID, reqID)
			result.OrderID = orderID
			instrumentID := cfg.InstrumentID
			instrument := chooseInstrumentForActor(rng, cfg, actor)
			if instrument != nil {
				instrumentID = instrument.InstrumentID
			}
			if shouldInjectFault(rng, cfg, "reject_submit", instrumentID) {
				result.StatusCode = 200
				result.ErrorText = "rejected:INJECTED_FAULT"
				result.RejectCode = "INJECTED_FAULT"
				result.RejectReason = "deterministic fault injection"
				results <- result
				continue
			}
			payload := buildCommandPayload(cfg, sessionID, commandID, traceID, actorID, actorType, persona, strategyID, reqID)
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
			status, body, err := submitCommand(client, stream, cfg, workerID, commandID, traceID, payload, ActionSubmit)
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.orders = append(state.orders, trackedOrder{OrderID: orderID, InstrumentID: instrumentID})
				state.orders = compactTrackedOrders(state.orders, cfg)
				state.rejectStreak = 0
				traceSeen.Store(traceID, struct{}{})
			} else if isTerminalOrderRejection(result.RejectCode) {
				updateRecoveryState(&state, cfg)
			}
		case ActionModify:
			if len(state.orders) == 0 {
				continue
			}
			order := pickOrder(rng, state.orders, cfg.Mode)
			result.OrderID = order.OrderID
			if shouldInjectFault(rng, cfg, "reject_modify", order.InstrumentID) {
				result.StatusCode = 200
				result.ErrorText = "rejected:INJECTED_FAULT"
				result.RejectCode = "INJECTED_FAULT"
				result.RejectReason = "deterministic fault injection"
				results <- result
				continue
			}
			payload := buildCommandPayload(cfg, sessionID, commandID, traceID, actorID, actorType, persona, strategyID, reqID)
			payload["orderId"] = order.OrderID
			payload["instrumentId"] = order.InstrumentID
			payload["quantityUnits"] = fmt.Sprintf("%d", profileQuantity(rng, cfg, profile))
			payload["limitPrice"] = fmt.Sprintf("%d", profilePrice(rng, cfg, effectiveProfile, nil))
			status, body, err := doPOST(
				client,
				cfg.BaseURL+commandRoute(cfg, ActionModify),
				payload,
				commandHeaders(cfg, workerID, commandID, traceID),
			)
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.rejectStreak = 0
				traceSeen.Store(traceID, struct{}{})
			} else if shouldPruneTerminalOrder(cfg.Mode) && isTerminalOrderRejection(result.RejectCode) {
				state.orders = removeOrder(state.orders, order.OrderID)
				state.orders = compactTrackedOrders(state.orders, cfg)
				updateRecoveryState(&state, cfg)
			}
		case ActionCancel:
			if len(state.orders) == 0 {
				continue
			}
			idx := pickOrderIndex(rng, state.orders, cfg.Mode)
			order := state.orders[idx]
			result.OrderID = order.OrderID
			if shouldInjectFault(rng, cfg, "reject_cancel", order.InstrumentID) {
				result.StatusCode = 200
				result.ErrorText = "rejected:INJECTED_FAULT"
				result.RejectCode = "INJECTED_FAULT"
				result.RejectReason = "deterministic fault injection"
				results <- result
				continue
			}
			payload := buildCommandPayload(cfg, sessionID, commandID, traceID, actorID, actorType, persona, strategyID, reqID)
			payload["orderId"] = order.OrderID
			payload["instrumentId"] = order.InstrumentID
			payload["reason"] = "load test"
			status, body, err := doPOST(
				client,
				cfg.BaseURL+commandRoute(cfg, ActionCancel),
				payload,
				commandHeaders(cfg, workerID, commandID, traceID),
			)
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.orders = append(state.orders[:idx], state.orders[idx+1:]...)
				state.rejectStreak = 0
				traceSeen.Store(traceID, struct{}{})
			} else if shouldPruneTerminalOrder(cfg.Mode) && isTerminalOrderRejection(result.RejectCode) {
				state.orders = removeOrder(state.orders, order.OrderID)
				state.orders = compactTrackedOrders(state.orders, cfg)
				updateRecoveryState(&state, cfg)
			}
		}
		results <- result
	}
}

func submitCommand(
	client *http.Client,
	stream *streamSubmitter,
	cfg Config,
	workerID int,
	commandID string,
	traceID string,
	payload map[string]string,
	action Action,
) (int, []byte, error) {
	if cfg.Transport == transportStream && action == ActionSubmit {
		return stream.submit(payload)
	}
	return doPOST(
		client,
		cfg.BaseURL+commandRoute(cfg, action),
		payload,
		commandHeaders(cfg, workerID, commandID, traceID),
	)
}

type streamSubmitter struct {
	address string
	timeout time.Duration
	conn    net.Conn
	reader  *bufio.Reader
	writer  *bufio.Writer
}

func (s *streamSubmitter) submit(payload map[string]string) (int, []byte, error) {
	if err := s.ensureConnected(); err != nil {
		return 0, nil, err
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, err
	}
	if err := s.conn.SetDeadline(time.Now().Add(s.timeout)); err != nil {
		s.close()
		return 0, nil, err
	}
	if _, err := s.writer.Write(body); err != nil {
		s.close()
		return 0, nil, err
	}
	if err := s.writer.WriteByte('\n'); err != nil {
		s.close()
		return 0, nil, err
	}
	if err := s.writer.Flush(); err != nil {
		s.close()
		return 0, nil, err
	}
	line, err := s.reader.ReadString('\n')
	if err != nil {
		s.close()
		return 0, nil, err
	}
	line = strings.TrimSpace(line)
	statusText, bodyText, hasBody := strings.Cut(line, "\t")
	status, err := strconv.Atoi(statusText)
	if err != nil {
		return 0, nil, err
	}
	if hasBody {
		return status, []byte(bodyText), nil
	}
	return status, nil, nil
}

func (s *streamSubmitter) ensureConnected() error {
	if s.conn != nil {
		return nil
	}
	conn, err := net.DialTimeout("tcp", s.address, s.timeout)
	if err != nil {
		return err
	}
	s.conn = conn
	s.reader = bufio.NewReaderSize(conn, 64*1024)
	s.writer = bufio.NewWriterSize(conn, 64*1024)
	return nil
}

func (s *streamSubmitter) close() {
	if s.conn != nil {
		_ = s.conn.Close()
	}
	s.conn = nil
	s.reader = nil
	s.writer = nil
}

func fillResult(result *requestResult, status int, body []byte, err error, start time.Time) {
	result.StatusCode = status
	result.Latency = time.Since(start)
	if err != nil {
		result.ErrorText = err.Error()
		return
	}
	if status < 200 || status >= 300 {
		var boundaryError boundaryErrorResponse
		if jsonErr := json.Unmarshal(body, &boundaryError); jsonErr == nil && boundaryError.Code != "" {
			result.RejectCode = boundaryError.Code
			result.RejectReason = boundaryError.Message
			result.ErrorText = fmt.Sprintf("http_%d:%s", status, boundaryError.Code)
		} else {
			result.ErrorText = fmt.Sprintf("http_%d", status)
		}
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

func (c *loadScheduleCounters) summary(cfg Config, completed int, durationSeconds float64) loadScheduleSummary {
	targetRequests := int64(cfg.Duration.Seconds() * float64(cfg.RatePerSecond))
	if cfg.RatePerSecond <= 0 {
		targetRequests = int64(completed)
	}
	scheduled := int64(0)
	enqueued := int64(completed)
	dropped := int64(0)
	if c != nil {
		scheduled = atomic.LoadInt64(&c.scheduled)
		enqueued = atomic.LoadInt64(&c.enqueued)
		dropped = atomic.LoadInt64(&c.dropped)
	}
	if cfg.RatePerSecond <= 0 {
		scheduled = int64(completed)
		enqueued = int64(completed)
	}
	completedCount := int64(completed)
	scheduleDeficit := targetRequests - scheduled
	if scheduleDeficit < 0 {
		scheduleDeficit = 0
	}
	completionDeficit := targetRequests - completedCount
	if completionDeficit < 0 {
		completionDeficit = 0
	}
	out := loadScheduleSummary{
		Mode:                cfg.RateSchedule,
		TargetRatePerSecond: cfg.RatePerSecond,
		TargetRequests:      targetRequests,
		Scheduled:           scheduled,
		Enqueued:            enqueued,
		Dropped:             dropped,
		Completed:           completedCount,
		ScheduleDeficit:     scheduleDeficit,
		CompletionDeficit:   completionDeficit,
	}
	if durationSeconds > 0 {
		out.EnqueuedPerSecond = float64(enqueued) / durationSeconds
		out.CompletedPerSecond = float64(completedCount) / durationSeconds
	}
	if targetRequests > 0 {
		out.CompletionToTargetPct = (float64(completedCount) / float64(targetRequests)) * 100
	}
	return out
}

type summaryAccumulator struct {
	report                  summary
	errorCounts             map[string]int64
	rejectReasons           map[string]int64
	rejectCodes             map[string]int64
	totalRejects            int64
	allLatencies            []float64
	actionLatencies         map[Action][]float64
	profileLatencies        map[string][]float64
	actorLatencies          map[string][]float64
	personaLatencies        map[string][]float64
	strategyLatencies       map[string][]float64
	profileActionLatencies  map[string]map[Action][]float64
	actorActionLatencies    map[string]map[Action][]float64
	personaActionLatencies  map[string]map[Action][]float64
	strategyActionLatencies map[string]map[Action][]float64
}

func newSummaryAccumulator(initialCapacity int) *summaryAccumulator {
	if initialCapacity < 0 {
		initialCapacity = 0
	}
	return &summaryAccumulator{
		report: summary{
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
		},
		errorCounts:   make(map[string]int64),
		rejectReasons: make(map[string]int64),
		rejectCodes:   make(map[string]int64),
		allLatencies:  make([]float64, 0, initialCapacity),
		actionLatencies: map[Action][]float64{
			ActionSubmit: {},
			ActionModify: {},
			ActionCancel: {},
		},
		profileLatencies:        map[string][]float64{},
		actorLatencies:          map[string][]float64{},
		personaLatencies:        map[string][]float64{},
		strategyLatencies:       map[string][]float64{},
		profileActionLatencies:  map[string]map[Action][]float64{},
		actorActionLatencies:    map[string]map[Action][]float64{},
		personaActionLatencies:  map[string]map[Action][]float64{},
		strategyActionLatencies: map[string]map[Action][]float64{},
	}
}

func (a *summaryAccumulator) add(r requestResult) {
	latencyMs := r.Latency.Seconds() * 1000
	a.report.TotalRequests++
	a.report.StatusCodes[r.StatusCode]++
	a.allLatencies = append(a.allLatencies, latencyMs)
	a.actionLatencies[r.Action] = append(a.actionLatencies[r.Action], latencyMs)
	a.profileLatencies[r.Profile] = append(a.profileLatencies[r.Profile], latencyMs)
	appendActionLatency(a.profileActionLatencies, r.Profile, r.Action, latencyMs)
	if r.ActorID != "" {
		a.actorLatencies[r.ActorID] = append(a.actorLatencies[r.ActorID], latencyMs)
		appendActionLatency(a.actorActionLatencies, r.ActorID, r.Action, latencyMs)
	}
	if r.Persona != "" {
		a.personaLatencies[r.Persona] = append(a.personaLatencies[r.Persona], latencyMs)
		appendActionLatency(a.personaActionLatencies, r.Persona, r.Action, latencyMs)
	}
	if r.StrategyID != "" {
		a.strategyLatencies[r.StrategyID] = append(a.strategyLatencies[r.StrategyID], latencyMs)
		appendActionLatency(a.strategyActionLatencies, r.StrategyID, r.Action, latencyMs)
	}

	current := a.report.ByAction[r.Action]
	current.Requests++
	if r.Success {
		a.report.TotalSuccess++
		current.Success++
	} else {
		a.report.TotalFailures++
		current.Failures++
		if r.ErrorText != "" {
			a.errorCounts[r.ErrorText]++
		}
		if r.RejectCode != "" {
			a.rejectCodes[r.RejectCode]++
			a.totalRejects++
			key := r.RejectCode
			if r.RejectReason != "" {
				key = key + ": " + r.RejectReason
			}
			a.rejectReasons[key]++
		}
	}
	a.report.ByAction[r.Action] = current

	pCurrent, ok := a.report.ByProfile[r.Profile]
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
	a.report.ByProfile[r.Profile] = pCurrent
	updateDimensionSummary(a.report.ByActor, r.ActorID, r.Action, r.Success)
	updateDimensionSummary(a.report.ByPersona, r.Persona, r.Action, r.Success)
	updateDimensionSummary(a.report.ByStrategy, r.StrategyID, r.Action, r.Success)
}

func (a *summaryAccumulator) build(sessionID string, started, finished time.Time, cfg Config, loadSchedule loadScheduleSummary) summary {
	report := a.report
	report.SessionID = sessionID
	report.StartedAt = started
	report.FinishedAt = finished
	report.DurationSeconds = finished.Sub(started).Seconds()
	report.Config = cfg
	report.LoadSchedule = loadSchedule
	report.ThroughputRPS = float64(report.TotalRequests) / report.DurationSeconds
	report.AcceptedBusinessOpsRPS = float64(report.TotalSuccess) / report.DurationSeconds
	report.Throughput = reporting.ComputeThroughput(report.TotalRequests, report.TotalSuccess, 0, report.DurationSeconds)
	report.LatencyMs = computeLatency(a.allLatencies)
	for action, values := range a.actionLatencies {
		current := report.ByAction[action]
		current.Latency = computeLatency(values)
		report.ByAction[action] = current
	}
	for profile, values := range a.profileLatencies {
		pCurrent := report.ByProfile[profile]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencyBuckets(&pCurrent, a.profileActionLatencies[profile])
		report.ByProfile[profile] = pCurrent
	}
	for actorID, values := range a.actorLatencies {
		pCurrent := report.ByActor[actorID]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencyBuckets(&pCurrent, a.actorActionLatencies[actorID])
		report.ByActor[actorID] = pCurrent
	}
	for persona, values := range a.personaLatencies {
		pCurrent := report.ByPersona[persona]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencyBuckets(&pCurrent, a.personaActionLatencies[persona])
		report.ByPersona[persona] = pCurrent
	}
	for strategyID, values := range a.strategyLatencies {
		pCurrent := report.ByStrategy[strategyID]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencyBuckets(&pCurrent, a.strategyActionLatencies[strategyID])
		report.ByStrategy[strategyID] = pCurrent
	}
	report.TopErrors = topErrors(a.errorCounts, 8)
	report.RejectReasons = topErrors(a.rejectReasons, 12)
	report.RejectTaxonomy = summarizeRejectTaxonomy(a.rejectCodes, report.TotalFailures, a.totalRejects, 12)
	report.Quality = computeQuality(report.TotalRequests, report.TotalSuccess, report.TotalFailures, a.rejectCodes, defaultInvalidIntentRejectCodes)
	return report
}

func buildSummary(sessionID string, started, finished time.Time, cfg Config, results []requestResult, loadSchedule loadScheduleSummary) summary {
	accumulator := newSummaryAccumulator(len(results))
	for _, result := range results {
		accumulator.add(result)
	}
	return accumulator.build(sessionID, started, finished, cfg, loadSchedule)
}

func summarizeRejectTaxonomy(rejectCodes map[string]int64, totalFailures int64, totalRejects int64, limit int) []rejectTaxonomySummary {
	return reporting.SummarizeRejectTaxonomy(rejectCodes, totalFailures, totalRejects, limit)
}

func computeQuality(totalRequests, totalSuccess, totalFailures int64, rejectCodes map[string]int64, invalidIntentCodes []string) qualitySummary {
	return reporting.ComputeQuality(totalRequests, totalSuccess, totalFailures, rejectCodes, invalidIntentCodes)
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

func appendActionLatency(target map[string]map[Action][]float64, key string, action Action, latencyMs float64) {
	byAction := target[key]
	if byAction == nil {
		byAction = map[Action][]float64{}
		target[key] = byAction
	}
	byAction[action] = append(byAction[action], latencyMs)
}

func applyActionLatencyBuckets(current *profileSummary, buckets map[Action][]float64) {
	for action, actionSummary := range current.ByAction {
		actionSummary.Latency = computeLatency(buckets[action])
		current.ByAction[action] = actionSummary
	}
}

func runTraceChecks(client *http.Client, cfg Config, seen *sync.Map) traceChecks {
	if cfg.TraceCheckLimit <= 0 {
		return traceChecks{Checked: 0, FailedTraceID: make([]string, 0)}
	}
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
	internalHeaders := map[string]string{"X-Reef-Internal-Route": "true"}
	if cfg.HasSessionConfig && len(cfg.MarketEquities) > 0 {
		for _, eq := range cfg.MarketEquities {
			if err := seedPOST(client, cfg.BaseURL+"/reference/instruments", map[string]string{
				"instrumentId": eq.InstrumentID,
				"symbol":       eq.Symbol,
			}, internalHeaders); err != nil {
				return err
			}
		}
	} else {
		if err := seedPOST(client, cfg.BaseURL+"/reference/instruments", map[string]string{
			"instrumentId": cfg.InstrumentID,
			"symbol":       cfg.InstrumentSymbol,
		}, internalHeaders); err != nil {
			return err
		}
	}
	if err := seedPOST(client, cfg.BaseURL+"/reference/participants", map[string]string{
		"participantId": cfg.ParticipantID,
		"name":          cfg.ParticipantName,
	}, internalHeaders); err != nil {
		return err
	}
	if err := seedPOST(client, cfg.BaseURL+"/reference/accounts", map[string]string{
		"accountId":     cfg.AccountID,
		"participantId": cfg.ParticipantID,
	}, internalHeaders); err != nil {
		return err
	}
	if err := seedOrderAuthorization(client, cfg, internalHeaders); err != nil {
		return err
	}
	return nil
}

func seedOrderAuthorization(client *http.Client, cfg Config, internalHeaders map[string]string) error {
	if err := seedPOST(client, cfg.BaseURL+"/auth/roles", map[string]string{
		"roleId":      "order_trader",
		"permissions": "order.submit,order.cancel,order.modify",
	}, internalHeaders); err != nil {
		return err
	}
	for _, actorID := range orderActorIDs(cfg) {
		if err := seedPOST(client, cfg.BaseURL+"/auth/actor-roles", map[string]string{
			"actorId": actorID,
			"roleId":  "order_trader",
		}, internalHeaders); err != nil {
			return err
		}
	}
	return nil
}

func orderActorIDs(cfg Config) []string {
	seen := map[string]struct{}{}
	if cfg.HasSessionConfig {
		for _, actor := range cfg.SessionActors {
			actorID := strings.TrimSpace(actor.ActorID)
			if actorID != "" {
				seen[actorID] = struct{}{}
			}
		}
	}
	if len(seen) == 0 {
		workers := cfg.Workers
		if workers <= 0 {
			workers = 1
		}
		for workerID := 0; workerID < workers; workerID++ {
			seen[fmt.Sprintf("bot-%d", workerID)] = struct{}{}
		}
	}
	out := make([]string, 0, len(seen))
	for actorID := range seen {
		out = append(out, actorID)
	}
	sort.Strings(out)
	return out
}

func seedPOST(client *http.Client, url string, payload map[string]string, headers map[string]string) error {
	status, body, err := doPOST(client, url, payload, headers)
	if err != nil {
		return err
	}
	if status < 200 || status >= 300 {
		return fmt.Errorf("seed POST %s failed (%d): %s", url, status, strings.TrimSpace(string(body)))
	}
	return nil
}

func doPOST(client *http.Client, url string, payload map[string]string, headers map[string]string) (int, []byte, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, err
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(resp.Body)
	return resp.StatusCode, respBody, err
}

func commandRoute(cfg Config, action Action) string {
	if cfg.UseApiV1 {
		switch action {
		case ActionModify:
			return "/api/v1/orders/modify"
		case ActionCancel:
			return "/api/v1/orders/cancel"
		default:
			return "/api/v1/orders/submit"
		}
	}
	switch action {
	case ActionModify:
		return "/orders/modify"
	case ActionCancel:
		return "/orders/cancel"
	default:
		return "/orders/submit"
	}
}

func commandHeaders(cfg Config, workerID int, commandID, traceID string) map[string]string {
	if !cfg.UseApiV1 {
		if cfg.LegacyInternalRoute {
			return map[string]string{
				"X-Reef-Internal-Route": "true",
			}
		}
		return nil
	}
	return map[string]string{
		"X-Client-Id":      fmt.Sprintf("%s-%d", cfg.ClientIDPrefix, workerID),
		"Idempotency-Key":  commandID,
		"X-Correlation-Id": traceID,
	}
}

func buildCommandPayload(cfg Config, sessionID, commandID, traceID, actorID, actorType, persona, strategyID string, reqID int64) map[string]string {
	payload := map[string]string{
		"commandId":      commandID,
		"traceId":        traceID,
		"causationId":    "",
		"correlationId":  traceID,
		"actorId":        actorID,
		"actorType":      actorType,
		"strategyId":     strategyID,
		"runId":          cfg.RunID,
		"runKind":        cfg.RunKind,
		"scenarioId":     cfg.ScenarioID,
		"venueSessionId": commandVenueSessionID(cfg, sessionID),
		"occurredAt":     commandOccurredAt(cfg, reqID),
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

func commandVenueSessionID(cfg Config, sessionID string) string {
	if value := strings.TrimSpace(cfg.SessionName); value != "" {
		return value
	}
	if value := strings.TrimSpace(sessionID); value != "" {
		return value
	}
	if value := strings.TrimSpace(cfg.RunID); value != "" {
		return value
	}
	return "load-session"
}

func commandOccurredAt(cfg Config, reqID int64) string {
	if cfg.CommandClockStart == "" {
		return time.Now().UTC().Format(time.RFC3339)
	}
	start, err := time.Parse(time.RFC3339, cfg.CommandClockStart)
	if err != nil {
		return time.Now().UTC().Format(time.RFC3339)
	}
	step := cfg.CommandClockStep
	if step <= 0 {
		step = time.Second
	}
	offset := reqID - 1
	if offset < 0 {
		offset = 0
	}
	return start.Add(time.Duration(offset) * step).UTC().Format(time.RFC3339)
}

func computeLatency(values []float64) latencySummary {
	return reporting.ComputeLatency(values)
}

func topErrors(m map[string]int64, limit int) []errorSummary {
	return reporting.TopErrors(m, limit)
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
	if report.Config.RatePerSecond > 0 {
		fmt.Printf(
			"  schedule=%s target=%d scheduled=%d enqueued=%d dropped=%d completed=%d completion=%.2f%%\n",
			report.LoadSchedule.Mode,
			report.LoadSchedule.TargetRequests,
			report.LoadSchedule.Scheduled,
			report.LoadSchedule.Enqueued,
			report.LoadSchedule.Dropped,
			report.LoadSchedule.Completed,
			report.LoadSchedule.CompletionToTargetPct,
		)
		if report.LoadSchedule.ScheduleDeficit > 0 || report.LoadSchedule.CompletionDeficit > 0 {
			fmt.Printf("  schedule-deficit=%d completion-deficit=%d\n", report.LoadSchedule.ScheduleDeficit, report.LoadSchedule.CompletionDeficit)
		}
	}
	fmt.Printf("  success-rate=%.2f%%\n", successRate)
	fmt.Printf("  valid-intent-success=%.2f%% invalid-intent=%.2f%% system-failure=%.2f%%\n",
		report.Quality.ValidIntentSuccessRatePct, report.Quality.InvalidIntentRatePct, report.Quality.SystemFailureRatePct)
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
