package main

import "time"

// buildSummary is a test-only convenience over summaryAccumulator.
func buildSummary(sessionID string, started, finished time.Time, cfg Config, results []requestResult, loadSchedule loadScheduleSummary) summary {
	accumulator := newSummaryAccumulator(len(results))
	for _, result := range results {
		accumulator.add(result)
	}
	return accumulator.build(sessionID, started, finished, cfg, loadSchedule)
}
