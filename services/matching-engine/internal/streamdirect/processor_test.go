package streamdirect

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
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
	if stats.Source != "stream-direct" || stats.CommandStream != "" || stats.EventStream != "REEF_VENUE_EVENTS" {
		t.Fatalf("unexpected diagnostic source/streams %#v", stats)
	}
	if stats.LastBatchID != "engine-test-p0-10-10" {
		t.Fatalf("expected last batch id to be recorded, got %q", stats.LastBatchID)
	}
	if stats.LastFetchedSequence != 10 || stats.LastAckedSequence != 10 || stats.AckLag != 0 {
		t.Fatalf("unexpected sequence diagnostics %#v", stats)
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
	stats := processor.Stats()
	if stats.LastBatchID != "" {
		t.Fatalf("expected no durable batch id when publish fails, got %q", stats.LastBatchID)
	}
	if stats.LastFetchedSequence != 11 || stats.LastAckedSequence != 0 || stats.AckLag != 11 {
		t.Fatalf("unexpected diagnostics after publish failure %#v", stats)
	}
}

func TestProcessorRecordsUnackedLagWhenAckFailsAfterEventPublish(t *testing.T) {
	delivery := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 14, map[string]string{
		"commandId":     "cmd-ack-crash",
		"occurredAt":    "2026-07-04T00:00:00Z",
		"orderId":       "ord-ack-crash",
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
	delivery.ackErr = errors.New("simulated offset commit lost")
	source := &fakeSource{deliveries: []CommandDelivery{delivery}}
	publisher := &fakePublisher{}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{
		ShardID:         "engine-test",
		Partition:       0,
		BatchSize:       10,
		CommandStream:   "REEF_COMMANDS",
		EventStreamName: "REEF_VENUE_EVENTS",
		Source:          "redpanda",
	})

	processed, err := processor.ProcessOnce(context.Background())
	if err != nil {
		t.Fatalf("ProcessOnce returned error: %v", err)
	}
	if processed != 1 || len(publisher.batches) != 1 {
		t.Fatalf("expected one published batch before ack failure, processed=%d batches=%d", processed, len(publisher.batches))
	}
	stats := processor.Stats()
	if stats.Published != 1 || stats.Acked != 0 || stats.Failed != 1 {
		t.Fatalf("unexpected counters after ack failure %#v", stats)
	}
	if stats.LastBatchID != "engine-test-p0-14-14" {
		t.Fatalf("expected durable batch id after publish, got %q", stats.LastBatchID)
	}
	if stats.Source != "redpanda" || stats.CommandStream != "REEF_COMMANDS" || stats.EventStream != "REEF_VENUE_EVENTS" {
		t.Fatalf("unexpected source/stream diagnostics %#v", stats)
	}
	if stats.LastFetchedSequence != 14 || stats.LastAckedSequence != 0 || stats.AckLag != 14 {
		t.Fatalf("expected unacked sequence lag after ack failure, got %#v", stats)
	}
}

func TestLocalAckFailureHookFailsOnceAfterEventPublish(t *testing.T) {
	payload := map[string]string{
		"commandId":     "cmd-local-ack-fail",
		"occurredAt":    "2026-07-04T00:00:00Z",
		"orderId":       "ord-local-ack-fail",
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

	first := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 15, payload)
	firstSource := wrapCommandSourceWithLocalFaultHooks(&fakeBatchAckSource{
		fakeSource: fakeSource{deliveries: []CommandDelivery{first}},
	}, RuntimeConfig{
		TestFailAckOnce:      true,
		TestStopAfterAckFail: true,
	})
	firstProcessor := NewProcessor(service, firstSource, publisher, ProcessorConfig{
		ShardID:          "engine-test",
		Partition:        0,
		BatchSize:        10,
		StopAfterAckFail: true,
	})
	processed, err := firstProcessor.ProcessOnce(context.Background())
	if err == nil {
		t.Fatal("expected injected ack failure")
	}
	if processed != 1 || len(publisher.batches) != 1 {
		t.Fatalf("expected event batch published before injected ack failure, processed=%d batches=%d", processed, len(publisher.batches))
	}
	if first.acked != 0 {
		t.Fatalf("expected command offset not acked after injected failure, got %d", first.acked)
	}
	if got := firstProcessor.Stats(); got.Published != 1 || got.Acked != 0 || got.Failed != 1 || got.AckLag != 15 {
		t.Fatalf("unexpected stats after injected ack failure %#v", got)
	}

	redelivered := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 15, payload)
	redeliverySource := wrapCommandSourceWithLocalFaultHooks(&fakeBatchAckSource{
		fakeSource: fakeSource{deliveries: []CommandDelivery{redelivered}},
	}, RuntimeConfig{})
	redeliveryProcessor := NewProcessor(service, redeliverySource, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	if _, err := redeliveryProcessor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("redelivery ProcessOnce returned error: %v", err)
	}
	if redelivered.acked != 0 {
		t.Fatalf("expected fake batch source to commit through AckBatch, not delivery Ack, got %d", redelivered.acked)
	}
	if got := redeliveryProcessor.Stats(); got.Published != 1 || got.Acked != 1 || got.Failed != 0 || got.AckLag != 0 {
		t.Fatalf("unexpected redelivery stats %#v", got)
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

func TestProcessorDoesNotMatchSameInstrumentAcrossVenueSessions(t *testing.T) {
	sell := newFakeDelivery("reef.cmd.v1.p00.session-1.STK001.SubmitOrder", 23, map[string]string{
		"commandId":      "cmd-session-1-sell",
		"occurredAt":     "2026-07-04T00:00:00Z",
		"orderId":        "ord-session-1-sell",
		"venueSessionId": "session-1",
		"instrumentId":   "STK001",
		"participantId":  "participant-1",
		"accountId":      "account-1",
		"actorId":        "actor-1",
		"side":           "SELL",
		"orderType":      "LIMIT",
		"quantityUnits":  "100",
		"limitPrice":     "100000000000",
		"currency":       "USD",
		"timeInForce":    "DAY",
	})
	buy := newFakeDelivery("reef.cmd.v1.p01.session-2.STK001.SubmitOrder", 24, map[string]string{
		"commandId":      "cmd-session-2-buy",
		"occurredAt":     "2026-07-04T00:00:01Z",
		"orderId":        "ord-session-2-buy",
		"venueSessionId": "session-2",
		"instrumentId":   "STK001",
		"participantId":  "participant-2",
		"accountId":      "account-2",
		"actorId":        "actor-2",
		"side":           "BUY",
		"orderType":      "LIMIT",
		"quantityUnits":  "100",
		"limitPrice":     "101000000000",
		"currency":       "USD",
		"timeInForce":    "DAY",
	})
	service := app.NewService()
	source := &fakeSource{deliveries: []CommandDelivery{sell, buy}}
	publisher := &fakePublisher{}
	processor := NewProcessor(service, source, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})

	processed, err := processor.ProcessOnce(context.Background())
	if err != nil {
		t.Fatalf("ProcessOnce returned error: %v", err)
	}
	if processed != 2 || len(publisher.batches) != 1 {
		t.Fatalf("expected one two-command batch, processed=%d batches=%d", processed, len(publisher.batches))
	}
	batch := publisher.batches[0]
	if len(batch.Outcomes) != 2 {
		t.Fatalf("expected two outcomes, got %#v", batch.Outcomes)
	}
	for _, outcome := range batch.Outcomes {
		if outcome.Status != "accepted" || len(outcome.Result.Trades) != 0 || len(outcome.Result.Executions) != 0 {
			t.Fatalf("expected accepted non-trading outcome across sessions, got %#v", outcome)
		}
	}
	if got := service.RestingOrdersInSession("session-1", "STK001", domain.SideSell); got != 1 {
		t.Fatalf("expected session-1 sell liquidity to remain, got %d", got)
	}
	if got := service.RestingOrdersInSession("session-2", "STK001", domain.SideBuy); got != 1 {
		t.Fatalf("expected session-2 buy liquidity to rest separately, got %d", got)
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

// TestScenario2_RedeliveryAfterPublishSucceedsButOffsetCommitIsLostIsDedupedNotReexecuted
// covers WORK_PLAN.md crash/restart scenario 2: "matching engine publishes
// VenueEventBatch then exits before command offset commit." The batch published
// successfully (durable event-batch handoff complete) but the process exits before
// the delivery ack (offset commit) lands, so the broker redelivers the same command.
// This is the same shape as TestProcessorRedeliveredSubmitAfterCrashIsRejectedNotReexecuted
// above; this test names it explicitly to the WORK_PLAN scenario and additionally
// asserts the redelivered attempt is still acked so the offset advances exactly once
// even though the engine-level result differs (rejected) from the original (accepted).
func TestScenario2_RedeliveryAfterPublishSucceedsButOffsetCommitIsLostIsDedupedNotReexecuted(t *testing.T) {
	payload := map[string]string{
		"commandId":     "cmd-scenario2",
		"occurredAt":    "2026-07-04T00:00:00Z",
		"orderId":       "ord-scenario2",
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

	first := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 40, payload)
	firstProcessor := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{first}}, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	if _, err := firstProcessor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("first ProcessOnce returned error: %v", err)
	}
	if len(publisher.batches) != 1 || first.acked != 1 {
		t.Fatalf("expected first attempt published and acked, got batches=%d acked=%d", len(publisher.batches), first.acked)
	}

	// Process "exits" here, before the broker durably records the offset commit for
	// stream sequence 40 - it redelivers the same command on restart.
	redelivered := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 40, payload)
	redeliveredProcessor := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{redelivered}}, publisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	processed, err := redeliveredProcessor.ProcessOnce(context.Background())
	if err != nil {
		t.Fatalf("redelivered ProcessOnce returned error: %v", err)
	}
	if processed != 1 {
		t.Fatalf("expected redelivered command to be processed exactly once per cycle, got %d", processed)
	}
	if len(publisher.batches) != 2 {
		t.Fatalf("expected redelivery to publish a second outcome batch (never silently dropped), got %d", len(publisher.batches))
	}
	if redelivered.acked != 1 || redelivered.nacked != 0 {
		t.Fatalf("expected redelivered command to ack (offset commits exactly once), got acked=%d nacked=%d", redelivered.acked, redelivered.nacked)
	}
}

// TestScenario3_MatchingEngineFailsBeforeEventBatchPublishLeavesCommandOffsetUncommitted
// covers WORK_PLAN.md crash/restart scenario 3: "matching engine fails before
// event-batch publish, leaving command offset uncommitted." It proves the trivial
// half by construction (no ack, no offset commit, so a nak'd/never-acked delivery
// will be redelivered by the broker) and, more importantly, that the engine's book
// does not diverge from the canonical published record at this crash point: a
// failed publish now rolls back the order reservation/match that processing made
// (via Service.BeginBatch/BatchRollback.Rollback), so a subsequent redelivery
// reprocesses the command cleanly and is durably published as ACCEPTED - not
// rejected as a duplicate - exactly once.
func TestScenario3_MatchingEngineFailsBeforeEventBatchPublishLeavesCommandOffsetUncommitted(t *testing.T) {
	payload := map[string]string{
		"commandId":     "cmd-scenario3",
		"occurredAt":    "2026-07-04T00:00:00Z",
		"orderId":       "ord-scenario3",
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
	}
	service := app.NewService()

	failingPublisher := &fakePublisher{err: errors.New("publish failed")}
	first := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 41, payload)
	firstProcessor := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{first}}, failingPublisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	if _, err := firstProcessor.ProcessOnce(context.Background()); err == nil {
		t.Fatal("expected publish error before any offset commit")
	}
	if first.acked != 0 || first.nacked != 1 {
		t.Fatalf("expected offset left uncommitted (nak, no ack) when publish fails, got acked=%d nacked=%d", first.acked, first.nacked)
	}
	if len(failingPublisher.batches) != 0 {
		t.Fatalf("expected nothing durable to have been published, got %d batches", len(failingPublisher.batches))
	}

	// The book must not hold a live reservation for a command that was never
	// durably published: the failed publish should have rolled back
	// reserveOrder()'s mutation entirely.
	if _, ok := service.OrderState("ord-scenario3"); ok {
		t.Fatal("expected the order reservation to be rolled back after the failed publish, but it is still live in engine state")
	}
	if resting := service.RestingOrders("STK001", domain.SideSell); resting != 0 {
		t.Fatalf("expected no resting orders left in the book after rollback, got %d", resting)
	}

	// Broker redelivers the un-acked command; this time publish succeeds.
	successPublisher := &fakePublisher{}
	redelivered := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 41, payload)
	redeliveredProcessor := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{redelivered}}, successPublisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	if _, err := redeliveredProcessor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("redelivered ProcessOnce returned error: %v", err)
	}
	if len(successPublisher.batches) != 1 {
		t.Fatalf("expected the redelivered command to still be durably published (not silently dropped), got %d batches", len(successPublisher.batches))
	}
	if redelivered.acked != 1 {
		t.Fatalf("expected redelivered command to ack once published successfully, got %d", redelivered.acked)
	}
	// The rollback means this redelivery is the *first* successful durable
	// publish attempt for this commandId, so it must be accepted, not rejected
	// as a duplicate of a reservation that no longer exists.
	outcome := successPublisher.batches[0].Outcomes[0]
	if outcome.CommandID != "cmd-scenario3" {
		t.Fatalf("unexpected outcome commandId %q", outcome.CommandID)
	}
	if outcome.Status != "accepted" || outcome.Result.Accepted == nil {
		t.Fatalf("expected the redelivered submit to be accepted after rollback, got %#v", outcome)
	}
	if resting := service.RestingOrders("STK001", domain.SideSell); resting != 1 {
		t.Fatalf("expected exactly one resting order in the book after the redelivered accept, got %d", resting)
	}
}

func TestPublishFailureRollbackRestoresPassiveMatchedLiquidity(t *testing.T) {
	service := app.NewService()
	seed := service.SubmitOrder(domain.SubmitOrder{
		OrderID:        "ord-resting-sell",
		VenueSessionID: "session",
		InstrumentID:   "STK001",
		ParticipantID:  "participant-2",
		AccountID:      "account-2",
		Side:           domain.SideSell,
		QuantityUnits:  "100",
		LimitPrice:     "100000000000",
		Currency:       "USD",
	})
	if seed.Accepted == nil {
		t.Fatalf("expected resting seed accepted, got %#v", seed)
	}

	payload := map[string]string{
		"commandId":      "cmd-rollback-match",
		"occurredAt":     "2026-07-04T00:00:00Z",
		"orderId":        "ord-taking-buy",
		"venueSessionId": "session",
		"instrumentId":   "STK001",
		"participantId":  "participant-1",
		"accountId":      "account-1",
		"actorId":        "actor-1",
		"side":           "BUY",
		"orderType":      "LIMIT",
		"quantityUnits":  "100",
		"limitPrice":     "101000000000",
		"currency":       "USD",
		"timeInForce":    "DAY",
	}
	failingPublisher := &fakePublisher{err: errors.New("publish failed")}
	first := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 51, payload)
	firstProcessor := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{first}}, failingPublisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	if _, err := firstProcessor.ProcessOnce(context.Background()); err == nil {
		t.Fatal("expected publish failure")
	}

	if _, ok := service.OrderState("ord-taking-buy"); ok {
		t.Fatal("expected failed-publish taker to be removed from order state")
	}
	restored, ok := service.OrderState("ord-resting-sell")
	if !ok || restored.Status != domain.OrderStatusAccepted || restored.RemainingQuantity != "100" {
		t.Fatalf("expected passive resting sell restored after rollback, got %#v", restored)
	}
	if got := service.RestingOrdersInSession("session", "STK001", domain.SideSell); got != 1 {
		t.Fatalf("expected restored sell to rest after rollback, got %d", got)
	}

	successPublisher := &fakePublisher{}
	redelivered := newFakeDelivery("reef.cmd.v1.p00.session.STK001.SubmitOrder", 51, payload)
	redeliveredProcessor := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{redelivered}}, successPublisher, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 0,
		BatchSize: 10,
	})
	if _, err := redeliveredProcessor.ProcessOnce(context.Background()); err != nil {
		t.Fatalf("redelivered ProcessOnce returned error: %v", err)
	}
	if len(successPublisher.batches) != 1 || len(successPublisher.batches[0].Outcomes) != 1 {
		t.Fatalf("expected one redelivered outcome batch, got %#v", successPublisher.batches)
	}
	outcome := successPublisher.batches[0].Outcomes[0]
	if outcome.Status != "accepted" || len(outcome.Result.Trades) != 1 {
		t.Fatalf("expected redelivery to match restored liquidity, got %#v", outcome)
	}
}

type fakeSource struct {
	deliveries []CommandDelivery
	fetchErr   error
	fetchCalls int
}

func (s *fakeSource) Fetch(_ context.Context, _ int, _ time.Duration) ([]CommandDelivery, error) {
	s.fetchCalls++
	if s.fetchErr != nil {
		return nil, s.fetchErr
	}
	out := s.deliveries
	s.deliveries = nil
	return out, nil
}

// fakeBatchAckSource is a distinct type (rather than adding AckBatch to
// fakeSource) so existing tests that rely on fakeSource NOT implementing
// BatchCommandAcker keep exercising the per-delivery ack path.
type fakeBatchAckSource struct {
	fakeSource
	ackBatchFn    func([]CommandDelivery) (int, error)
	ackBatchCalls int
}

func (s *fakeBatchAckSource) AckBatch(deliveries []CommandDelivery) (int, error) {
	s.ackBatchCalls++
	if s.ackBatchFn != nil {
		return s.ackBatchFn(deliveries)
	}
	return len(deliveries), nil
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
	ackErr  error
	nakErr  error
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
	if d.ackErr != nil {
		return d.ackErr
	}
	d.acked++
	return nil
}

func (d *fakeDelivery) Nak() error {
	if d.nakErr != nil {
		return d.nakErr
	}
	d.nacked++
	return nil
}

func (d *fakeDelivery) Term() error {
	d.termed++
	return nil
}

func TestProcessorRecordFailureIgnoresNilError(t *testing.T) {
	processor := NewProcessor(app.NewService(), &fakeSource{}, &fakePublisher{}, ProcessorConfig{})
	processor.recordFailure(nil)
	stats := processor.Stats()
	if stats.Failed != 0 || stats.LastError != "" {
		t.Fatalf("expected no-op for nil error, got %#v", stats)
	}
}

func TestProcessorRecordFailureTracksLastError(t *testing.T) {
	processor := NewProcessor(app.NewService(), &fakeSource{}, &fakePublisher{}, ProcessorConfig{})
	processor.recordFailure(errors.New("boom"))
	stats := processor.Stats()
	if stats.Failed != 1 || stats.LastError != "boom" {
		t.Fatalf("unexpected stats after recordFailure: %#v", stats)
	}
}

func TestProcessorNakAllRecordsFailureOnNakError(t *testing.T) {
	delivery := &fakeDelivery{subject: "reef.cmd.v1.p00.session.STK001.CancelOrder", seq: 1, nakErr: errors.New("nak failed")}
	processor := NewProcessor(app.NewService(), &fakeSource{}, &fakePublisher{}, ProcessorConfig{})

	processor.nakAll([]CommandDelivery{delivery})

	stats := processor.Stats()
	if stats.Nacked != 0 {
		t.Fatalf("expected no successful nacks, got %d", stats.Nacked)
	}
	if stats.Failed != 1 || stats.LastError != "nak failed" {
		t.Fatalf("expected recordFailure to capture nak error, got %#v", stats)
	}
}

func TestProcessorAckAllRecordsFailureOnAckError(t *testing.T) {
	delivery := &fakeDelivery{subject: "reef.cmd.v1.p00.session.STK001.CancelOrder", seq: 1, ackErr: errors.New("ack failed")}
	processor := NewProcessor(app.NewService(), &fakeSource{}, &fakePublisher{}, ProcessorConfig{})

	processor.ackAll([]CommandDelivery{delivery})

	stats := processor.Stats()
	if stats.Acked != 0 {
		t.Fatalf("expected no successful acks, got %d", stats.Acked)
	}
	if stats.Failed != 1 || stats.LastError != "ack failed" {
		t.Fatalf("expected recordFailure to capture ack error, got %#v", stats)
	}
	if stats.LastAckedAt != "" {
		t.Fatalf("expected LastAckedAt to remain unset when ack fails, got %q", stats.LastAckedAt)
	}
}

func TestProcessorAckAllUsesBatchAckerWhenAvailable(t *testing.T) {
	delivery := &fakeDelivery{subject: "reef.cmd.v1.p00.session.STK001.CancelOrder", seq: 1}
	source := &fakeBatchAckSource{}
	processor := NewProcessor(app.NewService(), source, &fakePublisher{}, ProcessorConfig{})

	processor.ackAll([]CommandDelivery{delivery})

	if source.ackBatchCalls != 1 {
		t.Fatalf("expected AckBatch to be called once, got %d", source.ackBatchCalls)
	}
	stats := processor.Stats()
	if stats.Acked != 1 {
		t.Fatalf("expected batch ack count to be reflected in stats, got %d", stats.Acked)
	}
	if stats.LastAckedAt == "" {
		t.Fatal("expected LastAckedAt to be set after batch ack")
	}
	// Per-delivery Ack() must not be called when a batch acker is used.
	if delivery.acked != 0 {
		t.Fatalf("expected delivery.Ack() not to be called when batch acker is present, got %d", delivery.acked)
	}
}

func TestProcessorAckAllBatchAckerPropagatesError(t *testing.T) {
	delivery := &fakeDelivery{subject: "reef.cmd.v1.p00.session.STK001.CancelOrder", seq: 1}
	source := &fakeBatchAckSource{
		ackBatchFn: func(deliveries []CommandDelivery) (int, error) {
			return 0, errors.New("batch ack failed")
		},
	}
	processor := NewProcessor(app.NewService(), source, &fakePublisher{}, ProcessorConfig{})

	processor.ackAll([]CommandDelivery{delivery})

	stats := processor.Stats()
	if stats.Acked != 0 {
		t.Fatalf("expected zero acked on batch failure, got %d", stats.Acked)
	}
	if stats.Failed != 1 || stats.LastError != "batch ack failed" {
		t.Fatalf("expected recordFailure to capture batch ack error, got %#v", stats)
	}
}

func TestProcessorRunProcessesUntilContextCancelled(t *testing.T) {
	delivery := newFakeDelivery("reef.cmd.v1.p00.session.STK001.CancelOrder", 1, map[string]string{
		"commandId": "cmd-run-1",
		"orderId":   "ord-run-1",
	})
	source := &fakeSource{deliveries: []CommandDelivery{delivery}}
	publisher := &fakePublisher{}
	processor := NewProcessor(app.NewService(), source, publisher, ProcessorConfig{
		PollInterval: time.Millisecond,
		BatchSize:    10,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()

	err := processor.Run(ctx)
	if err == nil {
		t.Fatal("expected Run to return an error when the context is done")
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected DeadlineExceeded, got %v", err)
	}
	if source.fetchCalls < 2 {
		t.Fatalf("expected Run to poll fetch multiple times (once with data, then empty), got %d calls", source.fetchCalls)
	}
	if len(publisher.batches) != 1 {
		t.Fatalf("expected the single delivery to be processed exactly once, got %d batches", len(publisher.batches))
	}
}

func TestProcessorRunReturnsImmediatelyWhenContextAlreadyCancelled(t *testing.T) {
	source := &fakeSource{}
	processor := NewProcessor(app.NewService(), source, &fakePublisher{}, ProcessorConfig{})

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	err := processor.Run(ctx)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}
	if source.fetchCalls != 0 {
		t.Fatalf("expected no fetch calls once context is already cancelled, got %d", source.fetchCalls)
	}
}

func TestProcessorRunRecordsFailureOnFetchErrorAndKeepsPolling(t *testing.T) {
	source := &fakeSource{fetchErr: errors.New("fetch failed")}
	processor := NewProcessor(app.NewService(), source, &fakePublisher{}, ProcessorConfig{
		PollInterval: time.Millisecond,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()

	err := processor.Run(ctx)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected DeadlineExceeded, got %v", err)
	}
	stats := processor.Stats()
	if stats.Failed == 0 {
		t.Fatal("expected fetch errors to be recorded as failures")
	}
	if stats.LastError != "fetch failed" {
		t.Fatalf("expected last error to be fetch failed, got %q", stats.LastError)
	}
}
