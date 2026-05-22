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
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Config struct {
	BaseURL         string
	Duration        time.Duration
	Workers         int
	RatePerSecond   int
	RequestTimeout  time.Duration
	SubmitPct       int
	ModifyPct       int
	CancelPct       int
	InstrumentID    string
	InstrumentSymbol string
	ParticipantID   string
	ParticipantName string
	AccountID       string
	QuantityMin     int
	QuantityMax     int
	PriceMin        int64
	PriceMax        int64
	TraceCheckLimit int
	ReportOut       string
	Mode            string
}

type Action string

const (
	ActionSubmit Action = "submit"
	ActionModify Action = "modify"
	ActionCancel Action = "cancel"
)

type requestResult struct {
	Action      Action
	Success     bool
	StatusCode  int
	Latency     time.Duration
	ErrorText   string
	TraceID     string
	OrderID     string
	CommandID   string
	RejectCode  string
	RejectReason string
}

type summary struct {
	SessionID       string                      `json:"sessionId"`
	StartedAt       time.Time                   `json:"startedAt"`
	FinishedAt      time.Time                   `json:"finishedAt"`
	DurationSeconds float64                     `json:"durationSeconds"`
	Config          Config                      `json:"config"`
	ThroughputRPS   float64                     `json:"throughputRps"`
	AcceptedBusinessOpsRPS float64              `json:"acceptedBusinessOpsRps"`
	TotalRequests   int64                       `json:"totalRequests"`
	TotalSuccess    int64                       `json:"totalSuccess"`
	TotalFailures   int64                       `json:"totalFailures"`
	ByAction        map[Action]actionSummary    `json:"byAction"`
	StatusCodes     map[int]int64               `json:"statusCodes"`
	TopErrors       []errorSummary              `json:"topErrors"`
	RejectReasons   []errorSummary              `json:"rejectReasons"`
	LatencyMs       latencySummary              `json:"latencyMs"`
	TraceChecks     traceChecks                 `json:"traceChecks"`
}

type actionSummary struct {
	Requests int64         `json:"requests"`
	Success  int64         `json:"success"`
	Failures int64         `json:"failures"`
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

	for workerID := 0; workerID < cfg.Workers; workerID++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			runWorker(ctx, client, cfg, id, &counter, rateCh, results, &traceSeen)
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
	cfg := Config{}
	flag.StringVar(&cfg.BaseURL, "base-url", envOr("REEF_BASE_URL", "http://localhost:8080"), "platform runtime base url")
	flag.DurationVar(&cfg.Duration, "duration", envDuration("REEF_DURATION", 30*time.Second), "test duration")
	flag.IntVar(&cfg.Workers, "workers", envInt("REEF_WORKERS", 8), "concurrent workers")
	flag.IntVar(&cfg.RatePerSecond, "rate", envInt("REEF_RATE", 0), "global request rate per second (0 = unthrottled)")
	flag.DurationVar(&cfg.RequestTimeout, "timeout", envDuration("REEF_TIMEOUT", 5*time.Second), "request timeout")
	flag.IntVar(&cfg.SubmitPct, "submit-pct", envInt("REEF_SUBMIT_PCT", 60), "submit action percentage")
	flag.IntVar(&cfg.ModifyPct, "modify-pct", envInt("REEF_MODIFY_PCT", 25), "modify action percentage")
	flag.IntVar(&cfg.CancelPct, "cancel-pct", envInt("REEF_CANCEL_PCT", 15), "cancel action percentage")
	flag.StringVar(&cfg.InstrumentID, "instrument-id", envOr("REEF_INSTRUMENT_ID", "AAPL"), "instrument id used for orders")
	flag.StringVar(&cfg.InstrumentSymbol, "instrument-symbol", envOr("REEF_INSTRUMENT_SYMBOL", "AAPL"), "instrument symbol")
	flag.StringVar(&cfg.ParticipantID, "participant-id", envOr("REEF_PARTICIPANT_ID", "participant-1"), "participant id")
	flag.StringVar(&cfg.ParticipantName, "participant-name", envOr("REEF_PARTICIPANT_NAME", "Participant 1"), "participant name")
	flag.StringVar(&cfg.AccountID, "account-id", envOr("REEF_ACCOUNT_ID", "account-1"), "account id")
	flag.IntVar(&cfg.QuantityMin, "qty-min", envInt("REEF_QTY_MIN", 10), "minimum order quantity")
	flag.IntVar(&cfg.QuantityMax, "qty-max", envInt("REEF_QTY_MAX", 1000), "maximum order quantity")
	flag.Int64Var(&cfg.PriceMin, "price-min", envInt64("REEF_PRICE_MIN", 149_000_000_000), "minimum order price nanos")
	flag.Int64Var(&cfg.PriceMax, "price-max", envInt64("REEF_PRICE_MAX", 151_000_000_000), "maximum order price nanos")
	flag.IntVar(&cfg.TraceCheckLimit, "trace-check-limit", envInt("REEF_TRACE_CHECK_LIMIT", 50), "max unique traces to validate")
	flag.StringVar(&cfg.ReportOut, "report-out", envOr("REEF_REPORT_OUT", ""), "optional json report output path")
	flag.StringVar(&cfg.Mode, "mode", envOr("REEF_MODE", "chaos"), "traffic mode: chaos or strict-lifecycle")
	flag.Parse()

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
	if cfg.Mode != "chaos" && cfg.Mode != "strict-lifecycle" {
		return cfg, errors.New("mode must be chaos or strict-lifecycle")
	}
	return cfg, nil
}

func runWorker(
	ctx context.Context,
	client *http.Client,
	cfg Config,
	workerID int,
	counter *int64,
	rateCh <-chan struct{},
	results chan<- requestResult,
	traceSeen *sync.Map,
) {
	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(workerID)*7919))
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

		action := chooseAction(rng, cfg, len(state.orders) > 0)
		reqID := atomic.AddInt64(counter, 1)
		traceID := fmt.Sprintf("trace-%d-%d", workerID, reqID)
		commandID := fmt.Sprintf("cmd-%d-%d", workerID, reqID)
		start := time.Now()
		result := requestResult{
			Action:    action,
			TraceID:   traceID,
			CommandID: commandID,
		}
		switch action {
		case ActionSubmit:
			orderID := fmt.Sprintf("ord-%d-%d", workerID, reqID)
			result.OrderID = orderID
			status, body, err := doPOST(client, cfg.BaseURL+"/orders/submit", map[string]string{
				"commandId":     commandID,
				"traceId":       traceID,
				"causationId":   "",
				"correlationId": traceID,
				"actorId":       fmt.Sprintf("bot-%d", workerID),
				"occurredAt":    time.Now().UTC().Format(time.RFC3339),
				"orderId":       orderID,
				"instrumentId":  cfg.InstrumentID,
				"participantId": cfg.ParticipantID,
				"accountId":     cfg.AccountID,
				"side":          chooseSide(rng),
				"orderType":     "LIMIT",
				"quantityUnits": fmt.Sprintf("%d", randomInt(rng, cfg.QuantityMin, cfg.QuantityMax)),
				"limitPrice":    fmt.Sprintf("%d", randomInt64(rng, cfg.PriceMin, cfg.PriceMax)),
				"currency":      "USD",
				"timeInForce":   "DAY",
			})
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.orders = append(state.orders, orderID)
				traceSeen.Store(traceID, struct{}{})
			}
		case ActionModify:
			if len(state.orders) == 0 {
				continue
			}
			orderID := state.orders[rng.Intn(len(state.orders))]
			result.OrderID = orderID
			status, body, err := doPOST(client, cfg.BaseURL+"/orders/modify", map[string]string{
				"commandId":     commandID,
				"traceId":       traceID,
				"causationId":   "",
				"correlationId": traceID,
				"actorId":       fmt.Sprintf("bot-%d", workerID),
				"occurredAt":    time.Now().UTC().Format(time.RFC3339),
				"orderId":       orderID,
				"quantityUnits": fmt.Sprintf("%d", randomInt(rng, cfg.QuantityMin, cfg.QuantityMax)),
				"limitPrice":    fmt.Sprintf("%d", randomInt64(rng, cfg.PriceMin, cfg.PriceMax)),
			})
			fillResult(&result, status, body, err, start)
			if result.Success {
				traceSeen.Store(traceID, struct{}{})
			} else if cfg.Mode == "strict-lifecycle" && isTerminalOrderRejection(result.RejectCode) {
				state.orders = removeOrder(state.orders, orderID)
			}
		case ActionCancel:
			if len(state.orders) == 0 {
				continue
			}
			idx := rng.Intn(len(state.orders))
			orderID := state.orders[idx]
			result.OrderID = orderID
			status, body, err := doPOST(client, cfg.BaseURL+"/orders/cancel", map[string]string{
				"commandId":     commandID,
				"traceId":       traceID,
				"causationId":   "",
				"correlationId": traceID,
				"actorId":       fmt.Sprintf("bot-%d", workerID),
				"occurredAt":    time.Now().UTC().Format(time.RFC3339),
				"orderId":       orderID,
				"reason":        "load test",
			})
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.orders = append(state.orders[:idx], state.orders[idx+1:]...)
				traceSeen.Store(traceID, struct{}{})
			} else if cfg.Mode == "strict-lifecycle" && isTerminalOrderRejection(result.RejectCode) {
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
		StatusCodes: make(map[int]int64),
	}

	errorCounts := make(map[string]int64)
	rejectReasons := make(map[string]int64)
	allLatencies := make([]float64, 0, len(results))
	actionLatencies := map[Action][]float64{
		ActionSubmit: {},
		ActionModify: {},
		ActionCancel: {},
	}

	for _, r := range results {
		report.TotalRequests++
		report.StatusCodes[r.StatusCode]++
		allLatencies = append(allLatencies, r.Latency.Seconds()*1000)
		actionLatencies[r.Action] = append(actionLatencies[r.Action], r.Latency.Seconds()*1000)

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
				key := r.RejectCode
				if r.RejectReason != "" {
					key = key + ": " + r.RejectReason
				}
				rejectReasons[key]++
			}
		}
		report.ByAction[r.Action] = current
	}

	report.ThroughputRPS = float64(report.TotalRequests) / report.DurationSeconds
	report.AcceptedBusinessOpsRPS = float64(report.TotalSuccess) / report.DurationSeconds
	report.LatencyMs = computeLatency(allLatencies)
	for action, values := range actionLatencies {
		current := report.ByAction[action]
		current.Latency = computeLatency(values)
		report.ByAction[action] = current
	}
	report.TopErrors = topErrors(errorCounts, 8)
	report.RejectReasons = topErrors(rejectReasons, 12)
	return report
}

func runTraceChecks(client *http.Client, cfg Config, seen *sync.Map) traceChecks {
	traceIDs := make([]string, 0, cfg.TraceCheckLimit)
	seen.Range(func(key, _ any) bool {
		traceIDs = append(traceIDs, key.(string))
		return len(traceIDs) < cfg.TraceCheckLimit
	})

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

func checkTrace(client *http.Client, baseURL, traceID string) bool {
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
	last := int64(0)
	for _, event := range parsed.Events {
		if event.TraceID != traceID {
			return false
		}
		if event.SequenceNumber != last+1 {
			return false
		}
		last = event.SequenceNumber
	}
	return true
}

func seedReferenceData(client *http.Client, cfg Config) error {
	if _, _, err := doPOST(client, cfg.BaseURL+"/reference/instruments", map[string]string{
		"instrumentId": cfg.InstrumentID,
		"symbol":       cfg.InstrumentSymbol,
	}); err != nil {
		return err
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

func chooseAction(rng *rand.Rand, cfg Config, hasOrders bool) Action {
	if cfg.Mode == "strict-lifecycle" && !hasOrders {
		return ActionSubmit
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

func isTerminalOrderRejection(code string) bool {
	return code == "INVALID_STATE" || code == "NOT_FOUND"
}

func removeOrder(orders []string, orderID string) []string {
	for i, existing := range orders {
		if existing == orderID {
			return append(orders[:i], orders[i+1:]...)
		}
	}
	return orders
}

func chooseSide(rng *rand.Rand) string {
	if rng.Intn(2) == 0 {
		return "BUY"
	}
	return "SELL"
}

func randomInt(rng *rand.Rand, min, max int) int {
	if min == max {
		return min
	}
	return min + rng.Intn(max-min+1)
}

func randomInt64(rng *rand.Rand, min, max int64) int64 {
	if min == max {
		return min
	}
	return min + rng.Int63n(max-min+1)
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
	blob, _ := json.MarshalIndent(report, "", "  ")
	fmt.Println(string(blob))
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
