package report

import "testing"

func TestComputeLatency(t *testing.T) {
	s := ComputeLatency([]float64{10, 30, 20, 40})
	if s.Min != 10 || s.Max != 40 {
		t.Fatalf("unexpected min/max: %+v", s)
	}
	if s.P50 <= 0 || s.P95 <= 0 || s.P99 <= 0 {
		t.Fatalf("expected non-zero percentile values: %+v", s)
	}
}

func TestComputeLatencyEmpty(t *testing.T) {
	s := ComputeLatency(nil)
	if s.Min != 0 || s.Max != 0 || s.P50 != 0 || s.P95 != 0 || s.P99 != 0 {
		t.Fatalf("expected zero-value summary for empty input, got %+v", s)
	}
}

func TestComputeThroughputUsesCanonicalNames(t *testing.T) {
	row := ComputeThroughput(100, 90, 80, 10)
	if row.AttemptedPerSecond != 10 {
		t.Fatalf("unexpected attempted/sec: %+v", row)
	}
	if row.AcceptedPerSecond != 9 {
		t.Fatalf("unexpected accepted/sec: %+v", row)
	}
	if row.CompletedPerSecond != 8 {
		t.Fatalf("unexpected completed/sec: %+v", row)
	}
}

func TestSummarizeRejectTaxonomy(t *testing.T) {
	rows := SummarizeRejectTaxonomy(map[string]int64{"INVALID_STATE": 3, "NOT_FOUND": 1}, 5, 4, 10)
	if len(rows) != 2 {
		t.Fatalf("expected 2 rows, got %d", len(rows))
	}
	if rows[0].Code != "INVALID_STATE" || rows[0].Count != 3 {
		t.Fatalf("unexpected first row: %+v", rows[0])
	}
}

func TestSummarizeRejectTaxonomyNonPositiveLimit(t *testing.T) {
	rows := SummarizeRejectTaxonomy(map[string]int64{"INVALID_STATE": 3}, 5, 4, 0)
	if rows != nil {
		t.Fatalf("expected nil rows for zero limit, got %+v", rows)
	}
	rows = SummarizeRejectTaxonomy(map[string]int64{"INVALID_STATE": 3}, 5, 4, -1)
	if rows != nil {
		t.Fatalf("expected nil rows for negative limit, got %+v", rows)
	}
}

func TestTopErrorsSortsByCountThenName(t *testing.T) {
	rows := TopErrors(map[string]int64{"B_ERR": 2, "A_ERR": 2, "C_ERR": 5}, 10)
	if len(rows) != 3 {
		t.Fatalf("expected 3 rows, got %d", len(rows))
	}
	if rows[0].Error != "C_ERR" || rows[0].Count != 5 {
		t.Fatalf("expected highest count first, got %+v", rows[0])
	}
	if rows[1].Error != "A_ERR" || rows[2].Error != "B_ERR" {
		t.Fatalf("expected tie broken alphabetically, got %+v", rows[1:])
	}
}

func TestTopErrorsAppliesLimit(t *testing.T) {
	rows := TopErrors(map[string]int64{"A": 1, "B": 2, "C": 3}, 2)
	if len(rows) != 2 {
		t.Fatalf("expected limit of 2 rows, got %d", len(rows))
	}
	if rows[0].Error != "C" || rows[1].Error != "B" {
		t.Fatalf("expected top-2 by count, got %+v", rows)
	}
}

func TestTopErrorsNonPositiveLimit(t *testing.T) {
	rows := TopErrors(map[string]int64{"A": 1}, 0)
	if rows != nil {
		t.Fatalf("expected nil rows for zero limit, got %+v", rows)
	}
	rows = TopErrors(map[string]int64{"A": 1}, -1)
	if rows != nil {
		t.Fatalf("expected nil rows for negative limit, got %+v", rows)
	}
}

func TestTopErrorsEmptyMap(t *testing.T) {
	rows := TopErrors(map[string]int64{}, 10)
	if len(rows) != 0 {
		t.Fatalf("expected no rows for empty map, got %+v", rows)
	}
}

func TestComputeQualitySeparatesInvalidIntentFromSystemFailures(t *testing.T) {
	row := ComputeQuality(
		100,
		90,
		10,
		map[string]int64{"INVALID_STATE": 7, "ABUSE_BLOCKED": 2, "VALIDATION_ERROR": 1},
		[]string{"VALIDATION_ERROR", "INVALID_STATE", "INVALID_STATE"},
	)
	if row.InvalidIntentRejectCount != 8 {
		t.Fatalf("expected 8 invalid-intent rejects, got %+v", row)
	}
	if row.SystemFailureCount != 2 {
		t.Fatalf("expected 2 system failures, got %+v", row)
	}
	if row.EndToEndSuccessRatePct != 90 {
		t.Fatalf("unexpected end-to-end success rate: %+v", row)
	}
	if row.ValidIntentSuccessRatePct < 97.82 || row.ValidIntentSuccessRatePct > 97.83 {
		t.Fatalf("unexpected valid-intent success rate: %+v", row)
	}
	if row.InvalidIntentRatePct != 8 {
		t.Fatalf("unexpected invalid-intent rate: %+v", row)
	}
	if row.SystemFailureRatePct != 2 {
		t.Fatalf("unexpected system failure rate: %+v", row)
	}
	if len(row.InvalidIntentRejectCodes) != 2 || row.InvalidIntentRejectCodes[0] != "INVALID_STATE" || row.InvalidIntentRejectCodes[1] != "VALIDATION_ERROR" {
		t.Fatalf("expected sorted unique invalid-intent codes, got %+v", row.InvalidIntentRejectCodes)
	}
}
