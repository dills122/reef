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

func TestSummarizeRejectTaxonomy(t *testing.T) {
	rows := SummarizeRejectTaxonomy(map[string]int64{"INVALID_STATE": 3, "NOT_FOUND": 1}, 5, 4, 10)
	if len(rows) != 2 {
		t.Fatalf("expected 2 rows, got %d", len(rows))
	}
	if rows[0].Code != "INVALID_STATE" || rows[0].Count != 3 {
		t.Fatalf("unexpected first row: %+v", rows[0])
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
