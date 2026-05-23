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
