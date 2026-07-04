package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/loadtest"
)

func main() {
	cfg := loadtest.Config{}
	var duration string

	flag.StringVar(&cfg.RunID, "run-id", "", "run identifier used for artifact directory naming")
	flag.StringVar(&cfg.Scenario, "scenario", loadtest.ScenarioAlternatingCross, "load scenario: alternating-cross, resting-book, lifecycle, deep-lifecycle")
	flag.IntVar(&cfg.RatePerSecond, "rate", 10000, "target attempted commands per second")
	flag.StringVar(&duration, "duration", "30s", "sustained load duration, for example 30s, 5m, 1h")
	flag.IntVar(&cfg.Workers, "workers", 1, "engine client workers; use 1 for deterministic single-lane runs")
	flag.IntVar(&cfg.Instruments, "instruments", 1, "number of instruments/books to distribute commands across")
	flag.StringVar(&cfg.OutputDir, "output-dir", "../../reports/matching-engine-load", "base directory for benchmark artifacts")
	flag.BoolVar(&cfg.RecordResults, "record-results", false, "write per-command result records to results.ndjson")
	flag.IntVar(&cfg.MaxRecordedResults, "max-recorded-results", 0, "maximum result records to write; 0 means unlimited")
	flag.Float64Var(&cfg.MinProcessedRate, "min-processed-rate", 0, "fail when processed commands per second is below this value; 0 disables the check")
	flag.Parse()

	parsedDuration, err := time.ParseDuration(duration)
	if err != nil {
		log.Fatalf("invalid duration: %v", err)
	}
	cfg.Duration = parsedDuration

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	report, err := loadtest.Run(ctx, app.NewService(), cfg)
	if encodeErr := printReport(report); encodeErr != nil {
		log.Fatalf("failed to print report: %v", encodeErr)
	}
	if err != nil {
		log.Fatal(err)
	}
}

func printReport(report loadtest.Report) error {
	encoded, err := json.MarshalIndent(struct {
		RunID                string                  `json:"runId"`
		Scenario             string                  `json:"scenario"`
		Attempted            int64                   `json:"attempted"`
		Processed            int64                   `json:"processed"`
		Accepted             int64                   `json:"accepted"`
		Rejected             int64                   `json:"rejected"`
		SystemFailures       int64                   `json:"systemFailures"`
		Trades               int64                   `json:"trades"`
		Executions           int64                   `json:"executions"`
		AttemptedPerSecond   float64                 `json:"attemptedPerSecond"`
		ProcessedPerSecond   float64                 `json:"processedPerSecond"`
		AcceptedPerSecond    float64                 `json:"acceptedPerSecond"`
		LatencyMicros        loadtest.LatencySummary `json:"latencyMicros"`
		Artifacts            map[string]string       `json:"artifacts"`
		PassedMinRate        bool                    `json:"passedMinRate"`
		MinRateFailureReason string                  `json:"minRateFailureReason,omitempty"`
	}{
		RunID:                report.RunID,
		Scenario:             report.Config.Scenario,
		Attempted:            report.Attempted,
		Processed:            report.Processed,
		Accepted:             report.Accepted,
		Rejected:             report.Rejected,
		SystemFailures:       report.SystemFailures,
		Trades:               report.Trades,
		Executions:           report.Executions,
		AttemptedPerSecond:   report.AttemptedPerSecond,
		ProcessedPerSecond:   report.ProcessedPerSecond,
		AcceptedPerSecond:    report.AcceptedPerSecond,
		LatencyMicros:        report.LatencyMicros,
		Artifacts:            report.Artifacts,
		PassedMinRate:        report.PassedMinRate,
		MinRateFailureReason: report.MinRateFailureReason,
	}, "", "  ")
	if err != nil {
		return err
	}
	fmt.Println(string(encoded))
	return nil
}
