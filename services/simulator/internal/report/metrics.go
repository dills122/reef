package report

import "sort"

type LatencySummary struct {
	Min float64 `json:"min"`
	P50 float64 `json:"p50"`
	P95 float64 `json:"p95"`
	P99 float64 `json:"p99"`
	Max float64 `json:"max"`
}

type ErrorSummary struct {
	Error string `json:"error"`
	Count int64  `json:"count"`
}

type RejectTaxonomySummary struct {
	Code              string  `json:"code"`
	Count             int64   `json:"count"`
	PercentOfFailures float64 `json:"percentOfFailures"`
	PercentOfRejects  float64 `json:"percentOfRejects"`
}

type QualitySummary struct {
	TotalRequests             int64    `json:"totalRequests"`
	TotalSuccess              int64    `json:"totalSuccess"`
	TotalFailures             int64    `json:"totalFailures"`
	InvalidIntentRejectCodes  []string `json:"invalidIntentRejectCodes"`
	InvalidIntentRejectCount  int64    `json:"invalidIntentRejectCount"`
	SystemFailureCount        int64    `json:"systemFailureCount"`
	EndToEndSuccessRatePct    float64  `json:"endToEndSuccessRatePct"`
	ValidIntentSuccessRatePct float64  `json:"validIntentSuccessRatePct"`
	InvalidIntentRatePct      float64  `json:"invalidIntentRatePct"`
	SystemFailureRatePct      float64  `json:"systemFailureRatePct"`
}

type ThroughputSummary struct {
	AttemptedPerSecond float64 `json:"attemptedPerSecond"`
	AcceptedPerSecond  float64 `json:"acceptedPerSecond"`
	CompletedPerSecond float64 `json:"completedPerSecond,omitempty"`
	ProjectedPerSecond float64 `json:"projectedPerSecond,omitempty"`
	VisiblePerSecond   float64 `json:"visiblePerSecond,omitempty"`
}

func ComputeThroughput(totalRequests, accepted, completed int64, durationSeconds float64) ThroughputSummary {
	if durationSeconds <= 0 {
		return ThroughputSummary{}
	}
	out := ThroughputSummary{
		AttemptedPerSecond: float64(totalRequests) / durationSeconds,
		AcceptedPerSecond:  float64(accepted) / durationSeconds,
	}
	if completed > 0 {
		out.CompletedPerSecond = float64(completed) / durationSeconds
	}
	return out
}

func ComputeLatency(values []float64) LatencySummary {
	if len(values) == 0 {
		return LatencySummary{}
	}
	sorted := append([]float64(nil), values...)
	sort.Float64s(sorted)
	return LatencySummary{
		Min: sorted[0],
		P50: percentile(sorted, 0.50),
		P95: percentile(sorted, 0.95),
		P99: percentile(sorted, 0.99),
		Max: sorted[len(sorted)-1],
	}
}

func TopErrors(m map[string]int64, limit int) []ErrorSummary {
	out := make([]ErrorSummary, 0, len(m))
	for k, v := range m {
		out = append(out, ErrorSummary{Error: k, Count: v})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Count == out[j].Count {
			return out[i].Error < out[j].Error
		}
		return out[i].Count > out[j].Count
	})
	if len(out) > limit {
		return out[:limit]
	}
	return out
}

func SummarizeRejectTaxonomy(rejectCodes map[string]int64, totalFailures int64, totalRejects int64, limit int) []RejectTaxonomySummary {
	if len(rejectCodes) == 0 {
		return nil
	}
	keys := make([]string, 0, len(rejectCodes))
	for code := range rejectCodes {
		keys = append(keys, code)
	}
	sort.Slice(keys, func(i, j int) bool {
		left := rejectCodes[keys[i]]
		right := rejectCodes[keys[j]]
		if left == right {
			return keys[i] < keys[j]
		}
		return left > right
	})
	if len(keys) > limit {
		keys = keys[:limit]
	}
	out := make([]RejectTaxonomySummary, 0, len(keys))
	for _, code := range keys {
		count := rejectCodes[code]
		row := RejectTaxonomySummary{Code: code, Count: count}
		if totalFailures > 0 {
			row.PercentOfFailures = (float64(count) / float64(totalFailures)) * 100
		}
		if totalRejects > 0 {
			row.PercentOfRejects = (float64(count) / float64(totalRejects)) * 100
		}
		out = append(out, row)
	}
	return out
}

func ComputeQuality(totalRequests, totalSuccess, totalFailures int64, rejectCodes map[string]int64, invalidIntentCodes []string) QualitySummary {
	normalizedInvalidCodes := normalizeCodes(invalidIntentCodes)
	invalidCodeSet := make(map[string]struct{}, len(normalizedInvalidCodes))
	for _, code := range normalizedInvalidCodes {
		invalidCodeSet[code] = struct{}{}
	}

	invalidIntentRejects := int64(0)
	for code, count := range rejectCodes {
		if _, ok := invalidCodeSet[code]; ok {
			invalidIntentRejects += count
		}
	}
	systemFailures := totalFailures - invalidIntentRejects
	if systemFailures < 0 {
		systemFailures = 0
	}
	validIntentRequests := totalRequests - invalidIntentRejects

	row := QualitySummary{
		TotalRequests:            totalRequests,
		TotalSuccess:             totalSuccess,
		TotalFailures:            totalFailures,
		InvalidIntentRejectCodes: normalizedInvalidCodes,
		InvalidIntentRejectCount: invalidIntentRejects,
		SystemFailureCount:       systemFailures,
	}
	if totalRequests > 0 {
		row.EndToEndSuccessRatePct = (float64(totalSuccess) / float64(totalRequests)) * 100
		row.InvalidIntentRatePct = (float64(invalidIntentRejects) / float64(totalRequests)) * 100
		row.SystemFailureRatePct = (float64(systemFailures) / float64(totalRequests)) * 100
	}
	if validIntentRequests > 0 {
		row.ValidIntentSuccessRatePct = (float64(totalSuccess) / float64(validIntentRequests)) * 100
	}
	return row
}

func percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	if p <= 0 {
		return sorted[0]
	}
	if p >= 1 {
		return sorted[len(sorted)-1]
	}
	idx := int(float64(len(sorted)-1) * p)
	return sorted[idx]
}

func normalizeCodes(codes []string) []string {
	if len(codes) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(codes))
	out := make([]string, 0, len(codes))
	for _, code := range codes {
		if code == "" {
			continue
		}
		if _, ok := seen[code]; ok {
			continue
		}
		seen[code] = struct{}{}
		out = append(out, code)
	}
	sort.Strings(out)
	return out
}
