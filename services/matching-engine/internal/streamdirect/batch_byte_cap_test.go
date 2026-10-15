package streamdirect

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/IBM/sarama"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

// rewindSource models a Kafka partition: Fetch hands out the head of the log
// without consuming it, and only a committed publish moves the head. A nak
// therefore redelivers the same commands, which is what the real source does
// by rewinding its consumer offset.
type rewindSource struct {
	pending []CommandDelivery
	fetches []int
}

func (s *rewindSource) Fetch(_ context.Context, batchSize int, _ time.Duration) ([]CommandDelivery, error) {
	s.fetches = append(s.fetches, batchSize)
	n := batchSize
	if n > len(s.pending) {
		n = len(s.pending)
	}
	return append([]CommandDelivery(nil), s.pending[:n]...), nil
}

type sizeLimitedPublisher struct {
	source    *rewindSource
	limit     int
	batches   []VenueEventBatch
	oversized []int
	// err, when set, replaces the size decision (for errors raised by the producer).
	err error
}

func (p *sizeLimitedPublisher) PublishEventBatch(context.Context, VenueEventBatch) error {
	return errors.New("the byte cap test publishes atomically")
}

func (p *sizeLimitedPublisher) PublishEventBatchAndAck(_ context.Context, batch VenueEventBatch, deliveries []CommandDelivery) (int, error) {
	payload, err := json.Marshal(batch)
	if err != nil {
		return 0, err
	}
	if p.err != nil {
		p.oversized = append(p.oversized, len(payload))
		return 0, p.err
	}
	if p.limit > 0 && len(payload) > p.limit {
		p.oversized = append(p.oversized, len(payload))
		return 0, &EventBatchTooLargeError{Bytes: len(payload), Limit: p.limit}
	}
	p.batches = append(p.batches, batch)
	p.source.pending = p.source.pending[len(deliveries):]
	return len(deliveries), nil
}

func submitDelivery(seq uint64) *fakeDelivery {
	return newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", seq, map[string]string{
		"commandId":     fmt.Sprintf("cmd-cap-%04d", seq),
		"occurredAt":    "2026-07-19T18:00:00Z",
		"orderId":       fmt.Sprintf("ord-cap-%04d", seq),
		"instrumentId":  "STK001",
		"participantId": "participant-1",
		"accountId":     "account-1",
		"side":          "BUY",
		"orderType":     "LIMIT",
		"quantityUnits": "100",
		"limitPrice":    "100000000000",
		"currency":      "USD",
		"timeInForce":   "DAY",
	})
}

func newCapSource(n int) (*rewindSource, []*fakeDelivery) {
	source := &rewindSource{}
	all := make([]*fakeDelivery, 0, n)
	for i := 1; i <= n; i++ {
		d := submitDelivery(uint64(i))
		all = append(all, d)
		source.pending = append(source.pending, d)
	}
	return source, all
}

// sizeOfBatch measures what n of these commands cost as one event batch so the
// tests can set a limit that fits some batch sizes and not others.
func sizeOfBatch(t *testing.T, n int) int {
	t.Helper()
	source, _ := newCapSource(n)
	publisher := &sizeLimitedPublisher{source: source}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{ShardID: "engine-test", BatchSize: n})
	if _, err := processor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("measuring batch: %v", err)
	}
	payload, err := json.Marshal(publisher.batches[0])
	if err != nil {
		t.Fatal(err)
	}
	return len(payload)
}

func drain(t *testing.T, processor *Processor, source *rewindSource, maxCalls int) (tooLarge int) {
	t.Helper()
	for call := 0; len(source.pending) > 0; call++ {
		if call >= maxCalls {
			t.Fatalf("lane did not drain after %d ProcessOnce calls, %d commands pending: it is wedged", maxCalls, len(source.pending))
		}
		_, err := processor.ProcessOnce(context.Background())
		if err == nil {
			continue
		}
		var fatal *fatalProcessorError
		if errors.As(err, &fatal) {
			t.Fatalf("lane stopped on a recoverable oversize batch: %v", err)
		}
		if !isEventBatchTooLarge(err) {
			t.Fatalf("unexpected error: %v", err)
		}
		tooLarge++
	}
	return tooLarge
}

func TestProcessorSplitsABatchThatExceedsTheMessageLimitAndDrainsTheLane(t *testing.T) {
	limit := sizeOfBatch(t, 10)
	source, deliveries := newCapSource(64)
	publisher := &sizeLimitedPublisher{source: source, limit: limit}
	service := app.NewService()
	processor := NewProcessor(service, source, publisher, ProcessorConfig{ShardID: "engine-test", BatchSize: 64})

	tooLarge := drain(t, processor, source, 200)

	if tooLarge == 0 {
		t.Fatal("the limit never bit; the test proves nothing")
	}
	var published int
	var last uint64
	for _, batch := range publisher.batches {
		if len(batch.Outcomes) == 0 {
			t.Fatalf("empty batch published: %#v", batch)
		}
		if payload, _ := json.Marshal(batch); len(payload) > limit {
			t.Fatalf("published a batch of %d bytes over the %d limit", len(payload), limit)
		}
		for _, outcome := range batch.Outcomes {
			if outcome.Status != "accepted" {
				t.Fatalf("command %s is %q: an oversize attempt left engine state behind or reprocessed a duplicate", outcome.CommandID, outcome.Status)
			}
			if outcome.StreamSequence != last+1 {
				t.Fatalf("outcome sequence %d after %d: a command was skipped or repeated", outcome.StreamSequence, last)
			}
			last = outcome.StreamSequence
			published++
		}
	}
	if published != len(deliveries) {
		t.Fatalf("published %d outcomes for %d commands", published, len(deliveries))
	}
	for i := range deliveries {
		if _, ok := service.OrderState("", fmt.Sprintf("ord-cap-%04d", i+1)); !ok {
			t.Fatalf("order %d is missing from the engine", i+1)
		}
	}
	if stats := processor.Stats(); stats.BatchSplits == 0 {
		t.Fatalf("splits not counted: %#v", stats)
	}
}

func TestProcessorStopsTheLaneWhenOneCommandAloneExceedsTheMessageLimit(t *testing.T) {
	source, deliveries := newCapSource(1)
	publisher := &sizeLimitedPublisher{source: source, limit: 64}
	service := app.NewService()
	processor := NewProcessor(service, source, publisher, ProcessorConfig{ShardID: "engine-test", BatchSize: 8})

	_, err := processor.ProcessOnce(context.Background())

	var fatal *fatalProcessorError
	if !errors.As(err, &fatal) || !isEventBatchTooLarge(err) {
		t.Fatalf("a single command that cannot fit must stop the lane loudly, got %v", err)
	}
	if _, ok := service.OrderState("", "ord-cap-0001"); ok {
		t.Fatal("the refused command left engine state behind")
	}
	if deliveries[0].nacked == 0 || deliveries[0].acked != 0 {
		t.Fatalf("the command must stay unacknowledged: ack=%d nak=%d", deliveries[0].acked, deliveries[0].nacked)
	}
}

func TestProcessorTreatsAProducerSizeRejectionLikeAnOversizeBatch(t *testing.T) {
	source, _ := newCapSource(8)
	publisher := &sizeLimitedPublisher{source: source}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{ShardID: "engine-test", BatchSize: 8})
	publisher.err = fmt.Errorf("publish venue event batch in Kafka transaction: %w", sarama.ErrMessageSizeTooLarge)

	if _, err := processor.ProcessOnce(context.Background()); !isEventBatchTooLarge(err) {
		t.Fatalf("producer rejection not recognised: %v", err)
	}
	publisher.err = nil
	if _, err := processor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("the halved batch should publish: %v", err)
	}
	if got := len(publisher.batches[0].Outcomes); got != 4 {
		t.Fatalf("expected the retry to carry half the batch (4), got %d", got)
	}
}

func TestProcessorGrowsBackToTheConfiguredBatchAfterSustainedSuccess(t *testing.T) {
	source, _ := newCapSource(2000)
	publisher := &sizeLimitedPublisher{source: source}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{ShardID: "engine-test", BatchSize: 32})
	publisher.err = &EventBatchTooLargeError{Bytes: 2, Limit: 1}
	if _, err := processor.ProcessOnce(context.Background()); !isEventBatchTooLarge(err) {
		t.Fatalf("expected an oversize error: %v", err)
	}
	publisher.err = nil
	if got := processor.Stats().BatchLimit; got != 16 {
		t.Fatalf("limit after one split = %d, want 16", got)
	}
	for i := 0; i < 400 && processor.Stats().BatchLimit < 32; i++ {
		if _, err := processor.ProcessOnce(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	if got := processor.Stats().BatchLimit; got != 32 {
		t.Fatalf("limit never recovered: %d", got)
	}
}

func TestKafkaPublisherRefusesAnEventBatchOverTheProducerLimitBeforeOpeningATransaction(t *testing.T) {
	publisher := &KafkaEventBatchPublisher{
		config:          KafkaConfig{EventTopic: "events", EventSubjectPrefix: "reef.evt", PartitionCount: 1},
		maxMessageBytes: 4096,
	}
	small := VenueEventBatch{BatchID: "small", CreatedAt: "2026-07-19T18:00:00Z"}
	if _, err := publisher.eventBatchMessage(small); err != nil {
		t.Fatalf("a small batch must pass: %v", err)
	}
	large := VenueEventBatch{BatchID: "large", CreatedAt: "2026-07-19T18:00:00Z"}
	for i := 0; i < 200; i++ {
		large.Outcomes = append(large.Outcomes, CommandOutcomeFact{CommandID: fmt.Sprintf("cmd-%04d", i), Status: "accepted"})
	}
	_, err := publisher.eventBatchMessage(large)
	var tooLarge *EventBatchTooLargeError
	if !errors.As(err, &tooLarge) || tooLarge.Limit != 4096 || tooLarge.Bytes <= 4096 {
		t.Fatalf("expected EventBatchTooLargeError over 4096, got %v", err)
	}
	publisher.maxMessageBytes = 0
	if _, err := publisher.eventBatchMessage(large); err != nil {
		t.Fatalf("limit 0 means unchecked: %v", err)
	}
}
