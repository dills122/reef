package streamdirect

import (
	"encoding/json"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func TestCanonicalEffectsDoNotChangeDirectEngineResultJSON(t *testing.T) {
	result := domain.SubmitOrderResult{
		EffectVersion: 1,
		Accepted:      &domain.OrderAccepted{EventID: "accepted", OrderID: "order-1"},
		OrderStates:   []domain.OrderState{{OrderID: "order-1"}},
	}
	directJSON, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	if string(directJSON) != `{"accepted":{"eventId":"accepted","orderId":"order-1","engineOrderId":"","occurredAt":""}}` {
		t.Fatalf("direct engine response changed: %s", directJSON)
	}
	canonicalJSON, err := json.Marshal(canonicalOutcomeResult(result))
	if err != nil {
		t.Fatal(err)
	}
	var canonical map[string]json.RawMessage
	if err := json.Unmarshal(canonicalJSON, &canonical); err != nil {
		t.Fatal(err)
	}
	if string(canonical["effectVersion"]) != "1" || len(canonical["orderStates"]) == 0 || len(canonical["accepted"]) == 0 {
		t.Fatalf("canonical outcome lost versioned effects: %s", canonicalJSON)
	}
}

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
	batch.Outcomes[0].Result = canonicalOutcomeResult(domain.SubmitOrderResult{
		Rejected: &domain.OrderRejected{Code: "DUPLICATE_ORDER_ID", Reason: "duplicate"},
	})
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
				Result: canonicalOutcomeResult(domain.SubmitOrderResult{
					Accepted: &domain.OrderAccepted{
						EventID:       "evt-1",
						OrderID:       "ord-1",
						EngineOrderID: "eng-1",
						OccurredAt:    "2026-07-19T12:00:00Z",
					},
				}),
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
