package streamdirect

import (
	"encoding/json"
	"fmt"
	"os"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

// Fixture models 100 crossing pairs, including two executions and one trade
// per SELL. Measures checksum CPU/allocations, not venue or broker throughput.
func checksumBenchmarkBatch() VenueEventBatch {
	batch := checksumTestBatch()
	batch.BatchID = "engine-0-p2-101-300"
	batch.CommandCount = 200
	batch.LastSequence = 300
	batch.Outcomes = make([]CommandOutcomeFact, 200)
	for i := range batch.Outcomes {
		id := fmt.Sprintf("order-%d", i)
		out := CommandOutcomeFact{CommandID: fmt.Sprintf("command-%d", i), CommandType: "SubmitOrder", StreamSequence: uint64(101 + i), DeliveredCount: 1, PayloadHash: "b2f0980bc24265a0c4ca0e12626c6758b281b8a4fb72e37da273fbde24cf8ec0", InstrumentID: "AAPL", OrderID: id, Status: "accepted", Result: domain.SubmitOrderResult{Accepted: &domain.OrderAccepted{EventID: "accepted-" + id, OrderID: id, EngineOrderID: "engine-" + id, OccurredAt: "2026-10-02T04:00:00.123456789Z"}}}
		if i%2 == 1 {
			buyID := fmt.Sprintf("order-%d", i-1)
			for _, side := range []string{"buy", "sell"} {
				orderID, role := buyID, "MAKER"
				if side == "sell" {
					orderID, role = id, "TAKER"
				}
				out.Result.Executions = append(out.Result.Executions, domain.ExecutionCreated{EventID: "execution-" + side + "-" + id, ExecutionID: "exec-" + side + "-" + id, OrderID: orderID, InstrumentID: "AAPL", QuantityUnits: "100", ExecutionPrice: "10000", Currency: "USD", OccurredAt: "2026-10-02T04:00:00.123456789Z", LiquidityRole: role})
			}
			out.Result.Trades = []domain.TradeCreated{{EventID: "trade-" + id, TradeID: "trade-" + id, ExecutionID: "exec-" + id, BuyOrderID: buyID, SellOrderID: id, InstrumentID: "AAPL", QuantityUnits: "100", Price: "10000", Currency: "USD", OccurredAt: "2026-10-02T04:00:00.123456789Z"}}
		}
		batch.Outcomes[i] = out
	}
	return batch
}

// Optional captured wire batch replaces the synthetic fixture. Read/decode and
// checksum validation happen before benchmark timers; preserve original
// synthetic mode when REEF_CHECKSUM_BENCH_FIXTURE is unset.
func checksumSelectedFixture(t testing.TB) VenueEventBatch {
	t.Helper()
	path := os.Getenv("REEF_CHECKSUM_BENCH_FIXTURE")
	if path == "" {
		return checksumBenchmarkBatch()
	}
	payload, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var batch VenueEventBatch
	if err := json.Unmarshal(payload, &batch); err != nil {
		t.Fatal(err)
	}
	if batch.PayloadChecksum == "" || batch.ChecksumAlgo != venueEventBatchChecksumAlgorithm || batch.CommandCount != len(batch.Outcomes) || batch.CommandCount == 0 {
		t.Fatal("captured fixture must have supported wire checksum and nonempty consistent outcome count")
	}
	// A schema change/omitted wire field must fail this check, not silently
	// benchmark a lossy re-marshaled fixture against a newly computed checksum.
	for name, checksum := range map[string]func(VenueEventBatch) (string, error){"reference": referenceVenueEventBatchChecksum, "optimized": venueEventBatchChecksum} {
		got, err := checksum(batch)
		if err != nil {
			t.Fatal(err)
		}
		if got != batch.PayloadChecksum {
			t.Fatalf("%s captured checksum=%s wire=%s", name, got, batch.PayloadChecksum)
		}
	}
	t.Logf("captured fixture=%s bytes=%d commandCount=%d wireChecksum=%s", path, len(payload), batch.CommandCount, batch.PayloadChecksum)
	return batch
}

var benchmarkChecksum string

func BenchmarkVenueEventBatchChecksum200(b *testing.B) {
	batch := checksumSelectedFixture(b)
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		value, err := venueEventBatchChecksum(batch)
		if err != nil {
			b.Fatal(err)
		}
		benchmarkChecksum = value
	}
}

func BenchmarkVenueEventBatchChecksumReference200(b *testing.B) {
	batch := checksumSelectedFixture(b)
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		value, err := referenceVenueEventBatchChecksum(batch)
		if err != nil {
			b.Fatal(err)
		}
		benchmarkChecksum = value
	}
}

// Alternate reference/optimized order between rounds to expose temporal noise.
func BenchmarkVenueEventBatchChecksumPaired200(b *testing.B) {
	batch := checksumSelectedFixture(b)
	rounds := 4
	if os.Getenv("REEF_CHECKSUM_BENCH_FIXTURE") != "" {
		rounds = 2
	}
	for round := 0; round < rounds; round++ {
		names := []string{"reference", "optimized"}
		if round%2 == 1 {
			names = []string{"optimized", "reference"}
		}
		for _, name := range names {
			b.Run(fmt.Sprintf("round%d/%s", round, name), func(b *testing.B) {
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					var value string
					var err error
					if name == "reference" {
						value, err = referenceVenueEventBatchChecksum(batch)
					} else {
						value, err = venueEventBatchChecksum(batch)
					}
					if err != nil {
						b.Fatal(err)
					}
					benchmarkChecksum = value
				}
			})
		}
	}
}
