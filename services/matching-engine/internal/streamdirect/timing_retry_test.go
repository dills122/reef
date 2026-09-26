package streamdirect

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

// This publisher persists before losing its first response, without implementing
// the atomic publish-and-ack interface. Both attempts therefore reach durability.
type timingLostResponsePublisher struct {
	batches []VenueEventBatch
}

func (p *timingLostResponsePublisher) PublishEventBatch(_ context.Context, batch VenueEventBatch) error {
	p.batches = append(p.batches, batch)
	if len(p.batches) == 1 {
		return errors.New("durable publish succeeded but response was lost")
	}
	return nil
}

func TestProcessorTimingRetryAfterDurablePublishLostResponsePreservesSemanticIdentity(t *testing.T) {
	payload := map[string]string{
		"commandId": "cmd-timing-retry", "occurredAt": "2026-09-01T00:00:00Z",
		"orderId": "ord-timing-retry", "instrumentId": "STK001",
		"participantId": "participant-1", "accountId": "account-1", "actorId": "actor-1",
		"side": "SELL", "orderType": "LIMIT", "quantityUnits": "100",
		"limitPrice": "100000000000", "currency": "USD", "timeInForce": "DAY",
	}
	const subject = "reef.cmd.v1.p00.session.STK001.SubmitOrder"
	first := newFakeDelivery(subject, 41, payload)
	source := &fakeSource{deliveries: []CommandDelivery{first}}
	publisher := &timingLostResponsePublisher{}
	service := app.NewService()
	processor := NewProcessor(service, source, publisher, ProcessorConfig{
		ShardID: "engine-test", Partition: 0, BatchSize: 10,
		CommandStream: "REEF_COMMANDS", EventStreamName: "REEF_VENUE_EVENTS",
	})
	now := time.Date(2026, 9, 1, 12, 0, 0, 0, time.UTC)
	processor.now = func() time.Time {
		now = now.Add(time.Second)
		return now
	}
	if _, err := processor.ProcessOnce(context.Background()); err == nil {
		t.Fatal("expected lost publish response")
	}
	if len(publisher.batches) != 1 || first.acked != 0 || first.nacked != 1 {
		t.Fatalf("durable output must precede failed response and source retry: batches=%d ack=%d nak=%d", len(publisher.batches), first.acked, first.nacked)
	}
	if _, ok := service.OrderState("ord-timing-retry"); ok {
		t.Fatal("failed publish response must roll back engine order state")
	}
	if got := service.RestingOrders("STK001", domain.SideSell); got != 0 {
		t.Fatalf("rollback left %d resting orders", got)
	}

	retry := newFakeDelivery(subject, 41, payload)
	source.deliveries = []CommandDelivery{retry}
	if _, err := processor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("retry failed: %v", err)
	}
	if len(publisher.batches) != 2 || retry.acked != 1 {
		t.Fatalf("retry must durably publish then ack: batches=%d ack=%d", len(publisher.batches), retry.acked)
	}
	before, after := publisher.batches[0], publisher.batches[1]
	if before.BatchID != after.BatchID || before.PayloadChecksum != after.PayloadChecksum {
		t.Fatalf("retry changed immutable identity: before=%s/%s after=%s/%s", before.BatchID, before.PayloadChecksum, after.BatchID, after.PayloadChecksum)
	}
	if before.WorkFinishedAt == after.WorkFinishedAt || before.TimingChecksum == after.TimingChecksum {
		t.Fatal("advancing retry clock must change timing proof")
	}
	for _, batch := range publisher.batches {
		checksum, err := venueEventBatchChecksum(batch)
		if err != nil || checksum != batch.PayloadChecksum {
			t.Fatalf("invalid semantic checksum: %s, %v", checksum, err)
		}
		if batch.TimingChecksum == "" || batch.TimingChecksum != venueEventBatchTimingChecksum(batch) {
			t.Fatal("invalid separately bound timing checksum")
		}
		if len(batch.Outcomes) != 1 || batch.Outcomes[0].Result.Accepted == nil {
			t.Fatal("rollback retry must recreate accepted outcome")
		}
	}
	if got := service.RestingOrders("STK001", domain.SideSell); got != 1 {
		t.Fatalf("expected one order after replay, got %d", got)
	}
	// Recreate the old hashing mistake locally, without mutating production globals.
	// These same captured attempts would conflict if retry timing remained semantic.
	if timingRetryChecksumIncludingWorkFinished(t, before) == timingRetryChecksumIncludingWorkFinished(t, after) {
		t.Fatal("regression fixture failed to expose timing-dependent semantic conflict")
	}
	conflicting := after
	conflicting.Outcomes = append([]CommandOutcomeFact(nil), after.Outcomes...)
	conflicting.Outcomes[0].Result = canonicalOutcomeResult(domain.SubmitOrderResult{
		Rejected: &domain.OrderRejected{Code: "DUPLICATE_ORDER_ID", Reason: "corrupt retry result"},
	})
	checksum, err := venueEventBatchChecksum(conflicting)
	if err != nil || checksum == after.PayloadChecksum {
		t.Fatalf("changed actual result must still create semantic conflict: %s, %v", checksum, err)
	}
}

func timingRetryChecksumIncludingWorkFinished(t *testing.T, batch VenueEventBatch) string {
	t.Helper()
	payload, err := json.Marshal(batch)
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		t.Fatal(err)
	}
	for field := range venueEventBatchChecksumExcludedFields {
		if field != "workFinishedAt" {
			delete(root, field)
		}
	}
	digest := sha256.New()
	if err := writeCanonicalValue(digest, root); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(digest.Sum(nil))
}
