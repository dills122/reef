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
