package streamdirect

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func TestVenueEventBatchChecksumCoversSemanticOutcomeAndIgnoresCreatedAt(t *testing.T) {
	batch := checksumTestBatch()
	first, err := venueEventBatchChecksum(batch)
	if err != nil {
		t.Fatalf("checksum failed: %v", err)
	}

	batch.CreatedAt = "2099-01-01T00:00:00Z"
	second, err := venueEventBatchChecksum(batch)
	if err != nil {
		t.Fatalf("checksum with changed creation time failed: %v", err)
	}
	if first != second {
		t.Fatalf("volatile creation time changed checksum: %s != %s", first, second)
	}

	batch.Outcomes[0].Status = "rejected"
	batch.Outcomes[0].Result = domain.SubmitOrderResult{
		Rejected: &domain.OrderRejected{Code: "DUPLICATE_ORDER_ID", Reason: "duplicate"},
	}
	conflicting, err := venueEventBatchChecksum(batch)
	if err != nil {
		t.Fatalf("checksum with conflicting result failed: %v", err)
	}
	if first == conflicting {
		t.Fatal("semantic outcome change did not change checksum")
	}
}

func TestVenueEventBatchChecksumCrossLanguageVector(t *testing.T) {
	checksum, err := venueEventBatchChecksum(checksumTestBatch())
	if err != nil {
		t.Fatalf("checksum failed: %v", err)
	}
	const expected = "83bf9c8f68dfe9eff49e35578ae3e20f3b4b4b4feb08f5636a677bcf9be9da7c"
	if checksum != expected {
		t.Fatalf("checksum vector changed: got %s want %s", checksum, expected)
	}
}

func checksumTestBatch() VenueEventBatch {
	return VenueEventBatch{
		BatchID:       "engine-0-p2-101-101",
		ShardID:       "engine-0",
		Partition:     2,
		CommandStream: "REEF_COMMANDS",
		EventStream:   "REEF_VENUE_EVENTS",
		FirstSequence: 101,
		LastSequence:  101,
		CommandCount:  1,
		CreatedAt:     "2026-07-19T12:00:00Z",
		ChecksumAlgo:  venueEventBatchChecksumAlgorithm,
		Outcomes: []CommandOutcomeFact{
			{
				CommandID:      "cmd-1",
				CommandType:    "SubmitOrder",
				StreamSequence: 101,
				DeliveredCount: 1,
				PayloadHash:    "payload-hash-1",
				InstrumentID:   "AAPL",
				OrderID:        "ord-1",
				Status:         "accepted",
				Result: domain.SubmitOrderResult{
					Accepted: &domain.OrderAccepted{
						EventID:       "evt-1",
						OrderID:       "ord-1",
						EngineOrderID: "eng-1",
						OccurredAt:    "2026-07-19T12:00:00Z",
					},
				},
			},
		},
	}
}

func TestVenueEventBatchChecksumIgnoresRetryTiming(t *testing.T) {
	batch := checksumTestBatch()
	batch.WorkFinishedAt = "2026-09-24T00:00:00Z"
	first, err := venueEventBatchChecksum(batch)
	if err != nil {
		t.Fatal(err)
	}
	batch.WorkFinishedAt = "2026-09-24T00:00:01Z"
	second, err := venueEventBatchChecksum(batch)
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatal("same semantic batch changes checksum on retry")
	}
}

func TestCanonicalWriterMatchesReferenceNestedAndOverflow(t *testing.T) {
	wide := make(map[string]any)
	for i := 0; i < 256; i++ {
		wide[fmt.Sprintf("field-%03d", i)] = map[string]any{"repeat": json.Number("1e+04"), "nested": []any{nil, true, false, "é雪🙂\x00", json.Number("-0.00")}}
	}
	wide[strings.Repeat("界", 43)] = "UTF-8 field longer than 128 bytes"
	wide[strings.Repeat("x", 128)] = strings.Repeat("界🙂\x00", 100)
	fixtures := []any{nil, true, false, "", "雪\x00🙂", json.Number("-0"), json.Number("1.00e+02"), []any{}, map[string]any{}, []any{wide, wide, map[string]any{"repeat": "again", "a": []any{json.Number("0"), json.Number("0.0"), json.Number("0e0")}}}}
	for i, fixture := range fixtures {
		actual, expected := sha256.New(), sha256.New()
		if err := writeCanonicalValue(actual, fixture); err != nil {
			t.Fatal(err)
		}
		if err := referenceWriteCanonicalValue(expected, fixture); err != nil {
			t.Fatal(err)
		}
		if fmt.Sprintf("%x", actual.Sum(nil)) != fmt.Sprintf("%x", expected.Sum(nil)) {
			t.Fatalf("fixture%d differs from reference", i)
		}
	}
}

func TestVenueEventBatchChecksum200MatchesReference(t *testing.T) {
	batch := checksumBenchmarkBatch()
	actual, err := venueEventBatchChecksum(batch)
	if err != nil {
		t.Fatal(err)
	}
	expected, err := referenceVenueEventBatchChecksum(batch)
	if err != nil {
		t.Fatal(err)
	}
	if actual != expected {
		t.Fatalf("full outcome fixture checksum=%s reference=%s", actual, expected)
	}
}

func TestCanonicalWriterReducesAllocations(t *testing.T) {
	// Relative to frozen reference, protect allocation reduction independently
	// of full JSON decode allocations and changes in absolute compiler counts.
	value := map[string]any{"outcomes": make([]any, 200)}
	for i := range value["outcomes"].([]any) {
		value["outcomes"].([]any)[i] = map[string]any{"orderId": "order-123", "status": "accepted", "result": map[string]any{"quantityUnits": "100", "executions": []any{true, nil, json.Number("1.00")}}}
	}
	actual := testing.AllocsPerRun(3, func() {
		if err := writeCanonicalValue(sha256.New(), value); err != nil {
			panic(err)
		}
	})
	reference := testing.AllocsPerRun(3, func() {
		if err := referenceWriteCanonicalValue(sha256.New(), value); err != nil {
			panic(err)
		}
	})
	if actual > reference/2 {
		t.Fatalf("canonical allocations=%g exceeds half reference=%g", actual, reference)
	}
}

func TestCanonicalFieldMemoStaysBounded(t *testing.T) {
	writer := canonicalWriter{digest: sha256.New()}
	for i := 0; i < 256; i++ {
		writer.field(fmt.Sprintf("field-%03d", i))
	}
	// Cardinality and UTF-8 byte size constrain per-call memo memory even when
	// object names come from dynamic input rather than a fixed typed model.
	if len(writer.fields) != canonicalFieldMemoLimit {
		t.Fatalf("cached names=%d want%d", len(writer.fields), canonicalFieldMemoLimit)
	}
	for name, token := range writer.fields {
		if len(name) > canonicalFieldMemoMaxBytes || len(token) > canonicalFieldMemoMaxBytes+24 {
			t.Fatalf("oversized cached field bytes=%d token=%d", len(name), len(token))
		}
	}
	boundary := canonicalWriter{digest: sha256.New()}
	exact, over := strings.Repeat("x", 128), strings.Repeat("界", 43)
	boundary.field(exact)
	boundary.field(over)
	if _, ok := boundary.fields[exact]; !ok {
		t.Fatal("128-byte field not cached")
	}
	if _, ok := boundary.fields[over]; ok {
		t.Fatal("129-byte UTF-8 field cached")
	}
}

func TestCapturedVenueEventBatchChecksumMatchesWireGolden(t *testing.T) {
	if os.Getenv("REEF_CHECKSUM_BENCH_FIXTURE") == "" {
		t.Skip("set REEF_CHECKSUM_BENCH_FIXTURE for captured wire fixture")
	}
	_ = checksumSelectedFixture(t)
}
