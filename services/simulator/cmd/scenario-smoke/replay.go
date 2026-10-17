package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

func attachReplayChecksumEvidence(cfg config, report *smokeReport) {
	prefix := replayAssertionPrefix(report)
	body, err := os.ReadFile(cfg.replayCheckReportPath)
	if err != nil {
		failAssertion(report, prefix+"-checksum-clean", "replay_checksum", "readable replay check report", err.Error(), cfg.replayCheckReportPath)
		return
	}
	var parsed map[string]any
	if err := json.Unmarshal(body, &parsed); err != nil {
		failAssertion(report, prefix+"-checksum-clean", "replay_checksum", "valid replay check JSON", err.Error(), cfg.replayCheckReportPath)
		return
	}
	if report.PathID == "P1_GOLDEN_HIDDEN_CROSS_T1" {
		attachP1VisibilityTimelineToReplayChecksum(report, parsed)
	}
	report.ReplayChecksum = parsed
	report.ArtifactPaths = append(report.ArtifactPaths, cfg.replayCheckReportPath)
	pass, _ := parsed["pass"].(bool)
	failures := replayFailures(parsed)
	if !pass || len(failures) > 0 {
		observed := "pass false"
		if len(failures) > 0 {
			observed = strings.Join(failures, "; ")
		}
		failAssertion(report, prefix+"-checksum-clean", "replay_checksum", "pass true and no replay failures", observed, cfg.replayCheckReportPath)
		return
	}
	passAssertion(report, prefix+"-checksum-clean", "pass true and no replay failures", "pass true and no replay failures", cfg.replayCheckReportPath)
	if cfg.requireReplayCheck {
		assertReplayChecksumCounters(report, parsed, cfg.replayCheckReportPath, prefix, replayCounterMinimums(report))
	}
	if cfg.requireReplayCheck && report.PathID == "P1_GOLDEN_HIDDEN_CROSS_T1" {
		assertP1HistoricalVisibilityProof(report, parsed, cfg.replayCheckReportPath)
	}
}

func replayAssertionPrefix(report *smokeReport) string {
	switch report.PathID {
	case "P1_GOLDEN_HIDDEN_CROSS_T1":
		return "p1-replay"
	case "P2_SETTLEMENT_BREAK_REPAIR":
		return "p2-replay"
	default:
		return "replay"
	}
}

func replayFailures(parsed map[string]any) []string {
	raw, ok := parsed["failures"].([]any)
	if !ok {
		return nil
	}
	failures := make([]string, 0, len(raw))
	for _, item := range raw {
		if text, ok := item.(string); ok {
			failures = append(failures, text)
		}
	}
	return failures
}

func assertP1HistoricalVisibilityProof(report *smokeReport, parsed map[string]any, proofSource string) {
	visibility, ok := parsed["visibilityTimeline"].(map[string]any)
	if !ok {
		failAssertion(report, "p1-replay-hidden-depth-timeline-proof", "replay_visibility_timeline", "visibilityTimeline.publicDepthHiddenRestingExposed=false", "missing visibilityTimeline object", proofSource)
		return
	}
	exposed, found := visibility["publicDepthHiddenRestingExposed"].(bool)
	if !found {
		failAssertion(report, "p1-replay-hidden-depth-timeline-proof", "replay_visibility_timeline", "visibilityTimeline.publicDepthHiddenRestingExposed=false", "missing publicDepthHiddenRestingExposed boolean", proofSource)
		return
	}
	checkCount := replayVisibilityCheckCount(visibility)
	if !exposed && checkCount > 0 {
		passAssertion(report, "p1-replay-hidden-depth-timeline-proof", "hidden resting size never exposed in replayed public depth checks", fmt.Sprintf("%d public depth checks, exposed=false", checkCount), proofSource)
		return
	}
	observed := fmt.Sprintf("%d public depth checks, exposed=%t", checkCount, exposed)
	failAssertion(report, "p1-replay-hidden-depth-timeline-proof", "replay_visibility_timeline", "hidden resting size never exposed in replayed public depth checks", observed, proofSource)
}

func replayVisibilityCheckCount(visibility map[string]any) int {
	raw, ok := visibility["publicDepthChecks"].([]any)
	if !ok {
		return 0
	}
	return len(raw)
}

func attachP1VisibilityTimelineToReplayChecksum(report *smokeReport, parsed map[string]any) {
	if _, found := parsed["visibilityTimeline"]; found || report.VisibilityTimeline == nil {
		return
	}
	encoded, err := json.Marshal(report.VisibilityTimeline)
	if err != nil {
		return
	}
	var timeline map[string]any
	if err := json.Unmarshal(encoded, &timeline); err != nil {
		return
	}
	parsed["visibilityTimeline"] = timeline
}

func assertP1HistoricalVisibilityTimeline(report *smokeReport) {
	proofSource := "/api/v1/market-data/depth/XYZ"
	if report.VisibilityTimeline == nil {
		failAssertion(report, "p1-hidden-depth-timeline-proof", "public_depth_visibility_timeline", "captured pre-execution public depth check with hidden resting size not visible", "missing visibilityTimeline object", proofSource)
		return
	}
	checks := report.VisibilityTimeline.PublicDepthChecks
	readErrors := p1VisibilityReadErrors(checks)
	if len(checks) > 0 && len(readErrors) == 0 && !report.VisibilityTimeline.PublicDepthHiddenRestingExposed {
		passAssertion(report, "p1-hidden-depth-timeline-proof", "hidden resting size not visible before first execution", fmt.Sprintf("%d public depth checks, exposed=false", len(checks)), proofSource)
		return
	}
	observed := fmt.Sprintf("%d public depth checks, exposed=%t", len(checks), report.VisibilityTimeline.PublicDepthHiddenRestingExposed)
	if len(readErrors) > 0 {
		observed += ", errors=" + strings.Join(readErrors, "; ")
	}
	failAssertion(report, "p1-hidden-depth-timeline-proof", "public_depth_visibility_timeline", "hidden resting size not visible before first execution", observed, proofSource)
}

func p1VisibilityReadErrors(checks []p1PublicDepthCheck) []string {
	out := make([]string, 0)
	for _, check := range checks {
		if strings.TrimSpace(check.Error) != "" {
			out = append(out, check.Error)
		}
	}
	return out
}

func replayCounterMinimums(report *smokeReport) map[string]float64 {
	commandCount := float64(len(report.Requests))
	if commandCount <= 0 {
		commandCount = 1
	}
	return map[string]float64{
		"batchCount":            1,
		"storedCommandCount":    commandCount,
		"payloadOutcomeCount":   commandCount,
		"canonicalOutcomeCount": commandCount,
	}
}

func assertReplayChecksumCounters(report *smokeReport, parsed map[string]any, proofSource string, prefix string, minimums map[string]float64) {
	replayReport, ok := parsed["report"].(map[string]any)
	if !ok {
		failAssertion(report, prefix+"-counter-report-present", "replay_checksum", "replay report counters", "missing report object", proofSource)
		return
	}
	for key, minimum := range minimums {
		value, found := replayCounter(replayReport, key)
		if found && value >= minimum {
			passAssertion(report, prefix+"-"+kebabCase(key), fmt.Sprintf(">= %.0f", minimum), fmt.Sprintf("%.0f", value), proofSource)
		} else {
			observed := "missing"
			if found {
				observed = fmt.Sprintf("%.0f", value)
			}
			failAssertion(report, prefix+"-"+kebabCase(key), "replay_checksum", fmt.Sprintf(">= %.0f", minimum), observed, proofSource)
		}
	}
	zeroCounters := []string{
		"duplicateReplayInserted",
		"checksumMismatchCount",
		"batchCommandCountMismatchCount",
		"payloadHashMismatchCount",
		"missingOutcomeCount",
		"extraOutcomeCount",
		"streamGapCount",
		"streamOverlapCount",
		"watermarkLagCount",
	}
	for _, key := range zeroCounters {
		value, found := replayCounter(replayReport, key)
		if found && value == 0 {
			passAssertion(report, prefix+"-"+kebabCase(key), "0", "0", proofSource)
		} else {
			observed := "missing"
			if found {
				observed = fmt.Sprintf("%.0f", value)
			}
			failAssertion(report, prefix+"-"+kebabCase(key), "replay_checksum", "0", observed, proofSource)
		}
	}
}

func replayCounter(report map[string]any, key string) (float64, bool) {
	value, ok := report[key]
	if !ok {
		return 0, false
	}
	switch typed := value.(type) {
	case float64:
		return typed, true
	case int:
		return float64(typed), true
	case json.Number:
		number, err := typed.Float64()
		return number, err == nil
	default:
		return 0, false
	}
}

func kebabCase(value string) string {
	var out strings.Builder
	for index, char := range value {
		if index > 0 && char >= 'A' && char <= 'Z' {
			out.WriteByte('-')
		}
		out.WriteRune(char)
	}
	return strings.ToLower(out.String())
}
