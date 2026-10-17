package main

import (
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
	"time"
)

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
	if traceChecksSkipped(report) {
		fmt.Printf("  skipped (trace-check-limit=%d)\n", report.Config.TraceCheckLimit)
	} else {
		fmt.Printf("  checked=%d pass=%d fail=%d\n", report.TraceChecks.Checked, report.TraceChecks.Pass, report.TraceChecks.Fail)
		fmt.Printf("  pass-rate=%.2f%%\n", tracePassRate)
		if len(report.TraceChecks.FailedTraceID) > 0 {
			limit := len(report.TraceChecks.FailedTraceID)
			if limit > 5 {
				limit = 5
			}
			fmt.Printf("  failed trace sample: %s\n", strings.Join(report.TraceChecks.FailedTraceID[:limit], ", "))
		}
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

func traceChecksSkipped(report summary) bool {
	return report.Config.TraceCheckLimit <= 0 &&
		report.TraceChecks.Checked == 0 &&
		report.TraceChecks.Pass == 0 &&
		report.TraceChecks.Fail == 0 &&
		len(report.TraceChecks.FailedTraceID) == 0
}

func topProfileKeys(values map[string]profileSummary, limit int) []string {
	if limit <= 0 {
		return nil
	}
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
