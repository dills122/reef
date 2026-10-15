package streamdirect

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

// laneBenchSource hands out pre-built command deliveries, as many as asked, without a broker.
type laneBenchSource struct{ pending []CommandDelivery }

func (s *laneBenchSource) Fetch(_ context.Context, batchSize int, _ time.Duration) ([]CommandDelivery, error) {
	n := batchSize
	if n > len(s.pending) {
		n = len(s.pending)
	}
	out := s.pending[:n]
	s.pending = s.pending[n:]
	return out, nil
}

// laneBenchPublisher does what the Kafka publisher does on the CPU: it marshals the batch to the record payload.
type laneBenchPublisher struct{ bytes int }

func (p *laneBenchPublisher) PublishEventBatch(_ context.Context, batch VenueEventBatch) error {
	payload, err := json.Marshal(batch)
	p.bytes += len(payload)
	return err
}

func (p *laneBenchPublisher) PublishEventBatchAndAck(ctx context.Context, batch VenueEventBatch, deliveries []CommandDelivery) (int, error) {
	if err := p.PublishEventBatch(ctx, batch); err != nil {
		return 0, err
	}
	return len(deliveries), nil
}

func laneBenchDeliveries(round, pairs int) []CommandDelivery {
	out := make([]CommandDelivery, 0, 2*pairs)
	for i := 0; i < pairs; i++ {
		for k, side := range []string{"BUY", "SELL"} {
			seq := uint64(2*i + k + 1)
			id := fmt.Sprintf("r%d-%06d-%d", round, i, k)
			out = append(out, newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", seq, map[string]string{
				"commandId": "cmd-" + id, "occurredAt": "2026-07-19T18:00:00Z", "orderId": "ord-" + id, "instrumentId": "STK001",
				"participantId": fmt.Sprintf("participant-%d", (i+k)%50), "accountId": fmt.Sprintf("account-%d", (i+k)%50), "side": side, "orderType": "LIMIT",
				"quantityUnits": "100", "limitPrice": "100000000000", "currency": "USD", "timeInForce": "DAY",
			}))
		}
	}
	return out
}

// BenchmarkLaneProcessOnce measures the engine lane's CPU per crossing pair: command decode, matching, outcome and
// checksum construction, and the batch's JSON record, with a 500 command batch and no broker. One op is one round of
// 10,000 pairs (40 batches).
func BenchmarkLaneProcessOnce(b *testing.B) {
	const pairs = 10_000
	for round := 0; round < b.N; round++ {
		b.StopTimer()
		source := &laneBenchSource{pending: laneBenchDeliveries(round, pairs)}
		publisher := &laneBenchPublisher{}
		processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{ShardID: "engine-bench", BatchSize: 500, Source: "bench"})
		b.StartTimer()
		for len(source.pending) > 0 {
			if _, err := processor.ProcessOnce(context.Background()); err != nil {
				b.Fatal(err)
			}
		}
	}
	b.ReportMetric(float64(pairs)*float64(b.N)/b.Elapsed().Seconds(), "pairs/s")
}
