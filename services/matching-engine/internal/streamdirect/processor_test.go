package streamdirect

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

func TestProcessorPublishesEventBatchBeforeAck(t *testing.T) {
	delivery := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 10, map[string]string{
		"commandId":     "cmd-1",
		"occurredAt":    "2026-07-04T00:00:00Z",
		"orderId":       "ord-1",
		"instrumentId":  "STK001",
		"participantId": "participant-1",
		"accountId":     "account-1",
		"actorId":       "actor-1",
		"side":          "BUY",
		"orderType":     "LIMIT",
		"quantityUnits": "100",
		"limitPrice":    "100000000000",
		"currency":      "USD",
		"timeInForce":   "DAY",
	})
	source := &fakeSource{deliveries: []CommandDelivery{delivery}}
	publisher := &fakePublisher{}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{
		ShardID:         "engine-test",
		Partition:       0,
		BatchSize:       10,
		FetchTimeout:    time.Millisecond,
		EventStreamName: "REEF_VENUE_EVENTS",
	})

	processed, err := processor.ProcessOnce(context.Background())
	if err != nil {
		t.Fatalf("ProcessOnce returned error: %v", err)
	}
	if processed != 1 {
		t.Fatalf("expected one processed delivery, got %d", processed)
	}
	if len(publisher.batches) != 1 {
		t.Fatalf("expected one published batch, got %d", len(publisher.batches))
	}
	batch := publisher.batches[0]
	if batch.BatchID != "engine-test-p0-10-10" {
		t.Fatalf("unexpected batch id %q", batch.BatchID)
	}
	if batch.CommandCount != 1 || len(batch.Outcomes) != 1 {
		t.Fatalf("expected one outcome, got commandCount=%d outcomes=%d", batch.CommandCount, len(batch.Outcomes))
	}
	if batch.Outcomes[0].CommandID != "cmd-1" || batch.Outcomes[0].Status != "accepted" {
		t.Fatalf("unexpected outcome %#v", batch.Outcomes[0])
	}
	acceptedOrder := batch.Outcomes[0].Result.AcceptedOrder
	if acceptedOrder == nil {
		t.Fatal("expected durable outcome to include acceptedOrder projection fact")
	}
	if acceptedOrder.InstrumentID != "STK001" ||
		acceptedOrder.ParticipantID != "participant-1" ||
		acceptedOrder.AccountID != "account-1" ||
		acceptedOrder.QuantityUnits != "100" {
		t.Fatalf("unexpected acceptedOrder projection fact %#v", acceptedOrder)
	}
	if delivery.acked != 1 {
		t.Fatalf("expected delivery ack after event publish, got %d", delivery.acked)
	}
	if delivery.nacked != 0 || delivery.termed != 0 {
		t.Fatalf("unexpected nak/term counts: nak=%d term=%d", delivery.nacked, delivery.termed)
	}
	stats := processor.Stats()
	if stats.Published != 1 || stats.Acked != 1 || stats.Failed != 0 {
		t.Fatalf("unexpected stats %#v", stats)
	}
}

func TestProcessorNaksWhenEventPublishFails(t *testing.T) {
	delivery := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 11, map[string]string{
		"commandId":     "cmd-2",
		"occurredAt":    "2026-07-04T00:00:00Z",
		"orderId":       "ord-2",
		"instrumentId":  "STK001",
		"participantId": "participant-1",
		"accountId":     "account-1",
		"actorId":       "actor-1",
		"side":          "SELL",
		"orderType":     "LIMIT",
		"quantityUnits": "100",
		"limitPrice":    "100000000000",
		"currency":      "USD",
		"timeInForce":   "DAY",
	})
	source := &fakeSource{deliveries: []CommandDelivery{delivery}}
	publisher := &fakePublisher{err: errors.New("publish failed")}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})

	processed, err := processor.ProcessOnce(context.Background())
	if err == nil {
		t.Fatal("expected publish error")
	}
	if processed != 1 {
		t.Fatalf("expected one processed delivery, got %d", processed)
	}
	if delivery.acked != 0 {
		t.Fatalf("expected no ack when publish fails, got %d", delivery.acked)
	}
	if delivery.nacked != 1 {
		t.Fatalf("expected nak when publish fails, got %d", delivery.nacked)
	}
}

func TestProcessorProcessesModifyAndCancelCommands(t *testing.T) {
	submit := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 20, map[string]string{
		"commandId":     "cmd-submit",
		"occurredAt":    "2026-07-04T00:00:00Z",
		"orderId":       "ord-life-1",
		"instrumentId":  "STK001",
		"participantId": "participant-1",
		"accountId":     "account-1",
		"actorId":       "actor-1",
		"side":          "BUY",
		"orderType":     "LIMIT",
		"quantityUnits": "100",
		"limitPrice":    "100000000000",
		"currency":      "USD",
		"timeInForce":   "DAY",
	})
	modify := newFakeDelivery("reef.cmd.v1.p00.session.STK001.ModifyOrder", 21, map[string]string{
		"commandId":     "cmd-modify",
		"occurredAt":    "2026-07-04T00:00:01Z",
		"orderId":       "ord-life-1",
		"quantityUnits": "120",
		"limitPrice":    "100100000000",
	})
	cancel := newFakeDelivery("reef.cmd.v1.p00.session.STK001.CancelOrder", 22, map[string]string{
		"commandId":  "cmd-cancel",
		"occurredAt": "2026-07-04T00:00:02Z",
		"orderId":    "ord-life-1",
		"reason":     "test",
	})
	source := &fakeSource{deliveries: []CommandDelivery{submit, modify, cancel}}
	publisher := &fakePublisher{}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})

	processed, err := processor.ProcessOnce(context.Background())
	if err != nil {
		t.Fatalf("ProcessOnce returned error: %v", err)
	}
	if processed != 3 {
		t.Fatalf("expected three processed deliveries, got %d", processed)
	}
	if len(publisher.batches) != 1 {
		t.Fatalf("expected one published batch, got %d", len(publisher.batches))
	}
	batch := publisher.batches[0]
	if batch.CommandCount != 3 || len(batch.Outcomes) != 3 {
		t.Fatalf("expected three outcomes, got commandCount=%d outcomes=%d", batch.CommandCount, len(batch.Outcomes))
	}
	expectedTypes := []string{"SubmitOrder", "ModifyOrder", "CancelOrder"}
	for idx, expectedType := range expectedTypes {
		outcome := batch.Outcomes[idx]
		if outcome.CommandType != expectedType || outcome.Status != "accepted" {
			t.Fatalf("unexpected outcome %d: %#v", idx, outcome)
		}
		if outcome.InstrumentID != "STK001" || outcome.OrderID != "ord-life-1" {
			t.Fatalf("unexpected routing fields on outcome %d: %#v", idx, outcome)
		}
	}
	if submit.acked != 1 || modify.acked != 1 || cancel.acked != 1 {
		t.Fatalf("expected all deliveries acked after publish, got submit=%d modify=%d cancel=%d", submit.acked, modify.acked, cancel.acked)
	}
}

func TestProcessorPublishesFailedOutcomeForUnsupportedCommands(t *testing.T) {
	delivery := newFakeDelivery("reef.cmd.v1.p00.session.STK001.CancelOrder", 12, map[string]string{
		"commandId": "cmd-cancel-1",
		"orderId":   "ord-1",
	})
	delivery.subject = "reef.cmd.v1.p00.session.STK001.ReplaceOrder"
	source := &fakeSource{deliveries: []CommandDelivery{delivery}}
	publisher := &fakePublisher{}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})

	processed, err := processor.ProcessOnce(context.Background())
	if err != nil {
		t.Fatalf("ProcessOnce returned error: %v", err)
	}
	if processed != 1 {
		t.Fatalf("expected one processed delivery, got %d", processed)
	}
	if len(publisher.batches) != 1 {
		t.Fatalf("expected one published batch for unsupported command, got %d", len(publisher.batches))
	}
	outcome := publisher.batches[0].Outcomes[0]
	if outcome.Status != "failed" || outcome.CommandID != "cmd-cancel-1" {
		t.Fatalf("unexpected outcome for unsupported command: %#v", outcome)
	}
	if outcome.Result.Rejected == nil || outcome.Result.Rejected.Code != "UNSUPPORTED_COMMAND_TYPE" {
		t.Fatalf("expected UNSUPPORTED_COMMAND_TYPE reject code, got %#v", outcome.Result.Rejected)
	}
	if delivery.acked != 1 {
		t.Fatalf("expected unsupported command to be acked (offset committed) after failed outcome publish, got %d", delivery.acked)
	}
	if delivery.termed != 0 || delivery.nacked != 0 {
		t.Fatalf("unexpected term/nak counts: term=%d nak=%d", delivery.termed, delivery.nacked)
	}
}

func TestProcessorPublishesFailedOutcomeForUndecodableCommand(t *testing.T) {
	delivery := &fakeDelivery{
		subject: "reef.cmd.v1.p00.session.STK001.SubmitOrder",
		seq:     13,
		data:    []byte(`{"commandId":"cmd-poison-1","quantityUnits":123}`),
	}
	source := &fakeSource{deliveries: []CommandDelivery{delivery}}
	publisher := &fakePublisher{}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})

	processed, err := processor.ProcessOnce(context.Background())
	if err != nil {
		t.Fatalf("ProcessOnce returned error: %v", err)
	}
	if processed != 1 {
		t.Fatalf("expected one processed delivery, got %d", processed)
	}
	if len(publisher.batches) != 1 {
		t.Fatalf("expected one published batch for undecodable command, got %d", len(publisher.batches))
	}
	outcome := publisher.batches[0].Outcomes[0]
	if outcome.Status != "failed" || outcome.CommandID != "cmd-poison-1" {
		t.Fatalf("unexpected outcome for undecodable command: %#v", outcome)
	}
	if outcome.Result.Rejected == nil || outcome.Result.Rejected.Code != "POISON_COMMAND_DECODE_ERROR" {
		t.Fatalf("expected POISON_COMMAND_DECODE_ERROR reject code, got %#v", outcome.Result.Rejected)
	}
	if delivery.acked != 1 {
		t.Fatalf("expected undecodable command to be acked (offset committed) after failed outcome publish, got %d", delivery.acked)
	}
	if delivery.termed != 0 || delivery.nacked != 0 {
		t.Fatalf("unexpected term/nak counts: term=%d nak=%d", delivery.termed, delivery.nacked)
	}
}

func TestProcessorRedeliveredSubmitAfterCrashIsRejectedNotReexecuted(t *testing.T) {
	payload := map[string]string{
		"commandId":     "cmd-crash-redelivery",
		"occurredAt":    "2026-07-04T00:00:00Z",
		"orderId":       "ord-crash-redelivery",
		"instrumentId":  "STK001",
		"participantId": "participant-1",
		"accountId":     "account-1",
		"actorId":       "actor-1",
		"side":          "BUY",
		"orderType":     "LIMIT",
		"quantityUnits": "100",
		"limitPrice":    "100000000000",
		"currency":      "USD",
		"timeInForce":   "DAY",
	}
	service := app.NewService()
	publisher := &fakePublisher{}

	first := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 30, payload)
	firstProcessor := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{first}}, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	if _, err := firstProcessor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("first ProcessOnce returned error: %v", err)
	}
	if len(publisher.batches) != 1 || publisher.batches[0].Outcomes[0].Status != "accepted" {
		t.Fatalf("expected first attempt accepted, got %#v", publisher.batches)
	}
	if first.acked != 1 {
		t.Fatalf("expected first delivery acked, got %d", first.acked)
	}

	// Simulate engine crash after publish succeeded but before the command
	// offset committed: the broker redelivers the same command bytes on the
	// same (still-shared, in-process) service/order-book state.
	redelivered := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 31, payload)
	redeliveredProcessor := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{redelivered}}, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	if _, err := redeliveredProcessor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("redelivered ProcessOnce returned error: %v", err)
	}
	if len(publisher.batches) != 2 {
		t.Fatalf("expected redelivery to publish a second outcome batch, got %d", len(publisher.batches))
	}
	redeliveredOutcome := publisher.batches[1].Outcomes[0]
	if redeliveredOutcome.CommandID != "cmd-crash-redelivery" || redeliveredOutcome.Status != "rejected" {
		t.Fatalf("expected redelivered submit to be rejected, got %#v", redeliveredOutcome)
	}
	if redeliveredOutcome.Result.Rejected == nil || redeliveredOutcome.Result.Rejected.Code != "DUPLICATE_ORDER_ID" {
		t.Fatalf("expected DUPLICATE_ORDER_ID rejection on redelivery, got %#v", redeliveredOutcome.Result.Rejected)
	}
	if redelivered.acked != 1 {
		t.Fatalf("expected redelivered delivery acked (not endlessly nak-retried), got %d", redelivered.acked)
	}
}

type fakeSource struct {
	deliveries []CommandDelivery
}

func (s *fakeSource) Fetch(_ context.Context, _ int, _ time.Duration) ([]CommandDelivery, error) {
	out := s.deliveries
	s.deliveries = nil
	return out, nil
}

type fakePublisher struct {
	batches []VenueEventBatch
	err     error
}

func (p *fakePublisher) PublishEventBatch(_ context.Context, batch VenueEventBatch) error {
	if p.err != nil {
		return p.err
	}
	p.batches = append(p.batches, batch)
	return nil
}

type fakeDelivery struct {
	subject string
	data    []byte
	seq     uint64
	acked   int
	nacked  int
	termed  int
}

func newFakeDelivery(subject string, seq uint64, payload map[string]string) *fakeDelivery {
	data, err := json.Marshal(payload)
	if err != nil {
		panic(err)
	}
	return &fakeDelivery{subject: subject, seq: seq, data: data}
}

func (d *fakeDelivery) Subject() string {
	return d.subject
}

func (d *fakeDelivery) Data() []byte {
	return d.data
}

func (d *fakeDelivery) StreamSequence() uint64 {
	return d.seq
}

func (d *fakeDelivery) DeliveredCount() uint64 {
	return 1
}

func (d *fakeDelivery) Ack() error {
	d.acked++
	return nil
}

func (d *fakeDelivery) Nak() error {
	d.nacked++
	return nil
}

func (d *fakeDelivery) Term() error {
	d.termed++
	return nil
}
