package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	reporting "github.com/dills122/reef/services/simulator/internal/report"
)

type config struct {
	BaseURL             string        `json:"baseUrl"`
	Duration            time.Duration `json:"duration"`
	Workers             int           `json:"workers"`
	RatePerSecond       int           `json:"ratePerSecond"`
	RateSchedule        string        `json:"rateSchedule"`
	RequestTimeout      time.Duration `json:"requestTimeout"`
	ReportOut           string        `json:"reportOut"`
	PrettySummary       bool          `json:"prettySummary"`
	HTTPMaxIdleConns    int           `json:"httpMaxIdleConns"`
	HTTPIdleConnsHost   int           `json:"httpIdleConnsPerHost"`
	HTTPMaxConnsHost    int           `json:"httpMaxConnsPerHost"`
	HTTPIdleConnTimeout time.Duration `json:"httpIdleConnTimeout"`
	ClientIDPrefix      string        `json:"clientIdPrefix"`
	ActorIDPrefix       string        `json:"actorIdPrefix"`
	RunID               string        `json:"runId"`
	RunKind             string        `json:"runKind"`
	ScenarioID          string        `json:"scenarioId"`
	InstrumentID        string        `json:"instrumentId"`
	ParticipantID       string        `json:"participantId"`
	AccountID           string        `json:"accountId"`
	QuantityUnits       string        `json:"quantityUnits"`
	LimitPrice          string        `json:"limitPrice"`
	OccurredAt          string        `json:"occurredAt"`
}

type requestResult struct {
	Success    bool
	StatusCode int
	Latency    time.Duration
	ErrorText  string
}

type report struct {
	SessionID       string                   `json:"sessionId"`
	StartedAt       time.Time                `json:"startedAt"`
	FinishedAt      time.Time                `json:"finishedAt"`
	DurationSeconds float64                  `json:"durationSeconds"`
	Config          config                   `json:"config"`
	Requests        int64                    `json:"requests"`
	Success         int64                    `json:"success"`
	Failures        int64                    `json:"failures"`
	ThroughputRPS   float64                  `json:"throughputRps"`
	AcceptedRPS     float64                  `json:"acceptedRps"`
	SuccessRatePct  float64                  `json:"successRatePct"`
	LatencyMs       reporting.LatencySummary `json:"latencyMs"`
	StatusCodes     map[int]int64            `json:"statusCodes"`
	TopErrors       []reporting.ErrorSummary `json:"topErrors"`
}

const (
	rateScheduleDrop    = "drop"
	rateSchedulePrecise = "precise"
)

func main() {
	cfg, err := parseConfig(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}

	result := run(cfg)
	if cfg.ReportOut != "" {
		blob, err := json.MarshalIndent(result, "", "  ")
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		if err := os.WriteFile(cfg.ReportOut, blob, 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	printReport(result)
}

func parseConfig(args []string) (config, error) {
	cfg := defaultConfig()
	fs := flag.NewFlagSet("intake-bench", flag.ContinueOnError)
	fs.StringVar(&cfg.BaseURL, "base-url", cfg.BaseURL, "runtime base URL")
	fs.DurationVar(&cfg.Duration, "duration", cfg.Duration, "benchmark duration")
	fs.IntVar(&cfg.Workers, "workers", cfg.Workers, "concurrent workers")
	fs.IntVar(&cfg.RatePerSecond, "rate", cfg.RatePerSecond, "global request rate per second (0 = unthrottled)")
	fs.StringVar(&cfg.RateSchedule, "rate-schedule", cfg.RateSchedule, "rate scheduler: drop or precise")
	fs.DurationVar(&cfg.RequestTimeout, "timeout", cfg.RequestTimeout, "per-request timeout")
	fs.StringVar(&cfg.ReportOut, "report-out", cfg.ReportOut, "optional JSON report path")
	fs.BoolVar(&cfg.PrettySummary, "pretty-summary", cfg.PrettySummary, "print human-readable summary")
	fs.IntVar(&cfg.HTTPMaxIdleConns, "http-max-idle-conns", cfg.HTTPMaxIdleConns, "http client transport max idle connections")
	fs.IntVar(&cfg.HTTPIdleConnsHost, "http-max-idle-conns-per-host", cfg.HTTPIdleConnsHost, "http client transport max idle connections per host")
	fs.IntVar(&cfg.HTTPMaxConnsHost, "http-max-conns-per-host", cfg.HTTPMaxConnsHost, "http client transport max total connections per host (0 = no transport-side limit)")
	fs.DurationVar(&cfg.HTTPIdleConnTimeout, "http-idle-conn-timeout", cfg.HTTPIdleConnTimeout, "http client idle connection timeout")
	fs.StringVar(&cfg.ClientIDPrefix, "client-id-prefix", cfg.ClientIDPrefix, "X-Client-Id prefix")
	fs.StringVar(&cfg.ActorIDPrefix, "actor-id-prefix", cfg.ActorIDPrefix, "actorId prefix used in submit payloads")
	fs.StringVar(&cfg.RunID, "run-id", cfg.RunID, "run/session id stamped into command payloads")
	fs.StringVar(&cfg.RunKind, "run-kind", cfg.RunKind, "run kind stamped into command payloads")
	fs.StringVar(&cfg.ScenarioID, "scenario-id", cfg.ScenarioID, "scenario id stamped into command payloads")
	fs.StringVar(&cfg.InstrumentID, "instrument-id", cfg.InstrumentID, "submit payload instrumentId")
	fs.StringVar(&cfg.ParticipantID, "participant-id", cfg.ParticipantID, "submit payload participantId")
	fs.StringVar(&cfg.AccountID, "account-id", cfg.AccountID, "submit payload accountId")
	fs.StringVar(&cfg.QuantityUnits, "quantity", cfg.QuantityUnits, "submit payload quantityUnits")
	fs.StringVar(&cfg.LimitPrice, "limit-price", cfg.LimitPrice, "submit payload limitPrice")
	fs.StringVar(&cfg.OccurredAt, "occurred-at", cfg.OccurredAt, "submit payload occurredAt")
	if err := fs.Parse(args); err != nil {
		return cfg, err
	}
	if cfg.Duration <= 0 || cfg.Workers <= 0 {
		return cfg, errors.New("duration and workers must be > 0")
	}
	if cfg.RatePerSecond < 0 {
		return cfg, errors.New("rate must be >= 0")
	}
	if !validRateSchedule(cfg.RateSchedule) {
		return cfg, errors.New("rate-schedule must be drop or precise")
	}
	return cfg, nil
}

func defaultConfig() config {
	return config{
		BaseURL:             envOr("REEF_BASE_URL", "http://127.0.0.1:8080"),
		Duration:            envDuration("REEF_DURATION", 30*time.Second),
		Workers:             envInt("REEF_WORKERS", 256),
		RatePerSecond:       envInt("REEF_RATE", 0),
		RateSchedule:        envOr("REEF_RATE_SCHEDULE", rateSchedulePrecise),
		RequestTimeout:      envDuration("REEF_TIMEOUT", 5*time.Second),
		ReportOut:           envOr("REEF_REPORT_OUT", ""),
		PrettySummary:       envBool("REEF_PRETTY_SUMMARY", false),
		HTTPMaxIdleConns:    envInt("REEF_HTTP_MAX_IDLE_CONNS", 4096),
		HTTPIdleConnsHost:   envInt("REEF_HTTP_MAX_IDLE_CONNS_PER_HOST", 2048),
		HTTPMaxConnsHost:    envInt("REEF_HTTP_MAX_CONNS_PER_HOST", 0),
		HTTPIdleConnTimeout: envDuration("REEF_HTTP_IDLE_CONN_TIMEOUT", 90*time.Second),
		ClientIDPrefix:      envOr("REEF_CLIENT_ID_PREFIX", "intake-bench"),
		ActorIDPrefix:       envOr("REEF_ACTOR_ID_PREFIX", "bot"),
		RunID:               envOr("REEF_RUN_ID", ""),
		RunKind:             envOr("REEF_RUN_KIND", "intake-bench"),
		ScenarioID:          envOr("REEF_SCENARIO_ID", "raw-intake"),
		InstrumentID:        envOr("REEF_INSTRUMENT_ID", "AAPL"),
		ParticipantID:       envOr("REEF_PARTICIPANT_ID", "participant-1"),
		AccountID:           envOr("REEF_ACCOUNT_ID", "account-1"),
		QuantityUnits:       envOr("REEF_QUANTITY", "1"),
		LimitPrice:          envOr("REEF_LIMIT_PRICE", "100"),
		OccurredAt:          envOr("REEF_OCCURRED_AT", "2026-07-01T00:00:00Z"),
	}
}

func run(cfg config) report {
	client := buildHTTPClient(cfg)
	ctx, cancel := context.WithTimeout(context.Background(), cfg.Duration)
	defer cancel()

	sessionID := fmt.Sprintf("intake-%d", time.Now().UnixNano())
	if cfg.RunID == "" {
		cfg.RunID = sessionID
	}
	started := time.Now()
	results := make(chan requestResult, maxInt(cfg.Workers*8, 1024))
	rateCh := make(chan struct{}, rateChannelDepth(cfg))
	if cfg.RatePerSecond > 0 {
		go tokenFeeder(ctx, cfg.RatePerSecond, cfg.RateSchedule, rateCh)
	}

	var counter int64
	var wg sync.WaitGroup
	for workerID := 0; workerID < cfg.Workers; workerID++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			runWorker(ctx, client, cfg, sessionID, id, &counter, rateCh, results)
		}(workerID)
	}

	go func() {
		wg.Wait()
		close(results)
	}()

	all := make([]requestResult, 0, 1024)
	for result := range results {
		all = append(all, result)
	}
	finished := time.Now()
	return buildReport(sessionID, cfg, started, finished, all)
}

func runWorker(ctx context.Context, client *http.Client, cfg config, sessionID string, workerID int, counter *int64, rateCh <-chan struct{}, results chan<- requestResult) {
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
		reqID := atomic.AddInt64(counter, 1)
		started := time.Now()
		status, body, err := submit(client, cfg, sessionID, workerID, reqID)
		results <- requestResult{
			Success:    err == nil && status >= 200 && status < 300 && !bytes.Contains(body, []byte(`"rejected"`)),
			StatusCode: status,
			Latency:    time.Since(started),
			ErrorText:  errorText(status, body, err),
		}
	}
}

func submit(client *http.Client, cfg config, sessionID string, workerID int, reqID int64) (int, []byte, error) {
	commandID := fmt.Sprintf("%s-cmd-%d-%d", sessionID, workerID, reqID)
	traceID := fmt.Sprintf("%s-trace-%d-%d", sessionID, workerID, reqID)
	orderID := fmt.Sprintf("%s-order-%d-%d", sessionID, workerID, reqID)
	body := buildSubmitPayload(cfg, commandID, traceID, orderID, workerID)
	req, err := http.NewRequest(http.MethodPost, cfg.BaseURL+"/api/v1/orders/submit", bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Client-Id", fmt.Sprintf("%s-%d", cfg.ClientIDPrefix, workerID))
	req.Header.Set("Idempotency-Key", commandID)
	req.Header.Set("X-Correlation-Id", traceID)
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(resp.Body)
	return resp.StatusCode, respBody, err
}

func buildSubmitPayload(cfg config, commandID, traceID, orderID string, workerID int) []byte {
	payload := map[string]string{
		"commandId":     commandID,
		"traceId":       traceID,
		"correlationId": traceID,
		"actorId":       fmt.Sprintf("%s-%d", cfg.ActorIDPrefix, workerID),
		"actorType":     "benchmark",
		"strategyId":    "raw-intake",
		"runId":         cfg.RunID,
		"runKind":       cfg.RunKind,
		"scenarioId":    cfg.ScenarioID,
		"occurredAt":    cfg.OccurredAt,
		"orderId":       orderID,
		"instrumentId":  cfg.InstrumentID,
		"participantId": cfg.ParticipantID,
		"accountId":     cfg.AccountID,
		"side":          "BUY",
		"orderType":     "LIMIT",
		"quantityUnits": cfg.QuantityUnits,
		"limitPrice":    cfg.LimitPrice,
		"currency":      "USD",
		"timeInForce":   "DAY",
	}
	body, _ := json.Marshal(payload)
	return body
}

func buildReport(sessionID string, cfg config, started time.Time, finished time.Time, results []requestResult) report {
	statusCodes := map[int]int64{}
	errorsByText := map[string]int64{}
	latencies := make([]float64, 0, len(results))
	var success int64
	for _, result := range results {
		statusCodes[result.StatusCode]++
		latencies = append(latencies, float64(result.Latency.Microseconds())/1000.0)
		if result.Success {
			success++
		} else if result.ErrorText != "" {
			errorsByText[result.ErrorText]++
		}
	}
	total := int64(len(results))
	failures := total - success
	durationSeconds := finished.Sub(started).Seconds()
	out := report{
		SessionID:       sessionID,
		StartedAt:       started,
		FinishedAt:      finished,
		DurationSeconds: durationSeconds,
		Config:          cfg,
		Requests:        total,
		Success:         success,
		Failures:        failures,
		LatencyMs:       reporting.ComputeLatency(latencies),
		StatusCodes:     statusCodes,
		TopErrors:       reporting.TopErrors(errorsByText, 10),
	}
	if durationSeconds > 0 {
		out.ThroughputRPS = float64(total) / durationSeconds
		out.AcceptedRPS = float64(success) / durationSeconds
	}
	if total > 0 {
		out.SuccessRatePct = (float64(success) / float64(total)) * 100
	}
	return out
}

func printReport(result report) {
	if !result.Config.PrettySummary {
		blob, _ := json.MarshalIndent(result, "", "  ")
		fmt.Println(string(blob))
		return
	}
	fmt.Printf("\nReef Intake Bench Summary\n")
	fmt.Printf("Window : %s -> %s (%.1fs)\n", result.StartedAt.Format(time.RFC3339), result.FinishedAt.Format(time.RFC3339), result.DurationSeconds)
	fmt.Printf("Workers: %d | Rate: %d rps | Schedule: %s\n\n", result.Config.Workers, result.Config.RatePerSecond, result.Config.RateSchedule)
	fmt.Printf("Totals\n")
	fmt.Printf("  requests=%d success=%d failures=%d throughput=%.2f rps accepted=%.2f rps\n",
		result.Requests, result.Success, result.Failures, result.ThroughputRPS, result.AcceptedRPS)
	fmt.Printf("  success-rate=%.2f%%\n", result.SuccessRatePct)
	fmt.Printf("  latency(ms): min=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f\n\n",
		result.LatencyMs.Min, result.LatencyMs.P50, result.LatencyMs.P95, result.LatencyMs.P99, result.LatencyMs.Max)
	fmt.Printf("Status Codes\n")
	statuses := make([]int, 0, len(result.StatusCodes))
	for status := range result.StatusCodes {
		statuses = append(statuses, status)
	}
	sort.Ints(statuses)
	for _, status := range statuses {
		fmt.Printf("  %d: %d\n", status, result.StatusCodes[status])
	}
}

func buildHTTPClient(cfg config) *http.Client {
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

func errorText(status int, body []byte, err error) string {
	if err != nil {
		return err.Error()
	}
	if status >= 200 && status < 300 && !bytes.Contains(body, []byte(`"rejected"`)) {
		return ""
	}
	if len(body) == 0 {
		return fmt.Sprintf("http_%d", status)
	}
	text := strings.TrimSpace(string(body))
	if len(text) > 180 {
		text = text[:180]
	}
	return fmt.Sprintf("http_%d:%s", status, text)
}

func rateChannelDepth(cfg config) int {
	if strings.EqualFold(strings.TrimSpace(cfg.RateSchedule), rateSchedulePrecise) {
		return maxInt(cfg.Workers*2, 1)
	}
	return 1
}

func tokenFeeder(ctx context.Context, rate int, schedule string, out chan<- struct{}) {
	if strings.EqualFold(strings.TrimSpace(schedule), rateSchedulePrecise) {
		preciseTokenFeeder(ctx, rate, out)
		return
	}
	dropTokenFeeder(ctx, rate, out)
}

func dropTokenFeeder(ctx context.Context, rate int, out chan<- struct{}) {
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

func preciseTokenFeeder(ctx context.Context, rate int, out chan<- struct{}) {
	period := time.Second / time.Duration(rate)
	if period <= 0 {
		period = time.Microsecond
	}
	next := time.Now()
	for {
		next = next.Add(period)
		if delay := time.Until(next); delay > 0 {
			timer := time.NewTimer(delay)
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
		}
		select {
		case <-ctx.Done():
			return
		case out <- struct{}{}:
		}
		if time.Since(next) > period {
			next = time.Now()
		}
	}
}

func validRateSchedule(schedule string) bool {
	switch strings.ToLower(strings.TrimSpace(schedule)) {
	case rateScheduleDrop, rateSchedulePrecise:
		return true
	default:
		return false
	}
}

func envOr(key string, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func envBool(key string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func envDuration(key string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func maxInt(left int, right int) int {
	if left > right {
		return left
	}
	return right
}
