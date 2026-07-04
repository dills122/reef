package loadtest

import (
	"bufio"
	"context"
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

const (
	ScenarioAlternatingCross = "alternating-cross"
	ScenarioRestingBook      = "resting-book"
	ScenarioLifecycle        = "lifecycle"
)

type Config struct {
	RunID              string        `json:"runId"`
	Scenario           string        `json:"scenario"`
	RatePerSecond      int           `json:"ratePerSecond"`
	Duration           time.Duration `json:"duration"`
	Workers            int           `json:"workers"`
	Instruments        int           `json:"instruments"`
	OutputDir          string        `json:"outputDir"`
	RecordResults      bool          `json:"recordResults"`
	MaxRecordedResults int           `json:"maxRecordedResults"`
	MinProcessedRate   float64       `json:"minProcessedRate"`
}

type Report struct {
	RunID                string            `json:"runId"`
	StartedAt            string            `json:"startedAt"`
	FinishedAt           string            `json:"finishedAt"`
	DurationSeconds      float64           `json:"durationSeconds"`
	Config               ConfigSnapshot    `json:"config"`
	Attempted            int64             `json:"attempted"`
	Processed            int64             `json:"processed"`
	Accepted             int64             `json:"accepted"`
	Rejected             int64             `json:"rejected"`
	SystemFailures       int64             `json:"systemFailures"`
	Executions           int64             `json:"executions"`
	Trades               int64             `json:"trades"`
	AttemptedPerSecond   float64           `json:"attemptedPerSecond"`
	ProcessedPerSecond   float64           `json:"processedPerSecond"`
	AcceptedPerSecond    float64           `json:"acceptedPerSecond"`
	RejectedPerSecond    float64           `json:"rejectedPerSecond"`
	RejectCodes          map[string]int64  `json:"rejectCodes,omitempty"`
	LatencyMicros        LatencySummary    `json:"latencyMicros"`
	Intervals            []IntervalReport  `json:"intervals"`
	Artifacts            map[string]string `json:"artifacts,omitempty"`
	PassedMinRate        bool              `json:"passedMinRate"`
	MinRateFailureReason string            `json:"minRateFailureReason,omitempty"`
}

type ConfigSnapshot struct {
	Scenario           string  `json:"scenario"`
	RatePerSecond      int     `json:"ratePerSecond"`
	Duration           string  `json:"duration"`
	Workers            int     `json:"workers"`
	Instruments        int     `json:"instruments"`
	RecordResults      bool    `json:"recordResults"`
	MaxRecordedResults int     `json:"maxRecordedResults"`
	MinProcessedRate   float64 `json:"minProcessedRate"`
}

type LatencySummary struct {
	Count int64 `json:"count"`
	Min   int64 `json:"min"`
	P50   int64 `json:"p50"`
	P95   int64 `json:"p95"`
	P99   int64 `json:"p99"`
	Max   int64 `json:"max"`
}

type IntervalReport struct {
	Second         int     `json:"second"`
	Attempted      int64   `json:"attempted"`
	Processed      int64   `json:"processed"`
	Accepted       int64   `json:"accepted"`
	Rejected       int64   `json:"rejected"`
	SystemFailures int64   `json:"systemFailures"`
	Executions     int64   `json:"executions"`
	Trades         int64   `json:"trades"`
	ProcessedRate  float64 `json:"processedRate"`
}

type commandKind string

const (
	commandSubmit commandKind = "submit"
	commandModify commandKind = "modify"
	commandCancel commandKind = "cancel"
)

type commandEnvelope struct {
	Seq          int64
	Kind         commandKind
	OrderID      string
	InstrumentID string
	Submit       domain.SubmitOrder
	Modify       domain.ModifyOrder
	Cancel       domain.CancelOrder
}

type sample struct {
	Seq           int64
	Kind          commandKind
	OrderID       string
	InstrumentID  string
	LatencyMicros int64
	Result        domain.SubmitOrderResult
	Failure       string
}

type recordedResult struct {
	Seq           int64                    `json:"seq"`
	CommandType   string                   `json:"commandType"`
	OrderID       string                   `json:"orderId"`
	InstrumentID  string                   `json:"instrumentId"`
	Accepted      bool                     `json:"accepted"`
	RejectCode    string                   `json:"rejectCode,omitempty"`
	Executions    int                      `json:"executions"`
	Trades        int                      `json:"trades"`
	LatencyMicros int64                    `json:"latencyMicros"`
	Failure       string                   `json:"failure,omitempty"`
	Result        domain.SubmitOrderResult `json:"result"`
}

type counters struct {
	attempted      int64
	processed      int64
	accepted       int64
	rejected       int64
	systemFailures int64
	executions     int64
	trades         int64
	rejectCodes    map[string]int64
	latencies      []int64
	intervals      map[int]*IntervalReport
}

func Run(ctx context.Context, service *app.Service, cfg Config) (Report, error) {
	cfg = normalizeConfig(cfg)
	if err := validateConfig(cfg); err != nil {
		return Report{}, err
	}
	if service == nil {
		service = app.NewService()
	}

	runDir, err := prepareRunDir(cfg)
	if err != nil {
		return Report{}, err
	}

	var resultsFile *os.File
	var resultsWriter *bufio.Writer
	if cfg.RecordResults {
		resultsPath := filepath.Join(runDir, "results.ndjson")
		resultsFile, err = os.Create(resultsPath)
		if err != nil {
			return Report{}, fmt.Errorf("create results artifact: %w", err)
		}
		defer resultsFile.Close()
		resultsWriter = bufio.NewWriter(resultsFile)
		defer resultsWriter.Flush()
	}

	started := time.Now().UTC()
	totalAttempts := int64(float64(cfg.RatePerSecond) * cfg.Duration.Seconds())
	jobs := make(chan commandEnvelope, maxInt(cfg.RatePerSecond, cfg.Workers*4))
	samples := make(chan sample, maxInt(cfg.RatePerSecond, cfg.Workers*4))

	var workers sync.WaitGroup
	workers.Add(cfg.Workers)
	for workerID := 0; workerID < cfg.Workers; workerID++ {
		go func() {
			defer workers.Done()
			for command := range jobs {
				start := time.Now()
				result, failure := execute(service, command)
				samples <- sample{
					Seq:           command.Seq,
					Kind:          command.Kind,
					OrderID:       command.OrderID,
					InstrumentID:  command.InstrumentID,
					LatencyMicros: time.Since(start).Microseconds(),
					Result:        result,
					Failure:       failure,
				}
			}
		}()
	}

	generated := make(chan int64, 1)
	go func() {
		defer close(jobs)
		generated <- generate(ctx, cfg, started, totalAttempts, jobs)
	}()

	doneSamples := make(chan struct{})
	go func() {
		workers.Wait()
		close(samples)
		close(doneSamples)
	}()

	state := counters{
		rejectCodes: make(map[string]int64),
		latencies:   make([]int64, 0, totalAttempts),
		intervals:   make(map[int]*IntervalReport),
	}

	recorded := 0
	for item := range samples {
		applySample(&state, started, item)
		if resultsWriter != nil && shouldRecord(recorded, cfg.MaxRecordedResults) {
			if err := writeRecordedResult(resultsWriter, item); err != nil {
				return Report{}, err
			}
			recorded++
		}
	}
	<-doneSamples
	state.attempted = <-generated

	finished := time.Now().UTC()
	report := buildReport(cfg, runDir, started, finished, state)
	if err := writeArtifacts(runDir, report); err != nil {
		return Report{}, err
	}
	if cfg.RecordResults {
		report.Artifacts["results"] = filepath.Join(runDir, "results.ndjson")
		if err := writeArtifacts(runDir, report); err != nil {
			return Report{}, err
		}
	}

	if cfg.MinProcessedRate > 0 && !report.PassedMinRate {
		return report, fmt.Errorf("%s", report.MinRateFailureReason)
	}
	return report, nil
}

func normalizeConfig(cfg Config) Config {
	if cfg.RunID == "" {
		cfg.RunID = "matching-engine-load-" + time.Now().UTC().Format("20060102T150405Z")
	}
	if cfg.Scenario == "" {
		cfg.Scenario = ScenarioAlternatingCross
	}
	if cfg.RatePerSecond == 0 {
		cfg.RatePerSecond = 10000
	}
	if cfg.Duration == 0 {
		cfg.Duration = 30 * time.Second
	}
	if cfg.Workers == 0 {
		cfg.Workers = 1
	}
	if cfg.Instruments == 0 {
		cfg.Instruments = 1
	}
	if cfg.OutputDir == "" {
		cfg.OutputDir = filepath.Join("..", "..", "reports", "matching-engine-load")
	}
	return cfg
}

func validateConfig(cfg Config) error {
	if cfg.RatePerSecond <= 0 {
		return errors.New("rate must be positive")
	}
	if cfg.Duration <= 0 {
		return errors.New("duration must be positive")
	}
	if cfg.Workers <= 0 {
		return errors.New("workers must be positive")
	}
	if cfg.Instruments <= 0 {
		return errors.New("instruments must be positive")
	}
	switch cfg.Scenario {
	case ScenarioAlternatingCross, ScenarioRestingBook, ScenarioLifecycle:
		return nil
	default:
		return fmt.Errorf("unsupported scenario %q", cfg.Scenario)
	}
}

func prepareRunDir(cfg Config) (string, error) {
	runDir := filepath.Join(cfg.OutputDir, cfg.RunID)
	if err := os.MkdirAll(runDir, 0o755); err != nil {
		return "", fmt.Errorf("create output directory: %w", err)
	}
	return runDir, nil
}

func generate(ctx context.Context, cfg Config, started time.Time, totalAttempts int64, jobs chan<- commandEnvelope) int64 {
	var attempted int64
	interval := time.Second / time.Duration(cfg.RatePerSecond)
	if interval <= 0 {
		interval = time.Nanosecond
	}
	for seq := int64(0); seq < totalAttempts; seq++ {
		next := started.Add(time.Duration(seq) * interval)
		if delay := time.Until(next); delay > 0 {
			timer := time.NewTimer(delay)
			select {
			case <-ctx.Done():
				timer.Stop()
				return attempted
			case <-timer.C:
			}
		}
		command := buildCommand(cfg, seq, started)
		select {
		case <-ctx.Done():
			return attempted
		case jobs <- command:
			attempted++
		}
	}
	return attempted
}

func buildCommand(cfg Config, seq int64, started time.Time) commandEnvelope {
	instrumentID := fmt.Sprintf("LOAD-%03d", seq%int64(cfg.Instruments))
	occurredAt := started.Format(time.RFC3339Nano)
	switch cfg.Scenario {
	case ScenarioRestingBook:
		orderID := fmt.Sprintf("ord-rest-%d", seq)
		return commandEnvelope{
			Seq:          seq,
			Kind:         commandSubmit,
			OrderID:      orderID,
			InstrumentID: instrumentID,
			Submit:       submitOrder(seq, orderID, instrumentID, domain.SideBuy, "100", "150000000000", occurredAt),
		}
	case ScenarioLifecycle:
		group := seq / 3
		orderID := fmt.Sprintf("ord-life-%d", group)
		switch seq % 3 {
		case 0:
			return commandEnvelope{
				Seq:          seq,
				Kind:         commandSubmit,
				OrderID:      orderID,
				InstrumentID: instrumentID,
				Submit:       submitOrder(seq, orderID, instrumentID, domain.SideBuy, "200", "150000000000", occurredAt),
			}
		case 1:
			return commandEnvelope{
				Seq:          seq,
				Kind:         commandModify,
				OrderID:      orderID,
				InstrumentID: instrumentID,
				Modify: domain.ModifyOrder{
					CommandID:     fmt.Sprintf("cmd-%d", seq),
					TraceID:       fmt.Sprintf("trace-%s", cfg.RunID),
					CausationID:   fmt.Sprintf("cause-%d", seq),
					CorrelationID: fmt.Sprintf("corr-%d", group),
					ActorID:       "matching-load-harness",
					OccurredAt:    occurredAt,
					OrderID:       orderID,
					QuantityUnits: "210",
					LimitPrice:    "150100000000",
				},
			}
		default:
			return commandEnvelope{
				Seq:          seq,
				Kind:         commandCancel,
				OrderID:      orderID,
				InstrumentID: instrumentID,
				Cancel: domain.CancelOrder{
					CommandID:     fmt.Sprintf("cmd-%d", seq),
					TraceID:       fmt.Sprintf("trace-%s", cfg.RunID),
					CausationID:   fmt.Sprintf("cause-%d", seq),
					CorrelationID: fmt.Sprintf("corr-%d", group),
					ActorID:       "matching-load-harness",
					OccurredAt:    occurredAt,
					OrderID:       orderID,
					Reason:        "load-test-cycle",
				},
			}
		}
	default:
		orderID := fmt.Sprintf("ord-cross-%d", seq)
		side := domain.SideBuy
		price := "150250000000"
		if seq%2 == 1 {
			side = domain.SideSell
			price = "150000000000"
		}
		return commandEnvelope{
			Seq:          seq,
			Kind:         commandSubmit,
			OrderID:      orderID,
			InstrumentID: instrumentID,
			Submit:       submitOrder(seq, orderID, instrumentID, side, "100", price, occurredAt),
		}
	}
}

func submitOrder(seq int64, orderID string, instrumentID string, side domain.Side, quantity string, price string, occurredAt string) domain.SubmitOrder {
	return domain.SubmitOrder{
		CommandID:     fmt.Sprintf("cmd-%d", seq),
		TraceID:       "trace-matching-engine-load",
		CausationID:   fmt.Sprintf("cause-%d", seq),
		CorrelationID: fmt.Sprintf("corr-%d", seq/2),
		ActorID:       "matching-load-harness",
		OccurredAt:    occurredAt,
		OrderID:       orderID,
		InstrumentID:  instrumentID,
		ParticipantID: fmt.Sprintf("participant-%d", seq%16),
		AccountID:     fmt.Sprintf("account-%d", seq%16),
		Side:          side,
		OrderType:     "LIMIT",
		QuantityUnits: quantity,
		LimitPrice:    price,
		Currency:      "USD",
		TimeInForce:   "DAY",
	}
}

func execute(service *app.Service, command commandEnvelope) (result domain.SubmitOrderResult, failure string) {
	defer func() {
		if recovered := recover(); recovered != nil {
			result = domain.SubmitOrderResult{}
			failure = fmt.Sprintf("panic: %v", recovered)
		}
	}()
	switch command.Kind {
	case commandSubmit:
		return service.SubmitOrder(command.Submit), ""
	case commandModify:
		return service.ModifyOrder(command.Modify), ""
	case commandCancel:
		return service.CancelOrder(command.Cancel), ""
	default:
		return domain.SubmitOrderResult{}, "unsupported command kind"
	}
}

func applySample(state *counters, started time.Time, item sample) {
	state.processed++
	state.latencies = append(state.latencies, item.LatencyMicros)
	second := int(time.Since(started).Seconds())
	interval := state.intervals[second]
	if interval == nil {
		interval = &IntervalReport{Second: second}
		state.intervals[second] = interval
	}
	interval.Processed++

	if item.Failure != "" {
		state.systemFailures++
		interval.SystemFailures++
		return
	}
	if item.Result.Accepted != nil {
		state.accepted++
		interval.Accepted++
	}
	if item.Result.Rejected != nil {
		state.rejected++
		interval.Rejected++
		state.rejectCodes[item.Result.Rejected.Code]++
	}
	executions := int64(len(item.Result.Executions))
	trades := int64(len(item.Result.Trades))
	state.executions += executions
	state.trades += trades
	interval.Executions += executions
	interval.Trades += trades
}

func shouldRecord(recorded int, max int) bool {
	return max <= 0 || recorded < max
}

func writeRecordedResult(writer *bufio.Writer, item sample) error {
	rejectCode := ""
	if item.Result.Rejected != nil {
		rejectCode = item.Result.Rejected.Code
	}
	payload := recordedResult{
		Seq:           item.Seq,
		CommandType:   string(item.Kind),
		OrderID:       item.OrderID,
		InstrumentID:  item.InstrumentID,
		Accepted:      item.Result.Accepted != nil,
		RejectCode:    rejectCode,
		Executions:    len(item.Result.Executions),
		Trades:        len(item.Result.Trades),
		LatencyMicros: item.LatencyMicros,
		Failure:       item.Failure,
		Result:        item.Result,
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("encode result record: %w", err)
	}
	if _, err := writer.Write(encoded); err != nil {
		return fmt.Errorf("write result record: %w", err)
	}
	if err := writer.WriteByte('\n'); err != nil {
		return fmt.Errorf("write result delimiter: %w", err)
	}
	return nil
}

func buildReport(cfg Config, runDir string, started time.Time, finished time.Time, state counters) Report {
	durationSeconds := finished.Sub(started).Seconds()
	intervals := flattenIntervals(state.intervals)
	for idx := range intervals {
		intervals[idx].Attempted = attemptedForSecond(cfg, intervals[idx].Second)
		intervals[idx].ProcessedRate = float64(intervals[idx].Processed)
	}

	report := Report{
		RunID:           cfg.RunID,
		StartedAt:       started.Format(time.RFC3339Nano),
		FinishedAt:      finished.Format(time.RFC3339Nano),
		DurationSeconds: durationSeconds,
		Config:          snapshot(cfg),
		Attempted:       state.attempted,
		Processed:       state.processed,
		Accepted:        state.accepted,
		Rejected:        state.rejected,
		SystemFailures:  state.systemFailures,
		Executions:      state.executions,
		Trades:          state.trades,
		RejectCodes:     state.rejectCodes,
		LatencyMicros:   summarizeLatency(state.latencies),
		Intervals:       intervals,
		Artifacts:       map[string]string{},
		PassedMinRate:   true,
	}
	if durationSeconds > 0 {
		report.AttemptedPerSecond = float64(report.Attempted) / durationSeconds
		report.ProcessedPerSecond = float64(report.Processed) / durationSeconds
		report.AcceptedPerSecond = float64(report.Accepted) / durationSeconds
		report.RejectedPerSecond = float64(report.Rejected) / durationSeconds
	}
	report.Artifacts["summary"] = filepath.Join(runDir, "summary.json")
	report.Artifacts["intervals"] = filepath.Join(runDir, "intervals.csv")
	if cfg.MinProcessedRate > 0 && report.ProcessedPerSecond < cfg.MinProcessedRate {
		report.PassedMinRate = false
		report.MinRateFailureReason = fmt.Sprintf("processed rate %.2f/s is below minimum %.2f/s", report.ProcessedPerSecond, cfg.MinProcessedRate)
	}
	return report
}

func snapshot(cfg Config) ConfigSnapshot {
	return ConfigSnapshot{
		Scenario:           cfg.Scenario,
		RatePerSecond:      cfg.RatePerSecond,
		Duration:           cfg.Duration.String(),
		Workers:            cfg.Workers,
		Instruments:        cfg.Instruments,
		RecordResults:      cfg.RecordResults,
		MaxRecordedResults: cfg.MaxRecordedResults,
		MinProcessedRate:   cfg.MinProcessedRate,
	}
}

func attemptedForSecond(cfg Config, second int) int64 {
	elapsed := time.Duration(second+1) * time.Second
	if elapsed > cfg.Duration {
		elapsed = cfg.Duration
	}
	previous := time.Duration(second) * time.Second
	if previous > cfg.Duration {
		return 0
	}
	return int64(float64(cfg.RatePerSecond) * (elapsed - previous).Seconds())
}

func flattenIntervals(intervals map[int]*IntervalReport) []IntervalReport {
	keys := make([]int, 0, len(intervals))
	for key := range intervals {
		keys = append(keys, key)
	}
	sort.Ints(keys)
	flattened := make([]IntervalReport, 0, len(keys))
	for _, key := range keys {
		flattened = append(flattened, *intervals[key])
	}
	return flattened
}

func summarizeLatency(latencies []int64) LatencySummary {
	if len(latencies) == 0 {
		return LatencySummary{}
	}
	values := append([]int64(nil), latencies...)
	sort.Slice(values, func(i, j int) bool { return values[i] < values[j] })
	return LatencySummary{
		Count: int64(len(values)),
		Min:   values[0],
		P50:   percentile(values, 0.50),
		P95:   percentile(values, 0.95),
		P99:   percentile(values, 0.99),
		Max:   values[len(values)-1],
	}
}

func percentile(values []int64, pct float64) int64 {
	if len(values) == 0 {
		return 0
	}
	idx := int(float64(len(values)-1) * pct)
	if idx < 0 {
		return values[0]
	}
	if idx >= len(values) {
		return values[len(values)-1]
	}
	return values[idx]
}

func writeArtifacts(runDir string, report Report) error {
	summary, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return fmt.Errorf("encode summary artifact: %w", err)
	}
	if err := os.WriteFile(filepath.Join(runDir, "summary.json"), append(summary, '\n'), 0o644); err != nil {
		return fmt.Errorf("write summary artifact: %w", err)
	}

	file, err := os.Create(filepath.Join(runDir, "intervals.csv"))
	if err != nil {
		return fmt.Errorf("create intervals artifact: %w", err)
	}
	defer file.Close()
	writer := csv.NewWriter(file)
	defer writer.Flush()
	if err := writer.Write([]string{"second", "attempted", "processed", "accepted", "rejected", "system_failures", "executions", "trades", "processed_rate"}); err != nil {
		return fmt.Errorf("write intervals header: %w", err)
	}
	for _, interval := range report.Intervals {
		if err := writer.Write([]string{
			strconv.Itoa(interval.Second),
			strconv.FormatInt(interval.Attempted, 10),
			strconv.FormatInt(interval.Processed, 10),
			strconv.FormatInt(interval.Accepted, 10),
			strconv.FormatInt(interval.Rejected, 10),
			strconv.FormatInt(interval.SystemFailures, 10),
			strconv.FormatInt(interval.Executions, 10),
			strconv.FormatInt(interval.Trades, 10),
			strconv.FormatFloat(interval.ProcessedRate, 'f', 2, 64),
		}); err != nil {
			return fmt.Errorf("write intervals row: %w", err)
		}
	}
	if err := writer.Error(); err != nil {
		return fmt.Errorf("flush intervals artifact: %w", err)
	}
	return nil
}

func maxInt(a int, b int) int {
	if a > b {
		return a
	}
	return b
}
