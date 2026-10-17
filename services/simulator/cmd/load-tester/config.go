package main

import (
	"errors"
	"flag"
	"net"
	"net/http"
	"strings"
	"time"

	sessionconfig "github.com/dills122/reef/services/simulator/internal/config"
	"github.com/dills122/reef/services/simulator/internal/strategy"
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
	APIBearerToken      string `json:"-"`
	AdminAPIBearerToken string `json:"-"`
	CommandClockStart   string
	CommandClockStep    time.Duration
}

type Action string

const (
	ActionSubmit Action = "submit"
	ActionModify Action = "modify"
	ActionCancel Action = "cancel"
)

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

func validateStreamTransportActionSupport(cfg Config) error {
	if strings.ToLower(strings.TrimSpace(cfg.Transport)) != transportStream || !streamTransportMayUseLifecycleActions(cfg) {
		return nil
	}
	return errors.New("transport=stream currently supports submit orders only; set submit-pct=100, modify-pct=0, and cancel-pct=0 or use transport=http until stream modify/cancel envelopes are supported")
}

func streamTransportMayUseLifecycleActions(cfg Config) bool {
	if cfg.Mode == "capacity-baseline" {
		return true
	}
	if cfg.ModifyPct > 0 || cfg.CancelPct > 0 {
		return true
	}
	if cfg.ActionMixOverride || !cfg.HasSessionConfig {
		return false
	}
	if len(cfg.SessionActors) == 0 {
		return true
	}
	totalWeight := 0
	for _, actor := range cfg.SessionActors {
		totalWeight += actor.Weight
		if actor.Weight <= 0 {
			continue
		}
		if actorCanUseLifecycleActions(actor, cfg) {
			return true
		}
	}
	return totalWeight <= 0
}

func actorCanUseLifecycleActions(actor sessionconfig.Actor, cfg Config) bool {
	if mix, ok := strategy.ResolveActionMix(&actor, cfg.StrategyProfiles); ok {
		return mix.ModifyPct > 0 || mix.CancelPct > 0
	}
	switch normalizeProfile(actor.ActorType) {
	case profileMarketMaker, profileInstitutional, profileRetail, profileNoise:
		return true
	default:
		return false
	}
}

var defaultInvalidIntentRejectCodes = []string{"INVALID_STATE", "NOT_FOUND", "SELF_TRADE_PREVENTION", "VALIDATION_ERROR"}

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
	flag.StringVar(&cfg.APIBearerToken, "api-bearer-token", cfg.APIBearerToken, "bearer token used for /api/v1 static-token auth")
	flag.StringVar(&cfg.AdminAPIBearerToken, "admin-api-bearer-token", cfg.AdminAPIBearerToken, "bearer token used for admin gateway seed routes")
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
	cfg.Transport = strings.ToLower(strings.TrimSpace(cfg.Transport))
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
	if err := validateStreamTransportActionSupport(cfg); err != nil {
		return cfg, err
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
		HTTPMaxIdleConns:    envInt("REEF_HTTP_MAX_IDLE_CONNS", 4096),
		HTTPIdleConnsHost:   envInt("REEF_HTTP_MAX_IDLE_CONNS_PER_HOST", 2048),
		HTTPMaxConnsHost:    envInt("REEF_HTTP_MAX_CONNS_PER_HOST", 0),
		HTTPIdleConnTimeout: envDuration("REEF_HTTP_IDLE_CONN_TIMEOUT", 90*time.Second),
		UseApiV1:            envBool("REEF_USE_API_V1", true),
		LegacyInternalRoute: envBool("REEF_LEGACY_INTERNAL_ROUTE", false),
		ClientIDPrefix:      envOr("REEF_CLIENT_ID_PREFIX", "sim-client"),
		APIBearerToken:      envOr("REEF_API_BEARER_TOKEN", ""),
		AdminAPIBearerToken: envOr("REEF_ADMIN_API_BEARER_TOKEN", envOr("ADMIN_API_TOKEN", "")),
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
	if explicit["admin-api-bearer-token"] {
		cfg.AdminAPIBearerToken = parsed.AdminAPIBearerToken
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
