// calcify-load sends deterministic crossing order pairs through durable HTTP intake.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type config struct {
	WorkloadMode      string
	BaseURL           string
	InstrumentIDs     []string
	SmokeID           string
	Duration          time.Duration
	PairsPerSecond    int
	Workers           int
	StartIndex        int64
	ReceiptGeneration int
	ReportOut         string
}

type sample struct {
	ElapsedMs            int64  `json:"elapsedMs"`
	CompletedPairsBefore int64  `json:"completedPairsBefore"`
	CompletedPairs       int64  `json:"completedPairs"`
	Receipts             *int64 `json:"receipts,omitempty"`
	GapLower             *int64 `json:"acceptedToReceiptGapLower,omitempty"`
	Gap                  *int64 `json:"acceptedToReceiptGapUpper,omitempty"`
	ReceiptQueryMs       int64  `json:"receiptQueryMs,omitempty"`
	Error                string `json:"error,omitempty"`
}

type report struct {
	WorkloadMode             string   `json:"workloadMode"`
	OrdersPerTrade           int64    `json:"ordersPerTrade"`
	CompletedTrades          int64    `json:"completedTrades"`
	JobAccounting            string   `json:"jobAccounting"`
	InstrumentIDs            []string `json:"instrumentIds"`
	SmokeID                  string   `json:"smokeId"`
	TargetPairs              int64    `json:"targetPairs"`
	StartIndex               int64    `json:"startIndex"`
	TargetPairsPerSecond     int      `json:"targetPairsPerSecond"`
	ScheduledPairs           int64    `json:"scheduledPairs"`
	DispatchedPairs          int64    `json:"dispatchedPairs"`
	DroppedPairs             int64    `json:"droppedPairs"`
	CompletedPairs           int64    `json:"completedPairs"`
	AcceptedOrders           int64    `json:"acceptedOrders"`
	AcceptedOrdersAtDeadline int64    `json:"acceptedOrdersAtDeadline"`
	DeadlineAccounting       string   `json:"deadlineAccounting"`
	LoadStartedEpochMs       int64    `json:"loadStartedEpochMs"`
	ScheduledDeadlineEpochMs int64    `json:"scheduledDeadlineEpochMs"`
	AcceptedAllEpochMs       int64    `json:"acceptedAllEpochMs"`
	ReceiptDrainAccounting   string   `json:"receiptDrainAccounting"`
	AcceptedOrdersPerSecond  float64  `json:"acceptedOrdersPerSecond"`
	ElapsedMs                int64    `json:"elapsedMs"`
	Retries                  int64    `json:"retries"`
	Failures                 int64    `json:"failures"`
	FirstError               string   `json:"firstError,omitempty"`
	ReceiptGeneration        int      `json:"receiptGeneration,omitempty"`
	Samples                  []sample `json:"samples,omitempty"`
	FinalReceipts            *int64   `json:"finalReceipts,omitempty"`
	DrainMs                  *int64   `json:"drainMs,omitempty"`
	GapP95                   *int64   `json:"p95AcceptedToReceiptGap,omitempty"`
	GapMax                   *int64   `json:"maxAcceptedToReceiptGap,omitempty"`
	GatePassed               bool     `json:"gatePassed"`
	GateReasons              []string `json:"gateReasons,omitempty"`
}

type counters struct {
	acceptedOrders         atomic.Int64
	acceptedBeforeDeadline atomic.Int64
	// loadDeadline and now are fixed before workers start; only counters mutate.
	loadDeadline   time.Time
	now            func() time.Time
	completedPairs atomic.Int64
	retries        atomic.Int64
	failures       atomic.Int64
	firstError     atomic.Value
}

// recordAcknowledgement classifies one validated durable HTTP acknowledgement.
func (c *counters) recordAcknowledgement(observedAt time.Time) {
	c.acceptedOrders.Add(1)
	if observedAt.Before(c.loadDeadline) {
		c.acceptedBeforeDeadline.Add(1)
	}
}

func main() {
	cfg := config{}
	flag.StringVar(&cfg.WorkloadMode, "workload-mode", "paired", "paired (BUY then SELL) or aggressor (SELL against preseeded makers)")
	var instrumentIDs string
	flag.StringVar(&instrumentIDs, "instrument-ids", "", "optional comma-separated instrument IDs; pairs route round-robin by absolute pair index")
	flag.StringVar(&cfg.BaseURL, "base-url", "http://127.0.0.1:8080", "runtime URL")
	flag.StringVar(&cfg.SmokeID, "smoke-id", "", "seeded full-path smoke ID")
	flag.DurationVar(&cfg.Duration, "duration", 30*time.Second, "scheduled load duration")
	flag.IntVar(&cfg.PairsPerSecond, "pairs-per-second", 0, "offered trade jobs per second (legacy pairs flag; mode defines orders per job)")
	flag.IntVar(&cfg.Workers, "workers", 256, "concurrent pair workers")
	flag.Int64Var(&cfg.StartIndex, "start-index", 0, "first pair index; use nonoverlapping range on reused session")
	flag.IntVar(&cfg.ReceiptGeneration, "receipt-generation", 0, "fresh Calcify generation to sample and reconcile")
	flag.StringVar(&cfg.ReportOut, "report-out", "", "JSON report path")
	flag.Parse()
	if cfg.SmokeID == "" || cfg.Duration <= 0 || cfg.Duration%time.Second != 0 ||
		cfg.PairsPerSecond <= 0 || cfg.Workers <= 0 || cfg.StartIndex < 0 || cfg.ReportOut == "" {
		fmt.Fprintln(os.Stderr, "smoke-id, whole-second duration, pairs-per-second, workers, and report-out required")
		os.Exit(2)
	}
	var err error
	cfg.WorkloadMode, _, err = workloadParameters(cfg.WorkloadMode, cfg.ReceiptGeneration)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	cfg.InstrumentIDs, err = parseInstrumentIDs(instrumentIDs, cfg.SmokeID)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	result := run(cfg)
	blob, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if cfg.ReportOut != "" {
		if err := os.WriteFile(cfg.ReportOut, append(blob, '\n'), 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	fmt.Printf("CALCIFY_LOAD_RESULT %s\n", blob)
	if !result.GatePassed {
		os.Exit(1)
	}
}

func run(cfg config) report {
	mode, ordersPerTrade, err := workloadParameters(cfg.WorkloadMode, cfg.ReceiptGeneration)
	if err != nil {
		return report{WorkloadMode: cfg.WorkloadMode, Failures: 1, FirstError: err.Error(), GateReasons: []string{err.Error()}}
	}
	cfg.WorkloadMode = mode
	if len(cfg.InstrumentIDs) == 0 {
		cfg.InstrumentIDs = []string{"AAPL-" + cfg.SmokeID}
	}
	transport := &http.Transport{
		MaxIdleConns: cfg.Workers * 2, MaxIdleConnsPerHost: cfg.Workers,
		MaxConnsPerHost: cfg.Workers, IdleConnTimeout: 90 * time.Second,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second}
	total := int64(cfg.PairsPerSecond) * int64(cfg.Duration/time.Second)
	jobs := make(chan int64, cfg.Workers*4)
	started := time.Now()
	counts := counters{loadDeadline: started.Add(cfg.Duration)}
	var sampleMu sync.Mutex
	var samples []sample
	var workers sync.WaitGroup
	for range cfg.Workers {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for index := range jobs {
				if err := submitPair(client, cfg, index, &counts); err != nil {
					if counts.failures.Add(1) == 1 {
						counts.firstError.Store(err.Error())
					}
				}
			}
		}()
	}
	progressDone := make(chan struct{})
	progressStopped := make(chan struct{})
	go func() {
		defer close(progressStopped)
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-progressDone:
				return
			case <-ticker.C:
				s := sample{ElapsedMs: time.Since(started).Milliseconds(),
					CompletedPairsBefore: counts.completedPairs.Load()}
				if cfg.ReceiptGeneration > 0 {
					queryStarted := time.Now()
					if receipts, err := receiptCount(cfg.ReceiptGeneration); err == nil {
						s.CompletedPairs = counts.completedPairs.Load()
						lower, upper := gapRange(s.CompletedPairsBefore, s.CompletedPairs, receipts)
						s.Receipts, s.GapLower, s.Gap = &receipts, &lower, &upper
					} else {
						s.CompletedPairs = counts.completedPairs.Load()
						s.Error = err.Error()
					}
					s.ReceiptQueryMs = time.Since(queryStarted).Milliseconds()
				} else {
					s.CompletedPairs = s.CompletedPairsBefore
				}
				sampleMu.Lock()
				samples = append(samples, s)
				sampleMu.Unlock()
				progress := map[string]any{
					"elapsedMs":                 s.ElapsedMs,
					"completedPairs":            s.CompletedPairs,
					"acceptedOrders":            counts.acceptedOrders.Load(),
					"receipts":                  s.Receipts,
					"acceptedToReceiptGapLower": s.GapLower,
					"acceptedToReceiptGapUpper": s.Gap,
					"receiptQueryMs":            s.ReceiptQueryMs,
					"sampleError":               s.Error,
					"retries":                   counts.retries.Load(),
					"failures":                  counts.failures.Load(),
				}
				blob, _ := json.Marshal(progress)
				fmt.Printf("CALCIFY_LOAD_PROGRESS %s\n", blob)
			}
		}
	}()
	var scheduled, dispatched, dropped int64
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for scheduled < total && counts.failures.Load() == 0 {
		elapsed := time.Since(started)
		if elapsed >= cfg.Duration {
			break
		}
		due := int64(elapsed.Nanoseconds()) * int64(cfg.PairsPerSecond) / int64(time.Second)
		if due > total {
			due = total
		}
		for scheduled < due {
			select {
			case jobs <- cfg.StartIndex + scheduled:
				dispatched++
			default:
				dropped++
			}
			scheduled++
		}
		<-ticker.C
	}
	if counts.failures.Load() == 0 {
		for scheduled < total {
			select {
			case jobs <- cfg.StartIndex + scheduled:
				dispatched++
			default:
				dropped++
			}
			scheduled++
		}
	}
	close(jobs)
	workers.Wait()
	acceptedAt := time.Now()
	close(progressDone)
	<-progressStopped
	var finalReceipts, drainMs *int64
	if cfg.ReceiptGeneration > 0 && counts.failures.Load() == 0 {
		for time.Since(acceptedAt) < 60*time.Second {
			if receipts, err := receiptCount(cfg.ReceiptGeneration); err == nil {
				finalReceipts = &receipts
				if receipts >= counts.completedPairs.Load()+1 {
					value := time.Since(acceptedAt).Milliseconds()
					drainMs = &value
					break
				}
			}
			time.Sleep(250 * time.Millisecond)
		}
	}
	sampleMu.Lock()
	collected := append([]sample(nil), samples...)
	sampleMu.Unlock()
	sort.Slice(collected, func(i, j int) bool { return collected[i].ElapsedMs < collected[j].ElapsedMs })
	elapsed := acceptedAt.Sub(started)
	result := report{
		WorkloadMode: mode, OrdersPerTrade: ordersPerTrade, CompletedTrades: counts.completedPairs.Load(),
		JobAccounting: "completedTrades and legacy completedPairs count fully acknowledged trade jobs; pairs fields are legacy job counters; actual completed trades require canonical source/verified/resolved reconciliation; aggressor maker seeding occurs before load window",
		InstrumentIDs: cfg.InstrumentIDs,
		SmokeID:       cfg.SmokeID, TargetPairs: total, StartIndex: cfg.StartIndex,
		TargetPairsPerSecond: cfg.PairsPerSecond,
		ScheduledPairs:       scheduled, DispatchedPairs: dispatched, DroppedPairs: dropped,
		CompletedPairs: counts.completedPairs.Load(), AcceptedOrders: counts.acceptedOrders.Load(),
		AcceptedOrdersAtDeadline: counts.acceptedBeforeDeadline.Load(),
		DeadlineAccounting:       "validated durable HTTP acknowledgement observed strictly before fixed monotonic load-start plus requested duration; late/equal acknowledgements count only in acceptedOrders; epoch fields expose wall time from those same Go instants",
		LoadStartedEpochMs:       started.UnixMilli(),
		ScheduledDeadlineEpochMs: counts.loadDeadline.UnixMilli(),
		AcceptedAllEpochMs:       acceptedAt.UnixMilli(),
		ReceiptDrainAccounting:   "drainMs runs from HTTP worker completion before stopping the progress observer through successful receipt reconciliation; includes any outstanding observer query wait; acceptedAllEpochMs records worker completion even if requests failed",
		AcceptedOrdersPerSecond:  float64(counts.acceptedOrders.Load()) / elapsed.Seconds(),
		ElapsedMs:                elapsed.Milliseconds(), Retries: counts.retries.Load(), Failures: counts.failures.Load(),
		ReceiptGeneration: cfg.ReceiptGeneration, Samples: collected,
		FinalReceipts: finalReceipts, DrainMs: drainMs,
	}
	if value := counts.firstError.Load(); value != nil {
		result.FirstError = value.(string)
	}
	result.GapP95, result.GapMax = gapBounds(collected)
	result.GateReasons = gateReasons(result)
	result.GatePassed = len(result.GateReasons) == 0
	return result
}

func gapBounds(samples []sample) (*int64, *int64) {
	var gaps []int64
	for _, s := range samples {
		if s.Gap != nil {
			gaps = append(gaps, *s.Gap)
		}
	}
	if len(gaps) == 0 {
		return nil, nil
	}
	sort.Slice(gaps, func(i, j int) bool { return gaps[i] < gaps[j] })
	p95 := gaps[(len(gaps)*95+99)/100-1]
	max := gaps[len(gaps)-1]
	return &p95, &max
}

func gapRange(before, after, receipts int64) (int64, int64) {
	return max(int64(0), before+1-receipts), max(int64(0), after+1-receipts)
}

func gateReasons(result report) []string {
	var reasons []string
	if result.Failures != 0 || result.Retries != 0 {
		reasons = append(reasons, "request failures or retries")
	}
	_, ordersPerTrade, modeErr := workloadParameters(result.WorkloadMode, result.ReceiptGeneration)
	if modeErr != nil {
		return append(reasons, modeErr.Error())
	}
	if result.OrdersPerTrade != 0 && result.OrdersPerTrade != ordersPerTrade {
		reasons = append(reasons, "ordersPerTrade conflicts with workload mode")
	}
	if result.AcceptedOrdersAtDeadline*100 < result.TargetPairs*ordersPerTrade*95 {
		reasons = append(reasons, "accepted intake below 95% requested rate at deadline")
	}
	if result.DroppedPairs*100 > result.TargetPairs*5 {
		reasons = append(reasons, "scheduler dropped more than 5% of offered trade jobs")
	}
	if result.ReceiptGeneration > 0 {
		if result.FinalReceipts == nil || *result.FinalReceipts != result.CompletedPairs+1 {
			reasons = append(reasons, "final receipt count mismatch")
		}
		if result.DrainMs == nil || *result.DrainMs > 5000 {
			reasons = append(reasons, "receipt drain exceeded five seconds")
		}
		if result.GapP95 == nil || *result.GapP95 > int64(result.TargetPairsPerSecond*2) {
			reasons = append(reasons, "receipt gap p95 exceeded two seconds of requested trade rate")
		}
		if result.GapMax == nil || *result.GapMax > int64(result.TargetPairsPerSecond*5) {
			reasons = append(reasons, "receipt gap peak exceeded five seconds of requested trade rate")
		}
		for _, s := range result.Samples {
			if s.Error != "" {
				reasons = append(reasons, "receipt sampling failed")
				break
			}
		}
	}
	return reasons
}

func receiptCount(generation int) (int64, error) {
	query := fmt.Sprintf("SELECT COUNT(*) FROM runtime.calcify_commitment_receipts WHERE source_generation = %d", generation)
	out, err := exec.Command("docker", "exec", "reef-postgres", "psql", "-U", "reef", "-d", "reef", "-At", "-c", query).Output()
	if err != nil {
		return 0, err
	}
	return strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
}

func submitPair(client *http.Client, cfg config, index int64, counts *counters) error {
	mode, _, err := workloadParameters(cfg.WorkloadMode, cfg.ReceiptGeneration)
	if err != nil {
		return err
	}
	sides := []struct{ party, action string }{{"buyer", "BUY"}, {"seller", "SELL"}}
	if mode == "aggressor" {
		sides = sides[1:]
	}
	for _, side := range sides {
		if err := submit(client, cfg, index, side.party, side.action, counts); err != nil {
			return err
		}
	}
	counts.completedPairs.Add(1)
	return nil
}

func submit(client *http.Client, cfg config, index int64, party, side string, counts *counters) error {
	commandID := fmt.Sprintf("%s-load-%d-%s", party, index, cfg.SmokeID)
	body, err := json.Marshal(map[string]string{
		"commandId": commandID, "traceId": "trace-" + commandID,
		"causationId": "cause-" + commandID, "correlationId": "corr-" + commandID,
		"actorId": party + "-actor-" + cfg.SmokeID,
		"runId":   cfg.SmokeID, "venueSessionId": "session-" + cfg.SmokeID,
		"occurredAt":    "2026-09-29T15:00:00Z",
		"orderId":       fmt.Sprintf("%s-load-order-%d-%s", party, index, cfg.SmokeID),
		"instrumentId":  instrumentForPair(cfg, index),
		"participantId": party + "-" + cfg.SmokeID,
		"accountId":     party + "-account-" + cfg.SmokeID,
		"side":          side, "orderType": "LIMIT", "quantityUnits": "100",
		"limitPrice": "150250000000", "currency": "USD", "timeInForce": "DAY",
	})
	if err != nil {
		return err
	}
	for attempt := range 4 {
		req, err := http.NewRequest(http.MethodPost, cfg.BaseURL+"/api/v1/orders/submit", bytes.NewReader(body))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Client-Id", party+"-client-"+cfg.SmokeID)
		req.Header.Set("Idempotency-Key", fmt.Sprintf("%s-load-idem-%d-%s", party, index, cfg.SmokeID))
		resp, err := client.Do(req)
		if err != nil {
			if attempt == 3 {
				return fmt.Errorf("%s transport: %w", commandID, err)
			}
			counts.retries.Add(1)
			time.Sleep(time.Duration(attempt+1) * 25 * time.Millisecond)
			continue
		}
		responseBody, readErr := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		if readErr != nil {
			return fmt.Errorf("%s response: %w", commandID, readErr)
		}
		if resp.StatusCode != http.StatusAccepted {
			return fmt.Errorf("%s status %d: %s", commandID, resp.StatusCode, responseBody)
		}
		var ack struct {
			Accepted bool   `json:"accepted"`
			Status   string `json:"status"`
		}
		if err := json.Unmarshal(responseBody, &ack); err != nil {
			return fmt.Errorf("%s invalid acknowledgement: %w", commandID, err)
		}
		if !ack.Accepted && !strings.EqualFold(ack.Status, "accepted") {
			return errors.New(commandID + " was not accepted")
		}
		observedAt := time.Now()
		if counts.now != nil {
			observedAt = counts.now()
		}
		counts.recordAcknowledgement(observedAt)
		return nil
	}
	return errors.New(commandID + " exhausted retries")
}

// parseInstrumentIDs bounds explicit load routing before issuing HTTP work.
// Default preserves existing seeded smoke instrument and command identities.
func parseInstrumentIDs(raw, smokeID string) ([]string, error) {
	if raw == "" {
		return []string{"AAPL-" + smokeID}, nil
	}
	ids := strings.Split(raw, ",")
	if len(ids) > 64 {
		return nil, errors.New("instrument-ids allows at most 64 IDs")
	}
	seen := make(map[string]bool, len(ids))
	for i, id := range ids {
		id = strings.TrimSpace(id)
		if id == "" || len(id) > 128 {
			return nil, errors.New("instrument-ids requires nonempty IDs of at most 128 UTF-8 bytes")
		}
		if seen[id] {
			return nil, fmt.Errorf("instrument-ids repeats %q", id)
		}
		seen[id] = true
		ids[i] = id
	}
	return ids, nil
}

func instrumentForPair(cfg config, index int64) string {
	if len(cfg.InstrumentIDs) == 0 {
		return "AAPL-" + cfg.SmokeID
	}
	return cfg.InstrumentIDs[index%int64(len(cfg.InstrumentIDs))]
}

func workloadParameters(mode string, receiptGeneration int) (string, int64, error) {
	switch mode {
	case "", "paired":
		return "paired", 2, nil
	case "aggressor":
		if receiptGeneration != 0 {
			return "", 0, errors.New("aggressor mode requires receipt-generation=0; legacy receipt gate includes paired preflight")
		}
		return "aggressor", 1, nil
	default:
		return "", 0, fmt.Errorf("unsupported workload-mode %q; use paired or aggressor", mode)
	}
}
