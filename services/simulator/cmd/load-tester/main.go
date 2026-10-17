package main

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"
)

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
	traceSeen := newTraceSampler(cfg.TraceCheckLimit)
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
			runWorker(ctx, client, cfg, sessionID, id, workerProfile, rateCh, results, traceSeen)
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
	report.TraceChecks = runTraceChecks(client, cfg, traceSeen)
	printSummary(report)
	if cfg.ReportOut != "" {
		if err := writeReport(cfg.ReportOut, report); err != nil {
			fmt.Fprintf(os.Stderr, "failed to write report: %v\n", err)
			os.Exit(1)
		}
	}
}
