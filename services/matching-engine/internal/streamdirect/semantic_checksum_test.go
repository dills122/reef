package streamdirect

import (
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
